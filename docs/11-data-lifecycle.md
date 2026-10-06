# Capability、Attachment、DataComponent 与持久数据

这是一项独立服务工程：对象结构、查询、复制、失效、存档和网络必须一起设计。**保存成功、复制正确、客户端可见分别验收。**

## 1. 四个领域的区别

| 领域 | 用途 | 主要差异 |
| --- | --- | --- |
| Forge Capability | 查询对象提供的行为 / 接口，也可以由 provider 序列化数据 | CapabilityProvider / dispatcher、LazyOptional、invalidate / revive；对象父类和附加事件相关 |
| NeoForge Capability | 查询 Entity / ItemStack / Block 的功能接口与 context | 注册 provider、位置 / 方位等 context；BlockCapabilityCache 与显式失效，不是 Forge LazyOptional 门面 |
| Fabric / NeoForge Attachment | 给宿主附加按类型标识的数据 | holder 支持集合、codec / serializer、默认值、复制、死亡保留和同步选项不同 |
| DataComponent | 1.21.1 ItemStack 的类型化内容 | component type、默认值与 patch、持久 codec / network codec、物品复制与堆叠；不能当所有 Entity 的通用附件 |
| persistent NBT / SavedData | 生态原有任意标签与世界持久状态 | 存档位置、dirty 标记、作用域、保留规则；默认不承诺网络同步 |

依据：[Forge CapabilityProvider][f-capprovider]、[LazyOptional][f-lazy]、[NeoForge CapabilityHooks][n-caphooks]、[BlockCapabilityCache][n-capcache]、[Fabric AttachmentType][fa-atttype]、[NeoForge AttachmentType][n-atttype]、[ItemStack Patch][n-itempatch]。

不能把 capability 查询成功等同于对象数据持久化，也不能把 codec 可序列化等同于自动同步。Fabric Transfer API 的 Storage / Transaction 等功能协议也不直接等价于 Forge / NeoForge item handler；未来适配需要额外定义事务、模拟操作和 context 语义，本轮未做其全面审计。

## 2. 宿主覆盖和 ABI

| 宿主 | Forge 样本 | NeoForge 样本 | Fabric Attachment 样本 |
| --- | --- | --- | --- |
| Entity / Player | CapabilityProvider 父类；实体持久 NBT；clone 事件需 provider 自己处理数据 | AttachmentHolder 父类、EntityCapability 与 IEntityExtension | Mixin 提供 AttachmentTarget，复制 / world change 等路径 |
| ItemStack | Patch 继承 CapabilityProvider；同时有 vanilla components | ItemCapability；持久数据主要使用 DataComponent，不把 ItemStack 宣称为通用 AttachmentHolder | Attachment API 当前声明的宿主不含 ItemStack；使用 component 或模组自己的结构 |
| BlockEntity | capability 附加 / 查询，保存及卸载失效 | BlockCapability、AttachmentHolder 和 dirty 约束 | AttachmentTarget、保存与 dirty 约束 |
| Level / World | 世界 capability / 持久状态路径 | Level attachment，LevelAttachmentsSavedData | world attachment 的 PersistentState 保存路径 |
| Chunk | capability / 原生 Patch 需逐项保留 | ChunkAccess attachment，ProtoChunk→LevelChunk 复制 | Chunk attachment 与 promotion 复制 |

Forge Entity / ItemStack Patch 和 NeoForge Entity Patch 明确改变父类。已有 JAR 的方法引用、super 调用、字段或接口调用会依赖结构；仅在旁边放 WeakHashMap 没法满足这些 ABI。[Forge Entity Patch][f-entitypatch]、[Forge ItemStack Patch][f-itempatch]、[NeoForge Entity Patch][n-entitypatch]、[接口清单][n-interfaces]

上表表示取样源码的领域覆盖，不代表每条宿主链已逐行审计。若首批只支持 Entity 和 ItemStack，应在发现阶段拒绝必需但未支持的 Chunk / Level 功能；不能保留方法签名却返回永久空数据。

## 3. 查询与失效

Forge `LazyOptional` 有缓存、listener 和 invalidate 生命周期。provider 失效后旧句柄必须失效，revive 后不能让原句柄无条件恢复成新对象。Entity 的 remove / revive Patch 参与该生命周期。[LazyOptional][f-lazy]、[CapabilityProvider][f-capprovider]、[Entity Patch][f-entitypatch]

NeoForge BlockCapabilityCache 绑定 ServerLevel、位置、capability、context，并监听该位置失效；BlockEntity 替换、卸载或 provider 状态改变应触发相应失效 / 重查询。缓存逻辑不应直接套用 LazyOptional。[BlockCapabilityCache][n-capcache]、[CapabilityHooks][n-caphooks]

适配缓存键至少包含世界实例、宿主身份 / 位置、capability、context 和有效代次。跨维度后的相同坐标不能复用旧世界缓存；卸载后不得留下强引用常驻。生命周期边界由各源 API 分别处理。

## 4. 保存与复制

NeoForge AttachmentType 的可序列化附件默认通过 serializer 写读进行复制；AttachmentInternals 常规复制跳过没有 serializer 的类型，无 serializer 的默认 copy handler 自身则会抛错。copyOnDeath / 自定义 copyHandler 有条件约束。BlockEntity / Chunk 内部可变数据发生修改后仍需正确标脏。[AttachmentType][n-atttype]、[AttachmentInternals][n-attinternals]

