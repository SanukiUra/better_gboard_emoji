package io.github.sanuki.gboardemoji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wanxiang Ziranma auxiliary code decoder layered on top of Gboard's Shuangpin HMM engine.
 * Supports seamless mixing of 2-letter Shuangpin with optional 1-2 letter Ziranma shape codes
 * on single chars, multi-char words, and full sentences, plus trailing '/' single-char mode.
 */
public final class ZiranmaAuxEngine {
    private static final int MAX_SINGLE_RESULTS = 40;
    private static final int MAX_SEGMENTATIONS = 4;
    private static final int MAX_INPUT_LENGTH = 28;

    public interface ShadowDecoder {
        List<DecodedCandidate> decode(String pureShuangpin);
    }

    public static final class DecodedCandidate {
        public final String text;
        public final boolean fullSpan;
        public final int charCount;

        public DecodedCandidate(String text, boolean fullSpan) {
            this.text = text;
            this.fullSpan = fullSpan;
            this.charCount = text == null ? 0 : text.codePointCount(0, text.length());
        }
    }

    public static final class AuxItem {
        public final String text;
        public final int consumedRawChars;
        public final String remainingRawTail;

        public AuxItem(String text, int consumedRawChars, String remainingRawTail) {
            this.text = text;
            this.consumedRawChars = consumedRawChars;
            this.remainingRawTail = remainingRawTail;
        }
    }

    public static final class AuxPlan {
        public final List<AuxItem> items;
        public final boolean promoteFirstToDefault;
        public final int insertAt;
        public final String formattedComposing;

        public AuxPlan(List<AuxItem> items, boolean promoteFirstToDefault, int insertAt, String formattedComposing) {
            this.items = items;
            this.promoteFirstToDefault = promoteFirstToDefault;
            this.insertAt = insertAt;
            this.formattedComposing = formattedComposing;
        }
    }

    private static final class Syllable {
        final String sp;
        final String aux;
        final int rawLen;

        Syllable(String sp, String aux) {
            this.sp = sp;
            this.aux = aux;
            this.rawLen = 2 + aux.length();
        }
    }

    private static final class Segmentation {
        final List<Syllable> syllables;
        final int auxSyllableCount;
        final int fourCodeCount;

        Segmentation(List<Syllable> syllables, int auxSyllableCount, int fourCodeCount) {
            this.syllables = syllables;
            this.auxSyllableCount = auxSyllableCount;
            this.fourCodeCount = fourCodeCount;
        }

        String coreShuangpin() {
            StringBuilder sb = new StringBuilder(syllables.size() * 2);
            for (Syllable s : syllables) sb.append(s.sp);
            return sb.toString();
        }
    }

    private final ZiranmaAuxDictionary dict;

    public ZiranmaAuxEngine(ZiranmaAuxDictionary dict) {
        this.dict = dict;
    }

