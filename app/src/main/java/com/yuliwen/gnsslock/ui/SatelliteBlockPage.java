package com.yuliwen.gnsslock.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.location.GnssStatus;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.yuliwen.gnsslock.BlockPolicy;

/** Multi-constellation SVID editor, independent of settings and root access. */
public final class SatelliteBlockPage extends LinearLayout {
    public interface Listener {
        void onDraftChanged();
        void onApply();
        void onRefresh();
    }

    private final Listener listener;
    private final LinearLayout editable;
    private final HorizontalScrollView providerScroll;
    private final TextView[] providerChips = new TextView[8];
    private final TextView dirtyBadge;
    private final TextView selectionCount;
    private final TextView warning;
    private final TextView feedback;
    private final TextView selectedColumn;
    private final Button visibleButton;
    private final Button refresh;
    private final Button apply;
    private final SatelliteAdapter adapter;
    private final ListView satelliteList;
    private final LinearLayout card;
    private final SwipeHost swipeHost;
    private final List<Integer> displayedSvids = new ArrayList<>();
    private final Map<Integer, Signal> signals = new HashMap<>();
    private BlockPolicy loaded = BlockPolicy.allowAll();
    private BlockPolicy draft = BlockPolicy.allowAll();
    private int constellation = 1;
    private int focusedSvid = -1;
    private boolean busy;
    private boolean whitelistMode;
    private boolean switching;
    private int transitionSerial;

