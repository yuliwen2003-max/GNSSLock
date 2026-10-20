package com.yuliwen.gnsslock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable Android GNSS blocklist. A pair is constellation,SVID; SVID zero
 * blocks the whole constellation. Unknown or damaged source pairs survive edits.
 *
 * <p>SVID ranges follow the public GnssStatus.getSvid contract:
 * https://developer.android.com/reference/android/location/GnssStatus#getSvid(int)
 * GLONASS 93..106 are frequency-channel identifiers, not additional orbital slots.
 */
public final class BlockPolicy {
    private static final int MAX_PARSE_LENGTH = 131072;
    private static final int[][][] RANGES = {
            {}, {{1, 32}}, {{120, 151}, {183, 192}}, {{1, 25}, {93, 106}},
            {{183, 212}}, {{1, 63}}, {{1, 36}}, {{1, 14}}
    };
    private static final List<List<Integer>> VALID_SVIDS = createValidSvids();

    private final boolean[] whole;
    private final Map<Integer, Set<Integer>> detailed;
    private final List<String> opaquePairs;
    private final String danglingToken;
    private final boolean malformed;
    private final String oversizedRaw;
    private final List<String> unknownEntries;

    private BlockPolicy(boolean[] whole, Map<Integer, ? extends Collection<Integer>> detailed,
            List<String> opaquePairs, String danglingToken, boolean malformed, String oversizedRaw) {
        this.whole = whole.clone();
        Map<Integer, Set<Integer>> copy = new LinkedHashMap<>();
        for (int constellation = 1; constellation <= 7; constellation++) {
            Collection<Integer> entries = detailed.get(constellation);
            TreeSet<Integer> svids = entries == null ? new TreeSet<>() : new TreeSet<>(entries);
            // The existing rooted BDS strategy expands 5,0 into all 63 satellites.
            // Both encodings describe one selection and must not create a dirty draft.
            if (constellation == 5 && svids.containsAll(validSvids(5))) this.whole[5] = true;
            if (!this.whole[constellation] && !svids.isEmpty()) {
                copy.put(constellation, Collections.unmodifiableSet(svids));
            }
        }
        this.detailed = Collections.unmodifiableMap(copy);
        this.opaquePairs = Collections.unmodifiableList(new ArrayList<>(opaquePairs));
        this.danglingToken = danglingToken;
        this.malformed = malformed;
        this.oversizedRaw = oversizedRaw;
        List<String> unknown = new ArrayList<>(opaquePairs);
        if (danglingToken != null) unknown.add(danglingToken);
        if (oversizedRaw != null) unknown.add(oversizedRaw);
        this.unknownEntries = Collections.unmodifiableList(unknown);
    }

    public static BlockPolicy allowAll() {
        return new BlockPolicy(new boolean[8], Collections.emptyMap(),
                Collections.emptyList(), null, false, null);
    }

