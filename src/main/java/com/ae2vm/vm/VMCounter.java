package com.ae2vm.vm;

import appeng.api.storage.data.IAEItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.12 replacement for AE2 1.21's KeyCounter: a mutable key -> long multiset.
 *
 * AEItemStack.equals() is isSameType (item+damage+NBT, size agnostic) and its
 * hash code is type based, so a HashMap keyed on normalized copies behaves as a
 * type-keyed counter. Iteration order is insertion order, matching the
 * deterministic plan output of the original.
 */
public final class VMCounter {

    private static final class Entry {
        final IAEItemStack key;
        long value;

        Entry(IAEItemStack key, long value) {
            this.key = key;
            this.value = value;
        }
    }

    private final Map<IAEItemStack, Entry> entries = new LinkedHashMap<>();

    private static IAEItemStack normalize(IAEItemStack key) {
        IAEItemStack copy = key.copy();
        copy.reset();
        copy.setStackSize(1);
        return copy;
    }

    private Entry find(IAEItemStack key) {
        return entries.get(key);
    }

    public void add(IAEItemStack key, long amount) {
        if (amount == 0) {
            return;
        }
        Entry e = find(key);
        if (e == null) {
            e = new Entry(normalize(key), amount);
            entries.put(e.key, e);
        } else {
            e.value += amount;
        }
        if (e.value == 0) {
            entries.remove(e.key);
        }
    }

    public long get(IAEItemStack key) {
        Entry e = find(key);
        return e == null ? 0L : e.value;
    }

    public void remove(IAEItemStack key) {
        entries.remove(key);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void reset() {
        entries.clear();
    }

    public void addAll(VMCounter other) {
        for (Entry e : other.entries.values()) {
            add(e.key, e.value);
        }
    }

    public void subtract(VMCounter other) {
        // Iterate over a copy: add() may remove zeroed entries of this map,
        // but only keys present in `other` are touched.
        for (Entry e : other.entries.values().toArray(new Entry[0])) {
            add(e.key, -e.value);
        }
    }

    public Iterable<IAEItemStack> keys() {
        return new Iterable<IAEItemStack>() {
            @Override
            public java.util.Iterator<IAEItemStack> iterator() {
                return new java.util.Iterator<IAEItemStack>() {
                    private final java.util.Iterator<Entry> it = entries.values().iterator();

                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public IAEItemStack next() {
                        return it.next().key;
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    public long getValue(IAEItemStack key) {
        return get(key);
    }

    public Iterable<Map.Entry<IAEItemStack, Long>> entrySet() {
        return new Iterable<Map.Entry<IAEItemStack, Long>>() {
            @Override
            public java.util.Iterator<Map.Entry<IAEItemStack, Long>> iterator() {
                return new java.util.Iterator<Map.Entry<IAEItemStack, Long>>() {
                    private final java.util.Iterator<Entry> it = entries.values().iterator();

                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Map.Entry<IAEItemStack, Long> next() {
                        Entry e = it.next();
                        return new java.util.AbstractMap.SimpleImmutableEntry<>(e.key, e.value);
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }
}