    public SatelliteBlockPage(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(PolicyPageStyle.BACKGROUND);
        editable = PolicyPageStyle.column(context);
        editable.setPadding(dp(16), dp(8), dp(16), dp(10));
        addView(editable, new LayoutParams(-1, 0, 1));

        providerScroll = new HorizontalScrollView(context);
        providerScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout providers = PolicyPageStyle.row(context);
        for (int c = 1; c <= 7; c++) {
            final int selected = c;
            TextView chip = PolicyPageStyle.text(context, "", 12, PolicyPageStyle.BLUE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinWidth(dp(c == 3 ? 92 : 74));
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setOnClickListener(view -> selectConstellation(selected));
            LayoutParams params = new LayoutParams(-2, dp(58));
            if (c > 1) params.leftMargin = dp(7);
            providers.addView(chip, params);
            providerChips[c] = chip;
        }
        providerScroll.addView(providers);
        editable.addView(providerScroll, new LayoutParams(-1, dp(58)));

        card = PolicyPageStyle.card(context);
        LinearLayout summary = PolicyPageStyle.row(context);
        selectionCount = PolicyPageStyle.text(context, "", 15, PolicyPageStyle.INK, true);
        selectionCount.setTextSize(13);
        summary.addView(selectionCount, new LayoutParams(0, dp(38), 1));
        dirtyBadge = PolicyPageStyle.text(context, "未应用", 11, PolicyPageStyle.AMBER, true);
        dirtyBadge.setPadding(dp(8), dp(5), dp(8), dp(5));
        dirtyBadge.setBackground(PolicyPageStyle.background(context, 0xfffff2da, 10, 0));
        summary.addView(dirtyBadge);
        card.addView(summary);

        LinearLayout batches = PolicyPageStyle.row(context);
        Button all = PolicyPageStyle.button(context, "全选", false);
        Button invert = PolicyPageStyle.button(context, "反选", false);
        Button clear = PolicyPageStyle.button(context, "清空", false);
        visibleButton = PolicyPageStyle.button(context, "选可见", false);
        for (Button button : new Button[] {all, invert, clear, visibleButton}) {
            button.setTextSize(12);
            button.setPadding(dp(3), 0, dp(3), 0);
            addWeighted(batches, button, batches.getChildCount() == 0 ? 0 : 4);
        }
        all.setContentDescription("选中当前星座全部卫星");
        invert.setContentDescription("反转当前星座全部卫星的选择");
        clear.setContentDescription("取消当前星座全部选择");
        visibleButton.setContentDescription("选中当前星座当前可见的卫星");
        all.setOnClickListener(view -> setAllSelected(true));
        invert.setOnClickListener(view -> invertSelection());
        clear.setOnClickListener(view -> setAllSelected(false));
        visibleButton.setOnClickListener(view -> setSatellites(currentSignalSvids(false), true));
        PolicyPageStyle.add(card, batches, 2);

        LinearLayout columns = PolicyPageStyle.row(context);
        columns.setPadding(dp(8), 0, 0, 0);
        columns.addView(columnLabel("编号"), new LayoutParams(dp(50), dp(30)));
        columns.addView(columnLabel("信号"), new LayoutParams(dp(90), dp(30)));
        columns.addView(columnLabel("状态"), new LayoutParams(0, dp(30), 1));
        selectedColumn = columnLabel("锁定");
        selectedColumn.setGravity(Gravity.CENTER);
        columns.addView(selectedColumn, new LayoutParams(dp(48), dp(30)));
        card.addView(columns);
        satelliteList = new ListView(context);
        satelliteList.setDivider(new android.graphics.drawable.ColorDrawable(PolicyPageStyle.BORDER));
        satelliteList.setDividerHeight(dp(1));
        satelliteList.setCacheColorHint(Color.TRANSPARENT);
        satelliteList.setBackgroundColor(Color.TRANSPARENT);
        satelliteList.setFocusable(false);
        adapter = new SatelliteAdapter();
        satelliteList.setAdapter(adapter);
        satelliteList.setOnItemClickListener((parent, view, position, id) -> {
            if (busy || switching || !draft.isEditable()) return;
            int svid = displayedSvids.get(position);
            focusedSvid = svid;
            boolean selected = draft.isConstellationBlocked(constellation)
                    || draft.getBlockedSvids(constellation).contains(svid);
            change(draft.withSatelliteBlocked(constellation, svid, !selected));
        });
        card.addView(satelliteList, new LayoutParams(-1, 0, 1));
        swipeHost = new SwipeHost(context);
        swipeHost.addView(card, new FrameLayout.LayoutParams(-1, -1));
        LayoutParams cardParams = new LayoutParams(-1, 0, 1);
        cardParams.topMargin = dp(10);
        editable.addView(swipeHost, cardParams);
        warning = PolicyPageStyle.text(context, "", 13, PolicyPageStyle.AMBER, false);
        PolicyPageStyle.add(editable, warning, 8);

        LinearLayout footer = PolicyPageStyle.column(context);
        footer.setPadding(dp(16), dp(6), dp(16), dp(6));
        footer.setBackgroundColor(Color.WHITE);
        feedback = PolicyPageStyle.text(context, "", 13, PolicyPageStyle.MUTED, false);
        feedback.setPadding(dp(2), dp(3), dp(2), dp(4));
        footer.addView(feedback);
        LinearLayout actions = PolicyPageStyle.row(context);
        refresh = PolicyPageStyle.button(context, "重新读取", false);
        apply = PolicyPageStyle.button(context, "应用", true);
        addWeighted(actions, refresh, 0);
        addWeighted(actions, apply, 10);
        refresh.setOnClickListener(view -> listener.onRefresh());
        apply.setOnClickListener(view -> listener.onApply());
        footer.addView(actions);
        addView(footer, new LayoutParams(-1, -2));

        rebuildDisplayed();
        setFeedback("", false);
        renderPolicy();
    }

    private int dp(float value) { return PolicyPageStyle.dp(getContext(), value); }

    private TextView columnLabel(String value) {
        TextView label = PolicyPageStyle.text(getContext(), value, 11, PolicyPageStyle.MUTED, false);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private void addWeighted(LinearLayout row, View view, int gap) {
        LayoutParams params = new LayoutParams(0, dp(50), 1);
        params.leftMargin = dp(gap);
        row.addView(view, params);
    }

    public void loadPolicy(BlockPolicy policy) {
        loaded = policy == null ? BlockPolicy.allowAll() : policy;
        draft = loaded;
        setFeedback("", false);
        renderPolicy();
    }

    public BlockPolicy getDraft() { return draft; }
    public boolean isDirty() { return !draft.equals(loaded); }

    public void setWhitelistMode(boolean enabled) {
        if (whitelistMode == enabled) return;
        whitelistMode = enabled;
        renderPolicy();
    }

    public void saveState(Bundle state) {
        state.putString("loaded", loaded.serialize());
        state.putString("draft", draft.serialize());
        state.putInt("constellation", constellation);
        state.putInt("focusedSvid", focusedSvid);
        state.putParcelable("satelliteListState", satelliteList.onSaveInstanceState());
    }

    public void restoreState(Bundle state) {
        if (state == null) return;
        loaded = BlockPolicy.parse(state.getString("loaded"));
        draft = BlockPolicy.parse(state.getString("draft"));
        int saved = state.getInt("constellation", 1);
        constellation = saved >= 1 && saved <= 7 ? saved : 1;
        focusedSvid = state.getInt("focusedSvid", -1);
        cancelTransition();
        rebuildDisplayed();
        renderPolicy();
        android.os.Parcelable position = state.getParcelable("satelliteListState");
        if (position != null) satelliteList.post(() -> satelliteList.onRestoreInstanceState(position));
        scrollProviderIntoView();
    }

    public void setBusy(boolean value) {
        busy = value;
        PolicyPageStyle.setTreeEnabled(editable, !busy);
        refresh.setEnabled(!busy);
        apply.setEnabled(!busy && draft.isEditable() && isDirty());
        apply.setText(busy ? "处理中…" : "应用");
        updateSignalButtons();
    }

    public void setFeedback(String text, boolean error) {
        feedback.setText(text == null ? "" : text);
        feedback.setTextColor(error ? PolicyPageStyle.RED : PolicyPageStyle.MUTED);
        feedback.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    /** Update only signal metadata, leaving the draft, constellation and scroll position untouched. */
    public void setGnssStatus(GnssStatus status) {
        signals.clear();
        if (status != null) for (int i = 0; i < status.getSatelliteCount(); i++) {
            int c = status.getConstellationType(i);
            int svid = status.getSvid(i);
            if (c < 1 || c > 7 || !BlockPolicy.isValidSvid(c, svid)) continue;
            int key = key(c, svid);
            Signal signal = signals.get(key);
            if (signal == null) {
                signal = new Signal();
                signals.put(key, signal);
            }
            signal.cn0 = Math.max(signal.cn0, status.getCn0DbHz(i));
            signal.used |= status.usedInFix(i);
        }
        adapter.notifyDataSetChanged();
        updateSignalButtons();
        renderSelectionCount();
    }

    /** Open a satellite from the status page without changing any selection. */
    public void focusSatellite(int c, int svid) {
        if (c < 1 || c > 7 || !BlockPolicy.isValidSvid(c, svid)) return;
        cancelTransition();
        constellation = c;
        focusedSvid = svid;
        rebuildDisplayed();
        renderPolicy();
        satelliteList.setSelection(displayedSvids.indexOf(svid));
        scrollProviderIntoView();
    }

    private void selectConstellation(int c) {
        if (busy || switching || c < 1 || c > 7 || constellation == c) return;
        final int direction = c > constellation ? 1 : -1;
        final int serial = ++transitionSerial;
        final int width = swipeHost.getWidth();
        if (width <= 0 || !isAttachedToWindow()) {
            showConstellation(c);
            return;
        }
        switching = true;
        satelliteList.setEnabled(false);
        card.animate().translationX(-direction * width).alpha(.65f).setDuration(110)
                .withEndAction(() -> {
                    if (serial != transitionSerial) return;
                    showConstellation(c);
                    card.setTranslationX(direction * width);
                    card.animate().translationX(0).alpha(1).setDuration(160)
                            .withEndAction(() -> {
                                if (serial != transitionSerial) return;
                                switching = false;
                                satelliteList.setEnabled(!busy);
                            }).start();
                }).start();
    }

    private void showConstellation(int c) {
        constellation = c;
        focusedSvid = -1;
        rebuildDisplayed();
        renderPolicy();
        satelliteList.setSelection(0);
        scrollProviderIntoView();
    }

    private void cancelTransition() {
        transitionSerial++;
        card.animate().cancel();
        card.setTranslationX(0);
        card.setAlpha(1);
        switching = false;
        satelliteList.setEnabled(!busy);
    }

    @Override protected void onDetachedFromWindow() {
        cancelTransition();
        super.onDetachedFromWindow();
    }

    private void scrollProviderIntoView() {
        TextView selected = providerChips[constellation];
        providerScroll.post(() -> providerScroll.smoothScrollTo(Math.max(0,
                selected.getLeft() - (providerScroll.getWidth() - selected.getWidth()) / 2), 0));
    }

    private void change(BlockPolicy policy) {
        draft = policy;
        setFeedback("", false);
        renderPolicy();
        listener.onDraftChanged();
    }

    private void setAllSelected(boolean selected) {
        if (busy || switching || !draft.isEditable()) return;
        boolean blocked = whitelistMode ? !selected : selected;
        change(draft.withBlockedSvids(constellation, Collections.emptySet())
                .withConstellationBlocked(constellation, blocked));
    }

    private void setSatellites(Collection<Integer> svids, boolean selected) {
        if (busy || switching || !draft.isEditable() || svids.isEmpty()) return;
        boolean blocked = whitelistMode ? !selected : selected;
        BlockPolicy next = draft;
        for (Integer svid : new ArrayList<>(svids)) next = next.withSatelliteBlocked(constellation, svid, blocked);
        change(next);
    }

    private void invertSelection() {
        if (busy || switching || !draft.isEditable()) return;
        Set<Integer> blocked = new TreeSet<>();
        for (Integer svid : BlockPolicy.validSvids(constellation)) {
            if (!draft.isSatelliteBlocked(constellation, svid)) blocked.add(svid);
        }
        BlockPolicy next = draft.withBlockedSvids(constellation, blocked);
        if (blocked.size() == BlockPolicy.validSvids(constellation).size()) {
            next = next.withBlockedSvids(constellation, Collections.emptySet())
                    .withConstellationBlocked(constellation, true);
        }
        change(next);
    }

    private void renderPolicy() {
        for (int c = 1; c <= 7; c++) {
            int count = draft.isConstellationBlocked(c) ? BlockPolicy.validSvids(c).size()
                    : draft.getBlockedSvids(c).size();
            int selected = whitelistMode ? BlockPolicy.validSvids(c).size() - count : count;
            providerChips[c].setText(PolicyPageStyle.NAMES[c] + "\n" + selected + " 颗");
            providerChips[c].setTextColor(c == constellation ? Color.WHITE : PolicyPageStyle.BLUE);
            providerChips[c].setBackground(PolicyPageStyle.background(getContext(),
                    c == constellation ? PolicyPageStyle.BLUE : Color.WHITE, 14,
                    c == constellation ? 0 : PolicyPageStyle.BORDER));
            providerChips[c].setContentDescription(PolicyPageStyle.NAMES[c] + "，已选 " + selected + " 颗");
        }
        selectedColumn.setText("锁定");
        dirtyBadge.setVisibility(isDirty() ? VISIBLE : GONE);
        boolean unrecognized = draft.malformed() || !draft.unknownEntries().isEmpty() || !draft.isEditable();
        warning.setVisibility(unrecognized ? VISIBLE : GONE);
        warning.setText(!draft.isEditable() ? "配置过大，当前仅供查看" : draft.malformed() ? "配置含损坏条目，请检查原始配置" : "未识别条目会保留");
        apply.setEnabled(!busy && draft.isEditable() && isDirty());
        renderSelectionCount();
        updateSignalButtons();
        adapter.notifyDataSetChanged();
    }

    private void renderSelectionCount() {
        int count = draft.isConstellationBlocked(constellation) ? BlockPolicy.validSvids(constellation).size()
                : draft.getBlockedSvids(constellation).size();
        int selected = whitelistMode ? BlockPolicy.validSvids(constellation).size() - count : count;
        selectionCount.setText(PolicyPageStyle.NAMES[constellation] + "  ·  已选 " + selected
                + " / " + BlockPolicy.validSvids(constellation).size()
                + "    可见 " + currentSignalSvids(false).size());
    }

    private Set<Integer> currentSignalSvids(boolean onlyUsed) {
        Set<Integer> result = new TreeSet<>();
        for (Integer svid : BlockPolicy.validSvids(constellation)) {
            Signal signal = signals.get(key(constellation, svid));
            if (signal != null && (!onlyUsed || signal.used)) result.add(svid);
        }
        return result;
    }

    private void updateSignalButtons() {
        visibleButton.setEnabled(!busy && draft.isEditable() && !currentSignalSvids(false).isEmpty());
    }

    private void rebuildDisplayed() {
        displayedSvids.clear();
        displayedSvids.addAll(BlockPolicy.validSvids(constellation));
        adapter.notifyDataSetChanged();
        satelliteList.setSelection(0);
    }

    /** Claims horizontal gestures before ListView can turn the release into a row click. */
    private final class SwipeHost extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private boolean horizontal, vertical;

        SwipeHost(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(true);
        }

        private void inspectDrag(MotionEvent event) {
            if (vertical || busy) return;
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (dx > slop && dx > dy * 1.4f) {
                horizontal = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (dy > slop && dy > dx) {
                vertical = true;
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();downY = event.getY();
                horizontal = false;vertical = false;
                return switching;
            }
            if (switching) return true;
            if (action == MotionEvent.ACTION_POINTER_DOWN) vertical = true;
            if (action == MotionEvent.ACTION_MOVE) inspectDrag(event);
            return horizontal;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (switching) return true;
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();downY = event.getY();
                horizontal = false;vertical = false;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                horizontal = false;vertical = true;
            }
            if (action == MotionEvent.ACTION_MOVE) inspectDrag(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float dx = event.getX() - downX, dy = Math.abs(event.getY() - downY);
                boolean change = horizontal && !busy && action == MotionEvent.ACTION_UP
                        && Math.abs(dx) >= dp(48) && Math.abs(dx) > dy * 1.4f;
                horizontal = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (change) selectConstellation(Math.max(1, Math.min(7,
                        constellation + (dx < 0 ? 1 : -1))));
                return true;
            }
            return true;
        }
    }

    private static int key(int constellation, int svid) { return constellation * 10000 + svid; }

    private static final class Signal {
        float cn0;
        boolean used;
    }

    private final class SatelliteAdapter extends BaseAdapter {
        @Override public int getCount() { return displayedSvids.size(); }
        @Override public Object getItem(int position) { return displayedSvids.get(position); }
        @Override public long getItemId(int position) { return key(constellation, displayedSvids.get(position)); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SatelliteRow row;
            if (convertView == null) {
                LinearLayout content = PolicyPageStyle.row(getContext());
                content.setPadding(dp(8), 0, 0, 0);
                content.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(56)));
                TextView number = PolicyPageStyle.text(getContext(), "", 15, PolicyPageStyle.INK, true);
                SignalBar strength = new SignalBar(getContext());
                TextView state = PolicyPageStyle.text(getContext(), "", 12, PolicyPageStyle.MUTED, false);
                number.setSingleLine(true);state.setSingleLine(true);
                number.setGravity(Gravity.CENTER);state.setGravity(Gravity.CENTER);
                content.addView(number, new LayoutParams(dp(50), -1));
                content.addView(strength, new LayoutParams(dp(90), -1));
                content.addView(state, new LayoutParams(0, -1, 1));
                CheckBox selected = new CheckBox(getContext());
                selected.setClickable(false);selected.setFocusable(false);
                selected.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                selected.setGravity(Gravity.CENTER);
                selected.setPadding(0, 0, 0, 0);
                selected.setMinWidth(0);selected.setMinimumWidth(0);
                selected.setButtonTintList(ColorStateList.valueOf(PolicyPageStyle.BLUE));
                FrameLayout checkboxCell = new FrameLayout(getContext());
                checkboxCell.addView(selected, new FrameLayout.LayoutParams(dp(32), dp(48), Gravity.CENTER));
                content.addView(checkboxCell, new LayoutParams(dp(48), dp(48)));
                row = new SatelliteRow(number, strength, state, selected);
                content.setTag(row);convertView = content;
            } else row = (SatelliteRow) convertView.getTag();
            int svid = displayedSvids.get(position);
            Signal signal = signals.get(key(constellation, svid));
            boolean blocked = draft.isConstellationBlocked(constellation)
                    || draft.getBlockedSvids(constellation).contains(svid);
            boolean selected = whitelistMode ? !blocked : blocked;
            String number = String.format(Locale.US, "%02d", svid);
            float cn0 = signal == null ? 0 : signal.cn0;
            String strength = String.format(Locale.US, "%.1f dB-Hz", cn0);
            String state = signal == null ? "无信号" : signal.used ? "参与定位" : cn0 > 0 ? "可见" : "无信号";
            row.number.setText(number);row.strength.setSignal(cn0, signal != null && signal.used);row.state.setText(state);
            row.number.setTextColor(selected ? PolicyPageStyle.BLUE : PolicyPageStyle.INK);
            row.state.setTextColor(signal != null && signal.used ? 0xff247b60 : PolicyPageStyle.MUTED);
            row.selected.setChecked(selected);row.selected.setEnabled(!busy && draft.isEditable());
            convertView.setBackground(PolicyPageStyle.background(getContext(), selected ? PolicyPageStyle.PALE : Color.WHITE,
                    7, focusedSvid == svid ? PolicyPageStyle.BLUE : 0));
            convertView.setContentDescription(PolicyPageStyle.NAMES[constellation] + " 卫星 " + svid + "，"
                    + (selected ? "已选" : "未选") + "，" + (blocked ? "受限" : "可用") + "，" + state + "，" + strength);
            convertView.setEnabled(!busy && draft.isEditable());
            return convertView;
        }
    }

