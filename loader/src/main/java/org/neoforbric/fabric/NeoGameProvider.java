package org.neoforbric.fabric;

import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.Arguments;
import org.neoforbric.bootstrap.LaunchOptions;
import org.neoforbric.loader.Failure;

/** Runtime information only. All native discovery, launch and classpath mutation entry points are forbidden. */
final class NeoGameProvider implements GameProvider {
    private final LaunchOptions options;
    private final Path directory;
    private final Arguments arguments = new Arguments();
    NeoGameProvider(LaunchOptions options) {
        this.options = options; arguments.parse(options.gameArguments().toArray(String[]::new));
        directory = Path.of(arguments.getOrDefault("gameDir", ".")).toAbsolutePath().normalize();
    }
    @Override public String getGameId() { return "minecraft"; }
    @Override public String getGameName() { return "Minecraft"; }
    @Override public String getRawGameVersion() { return "1.21.1"; }
    @Override public String getNormalizedGameVersion() { return "1.21.1"; }
    @Override public Collection<BuiltinMod> getBuiltinMods() { return List.of(); }
    @Override public String getEntrypoint() { return options.mainClass(); }
    @Override public Path getLaunchDirectory() { return directory; }
    @Override public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        if (className.startsWith("net.minecraft.")) return Set.of(BuiltinTransform.WIDEN_ALL_PACKAGE_ACCESS, BuiltinTransform.CLASS_TWEAKS);
        return Set.of(BuiltinTransform.STRIP_ENVIRONMENT);
    }
    @Override public String getRuntimeNamespace(String defaultNamespace) { return "mojang"; }
    @Override public String getDefaultModDistributionNamespace(String defaultNamespace) { return "intermediary"; }
    @Override public boolean requiresUrlClassLoader() { return false; }
    @Override public boolean isEnabled() { return true; }
    private Failure forbidden(String action) { return new Failure("NATIVE_BOOTSTRAP_FORBIDDEN", "NeoForbric owns " + action); }
    @Override public boolean locateGame(FabricLauncher launcher, String[] args) { throw forbidden("game discovery"); }
    @Override public void initialize(FabricLauncher launcher) { throw forbidden("bootstrap initialization"); }
    @Override public GameTransformer getEntrypointTransformer() { throw forbidden("entrypoint transformation"); }
    @Override public void unlockClassPath(FabricLauncher launcher) { throw forbidden("classloader boundaries"); }
    @Override public void launch(ClassLoader loader) { throw forbidden("Minecraft main"); }
    @Override public Arguments getArguments() { return arguments; }
    @Override public String[] getLaunchArguments(boolean sanitize) {
        List<String> result = new ArrayList<>(options.gameArguments());
        if (sanitize) for (int i = 0; i + 1 < result.size(); i++) if (result.get(i).equals("--accessToken")) result.set(i + 1, "<redacted>");
        return result.toArray(String[]::new);
    }
    @Override public boolean canOpenErrorGui() { return false; }
}
