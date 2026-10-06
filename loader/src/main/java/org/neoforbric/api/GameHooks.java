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
    public static void beforeRegistryFreeze() { current().before(); }
    public static void afterRegistryFreeze() { current().after(); }
    private static Session current() {
        Session session = ACTIVE.get(); if (session == null) throw new IllegalStateException("Game hook called outside its owning launch thread"); return session;
    }
    public static final class Session implements AutoCloseable {
        private final Runnable before, after;
        private int opened, frozen;
        private Throwable failure;
        private Session(Runnable before, Runnable after) { this.before = Objects.requireNonNull(before); this.after = Objects.requireNonNull(after); }
        private void before() {
            if (++opened != 1) throw new IllegalStateException("Registry window opened more than once");
            invoke(before);
        }
        private void after() {
            if (opened != 1 || ++frozen != 1 || failure != null) throw new IllegalStateException("Registry freeze outside successful initialization");
            invoke(after);
        }
        private void invoke(Runnable action) {
            try { action.run(); }
            catch (RuntimeException | Error failed) { failure = failed; throw failed; }
        }
        public void verifyComplete() {
            // Vanilla Main may catch and log a RuntimeException; it must not turn this into launch success.
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (opened != 1 || frozen != 1) throw new IllegalStateException("Minecraft did not complete the expected registry window");
        }
        @Override public void close() { if (ACTIVE.get() != this) throw new IllegalStateException("Wrong hook session owner"); ACTIVE.remove(); }
    }
}
