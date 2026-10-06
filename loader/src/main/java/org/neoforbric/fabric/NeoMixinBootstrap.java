package org.neoforbric.fabric;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** Explicit service selection avoids booting native Knot or LaunchWrapper service providers. */
public final class NeoMixinBootstrap implements IMixinServiceBootstrap {
    @Override public String getName() { return "NeoForbric"; }
    @Override public String getServiceClassName() { return NeoMixinService.class.getName(); }
    @Override public void bootstrap() { }
}
