package org.neoforbric.client;

import com.mojang.blaze3d.vertex.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Opt-in real GeckoLib linkage and first-person rendering check in a separate fresh world. */
public final class SscRenderProbe {
    private static long started;
    private static int ticks;
    private static boolean done;
    private SscRenderProbe() {}

    public static void verifyClient(Object instance) throws Exception {
        Minecraft client = (Minecraft) instance;
        ClassLoader game = client.getClass().getClassLoader();
        game.loadClass("demo.clientprobe.ClientProbe").getMethod("verifyClient", Object.class).invoke(null, client);
        client.getWindow().setTitle("NeoForbric - Automated world verification");
        checkGeckoBuffer(game);
        started = System.nanoTime();
        Class<?> listener = game.loadClass("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick");
        Object event = game.loadClass("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents").getField("END_CLIENT_TICK").get(null);
        Object callback = Proxy.newProxyInstance(game, new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onEndTick")) { step((Minecraft) args[0]); return null; }
            if (method.getName().equals("toString")) return "NeoForbric SSC rendering probe";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new AssertionError(method);
        });
        game.loadClass("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, callback);
        String world = "neoforbric-ssc-probe-" + UUID.randomUUID();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        client.createWorldOpenFlows().createFreshLevel(world,
                new LevelSettings("NeoForbric SSC Probe", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                new WorldOptions(1211L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
        System.out.println("SSC_PROBE_WORLD_REQUESTED " + world);
    }

    private static void checkGeckoBuffer(ClassLoader game) throws Exception {
        Class<?> inner = game.loadClass("com.mojang.blaze3d.vertex.VertexMultiConsumer$Double");
        if (!Modifier.isPublic(inner.getModifiers()) || !Modifier.isPublic(inner.getField("first").getModifiers())
                || !Modifier.isPublic(inner.getField("second").getModifiers()) || inner.getClassLoader() != game)
            throw new AssertionError("GeckoLib access widener was not applied to VertexMultiConsumer.Double");
        VertexConsumer refreshedFirst = consumer(game), refreshedSecond = consumer(game);
        Class<?> rendererType = game.loadClass("software.bernie.geckolib.renderer.GeoRenderer");
        Object renderer = Proxy.newProxyInstance(game, new Class<?>[]{rendererType}, (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new AssertionError("Unexpected GeoRenderer callback " + method);
        });
        Method check = rendererType.getMethod("checkAndRefreshBuffer", boolean.class, VertexConsumer.class, MultiBufferSource.class, RenderType.class);
        int[] calls = {0};
        MultiBufferSource source = type -> calls[0]++ == 0 ? refreshedFirst : refreshedSecond;
        try (ByteBufferBuilder firstMemory = new ByteBufferBuilder(1024); ByteBufferBuilder secondMemory = new ByteBufferBuilder(1024)) {
            BufferBuilder first = new BufferBuilder(firstMemory, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            BufferBuilder second = new BufferBuilder(secondMemory, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            VertexConsumer combined = VertexMultiConsumer.create(first, second);
            if (check.invoke(renderer, false, combined, source, null) != combined)
                throw new AssertionError("GeckoLib replaced an active buffer");
            if (first.build() != null || second.build() != null) throw new AssertionError("Probe buffers were not empty");
            Object replacement = check.invoke(renderer, false, combined, source, null);
            if (!inner.isInstance(replacement) || replacement == combined || calls[0] != 2
                    || inner.getField("first").get(replacement) != refreshedFirst || inner.getField("second").get(replacement) != refreshedSecond)
                throw new AssertionError("GeckoLib failed to refresh both Double buffers");
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Error cause) throw cause;
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
        System.out.println("GECKO_BUFFER_PROBE_OK originalAndRefreshed=true gameLoader=NeoForbric-Game");
    }

    private static VertexConsumer consumer(ClassLoader game) {
        return (VertexConsumer) Proxy.newProxyInstance(game, new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getReturnType() == VertexConsumer.class) return proxy;
            if (method.getReturnType() == void.class) return null;
            throw new AssertionError(method);
        });
    }

    private static void step(Minecraft client) throws Exception {
        if (done) return;
        if (System.nanoTime() - started > 300_000_000_000L) throw new AssertionError("SSC world probe timed out");
        if (client.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) throw new AssertionError("SSC test world disconnected");
        if (client.level == null || client.player == null || client.screen != null || client.getOverlay() != null) return;
        client.options.setCameraType(CameraType.FIRST_PERSON);
        if (++ticks < 100) return;
        Path screenshot = client.gameDirectory.toPath().resolve("screenshots/neoforbric-ssc-world.png");
        Files.createDirectories(screenshot.getParent());
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(screenshot); }
        String evidence = "SSC_RENDER_PROBE_OK firstPersonTicks=" + ticks + " geckoBufferRefresh=true gameLoader=NeoForbric-Game";
        Files.writeString(client.gameDirectory.toPath().resolve("ssc-render-probe.txt"), evidence + "\n");
        System.out.println(evidence); done = true; client.stop();
    }
}
