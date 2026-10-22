package io.github.sanuki.gboardemoji;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JVM regression coverage; no Android runtime or installed Gboard is required. */
public final class EmojiRegressionTest {
    private EmojiDictionary dictionary;
    private ReflectiveCandidateAccess access;
    private CandidateAugmenter augmenter;

    public enum Kind { RECOMMENDATION, PREDICTION, READING_TEXT }

    public static final class Candidate {
        public final CharSequence a, b, d;
        public final String c;
        public final Kind e;
        public final boolean f, g;
        public final Object m, u;

        Candidate(Builder source) {
            a = source.a; b = source.b; c = source.c; d = source.d; e = source.e;
            f = source.f; g = source.g; m = source.m; u = source.u;
        }
    }

    public static final class Builder {
        public CharSequence a, b, d;
        public String c;
        public Kind e = Kind.RECOMMENDATION;
        public boolean f = true, g = true;
        public Object m = 0, u = new Object();

        public void b(Candidate source) {
            a = source.a; b = source.b; c = source.c; d = source.d; e = source.e;
            f = source.f; g = source.g; m = source.m; u = source.u;
        }

        public Candidate a() { return new Candidate(this); }
    }

    private static Candidate candidate(String word, Kind kind, int index) {
        Builder builder = new Builder();
        builder.a = word; builder.b = word; builder.c = word; builder.d = "annotation";
        builder.e = kind; builder.m = index;
        return builder.a();
    }

