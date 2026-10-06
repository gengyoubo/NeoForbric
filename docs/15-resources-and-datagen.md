# 数据包、Resource Reload 与 Datagen

目标 1.21.1。**不能把所有数据与资源工作塞进一个 reload 事件。** 这会混淆注册定义、世界内容、运行替换和离线产物。

## 1. 四条流程

| 流程 | 输入 / 生命周期 | 关键边界 |
| --- | --- | --- |
| 世界加载动态 registry bootstrap | datapack、codec 与 lookup context；建立世界 RegistryAccess | 先声明 registry / codec，加载内容、解析跨表引用后冻结；不是全局 DeferredRegister |
| server data runtime reload | recipes、loot、tags 及专门可重载内容 | prepare / apply、registry lookup、数据代次；不保证世界生成 registry 每次都重建 |
| client resources reload | models、textures、shader、语言、声音等 | client resource manager，render / GPU 生命周期；不是服务端数据监听 |
| datagen | bootstrap、provider、输出目录，离线生成 JSON / 资源 | 写产物，不应启动完整 world / network；验证实际产物可被 runtime 读取 |

NeoForge RegistryDataLoader Patch 与 ReloadableServerResources Patch 位于不同加载路径。可重载 registry 条目加载与世界生成等初始动态注册不可合成“所有 RegistryAccess 在 reload 时一起换一遍”。具体支持集合应以所选基底确认。[RegistryDataLoader Patch][n-regload]、[ReloadableServerRegistries Patch][n-reloadreg]

Fabric DynamicRegistries 和 NeoForge DataPackRegistryEvent 都允许区分数据与网络 codec；datagen bootstrap 的 RegistrySetBuilder / lookup 并非模组任意时间向运行中的冻结表写入的后门。见 [注册专项](07-registries.md)。[Fabric DynamicRegistries][fa-dynamic]、[NeoForge DataPackRegistryEvent][n-datapack]

## 2. Listener 的依赖与执行

Fabric ResourceManagerHelperImpl 按 SERVER_DATA / CLIENT_RESOURCES 区分实例，允许有标识和依赖的 listener，保持 vanilla listener 的相对顺序并在其后安排自定义 listener。重复 ID、依赖缺失和环需要按实现处理；不能拿 mod load order 当 listener 依赖图。[ResourceManagerHelperImpl][fa-reshelper]、[IdentifiableResourceReloadListener][fa-reloadlistener]

Forge / NeoForge AddReloadListenerEvent 传递 server resource / registry lookup 背景；client listener 注册另走客户端事件。两个事件都叫 listener，也不表示线程或准备屏障相同。[Forge AddReloadListenerEvent][f-reloadevent]、[NeoForge AddReloadListenerEvent][n-reloadevent]、[NeoForge client reload 事件][n-clientreload]

统一调度需要保留异步 prepare executor、apply executor、preparation barrier 和依赖。不要把所有 listener 的 reload 函数同步调用一次就报告完成；future 未完成、exceptional completion 和 apply 次序必须跟踪。

## 3. Pack 与 codec 驱动内容

mod 资源包、内建可选包、用户数据包、覆盖层及 pack 顺序是运行输入；`assets` 与 `data` 不混用。模组 JAR 转换应保留 pack.mcmeta、JSON、目录结构和声明，而非只转 class。自定义动态 registry 的非 vanilla namespace 路径也需要适配，不能盲目去掉一层 namespace。[Fabric ResourceManagerHelperImpl][fa-reshelper]、[Fabric DynamicRegistries][fa-dynamic]

codec 解析依赖 RegistryOps / Holder lookup：相同 JSON 在错误 registry context 中也可能失败或引用错条目。记录 pack 来源、资源路径、registry key、generation 和 codec 错误路径。遇到 schema 差异不得吞错后给空列表；那会把本应加载的内容丢掉。

NeoForge data map 等扩展是独立的 registry 关联数据和同步任务，不能仅把其 JSON 当成普通 recipe listener。若首批未支持，应明确标记，并在现有 mod 的必要能力检查中处理。[RegistryManager][n-regmanager]、[NetworkRegistry][n-network]

## 4. 失败与原子性

新 Common API 可建议 prepare 独立状态、全部校验后提交新 generation；失败时保留旧状态。但这只是新契约设计，不能宣称所有原生 listener 都能回滚。有些 apply 已经改全局缓存，源行为可能是 reload 失败、退出或部分状态更新。

现有 JAR 兼容要保留或明确限制其失败语义。审计分别报告 prepare 成功、apply 开始 / 完成与最后可用代次；不能只记录一个“reload 返回 false”。成功后再触发需要的 tag / recipe / registry 等网络更新，不把旧代次内容发给客户端。

## 5. Datagen 单独适配

Fabric DataGeneratorEntrypoint / FabricDataGenerator 管理 provider 与 pack，并可建立 registry bootstrap；Forge / NeoForge GatherDataEvent 提供生成器、lookup future 和侧别包含选项。NeoForge CommonModLoader 的 datagen 只执行 begin 范围，不能用完整游戏初始化替代。[Fabric DataGeneratorEntrypoint][fa-datagenentry]、[FabricDataGenerator][fa-datagen]、[NeoForge GatherDataEvent][n-gather]、[Forge GatherDataEvent][f-gather]、[CommonModLoader][n-common]

生成的 codec 驱动 registry / tags / recipes 应在测试世界中重新读取并核对。生成程序 exit 0 不证明 JSON path、引用和 codec 一定正确。输出命名空间冲突、两个 provider 覆盖同文件、缓存造成漏输出也需记录。

## 6. 待执行探针

| 编号 | 场景 | 验收 |
| --- | --- | --- |
| S3-L01 | world bootstrap 与两次 /reload | 哪些 registry 实际换代、哪些仅重载 tags / 数据 |
| S3-L02 | 有依赖 listener 的并行 prepare / 顺序 apply | barrier、依赖、线程与异常传播 |
| S3-L03 | pack 优先级、内建可选包与 overlays | 原生资源选择结果、可追踪来源 |
| S3-L04 | 坏 codec、缺 Holder 引用、apply 中途失败 | 阶段归因、实际可用状态，不假报回滚成功 |
| S3-L05 | client F3+T 与 server /reload 分别执行 | 正确资源域；GPU 与 server cache 生命周期 |
| S3-L06 | 三端 datagen 固定相同输入后运行读取产物 | 输出、路径、引用与 codec 对照 |

[f-gather]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/data/event/GatherDataEvent.java
[f-reloadevent]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/event/AddReloadListenerEvent.java
[fa-datagen]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/FabricDataGenerator.java
[fa-datagenentry]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/DataGeneratorEntrypoint.java
[fa-dynamic]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/api/event/registry/DynamicRegistries.java
[fa-reloadlistener]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/api/resource/IdentifiableResourceReloadListener.java
[fa-reshelper]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/impl/resource/loader/ResourceManagerHelperImpl.java
[n-clientreload]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java
[n-common]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/internal/CommonModLoader.java
[n-datapack]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DataPackRegistryEvent.java
[n-gather]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/data/event/GatherDataEvent.java
[n-network]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java
[n-regload]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/resources/RegistryDataLoader.java.patch
[n-regmanager]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/RegistryManager.java
[n-reloadevent]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/event/AddReloadListenerEvent.java
[n-reloadreg]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/ReloadableServerResources.java.patch
