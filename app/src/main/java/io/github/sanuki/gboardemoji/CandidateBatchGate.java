package io.github.sanuki.gboardemoji;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Pagination uses the same iterator; a new composition installs a new iterator. */
public final class CandidateBatchGate {
    private final Map<Object, WeakReference<Object>> previous = new WeakHashMap<>();

    public synchronized boolean first(Object processor, Object iterator) {
        if (processor == null || iterator == null) return false;
        WeakReference<Object> reference = previous.get(processor);
        if (reference != null && reference.get() == iterator) return false;
        previous.put(processor, new WeakReference<>(iterator));
        return true;
    }
}