    @Before public void setUp() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/emoji-map.tsv");
        assertNotNull("The Rime mapping must be on the test resource classpath", stream);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            dictionary = EmojiDictionary.read(reader);
        }
        String properties = "candidateClass=" + Candidate.class.getName() + "\nbuilderClass=" + Builder.class.getName()
                + "\nbuilderCopy=b\nbuilderBuild=a\ncommitTextField=a\ndisplayTextField=b\ndescriptionField=c"
                + "\nannotationField=d\nkindField=e\ndataField=m\nremovableField=f\nhighlightField=g\ndecorationField=u\n";
        HookProfile profile = HookProfile.read(new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
        access = new ReflectiveCandidateAccess(getClass().getClassLoader(), profile);
        augmenter = new CandidateAugmenter(dictionary, access);
    }

    @Test public void importedRimeMappingsKeepOrderAndEmojiSequences() {
        assertEquals(Arrays.asList("😂", "🤣"), dictionary.lookup("笑哭"));
        assertEquals("😂", dictionary.lookup(" 笑哭 ").get(0));
        assertTrue(dictionary.lookup("xiaoku").isEmpty());
        assertTrue(dictionary.lookup("xcku").isEmpty());
        assertTrue(dictionary.lookup("不存在的映射").isEmpty());
        assertTrue(dictionary.lookup("美刀").isEmpty());
        assertTrue(dictionary.lookup("牛皮").contains("🐮🍺"));
        assertTrue(dictionary.lookup("星号").contains("*️⃣"));
    }

    @Test public void dictionaryHandlesEscapedTabsDuplicatesAndPlainValues() throws Exception {
        EmojiDictionary mixed = EmojiDictionary.read(new StringReader("\uFEFF笑哭\t😂\\t🤣\r\n笑哭\t😂\t😄\n美元\t$\\t💵\n家庭\t👨‍👩‍👧‍👦\n# comment\n"));
        assertEquals(Arrays.asList("😂", "🤣", "😄"), mixed.lookup("笑哭"));
        assertEquals(Collections.singletonList("💵"), mixed.lookup("美元"));
        assertEquals("👨‍👩‍👧‍👦", mixed.lookup("家庭").get(0));
        assertThrows(java.io.IOException.class, () -> EmojiDictionary.read(new StringReader("broken line")));
    }

    @Test public void insertsSecondWithoutMutatingDefaultOrSourceList() throws Exception {
        Candidate first = candidate("笑哭", Kind.RECOMMENDATION, 0);
        Candidate second = candidate("小库", Kind.RECOMMENDATION, 1);
        List<Candidate> original = Collections.unmodifiableList(Arrays.asList(first, second));
        List<?> result = augmenter.augment(original);
        assertEquals(3, result.size());
        assertSame(first, result.get(0));
        assertSame(second, result.get(2));
        assertEquals(2, original.size());
        assertEquals("笑哭", first.a);
        Candidate emoji = (Candidate) result.get(1);
        assertEquals("😂", emoji.a);
        assertEquals("😂", emoji.b);
        assertEquals("😂", emoji.c);
        assertFalse(emoji.f);
        assertFalse(emoji.g);
        assertNull(emoji.u);
        assertNull(emoji.d);
        assertEquals("😂", access.emoji(emoji));
        assertNull(access.emoji(first));
        assertSame(result, augmenter.augment(result));
        assertFalse(access.metadata(first).contains("笑哭"));
        assertEquals(2, augmenter.augment(Collections.singletonList(candidate("笑哭", Kind.RECOMMENDATION, 20))).size());
    }

    @Test public void paginationUsesIteratorIdentityInsteadOfNativeCandidateId() {
        CandidateBatchGate gate = new CandidateBatchGate();
        Object processor = new Object(), iterator = new Object(), nextIterator = new Object();
        assertTrue(gate.first(processor, iterator));
        assertFalse(gate.first(processor, iterator));
        assertTrue(gate.first(processor, nextIterator));
        assertTrue(gate.first(new Object(), iterator));
        assertFalse(gate.first(processor, null));
        assertFalse(gate.first(null, iterator));
        assertFalse(gate.first(processor, nextIterator));
        assertTrue(gate.first(processor, new String("same")));
        assertTrue(gate.first(processor, new String("same")));
    }

    @Test public void leavesEmptyUnmappedPredictionAndReadingListsAlone() throws Exception {
        assertNull(augmenter.augment(null));
        List<?> empty = Collections.emptyList();
        assertSame(empty, augmenter.augment(empty));
        for (Candidate source : Arrays.asList(candidate("没有对应词", Kind.RECOMMENDATION, 0),
                candidate("笑哭", Kind.PREDICTION, 0), candidate("笑哭", Kind.READING_TEXT, 0),
                candidate("笑哭", Kind.RECOMMENDATION, -1))) {
            List<?> original = Collections.singletonList(source);
            assertSame(original, augmenter.augment(original));
        }
    }

    @Test public void nativeEmojiIsReusedAndKeepsItsNativeSelectionFlow() throws Exception {
        Candidate first = candidate("笑哭", Kind.RECOMMENDATION, 0);
        Candidate second = candidate("小库", Kind.RECOMMENDATION, 1);
        Candidate nativeEmoji = candidate("😂", Kind.RECOMMENDATION, 7);
        List<?> dedup = augmenter.augment(Arrays.asList(first, second, nativeEmoji));
        assertEquals(3, dedup.size());
        assertSame(nativeEmoji, dedup.get(1));
        assertSame(second, dedup.get(2));
        assertNull(access.emoji(nativeEmoji));
        List<?> ordered = Arrays.asList(first, nativeEmoji, second);
        assertSame(ordered, augmenter.augment(ordered));
    }

    @Test public void emojiSelectionCommitsOnceThenResetsDecoder() throws Exception {
        List<String> operations = new ArrayList<>();
        EmojiSelection.Operations selection = new EmojiSelection.Operations() {
            public void commit(String value) { operations.add("commit:" + value); }
            public void reset() { operations.add("reset"); }
        };
        assertFalse(EmojiSelection.handle(null, true, selection));
        assertTrue(operations.isEmpty());
        assertTrue(EmojiSelection.handle("😂", false, selection));
        assertTrue(operations.isEmpty());
        assertTrue(EmojiSelection.handle("😂", true, selection));
        assertEquals(Arrays.asList("commit:😂", "reset"), operations);
    }
}
