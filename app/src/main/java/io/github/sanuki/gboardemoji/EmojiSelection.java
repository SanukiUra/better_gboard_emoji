package io.github.sanuki.gboardemoji;

/** Runs inside the decoder worker, so no InputConnection or cross-thread patching is needed. */
public final class EmojiSelection {
    public interface Operations {
        void commit(String emoji) throws Exception;
        void reset() throws Exception;
    }

    public static boolean handle(String emoji, boolean commit, Operations operations) throws Exception {
        if (emoji == null) return false;
        if (commit) {
            operations.commit(emoji);
            operations.reset();
        }
        return true;
    }
}
