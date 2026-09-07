package org.graphiks.kalligraphie.font.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lock-free least-recently-used retention for immutable values with an explicit byte weight.
 *
 * Callers provide complete immutable values only. Reads may race with replacement or eviction,
 * but each call returns either one complete previously published value or no value. A value whose
 * weight exceeds the budget is deliberately not retained. The cache holds neither resource leases
 * nor callbacks, so clearing it never affects an active materialization.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class WeightedEvictableCache<Key : Any, Value : Any>(
    private val maximumRetainedBytes: Long,
) {
    private val state = AtomicReference(CacheState<Key, Value>(entries = emptyList(), retainedBytes = 0L))

    init {
        require(maximumRetainedBytes >= 0L) { "maximumRetainedBytes must not be negative." }
    }

    /** Returns [key]'s complete retained value and promotes it to most recently used, when present. */
    fun get(key: Key): Value? {
        while (true) {
            val current = state.load()
            val index = current.entries.indexOfFirst { entry -> entry.key == key }
            if (index < 0) return null
            val entry = current.entries[index]
            if (index == current.entries.lastIndex) return entry.value
            val promoted = current.entries.toMutableList().also { entries ->
                entries.removeAt(index)
                entries += entry
            }
            val next = CacheState(entries = promoted, retainedBytes = current.retainedBytes)
            if (state.compareAndSet(current, next)) return entry.value
        }
    }

    /**
     * Retains one complete [value] under [key] when its exact estimated [retainedBytes] fits.
     *
     * Existing least-recently-used values are evicted atomically until the new total fits. An
     * oversized value is left unretained rather than causing a failed materialization.
     */
    fun put(
        key: Key,
        value: Value,
        retainedBytes: Long,
    ) {
        require(retainedBytes > 0L) { "retainedBytes must be positive." }
        if (maximumRetainedBytes == 0L || retainedBytes > maximumRetainedBytes) return
        while (true) {
            val current = state.load()
            val retained = current.entries.filterNot { entry -> entry.key == key }.toMutableList()
            retained += CacheEntry(key = key, value = value, retainedBytes = retainedBytes)
            while (retained.exceeds(maximumRetainedBytes)) retained.removeAt(0)
            val next = CacheState(entries = retained, retainedBytes = retained.totalRetainedBytes())
            if (state.compareAndSet(current, next)) return
        }
    }

    /** Releases every retained value without changing any caller-owned resource or result. */
    fun clear() {
        while (true) {
            val current = state.load()
            if (current.entries.isEmpty()) return
            if (state.compareAndSet(current, CacheState(entries = emptyList(), retainedBytes = 0L))) return
        }
    }
}

private data class CacheState<Key : Any, Value : Any>(
    val entries: List<CacheEntry<Key, Value>>,
    val retainedBytes: Long,
)

private data class CacheEntry<Key : Any, Value : Any>(
    val key: Key,
    val value: Value,
    val retainedBytes: Long,
)

private fun <Key : Any, Value : Any> List<CacheEntry<Key, Value>>.totalRetainedBytes(): Long {
    var total = 0L
    for (entry in this) {
        total += entry.retainedBytes
    }
    return total
}

private fun <Key : Any, Value : Any> List<CacheEntry<Key, Value>>.exceeds(maximumRetainedBytes: Long): Boolean {
    var total = 0L
    for (entry in this) {
        if (entry.retainedBytes > maximumRetainedBytes - total) return true
        total += entry.retainedBytes
    }
    return false
}
