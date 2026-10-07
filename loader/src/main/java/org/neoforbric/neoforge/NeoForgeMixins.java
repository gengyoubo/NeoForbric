package org.neoforbric.neoforge;

import java.util.*;
import org.neoforbric.fabric.*;
import org.neoforbric.loader.*;
import org.spongepowered.asm.mixin.*;

/** The kernel-owned Mixin service is shared tooling, with NeoForge configs targeting Mojang names. */
public final class NeoForgeMixins {
    private static boolean active;
    public static boolean active() { return active; }
    private final AuditLog audit;
    private final NeoFabricLauncher bytecode;
    private boolean ready;
    public NeoForgeMixins(TransformPipeline pipeline, AuditLog audit) {
        active = true; this.audit = audit;
        bytecode = new NeoFabricLauncher(net.fabricmc.api.EnvType.CLIENT, "net.minecraft.client.main.Main", audit);
        bytecode.mixinBoundary("neoforge-mixin");
        Set<String> preceding = pipeline.registeredIds();
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "neoforge-mixin"; }
            public Set<String> after() { return preceding; }
            public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
                if (!ready) return bytes;
                try { return NeoMixinService.transform(context.name(), bytes); }
                catch (org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError directMixin) {
                    audit.record("MIXIN", "mixin-direct-load", context.name(), Map.of()); return bytes;
                }
            }
        });
    }
    public GameClassLoader.Generated generated(String name, ClassIndex index) { return NeoMixinService.generated(name, index); }
    public void bind(ClassIndex index, GameClassLoader game, TransformPipeline pipeline, List<Archive> archives) {
        bytecode.bind(index, game, pipeline, archives);
        System.setProperty("mixin.bootstrapService", NeoMixinBootstrap.class.getName());
        System.setProperty("mixin.service", NeoMixinService.class.getName());
        NeoMixinService.attach(audit);
        org.spongepowered.asm.launch.MixinBootstrap.init();
        if (!(org.spongepowered.asm.service.MixinService.getService() instanceof NeoMixinService)) throw new Failure("MIXIN_OWNERSHIP", "NeoForbric Mixin service not selected");
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
    }
    public void start(List<String> configs) {
        configs.forEach(Mixins::addConfiguration);
        // NeoForge 21.1.248 pins sponge-mixin 0.15.2 (Fabric compatibility 0.14.0).
        // Match that contract through Mixin's supported compatibility decoration; required
        // injections and explicitly strict missing-target checks remain enforced.
        for (var config : Mixins.getConfigs()) config.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_0_14_0);
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        ready = true; bytecode.finishMixin();
        audit.record("PREPARE", "neoforge-mixin-ready", "plan", Map.of("configs", Integer.toString(configs.size()), "loader", "G"));
    }
}
