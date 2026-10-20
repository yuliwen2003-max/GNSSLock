package com.yuliwen.gnsslock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure Java parsing and serialization of the Android GNSS satellite blocklist. */
public final class GnssPolicy {
    public static final String KEY = "gnss_satellite_blocklist";
    public static final String[] NAMES = {
            "未知", "GPS", "SBAS", "GLONASS", "QZSS", "北斗", "Galileo", "NavIC / IRNSS"
    };

    public static final class Parsed {
        public final boolean[] allowed = new boolean[8];
        public final List<String> partialEntries = new ArrayList<>();
        public final List<String> unknownEntries = new ArrayList<>();
        public boolean malformed;
        public boolean bdsAllSatellites;

        private Parsed() { Arrays.fill(allowed, true); }

        public boolean replacesDetailedConfiguration() {
            return malformed || !partialEntries.isEmpty() || !unknownEntries.isEmpty();
        }
    }

    private GnssPolicy() { }

    public static Parsed parse(String raw) {
        Parsed result = new Parsed();
        if (raw == null || raw.trim().isEmpty()) return result;
        boolean[] bdsSatellites = new boolean[64];
        List<String> bdsPartialEntries = new ArrayList<>();
        String[] tokens = raw.split(",", -1);
        if (tokens.length % 2 != 0) result.malformed = true;
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                int constellation = Integer.parseInt(tokens[i].trim());
                int svid = Integer.parseInt(tokens[i + 1].trim());
                if (constellation < 0 || svid < 0) {
                    result.malformed = true;
                    continue;
                }
                String entry = constellation + "," + svid;
                if (constellation < 1 || constellation > 7) {
                    result.unknownEntries.add(entry);
                } else if (svid == 0) {
                    result.allowed[constellation] = false;
                } else if (constellation == 5 && svid <= 63) {
                    bdsSatellites[svid] = true;
                    bdsPartialEntries.add(NAMES[constellation] + " 卫星 " + svid);
                } else {
                    result.partialEntries.add(NAMES[constellation] + " 卫星 " + svid);
                }
            } catch (NumberFormatException ignored) {
                result.malformed = true;
            }
        }
        boolean fullBdsSet = true;
        for (int svid = 1; svid <= 63; svid++) fullBdsSet &= bdsSatellites[svid];
        result.bdsAllSatellites = fullBdsSet;
        if (fullBdsSet) result.allowed[5] = false;
        else result.partialEntries.addAll(bdsPartialEntries);
        return result;
    }

    public static String serialize(boolean[] allowed) {
        if (allowed == null || allowed.length < 8) {
            throw new IllegalArgumentException("必须提供 7 个星座的选择状态");
        }
        boolean anyAllowed = false;
        StringBuilder value = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            anyAllowed |= allowed[i];
            if (!allowed[i]) {
                if (value.length() > 0) value.append(',');
                value.append(i).append(",0");
            }
        }
        if (!anyAllowed) throw new IllegalArgumentException("至少需要允许一个星座");
        return value.toString();
    }

    public static String describe(boolean[] allowed, boolean describeAllowed) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            if (allowed[i] == describeAllowed) names.add(NAMES[i]);
        }
        return names.isEmpty() ? "无" : String.join("、", names);
    }
}
