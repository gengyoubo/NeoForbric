package org.neoforbric.fabric;

import java.util.Map;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.IMixinAuditTrail;
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot;
import org.neoforbric.loader.*;

/** Reuses Fabric's passive bytecode/provider implementation with an explicitly owned transformer and service. */
public final class NeoMixinService extends MixinServiceKnot {
    private static IMixinTransformer transformer;
    private static AuditLog audit;
    static void attach(AuditLog log) { audit = log; }
    @Override public String getName() { return "NeoForbric"; }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, NeoMixinService.class.getClassLoader()); }
    @Override public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            if (transformer != null) throw new Failure("MIXIN_OWNERSHIP", "Second Mixin transformer offered");
            transformer = factory.createTransformer();
        }
    }
    static byte[] transform(String name, byte[] bytes) {
        if (transformer == null) throw new Failure("MIXIN_NOT_READY", "NeoForbric Mixin transformer was not offered");
        return transformer.transformClassBytes(name, name, bytes);
    }
    static GameClassLoader.Generated generated(String name, ClassIndex index) {
        if (transformer == null) return null;
        var info = transformer.getExtensions().getSyntheticClassRegistry().findSyntheticClass(name);
        if (info == null) return null;
        String mixin = info.getMixin().getClassName(); ClassIndex.Entry owner = index.entry(mixin);
        if (owner == null) throw new Failure("MIXIN_GENERATED_OWNER", "No admitted owner for synthetic class " + name + " from " + mixin);
        byte[] bytes = transformer.generateClass(org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment(), name);
        if (bytes == null) throw new Failure("MIXIN_GENERATION", "Registered synthetic class could not be generated: " + name);
        return new GameClassLoader.Generated(bytes, owner.archive(), "Mixin:" + mixin);
    }
    @Override public IMixinAuditTrail getAuditTrail() {
        return new IMixinAuditTrail() {
            @Override public void onApply(String className, String mixinName) { audit.record("MIXIN", "mixin-applied", className, Map.of("mixin", mixinName, "owner", "NeoForbric")); }
            @Override public void onPostProcess(String className) { audit.record("MIXIN", "mixin-postprocess", className, Map.of()); }
            @Override public void onGenerate(String className, String generatorName) { audit.record("MIXIN", "mixin-generated", className, Map.of("generator", generatorName)); }
        };
    }
}
