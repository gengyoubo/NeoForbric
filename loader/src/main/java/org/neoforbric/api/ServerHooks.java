package org.neoforbric.api;

import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Shared boundary: server objects stay in G; the kernel waits until their thread has ended. */
public final class ServerHooks {
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private ServerHooks() {}
    public static Session attach(Consumer<String> lifecycle, Consumer<Object> firstTick, int stopAfterTicks) {
        Session session = new Session(lifecycle, firstTick, stopAfterTicks);
        if (!ACTIVE.compareAndSet(null, session)) throw new IllegalStateException("A persistent server already owns this JVM");
        return session;
    }
    private static Session current() {
        Session session = ACTIVE.get();
        if (session == null) throw new IllegalStateException("Server hook outside an active launch");
        return session;
    }
    public static void bind(Object server) { current().bindServer(server); }
    public static void registerShutdownHook(Runtime runtime, Thread hook) {
        runtime.addShutdownHook(hook); current().vanillaShutdownHook = hook;
    }
    public static void initialized(Object server, boolean initialized) {
        Session session = current();
        if (initialized) { session.started = true; session.lifecycle.accept("started"); }
        else session.failed(new IllegalStateException("Minecraft server initialization returned false"));
    }
    public static void tick(Object server) { current().tickServer(server); }
    public static void failed(Throwable failure) { current().failed(failure); }
    public static void stopped() { current().saved = true; current().lifecycle.accept("saved"); }

    public static final class Session implements AutoCloseable {
        private final Consumer<String> lifecycle;
        private final Consumer<Object> firstTick;
        private final int stopAfterTicks;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Object server;
        private volatile Thread thread;
        private volatile Thread vanillaShutdownHook;
        private volatile boolean started, saved;
        private volatile boolean stopRequested;
        private volatile int ticks;
        private Session(Consumer<String> lifecycle, Consumer<Object> firstTick, int stopAfterTicks) {
            this.lifecycle = lifecycle; this.firstTick = firstTick; this.stopAfterTicks = stopAfterTicks;
        }
        private void bindServer(Object instance) {
            if (server != null) throw new IllegalStateException("Minecraft created more than one server");
            server = instance;
            thread = (Thread) call(instance, "getRunningThread", new Class<?>[0]);
            Thread.UncaughtExceptionHandler vanilla = thread.getUncaughtExceptionHandler();
            thread.setUncaughtExceptionHandler((owner, error) -> { failed(error); if (vanilla != null) vanilla.uncaughtException(owner, error); });
            lifecycle.accept("bound");
            if (stopRequested) requestStop();
        }
        private void tickServer(Object instance) {
            ticks++;
            if (ticks == 1) {
                try { firstTick.accept(instance); lifecycle.accept("first-tick"); }
                catch (RuntimeException | Error error) { failed(error); throw error; }
            }
            if (stopAfterTicks != 0 && ticks == stopAfterTicks) requestStop();
        }
        private void failed(Throwable error) { failure.compareAndSet(null, error); }
        public void requestStop() {
            stopRequested = true;
            Object instance = server;
            if (instance != null) { lifecycle.accept("stop-requested"); call(instance, "halt", new Class<?>[]{boolean.class}, false); }
        }
        public void await() throws InterruptedException {
            Thread owner = thread;
            if (owner == null) throw new IllegalStateException("Minecraft main returned without creating a server; check EULA and startup logs");
            owner.join();
            lifecycle.accept("thread-ended");
            Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error != null) throw new IllegalStateException("Minecraft server failed", error);
            if (!started || ticks == 0 || !saved) throw new IllegalStateException("Server did not complete startup, ticks and save");
        }
        public int ticks() { return ticks; }
        @Override public void close() {
            if (thread != null && thread.isAlive()) {
                requestStop();
                boolean interrupted = false;
                while (thread.isAlive()) {
                    try { thread.join(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (!ACTIVE.compareAndSet(this, null)) throw new IllegalStateException("Wrong persistent server owner");
            if (vanillaShutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(vanillaShutdownHook); }
                catch (IllegalStateException shutdownInProgress) { /* Original shutdown is still allowed to join the server. */ }
            }
        }
        private static Object call(Object instance, String method, Class<?>[] parameters, Object... arguments) {
            try { return instance.getClass().getMethod(method, parameters).invoke(instance, arguments); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Server lifecycle method " + method + " failed", error instanceof InvocationTargetException wrapper ? wrapper.getCause() : error); }
        }
    }
}
