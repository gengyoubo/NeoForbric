package org.neoforbric.forge.runtime;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.function.BiFunction;
import cpw.mods.modlauncher.*;
import cpw.mods.modlauncher.api.*;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.accesstransformer.*;
import net.minecraftforge.accesstransformer.service.AccessTransformerService;
import net.minecraftforge.coremod.CoreModProvider;
import net.minecraftforge.forgespi.coremod.ICoreModFile;
import net.minecraftforge.fml.loading.RuntimeDistCleaner;
import net.minecraftforge.eventbus.EventBusEngine;
import net.minecraftforge.eventbus.service.ModLauncherService;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Forge-only native services. Loaded in G; never starts FML discovery or ModLauncher. */
public final class ForgeBridge {
    private static Path directory;
    private static Dist side;
    private static Map<String, String> names;
    private static final Map<String, ILaunchPluginService> plugins = new LinkedHashMap<>();
    private static List<ITransformer<?>> coremods = List.of();
    private static EventBusEngine events;
    private static RuntimeDistCleaner cleaner;
    private ForgeBridge() {}
    public static Dist dist() { return Objects.requireNonNull(side, "Forge side has not been bound"); }
    public static String naming() { return "mojang"; }
    public static boolean production() { return true; }
    public static boolean secureJarsEnabled() { return false; }
    public static Path gamePath() { return directory; }
    public static Optional<ILaunchPluginService> findLaunchPlugin(String name) { return Optional.ofNullable(plugins.get(name)); }
    public static Optional<?> findLaunchHandler(String name) { return Optional.empty(); }
    public static Optional<?> findLayerManager() { return Optional.empty(); }
    public static Optional<BiFunction<INameMappingService.Domain, String, String>> findNameMapping(String namespace) {
        return namespace.equals("srg") ? Optional.of((domain, name) -> map(name)) : Optional.empty();
    }
    private static String map(String name) {
        String result = names.get(name);
        if (result == null && (name.startsWith("m_") || name.startsWith("f_"))) throw new IllegalStateException("FORGE_MAPPING: Unmapped member " + name);
        return result == null ? name : result;
    }
    public static Map<String, Object> prepare(Path gameDirectory, String physicalSide, Path universal, Path accessRules, Map<String, String> mapping) throws Exception {
        if (directory != null) throw new IllegalStateException("FORGE_STATE: Bridge already bound");
        directory = gameDirectory; side = physicalSide.equals("client") ? Dist.CLIENT : Dist.DEDICATED_SERVER; names = Map.copyOf(mapping);
        var constructor = Launcher.class.getDeclaredConstructor(); constructor.setAccessible(true); Launcher.INSTANCE = constructor.newInstance();
        var environment = Launcher.INSTANCE.environment();
        environment.putPropertyIfAbsent(IEnvironment.Keys.GAMEDIR.get(), directory);
        environment.putPropertyIfAbsent(IEnvironment.Keys.NAMING.get(), "mojang");
        environment.putPropertyIfAbsent(IEnvironment.Keys.VERSION.get(), "1.21.1");
        environment.putPropertyIfAbsent(IEnvironment.Keys.MLIMPL_VERSION.get(), "10.2.4");
        environment.putPropertyIfAbsent(IEnvironment.Keys.SECURED_JARS_ENABLED.get(), false);
        environment.putPropertyIfAbsent(IEnvironment.Keys.LAUNCHTARGET.get(), "neoforbric_forge_" + physicalSide);
        net.minecraftforge.fml.loading.FMLPaths.setup(environment);
        net.minecraftforge.fml.loading.FMLConfig.load();
        // Native plugins read this class while transforming targets; initialize it
        // before installing callbacks so it cannot recursively transform itself.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != side) throw new IllegalStateException("FORGE_SIDE: Environment differs from bound Dist");
        cleaner = new RuntimeDistCleaner(); cleaner.getExtension().accept(side); plugins.put(cleaner.name(), cleaner);
        events = new EventBusEngine(); var eventPlugin = new ModLauncherService();
        var engine = ModLauncherService.class.getDeclaredField("eventBusEngine"); engine.setAccessible(true); engine.set(eventPlugin, events);
        plugins.put(eventPlugin.name(), eventPlugin);
        var at = AccessTransformerEngine.INSTANCE;
        at.acceptNaming(new INameHandler() {
            public String translateClassName(String name) { return name; }
            public String translateFieldName(String name) { return map(name); }
            public String translateMethodName(String name) { return map(name); }
        });
        at.addResource(accessRules, "Forge 52.1.0"); var atPlugin = new AccessTransformerService(); plugins.put(atPlugin.name(), atPlugin);
        var provider = new CoreModProvider();
        // The pinned universal declares these two scripts. Arbitrary external providers
        // require admission and their own voting / target contract before they can run.
        for (String script : List.of("coremods/field_to_method.js", "coremods/method_redirector.js")) {
            provider.addCoreMod(new ICoreModFile() {
                public String getOwnerId() { return "forge"; }
                public Path getPath() { return universal.resolveSibling(script.replace('/', '_')); }
                public Reader readCoreMod() throws IOException { return getAdditionalFile(script); }
                public Reader getAdditionalFile(String name) throws IOException {
                    if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("Invalid coremod resource " + name);
                    try (var jar = new JarFile(universal.toFile())) {
                        var entry = jar.getJarEntry(name); if (entry == null) throw new IOException("Missing coremod resource " + name);
                        try (var input = jar.getInputStream(entry)) { return new StringReader(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
                    }
                }
            });
        }
        coremods = List.copyOf(provider.getCoreModTransformers());
        if (coremods.size() != 6) throw new IllegalStateException("FORGE_COREMOD: Expected six pinned JS transformers, got " + coremods.size());
        return Map.of("side", physicalSide, "loader", "G", "jsTransformers", coremods.size(), "plugins", List.copyOf(plugins.keySet()));
    }
    public static byte[] plugins(String name, byte[] bytes) {
        if (events == null) return bytes;
        // These are upstream launch-tool classes, normally defined before native
        // game transformation. They remain owned by G but do not transform themselves.
        if (name.startsWith("net.minecraftforge.fml.loading.") || name.startsWith("net.minecraftforge.eventbus.")
                || name.startsWith("net.minecraftforge.coremod.") || name.startsWith("net.minecraftforge.accesstransformer.")
                || name.startsWith("cpw.mods.modlauncher.")) return bytes;
        Type type = Type.getObjectType(name.replace('.', '/')); ClassNode node = read(bytes);
        cleaner.processClassWithFlags(ILaunchPluginService.Phase.BEFORE, node, type, "classloading");
        if (events.handlesClass(type)) events.processClass(node, type);
        return write(node);
    }
    public static byte[] access(String name, byte[] bytes) {
        Type type = Type.getObjectType(name.replace('.', '/'));
        if (directory == null || !AccessTransformerEngine.INSTANCE.handlesClass(type)) return bytes;
        ClassNode node = read(bytes); AccessTransformerEngine.INSTANCE.transform(node, type); return write(node);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static byte[] coremods(String name, byte[] bytes) {
        ClassNode node = null;
        for (ITransformer transformer : coremods) for (Object value : transformer.targets()) {
            var target = (ITransformer.Target)value;
            if (!target.getClassName().replace('/', '.').equals(name)) continue;
            if (node == null) node = read(bytes);
            var context = (ITransformerVotingContext)Proxy.newProxyInstance(ForgeBridge.class.getClassLoader(), new Class<?>[]{ITransformerVotingContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getClassName" -> name; case "doesClassExist" -> true; case "getReason" -> "classloading";
                case "getInitialClassSha256" -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                case "getAuditActivities" -> List.of();
                default -> throw new UnsupportedOperationException("FORGE_COREMOD_CONTEXT: " + method.getName());
            });
            if (transformer.castVote(context) != TransformerVoteResult.YES) throw new IllegalStateException("FORGE_COREMOD_VOTE: Pinned transformer did not vote YES");
            switch (target.getTargetType()) {
                case CLASS, PRE_CLASS -> node = (ClassNode)transformer.transform(node, context);
                default -> throw new UnsupportedOperationException("FORGE_COREMOD_TARGET: Unadmitted target type " + target.getTargetType());
            }
            break;
        }
        return node == null ? bytes : write(node);
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
}
