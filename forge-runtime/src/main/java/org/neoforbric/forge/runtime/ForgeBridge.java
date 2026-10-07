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
    private static net.minecraftforge.fml.loading.targets.CommonLaunchHandler handler;
    private static List<Path> minecraftPaths = List.of();
    private ForgeBridge() {}
    public static Dist dist() { return Objects.requireNonNull(side, "Forge side has not been bound"); }
    public static String naming() { return "mojang"; }
    public static boolean production() { return true; }
    public static boolean secureJarsEnabled() { return false; }
    public static Path gamePath() { return directory; }
    public static Optional<ILaunchPluginService> findLaunchPlugin(String name) { return Optional.ofNullable(plugins.get(name)); }
    public static Map<String, ILaunchPluginService> launchPluginsView() {
        return new AbstractMap<>() {
            public Set<Entry<String, ILaunchPluginService>> entrySet() { return Collections.unmodifiableMap(plugins).entrySet(); }
            public ILaunchPluginService put(String name, ILaunchPluginService plugin) {
                throw new UnsupportedOperationException("FORGE_PLUGIN_REGISTRATION: Launch plugins require a kernel feature adapter: " + name);
            }
        };
    }
    public static Optional<?> findLaunchHandler(String name) { return handler != null && handler.name().equals(name) ? Optional.of(handler) : Optional.empty(); }
    public static net.minecraftforge.fml.loading.targets.CommonLaunchHandler launchHandler() { return Objects.requireNonNull(handler, "Forge launch query adapter not ready"); }
    public static void minecraftPaths(Path game, Path universal) { minecraftPaths = List.of(game, universal); }
    private static final class PassiveLaunchHandler extends net.minecraftforge.fml.loading.targets.CommonLaunchHandler {
        PassiveLaunchHandler() { super(side == Dist.CLIENT ? CLIENT : SERVER, "neoforbric_forge_"); }
        public boolean isProduction() { return true; }
        public String getNaming() { return "mojang"; }
        public List<Path> getMinecraftPaths() { return minecraftPaths; }
        public ServiceRunner launchService(String[] args, ModuleLayer layer) { throw takeover(); }
        protected String[] preLaunch(String[] args, ModuleLayer layer) { throw takeover(); }
        protected ServiceRunner makeService(String[] args, ModuleLayer layer) { throw takeover(); }
        protected void runTarget(String module, String target, String[] args, ModuleLayer layer) { throw takeover(); }
        private UnsupportedOperationException takeover() { return new UnsupportedOperationException("FORGE_LAUNCH_OWNERSHIP: NF owns JVM main and G"); }
    }
    public static Optional<?> findLayerManager() { return Optional.empty(); }
    public static Optional<BiFunction<INameMappingService.Domain, String, String>> findNameMapping(String namespace) {
        return namespace.equals("srg") ? Optional.of((domain, name) -> map(name)) : Optional.empty();
    }
    private static String map(String name) {
        // MCPNamingService preserves unknown names. AT files often retain rules
        // for removed members; native AT simply finds no matching member.
        return names.getOrDefault(name, name);
    }
    public static Map<String, Object> prepare(Path gameDirectory, String physicalSide, Path universal, Path accessRules, Map<String, String> mapping) throws Exception {
        if (directory != null) throw new IllegalStateException("FORGE_STATE: Bridge already bound");
        directory = gameDirectory; side = physicalSide.equals("client") ? Dist.CLIENT : Dist.DEDICATED_SERVER; names = Map.copyOf(mapping);
        handler = new PassiveLaunchHandler();
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
        Class.forName("net.minecraftforge.fml.loading.RuntimeDistCleaner$Target", true, ForgeBridge.class.getClassLoader());
        for (ILaunchPluginService plugin : List.of(new net.minecraftforge.fml.common.asm.RuntimeEnumExtender(), new net.minecraftforge.fml.common.asm.ObjectHolderDefinalize(), new net.minecraftforge.fml.common.asm.CapabilityTokenSubclass())) plugins.put(plugin.name(), plugin);
        for (String helper : List.of("net.minecraftforge.fml.common.asm.CapabilityTokenSubclass$Holder", "net.minecraftforge.fml.common.asm.CapabilityTokenSubclass$1", "net.minecraftforge.fml.common.asm.ObjectHolderDefinalize$VanillaObjectHolderData")) Class.forName(helper, true, ForgeBridge.class.getClassLoader());
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
        return plugins(name, bytes, "classloading");
    }
    public static byte[] plugins(String name, byte[] bytes, String reason) {
        if (events == null) return bytes;
        // These are upstream launch-tool classes, normally defined before native
        // game transformation. They remain owned by G but do not transform themselves.
        if (name.startsWith("net.minecraftforge.fml.loading.") || name.startsWith("net.minecraftforge.fml.common.asm.") || name.startsWith("net.minecraftforge.eventbus.")
                || name.startsWith("net.minecraftforge.coremod.") || name.startsWith("net.minecraftforge.accesstransformer.")
                || name.startsWith("cpw.mods.modlauncher.")) return bytes;
        Type type = Type.getObjectType(name.replace('.', '/')); ClassNode node = read(bytes);
        int flags = 0;
        for (var phase : ILaunchPluginService.Phase.values()) for (var plugin : plugins.values()) {
            if (plugin instanceof AccessTransformerService || !plugin.handlesClass(type, false, reason).contains(phase)) continue;
            flags |= plugin.processClassWithFlags(phase, node, type, reason);
        }
        if (flags == 0) return bytes;
        var writer = new ForgeBytecodeWriter((flags & ClassWriter.COMPUTE_FRAMES) != 0 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS, node);
        node.accept(writer); return writer.toByteArray();
    }
    public static void prepareModCoremods(List<Path> mods) throws Exception {
        var provider = new CoreModProvider(); int count = 0;
        for (Path path : mods) try (var jar = new JarFile(path.toFile())) {
            var declaration = jar.getJarEntry("META-INF/coremods.json"); if (declaration == null) continue;
            var config = com.google.gson.JsonParser.parseString(new String(jar.getInputStream(declaration).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : config.entrySet()) {
                String script = entry.getValue().getAsString(); String owner = path.getFileName() + ":" + entry.getKey();
                provider.addCoreMod(new ICoreModFile() {
                    public String getOwnerId() { return owner; }
                    public Path getPath() { return path.resolveSibling(path.getFileName() + "-" + script.replace('/', '_')); }
                    public Reader readCoreMod() throws IOException { return getAdditionalFile(script); }
                    public Reader getAdditionalFile(String name) throws IOException {
                        if (name.startsWith("/") || name.contains("\\") || name.contains(":") || Arrays.stream(name.split("/", -1)).anyMatch(part -> Set.of("", ".", "..").contains(part))) throw new IOException("Invalid coremod resource " + name);
                        try (var source = new JarFile(path.toFile())) {
                            var resource = source.getJarEntry(name); if (resource == null) throw new IOException("Missing coremod resource " + name);
                            try (var input = source.getInputStream(resource)) { return new StringReader(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
                        }
                    }
                }); count++;
            }
        }
        if (count == 0) return;
        var additional = provider.getCoreModTransformers();
        // Native JS initialization records errors rather than throwing them. Never
        // continue with a partially initialized set of admitted transformations.
        var engineField = CoreModProvider.class.getDeclaredField("engine"); engineField.setAccessible(true);
        var engine = engineField.get(provider); var scriptsField = engine.getClass().getDeclaredField("coreMods"); scriptsField.setAccessible(true);
        for (Object script : (List<?>)scriptsField.get(engine)) {
            var mod = (net.minecraftforge.coremod.CoreMod)script;
            if (mod.hasError()) throw new IllegalStateException("FORGE_COREMOD: " + mod.getPath(), mod.getError());
        }
        for (var transformer : additional) for (var target : transformer.targets())
            if (target.getTargetType() != ITransformer.TargetType.CLASS && target.getTargetType() != ITransformer.TargetType.PRE_CLASS) throw new UnsupportedOperationException("FORGE_COREMOD_TARGET: Unadapted target " + target.getTargetType());
        var all = new ArrayList<ITransformer<?>>(coremods); all.addAll(additional); coremods = List.copyOf(all);
        System.out.println("FORGE_MOD_COREMODS_READY scripts=" + count + " transformers=" + additional.size());
    }
    public static Map<String, Object> generated(String name) throws Exception {
        var factory = net.minecraftforge.eventbus.ModLauncherFactory.class;
        if (!net.minecraftforge.eventbus.ModLauncherFactory.hasPendingWrapperClass(name)) return null;
        var field = factory.getDeclaredField("PENDING"); field.setAccessible(true); Object pending = field.get(null);
        var get = pending.getClass().getMethod("get", Object.class); get.setAccessible(true);
        var target = (java.lang.reflect.Method)get.invoke(pending, name);
        if (target == null) throw new IllegalStateException("FORGE_EVENT_WRAPPER: Missing pending method " + name);
        ClassNode node = new ClassNode(); net.minecraftforge.eventbus.ModLauncherFactory.processWrapperClass(name, node);
        return Map.of("bytes", write(node), "owner", target.getDeclaringClass().getName());
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