Fabric AttachmentType 文档说明复制路径会将附件转交新实例，默认不是承诺一次 codec 深拷贝；持有旧 target 引用的 mutable 对象可能失效，mod 需自定义复制处理。死亡复制按 copyOnDeath 控制。**把两生态附件统一成“复制时总是 serialize→deserialize”会改变 Fabric 行为；统一成共享引用又会破坏 NeoForge 行为。**[Fabric AttachmentType][fa-atttype]、[AttachmentRegistryImpl][fa-attimpl]

建议统一数据服务保留原生态类型身份与操作策略：默认构造、read/write codec、copy、death policy、holder rebinding、dirty 和 sync。跨生态新 Common 数据类型可采用统一规则，但不要偷偷改变已有类型。

persistent data 也有原存档 key。固定 Entity Patch 分别使用 `ForgeData` 与 `NeoForgeData`，附件有自己的 key；迁到统一命名空间必须提供显式迁移和未知数据保留策略，不能重命名后当旧世界不存在数据。[Forge Entity Patch][f-entitypatch]、[NeoForge Entity Patch][n-entitypatch]、[Fabric AttachmentSerializingImpl][fa-attserialize]

玩家死亡 clone、返回 End、普通玩家跨维度、非玩家实体跨维度、实体转换、Chunk promotion 是不同复制路径。不能在每次 Entity spawn 都复制旧数据；否则容易重复或串档。ItemStack 的 copy / split / merge 还涉及 component 与 capability 自定义状态、相等性和堆叠规则。

## 5. 同步必须限定修订和触发条件

本轮固定的 Fabric 1.21.1 分支源码已有 `AttachmentRegistry.Builder.syncWith(PacketCodec, predicate)`，NeoForge 固定分支 AttachmentType.Builder 也已有 sync(handler / StreamCodec / predicate)。因此“1.21.1 的附件全部没有同步功能”不符合本轮样本。**不能将分支当前功能外推到该游戏版本的所有旧修订。**[Fabric AttachmentRegistry][fa-attregistry]、[NeoForge AttachmentType][n-atttype]

NeoForge AttachmentSync 区分 entity tracking / self、block entity、chunk、level 等接收者；Fabric 也维护 sync change 与已接受附件连接状态。内存中直接改 mutable 对象不一定触发 setData 或变更记录，应保持源 API 的显式通知要求。[NeoForge AttachmentSync][n-attsync]、[Fabric AttachmentTargetsMixin][fa-attmixin]、[Fabric AttachmentSync][fa-attsync]

DataComponent 持久 codec 与 stream codec、附件同步与 `SynchedEntityData`、菜单 / block entity update packet、任意 persistent NBT 是不同协议。DataComponentType 未显式设 network codec 时还可能由 persistent codec 构造网络 codec；不要误以为“只配置 persistent”就一定没有网络数据。component 值应保持稳定的 equals / hashCode 并按 API set，避免内部可变值绕过 patch / cache 更新。[Data Components 官方文档](https://docs.neoforged.net/docs/1.21.1/items/datacomponents/)

不要给所有数据“统一每 Tick 广播”，这会改变权限、tracking、时机与客户端 codec 需求。

## 6. 待执行探针

| 编号 | 宿主 / 操作 | 验收 |
| --- | --- | --- |
| S3-P01 | Entity、BlockEntity、Level、Chunk 保存退出再载入 | 类型、值、NBT key、dirty、未知字段保留 |
| S3-P02 | 玩家死亡 clone / End 返回 / 跨维度；非玩家转换 | 死亡策略与其他复制分别正确，无旧 holder 引用 |
| S3-P03 | mutable attachment 默认复制与定制复制 | 原生态深浅复制策略、无错误共享 |
| S3-P04 | capability invalidate / revive，block 替换、chunk unload | 旧句柄与缓存失效；正确重查询 |
| S3-P05 | ItemStack copy / split / merge / 菜单传输 | component 与 capability 状态、堆叠边界 |
| S3-P06 | 两个世界相同位置、重启 integrated server | 无跨世界数据与缓存泄漏 |
| S3-P07 | 新 tracking、取消 tracking、数据修改 / 删除 | 接收者过滤、初始与增量同步、codec 兼容 |
| S3-P08 | 同步字段缺 codec / 对端没有 attachment type | 连接或功能明确失败；不假造丢失值 |

[f-capprovider]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/common/capabilities/CapabilityProvider.java
[f-entitypatch]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/entity/Entity.java.patch
[f-itempatch]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/item/ItemStack.java.patch
[f-lazy]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/common/util/LazyOptional.java
[fa-attimpl]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentRegistryImpl.java
[fa-attmixin]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/mixin/attachment/AttachmentTargetsMixin.java
[fa-attregistry]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentRegistry.java
[fa-attserialize]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/AttachmentSerializingImpl.java
[fa-attsync]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/impl/attachment/sync/AttachmentSync.java
[fa-atttype]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentType.java
[n-attinternals]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentInternals.java
[n-attsync]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentSync.java
[n-atttype]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentType.java
[n-capcache]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/capabilities/BlockCapabilityCache.java
[n-caphooks]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/capabilities/CapabilityHooks.java
[n-entitypatch]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/Entity.java.patch
[n-interfaces]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/resources/META-INF/injected-interfaces.json
[n-itempatch]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/item/ItemStack.java.patch
