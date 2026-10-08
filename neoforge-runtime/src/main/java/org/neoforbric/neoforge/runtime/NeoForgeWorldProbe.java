package org.neoforbric.neoforge.runtime;

import java.util.*;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in world test uses a fresh save in build/neoforge-world, never a user's save. */
public final class NeoForgeWorldProbe {
    private static long started;
    private static int ticks;
    private static CompletableFuture<List<Integer>> spawned;
    private NeoForgeWorldProbe() {}
    public static void start(Minecraft client) {
        started = System.nanoTime();
        NeoForge.EVENT_BUS.addListener(NeoForgeWorldProbe::tick);
        GameRules rules = new GameRules(); rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        String world = "neoforbric-neoforge-probe-" + UUID.randomUUID();
        client.createWorldOpenFlows().createFreshLevel(world,
                new LevelSettings("NeoForbric NeoForge Probe", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                new WorldOptions(1211L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
        System.out.println("NEOFORGE_WORLD_REQUESTED " + world);
    }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (System.nanoTime() - started > 180_000_000_000L) throw new IllegalStateException("NeoForge world test timed out");
        if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return;
        if (spawned == null) {
            spawned = new CompletableFuture<>();
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld(); var player = server.getPlayerList().getPlayers().getFirst();
                    BlockPos center = player.blockPosition();
                    for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                        level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        for (int y = 0; y < 4; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    List<Integer> ids = new ArrayList<>();
                    boolean mixed = Boolean.getBoolean("neoforbric.neoforge.mixedWorldProbe");
                    var types = mixed ? java.util.Collections.nCopies(3, net.minecraft.resources.ResourceLocation.withDefaultNamespace("sheep"))
                            : BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(id -> id.getNamespace().equals("ecologicalgarden")).sorted().toList();
                    for (var key : types) {
                        var entity = BuiltInRegistries.ENTITY_TYPE.get(key).create(level);
                        if (!(entity instanceof Mob mob)) continue;
                        mob.setNoAi(true); mob.moveTo(center.getX() + 2 + ids.size(), center.getY(), center.getZ(), 180, 0);
                        if (!level.addFreshEntity(mob)) throw new IllegalStateException("Failed to spawn " + key);
                        ids.add(mob.getId()); if (ids.size() == 3) break;
                    }
                    if (ids.size() != 3) throw new IllegalStateException("Could not create three probe mobs");
                    if (mixed) {
                        var item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", "backpack"));
                        if (item == net.minecraft.world.item.Items.AIR) throw new IllegalStateException("Backpack was not registered");
                        player.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(item));
                        net.minecraft.world.entity.item.FallingBlockEntity.fall(level, center.offset(3, 5, 0), Blocks.SAND.defaultBlockState());
                    }
                    player.connection.teleport(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, -90, 15);
                    spawned.complete(ids);
                } catch (Throwable error) { spawned.completeExceptionally(error); }
            });
        }
        if (!spawned.isDone()) return;
        List<Integer> ids = spawned.join();
        if (ids.stream().anyMatch(id -> client.level.getEntity(id) == null)) return;
        if (++ticks != 60) return;
        if (Boolean.getBoolean("neoforbric.neoforge.mixedWorldProbe") && !BuiltInRegistries.ITEM.getKey(client.player.getInventory().getItem(0).getItem()).toString().equals("sophisticatedbackpacks:backpack"))
            throw new IllegalStateException("Native backpack did not synchronize to the mixed client");
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            Path screenshot = client.gameDirectory.toPath().resolve("neoforge-world-probe.png"); image.writeToFile(screenshot);
            System.out.println("NEOFORGE_WORLD_PROBE_OK mobs=" + ids.size() + " ticks=" + ticks + " screenshot=" + screenshot);
            if (Boolean.getBoolean("neoforbric.neoforge.mixedWorldProbe")) System.out.println("MIXED_FABRIC_WORLD_PROBE_OK backpack=true fabric=[continuity,carpet,viafabricplus,fabric-api] native=[sodium,sophisticatedbackpacks,sophisticatedcore]");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        client.stop();
    }
}
