package com.ae2vm.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The server-local token vault: a persistent singleton
 * mapping REAL identities to opaque tokens. Created once (loaded at server
 * startup, created if absent) and appended lazily as new identities show
 * up; every trace on this server shares one vault so the operator can map
 * tokens back to real names locally while published traces stay
 * pseudonymous. Tokens have no mathematical relation to their originals
 * (PCI tokenization model) and the vault NEVER travels with traces —
 * losing it only loses human readability, never processing ability.
 *
 * <p>Namespaces: stacks (real item identity → "i#xxxx"), NBT payloads
 * (SNBT text → "n#xxxx"), players (uuid → "p#xxxx"), devices
 * (mod+pos → "dev#xx"). All access is synchronized: recorder appends come
 * from the crafting calc pool while START/AUDIT writes come from the
 * server thread.
 */
public final class TokenVault {

    public static final int FORMAT = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_CHARS = "0123456789abcdef";

    private final Path file; // null for in-memory instances (tests)
    private final String vaultId;
    private final Map<String, String> stackTokenByKey = new HashMap<>();
    private final Map<String, StackIdentity> stackByIdToken = new HashMap<>();
    private final Map<String, String> nbtTokenByText = new HashMap<>();
    private final Map<String, String> nbtTextByToken = new HashMap<>();
    private final Map<String, String> playerTokenByUuid = new HashMap<>();
    private final Map<String, String> playerNameByToken = new HashMap<>();
    private final Map<String, String> deviceTokenByKey = new HashMap<>();
    private final Map<String, String> deviceDescByToken = new HashMap<>();
    private boolean dirty;

    private TokenVault(Path file, String vaultId) {
        this.file = file;
        this.vaultId = vaultId;
    }

