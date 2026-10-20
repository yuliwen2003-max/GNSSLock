package com.yuliwen.gnsslock.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, shared native view styling for the policy editors. */
final class PolicyPageStyle {
    static final int BLUE = Color.rgb(38, 87, 171);
    static final int INK = Color.rgb(27, 43, 65);
    static final int MUTED = Color.rgb(101, 117, 139);
    static final int PALE = Color.rgb(235, 243, 255);
    static final int BACKGROUND = Color.rgb(244, 247, 252);
    static final int BORDER = Color.rgb(221, 229, 241);
    static final int RED = Color.rgb(174, 48, 51);
    static final int AMBER = Color.rgb(152, 91, 13);
    static final String[] NAMES = {"未知", "GPS", "SBAS", "GLONASS", "QZSS", "北斗", "Galileo", "NavIC"};

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(Context c, int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radius));
        if (stroke != 0) drawable.setStroke(dp(c, 1), stroke);
        return drawable;
    }

    static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static Button button(Context c, String value, boolean primary) {
        Button view = new Button(c) {
            @Override public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1f : .45f);
            }
        };
        view.setText(value);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTextColor(primary ? Color.WHITE : BLUE);
        view.setBackgroundTintList(ColorStateList.valueOf(primary ? BLUE : PALE));
        view.setMinHeight(dp(c, 48));
        view.setMinimumHeight(dp(c, 48));
        view.setPadding(dp(c, 10), dp(c, 2), dp(c, 10), dp(c, 2));
        return view;
    }

    static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout card(Context c) {
        LinearLayout layout = column(c);
        layout.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        layout.setBackground(background(c, Color.WHITE, 18, BORDER));
        return layout;
    }

    static void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), topDp);
        parent.addView(child, params);
    }

    static void setTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setTreeEnabled(group.getChildAt(i), enabled);
        }
    }

    private PolicyPageStyle() { }
}
