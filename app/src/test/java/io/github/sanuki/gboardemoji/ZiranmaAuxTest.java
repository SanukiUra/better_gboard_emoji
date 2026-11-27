package io.github.sanuki.gboardemoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public final class ZiranmaAuxTest {
    private static ZiranmaAuxDictionary dict;
    private static ZiranmaAuxEngine engine;

    @BeforeClass
    public static void loadDictionary() throws Exception {
        try (InputStream in = ZiranmaAuxTest.class.getResourceAsStream("/ziranma-aux.bin")) {
            assertNotNull("ziranma-aux.bin must be bundled in test resources", in);
            dict = ZiranmaAuxDictionary.read(in);
            engine = new ZiranmaAuxEngine(dict);
        }
    }

    @Test
    public void fullZiProDictionaryLoadedWithCommonAndRareChars() {
        assertTrue("Expected > 40k unique chars", dict.charCount() > 40000);
        assertTrue("Expected > 55k entries", dict.entryCount() > 55000);

        // Common chars & zero-initial syllables
        assertTrue(dict.matchesChar("镇".codePointAt(0), "vf", "j"));
        assertTrue(dict.matchesChar("镇".codePointAt(0), "vf", "jv"));
        assertTrue(dict.matchesChar("你".codePointAt(0), "ni", "r"));
        assertTrue(dict.matchesChar("你".codePointAt(0), "ni", "re"));
        assertTrue(dict.matchesChar("爱".codePointAt(0), "al", "vy"));
        assertTrue(dict.matchesChar("啊".codePointAt(0), "aa", "ka"));

        // Polyphonic char: 长 (cháng -> ih, zhǎng -> vh)
        assertTrue(dict.isValidSyllable("ih"));
        assertTrue(dict.isValidSyllable("vh"));

        // Supplementary CJK Extension B+ surrogate pair char (𠼞 ā;hy -> aa + hy)
        int extCp = "𠼞".codePointAt(0);
        assertTrue(Character.isSupplementaryCodePoint(extCp));
        assertTrue(dict.matchesChar(extCp, "aa", "hy"));
    }

    @Test
    public void threeCodeSingleCharPromotesToTopWithGboardContextOrder() {
        ZiranmaAuxEngine.ShadowDecoder shadow = sp -> {
            if ("vf".equals(sp)) {
                return Arrays.asList(
                        new ZiranmaAuxEngine.DecodedCandidate("真", true),
                        new ZiranmaAuxEngine.DecodedCandidate("镇", true),
                        new ZiranmaAuxEngine.DecodedCandidate("针", true));
            }
            return Collections.emptyList();
        };

        ZiranmaAuxEngine.AuxPlan plan = engine.resolve("vfj", false, false, shadow);
        assertNotNull(plan);
        assertTrue(plan.promoteFirstToDefault);
        assertEquals(0, plan.insertAt);
        assertEquals("镇", plan.items.get(0).text);
        assertEquals(3, plan.items.get(0).consumedRawChars);
        assertEquals("", plan.items.get(0).remainingRawTail);
    }

    @Test
    public void fourCodeKeepsTwoCharWordFirstUnlessSlashOrNoWord() {
        ZiranmaAuxEngine.ShadowDecoder shadow = sp -> {
            if ("ni".equals(sp)) {
                return Arrays.asList(
                        new ZiranmaAuxEngine.DecodedCandidate("你", true),
                        new ZiranmaAuxEngine.DecodedCandidate("泥", true));
            }
            return Collections.emptyList();
        };

        // When Gboard has a 2-char word (primaryHasFullSpanWord = true), word stays #1.
        ZiranmaAuxEngine.AuxPlan wordFirst = engine.resolve("nire", false, true, shadow);
        assertNotNull(wordFirst);
        assertFalse(wordFirst.promoteFirstToDefault);
        assertEquals(1, wordFirst.insertAt);
        assertEquals("你", wordFirst.items.get(0).text);

        // Trailing '/' forces 4-code single char to #1.
        ZiranmaAuxEngine.AuxPlan slashForced = engine.resolve("nire", true, true, shadow);
        assertNotNull(slashForced);
        assertTrue(slashForced.promoteFirstToDefault);
        assertEquals(0, slashForced.insertAt);
        assertEquals("你", slashForced.items.get(0).text);

        // No 2-char word -> 4-code single char promotes to #1 automatically.
        ZiranmaAuxEngine.AuxPlan noWord = engine.resolve("nire", false, false, shadow);
        assertNotNull(noWord);
        assertTrue(noWord.promoteFirstToDefault);
        assertEquals(0, noWord.insertAt);
        assertEquals("你", noWord.items.get(0).text);
    }

    @Test
    public void wordAndSentenceMixedShuangpinWithAux() {
        Map<String, List<ZiranmaAuxEngine.DecodedCandidate>> mock = new HashMap<>();
        mock.put("vgju", Arrays.asList(
                new ZiranmaAuxEngine.DecodedCandidate("正剧", true),
                new ZiranmaAuxEngine.DecodedCandidate("整句", true),
                new ZiranmaAuxEngine.DecodedCandidate("证据", true)));
        mock.put("womfvf", Collections.singletonList(
                new ZiranmaAuxEngine.DecodedCandidate("我们真", true)));
        mock.put("vf", Arrays.asList(
                new ZiranmaAuxEngine.DecodedCandidate("真", true),
                new ZiranmaAuxEngine.DecodedCandidate("镇", true)));
        mock.put("vfni", Collections.singletonList(
                new ZiranmaAuxEngine.DecodedCandidate("真你", true)));

        ZiranmaAuxEngine.ShadowDecoder shadow = sp -> mock.getOrDefault(sp, Collections.emptyList());

        // Word + trailing 1-letter aux on 2nd char: vgjuf -> 证据 (据 has 扌=f)
        ZiranmaAuxEngine.AuxPlan trailingLast = engine.resolve("vgjuf", false, false, shadow);
        assertNotNull(trailingLast);
        assertTrue(trailingLast.promoteFirstToDefault);
        assertEquals("证据", trailingLast.items.get(0).text);

        // Word + trailing 1-letter aux matching 1st char (Wanxiang flexible direct aux): vgjuy -> 证据 (证 has 讠=y)
        ZiranmaAuxEngine.AuxPlan trailingFlex = engine.resolve("vgjuy", false, false, shadow);
        assertNotNull(trailingFlex);
        assertEquals("证据", trailingFlex.items.get(0).text);

        // Word + leading 1-letter aux on 1st char: vgyju -> 证据
        ZiranmaAuxEngine.AuxPlan leadingAux = engine.resolve("vgyju", false, false, shadow);
        assertNotNull(leadingAux);
        assertEquals("证据", leadingAux.items.get(0).text);

        // Full sentence fallback composition: womfvfj -> 我们镇 (replaces 真 with 镇)
        ZiranmaAuxEngine.AuxPlan sentence = engine.resolve("womfvfj", false, false, shadow);
        assertNotNull(sentence);
        assertTrue(sentence.promoteFirstToDefault);
        assertEquals("我们镇", sentence.items.get(0).text);
        assertEquals(7, sentence.items.get(0).consumedRawChars);
        assertEquals("", sentence.items.get(0).remainingRawTail);

        // Leading aux in multi-syllable input also offers prefix single-char with remaining tail
        ZiranmaAuxEngine.AuxPlan withPrefix = engine.resolve("vfjni", false, false, shadow);
        assertNotNull(withPrefix);
        assertEquals("镇你", withPrefix.items.get(0).text);
        boolean foundPrefix = false;
        for (ZiranmaAuxEngine.AuxItem item : withPrefix.items) {
            if ("镇".equals(item.text) && "ni".equals(item.remainingRawTail)) {
                foundPrefix = true;
                break;
            }
        }
        assertTrue("Expected prefix candidate '镇' with remainingRawTail='ni'", foundPrefix);

        // User real-world case: 燃烧 (rjh uk) -> rjhuk
        mock.put("rjuk", Collections.singletonList(new ZiranmaAuxEngine.DecodedCandidate("燃烧", true)));
        mock.put("rj", Arrays.asList(
                new ZiranmaAuxEngine.DecodedCandidate("然", true),
                new ZiranmaAuxEngine.DecodedCandidate("燃", true)));
        ZiranmaAuxEngine.AuxPlan ranshao = engine.resolve("rjhuk", false, false, shadow);
        assertNotNull(ranshao);
        assertEquals("燃烧", ranshao.items.get(0).text);
        assertEquals("rjh uk", ranshao.formattedComposing);
        boolean foundRan = false;
        for (ZiranmaAuxEngine.AuxItem item : ranshao.items) {
            if ("燃".equals(item.text) && "uk".equals(item.remainingRawTail)) {
                foundRan = true;
                break;
            }
        }
        assertTrue("Expected prefix '燃' keeping remainingRawTail='uk'", foundRan);
    }
}
