package io.github.sanuki.gboardemoji;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory Ziranma Shuangpin + Wanxiang auxiliary code index compiled from zi.pro.dict.yaml.
 * Entries are bucketed by 2-letter Shuangpin ID (0..675) and pre-sorted by frequency descending.
 */
public final class ZiranmaAuxDictionary {
    private static final int MAGIC = 0x5A524158; // "ZRAX"
    private static final int BUCKETS = 676;

    private final int[] offsets;
    private final int[] codePoints;
    private final short[] auxPacked;
    private final int[] weights;
    private final Map<Integer, int[]> byCodePoint;

    private ZiranmaAuxDictionary(int[] offsets, int[] codePoints, short[] auxPacked, int[] weights) {
        this.offsets = offsets;
        this.codePoints = codePoints;
        this.auxPacked = auxPacked;
        this.weights = weights;
        this.byCodePoint = buildReverseIndex(offsets, codePoints, auxPacked);
    }

    public static ZiranmaAuxDictionary read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 32768));
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Invalid ziranma-aux magic: 0x" + Integer.toHexString(magic));
        int version = in.readUnsignedShort();
        if (version != 1) throw new IOException("Unsupported ziranma-aux version: " + version);
        int total = in.readInt();
        int[] offsets = new int[BUCKETS + 1];
        for (int i = 0; i <= BUCKETS; i++) offsets[i] = in.readInt();
        int[] codePoints = new int[total];
        short[] auxPacked = new short[total];
        int[] weights = new int[total];
        for (int i = 0; i < total; i++) {
            codePoints[i] = in.readInt();
            auxPacked[i] = (short) in.readUnsignedShort();
            weights[i] = in.readInt();
        }
        return new ZiranmaAuxDictionary(offsets, codePoints, auxPacked, weights);
    }

    private static Map<Integer, int[]> buildReverseIndex(int[] offsets, int[] codePoints, short[] auxPacked) {
        Map<Integer, int[]> counts = new HashMap<>(65536);
        int[] sizeByCp = new int[0];
        Map<Integer, Integer> freq = new HashMap<>(65536);
        for (int cp : codePoints) {
            Integer prev = freq.get(cp);
            freq.put(cp, prev == null ? 1 : prev + 1);
        }
        for (Map.Entry<Integer, Integer> entry : freq.entrySet()) {
            counts.put(entry.getKey(), new int[entry.getValue()]);
        }
        Map<Integer, Integer> cursor = new HashMap<>(freq.size());
        for (int sid = 0; sid < BUCKETS; sid++) {
            int start = offsets[sid], end = offsets[sid + 1];
            int sidShift = sid << 10;
            for (int i = start; i < end; i++) {
                int cp = codePoints[i];
                int[] arr = counts.get(cp);
                Integer idxObj = cursor.get(cp);
                int idx = idxObj == null ? 0 : idxObj;
                arr[idx] = sidShift | (auxPacked[i] & 0x3FF);
                cursor.put(cp, idx + 1);
            }
        }
        return counts;
    }

    public int entryCount() { return codePoints.length; }
    public int charCount() { return byCodePoint.size(); }

    public static int spId(String sp) {
        if (sp == null || sp.length() != 2) return -1;
        int a = sp.charAt(0) - 'a';
        int b = sp.charAt(1) - 'a';
        if (a < 0 || a >= 26 || b < 0 || b >= 26) return -1;
        return a * 26 + b;
    }

    public boolean isValidSyllable(String sp) {
        int sid = spId(sp);
        return sid >= 0 && offsets[sid] < offsets[sid + 1];
    }

    public boolean hasMatch(String sp, String aux) {
        int sid = spId(sp);
        if (sid < 0 || aux == null || aux.isEmpty() || aux.length() > 2) return false;
        int target = encodeAuxQuery(aux);
        if (target < 0) return false;
        boolean prefixOnly = aux.length() == 1;
        int start = offsets[sid], end = offsets[sid + 1];
        for (int i = start; i < end; i++) {
            if (auxMatches(auxPacked[i] & 0x3FF, target, prefixOnly)) return true;
        }
        return false;
    }

    public List<String> matchSingleChars(String sp, String aux, int limit) {
        int sid = spId(sp);
        if (sid < 0 || aux == null || aux.isEmpty() || aux.length() > 2 || limit <= 0) {
            return java.util.Collections.emptyList();
        }
        int target = encodeAuxQuery(aux);
        if (target < 0) return java.util.Collections.emptyList();
        boolean prefixOnly = aux.length() == 1;
        int start = offsets[sid], end = offsets[sid + 1];
        List<String> result = new ArrayList<>(Math.min(limit, 16));
        Set<Integer> seen = new HashSet<>();
        for (int i = start; i < end && result.size() < limit; i++) {
            if (!auxMatches(auxPacked[i] & 0x3FF, target, prefixOnly)) continue;
            int cp = codePoints[i];
            if (seen.add(cp)) result.add(new String(Character.toChars(cp)));
        }
        return result;
    }

    public boolean matchesChar(int codePoint, String sp, String aux) {
        int sid = spId(sp);
        if (sid < 0 || aux == null || aux.isEmpty() || aux.length() > 2) return false;
        int target = encodeAuxQuery(aux);
        if (target < 0) return false;
        int[] entries = byCodePoint.get(codePoint);
        if (entries == null) return false;
        boolean prefixOnly = aux.length() == 1;
        for (int packed : entries) {
            if ((packed >>> 10) == sid && auxMatches(packed & 0x3FF, target, prefixOnly)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesCharAnySyllable(int codePoint, String aux) {
        if (aux == null || aux.isEmpty() || aux.length() > 2) return false;
        int target = encodeAuxQuery(aux);
        if (target < 0) return false;
        int[] entries = byCodePoint.get(codePoint);
        if (entries == null) return false;
        boolean prefixOnly = aux.length() == 1;
        for (int packed : entries) {
            if (auxMatches(packed & 0x3FF, target, prefixOnly)) return true;
        }
        return false;
    }

    private static int encodeAuxQuery(String aux) {
        int a = aux.charAt(0) - 'a';
        if (a < 0 || a >= 26) return -1;
        if (aux.length() == 1) return a;
        int b = aux.charAt(1) - 'a';
        if (b < 0 || b >= 26) return -1;
        return (a << 5) | b;
    }

    private static boolean auxMatches(int packed, int query, boolean prefixOnly) {
        if (prefixOnly) return ((packed >>> 5) & 0x1F) == query;
        return packed == query;
    }

    /** Test helper to construct small in-memory dictionaries without reading a file. */
    static ZiranmaAuxDictionary fromTestEntries(List<TestEntry> entries) {
        @SuppressWarnings("unchecked")
        List<TestEntry>[] buckets = new List[BUCKETS];
        for (TestEntry e : entries) {
            int sid = spId(e.sp);
            if (sid < 0) continue;
            if (buckets[sid] == null) buckets[sid] = new ArrayList<>();
            buckets[sid].add(e);
        }
        int total = 0;
        for (List<TestEntry> b : buckets) if (b != null) total += b.size();
        int[] offsets = new int[BUCKETS + 1];
        int[] cps = new int[total];
        short[] auxs = new short[total];
        int[] wts = new int[total];
        int pos = 0;
        for (int sid = 0; sid < BUCKETS; sid++) {
            offsets[sid] = pos;
            List<TestEntry> b = buckets[sid];
            if (b != null) {
                b.sort((x, y) -> Integer.compare(y.weight, x.weight));
                for (TestEntry e : b) {
                    cps[pos] = e.cp;
                    int a = e.aux.charAt(0) - 'a';
                    int second = e.aux.length() > 1 ? e.aux.charAt(1) - 'a' : 26;
                    auxs[pos] = (short) ((a << 5) | second);
                    wts[pos] = e.weight;
                    pos++;
                }
            }
        }
        offsets[BUCKETS] = pos;
        return new ZiranmaAuxDictionary(offsets, cps, auxs, wts);
    }

    static final class TestEntry {
        final int cp;
        final String sp;
        final String aux;
        final int weight;
        TestEntry(String ch, String sp, String aux, int weight) {
            this.cp = ch.codePointAt(0);
            this.sp = sp;
            this.aux = aux;
            this.weight = weight;
        }
    }
}
