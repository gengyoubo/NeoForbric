package org.neoforbric.forge;

import java.util.*;
import org.neoforbric.fabric.*;
import org.neoforbric.loader.*;
import org.spongepowered.asm.mixin.*;

/** The kernel Mixin service consumes Forge configs; it never invokes Forge's launcher service. */
public final class ForgeMixins {
    private static boolean active;
    public static boolean active() { return active; }
    private final NeoFabricLauncher bytes;
    private final AuditLog audit;
    private boolean ready;
    public ForgeMixins(TransformPipeline pipeline, AuditLog audit, String side) {
        active = true; this.audit = audit;
        bytes = new NeoFabricLauncher(side.equals("client") ? net.fabricmc.api.EnvType.CLIENT : net.fabricmc.api.EnvType.SERVER, side.equals("client") ? "net.minecraft.client.main.Main" : "net.minecraft.server.Main", audit);
        bytes.mixinBoundary("forge-mixin"); Set<String> preceding = pipeline.registeredIds();
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "forge-mixin"; }
            public Set<String> after() { return preceding; }
            public byte[] transform(TransformPipeline.Context context, byte[] input) {
                if (!ready || context.name().startsWith("net.minecraftforge.fml.loading.") || context.name().startsWith("net.minecraftforge.fml.common.asm.") || context.name().startsWith("net.minecraftforge.eventbus.")
                        || context.name().startsWith("net.minecraftforge.coremod.") || context.name().startsWith("net.minecraftforge.accesstransformer.")
                        || context.name().startsWith("cpw.mods.modlauncher.")) return input;
                return NeoMixinService.transform(context.name(), input);
            }
        });
    }
    public void bind(ClassIndex index, GameClassLoader loader, TransformPipeline pipeline, List<Archive> archives, String side) {
        bytes.bind(index, loader, pipeline, archives);
        System.setProperty("mixin.bootstrapService", NeoMixinBootstrap.class.getName()); System.setProperty("mixin.service", NeoMixinService.class.getName());
        NeoMixinService.attach(audit); org.spongepowered.asm.launch.MixinBootstrap.init();
        if (!(org.spongepowered.asm.service.MixinService.getService() instanceof NeoMixinService)) throw new Failure("MIXIN_OWNERSHIP", "NF Mixin service not selected");
        MixinEnvironment.getDefaultEnvironment().setSide(side.equals("client") ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);
    }
    public void start(List<String> configs) { configs.forEach(Mixins::addConfiguration); com.llamalad7.mixinextras.MixinExtrasBootstrap.init(); ready = true; bytes.finishMixin(); audit.record("PREPARE", "forge-mixin-ready", "plan", Map.of("configs", configs.toString(), "loader", "G")); }
    public GameClassLoader.Generated generated(String name, ClassIndex index) { return NeoMixinService.generated(name, index); }
}
