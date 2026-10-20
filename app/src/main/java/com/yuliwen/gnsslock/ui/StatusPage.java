package com.yuliwen.gnsslock.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Foreground GNSS observations. Coordinates live only in this view's memory. */
public final class StatusPage extends LinearLayout {
    public interface SatelliteAction {
        void onSatellite(int constellation, int svid);
    }
    public interface PageListener {
        void onPageChanged(String title);
    }

    private static final int INK = Color.rgb(27, 42, 63);
    private static final int MUTED = Color.rgb(103, 117, 137);
    private static final int BLUE = Color.rgb(36, 91, 177);
    private static final int BG = Color.rgb(244, 247, 251);
    private static final int LINE = Color.rgb(228, 234, 243);
    private static final long FRESH_MILLIS = 10000L;
    private static final int[] PROVIDERS = {0, 1, 5, 6, 3, 4, 2, 7};
    private static final int[] OVERVIEW = {1, 5, 6, 3, 4, 2, 7, 0};
    private static final int OVERVIEW_PAGE = 0, SIGNALS_PAGE = 1;

    private final ListView list;
    private final SwipeHost pageHost;
    private final ScrollView overviewScroll;
    private final LinearLayout allPage;
    private final SatelliteAdapter adapter = new SatelliteAdapter();
    private final ArrayList<Satellite> signals = new ArrayList<>();
    private final ArrayList<Satellite> filtered = new ArrayList<>();
    private final TextView fixTitle;
    private final TextView fixAge;
    private final TextView longitude;
    private final TextView latitude;
    private final TextView speed;
    private final TextView accuracy;
    private final TextView altitude;
    private final TextView satelliteAge;
    private final TextView directionStatus;
    private final TextView overviewLegend;
    private final TextView signalCount;
    private final TextView[] overviewCounts = new TextView[8];
    private final SkyPlot sky;
    private final Spinner providerFilter;
    private final Spinner sorting;
    private final Switch usedOnly;
    private SatelliteAction satelliteAction;
    private PageListener pageListener;
    private String notifiedPageTitle;
    private Location location;
    private long lastSatelliteElapsed;
    private long now = SystemClock.elapsedRealtime();
    private boolean monitoring;
    private boolean locationEnabled = true;
    private boolean satellitesFresh;
    private int subpage = OVERVIEW_PAGE;
    private int transitionTarget = -1;
    private ValueAnimator pageAnimator;
    private Parcelable pendingListState;

