package org.neoforbric.api;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only published catalog. A single kernel-owned publisher supplies immutable snapshots. */
public final class LoadedMods {
    private static final AtomicReference<Publisher> OWNER = new AtomicReference<>();
    private LoadedMods() {}
    public static List<LoadedModInfo> snapshot() {
        Publisher publisher = OWNER.get(); return publisher == null ? List.of() : publisher.snapshot;
    }
    public static Publisher install(List<LoadedModInfo> initial) {
        Publisher publisher = new Publisher(initial);
        if (!OWNER.compareAndSet(null, publisher)) throw new IllegalStateException("A loaded mod catalog already owns this JVM");
        return publisher;
    }
    public static final class Publisher implements AutoCloseable {
        private volatile List<LoadedModInfo> snapshot;
        private Publisher(List<LoadedModInfo> snapshot) { this.snapshot = List.copyOf(snapshot); }
        public void publish(List<LoadedModInfo> next) {
            if (OWNER.get() != this) throw new IllegalStateException("Catalog publisher no longer owns the instance");
            snapshot = List.copyOf(next);
        }
        @Override public void close() {
            if (!OWNER.compareAndSet(this, null)) throw new IllegalStateException("Wrong catalog owner");
        }
    }
}