    /** Loads the vault file or creates a fresh one (and writes it immediately). */
    public static synchronized TokenVault open(Path file) throws IOException {
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.get("format").getAsInt() == FORMAT) {
                    TokenVault v = new TokenVault(file, root.get("vaultId").getAsString());
                    v.read(root);
                    return v;
                }
                // unsupported vault format: start fresh rather than crash the server;
                // old traces simply lose their human-readable mapping
            } catch (Exception corrupt) {
                // fall through to a fresh vault — the vault is a cache of names,
                // never a source of truth for processing
            }
        }
        TokenVault v = new TokenVault(file, newVaultId());
        v.dirty = true; // a fresh vault must materialize its file on the first flush
        v.flush();
        return v;
    }

    /** In-memory vault for tests. */
    public static synchronized TokenVault inMemory() {
        return new TokenVault(null, newVaultId());
    }

    private static String newVaultId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(TOKEN_CHARS.charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    private static String allocToken(String ns, Map<String, ? extends Object> usedKeys) {
        while (true) {
            StringBuilder sb = new StringBuilder(ns.length() + 5);
            sb.append(ns).append('#');
            for (int i = 0; i < 4; i++) {
                sb.append(TOKEN_CHARS.charAt(RANDOM.nextInt(16)));
            }
            String t = sb.toString();
            if (!usedKeys.containsKey(t)) {
                return t;
            }
        }
    }

    /** Returns the stable token for a real stack identity, allocating one on first sight. */
    public synchronized String tokenForStack(StackIdentity id) {
        String key = id.key();
        String t = stackTokenByKey.get(key);
        if (t == null) {
            t = allocToken(id.id.startsWith("fluid:") ? "f" : "i", stackTokenByKey);
            stackTokenByKey.put(key, t);
            stackByIdToken.put(t, id);
            dirty = true;
        }
        return t;
    }

    /** Reverse lookup for rendering only; null when the token is unknown. */
    public synchronized StackIdentity stackForToken(String token) {
        return stackByIdToken.get(token);
    }

    /** Returns the stable token for an NBT payload text. */
    public synchronized String tokenForNbt(String nbtText) {
        String t = nbtTokenByText.get(nbtText);
        if (t == null) {
            t = allocToken("n", nbtTokenByText);
            nbtTokenByText.put(nbtText, t);
            nbtTextByToken.put(t, nbtText);
            dirty = true;
        }
        return t;
    }

    public synchronized String nbtForToken(String token) {
        return nbtTextByToken.get(token);
    }

    public synchronized String tokenForPlayer(UUID uuid, String name) {
        String key = uuid.toString();
        String t = playerTokenByUuid.get(key);
        if (t == null) {
            t = allocToken("p", playerTokenByUuid);
            playerTokenByUuid.put(key, t);
            playerNameByToken.put(t, name);
            dirty = true;
        }
        return t;
    }

    public synchronized String playerNameForToken(String token) {
        return playerNameByToken.get(token);
    }

    public synchronized String tokenForDevice(String modId, String pos) {
        String key = modId + "\u0000" + pos;
        String t = deviceTokenByKey.get(key);
        if (t == null) {
            t = allocToken("dev", deviceTokenByKey);
            deviceTokenByKey.put(key, t);
            deviceDescByToken.put(t, modId + "@" + pos);
            dirty = true;
        }
        return t;
    }

    public synchronized String deviceDescForToken(String token) {
        return deviceDescByToken.get(token);
    }

    public synchronized String getVaultId() {
        return vaultId;
    }

    /** Persists the vault atomically when dirty; a no-op for in-memory vaults. */
    public synchronized void flush() throws IOException {
        if (file == null || !dirty) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("vaultId", vaultId);

        JsonArray stacks = new JsonArray();
        for (Map.Entry<String, StackIdentity> e : stackByIdToken.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("tok", e.getKey());
            o.addProperty("id", e.getValue().id);
            o.addProperty("d", e.getValue().damage);
            if (e.getValue().nbt != null) {
                o.addProperty("n", e.getValue().nbt);
            }
            stacks.add(o);
        }
        root.add("stacks", stacks);

        JsonArray nbts = new JsonArray();
        for (Map.Entry<String, String> e : nbtTextByToken.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("tok", e.getKey());
            o.addProperty("text", e.getValue());
            nbts.add(o);
        }
        root.add("nbts", nbts);

        JsonArray players = new JsonArray();
        for (Map.Entry<String, String> e : playerNameByToken.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("tok", e.getKey());
            o.addProperty("name", e.getValue());
            players.add(o);
        }
        root.add("players", players);

        JsonArray devices = new JsonArray();
        for (Map.Entry<String, String> e : deviceDescByToken.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("tok", e.getKey());
            o.addProperty("desc", e.getValue());
            devices.add(o);
        }
        root.add("devices", devices);

        byte[] json = TraceJson.canonical(root).getBytes(StandardCharsets.UTF_8);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    private void read(JsonObject root) {
        for (JsonElement el : root.getAsJsonArray("stacks")) {
            JsonObject o = el.getAsJsonObject();
            StackIdentity id = new StackIdentity(
                    o.get("id").getAsString(),
                    o.has("d") ? o.get("d").getAsInt() : 0,
                    o.has("n") ? o.get("n").getAsString() : null);
            String tok = o.get("tok").getAsString();
            stackTokenByKey.put(id.key(), tok);
            stackByIdToken.put(tok, id);
        }
        if (root.has("nbts")) {
            for (JsonElement el : root.getAsJsonArray("nbts")) {
                JsonObject o = el.getAsJsonObject();
                nbtTokenByText.put(o.get("text").getAsString(), o.get("tok").getAsString());
                nbtTextByToken.put(o.get("tok").getAsString(), o.get("text").getAsString());
            }
        }
        if (root.has("players")) {
            for (JsonElement el : root.getAsJsonArray("players")) {
                JsonObject o = el.getAsJsonObject();
                playerNameByToken.put(o.get("tok").getAsString(), o.get("name").getAsString());
            }
        }
        if (root.has("devices")) {
            for (JsonElement el : root.getAsJsonArray("devices")) {
                JsonObject o = el.getAsJsonObject();
                deviceDescByToken.put(o.get("tok").getAsString(), o.get("desc").getAsString());
            }
        }
    }
}
