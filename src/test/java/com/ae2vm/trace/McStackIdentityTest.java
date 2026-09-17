package com.ae2vm.trace;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.init.Bootstrap;

/**
 * M0 acceptance ("MC 内 + 垫片两路"): real stacks → vault identities →
 * tokenized specs, then headless materialization preserves the identity
 * graph (same spec → equal stacks; different damage/NBT axes → different
 * stacks) without ever needing the real registry on the consumer side.
 */
class McStackIdentityTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void realStacksCarryRegistryIdentity() {
        IAEItemStack iron = AEItemStack.fromItemStack(new ItemStack(Items.IRON_INGOT));
        StackIdentity id = McStackAdapter.identityOf(iron);
        assertEquals("minecraft:iron_ingot", id.id);
        assertEquals(0, id.damage);
        assertNull(id.nbt);
    }

    @Test
    void damageAndNbtAreIdentityAxesThroughTokens() {
        TokenVault vault = TokenVault.inMemory();
        StackCodec codec = new StackCodec(vault);

        StackIdentity stone0 = McStackAdapter.identityOf(AEItemStack.fromItemStack(new ItemStack(Items.COAL)));
        StackIdentity stone3 = McStackAdapter.identityOf(AEItemStack.fromItemStack(new ItemStack(Items.COAL, 1, 3)));
        assertNotEquals(codec.toSpec(stone0), codec.toSpec(stone3), "damage must be preserved verbatim");

        StackIdentity plain = McStackAdapter.identityOf(AEItemStack.fromItemStack(new ItemStack(Items.IRON_INGOT)));
        StackIdentity named = McStackAdapter.identityOf(AEItemStack.fromItemStack(namedIron()));
        assertNotEquals(plain.nbt, named.nbt);
        StackSpec plainSpec = codec.toSpec(plain);
        StackSpec namedSpec = codec.toSpec(named);
        assertNotEquals(plainSpec.token, namedSpec.token, "NBT axis must mint distinct stack tokens");
        assertNotEquals(plainSpec.nbtToken, namedSpec.nbtToken);
    }

    @Test
    void headlessMaterializationPreservesTheIdentityGraph() {
        TokenVault vault = TokenVault.inMemory();
        StackCodec codec = new StackCodec(vault);
        HeadlessStackFactory factory = new HeadlessStackFactory();

        StackSpec named = codec.toSpec(McStackAdapter.identityOf(AEItemStack.fromItemStack(namedIron())));
        StackSpec plain = codec.toSpec(McStackAdapter.identityOf(AEItemStack.fromItemStack(new ItemStack(Items.IRON_INGOT))));

        IAEItemStack a1 = factory.stack(named, 10);
        IAEItemStack a2 = factory.stack(named, 10);
        IAEItemStack b = factory.stack(plain, 10);

        assertTrue(a1.isSameType(a2), "same spec must materialize equal identity");
        assertEquals(10, a1.getStackSize());
        assertTrue(a1.isSameType(factory.stack(named, 999)), "type equality must ignore size");
        assertFalse(a1.isSameType(b), "different specs must never collide");
    }

    @Test
    void realIdentitySurvivesRegistryRoundTrip() {
        StackIdentity id = McStackAdapter.identityOf(AEItemStack.fromItemStack(namedIron()));
        IAEItemStack back = McStackAdapter.fromIdentity(id, 7);
        assertEquals(id, McStackAdapter.identityOf(back), "identity must survive a registry rebuild");
        assertEquals(7, back.getStackSize());
    }

    private static ItemStack namedIron() {
        ItemStack st = new ItemStack(Items.IRON_INGOT);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "{\"text\":\"Super Iron\"}");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("display", display);
        st.setTagCompound(tag);
        return st;
    }
}