    public AuxPlan resolve(
            String rawTail,
            boolean slashMode,
            boolean primaryHasFullSpanWord,
            ShadowDecoder shadow) {
        if (rawTail == null || rawTail.length() < 3 || rawTail.length() > MAX_INPUT_LENGTH) return null;
        for (int i = 0; i < rawTail.length(); i++) {
            char c = rawTail.charAt(i);
            if (c < 'a' || c > 'z') return null;
        }

        Map<String, List<DecodedCandidate>> cache = new HashMap<>();
        int len = rawTail.length();

        if (len == 3) {
            String sp = rawTail.substring(0, 2);
            String aux = rawTail.substring(2, 3);
            List<String> chars = resolveSingleChars(sp, aux, MAX_SINGLE_RESULTS, shadow, cache);
            if (chars.isEmpty()) return null;
            return new AuxPlan(toFullItems(chars, 3), true, 0, slashMode ? rawTail + "/" : rawTail);
        }

        if (len == 4) {
            String sp = rawTail.substring(0, 2);
            String aux = rawTail.substring(2, 4);
            List<String> chars = resolveSingleChars(sp, aux, slashMode ? MAX_SINGLE_RESULTS : 4, shadow, cache);
            if (chars.isEmpty()) return null;
            if (slashMode || !primaryHasFullSpanWord) {
                return new AuxPlan(toFullItems(chars, 4), true, 0, slashMode ? rawTail + "/" : rawTail);
            }
            // Normal 2-char word stays #1; keep 4-code single chars immediately accessible right after #1.
            return new AuxPlan(toFullItems(chars, 4), false, 1, null);
        }

        // len >= 5: multi-syllable words and sentences mixing normal Shuangpin + auxiliary codes.
        List<Segmentation> segs = segment(rawTail);
        if (segs.isEmpty()) return null;

        Map<String, AuxItem> results = new LinkedHashMap<>();
        List<AuxItem> exactMatches = new ArrayList<>();
        List<AuxItem> flexMatches = new ArrayList<>();
        List<AuxItem> composedMatches = new ArrayList<>();
        Segmentation exactWinner = null;
        Segmentation flexWinner = null;
        Segmentation composedWinner = null;

        for (Segmentation seg : segs) {
            String core = seg.coreShuangpin();
            List<DecodedCandidate> decoded = cachedDecode(core, shadow, cache);
            int k = seg.syllables.size();

            // Pass A: exact per-syllable auxiliary match on full-span HMM candidates.
            boolean hitExact = false;
            for (DecodedCandidate cand : decoded) {
                if (cand.charCount != k) continue;
                if (matchesAllConstraints(cand.text, seg.syllables)) {
                    exactMatches.add(new AuxItem(cand.text, len, ""));
                    hitExact = true;
                }
            }
            if (hitExact && exactWinner == null) exactWinner = seg;

            // Pass B: for 2N + 1 (trailing 1-letter aux on a word), also allow matching any char in the word.
            if (exactMatches.isEmpty() && isTrailingSingleAux(seg)) {
                String trailingAux = seg.syllables.get(k - 1).aux;
                boolean hitFlex = false;
                for (DecodedCandidate cand : decoded) {
                    if (cand.charCount != k) continue;
                    if (matchesAnyCharInWord(cand.text, trailingAux)) {
                        flexMatches.add(new AuxItem(cand.text, len, ""));
                        hitFlex = true;
                    }
                }
                if (hitFlex && flexWinner == null) flexWinner = seg;
            }

            // Pass C: sentence composition fallback (take top HMM sentence and substitute constrained chars).
            if (exactMatches.isEmpty() && flexMatches.isEmpty()) {
                for (DecodedCandidate cand : decoded) {
                    if (cand.charCount != k) continue;
                    String composed = composeConstrainedSentence(cand.text, seg.syllables, shadow, cache);
                    if (composed != null) {
                        composedMatches.add(new AuxItem(composed, len, ""));
                        if (composedWinner == null) composedWinner = seg;
                        break;
                    }
                }
            }
        }

        for (AuxItem item : exactMatches) results.putIfAbsent(item.text, item);
        for (AuxItem item : flexMatches) results.putIfAbsent(item.text, item);
        for (AuxItem item : composedMatches) results.putIfAbsent(item.text, item);

        Segmentation winner = exactWinner != null ? exactWinner
                : (flexWinner != null ? flexWinner : (composedWinner != null ? composedWinner : segs.get(0)));

        // Offer prefix single-char selection if the winning segmentation (or any fallback) starts with an aux syllable.
        Syllable winnerFirst = winner.syllables.get(0);
        if (!winnerFirst.aux.isEmpty()) {
            addPrefixCandidates(results, winnerFirst, rawTail, shadow, cache);
        } else {
            for (Segmentation seg : segs) {
                Syllable first = seg.syllables.get(0);
                if (!first.aux.isEmpty()) {
                    addPrefixCandidates(results, first, rawTail, shadow, cache);
                    break;
                }
            }
        }

        if (results.isEmpty()) return null;
        boolean oddLength = (len % 2) != 0;
        boolean promote = oddLength || !primaryHasFullSpanWord;
        String formatted = promote ? formatSegmentation(winner) : null;
        return new AuxPlan(new ArrayList<>(results.values()), promote, promote ? 0 : 1, formatted);
    }

