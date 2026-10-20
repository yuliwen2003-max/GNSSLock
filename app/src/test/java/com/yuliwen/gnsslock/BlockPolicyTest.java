package com.yuliwen.gnsslock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Runs with plain javac/java, without Android or test dependencies. */
public final class BlockPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        expect(BlockPolicy.allowAll().equals(BlockPolicy.parse(null)), "null allows all");
        expect("".equals(BlockPolicy.parse("  ").serialize()), "blank allows all");
        expect("1,2,1,9,3,0,6,4".equals(
                BlockPolicy.parse("6,4,1,9,3,0,1,2,1,9").serialize()), "numeric canonical order and deduplication");
        BlockPolicy wholeBds = BlockPolicy.parse("5,0");
        BlockPolicy expandedBds = BlockPolicy.parse(expanded(5, 63));
        expect(wholeBds.equals(expandedBds), "63 BeiDou entries equal one whole block");
        expect(wholeBds.hashCode() == expandedBds.hashCode(), "equal selections hash identically");
        expect("5,0".equals(expandedBds.serialize()), "63 BeiDou entries canonicalize");
        expect(expandedBds.requiresBdsAppend() && expandedBds.hasBdsSelection(), "full BeiDou needs append strategy");
        BlockPolicy partialBds = BlockPolicy.parse(expanded(5, 62));
        expect(!wholeBds.equals(partialBds) && !partialBds.isConstellationBlocked(5), "62 BeiDou entries stay partial");
        expect(partialBds.hasBdsSelection() && !partialBds.requiresBdsAppend(), "partial BeiDou selection");
        expect(partialBds.getBlockedSvids(5).size() == 62, "62 entries retained");
        expect(!expandedBds.hasDetailedEntries(), "full BeiDou set is not a detailed selection");
        expect(BlockPolicy.parse("5,0,5,1,5,2").equals(wholeBds), "whole block dominates partial entries");

        BlockPolicy allProviders = BlockPolicy.parse("1,3,2,120,3,93,4,193,5,12,6,7,7,14");
        BlockPolicy changedGeneral = allProviders.withConstellationBlocked(5, true);
        for (int c = 1; c <= 7; c++) {
            if (c != 5) expect(allProviders.getBlockedSvids(c).equals(changedGeneral.getBlockedSvids(c)),
                    "general editor preserves provider " + c);
        }
        BlockPolicy changedSatellite = allProviders.withBlockedSvids(6, Arrays.asList(2, 9));
        for (int c = 1; c <= 7; c++) {
            if (c != 6) expect(allProviders.getBlockedSvids(c).equals(changedSatellite.getBlockedSvids(c)),
                    "satellite editor preserves provider " + c);
        }
        expect(changedSatellite.getBlockedSvids(6).equals(new java.util.TreeSet<>(Arrays.asList(2, 9))), "replace one partial set");
        expect(changedGeneral.withConstellationBlocked(5, false).getBlockedSvids(1).contains(3), "unblock provider preserves other details");
        expect(allProviders.getBlockedSvids(5).contains(12), "source policy remains immutable");

        BlockPolicy bdsOneAllowed = wholeBds.withSatelliteBlocked(5, 63, false);
        expect(!bdsOneAllowed.isConstellationBlocked(5) && bdsOneAllowed.getBlockedSvids(5).size() == 62,
                "uncheck from BeiDou whole block expands remaining 62");
        expect(!bdsOneAllowed.isSatelliteBlocked(5, 63) && bdsOneAllowed.isSatelliteBlocked(5, 62),
                "unchecked satellite becomes allowed");
        expect(bdsOneAllowed.withSatelliteBlocked(5, 63, true).equals(wholeBds), "recheck last BeiDou satellite restores whole");
        BlockPolicy gpsOneAllowed = BlockPolicy.parse("1,0,6,2").withSatelliteBlocked(1, 1, false);
        expect(!gpsOneAllowed.isConstellationBlocked(1) && gpsOneAllowed.getBlockedSvids(1).size() == 31,
                "uncheck from GPS whole block expands remaining 31");
        expect(gpsOneAllowed.getBlockedSvids(6).contains(2), "expanding whole preserves other providers");

        BlockPolicy opaque = BlockPolicy.parse("8,0,1,99,3,-1,foo,7,6,2,tail");
        expect(opaque.malformed() && opaque.unknownEntries().size() == 5, "opaque and malformed entries warn");
        BlockPolicy editedOpaque = opaque.withConstellationBlocked(5, true)
                .withBlockedSvids(1, Arrays.asList(2, 4));
        expect(editedOpaque.unknownEntries().equals(opaque.unknownEntries()), "UI edits preserve all opaque entries");
        expect("1,2,1,4,5,0,6,2,8,0,1,99,3,-1,foo,7,tail".equals(editedOpaque.serialize()),
                "opaque pairs and odd trailing token survive serialization");
        expect(BlockPolicy.parse(editedOpaque.serialize()).equals(editedOpaque), "canonical opaque serialization is stable");
        BlockPolicy cleared = editedOpaque.withoutDetailedEntries();
        expect(cleared.isConstellationBlocked(5) && !cleared.hasDetailedEntries(), "clear detailed retains whole blocks");
        expect(cleared.unknownEntries().equals(opaque.unknownEntries()), "clear detailed retains opaque entries");
        expect(BlockPolicy.parse("1,no").malformed() && BlockPolicy.parse("1,-1").malformed(), "noninteger and negative warn");
        expect(!BlockPolicy.parse("1,999").malformed() && !BlockPolicy.parse("1,999").unknownEntries().isEmpty(),
                "out of range is opaque but syntactically valid");

        expect(BlockPolicy.isValidSvid(2, 151) && BlockPolicy.isValidSvid(2, 192)
                && !BlockPolicy.isValidSvid(2, 152), "public SBAS ranges");
        expect(BlockPolicy.isValidSvid(3, 25) && BlockPolicy.isValidSvid(3, 93)
                && BlockPolicy.isValidSvid(3, 106) && !BlockPolicy.isValidSvid(3, 92), "orbital slot and FCN GLONASS ranges");
        expect(BlockPolicy.isValidSvid(4, 183) && BlockPolicy.isValidSvid(4, 212)
                && !BlockPolicy.isValidSvid(4, 213), "public QZSS range");
        expect(BlockPolicy.isValidSvid(7, 14) && !BlockPolicy.isValidSvid(7, 15), "public IRNSS range");
        expect(BlockPolicy.validSvids(0).isEmpty() && BlockPolicy.maxSvid(99) == 0, "unknown range queries are safe");
        expect(BlockPolicy.validSvids(3).size() == 39, "GLONASS ranges enumerated without gap");
        int[][] ranges = BlockPolicy.getValidRanges(1);
        ranges[0][1] = 999;
        expect(!BlockPolicy.isValidSvid(1, 999), "range getter copies mutable arrays");
        expectUnmodifiable(() -> BlockPolicy.validSvids(1).add(99), "valid SVID list is immutable");
        expectUnmodifiable(() -> allProviders.getBlockedSvids(1).clear(), "blocked SVID set is immutable");
        expectUnmodifiable(() -> opaque.unknownEntries().clear(), "opaque entry list is immutable");
        List<Integer> supplied = new ArrayList<>(Arrays.asList(1, 2));
        BlockPolicy copied = BlockPolicy.allowAll().withBlockedSvids(1, supplied);
        supplied.clear();
        expect(copied.getBlockedSvids(1).size() == 2, "supplied collections are copied");
        expect(BlockPolicy.parse("1,0").withBlockedSvids(1, Collections.singleton(2))
                .serialize().equals("1,2"), "replacing detailed selection removes whole block");
        expectRejected(() -> BlockPolicy.allowAll().withSatelliteBlocked(1, 99, true), "invalid UI SVID rejects");
        expectRejected(() -> BlockPolicy.allowAll().withConstellationBlocked(9, true), "unknown UI provider rejects");

        String[] edgeCases = {"1", "1,", ",", ",,", "1,2,", "1,x,", "1,2,3", "1,0,1,2", "7,14,8,0", "01,+2"};
        for (String edge : edgeCases) {
            BlockPolicy parsed = BlockPolicy.parse(edge);
            String canonical = parsed.serialize();
            expect(BlockPolicy.parse(canonical).serialize().equals(canonical), "canonical edge stability: " + edge);
        }
        char[] oversizedChars = new char[131073];
        Arrays.fill(oversizedChars, '8');
        String oversized = new String(oversizedChars);
        BlockPolicy guarded = BlockPolicy.parse(oversized);
        expect(guarded.malformed() && !guarded.isEditable(), "oversized input is guarded");
        expect(oversized.equals(guarded.serialize()), "oversized input remains round-trippable");
        boolean rejected = false;
        try { guarded.withConstellationBlocked(1, true); }
        catch (IllegalStateException expected) { rejected = true; }
        expect(rejected, "oversized configuration cannot be silently replaced during edit");
        System.out.println("BlockPolicyTest: " + checks + " checks passed");
    }

    private static String expanded(int constellation, int count) {
        StringBuilder result = new StringBuilder();
        for (int svid = 1; svid <= count; svid++) {
            if (result.length() != 0) result.append(',');
            result.append(constellation).append(',').append(svid);
        }
        return result.toString();
    }

    private static void expect(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static void expectUnmodifiable(Runnable operation, String name) {
        boolean rejected = false;
        try { operation.run(); } catch (UnsupportedOperationException expected) { rejected = true; }
        expect(rejected, name);
    }

    private static void expectRejected(Runnable operation, String name) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        expect(rejected, name);
    }
}
