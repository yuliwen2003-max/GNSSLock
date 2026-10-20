package com.yuliwen.gnsslock;

import java.util.Arrays;

/** Runs with plain javac/java, without Android or a test dependency. */
public final class GnssPolicyTest {
    public static void main(String[] args) {
        boolean[] allowed = new boolean[8];
        allowed[1] = true;
        expect("2,0,3,0,4,0,5,0,6,0,7,0".equals(GnssPolicy.serialize(allowed)), "GPS only");
        allowed[5] = true;
        expect("2,0,3,0,4,0,6,0,7,0".equals(GnssPolicy.serialize(allowed)), "GPS and BeiDou");
        Arrays.fill(allowed, true);
        expect("".equals(GnssPolicy.serialize(allowed)), "all constellations use empty string");
        Arrays.fill(allowed, false);
        boolean rejected = false;
        try { GnssPolicy.serialize(allowed); } catch (IllegalArgumentException expected) { rejected = true; }
        expect(rejected, "reject blocking everything");
        GnssPolicy.Parsed partial = GnssPolicy.parse("3,0,5,12");
        expect(!partial.allowed[3] && partial.allowed[5], "whole versus individual satellite");
        expect(partial.replacesDetailedConfiguration() && partial.partialEntries.size() == 1, "warn on partial entries");
        expect(GnssPolicy.parse("1,0,5").malformed, "odd token count");
        expect(GnssPolicy.parse("1,no").malformed, "non integer");
        expect(GnssPolicy.parse("1,-1").malformed, "negative SVID");
        expect(GnssPolicy.parse("8,0").replacesDetailedConfiguration(), "future constellation retained as warning");
        expect(!GnssPolicy.parse("").replacesDetailedConfiguration(), "empty configuration");
        expect(!GnssPolicy.parse(null).replacesDetailedConfiguration(), "absent configuration");
        StringBuilder allBds = new StringBuilder();
        for (int svid = 1; svid <= 63; svid++) {
            if (allBds.length() > 0) allBds.append(',');
            allBds.append("5,").append(svid);
        }
        GnssPolicy.Parsed expanded = GnssPolicy.parse(allBds.toString());
        expect(!expanded.allowed[5] && expanded.bdsAllSatellites, "all 63 BeiDou satellites mean BeiDou excluded");
        expect(!expanded.replacesDetailedConfiguration(), "expanded BeiDou strategy is not a partial warning");
        String missingBds63 = allBds.substring(0, allBds.lastIndexOf(",5,63"));
        GnssPolicy.Parsed missingOne = GnssPolicy.parse(missingBds63);
        expect(missingOne.allowed[5] && !missingOne.bdsAllSatellites, "62 BeiDou satellites are not a whole constellation");
        expect(missingOne.partialEntries.size() == 62 && missingOne.replacesDetailedConfiguration(), "incomplete BeiDou set retains partial warnings");
        GnssPolicy.Parsed mixedWhole = GnssPolicy.parse("5,0," + missingBds63);
        expect(!mixedWhole.allowed[5] && mixedWhole.partialEntries.size() == 62, "BeiDou whole entry remains effective with partial entries");
        GnssPolicy.Parsed mixedComplete = GnssPolicy.parse("5,0," + allBds + ",3,12");
        expect(!mixedComplete.allowed[5] && mixedComplete.partialEntries.size() == 1, "complete BeiDou set plus whole entry preserves unrelated partial entries");
        System.out.println("GnssPolicyTest: all checks passed");
    }

    private static void expect(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