    /** Parse defensively; malformed and unknown entries are retained verbatim as pairs. */
    public static BlockPolicy parse(String raw) {
        if (raw == null || raw.isEmpty()) return allowAll();
        if (raw.length() > MAX_PARSE_LENGTH) {
            // Do not tokenize unbounded settings data, and never truncate a configuration.
            return new BlockPolicy(new boolean[8], Collections.emptyMap(),
                    Collections.emptyList(), null, true, raw);
        }
        if (raw.trim().isEmpty()) return allowAll();
        boolean[] whole = new boolean[8];
        Map<Integer, Collection<Integer>> detailed = new LinkedHashMap<>();
        List<String> opaque = new ArrayList<>();
        String[] tokens = raw.split(",", -1);
        boolean malformed = tokens.length % 2 != 0;
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            String first = tokens[i].trim();
            String second = tokens[i + 1].trim();
            String pair = first + "," + second;
            try {
                int constellation = Integer.parseInt(first);
                int svid = Integer.parseInt(second);
                if (constellation < 0 || svid < 0) {
                    malformed = true;
                    opaque.add(pair);
                } else if (!isKnownConstellation(constellation)
                        || (svid != 0 && !isValidSvid(constellation, svid))) {
                    // Canonicalize valid numbers, including future constellation types.
                    opaque.add(constellation + "," + svid);
                } else if (svid == 0) {
                    whole[constellation] = true;
                } else {
                    Collection<Integer> svids = detailed.get(constellation);
                    if (svids == null) {
                        svids = new TreeSet<>();
                        detailed.put(constellation, svids);
                    }
                    svids.add(svid);
                }
            } catch (NumberFormatException ignored) {
                malformed = true;
                opaque.add(pair);
            }
        }
        String dangling = tokens.length % 2 == 0 ? null : tokens[tokens.length - 1].trim();
        return new BlockPolicy(whole, detailed, opaque, dangling, malformed, null);
    }

    public boolean isConstellationBlocked(int constellation) {
        return isKnownConstellation(constellation) && whole[constellation];
    }

    /** Explicit individual entries; whole-constellation entries return an empty set. */
    public Set<Integer> getBlockedSvids(int constellation) {
        Set<Integer> svids = detailed.get(constellation);
        return svids == null ? Collections.emptySet() : svids;
    }

    public boolean isSatelliteBlocked(int constellation, int svid) {
        return isConstellationBlocked(constellation) || getBlockedSvids(constellation).contains(svid);
    }

    /** Unknown constellations, invalid ranges, malformed pairs and trailing token. */
    public List<String> unknownEntries() { return unknownEntries; }

    public boolean malformed() { return malformed; }

    /** Oversized settings remain readable and round-trippable but cannot be edited. */
    public boolean isEditable() { return oversizedRaw == null; }

    public boolean hasBdsSelection() {
        return whole[5] || !getBlockedSvids(5).isEmpty();
    }

    public boolean requiresBdsAppend() { return whole[5]; }

    public boolean hasDetailedEntries() { return !detailed.isEmpty(); }

    /** Known entries are ordered numerically; opaque pairs keep their source order. */
    public String serialize() {
        if (oversizedRaw != null) return oversizedRaw;
        StringBuilder value = new StringBuilder();
        for (int constellation = 1; constellation <= 7; constellation++) {
            if (whole[constellation]) append(value, constellation + ",0");
            else for (int svid : getBlockedSvids(constellation)) {
                append(value, constellation + "," + svid);
            }
        }
        for (String pair : opaquePairs) append(value, pair);
        if (danglingToken != null) append(value, danglingToken);
        return value.toString();
    }

    /** Change only this provider's whole-constellation selection. */
    public BlockPolicy withConstellationBlocked(int constellation, boolean blocked) {
        requireEditable();
        requireConstellation(constellation);
        boolean[] changedWhole = whole.clone();
        changedWhole[constellation] = blocked;
        return copy(changedWhole, detailed);
    }

    /** Replace this provider's individual set and remove its whole-constellation entry. */
    public BlockPolicy withBlockedSvids(int constellation, Collection<Integer> svids) {
        requireEditable();
        requireConstellation(constellation);
        if (svids == null) throw new IllegalArgumentException("卫星列表不能为空");
        TreeSet<Integer> checked = new TreeSet<>();
        for (Integer svid : svids) {
            if (svid == null || !isValidSvid(constellation, svid)) {
                throw new IllegalArgumentException("卫星编号不在此星座的有效范围内：" + svid);
            }
            checked.add(svid);
        }
        boolean[] changedWhole = whole.clone();
        changedWhole[constellation] = false;
        Map<Integer, Collection<Integer>> changed = mutableDetailed();
        changed.put(constellation, checked);
        return copy(changedWhole, changed);
    }

    /** Unchecking one satellite in a whole block expands the remaining valid SVIDs. */
    public BlockPolicy withSatelliteBlocked(int constellation, int svid, boolean blocked) {
        requireEditable();
        requireConstellation(constellation);
        if (!isValidSvid(constellation, svid)) {
            throw new IllegalArgumentException("卫星编号不在此星座的有效范围内：" + svid);
        }
        if (whole[constellation] && blocked) return this;
        TreeSet<Integer> changed = new TreeSet<>(whole[constellation]
                ? validSvids(constellation) : getBlockedSvids(constellation));
        if (blocked) changed.add(svid);
        else changed.remove(svid);
        return withBlockedSvids(constellation, changed);
    }

    /** Clear known partial selections, retaining whole blocks and all opaque source data. */
    public BlockPolicy withoutDetailedEntries() {
        requireEditable();
        return copy(whole, Collections.emptyMap());
    }

    public static List<Integer> validSvids(int constellation) {
        return isKnownConstellation(constellation) ? VALID_SVIDS.get(constellation)
                : Collections.emptyList();
    }

    public static boolean isValidSvid(int constellation, int svid) {
        if (!isKnownConstellation(constellation)) return false;
        for (int[] range : RANGES[constellation]) {
            if (svid >= range[0] && svid <= range[1]) return true;
        }
        return false;
    }

    /** Each inclusive range is copied; modifying the result cannot affect this class. */
    public static int[][] getValidRanges(int constellation) {
        if (!isKnownConstellation(constellation)) return new int[0][];
        int[][] copy = new int[RANGES[constellation].length][];
        for (int i = 0; i < copy.length; i++) copy[i] = RANGES[constellation][i].clone();
        return copy;
    }

    public static int maxSvid(int constellation) {
        List<Integer> values = validSvids(constellation);
        return values.isEmpty() ? 0 : values.get(values.size() - 1);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlockPolicy)) return false;
        BlockPolicy policy = (BlockPolicy) other;
        return java.util.Arrays.equals(whole, policy.whole)
                && detailed.equals(policy.detailed) && opaquePairs.equals(policy.opaquePairs)
                && Objects.equals(danglingToken, policy.danglingToken)
                && Objects.equals(oversizedRaw, policy.oversizedRaw);
    }

    @Override public int hashCode() {
        int result = java.util.Arrays.hashCode(whole);
        result = 31 * result + detailed.hashCode();
        result = 31 * result + opaquePairs.hashCode();
        result = 31 * result + Objects.hashCode(danglingToken);
        return 31 * result + Objects.hashCode(oversizedRaw);
    }

    @Override public String toString() { return serialize(); }

    private BlockPolicy copy(boolean[] changedWhole,
            Map<Integer, ? extends Collection<Integer>> changedDetailed) {
        return new BlockPolicy(changedWhole, changedDetailed, opaquePairs,
                danglingToken, malformed, oversizedRaw);
    }

    private Map<Integer, Collection<Integer>> mutableDetailed() {
        Map<Integer, Collection<Integer>> changed = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : detailed.entrySet()) {
            changed.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        return changed;
    }

    private void requireEditable() {
        if (!isEditable()) throw new IllegalStateException("当前名单过长，无法编辑");
    }

    private static boolean isKnownConstellation(int constellation) {
        return constellation >= 1 && constellation <= 7;
    }

    private static void requireConstellation(int constellation) {
        if (!isKnownConstellation(constellation)) throw new IllegalArgumentException("未知星座：" + constellation);
    }

    private static List<List<Integer>> createValidSvids() {
        List<List<Integer>> result = new ArrayList<>();
        for (int[][] ranges : RANGES) {
            List<Integer> svids = new ArrayList<>();
            for (int[] range : ranges) {
                for (int svid = range[0]; svid <= range[1]; svid++) svids.add(svid);
            }
            result.add(Collections.unmodifiableList(svids));
        }
        return Collections.unmodifiableList(result);
    }

    private static void append(StringBuilder result, String entry) {
        if (result.length() != 0) result.append(',');
        result.append(entry);
    }
}