    public String formatComposing(
            String rawTail, boolean slashMode, boolean primaryHasFullSpanWord, ShadowDecoder shadow) {
        AuxPlan plan = resolve(rawTail, slashMode, primaryHasFullSpanWord, shadow);
        return plan == null ? null : plan.formattedComposing;
    }

    private void addPrefixCandidates(
            Map<String, AuxItem> results,
            Syllable first,
            String rawTail,
            ShadowDecoder shadow,
            Map<String, List<DecodedCandidate>> cache) {
        List<String> prefixChars = resolveSingleChars(first.sp, first.aux, 2, shadow, cache);
        String remaining = rawTail.substring(first.rawLen);
        for (String ch : prefixChars) {
            results.putIfAbsent(ch, new AuxItem(ch, first.rawLen, remaining));
        }
    }

    private static String formatSegmentation(Segmentation seg) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.syllables.size(); i++) {
            if (i > 0) sb.append(' ');
            Syllable s = seg.syllables.get(i);
            sb.append(s.sp).append(s.aux);
        }
        return sb.toString();
    }

    private List<String> resolveSingleChars(
            String sp,
            String aux,
            int limit,
            ShadowDecoder shadow,
            Map<String, List<DecodedCandidate>> cache) {
        if (!dict.hasMatch(sp, aux)) return Collections.emptyList();
        Set<String> ordered = new LinkedHashSet<>();

        // Tier 1: Gboard context/frequency-ranked single chars for this syllable that match the aux code.
        for (DecodedCandidate cand : cachedDecode(sp, shadow, cache)) {
            if (cand.charCount != 1) continue;
            int cp = cand.text.codePointAt(0);
            if (dict.matchesChar(cp, sp, aux)) {
                ordered.add(cand.text);
                if (ordered.size() >= limit) return new ArrayList<>(ordered);
            }
        }

        // Tier 2: Full zi.pro dictionary sorted by frequency descending (covers all ~50k chars).
        for (String ch : dict.matchSingleChars(sp, aux, limit)) {
            ordered.add(ch);
            if (ordered.size() >= limit) break;
        }
        return new ArrayList<>(ordered);
    }

    private List<DecodedCandidate> cachedDecode(
            String coreSp, ShadowDecoder shadow, Map<String, List<DecodedCandidate>> cache) {
        List<DecodedCandidate> cached = cache.get(coreSp);
        if (cached != null) return cached;
        List<DecodedCandidate> result = shadow == null ? Collections.emptyList() : shadow.decode(coreSp);
        if (result == null) result = Collections.emptyList();
        cache.put(coreSp, result);
        return result;
    }

    private List<Segmentation> segment(String input) {
        int n = input.length();
        @SuppressWarnings("unchecked")
        List<Segmentation>[] dp = new List[n + 1];
        dp[0] = Collections.singletonList(new Segmentation(Collections.emptyList(), 0, 0));

        for (int i = 0; i < n; i++) {
            List<Segmentation> cur = dp[i];
            if (cur == null || cur.isEmpty()) continue;
            if (i + 2 <= n) {
                String sp = input.substring(i, i + 2);
                if (dict.isValidSyllable(sp)) {
                    extend(dp, i + 2, cur, new Syllable(sp, ""));
                    if (i + 3 <= n) {
                        String a1 = input.substring(i + 2, i + 3);
                        if (dict.hasMatch(sp, a1)) {
                            extend(dp, i + 3, cur, new Syllable(sp, a1));
                        }
                    }
                    if (i + 4 <= n) {
                        String a2 = input.substring(i + 2, i + 4);
                        if (dict.hasMatch(sp, a2)) {
                            extend(dp, i + 4, cur, new Syllable(sp, a2));
                        }
                    }
                }
            }
        }

        List<Segmentation> done = dp[n];
        if (done == null || done.isEmpty()) return Collections.emptyList();
        List<Segmentation> withAux = new ArrayList<>();
        for (Segmentation s : done) {
            if (s.auxSyllableCount > 0) withAux.add(s);
        }
        sortSegmentations(withAux);
        return withAux.size() > MAX_SEGMENTATIONS ? withAux.subList(0, MAX_SEGMENTATIONS) : withAux;
    }

    private static void extend(List<Segmentation>[] dp, int nextIdx, List<Segmentation> prevs, Syllable syl) {
        List<Segmentation> target = dp[nextIdx];
        if (target == null) {
            target = new ArrayList<>();
            dp[nextIdx] = target;
        }
        int auxInc = syl.aux.isEmpty() ? 0 : 1;
        int fourInc = syl.aux.length() == 2 ? 1 : 0;
        for (Segmentation prev : prevs) {
            List<Syllable> nextSyls = new ArrayList<>(prev.syllables.size() + 1);
            nextSyls.addAll(prev.syllables);
            nextSyls.add(syl);
            target.add(new Segmentation(nextSyls, prev.auxSyllableCount + auxInc, prev.fourCodeCount + fourInc));
        }
        if (target.size() > MAX_SEGMENTATIONS * 2) {
            sortSegmentations(target);
            target.subList(MAX_SEGMENTATIONS * 2, target.size()).clear();
        }
    }

    private static void sortSegmentations(List<Segmentation> list) {
        list.sort(Comparator
                .comparingInt((Segmentation s) -> s.fourCodeCount)
                .thenComparingInt(s -> s.auxSyllableCount)
                .thenComparingInt(s -> trailingAuxRank(s)));
    }

    private static int trailingAuxRank(Segmentation s) {
        // Prefer trailing auxiliary code (e.g., 2+3 over 3+2) when counts are tied.
        int n = s.syllables.size();
        return (n > 0 && !s.syllables.get(n - 1).aux.isEmpty()) ? 0 : 1;
    }

    private boolean matchesAllConstraints(String text, List<Syllable> syllables) {
        int[] cps = toCodePoints(text);
        if (cps.length != syllables.size()) return false;
        for (int i = 0; i < cps.length; i++) {
            Syllable s = syllables.get(i);
            if (!s.aux.isEmpty() && !dict.matchesChar(cps[i], s.sp, s.aux)) return false;
        }
        return true;
    }

    private static boolean isTrailingSingleAux(Segmentation seg) {
        int n = seg.syllables.size();
        return seg.auxSyllableCount == 1 && n >= 2 && seg.syllables.get(n - 1).aux.length() == 1;
    }

    private boolean matchesAnyCharInWord(String text, String aux) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (dict.matchesCharAnySyllable(cp, aux)) return true;
        }
        return false;
    }

    private String composeConstrainedSentence(
            String baseText,
            List<Syllable> syllables,
            ShadowDecoder shadow,
            Map<String, List<DecodedCandidate>> cache) {
        int[] cps = toCodePoints(baseText);
        if (cps.length != syllables.size()) return null;
        StringBuilder sb = new StringBuilder(baseText.length());
        for (int i = 0; i < cps.length; i++) {
            Syllable s = syllables.get(i);
            if (s.aux.isEmpty() || dict.matchesChar(cps[i], s.sp, s.aux)) {
                sb.appendCodePoint(cps[i]);
            } else {
                List<String> replacements = resolveSingleChars(s.sp, s.aux, 1, shadow, cache);
                if (replacements.isEmpty()) return null;
                sb.append(replacements.get(0));
            }
        }
        return sb.toString();
    }

    private static int[] toCodePoints(String text) {
        int count = text.codePointCount(0, text.length());
        int[] cps = new int[count];
        int idx = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            cps[idx++] = cp;
            i += Character.charCount(cp);
        }
        return cps;
    }

    private static List<AuxItem> toFullItems(List<String> texts, int consumed) {
        List<AuxItem> items = new ArrayList<>(texts.size());
        for (String text : texts) items.add(new AuxItem(text, consumed, ""));
        return items;
    }
}
