package org.neoforbric.forge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.neoforbric.loader.*;

/** Headless native contract probe; never calls Minecraft Main or a native launcher. */
public final class ForgeBaseline {
    private ForgeBaseline() {}
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) throw new Failure("ARGUMENTS", "ForgeBaseline <forge-plan> <vanilla-plan> <bridge> <output> <client|server>");
        if (!Set.of("client", "server").contains(arguments[4])) throw new Failure("FORGE_SIDE", arguments[4]);
        Path output = Path.of(arguments[3]).toAbsolutePath().normalize(); Files.createDirectories(output);
        var audit = new AuditLog(); audit.mode("forge-headless-contract"); Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Native contracts in G; Minecraft/FML game loading lifecycle is not started");
        try {
            var runtime = new ForgeRuntime(Path.of(arguments[0]), Path.of(arguments[2])); var pipeline = new TransformPipeline(); runtime.install(pipeline);
            var archives = runtime.inputs(Path.of(arguments[1]), audit); Set<Path> libraries = new HashSet<>();
            for (var archive : archives) if (archive != archives.getFirst() && !archive.path().equals(runtime.bridge())) libraries.add(archive.path());
            ClassLoader parent = ForgeBaseline.class.getClassLoader();
            var index = ClassIndex.prepare(archives, parent, audit, libraries, Set.of(runtime.bridge()));
            report.put("minecraftClientIndexed", index.entry("net.minecraft.client.Minecraft") != null);
            report.put("upstreamStructure", ForgeAnchors.verify(index, audit));
            pipeline.seal(audit);
            try (var loader = new GameClassLoader(index, pipeline, parent, audit)) {
                loader.open();
                var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
                try {
                    report.put("nativeServices", runtime.prepare(loader, output.resolve("run"), arguments[4]));
                    var probe = Class.forName("org.neoforbric.forge.runtime.ForgeContractProbe", true, loader);
                    report.put("contracts", ForgeRuntime.invoke(probe.getMethod("run", Path.class), output.resolve("run")));
                    if (loader.hasDefined("net.minecraft.client.Minecraft")) throw new Failure("FORGE_CLIENT_DEFINITION", "Headless probe defined Minecraft client");
                    report.put("minecraftClientDefined", false); report.put("side", arguments[4]); report.put("referenceMods", 0); report.put("result", "PASS");
                } finally { Thread.currentThread().setContextClassLoader(previous); }
            }
        } catch (Exception | Error failure) {
            report.put("result", "FAIL"); report.put("error", failure.toString());
            List<String> causes = new ArrayList<>(); Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) causes.add(cause.toString());
            report.put("causes", causes); throw failure;
        } finally {
            Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            audit.write(output.resolve("audit.json"), String.valueOf(report.getOrDefault("result", "FAIL")));
        }
        System.out.println("FORGE_BASELINE_PASS side=" + arguments[4] + " referenceMods=0 report=" + output.resolve("report.json"));
    }
}
