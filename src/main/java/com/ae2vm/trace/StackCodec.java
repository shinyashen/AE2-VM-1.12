package com.ae2vm.trace;

/**
 * Identity → token substitution boundary. Everything
 * that enters a trace passes through here at the recorder boundary, so
 * every artifact derived from a session — file, chat summary, upload,
 * client downlink — is pseudonymous by construction.
 */
public final class StackCodec {

    private final TokenVault vault;

    public StackCodec(TokenVault vault) {
        this.vault = vault;
    }

    /** Real identity → trace spec (tokenized NBT included). */
    public StackSpec toSpec(StackIdentity id) {
        String nbtToken = id.nbt == null ? null : vault.tokenForNbt(id.nbt);
        return new StackSpec(false, vault.tokenForStack(id), id.damage, nbtToken);
    }

    /** Convenience overload for raw parts. */
    public StackSpec toSpec(String id, int damage, String nbtText) {
        return toSpec(new StackIdentity(id, damage, nbtText));
    }

    /** Trace spec → real identity for rendering; null for unknown tokens. */
    public StackIdentity identityOf(StackSpec spec) {
        return vault.stackForToken(spec.token);
    }

    /** NBT payload text → opaque token. */
    public String nbtToken(String nbtText) {
        return vault.tokenForNbt(nbtText);
    }

    public TokenVault vault() {
        return vault;
    }
}
