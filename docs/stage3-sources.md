# 第三阶段来源与复现索引

调查日期：2026-10-06，目标 Minecraft 1.21.1。采用 [前期固定提交](sources.md) 与版本化源码包，正文引用直接链接对应输入。下载文件不等于全文审计；本轮检查与结论相关的片段，没有运行游戏。

## 1. Git 引用索引

| 引用 ID | 仓库 | 路径 |
| --- | --- | --- |
| `f-asmhandler` | MinecraftForge/EventBus | [src/main/java/net/minecraftforge/eventbus/ASMEventHandler.java](https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/ASMEventHandler.java) |
| `f-atservice` | MinecraftForge/AccessTransformers | [at-mlservice/src/main/java/net/minecraftforge/accesstransformer/service/AccessTransformerService.java](https://github.com/MinecraftForge/AccessTransformers/blob/c89aba52660fb341e7e4ba663ff78df240611f0c/at-mlservice/src/main/java/net/minecraftforge/accesstransformer/service/AccessTransformerService.java) |
| `f-build` | MinecraftForge/MinecraftForge | [build_forge.gradle](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/build_forge.gradle) |
| `f-bus` | MinecraftForge/EventBus | [src/main/java/net/minecraftforge/eventbus/EventBus.java](https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/EventBus.java) |
| `f-capprovider` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/common/capabilities/CapabilityProvider.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/common/capabilities/CapabilityProvider.java) |
| `f-corewrapper` | MinecraftForge/CoreMods | [src/main/java/net/minecraftforge/coremod/transformer/CoreModBaseTransformer.java](https://github.com/MinecraftForge/CoreMods/blob/e7503dd0cdd2607a2244ece31174d5c8e01bf306/src/main/java/net/minecraftforge/coremod/transformer/CoreModBaseTransformer.java) |
| `f-datapack` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/registries/DataPackRegistryEvent.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/registries/DataPackRegistryEvent.java) |
| `f-entitypatch` | MinecraftForge/MinecraftForge | [patches/minecraft/net/minecraft/world/entity/Entity.java.patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/entity/Entity.java.patch) |
| `f-event` | MinecraftForge/EventBus | [src/main/java/net/minecraftforge/eventbus/api/Event.java](https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/api/Event.java) |
| `f-gamedata` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/registries/GameData.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/registries/GameData.java) |
| `f-gather` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/data/event/GatherDataEvent.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/data/event/GatherDataEvent.java) |
| `f-itempatch` | MinecraftForge/MinecraftForge | [patches/minecraft/net/minecraft/world/item/ItemStack.java.patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/item/ItemStack.java.patch) |
| `f-jij` | MinecraftForge/MinecraftForge | [fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator.java) |
| `f-lazy` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/common/util/LazyOptional.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/common/util/LazyOptional.java) |
| `f-mlclass` | MinecraftForge/ModLauncher | [src/main/java/cpw/mods/modlauncher/ClassTransformer.java](https://github.com/MinecraftForge/ModLauncher/blob/ffce32c1bfa79f443baba9ee58030c2aea5c26b0/src/main/java/cpw/mods/modlauncher/ClassTransformer.java) |
| `f-model` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/client/event/ModelEvent.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/client/event/ModelEvent.java) |
| `f-modinfo` | MinecraftForge/MinecraftForge | [fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/ModInfo.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/ModInfo.java) |
| `f-netinit` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/network/NetworkInitialization.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/NetworkInitialization.java) |
| `f-packet` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/network/ForgePacketHandler.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/ForgePacketHandler.java) |
| `f-parallel` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/fml/event/lifecycle/ParallelDispatchEvent.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/fml/event/lifecycle/ParallelDispatchEvent.java) |
| `f-reloadevent` | MinecraftForge/MinecraftForge | [src/main/java/net/minecraftforge/event/AddReloadListenerEvent.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/event/AddReloadListenerEvent.java) |
| `f-sorter` | MinecraftForge/MinecraftForge | [fmlloader/src/main/java/net/minecraftforge/fml/loading/ModSorter.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/ModSorter.java) |
| `f-workqueue` | MinecraftForge/MinecraftForge | [fmlcore/src/main/java/net/minecraftforge/fml/DeferredWorkQueue.java](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlcore/src/main/java/net/minecraftforge/fml/DeferredWorkQueue.java) |
| `fa-arrayevent` | FabricMC/fabric | [fabric-api-base/src/main/java/net/fabricmc/fabric/impl/base/event/ArrayBackedEvent.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-api-base/src/main/java/net/fabricmc/fabric/impl/base/event/ArrayBackedEvent.java) |
| `fa-attimpl` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentRegistryImpl.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentRegistryImpl.java) |
| `fa-attmixin` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/mixin/attachment/AttachmentTargetsMixin.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/mixin/attachment/AttachmentTargetsMixin.java) |
| `fa-attregistry` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentRegistry.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentRegistry.java) |
| `fa-attserialize` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentSerializingImpl.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentSerializingImpl.java) |
| `fa-attsync` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/sync/AttachmentSync.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/sync/AttachmentSync.java) |
| `fa-atttype` | FabricMC/fabric | [fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentType.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentType.java) |
| `fa-config` | FabricMC/fabric | [fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerConfigurationNetworkHandlerMixin.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerConfigurationNetworkHandlerMixin.java) |
| `fa-datagen` | FabricMC/fabric | [fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/FabricDataGenerator.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/FabricDataGenerator.java) |
| `fa-datagenentry` | FabricMC/fabric | [fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/DataGeneratorEntrypoint.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/DataGeneratorEntrypoint.java) |
| `fa-dynamic` | FabricMC/fabric | [fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/api/event/registry/DynamicRegistries.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/api/event/registry/DynamicRegistries.java) |
| `fa-login` | FabricMC/fabric | [fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerLoginNetworkHandlerMixin.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerLoginNetworkHandlerMixin.java) |
| `fa-model` | FabricMC/fabric | [fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.java) |
| `fa-payload` | FabricMC/fabric | [fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java) |
| `fa-regsync` | FabricMC/fabric | [fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.java) |
| `fa-reloadlistener` | FabricMC/fabric | [fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/api/resource/IdentifiableResourceReloadListener.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/api/resource/IdentifiableResourceReloadListener.java) |
| `fa-reshelper` | FabricMC/fabric | [fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/impl/resource/loader/ResourceManagerHelperImpl.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/impl/resource/loader/ResourceManagerHelperImpl.java) |
| `fa-shader` | FabricMC/fabric | [fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/CoreShaderRegistrationCallback.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/CoreShaderRegistrationCallback.java) |
| `fa-useblock` | FabricMC/fabric | [fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/api/event/player/UseBlockCallback.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/api/event/player/UseBlockCallback.java) |
| `fa-worldrender` | FabricMC/fabric | [fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents.java](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents.java) |
| `fl-build` | FabricMC/fabric-loader | [build.gradle](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/build.gradle) |
| `fl-discoverer` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java) |
| `fl-entrypatch` | FabricMC/fabric-loader | [minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/patch/EntrypointPatch.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/patch/EntrypointPatch.java) |
| `fl-hooks` | FabricMC/fabric-loader | [minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java) |
| `fl-impl` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java) |
| `fl-knot` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java) |
| `fl-metadata` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java) |
| `fl-resolver` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/discovery/ModResolver.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/discovery/ModResolver.java) |
| `fl-transformer` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/transformer/FabricTransformer.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/transformer/FabricTransformer.java) |
| `fl-version` | FabricMC/fabric-loader | [src/main/java/net/fabricmc/loader/impl/util/version/VersionPredicateParser.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/util/version/VersionPredicateParser.java) |
| `mx-handler` | SpongePowered/Mixin | [src/modlauncher/java/org/spongepowered/asm/service/modlauncher/MixinTransformationHandler.java](https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/modlauncher/java/org/spongepowered/asm/service/modlauncher/MixinTransformationHandler.java) |
| `mx-plugin` | SpongePowered/Mixin | [src/modlauncher/java/org/spongepowered/asm/launch/MixinLaunchPluginLegacy.java](https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/modlauncher/java/org/spongepowered/asm/launch/MixinLaunchPluginLegacy.java) |
| `n-attinternals` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/attachment/AttachmentInternals.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentInternals.java) |
| `n-attsync` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/attachment/AttachmentSync.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentSync.java) |
| `n-atttype` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/attachment/AttachmentType.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentType.java) |
| `n-capcache` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/capabilities/BlockCapabilityCache.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/capabilities/BlockCapabilityCache.java) |
| `n-caphooks` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/capabilities/CapabilityHooks.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/capabilities/CapabilityHooks.java) |
| `n-clientreload` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java) |
| `n-common` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/internal/CommonModLoader.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/internal/CommonModLoader.java) |
| `n-datapack` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/registries/DataPackRegistryEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DataPackRegistryEvent.java) |
| `n-deferred` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/registries/DeferredRegister.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DeferredRegister.java) |
| `n-devplugin` | neoforged/NeoForge | [buildSrc/src/main/java/net/neoforged/neodev/NeoDevPlugin.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/buildSrc/src/main/java/net/neoforged/neodev/NeoDevPlugin.java) |
| `n-entityext` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/common/extensions/IEntityExtension.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/common/extensions/IEntityExtension.java) |
| `n-entitypatch` | neoforged/NeoForge | [patches/net/minecraft/world/entity/Entity.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/Entity.java.patch) |
| `n-gamedata` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/registries/GameData.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/GameData.java) |
| `n-gather` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/data/event/GatherDataEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/data/event/GatherDataEvent.java) |
| `n-holder` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/registries/DeferredHolder.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DeferredHolder.java) |
| `n-interfaces` | neoforged/NeoForge | [src/main/resources/META-INF/injected-interfaces.json](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/resources/META-INF/injected-interfaces.json) |
| `n-itempatch` | neoforged/NeoForge | [patches/net/minecraft/world/item/ItemStack.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/item/ItemStack.java.patch) |
| `n-mapped` | neoforged/NeoForge | [patches/net/minecraft/core/MappedRegistry.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/core/MappedRegistry.java.patch) |
| `n-model` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/client/event/ModelEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/ModelEvent.java) |
| `n-negotiator` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/network/negotiation/NetworkComponentNegotiator.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/negotiation/NetworkComponentNegotiator.java) |
| `n-network` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java) |
| `n-properties` | neoforged/NeoForge | [gradle.properties](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/gradle.properties) |
| `n-registrar` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/network/registration/PayloadRegistrar.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/registration/PayloadRegistrar.java) |
| `n-regload` | neoforged/NeoForge | [patches/net/minecraft/resources/RegistryDataLoader.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/resources/RegistryDataLoader.java.patch) |
| `n-regmanager` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/registries/RegistryManager.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/RegistryManager.java) |
| `n-reloadevent` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/event/AddReloadListenerEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/event/AddReloadListenerEvent.java) |
| `n-reloadreg` | neoforged/NeoForge | [patches/net/minecraft/server/ReloadableServerResources.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/ReloadableServerResources.java.patch) |
| `n-renderers` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/client/event/EntityRenderersEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/EntityRenderersEvent.java) |
| `n-renderstage` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java) |
| `n-serverconfig` | neoforged/NeoForge | [patches/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch) |
| `n-shader` | neoforged/NeoForge | [src/main/java/net/neoforged/neoforge/client/event/RegisterShadersEvent.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RegisterShadersEvent.java) |
| `n-transources` | neoforged/NeoForge | [buildSrc/src/main/java/net/neoforged/neodev/TransformSources.java](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/buildSrc/src/main/java/net/neoforged/neodev/TransformSources.java) |

