package org.neoforbric.forge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.neoforbric.loader.*;

/** Real FML loading, with no Minecraft Main, window, world or native launcher. */
public final class ForgeModProbe {
    private ForgeModProbe() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new Failure("ARGUMENTS", "ForgeModProbe <forge-plan> <vanilla-plan> <bridge> <mods> <output> <side>");
        if (!Set.of("client", "server").contains(args[5])) throw new Failure("ARGUMENTS", "Invalid Forge probe side");
        Path output = Path.of(args[4]); Files.createDirectories(output); var audit = new AuditLog(); audit.mode("forge-headless-mod-loading"); Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Real Forge ModLoader gather/load/finish in G; no Minecraft Main, window or world"); report.put("side", args[5]);
        List<Map<String, String>> states = Collections.synchronizedList(new ArrayList<>());
        try (var runtime = new ForgeRuntime(Path.of(args[0]), Path.of(args[2]))) {
            var discovered = ForgeDiscovery.discover(Path.of(args[3]), audit);
            var selected = Resolver.plan(discovered.mods(), args[5], audit, false, false, true).mods(); report.put("dependencyResolution", "PASS");
            var mods = runtime.remap(selected, audit); List<Archive> inputs = new ArrayList<>(runtime.inputs(Path.of(args[1]), audit));
            for (var mod : mods) inputs.add(mod.archive()); inputs.addAll(discovered.libraries());
            var pipeline = new TransformPipeline(); runtime.install(pipeline, true); runtime.mixins(pipeline, audit, args[5]);
            Set<Path> libraries = new HashSet<>();
            for (var archive : inputs) if (!archive.path().equals(runtime.bridge()) && archive != inputs.getFirst() && mods.stream().noneMatch(m -> m.archive().path().equals(archive.path()))) libraries.add(archive.path());
            var parent = ForgeModProbe.class.getClassLoader(); var index = ClassIndex.prepare(inputs, parent, audit, libraries, Set.of(runtime.bridge())); ForgeAnchors.verify(index, audit);
            pipeline.seal(audit);
            try (var resources = new GameResources(audit); var loader = new GameClassLoader(index, pipeline, parent, audit, resources)) {
                loader.generatedClasses(name -> runtime.generated(name, index)); runtime.packageMetadata(loader); loader.open(); var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
                try {
                    runtime.prepareMods(index, loader, pipeline, inputs, output.resolve("run"), args[5], mods, state -> states.add(Map.of("state", state, "thread", Thread.currentThread().getName())));
                    var verifier = Class.forName("org.neoforbric.forge.runtime.ForgeLoadedModsProbe", true, loader);
                    report.put("beforeConstruction", ForgeRuntime.invoke(verifier.getMethod("beforeConstruction", List.class), mods.stream().map(m -> m.metadata().id()).toList()));
                    ForgeRuntime.invoke(Class.forName("net.minecraft.SharedConstants", true, loader).getMethod("tryDetectVersion"));
                    ForgeRuntime.invoke(Class.forName("net.minecraft.server.Bootstrap", true, loader).getMethod("bootStrap"));
                    var type = Class.forName("org.neoforbric.forge.runtime.NativeForgeRuntime", true, loader);
                    for (String phase : List.of("gather", "load", "finish")) ForgeRuntime.invoke(type.getMethod(phase));
                    report.put("loaded", ForgeRuntime.invoke(verifier.getMethod("verify", List.class), mods.stream().map(m -> m.metadata().id()).toList()));
                    report.put("versionSupportMatrix", ForgeRuntime.invoke(Class.forName("org.neoforbric.forge.runtime.ForgeContractProbe", true, loader).getMethod("versionSupport")));
                    List<String> names = states.stream().map(state -> state.get("state")).toList();
                    if (!names.contains("NETWORK_LOCK") || names.indexOf("REGISTRY_FROZEN") <= names.indexOf("COMMON_SETUP")) throw new Failure("FORGE_LIFECYCLE", "Incomplete or reordered native state sequence " + names);
                    report.put("minecraftMainDefined", loader.hasDefined("net.minecraft.client.main.Main")); report.put("result", "PASS");
                } finally { try { runtime.close(); } finally { Thread.currentThread().setContextClassLoader(previous); } }
            }
        } catch (Exception | Error failure) {
            report.put("result", "FAIL"); List<String> causes = new ArrayList<>(); Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) causes.add(cause.toString()); report.put("causes", causes); throw failure;
        } finally { report.put("states", states); Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); audit.write(output.resolve("audit.json"), String.valueOf(report.getOrDefault("result", "FAIL"))); }
        System.out.println("FORGE_MOD_PROBE_PASS report=" + output.resolve("report.json"));
    }
}
