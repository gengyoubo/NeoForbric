package org.neoforbric.api;

import java.util.Objects;
import java.util.function.Consumer;

/** Passive adapter callback; bootstrapping and class definition remain owned by NeoForbric. */
public final class FabricRuntimeHooks {
    private static Consumer<Object> client;
    private FabricRuntimeHooks() {}
    public static synchronized AutoCloseable attachClient(Consumer<Object> callback) {
        if (client != null) throw new IllegalStateException("Fabric client callback already attached");
        client = Objects.requireNonNull(callback);
        return () -> { synchronized (FabricRuntimeHooks.class) { client = null; } };
    }
    public static void clientInit(Object instance) {
        Consumer<Object> callback;
        synchronized (FabricRuntimeHooks.class) { callback = client; }
        if (callback != null) callback.accept(instance);
    }
    public static void startClient(java.io.File directory, Object instance) { clientInit(instance); }
}
