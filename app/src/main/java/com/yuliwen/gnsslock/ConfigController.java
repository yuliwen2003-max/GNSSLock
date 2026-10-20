package com.yuliwen.gnsslock;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AtomicFile;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retained application-context controller: UI pages never write configuration. */
final class ConfigController {
    interface Listener { void onChanged(); void onFinished(Outcome outcome); }
    enum Kind { APPLY, ROOT_STATUS, ROOT_DISABLE, RESTORE }
    static final class Snapshot {
        final boolean exists;
        final String raw;
        final BlockPolicy policy;
        Snapshot(boolean exists, String raw) {
            this.exists = exists; this.raw = raw; policy = BlockPolicy.parse(raw);
        }
        boolean sameRaw(Snapshot other) {
            return other != null && exists == other.exists
                    && (raw == null ? other.raw == null : raw.equals(other.raw));
        }
    }
    static final class RootInfo {
        boolean checked, available, verified, excluded;
        String state = "unknown", mask = "—", error = "";
        long pid, checkedAt;
    }
    static final class Outcome {
        final long id;
        final Kind kind;
        final boolean success;
        final String message;
        final Snapshot snapshot;
        Outcome(long id, Kind kind, boolean success, String message, Snapshot snapshot) {
            this.id = id; this.kind = kind; this.success = success;
            this.message = message; this.snapshot = snapshot;
        }
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RootCommandRunner root = new RootCommandRunner();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean busy, disposeWhenIdle;
    private long sequence;
    private volatile Snapshot snapshot;
    private String readError = "";
    private volatile RootInfo rootInfo = new RootInfo();
    private Outcome lastOutcome;

