package org.neoforbric.api;

import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Render-thread callbacks without exposing Minecraft types on the kernel classpath. */
public final class ClientHooks {
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private ClientHooks() {}
    public static Session attach(Consumer<String> lifecycle, Consumer<Object> ready, int stopAfterFrames) {
        Session session = new Session(lifecycle, ready, stopAfterFrames);
        if (!ACTIVE.compareAndSet(null, session)) throw new IllegalStateException("A client already owns this JVM");
        return session;
    }
    private static Session current() {
        Session session = ACTIVE.get(); if (session == null) throw new IllegalStateException("Client hook outside an active launch"); return session;
    }
    public static void frame(Object client) { current().frameRendered(client); }
    public static void registerShutdownHook(Runtime runtime, Thread hook) { runtime.addShutdownHook(hook); current().shutdownHook = hook; }
    public static void exit(int status) {
        Session session = current();
        if (status != 0) {
            ClientExit error = new ClientExit(status); session.failure.compareAndSet(null, error); throw error;
        }
        session.destroyed = true; session.lifecycle.accept("destroyed");
    }
    private static final class ClientExit extends Error {
        private ClientExit(int status) { super("Minecraft client requested exit " + status + "; see its crash report"); }
    }
    public static final class Session implements AutoCloseable {
        private final Consumer<String> lifecycle;
        private final Consumer<Object> ready;
        private final int stopAfterFrames;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Object client;
        private Thread shutdownHook;
        private boolean menu, destroyed;
        private int frames;
        private Session(Consumer<String> lifecycle, Consumer<Object> ready, int stopAfterFrames) { this.lifecycle = lifecycle; this.ready = ready; this.stopAfterFrames = stopAfterFrames; }
        private void frameRendered(Object instance) {
            client = instance;
            try {
                Object screen = instance.getClass().getField("screen").get(instance);
                Object overlay = instance.getClass().getMethod("getOverlay").invoke(instance);
                if (!menu && screen != null && screen.getClass().getName().equals("net.minecraft.client.gui.screens.TitleScreen") && overlay == null) {
                    ready.accept(instance); menu = true; lifecycle.accept("main-menu");
                }
                if (menu && ++frames == stopAfterFrames) { lifecycle.accept("stop-requested"); instance.getClass().getMethod("stop").invoke(instance); }
            } catch (ReflectiveOperationException error) {
                RuntimeException failed = new IllegalStateException("Client frame callback failed", error instanceof InvocationTargetException wrapper ? wrapper.getCause() : error);
                failure.compareAndSet(null, failed); throw failed;
            } catch (RuntimeException | Error error) { failure.compareAndSet(null, error); throw error; }
        }
        public void verifyComplete() {
            Throwable error = failure.get();
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            if (!menu || !destroyed) throw new IllegalStateException("Client did not render the main menu and finish native cleanup");
        }
        public int frames() { return frames; }
        @Override public void close() {
            try {
                if (client != null && !destroyed) {
                    try { client.getClass().getMethod("destroy").invoke(client); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException("Client cleanup failed", error); }
                }
            } finally {
                if (shutdownHook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException shutdownInProgress) { /* Native JVM shutdown still owns its hook. */ }
                }
                if (!ACTIVE.compareAndSet(this, null)) throw new IllegalStateException("Wrong client owner");
            }
        }
    }
}
