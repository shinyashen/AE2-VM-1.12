package com.ae2vm.replay;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;

/**
 * Platform-free stock list for offline replay. {@code appeng.util.item
 * .ItemList} pulls {@code Platform}/{@code AEConfig} statics that need a
 * booted Forge side — unusable in a bare JVM. This implementation covers
 * exactly what {@link NetworkCraftingSandbox} asks of a stock list:
 *
 * <ul>
 *   <li>{@code add}/{@code findPrecise} — value-equality merge
 *       (item + damage + NBT, i.e. full stack identity);</li>
 *   <li>{@code findFuzzy(IGNORE_ALL)} — the family boundary fixed in M1:
 *       SAME item + SAME damage, NBT variants only, key included,
 *       insertion order (deterministic — no Platform comparator).</li>
 * </ul>
 */
public final class HeadlessItemList implements IItemList<IAEItemStack> {

    private final Map<IAEItemStack, IAEItemStack> map = new LinkedHashMap<>();

    @Override
    public void add(IAEItemStack option) {
        if (option == null) {
            return;
        }
        IAEItemStack existing = map.get(option);
        if (existing == null) {
            map.put(option, option);
        } else {
            existing.incStackSize(option.getStackSize());
        }
    }

    @Override
    public IAEItemStack findPrecise(IAEItemStack i) {
        return i == null ? null : map.get(i);
    }

    @Override
    public Collection<IAEItemStack> findFuzzy(IAEItemStack input, FuzzyMode fuzzy) {
        List<IAEItemStack> family = new ArrayList<>();
        if (input == null) {
            return family;
        }
        Item identity = input.getItem();
        int damage = input.getItemDamage();
        for (IAEItemStack s : map.values()) {
            if (s.getItem() == identity && s.getItemDamage() == damage) {
                family.add(s);
            }
        }
        return family;
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public void addStorage(IAEItemStack option) {
        add(option);
    }

    @Override
    public void addCrafting(IAEItemStack option) {
        add(option);
    }

    @Override
    public void addRequestable(IAEItemStack option) {
        add(option);
    }

    @Override
    public IAEItemStack getFirstItem() {
        return map.isEmpty() ? null : map.values().iterator().next();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Iterator<IAEItemStack> iterator() {
        return map.values().iterator();
    }

    @Override
    public void resetStatus() {
        for (IAEItemStack s : map.values()) {
            s.reset();
        }
    }
}
