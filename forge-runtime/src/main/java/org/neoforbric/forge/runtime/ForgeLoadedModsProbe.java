package org.neoforbric.forge.runtime;

import java.util.*;
import net.minecraftforge.fml.*;

public final class ForgeLoadedModsProbe {
    private ForgeLoadedModsProbe() {}
    public static Map<String, Object> beforeConstruction(List<String> ids) throws Exception {
        for (String id : ids) if (ModList.get().getModContainerById(id).isPresent()) throw new IllegalStateException("Mod constructed before native gather: " + id);
        var crash = ModList.class.getDeclaredMethod("crashReport"); crash.setAccessible(true);
        String report = (String)crash.invoke(ModList.get());
        for (String id : ids) if (!report.contains(id)) throw new IllegalStateException("Mod missing from early crash report: " + id);
        if (!report.contains("NONE")) throw new IllegalStateException("Early crash report invented a constructed state");
        return Map.of("modContainers", "empty before native gather", "crashReport", "PASS");
    }
    public static Map<String, Object> verify(List<String> ids) throws Exception {
        if (!ModLoader.isLoadingStateValid()) throw new IllegalStateException("Forge loading state invalid");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : ids) {
            var container = ModList.get().getModContainerById(id).orElseThrow(); Object mod = container.getMod();
            if (mod == null || mod.getClass().getClassLoader() != ForgeLoadedModsProbe.class.getClassLoader()) throw new IllegalStateException("Mod was not constructed in G: " + id);
            result.put(id, Map.of("class", mod.getClass().getName(), "loader", "G", "constructed", true));
            if (id.equals("nf_forge_probe")) mod.getClass().getMethod("verify").invoke(mod);
        }
        return result;
    }
}