    public StatusPage(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        pageHost = new SwipeHost(context);
        addView(pageHost, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        allPage = column();
        overviewScroll = new ScrollView(context);
        overviewScroll.setFillViewport(false);
        overviewScroll.setClipToPadding(false);
        pageHost.addView(allPage, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(overviewScroll, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(context);
        list.setId(View.generateViewId());
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        list.setCacheColorHint(Color.TRANSPARENT);

        LinearLayout header = column();
        header.setPadding(dp(16), dp(5), dp(16), dp(12));
        overviewScroll.addView(header, new ScrollView.LayoutParams(-1, -2));
        LinearLayout fix = card();
        LinearLayout fixHeading = row();
        fixTitle = text("等待定位", 16, INK, true);
        fixAge = text("", 12, MUTED, false);
        fixHeading.addView(fixTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        fixHeading.addView(fixAge);
        fix.addView(fixHeading);
        LinearLayout coordinates = row();
        coordinates.setPadding(0, dp(9), 0, 0);
        longitude = metricCell(coordinates, "经度", 0);
        latitude = metricCell(coordinates, "纬度", 8);
        fix.addView(coordinates);
        LinearLayout measurements = row();
        measurements.setPadding(0, dp(8), 0, 0);
        accuracy = metricCell(measurements, "精度", 0);
        altitude = metricCell(measurements, "海拔", 8);
        fix.addView(measurements);
        LinearLayout speedRow = row();
        speedRow.setPadding(0, dp(8), 0, 0);
        speed = metricCell(speedRow, "速度", 0);
        fix.addView(speedRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addCard(header, fix);

        LinearLayout skyCard = card();
        LinearLayout skyHeading = row();
        skyHeading.addView(text("卫星天空图", 16, INK, true), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        satelliteAge = text("等待卫星", 12, MUTED, false);
        skyHeading.addView(satelliteAge);
        skyCard.addView(skyHeading);
        sky = new SkyPlot(context);
        skyCard.addView(sky, new LayoutParams(LayoutParams.MATCH_PARENT, dp(206)));
        directionStatus = text("北向朝上", 11, MUTED, false);
        directionStatus.setGravity(Gravity.CENTER);
        directionStatus.setPadding(0, dp(4), 0, 0);
        skyCard.addView(directionStatus);

        LinearLayout summary = card();
        LinearLayout summaryHeading = row();
        summaryHeading.addView(text("星座总览", 16, INK, true), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        overviewLegend = text("可见/参与", 11, MUTED, false);
        summaryHeading.addView(overviewLegend);
        summary.addView(summaryHeading);
        for (int r = 0; r < 2; r++) {
            LinearLayout gridRow = row();
            gridRow.setPadding(0, dp(9), 0, 0);
            for (int c = 0; c < 4; c++) {
                int index = r * 4 + c;
                int constellation = OVERVIEW[index];
                LinearLayout cell = column();
                TextView name = text(constellation == 0 ? "合计" : nameOf(constellation), 10, constellation == 0 ? MUTED : colorOf(constellation), true);
                name.setGravity(Gravity.CENTER);
                cell.addView(name);
                TextView count = text("— / —", 16, INK, true);
                count.setGravity(Gravity.CENTER);
                cell.addView(count);
                overviewCounts[index] = count;
                gridRow.addView(cell, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            }
            summary.addView(gridRow);
        }
        addCard(header, summary);
        addCard(header, skyCard);

        LinearLayout filters = column();
        filters.setPadding(dp(16), dp(2), dp(16), dp(7));
        filters.setBackgroundColor(Color.WHITE);
        LinearLayout filterRow = row();
        providerFilter = spinner(new String[]{"全部星座", "GPS", "北斗", "Galileo", "GLONASS", "QZSS", "SBAS", "NavIC"});
        filterRow.addView(providerFilter, new LayoutParams(0, dp(44), 1));
        usedOnly = new Switch(context);
        usedOnly.setText("仅参与");
        usedOnly.setTextSize(13);
        usedOnly.setTextColor(INK);
        usedOnly.setPadding(dp(8), 0, dp(3), 0);
        usedOnly.setContentDescription("仅显示参与定位的卫星，可与星座筛选组合");
        filterRow.addView(usedOnly, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));
        filters.addView(filterRow);
        LinearLayout sortRow = row();
        signalCount = text("等待卫星状态", 12, MUTED, false);
        sortRow.addView(signalCount, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        sorting = spinner(new String[]{"信号强度 ↓", "卫星编号"});
        sortRow.addView(sorting, new LayoutParams(dp(132), dp(38)));
        filters.addView(sortRow);
        allPage.addView(filters, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (pageAnimator == null && satelliteAction != null && position >= 0 && position < filtered.size()) {
                Satellite satellite = filtered.get(position);
                satelliteAction.onSatellite(satellite.constellation, satellite.svid);
            }
        });
        allPage.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        AdapterView.OnItemSelectedListener selection = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refilter();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        providerFilter.setOnItemSelectedListener(selection);
        sorting.setOnItemSelectedListener(selection);
        usedOnly.setOnCheckedChangeListener((button, checked) -> refilter());
        renderFreshness();
        snapPages();
    }

    public void setSatelliteAction(SatelliteAction action) {
        satelliteAction = action;
    }

    public String getSubpageTitle() {
        int current = transitionTarget >= 0 ? transitionTarget : subpage;
        return current == SIGNALS_PAGE ? "全部星座" : "概览";
    }

    public void setPageListener(PageListener listener) {
        pageListener = listener;
        notifiedPageTitle = getSubpageTitle();
        if (listener != null) listener.onPageChanged(notifiedPageTitle);
    }

    private void notifyPageChanged() {
        String title = getSubpageTitle();
        if (pageListener != null && !title.equals(notifiedPageTitle)) {
            notifiedPageTitle = title;
            pageListener.onPageChanged(title);
        }
    }

    /** Give the list subpage its own back step before the activity leaves this tab. */
    public boolean handleBack() {
        if (pageAnimator != null || pageHost.isDragging()) return true;
        if (subpage != SIGNALS_PAGE) return false;
        showPage(OVERVIEW_PAGE, true);
        return true;
    }

    /** Screen-top azimuth clockwise from north, supplied by the activity. */
    public void setHeading(float degrees, boolean available) {
        boolean reliable = available && Float.isFinite(degrees);
        float heading = reliable ? (degrees % 360f + 360f) % 360f : 0;
        int rounded = Math.round(heading) % 360;
        String status = reliable ? String.format(Locale.US, "朝向%03d°", rounded) : "北向朝上";
        if (!status.contentEquals(directionStatus.getText())) directionStatus.setText(status);
        directionStatus.setTextColor(reliable ? BLUE : MUTED);
        sky.setHeading(heading);
        String description = reliable ? "跟随设备指南针的卫星天空图，设备相对北方朝向 " + rounded + " 度。"
                : "北向朝上的卫星天空图，指南针方向不可用。";
        description += "卫星颜色对应星座，实心为参与定位。";
        if (!description.contentEquals(sky.getContentDescription())) sky.setContentDescription(description);
    }

    public void setLocation(Location value) {
        location = value == null ? null : new Location(value);
        now = SystemClock.elapsedRealtime();
        renderFreshness();
    }

    public void setGnssStatus(GnssStatus status) {
        now = SystemClock.elapsedRealtime();
        lastSatelliteElapsed = now;
        LinkedHashMap<Long, Satellite> distinctSignals = new LinkedHashMap<>();
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            int constellation = status.getConstellationType(i);
            int svid = status.getSvid(i);
            float frequency = status.hasCarrierFrequencyHz(i) ? status.getCarrierFrequencyHz(i) : 0;
            if (!Float.isFinite(frequency) || frequency < 0) frequency = 0;
            Satellite candidate = new Satellite(constellation, svid, status.getCn0DbHz(i),
                    status.getAzimuthDegrees(i), status.getElevationDegrees(i), frequency, status.usedInFix(i));
            Satellite existing = distinctSignals.get(candidate.signalKey());
            if (existing == null) {
                distinctSignals.put(candidate.signalKey(), candidate);
            } else {
                existing.used |= candidate.used;
                if (candidate.cn0 > existing.cn0) {
                    existing.cn0 = candidate.cn0;
                    existing.azimuth = candidate.azimuth;
                    existing.elevation = candidate.elevation;
                }
            }
        }
        signals.clear();
        signals.addAll(distinctSignals.values());
        renderOverview();
        renderFreshness();
        refilter();
    }

    public void setSessionState(boolean value, boolean enabled, String message) {
        monitoring = value;
        locationEnabled = enabled;
        now = SystemClock.elapsedRealtime();
        renderFreshness();
    }

    public void tick(long elapsedRealtimeMillis) {
        now = elapsedRealtimeMillis;
        renderFreshness();
    }

    public void resetObservation() {
        location = null;
        lastSatelliteElapsed = 0;
        signals.clear();
        renderOverview();
        refilter();
        renderFreshness();
    }

    /** Only UI choices are saved. No observation or coordinate is persisted. */
    public Bundle saveState() {
        Bundle state = new Bundle();
        state.putInt("provider", providerFilter.getSelectedItemPosition());
        state.putBoolean("usedOnly", usedOnly.isChecked());
        state.putInt("sort", sorting.getSelectedItemPosition());
        state.putString("statusSubpage", (transitionTarget >= 0 ? transitionTarget : subpage) == SIGNALS_PAGE ? "signals" : "overview");
        state.putInt("overviewScroll", overviewScroll.getScrollY());
        state.putParcelable("signalScroll", pendingListState != null ? pendingListState : list.onSaveInstanceState());
        return state;
    }

    public void restoreState(Bundle state) {
        if (state == null) return;
        providerFilter.setSelection(Math.max(0, Math.min(PROVIDERS.length - 1, state.getInt("provider", 0))));
        usedOnly.setChecked(state.getBoolean("usedOnly", false));
        sorting.setSelection(Math.max(0, Math.min(1, state.getInt("sort", 0))));
        cancelPageAnimation();
        String savedSubpage = state.getString("statusSubpage");
        if (savedSubpage != null) subpage = "signals".equals(savedSubpage) ? SIGNALS_PAGE : OVERVIEW_PAGE;
        // 0.4.4 used the opposite physical order: its index 0 was the signal list.
        else subpage = state.containsKey("subpage") && state.getInt("subpage", -1) == 0 ? SIGNALS_PAGE : OVERVIEW_PAGE;
        snapPages();
        int overviewY = Math.max(0, state.getInt("overviewScroll", 0));
        overviewScroll.post(() -> overviewScroll.scrollTo(0, overviewY));
        pendingListState = state.getParcelable("signalScroll");
        refilter();
    }

    private void renderFreshness() {
        boolean freshSatellite = monitoring && locationEnabled && lastSatelliteElapsed > 0
                && now >= lastSatelliteElapsed && now - lastSatelliteElapsed <= FRESH_MILLIS;
        boolean changed = satellitesFresh != freshSatellite;
        satellitesFresh = freshSatellite;
        if (location == null) {
            fixTitle.setText("等待定位");
            fixTitle.setTextColor(INK);
            fixAge.setText("");
            setMetric(longitude, "—°", false);
            setMetric(latitude, "—°", false);
            setMetric(speed, "— km/h", false);
            setMetric(accuracy, "— m", false);
            setMetric(altitude, "— m", false);
        } else {
            long age = locationAge();
            boolean freshFix = monitoring && locationEnabled && age >= 0 && age <= FRESH_MILLIS;
            fixTitle.setText(freshFix ? "已定位" : "上次定位");
            fixTitle.setTextColor(freshFix ? Color.rgb(25, 130, 94) : MUTED);
            fixAge.setText(age < 0 ? "时间未知" : (freshFix ? "" : "已过期 · ") + ageText(age));
            boolean hasLongitude = Double.isFinite(location.getLongitude());
            boolean hasLatitude = Double.isFinite(location.getLatitude());
            setMetric(longitude, hasLongitude ? String.format(Locale.US, "%.6f°", location.getLongitude()) : "—°", freshFix && hasLongitude);
            setMetric(latitude, hasLatitude ? String.format(Locale.US, "%.6f°", location.getLatitude()) : "—°", freshFix && hasLatitude);
            boolean hasSpeed = location.hasSpeed() && Float.isFinite(location.getSpeed()) && location.getSpeed() >= 0;
            setMetric(speed, hasSpeed ? String.format(Locale.US, "%.1f km/h", location.getSpeed() * 3.6f) : "— km/h", freshFix && hasSpeed);
            boolean hasAccuracy = location.hasAccuracy() && Float.isFinite(location.getAccuracy()) && location.getAccuracy() >= 0;
            setMetric(accuracy, hasAccuracy ? String.format(Locale.US, "± %.1f m", location.getAccuracy()) : "— m", freshFix && hasAccuracy);
            boolean hasAltitude = location.hasAltitude() && Double.isFinite(location.getAltitude());
            setMetric(altitude, hasAltitude ? String.format(Locale.US, "%.1f m", location.getAltitude()) : "— m", freshFix && hasAltitude);
        }

        if (lastSatelliteElapsed == 0) {
            satelliteAge.setText("等待卫星");
        } else {
            long age = Math.max(0, now - lastSatelliteElapsed);
            satelliteAge.setText(freshSatellite ? "实时 · " + ageText(age) : "已过期 · " + ageText(age));
        }
        overviewLegend.setText(freshSatellite || lastSatelliteElapsed == 0
                ? "可见/参与" : "上次可见/参与");
        sky.setStale(!freshSatellite && lastSatelliteElapsed > 0);
        if (changed) adapter.notifyDataSetChanged();
    }

    private long locationAge() {
        long nanos = location.getElapsedRealtimeNanos();
        if (nanos <= 0) return -1;
        long fixElapsed = nanos / 1000000L;
        return now >= fixElapsed ? now - fixElapsed : -1;
    }

    private void renderOverview() {
        Map<Integer, Set<Long>> visible = new HashMap<>();
        Map<Integer, Set<Long>> used = new HashMap<>();
        Set<Long> allVisible = new HashSet<>();
        Set<Long> allUsed = new HashSet<>();
        for (Satellite satellite : signals) {
            long key = satellite.satelliteKey();
            allVisible.add(key);
            Set<Long> constellationVisible = visible.get(satellite.constellation);
            if (constellationVisible == null) {
                constellationVisible = new HashSet<>();
                visible.put(satellite.constellation, constellationVisible);
            }
            constellationVisible.add(key);
            if (satellite.used) {
                allUsed.add(key);
                Set<Long> constellationUsed = used.get(satellite.constellation);
                if (constellationUsed == null) {
                    constellationUsed = new HashSet<>();
                    used.put(satellite.constellation, constellationUsed);
                }
                constellationUsed.add(key);
            }
        }
        for (int index = 0; index < OVERVIEW.length; index++) {
            int constellation = OVERVIEW[index];
            int seen = constellation == 0 ? allVisible.size() : sizeOf(visible.get(constellation));
            int inFix = constellation == 0 ? allUsed.size() : sizeOf(used.get(constellation));
            overviewCounts[index].setText(lastSatelliteElapsed == 0 ? "— / —" : seen + " / " + inFix);
        }
    }

    private void refilter() {
        if (providerFilter == null || sorting == null || usedOnly == null) return;
        int selected = Math.max(0, providerFilter.getSelectedItemPosition());
        int provider = PROVIDERS[Math.min(selected, PROVIDERS.length - 1)];
        boolean onlyUsed = usedOnly.isChecked();
        filtered.clear();
        Set<Long> satellites = new HashSet<>();
        for (Satellite satellite : signals) {
            if ((provider == 0 || satellite.constellation == provider) && (!onlyUsed || satellite.used)) {
                filtered.add(satellite);
                satellites.add(satellite.satelliteKey());
            }
        }
        final boolean bySignal = sorting.getSelectedItemPosition() == 0;
        Collections.sort(filtered, new Comparator<Satellite>() {
            @Override public int compare(Satellite a, Satellite b) {
                int compare = bySignal ? Float.compare(b.cn0, a.cn0) : 0;
                if (compare == 0) compare = Integer.compare(providerOrder(a.constellation), providerOrder(b.constellation));
                if (compare == 0) compare = Integer.compare(a.svid, b.svid);
                if (compare == 0) compare = Float.compare(b.frequency, a.frequency);
                return compare;
            }
        });
        signalCount.setText(lastSatelliteElapsed == 0 ? "等待卫星状态"
                : satellites.size() + " 颗 · " + filtered.size() + " 路信号" + (filtered.isEmpty() ? " · 无匹配" : ""));
        sky.setSatellites(filtered, provider != 0 || onlyUsed);
        adapter.notifyDataSetChanged();
        restoreSignalScroll();
    }

    private void restoreSignalScroll() {
        if (pendingListState == null || filtered.isEmpty()) return;
        Parcelable position = pendingListState;
        list.post(() -> {
            if (pendingListState != position || filtered.isEmpty()) return;
            list.onRestoreInstanceState(position);
            pendingListState = null;
        });
    }

    private View pageAt(int index) { return index == SIGNALS_PAGE ? allPage : overviewScroll; }

    private void positionPages(float offset, boolean revealNeighbor) {
        int width = pageHost.getWidth();
        for (int i = 0; i < 2; i++) {
            View page = pageAt(i);
            page.setTranslationX((i - subpage) * width + offset);
            page.setVisibility(i == subpage || revealNeighbor ? VISIBLE : INVISIBLE);
        }
    }

    private void snapPages() {
        positionPages(0, false);
        for (int i = 0; i < 2; i++) {
            ViewGroup page = (ViewGroup) pageAt(i);
            page.setImportantForAccessibility(i == subpage ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            page.setDescendantFocusability(i == subpage ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        }
        notifyPageChanged();
    }

    private void showPage(int target, boolean animate) {
        if (pageAnimator != null) return;
        target = Math.max(OVERVIEW_PAGE, Math.min(SIGNALS_PAGE, target));
        if (!animate || pageHost.getWidth() == 0) {
            subpage = target;
            snapPages();
        } else if (target != subpage) animatePage(target, 0);
    }

    private void animatePage(int target, float offset) {
        int width = pageHost.getWidth();
        if (width <= 0) { subpage = target; snapPages(); return; }
        transitionTarget = target;
        notifyPageChanged();
        for (int i = 0; i < 2; i++) {
            ViewGroup page = (ViewGroup) pageAt(i);
            page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            page.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }
        ValueAnimator animator = ValueAnimator.ofFloat(offset, (subpage - target) * width);
        pageAnimator = animator;
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> positionPages((float) value.getAnimatedValue(), true));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (pageAnimator != animation) return;
                pageAnimator = null;
                subpage = target;
                transitionTarget = -1;
                snapPages();
            }
        });
        animator.start();
    }

    private void cancelPageAnimation() {
        ValueAnimator animator = pageAnimator;
        pageAnimator = null;
        if (animator != null) animator.cancel();
        transitionTarget = -1;
    }

    @Override protected void onDetachedFromWindow() {
        int target = transitionTarget >= 0 ? transitionTarget : subpage;
        cancelPageAnimation();
        subpage = target;
        snapPages();
        super.onDetachedFromWindow();
    }

    /** Horizontal intent is decided before either vertical scroll view may lock interception. */
    private final class SwipeHost extends FrameLayout {
        private final int slop;
        private float downX, downY, dragOffset;
        private boolean tracking, horizontal, vertical, controlGesture;

        SwipeHost(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(true);
        }

        boolean isDragging() { return horizontal; }

        private boolean hitsControl(View control, float x, float y) {
            if (control == null || !control.isShown()) return false;
            Rect bounds = new Rect();
            control.getDrawingRect(bounds);
            offsetDescendantRectToMyCoords(control, bounds);
            return bounds.contains((int) x, (int) y);
        }

        private void beginGesture(MotionEvent event) {
            downX = event.getX(); downY = event.getY(); dragOffset = 0;
            tracking = true; horizontal = false; vertical = false;
            controlGesture = subpage == SIGNALS_PAGE && (hitsControl(providerFilter, downX, downY)
                    || hitsControl(sorting, downX, downY) || hitsControl(usedOnly, downX, downY));
        }

        private void inspectDrag(MotionEvent event) {
            if (!tracking || horizontal || vertical || controlGesture) return;
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (dx > slop && dx > dy * 1.4f) {
                horizontal = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (dy > slop) {
                vertical = true;
                super.requestDisallowInterceptTouchEvent(true);
            }
        }

        @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
            // ScrollView can request this on DOWN while it is already scrolled. Keep MOVE
            // until intent is known; then let vertical scrolling and native controls own it.
            if (!disallow || vertical || controlGesture) super.requestDisallowInterceptTouchEvent(disallow);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (pageAnimator != null) { tracking = false; return true; }
            if (action == MotionEvent.ACTION_DOWN) beginGesture(event);
            if (action == MotionEvent.ACTION_POINTER_DOWN) { vertical = true; horizontal = false; }
            if (action == MotionEvent.ACTION_MOVE) inspectDrag(event);
            return horizontal;
        }

        private float limitOffset(float dx) {
            int width = getWidth();
            if (subpage == OVERVIEW_PAGE && dx > 0 || subpage == SIGNALS_PAGE && dx < 0) dx *= .18f;
            return Math.max(-width, Math.min(width, dx));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (pageAnimator != null) { tracking = false; return true; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { beginGesture(event); return true; }
            if (!tracking) return true;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                vertical = true;
                if (horizontal) {
                    horizontal = false; tracking = false;
                    animatePage(subpage, dragOffset);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                inspectDrag(event);
                if (horizontal) {
                    dragOffset = limitOffset(event.getX() - downX);
                    positionPages(dragOffset, true);
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float dx = event.getX() - downX, dy = Math.abs(event.getY() - downY);
                boolean wasHorizontal = horizontal;
                int target = subpage;
                if (horizontal && action == MotionEvent.ACTION_UP && Math.abs(dx) >= Math.max(dp(48), getWidth() * .15f)
                        && Math.abs(dx) > dy * 1.4f) {
                    target = Math.max(OVERVIEW_PAGE, Math.min(SIGNALS_PAGE, subpage + (dx > 0 ? -1 : 1)));
                }
                tracking = false; horizontal = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (wasHorizontal) animatePage(target, dragOffset);
                return true;
            }
            return true;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int target = transitionTarget >= 0 ? transitionTarget : subpage;
            cancelPageAnimation();
            tracking = false; horizontal = false;
            subpage = target;
            snapPages();
        }
    }

    private static int sizeOf(Set<Long> value) { return value == null ? 0 : value.size(); }

    private TextView metricCell(LinearLayout parent, String label, int gap) {
        LinearLayout cell = column();
        cell.setPadding(dp(10), dp(9), dp(10), dp(9));
        cell.setMinimumHeight(dp(72));
        GradientDrawable background = new GradientDrawable();
        background.setColor(BG);
        background.setCornerRadius(dp(10));
        cell.setBackground(background);
        cell.addView(text(label, 12, MUTED, false));
        TextView value = text("—", 22, MUTED, true);
        value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(16, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        LayoutParams valueParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(32));
        valueParams.topMargin = dp(4);
        cell.addView(value, valueParams);
        LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = dp(gap);
        parent.addView(cell, params);
        return value;
    }

    private void setMetric(TextView view, String value, boolean fresh) {
        view.setText(value);
        view.setTextColor(fresh ? INK : MUTED);
    }

    private Spinner spinner(String[] choices) {
        Spinner spinner = new Spinner(getContext());
        ArrayAdapter<String> options = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, choices) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView value = (TextView) super.getView(position, recycled, parent);
                value.setTextSize(13);
                value.setTextColor(INK);
                value.setPadding(dp(4), 0, 0, 0);
                return value;
            }
        };
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(options);
        return spinner;
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(getContext());
        result.setOrientation(HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(getContext());
        result.setOrientation(VERTICAL);
        return result;
    }

    private LinearLayout card() {
        LinearLayout result = column();
        result.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(15));
        result.setBackground(shape);
        return result;
    }

    private void addCard(LinearLayout header, View card) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        header.addView(card, params);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(getContext());
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setIncludeFontPadding(false);
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static int providerOrder(int constellation) {
        for (int i = 1; i < PROVIDERS.length; i++) if (PROVIDERS[i] == constellation) return i;
        return 99;
    }

    private static String nameOf(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "北斗";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_IRNSS: return "NavIC";
            default: return "未知";
        }
    }

    private static int colorOf(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return Color.rgb(38, 100, 196);
            case GnssStatus.CONSTELLATION_SBAS: return Color.rgb(130, 111, 52);
            case GnssStatus.CONSTELLATION_GLONASS: return Color.rgb(191, 83, 82);
            case GnssStatus.CONSTELLATION_QZSS: return Color.rgb(139, 91, 174);
            case GnssStatus.CONSTELLATION_BEIDOU: return Color.rgb(214, 131, 28);
            case GnssStatus.CONSTELLATION_GALILEO: return Color.rgb(25, 142, 119);
            case GnssStatus.CONSTELLATION_IRNSS: return Color.rgb(50, 136, 160);
            default: return MUTED;
        }
    }

    private static String ageText(long milliseconds) {
        long seconds = milliseconds / 1000L;
        if (seconds == 0) return "刚刚";
        if (seconds < 60) return seconds + " 秒前";
        if (seconds < 3600) return seconds / 60 + " 分钟前";
        return seconds / 3600 + " 小时前";
    }

    private static final class Satellite {
        final int constellation;
        final int svid;
        float cn0;
        float azimuth;
        float elevation;
        final float frequency;
        boolean used;

        Satellite(int constellation, int svid, float cn0, float azimuth, float elevation, float frequency, boolean used) {
            this.constellation = constellation;
            this.svid = svid;
            this.cn0 = Float.isFinite(cn0) ? Math.max(0, cn0) : 0;
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.frequency = frequency;
            this.used = used;
        }

        long satelliteKey() { return ((long) constellation << 32) | (svid & 0xffffffffL); }
        long signalKey() { return constellation * 10000000000000L + svid * 10000000000L + Math.round(frequency / 1000f); }
    }

    private final class SatelliteAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return filtered.get(position).signalKey(); }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SatelliteRow holder;
            if (convertView == null) {
                LinearLayout outer = column();
                outer.setPadding(dp(16), 0, dp(16), 0);
                LinearLayout content = column();
                content.setPadding(dp(13), dp(11), dp(13), dp(11));
                content.setBackgroundColor(Color.WHITE);
                LinearLayout top = row();
                TextView name = text("", 14, INK, true);
                TextView usage = text("", 11, BLUE, true);
                usage.setPadding(dp(6), dp(3), dp(6), dp(3));
                GradientDrawable badge = new GradientDrawable();
                badge.setColor(Color.rgb(235, 243, 255));
                badge.setCornerRadius(dp(5));
                usage.setBackground(badge);
                TextView strength = text("", 13, INK, true);
                strength.setGravity(Gravity.RIGHT);
                top.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                top.addView(usage);
                LayoutParams strengthParams = new LayoutParams(dp(92), LayoutParams.WRAP_CONTENT);
                top.addView(strength, strengthParams);
                content.addView(top);
                SignalBar bar = new SignalBar(getContext());
                LayoutParams barParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(5));
                barParams.topMargin = dp(8);
                barParams.bottomMargin = dp(7);
                content.addView(bar, barParams);
                TextView detail = text("", 11, MUTED, false);
                content.addView(detail);
                View separator = new View(getContext());
                separator.setBackgroundColor(LINE);
                content.addView(separator, new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
                LayoutParams separatorParams = (LayoutParams) separator.getLayoutParams();
                separatorParams.topMargin = dp(10);
                content.setPadding(dp(13), dp(11), dp(13), 0);
                outer.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                holder = new SatelliteRow(name, usage, strength, detail, bar);
                outer.setTag(holder);
                convertView = outer;
            } else holder = (SatelliteRow) convertView.getTag();
            Satellite satellite = filtered.get(position);
            holder.name.setText(nameOf(satellite.constellation) + "  " + satellite.svid);
            holder.name.setTextColor(colorOf(satellite.constellation));
            holder.usage.setText(satellitesFresh ? "参与定位" : "上次参与");
            holder.usage.setVisibility(satellite.used ? View.VISIBLE : View.GONE);
            holder.strength.setText(String.format(Locale.US, "%.1f dB-Hz", satellite.cn0));
            String detail = String.format(Locale.US, "方位 %.0f°    仰角 %.0f°", satellite.azimuth, satellite.elevation);
            if (satellite.frequency > 0) detail += String.format(Locale.US, "    %.2f MHz", satellite.frequency / 1000000f);
            holder.detail.setText(detail);
            holder.bar.setSignal(satellite.cn0, colorOf(satellite.constellation));
            return convertView;
        }
    }

    private static final class SatelliteRow {
        final TextView name;
        final TextView usage;
        final TextView strength;
        final TextView detail;
        final SignalBar bar;
        SatelliteRow(TextView name, TextView usage, TextView strength, TextView detail, SignalBar bar) {
            this.name = name; this.usage = usage; this.strength = strength; this.detail = detail; this.bar = bar;
        }
    }

    private static final class SignalBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cn0;
        private int color;
        SignalBar(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setSignal(float value, int tint) { cn0 = value; color = tint; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = getHeight() / 2f;
            paint.setColor(LINE);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            paint.setColor(color);
            canvas.drawRoundRect(0, 0, getWidth() * Math.min(1, cn0 / 60f), getHeight(), radius, radius, paint);
        }
    }

    private final class SkyPlot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Satellite> visible = new ArrayList<>();
        private boolean stale;
        private boolean filteredView;
        private float heading;
        SkyPlot(Context context) {
            super(context);
            setContentDescription("北向朝上的卫星天空图，指南针方向不可用。卫星颜色对应星座，实心为参与定位。");
        }
        void setSatellites(List<Satellite> input, boolean filteredValue) {
            Map<Long, Satellite> unique = new LinkedHashMap<>();
            for (Satellite satellite : input) {
                Satellite existing = unique.get(satellite.satelliteKey());
                if (existing == null || (satellite.used && !existing.used) || (satellite.used == existing.used && satellite.cn0 > existing.cn0))
                    unique.put(satellite.satelliteKey(), satellite);
            }
            visible.clear();
            visible.addAll(unique.values());
            filteredView = filteredValue;
            invalidate();
        }
        void setHeading(float value) {
            if (heading != value) { heading = value; invalidate(); }
        }
        void setStale(boolean value) { if (stale != value) { stale = value; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.max(0, Math.min(getWidth() / 2f - dp(22), getHeight() / 2f - dp(21)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(248, 250, 253));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(LINE);
            for (int ring = 1; ring <= 3; ring++) canvas.drawCircle(cx, cy, radius * ring / 3f, paint);
            drawAxis(canvas, cx, cy, radius, -heading);
            drawAxis(canvas, cx, cy, radius, 90 - heading);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(11));
            paint.setColor(MUTED);
            drawDirection(canvas, "N", cx, cy, radius + dp(12), -heading);
            drawDirection(canvas, "E", cx, cy, radius + dp(12), 90 - heading);
            drawDirection(canvas, "S", cx, cy, radius + dp(12), 180 - heading);
            drawDirection(canvas, "W", cx, cy, radius + dp(12), 270 - heading);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(9));
            canvas.drawText("30°", cx + dp(13), cy - radius * 2f / 3f + dp(3), paint);
            canvas.drawText("60°", cx + dp(13), cy - radius / 3f + dp(3), paint);
            for (Satellite satellite : visible) {
                if (!Float.isFinite(satellite.azimuth) || !Float.isFinite(satellite.elevation)) continue;
                float distance = radius * (90 - Math.max(0, Math.min(90, satellite.elevation))) / 90f;
                double radians = Math.toRadians(satellite.azimuth - heading);
                float x = cx + (float) Math.sin(radians) * distance;
                float y = cy - (float) Math.cos(radians) * distance;
                int color = colorOf(satellite.constellation);
                int alpha = stale ? 125 : 255;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(satellite.used ? color : Color.WHITE);
                paint.setAlpha(alpha);
                canvas.drawCircle(x, y, dp(10), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(satellite.used ? 1.5f : 1));
                paint.setColor(color);
                paint.setAlpha(alpha);
                canvas.drawCircle(x, y, dp(10), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(dp(satellite.svid >= 100 ? 8 : 9));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setColor(satellite.used ? Color.WHITE : color);
                paint.setAlpha(alpha);
                canvas.drawText(String.valueOf(satellite.svid), x, y + dp(3), paint);
                paint.setAlpha(255);
            }
            if (visible.isEmpty()) {
                paint.setColor(MUTED);
                paint.setTextSize(dp(12));
                paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText(lastSatelliteElapsed == 0 ? "等待卫星状态" : filteredView ? "没有符合筛选的卫星" : "尚未收到可见卫星", cx, cy + dp(4), paint);
            }
        }
        private void drawAxis(Canvas canvas, float cx, float cy, float radius, float azimuth) {
            double radians = Math.toRadians(azimuth);
            float x = (float) Math.sin(radians) * radius;
            float y = -(float) Math.cos(radians) * radius;
            canvas.drawLine(cx - x, cy - y, cx + x, cy + y, paint);
        }
        private void drawDirection(Canvas canvas, String label, float cx, float cy, float radius, float azimuth) {
            double radians = Math.toRadians(azimuth);
            float x = cx + (float) Math.sin(radians) * radius;
            float y = cy - (float) Math.cos(radians) * radius;
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(label, x, y - (metrics.ascent + metrics.descent) / 2f, paint);
        }
    }
}
