package org.neoforbric.loader;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neoforbric.api.GameHooks;
import static org.junit.jupiter.api.Assertions.*;

class GameHooksTest {
    @Test void closedCapturedSessionCannotDispatchIntoALaterLaunch() throws Exception {
        GameHooks.Session captured;
        try (var hooks = GameHooks.attach(() -> fail("Closed launch must not initialize"), () -> fail("Closed launch must not initialize"))) {
            captured = GameHooks.captureSession();
            assertSame(hooks, captured);
        }
        var opened = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor(); var hooks = GameHooks.attach(opened::incrementAndGet, () -> {})) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, captured::beforeRegistryFreeze);
                assertThrows(IllegalStateException.class, captured::afterRegistryFreeze);
                assertThrows(IllegalStateException.class, GameHooks::captureSession);
            }).get(10, TimeUnit.SECONDS);
            assertEquals(0, opened.get());
            GameHooks.beforeRegistryFreeze(); GameHooks.afterRegistryFreeze(); hooks.verifyComplete();
        }
    }

    @Test void workerFailureRemainsFatalToOwningLaunchAndBlocksClientRegistration() throws Exception {
        var failed = new IllegalArgumentException("Fabric registration failed");
        try (var worker = Executors.newSingleThreadExecutor();
             var hooks = GameHooks.attach(() -> { throw failed; }, () -> fail("Failed registration must block the client callback"))) {
            var captured = GameHooks.captureSession();
            var task = worker.submit(captured::beforeRegistryFreeze);
            assertSame(failed, assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS)).getCause());
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, captured::afterRegistryFreeze);
                assertThrows(IllegalStateException.class, GameHooks::captureSession);
                assertThrows(IllegalStateException.class, captured::close);
            }).get(10, TimeUnit.SECONDS);
            assertSame(failed, assertThrows(IllegalArgumentException.class, hooks::verifyComplete));
        }
    }

    @Test void workersCannotRepeatOrReorderCapturedCallbacks() throws Exception {
        var opened = new AtomicInteger(); var frozen = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor(); var hooks = GameHooks.attach(opened::incrementAndGet, frozen::incrementAndGet)) {
            var captured = GameHooks.captureSession();
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, captured::afterRegistryFreeze);
                captured.beforeRegistryFreeze(); captured.afterRegistryFreeze();
            }).get(10, TimeUnit.SECONDS);
            hooks.verifyComplete();
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, captured::beforeRegistryFreeze);
                assertThrows(IllegalStateException.class, captured::afterRegistryFreeze);
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, opened.get()); assertEquals(1, frozen.get());
        }
    }
}
