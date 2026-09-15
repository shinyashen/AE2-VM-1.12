package appeng.api;

import appeng.api.storage.IStorageChannel;

/**
 * Replay shim: never executed on the replay path (the network-channel branch
 * is dead offline); present only so the class resolves.
 */
public final class AEApi {
    private static final AEApi INSTANCE = new AEApi();

    private AEApi() {
    }

    public static AEApi instance() {
        return INSTANCE;
    }

    public Storage storage() {
        return new Storage();
    }

    public static final class Storage {
        public <T extends IStorageChannel> T getStorageChannel(Class<T> channel) {
            return null;
        }
    }
}