    private static final class SatelliteRow {
        final TextView number, state;
        final SignalBar strength;
        final CheckBox selected;
        SatelliteRow(TextView number, SignalBar strength, TextView state, CheckBox selected) {
            this.number = number;this.strength = strength;this.state = state;this.selected = selected;
        }
    }

    private final class SignalBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cn0;
        private boolean used;
        SignalBar(Context context) { super(context); }
        void setSignal(float value, boolean inFix) {
            cn0 = Float.isFinite(value) ? Math.max(0, value) : 0;
            used = inFix;
            setContentDescription(String.format(Locale.US, "信号 %.1f dB-Hz", cn0));
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float left = dp(5), right = getWidth() - dp(5), top = (getHeight() - dp(22)) / 2f, bottom = top + dp(22);
            paint.setColor(PolicyPageStyle.BACKGROUND);
            canvas.drawRoundRect(left, top, right, bottom, dp(5), dp(5), paint);
            float end = left + (right - left) * Math.min(1, cn0 / 60f);
            if (end > left) {
                int saved = canvas.save();canvas.clipRect(left, top, end, bottom);
                paint.setColor(used ? 0xffb6e2d3 : 0xffbbd2f8);
                canvas.drawRoundRect(left, top, right, bottom, dp(5), dp(5), paint);canvas.restoreToCount(saved);
            }
            paint.setColor(PolicyPageStyle.INK);paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format(Locale.US, "%.1f dB-Hz", cn0), (left + right) / 2f,
                    (top + bottom) / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
        }
    }
}
