package org.neoforbric.client;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Opt-in integration test. Uses the real Fabric tick event and JEI's public runtime APIs. */
public final class JeiRuntimeProbe {
    private static final long TIMEOUT_NANOS = 300_000_000_000L;
    private static long started;
    private static int stage, ticks, ingredientCount, searchCount;
    private static long recipes;
    private static Object runtime, filter, focus;
    private static ClassLoader game;
    private JeiRuntimeProbe() {}
    public static void verifyClient(Object instance) throws Exception {
        Minecraft client = (Minecraft) instance; game = client.getClass().getClassLoader();
        game.loadClass("demo.clientprobe.ClientProbe").getMethod("verifyClient", Object.class).invoke(null, client);
        started = System.nanoTime();
        Class<?> listener = game.loadClass("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick");
        Object event = game.loadClass("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents").getField("END_CLIENT_TICK").get(null);
        Object callback = Proxy.newProxyInstance(game, new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onEndTick")) { step((Minecraft) args[0]); return null; }
            if (method.getName().equals("toString")) return "NeoForbric JEI integration probe";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new AssertionError(method);
        });
        game.loadClass("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, callback);
        String world = "neoforbric-jei-probe-" + UUID.randomUUID();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        client.createWorldOpenFlows().createFreshLevel(world,
                new LevelSettings("NeoForbric JEI Probe", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                new WorldOptions(1211L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
        System.out.println("JEI_PROBE_WORLD_REQUESTED " + world);
    }
    private static Object call(Object target, String api, String name, Class<?>[] types, Object... args) throws Exception {
        try { return game.loadClass(api).getMethod(name, types).invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
    private static Object call(Object target, String api, String name) throws Exception { return call(target, api, name, new Class<?>[0]); }
    private static Object runtime(String name) throws Exception { return call(runtime, "mezz.jei.api.runtime.IJeiRuntime", name); }
    private static void step(Minecraft client) throws Exception {
        if (stage == 4) return;
        if (client.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) throw new AssertionError("JEI test world disconnected before verification");
        if (System.nanoTime() - started > TIMEOUT_NANOS) throw new AssertionError("JEI probe timed out at stage " + stage);
        if (stage == 0) {
            if (client.level == null || client.player == null || client.getOverlay() != null) return;
            Optional<?> ready = (Optional<?>) game.loadClass("mezz.jei.common.Internal").getMethod("getOptionalJeiRuntime").invoke(null);
            if (ready.isEmpty()) return;
            runtime = ready.get();
            if (runtime.getClass().getClassLoader() != game || Items.OAK_PLANKS.getClass().getClassLoader() != game)
                throw new AssertionError("JEI and vanilla objects have different owners");
            filter = runtime("getIngredientFilter");
            call(filter, "mezz.jei.api.runtime.IIngredientFilter", "setFilterText", new Class<?>[]{String.class}, "");
            ingredientCount = ((List<?>) call(filter, "mezz.jei.api.runtime.IIngredientFilter", "getFilteredItemStacks")).size();
            if (ingredientCount < 500) throw new AssertionError("JEI ingredient list is incomplete: " + ingredientCount);
            client.setScreen(new InventoryScreen(client.player)); stage = 1; ticks = 0;
        } else if (++ticks >= 20 && stage == 1) {
            Object overlay = runtime("getIngredientListOverlay");
            if (!Boolean.TRUE.equals(call(overlay, "mezz.jei.api.runtime.IIngredientListOverlay", "isListDisplayed"))) throw new AssertionError("JEI inventory overlay is hidden");
            screenshot(client, "neoforbric-jei-items.png");
            call(filter, "mezz.jei.api.runtime.IIngredientFilter", "setFilterText", new Class<?>[]{String.class}, "oak plank");
            stage = 2; ticks = 0;
        } else if (ticks >= 20 && stage == 2) {
            List<?> matches = (List<?>) call(filter, "mezz.jei.api.runtime.IIngredientFilter", "getFilteredItemStacks");
            searchCount = matches.size();
            if (matches.isEmpty() || searchCount >= ingredientCount || matches.stream().noneMatch(item -> ((ItemStack) item).is(Items.OAK_PLANKS))) throw new AssertionError("JEI search did not find oak planks");
            screenshot(client, "neoforbric-jei-search.png");
            Object helpers = runtime("getJeiHelpers");
            Object factory = call(helpers, "mezz.jei.api.helpers.IJeiHelpers", "getFocusFactory");
            Class<?> roleType = game.loadClass("mezz.jei.api.recipe.RecipeIngredientRole");
            Object outputRole = roleType.getField("OUTPUT").get(null);
            Object itemType = game.loadClass("mezz.jei.api.constants.VanillaTypes").getField("ITEM_STACK").get(null);
            focus = call(factory, "mezz.jei.api.recipe.IFocusFactory", "createFocus", new Class<?>[]{roleType, game.loadClass("mezz.jei.api.ingredients.IIngredientType"), Object.class}, outputRole, itemType, new ItemStack(Items.OAK_PLANKS));
            Object crafting = game.loadClass("mezz.jei.api.constants.RecipeTypes").getField("CRAFTING").get(null);
            Object lookup = call(runtime("getRecipeManager"), "mezz.jei.api.recipe.IRecipeManager", "createRecipeLookup", new Class<?>[]{game.loadClass("mezz.jei.api.recipe.RecipeType")}, crafting);
            call(lookup, "mezz.jei.api.recipe.IRecipeLookup", "limitFocus", new Class<?>[]{Collection.class}, List.of(focus));
            try (Stream<?> stream = (Stream<?>) call(lookup, "mezz.jei.api.recipe.IRecipeLookup", "get")) { recipes = stream.count(); }
            if (recipes == 0) throw new AssertionError("JEI has no crafting recipe for oak planks");
            call(runtime("getRecipesGui"), "mezz.jei.api.runtime.IRecipesGui", "show", new Class<?>[]{List.class}, List.of(focus));
            stage = 3; ticks = 0;
        } else if (ticks >= 20 && stage == 3) {
            if (client.screen == null || !client.screen.getClass().getName().startsWith("mezz.jei.")) throw new AssertionError("JEI recipe GUI did not open");
            screenshot(client, "neoforbric-jei-recipes.png");
            String evidence = "JEI_PROBE_OK ingredients=" + ingredientCount + " search=" + searchCount + " oakPlanksRecipes=" + recipes + " screen=" + client.screen.getClass().getName() + " fabricTickEvent=true gameLoader=NeoForbric-Game";
            Files.writeString(client.gameDirectory.toPath().resolve("jei-probe.txt"), evidence + "\n"); System.out.println(evidence);
            stage = 4; client.stop();
        }
    }
    private static void screenshot(Minecraft client, String name) throws Exception {
        Path path = client.gameDirectory.toPath().resolve("screenshots").resolve(name); Files.createDirectories(path.getParent());
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(path); }
    }
}
