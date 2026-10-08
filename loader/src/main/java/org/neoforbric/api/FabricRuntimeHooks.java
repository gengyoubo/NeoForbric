package org.neoforbric.api;

import java.util.Objects;
import java.util.function.Consumer;

/** Passive adapter callback; bootstrapping and class definition remain owned by NeoForbric. */
public final class FabricRuntimeHooks {
    private static Consumer<Object> client;
    private static final java.util.List<Thread> shutdownHooks = new java.util.ArrayList<>();
    private FabricRuntimeHooks() {}
    public static synchronized AutoCloseable attachClient(Consumer<Object> callback) {
        if (client != null) throw new IllegalStateException("Fabric client callback already attached");
        client = Objects.requireNonNull(callback);
        return () -> {
            java.util.List<Thread> hooks;
            synchronized (FabricRuntimeHooks.class) { hooks = java.util.List.copyOf(shutdownHooks); shutdownHooks.clear(); }
            try {
                // G remains open while mod-owned saving callbacks complete.
                for (Thread hook : hooks) { hook.start(); hook.join(5_000); if (hook.isAlive()) throw new IllegalStateException("Fabric shutdown hook timed out: " + hook.getName()); }
            } finally { synchronized (FabricRuntimeHooks.class) { client = null; } }
        };
    }
    public static synchronized void addShutdownHook(Runtime runtime, Thread hook) {
        Objects.requireNonNull(runtime); Objects.requireNonNull(hook);
        if (client == null) throw new IllegalStateException("Fabric client lifecycle is not attached");
        if (hook.getState() != Thread.State.NEW || shutdownHooks.contains(hook)) throw new IllegalArgumentException("Invalid or duplicate Fabric shutdown hook");
        shutdownHooks.add(hook);
    }
    public static void clientInit(Object instance) {
        Consumer<Object> callback;
        synchronized (FabricRuntimeHooks.class) { callback = client; }
        if (callback != null) callback.accept(instance);
    }
    public static void startClient(java.io.File directory, Object instance) { clientInit(instance); }
}