Forge ModLauncher 10.2、AT 8.2、Coremods 5.2 和 EventBus 6.2 为版本系列 tag 的固定提交；没有证明与 Forge 所声明的具体发布包二进制一致。对应 SHA 见快照。其余 Git 引用沿用前两轮固定 SHA。

## 2. 下载并核对哈希的源码包

| 样本 | 字节数 | 下载 |
| --- | --- | --- |
| `neo_fml` | 275334 | [源码包](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar) |
| `neo_modlauncher` | 68125 | [源码包](https://maven.neoforged.net/releases/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5-sources.jar) |
| `neo_at` | 38853 | [源码包](https://maven.neoforged.net/releases/net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1-sources.jar) |
| `neo_coremods` | 11583 | [源码包](https://maven.neoforged.net/releases/net/neoforged/coremods/6.0.4/coremods-6.0.4-sources.jar) |
| `neo_bus` | 22669 | [源码包](https://maven.neoforged.net/releases/net/neoforged/bus/8.0.2/bus-8.0.2-sources.jar) |
| `aw` | 17683 | [源码包](https://maven.fabricmc.net/net/fabricmc/access-widener/2.1.0/access-widener-2.1.0-sources.jar) |
| `extras_055` | 221113 | [源码包](https://maven.neoforged.net/releases/io/github/llamalad7/mixinextras-neoforge/0.5.5/mixinextras-neoforge-0.5.5-sources.jar) |
| `extras_041` | 100652 | [源码包](https://repo.maven.apache.org/maven2/io/github/llamalad7/mixinextras-common/0.4.1/mixinextras-common-0.4.1-sources.jar) |
| `neo_at_ml` | 1689 | [源码包](https://maven.neoforged.net/releases/net/neoforged/accesstransformers/at-modlauncher/10.0.1/at-modlauncher-10.0.1-sources.jar) |
| `neo_coremods_fml` | 11360 | [源码包](https://maven.neoforged.net/releases/net/neoforged/coremods/7.0.3/coremods-7.0.3-sources.jar) |
| `neo_bus_fml` | 23775 | [源码包](https://maven.neoforged.net/releases/net/neoforged/bus/8.0.5/bus-8.0.5-sources.jar) |

SHA-256 基于原始下载字节，记录在 [stage3-snapshot.json](stage3-snapshot.json)。Git 源码哈希基于 UTF-8 / LF 内容。两种口径不混用。源码包引用的内部文件 / 类在正文注明，不应将 ZIP 整体视为已审计。

## 3. 版本解析缺口

NeoForge 固定 gradle.properties 声明 FML 4.0.45、ModLauncher 11.0.5、Bus 8.0.2、Coremods 6.0.4；FML POM 又声明 ModLauncher 11.0.3、Bus 8.0.5、Coremods 7.0.3。补查了后两份源码包。本轮未运行 Gradle 依赖解析，也未生成安装 profile，最终选中版本仍未验证。

[FML 4.0.45 POM](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45.pom) 与 [AT module metadata](https://maven.neoforged.net/releases/net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1.module) 也下载并记录哈希。AT 的运行 service 在独立的 `at-modlauncher` 包，不能只读取引擎源码就推定加载阶段。

Forge 的 ModLauncher 10.2.4、AT 8.2.2、Coremods 5.2.6、EventBus 6.2.33 源码包下载返回 HTTP 403；精确版本 tag 查询也未取得对应提交，采用版本系列固定提交补证机制。这是证据限制，不意味着这些功能不存在。

## 4. 官方文档补证

| 文档 | 范围 |
| --- | --- | --- |
| [Registries](https://docs.neoforged.net/docs/1.21.1/concepts/registries/) | 静态与数据包注册概念 |
| [Events](https://docs.neoforged.net/docs/1.21.1/concepts/events/) | 取消、优先级、lifecycle；具体修订以源码为准 |
| [Mod Files](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/) | TOML 字段与依赖类型 |
| [Capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/) | provider 与 cache |
| [Attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/) | 宿主、持久化与复制 |
| [Configuration Tasks](https://docs.neoforged.net/docs/1.21.1/networking/configuration-tasks/) | 配置任务与 acknowledgment |
| [Data Components](https://docs.neoforged.net/docs/1.21.1/items/datacomponents/) | codec、component patch 与值约束 |
| [Custom Model Loaders](https://docs.neoforged.net/docs/1.21.1/resources/client/models/modelloaders/) | geometry 加载 |
| [Resources](https://docs.neoforged.net/docs/1.21.1/resources/) | 资源域与数据包 |

另查 [Forge lifecycle](https://docs.minecraftforge.net/en/1.21.x/concepts/lifecycle/)、[Forge AT](https://docs.minecraftforge.net/en/1.21.x/advanced/accesstransformers/) 与 [MixinExtras WrapOperation](https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation)。文档不是发布依赖锁，跨版本概念由目标源码补证。

## 5. 复核方法

1. 按快照中的完整 Git SHA 与 path 读取 raw source，统一换行为 LF、编码 UTF-8，核对 sha256_utf8_lf。
2. 按源码包 URL 下载字节并核对 SHA-256，解压检查正文提及的 class / method。
3. 重建原生对照时另行锁定发行版、安装产物与 Gradle 解析结果；样本列表不能直接作为可运行组合。
4. 按各专项执行 70 项探针并记录实际结果。本快照所有探针 status 均为 not_run，probes_run=0。

已完成的文档校验与待执行的游戏探针是不同工作。未统计真实模组兼容率。
