package com.ae2vm.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.util.UUID;

/** M0 acceptance: vault tokens stable per identity, distinct across axes, persistent. */
class TokenVaultTest {

    @TempDir
    Path dir;

    @Test
    void tokensAreStablePerIdentityAndDistinctAcrossAxes() {
        TokenVault v = TokenVault.inMemory();
        StackIdentity iron = new StackIdentity("minecraft:iron_ingot", 0, null);
        StackIdentity ironWithNbt = new StackIdentity("minecraft:iron_ingot", 0, "{display:{Name:\"x\"}}");
        StackIdentity platinum = new StackIdentity("thermalfoundation:material", 134, null);

        String ironToken = v.tokenForStack(iron);
        assertEquals(ironToken, v.tokenForStack(iron), "same identity must map to the same token");
        assertNotEquals(ironToken, v.tokenForStack(ironWithNbt), "NBT is an identity axis");
        assertNotEquals(ironToken, v.tokenForStack(platinum), "name is an identity axis");
        assertNotEquals(v.tokenForStack(ironWithNbt), v.tokenForStack(platinum));
        assertTrue(platinum.toString().contains("134"));

        String n1 = v.tokenForNbt("{a:1}");
        assertEquals(n1, v.tokenForNbt("{a:1}"));
        assertNotEquals(n1, v.tokenForNbt("{a:2}"));
        assertEquals("{a:2}", v.nbtForToken(v.tokenForNbt("{a:2}")));

        assertTrue(v.tokenForStack(platinum).matches("i#[0-9a-f]{4}"));
        assertTrue(v.tokenForNbt("{a:1}").matches("n#[0-9a-f]{4}"));
        String p = v.tokenForPlayer(new UUID(1L, 2L), "shinya");
        assertTrue(p.matches("p#[0-9a-f]{4}"));
        assertEquals("shinya", v.playerNameForToken(p));
        String d = v.tokenForDevice("somemod", "overworld:10,20,30");
        assertTrue(d.matches("dev#[0-9a-f]{4}"));
        assertEquals("somemod@overworld:10,20,30", v.deviceDescForToken(d));
    }

    @Test
    void vaultPersistsAcrossReopen() throws IOException {
        Path file = dir.resolve("vault.json");
        TokenVault first = TokenVault.open(file);
        StackIdentity id = new StackIdentity("thermalfoundation:material", 166, "{FluidName:\"luminum\"}");
        String stackToken = first.tokenForStack(id);
        String nbtToken = first.tokenForNbt("{FluidName:\"luminum\"}");
        first.flush();

        TokenVault second = TokenVault.open(file);
        assertEquals(stackToken, second.tokenForStack(id), "tokens must survive restart");
        assertEquals(first.getVaultId(), second.getVaultId());
        assertEquals(stackToken, second.tokenForStack(new StackIdentity(
                "thermalfoundation:material", 166, "{FluidName:\"luminum\"}")));
        assertEquals("{FluidName:\"luminum\"}", second.nbtForToken(nbtToken));
    }

    @Test
    void freshVaultFileIsCreatedOnFirstOpen() throws IOException {
        Path file = dir.resolve("fresh.json");
        TokenVault v = TokenVault.open(file);
        assertTrue(Files.exists(file), "first open must materialize the vault");
        assertEquals(v.getVaultId(), TokenVault.open(file).getVaultId());
    }
}
