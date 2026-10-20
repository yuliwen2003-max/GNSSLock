package com.yuliwen.gnsslock.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.yuliwen.gnsslock.BlockPolicy;

/** Constellation-wide blocklist editor. It never writes settings itself. */
public final class GeneralBlockPage extends LinearLayout {
    public interface Listener {
        void onDraftChanged();
        void onApply();
        void onRefresh();
    }

    private final Listener listener;
    private final Switch[] switches = new Switch[8];
    private final TextView[] details = new TextView[8];
    private final LinearLayout editable;
    private final TextView dirtyBadge;
    private final TextView feedback;
    private final TextView warning;
    private final TextView detailLink;
    private final Button apply;
    private final Button refresh;
    private final Map<Integer, Set<Integer>> partialBeforeWhole = new HashMap<>();
    private BlockPolicy loaded = BlockPolicy.allowAll();
    private BlockPolicy draft = BlockPolicy.allowAll();
    private boolean rendering;
    private boolean busy;
    private boolean whitelistMode;

    public GeneralBlockPage(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(PolicyPageStyle.BACKGROUND);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        editable = PolicyPageStyle.column(context);
        editable.setPadding(dp(16), dp(6), dp(16), dp(16));
        scroll.addView(editable);
        addView(scroll, new LayoutParams(-1, 0, 1));

        LinearLayout card = PolicyPageStyle.card(context);
        for (int c = 1; c <= 7; c++) {
            final int constellation = c;
            LinearLayout row = PolicyPageStyle.row(context);
            row.setMinimumHeight(dp(66));
            LinearLayout label = PolicyPageStyle.column(context);
            TextView name = PolicyPageStyle.text(context, PolicyPageStyle.NAMES[c], 17, PolicyPageStyle.INK, true);
            details[c] = PolicyPageStyle.text(context, "可用", 12, PolicyPageStyle.MUTED, false);
            label.addView(name);
            label.addView(details[c]);
            row.addView(label, new LayoutParams(0, -2, 1));
            Switch toggle = new Switch(context);
            toggle.setText("锁定");
            toggle.setTextSize(13);
            toggle.setTextColor(PolicyPageStyle.MUTED);
            toggle.setSwitchPadding(dp(9));
            toggle.setThumbTintList(new ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                    new int[] {PolicyPageStyle.BLUE, 0xff8a9bb1}));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                    new int[] {0xffb8cff3, 0xffdce4ee}));
            toggle.setMinimumHeight(dp(48));
            toggle.setContentDescription("锁定 " + PolicyPageStyle.NAMES[c]);
            switches[c] = toggle;
            row.addView(toggle, new LayoutParams(-2, dp(52)));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (rendering || busy || !draft.isEditable()) return;
                boolean blocked = whitelistMode ? !checked : checked;
                if (blocked) {
                    partialBeforeWhole.put(constellation, new TreeSet<>(draft.getBlockedSvids(constellation)));
                    change(draft.withConstellationBlocked(constellation, true));
                } else {
                    BlockPolicy next = draft.withConstellationBlocked(constellation, false);
                    Set<Integer> previous = partialBeforeWhole.get(constellation);
                    if (previous != null) next = next.withBlockedSvids(constellation, previous);
                    change(next);
                }
            });
            row.setOnClickListener(view -> { if (!busy) toggle.setChecked(!toggle.isChecked()); });
            card.addView(row);
            if (c < 7) {
                android.view.View divider = new android.view.View(context);
                divider.setBackgroundColor(PolicyPageStyle.BORDER);
                card.addView(divider, new LayoutParams(-1, dp(1)));
            }
        }
        PolicyPageStyle.add(editable, card, 0);
        detailLink = PolicyPageStyle.text(context, "", 13, PolicyPageStyle.BLUE, false);
        detailLink.setMinHeight(dp(48));
        detailLink.setOnClickListener(view -> showDetails());
        PolicyPageStyle.add(editable, detailLink, 4);
        warning = PolicyPageStyle.text(context, "", 13, PolicyPageStyle.AMBER, false);
        PolicyPageStyle.add(editable, warning, 0);

        LinearLayout footer = PolicyPageStyle.column(context);
        footer.setPadding(dp(16), dp(6), dp(16), dp(6));
        footer.setBackgroundColor(Color.WHITE);
        LinearLayout feedbackRow = PolicyPageStyle.row(context);
        feedbackRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        feedback = PolicyPageStyle.text(context, "", 13, PolicyPageStyle.MUTED, false);
        feedback.setPadding(dp(2), dp(3), dp(2), dp(4));
        feedback.setVisibility(GONE);
        feedbackRow.addView(feedback, new LayoutParams(0, -2, 1));
        dirtyBadge = PolicyPageStyle.text(context, "未应用", 12, PolicyPageStyle.AMBER, true);
        dirtyBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        dirtyBadge.setBackground(PolicyPageStyle.background(context, 0xfffff2da, 12, 0));
        feedbackRow.addView(dirtyBadge);
        footer.addView(feedbackRow);
        LinearLayout actions = PolicyPageStyle.row(context);
        refresh = PolicyPageStyle.button(context, "重新读取", false);
        apply = PolicyPageStyle.button(context, "应用", true);
        LayoutParams left = new LayoutParams(0, dp(52), 1);
        left.rightMargin = dp(10);
        actions.addView(refresh, left);
        actions.addView(apply, new LayoutParams(0, dp(52), 1));
        refresh.setOnClickListener(view -> listener.onRefresh());
        apply.setOnClickListener(view -> listener.onApply());
        footer.addView(actions);
        addView(footer, new LayoutParams(-1, -2));
        render();
    }

    private int dp(float value) { return PolicyPageStyle.dp(getContext(), value); }

    public void loadPolicy(BlockPolicy policy) {
        loaded = policy == null ? BlockPolicy.allowAll() : policy;
        draft = loaded;
        partialBeforeWhole.clear();
        setFeedback("", false);
        render();
    }

    public BlockPolicy getDraft() { return draft; }
    public boolean isDirty() { return !draft.equals(loaded); }

    public void setWhitelistMode(boolean value) {
        if (whitelistMode == value) return;
        whitelistMode = value;
        render();
    }

    public void saveState(Bundle state) {
        state.putString("loaded", loaded.serialize());
        state.putString("draft", draft.serialize());
        for (Map.Entry<Integer, Set<Integer>> entry : partialBeforeWhole.entrySet()) {
            int[] svids = new int[entry.getValue().size()];
            int i = 0;
            for (Integer svid : entry.getValue()) svids[i++] = svid;
            state.putIntArray("previous_" + entry.getKey(), svids);
        }
    }

    public void restoreState(Bundle state) {
        if (state == null) return;
        loaded = BlockPolicy.parse(state.getString("loaded"));
        draft = BlockPolicy.parse(state.getString("draft"));
        partialBeforeWhole.clear();
        for (int c = 1; c <= 7; c++) {
            int[] svids = state.getIntArray("previous_" + c);
            if (svids != null) {
                Set<Integer> values = new TreeSet<>();
                for (int svid : svids) values.add(svid);
                partialBeforeWhole.put(c, values);
            }
        }
        render();
    }

    public void setBusy(boolean value) {
        busy = value;
        PolicyPageStyle.setTreeEnabled(editable, !busy && draft.isEditable());
        refresh.setEnabled(!busy);
        apply.setEnabled(!busy && draft.isEditable() && isDirty());
        apply.setText(busy ? "处理中…" : "应用");
    }

    public void setFeedback(String text, boolean error) {
        feedback.setText(text == null ? "" : text);
        feedback.setTextColor(error ? PolicyPageStyle.RED : PolicyPageStyle.MUTED);
        feedback.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    private void change(BlockPolicy policy) {
        draft = policy;
        setFeedback("", false);
        render();
        listener.onDraftChanged();
    }

    private void render() {
        rendering = true;
        int partial = 0;
        for (int c = 1; c <= 7; c++) {
            boolean whole = draft.isConstellationBlocked(c);
            int count = draft.getBlockedSvids(c).size();
            switches[c].setText("锁定");
            switches[c].setContentDescription("锁定 " + PolicyPageStyle.NAMES[c]);
            switches[c].setChecked(whitelistMode ? !whole : whole);
            details[c].setText(whole ? "已限制" : count == 0 ? "可用" : count + " 颗受限");
            details[c].setTextColor(whole ? PolicyPageStyle.BLUE : PolicyPageStyle.MUTED);
            if (!whole) partial += count;
        }
        rendering = false;
        dirtyBadge.setVisibility(isDirty() ? VISIBLE : GONE);
        detailLink.setText("逐星锁定 " + partial + " 颗  ›");
        detailLink.setVisibility(partial > 0 ? VISIBLE : GONE);
        boolean unrecognized = draft.malformed() || !draft.unknownEntries().isEmpty() || !draft.isEditable();
        warning.setVisibility(unrecognized ? VISIBLE : GONE);
        warning.setText(!draft.isEditable() ? "配置过大，当前仅供查看" : draft.malformed() ? "配置含损坏条目，请检查原始配置" : "未识别条目会保留");
        PolicyPageStyle.setTreeEnabled(editable, !busy && draft.isEditable());
        apply.setEnabled(!busy && draft.isEditable() && isDirty());
    }

    private void showDetails() {
        StringBuilder text = new StringBuilder();
        for (int c = 1; c <= 7; c++) {
            if (draft.isConstellationBlocked(c) || draft.getBlockedSvids(c).isEmpty()) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(PolicyPageStyle.NAMES[c]).append("\n");
            boolean first = true;
            for (Integer svid : draft.getBlockedSvids(c)) {
                if (!first) text.append("、");
                text.append(svid);
                first = false;
            }
        }
        new AlertDialog.Builder(getContext()).setTitle("逐星锁定")
                .setMessage(text.length() == 0 ? "无" : text.toString())
                .setPositiveButton("完成", null).show();
    }
}
