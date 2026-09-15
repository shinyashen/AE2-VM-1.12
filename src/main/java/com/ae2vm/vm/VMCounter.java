package com.ae2vm.vm;

import appeng.api.storage.data.IAEItemStack;

import java.util.Iterator;
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

    /** Temporary forensic probe (ledger trace). */
    public static boolean TRACE_KEY;
    private final String traceTag = "VMCounter@" + Integer.toHexString(System.identityHashCode(this));

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
        if (TRACE_KEY) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder who = new StringBuilder();
            for (int i = 2; i < Math.min(6, st.length); i++) {
                String s = st[i].getClassName();
                if (!s.contains("VMCounter")) {
                    who.append(s.substring(s.lastIndexOf('.') + 1)).append(':')
                       .append(st[i].getLineNumber()).append(' ');
                }
            }
            System.out.println("[LEDGER] " + traceTag + " " + e.key + " "
                    + (amount > 0 ? "+" : "") + amount + " -> " + e.value + "  | " + who);
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
            public Iterator<IAEItemStack> iterator() {
                return new Iterator<IAEItemStack>() {
                    private final Iterator<Entry> it = entries.values().iterator();

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
            public Iterator<Map.Entry<IAEItemStack, Long>> iterator() {
                return new Iterator<Map.Entry<IAEItemStack, Long>>() {
                    private final Iterator<Entry> it = entries.values().iterator();

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