    ConfigController(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("gpslock", Context.MODE_PRIVATE);
        refresh();
    }
    void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    boolean isBusy() { return busy; }
    Snapshot snapshot() { return snapshot; }
    String readError() { return readError; }
    RootInfo rootInfo() { return rootInfo; }
    Outcome lastOutcome() { return lastOutcome; }
    boolean compatibility() {
        return prefs.getBoolean("bds_append_enabled", prefs.getBoolean("root_mode_selected", false));
    }
    void setCompatibility(boolean enabled) {
        prefs.edit().putBoolean("bds_append_enabled", enabled).apply(); notifyChanged();
    }
    boolean whitelistMode() { return prefs.getBoolean("whitelist_mode", false); }
    void setWhitelistMode(boolean enabled) {
        if (busy || whitelistMode() == enabled) return;
        prefs.edit().putBoolean("whitelist_mode", enabled).apply(); notifyChanged();
    }
    boolean canWrite() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    Snapshot backup() {
        if (!prefs.getBoolean("backup_saved", false)) return null;
        return new Snapshot(prefs.getBoolean("original_exists", false),
                prefs.getBoolean("original_is_null", true) ? null : prefs.getString("original_value", ""));
    }
    boolean refresh() {
        if (busy) return false; // Never publish intermediate low/high masks to editors.
        try { snapshot = read(); readError = ""; }
        catch (Exception error) { readError = message(error); }
        notifyChanged();
        return readError.isEmpty();
    }
    Snapshot read() {
        try (Cursor cursor = context.getContentResolver().query(Settings.Global.getUriFor(GnssPolicy.KEY),
                new String[]{"name", "value"}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("无法读取系统名单");
            if (!cursor.moveToFirst()) return new Snapshot(false, null);
            int value = cursor.getColumnIndexOrThrow("value");
            return new Snapshot(true, cursor.isNull(value) ? null : cursor.getString(value));
        }
    }
    void apply(BlockPolicy desired, Snapshot expected) {
        final boolean useRoot = compatibility();
        submit(Kind.APPLY, () -> {
            if (!desired.isEditable() || desired.malformed()) throw new IllegalStateException("配置包含损坏条目，请先检查原始配置");
            Snapshot before = read();
            if (expected != null && !before.policy.equals(expected.policy))
                throw new IllegalStateException("系统名单已更新，请重新读取后再应用");
            saveBackup(before);
            String raw = desired.serialize();
            if (useRoot) runRootApply(raw, desired);
            else write(raw);
            Snapshot after = read();
            if (!after.policy.equals(desired)) throw new IllegalStateException("系统名单读回与请求不一致");
            if (!useRoot) rootInfo = new RootInfo();
            return useRoot ? "已应用，北斗名单已核验" : desired.requiresBdsAppend()
                    ? "已提交；本机标准方式仍会保留北斗" : "已提交系统名单";
        });
    }
    void inspectRoot() {
        submit(Kind.ROOT_STATUS, () -> { runRoot(RootCommandRunner.Action.STATUS, null); return "状态已更新"; });
    }
    void stopRoot() {
        submit(Kind.ROOT_DISABLE, () -> {
            saveBackup(read());
            runRoot(RootCommandRunner.Action.DISABLE, null);
            prefs.edit().putBoolean("bds_append_enabled", false).putBoolean("root_mode_selected", false).commit();
            return "已停止追加策略";
        });
    }
    void restore() {
        final Snapshot original = backup();
        final boolean useRoot = compatibility();
        if (original == null) return;
        submit(Kind.RESTORE, () -> {
            if (useRoot) {
                if (original.policy.malformed()) throw new IllegalStateException("异常原值请关闭追加策略后恢复");
                runRootApply(original.policy.serialize(), original.policy);
                // Empty/absent values can be restored exactly after modem mask=0.
                if (!original.exists) delete();
                else if (original.raw == null || original.raw.trim().isEmpty()) writeExact(original.raw);
            } else {
                if (original.exists) writeExact(original.raw); else delete();
            }
            Snapshot after = read();
            if (after.sameRaw(original)) {
                prefs.edit().remove("backup_saved").remove("original_exists").remove("original_is_null")
                        .remove("original_value").remove("backup_time").commit();
                return "已恢复原配置";
            }
            if (!after.policy.equals(original.policy)) throw new IllegalStateException("恢复名单读回不一致，备份已保留");
            return "已恢复锁定配置，原始备份已保留";
        });
    }
    private void runRootApply(String raw, BlockPolicy desired) throws Exception {
        if (!raw.matches("[0-9,\\s]*")) throw new IllegalStateException("追加策略仅支持数字名单");
        AtomicFile pending = new AtomicFile(new File(context.getFilesDir(), "pending_policy.txt"));
        FileOutputStream stream = null;
        try {
            stream = pending.startWrite(); stream.write(raw.getBytes(StandardCharsets.US_ASCII));
            pending.finishWrite(stream); stream = null;
        } catch (Exception error) { if (stream != null) pending.failWrite(stream); throw error; }
        runRoot(RootCommandRunner.Action.APPLY, desired);
    }
    private void runRoot(RootCommandRunner.Action action, BlockPolicy desired) throws Exception {
        RootCommandRunner.Result result = root.run(action);
        RootInfo info = new RootInfo(); info.checked = true; info.checkedAt = System.currentTimeMillis();
        JSONObject json = null;
        try {
            JSONTokener tokener = new JSONTokener(result.output.trim());
            Object object = tokener.nextValue();
            if (object instanceof JSONObject && tokener.nextClean() == 0) json = (JSONObject)object;
        } catch (Exception ignored) { }
        if (json != null) {
            info.available = !"root_required".equals(json.optString("error"));
            info.state = json.optString("state", "unknown"); info.pid = json.optLong("pid");
            info.mask = json.optString("bds_mask", "unknown"); info.excluded = json.optBoolean("bds_excluded");
            info.verified = json.optBoolean("modem_verified"); info.error = json.optString("error");
        }
        rootInfo = info;
        if (result.timedOut || result.error != null || result.truncated || result.exitCode != 0 || json == null || !json.optBoolean("ok")) {
            String reason = !info.error.isEmpty() ? info.error : result.error;
            if (reason == null || reason.isEmpty()) reason = result.timedOut ? "Root 操作超时" : "请检查 Root 授权和配套组件";
            info.error = reason;
            throw new IllegalStateException(reason);
        }
        if (!"bds_sv_append".equals(json.optString("strategy")) || info.pid <= 0
                || !("enabled".equals(info.state) || "stock".equals(info.state)) || !info.verified
                || !info.mask.matches("0x[0-9a-fA-F]{16}"))
            throw new IllegalStateException("接收机返回状态未通过核验");
        long actualMask = Long.parseUnsignedLong(info.mask.substring(2), 16);
        if (info.excluded != (actualMask == Long.MAX_VALUE)) throw new IllegalStateException("北斗状态不一致");
        if (desired != null) {
            long expectedMask = desired.isConstellationBlocked(5) ? Long.MAX_VALUE : 0;
            for (int svid : desired.getBlockedSvids(5)) {
                if (svid >= 1 && svid <= 63) expectedMask |= 1L << (svid - 1);
            }
            if (!"enabled".equals(info.state) || actualMask != expectedMask)
                throw new IllegalStateException("北斗名单与本次选择不一致");
        }
        if (action == RootCommandRunner.Action.DISABLE && (!"stock".equals(info.state) || actualMask != 0))
            throw new IllegalStateException("追加策略未完整关闭");
    }
    private void write(String raw) {
        Snapshot before = read();
        String submitted = raw.equals(before.raw) ? raw + " " : raw;
        writeExact(submitted);
    }
    private void writeExact(String raw) {
        if (!canWrite()) throw new IllegalStateException("缺少系统写入权限，可在高级页查看授权方式");
        if (!Settings.Global.putString(context.getContentResolver(), GnssPolicy.KEY, raw))
            throw new IllegalStateException("系统拒绝写入名单");
        if (!read().sameRaw(new Snapshot(true, raw))) throw new IllegalStateException("系统名单读回失败");
    }
    private void delete() {
        if (!canWrite()) throw new IllegalStateException("缺少系统写入权限");
        context.getContentResolver().delete(Settings.Global.getUriFor(GnssPolicy.KEY), null, null);
        if (read().exists) throw new IllegalStateException("原配置恢复失败");
    }
    private void saveBackup(Snapshot before) {
        if (prefs.getBoolean("backup_saved", false)) return;
        if (!prefs.edit().putBoolean("backup_saved", true).putBoolean("original_exists", before.exists)
                .putBoolean("original_is_null", before.raw == null).putString("original_value", before.raw)
                .putLong("backup_time", System.currentTimeMillis()).commit())
            throw new IllegalStateException("原配置备份失败");
    }
    interface Work { String run() throws Exception; }
    private void submit(Kind kind, Work work) {
        if (busy) return;
        busy = true; notifyChanged();
        final long id = ++sequence;
        executor.execute(() -> {
            boolean success; String text;
            try { text = work.run(); success = true; }
            catch (Exception error) { text = message(error); success = false; }
            Snapshot latest = snapshot; String latestError = "";
            try { latest = read(); } catch (Exception error) { latestError = message(error); }
            Outcome outcome = new Outcome(id, kind, success, text, latest);
            final String finalReadError = latestError;
            main.post(() -> {
                snapshot = outcome.snapshot; readError = finalReadError; busy = false; lastOutcome = outcome;
                notifyChanged();
                for (Listener listener : new ArrayList<>(listeners)) listener.onFinished(outcome);
                if (disposeWhenIdle) executor.shutdown();
            });
        });
    }
    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onChanged();
    }
    void releaseWhenIdle() { disposeWhenIdle = true; if (!busy) executor.shutdown(); }
    static String message(Exception error) {
        String text = error.getMessage(); return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }
}
