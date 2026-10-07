package org.neoforbric.api;

import java.util.Objects;

/** Shared, Minecraft-free boundary for kernel-owned bytecode hooks. */
public final class GameHooks {
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private GameHooks() {}
    public static Session attach(Runnable before, Runnable after) {
        if (ACTIVE.get() != null) throw new IllegalStateException("A game hook session is already active on this thread");
        Session session = new Session(before, after); ACTIVE.set(session); return session;
    }
    /** Capture on the launch thread before handing lifecycle work to an executor. */
    public static Session captureSession() { return current(); }
    public static void beforeRegistryFreeze() { current().beforeRegistryFreeze(); }
    public static void afterRegistryFreeze() { current().afterRegistryFreeze(); }
    private static Session current() {
        Session session = ACTIVE.get(); if (session == null) throw new IllegalStateException("Game hook called outside its owning launch thread"); return session;
    }
    public static final class Session implements AutoCloseable {
        private final Runnable before, after;
        private int opened, frozen;
        private Throwable failure;
        private boolean closed;
        private Session(Runnable before, Runnable after) { this.before = Objects.requireNonNull(before); this.after = Objects.requireNonNull(after); }
        public synchronized void beforeRegistryFreeze() {
            requireActive();
            if (++opened != 1) throw new IllegalStateException("Registry window opened more than once");
            invoke(before);
        }
        public synchronized void afterRegistryFreeze() {
            requireActive();
            if (opened != 1 || ++frozen != 1 || failure != null) throw new IllegalStateException("Registry freeze outside successful initialization");
            invoke(after);
        }
        private void requireActive() {
            if (closed) throw new IllegalStateException("Game hook session is closed");
        }
        private void invoke(Runnable action) {
            try { action.run(); }
            catch (RuntimeException | Error failed) { failure = failed; throw failed; }
        }
        public synchronized void verifyComplete() {
            requireActive();
            // Vanilla Main may catch and log a RuntimeException; it must not turn this into launch success.
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (opened != 1 || frozen != 1) throw new IllegalStateException("Minecraft did not complete the expected registry window");
        }
        @Override public synchronized void close() {
            if (ACTIVE.get() != this) throw new IllegalStateException("Wrong hook session owner");
            closed = true; ACTIVE.remove();
        }
    }
}
