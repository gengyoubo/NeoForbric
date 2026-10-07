package org.neoforbric.client;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Generic smoke probe, independent of any particular mod or native event bus. */
public final class BenchmarkProbe {
    private static long firstTick = -1;
    private static CompletableFuture<Boolean> serverReady;
    private static boolean done;
    private BenchmarkProbe() {}

    public static void verifyClient(Object instance) {
        Minecraft client = (Minecraft) instance;
        if (client.getWindow().getWindow() == 0 || client.getOverlay() != null)
            throw new IllegalStateException("Client window/resource reload is not ready");
        System.out.println("BENCHMARK_MENU_OK");
        if (!System.getProperty("neoforbric.benchmark.stage", "").equals("world")) return;
        String world = "neoforbric-benchmark-" + UUID.randomUUID();
        client.createWorldOpenFlows().createFreshLevel(world,
                new LevelSettings("NeoForbric Benchmark", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(1211L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
        System.out.println("BENCHMARK_WORLD_REQUESTED " + world);
    }

    /** Called on the render thread; measure game ticks rather than rendered frames. */
    public static boolean frame(Object instance) {
        Minecraft client = (Minecraft) instance;
        if (done || client.level == null || client.player == null || client.getSingleplayerServer() == null) return false;
        if (firstTick < 0) {
            firstTick = client.level.getGameTime();
            serverReady = new CompletableFuture<>();
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                try { serverReady.complete(server.overworld() != null && !server.getPlayerList().getPlayers().isEmpty()); }
                catch (Throwable error) { serverReady.completeExceptionally(error); }
            });
        }
        if (client.level.getGameTime() - firstTick < 60 || !serverReady.isDone()) return false;
        if (!serverReady.join()) throw new IllegalStateException("Integrated server has no world/player");
        Path screenshot = client.gameDirectory.toPath().resolve("benchmark-world.png");
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(screenshot); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot save world evidence", error); }
        done = true;
        System.out.println("BENCHMARK_WORLD_OK ticks=60 screenshot=" + screenshot);
        client.stop();
        return true;
    }
}
