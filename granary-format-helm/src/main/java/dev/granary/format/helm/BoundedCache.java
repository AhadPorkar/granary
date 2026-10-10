package dev.granary.format.helm;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * A small least-recently-used cache bounded by entry count and by a total weight (bytes or index entries), for
 * the derived documents a large upstream index produces. Keys embed the digest of what the value was computed
 * from, so a value is never stale; it is only dropped when room is needed.
 */
final class BoundedCache<K, V> {

    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<V> weigher;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    BoundedCache(int maxEntries, long maxWeight, ToLongFunction<V> weigher) {
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.weigher = weigher;
    }

    synchronized V getIfPresent(K key) {
        return map.get(key);
    }

    /** The cached value, or one computed now (outside the lock, so a slow computation blocks nobody else). */
    V get(K key, Supplier<V> compute) {
        V value = getIfPresent(key);
        if (value != null) {
            return value;
        }
        value = compute.get();
        put(key, value);
        return value;
    }

    synchronized void put(K key, V value) {
        long w = Math.max(0, weigher.applyAsLong(value));
        if (w > maxWeight) {
            return; // larger than the whole cache: computing it again is cheaper than evicting everything
        }
        V previous = map.put(key, value);
        if (previous != null) {
            weight -= weigher.applyAsLong(previous);
        }
        weight += w;
        Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
        while ((weight > maxWeight || map.size() > maxEntries) && it.hasNext()) {
            Map.Entry<K, V> eldest = it.next();
            if (eldest.getKey().equals(key)) {
                continue;
            }
            weight -= weigher.applyAsLong(eldest.getValue());
            it.remove();
        }
    }

    synchronized int size() {
        return map.size();
    }

    synchronized long weight() {
        return weight;
    }

    synchronized void clear() {
        map.clear();
        weight = 0;
    }
}
