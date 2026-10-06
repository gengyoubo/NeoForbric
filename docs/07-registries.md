# 注册表、动态注册表与合法窗口

本轮范围和证据等级见 [第三阶段总览](06-stage3-investigation.md)。下文的状态机是设计草案，原生源码调用链另行注明。

## 1. 三端差异

| 维度 | Fabric 1.21.1 | Forge 1.21.1 | NeoForge 1.21.1 |
| --- | --- | --- | --- |
| 常用静态注册 | 初始化期间直接 `Registry.register`，返回实际对象 | `DeferredRegister` / `RegisterEvent`，还有 Forge registry 包装与快照 | `DeferredRegister` / `RegisterEvent`，主要围绕扩展的 vanilla Registry |
| 延迟对象 | Loader 没有通用 DeferredRegister 契约；API / 模组可以自建 | RegistryObject 与 supplier 绑定须保持 | DeferredHolder 实现 Holder 与 Supplier，未绑定时不能当实际值使用 |
| 新静态 registry | root registry 注册及 Fabric 扩展 | NewRegistryEvent 与 RegistryBuilder / GameData | NewRegistryEvent；创建后未提交会报错 |
| 动态 registry 定义 | DynamicRegistries 注册数据 codec；registerSynced 可设网络 codec 与 SKIP_WHEN_EMPTY | DataPackRegistryEvent.NewRegistry；另设同步 codec | DataPackRegistryEvent.NewRegistry；另设同步 codec |
| 静态 ID 同步 | Fabric RegistrySyncManager 的特定协议与 remap | Forge RegistryList / RegistryData 与快照系统 | RegistryManager 的 SYNC_TO_CLIENT 快照与网络任务 |

依据：[Fabric 动态定义][fa-dynamic]、[Fabric 同步][fa-regsync]、[Forge GameData][f-gamedata]、[NeoForge DeferredHolder][n-holder]、[NeoForge RegistryManager][n-regmanager]、[Forge 数据包注册定义][f-datapack]、[NeoForge 数据包注册定义][n-datapack]。

`Registry.register` 这个名称相同，也不证明底层写入行为相同。NeoForge Patch 加入显式 ID 注册、重复 key/value 抛错、注册时绑定 Holder value、回调和 alias 解析，并让 `MappedRegistry` 继承 `BaseMappedRegistry`。统一注册服务必须考虑这些结构与可观察语义，不能只截获一个方法名。[MappedRegistry Patch][n-mapped]

## 2. 冻结的实际边界

NeoForge 的 `CommonModLoader.begin`：先 gather / initialize mods，再执行 registry 初始化任务：`postNewRegistryEvent → unfreezeData → postRegisterEvents → freezeData`，之后加载配置。`GameData.postRegisterEvents` 按 root registry 的有序 key 列表逐 registry 发事件，并维护错误与清理逻辑。它不是“所有 mod common setup 后再随便注册”。[CommonModLoader][n-common]、[GameData][n-gamedata]

Forge 的 `GameData` 有 vanilla / active / frozen 等状态及快照；`postRegisterEvents` 对对应 Forge registry 解冻、按 mod 顺序投递事件、再冻结，并处理 ObjectHolder 等更新。Forge 的包装注册表和 NeoForge 扩展的 MappedRegistry 不宜互换实现。[Forge GameData][f-gamedata]

Fabric 的 main / client / server 入口由 `Hooks` 调用；直接注册的常见窗口在初始化阶段。冻结最终由游戏注册表启动调用路径控制，不能由 `fabric.mod.json` 推出时机。本轮检查了 Hooks 和 EntrypointPatch，未执行启动轨迹；NeoForbric 应在自己的 bootstrap 中明确入口前后各 registry 的实际 frozen 状态。[Hooks][fl-hooks]、[EntrypointPatch][fl-entrypatch]

**原生注册窗口必须同时包含物理可写状态、API 门面状态和线程 / 阶段要求。** 只解冻真实 map，不代表 DeferredRegister 已经过了事件后还能接受条目。NeoForge `seenRegisterEvent` 明确拒绝后补注册；强制忽略这项检查会改变源 API 的契约。[DeferredRegister][n-deferred]

## 3. 统一服务如何保证合法窗口

建议建立以下规则，首版不追求任意模组之间全部初始化假设都能同时成立：

1. 完成发现与转换能力检查，建立 mod 顺序；收集自定义静态 registry 与动态 codec 定义。触发构造 / preLaunch 的真实类初始化必须记账，不能假设“扫描阶段不会注册”。
2. vanilla bootstrap 建立一套真实对象和 registry。由内核集中控制冻结，生态门面不能各自解冻另外一份 map。
3. 在 Fabric 对应的直接注册窗口调用其入口；写入立即提交并可按该 API 查询。禁止把 `Registry.register` 改成“现在返回对象、稍后才放进 registry”的透明队列。
4. Forge / NeoForge 构造阶段建立 DeferredRegister 监听；匹配 registry 的 RegisterEvent 才执行 supplier、写入与绑定。逐 registry 顺序应保留已选原生基线的重要约束，例如 block 在 item 之前，不能自行按 mod ID 分批冻结全表。
5. 在全部受支持写入阶段完成后，验证 key/value 唯一、intrusive holder / reference 完整、回调和索引一致，再建立基线快照并冻结。setup 只允许源 API 合法的读取和排队工作。
6. 冻结后未经协议允许的新增写入失败，报告 owner、调用栈、registry、阶段与窗口；ID remap / 存档恢复的内部受控解冻单独授权，不暴露成模组通用入口。

第 3、4 步的跨生态相对顺序是 **NeoForbric 的策略选择**。先 Fabric 后延迟注册可能帮助 Deferred supplier 引用 Fabric 对象，但 Fabric initializer 若立即查尚未执行的 NeoForge supplier 仍会失败；反向顺序会出现相反问题。一个进程不能让互相依赖的“双方先完成”都成立。应在依赖图中区分构造依赖、注册值依赖与 setup 依赖，首批拒绝无法调度的循环，而非返回占位对象伪装成真实 Block / Item。

## 4. Holder、ResourceKey 与跨模组可见性

`ResourceKey` / Fabric `RegistryKey` 表示地址，不承诺条目已存在；`Holder.Reference` / RegistryEntry.Reference 还关联 owner、key 和 value；`Holder.Direct` 没有相同的 registry 身份语义。DeferredHolder 保存 key 后尝试绑定，`get()` / `value()` 的失败与 `isBound()` 必须保持原有行为。[DeferredHolder][n-holder]

不能用一个普通 Supplier 替代完整 Holder：模组会调用 unwrap、is、tags、owner 检查或使用 codec 编解码。统一内核需让各门面引用同一条目与正确 holder owner；命名转换不会转换成另一份对象身份。

在注册窗口中允许“已经写入的目标”的源 API 查询，不保证任意依赖已经注册。NeoForge Patch 即时绑定 value 正说明“冻结前一律不能读”太粗糙；但这也不能放宽为“任意 supplier 什么时候都能 get”。

## 5. 动态内容、冻结和重载

动态 registry 定义（key / codec / network codec）属于启动契约，内容属于世界加载的 RegistryAccess。建议按 `(server/world 或 connection, registry key, generation)` 管理上下文；不要将世界内容塞进一次性的全局 DeferredRegister。

Fabric DynamicRegistries 对自定义内容使用 `data/<entry namespace>/<registry namespace>/<registry path>/...json`，synced 定义可另设网络 codec，空表可显式跳过。NeoForge 数据包定义也区分数据与网络 codec；没有网络 codec 不意味着客户端能依靠静态 ID 取得那些条目。[Fabric DynamicRegistries][fa-dynamic]、[NeoForge DataPackRegistryEvent][n-datapack]

世界初次加载的动态 bootstrap，与 `/reload` 的可重载内容不同。1.21.1 中世界生成等动态 registry 不能统一承诺每次 reload 全量重建；NeoForge 的 ReloadableServerResources / RegistryDataLoader Patch 分别进入不同加载路径。见 [资源专项](15-resources-and-datagen.md)。[动态加载 Patch][n-regload]、[可重载注册 Patch][n-reloadreg]

静态 key→整数 ID 同步、动态 entry codec 同步、tag 同步、NeoForge data map 同步必须分开记录。接收方没有类、内容定义或 codec 时，服务器发 ID 对照表并不能造出这些功能。具体连线边界见 [网络专项](12-network-protocol.md)。

## 6. 待执行探针

| 编号 | 原生对照与统一实例操作 | 必须比较的结果 |
| --- | --- | --- |
| S3-R01 | Fabric 直接注册后同函数查询；NeoForge constructor 建 supplier，事件时执行 | 立即可见性、构造次数、get 的成功 / 失败阶段 |
| S3-R02 | block→item→BlockEntityType 的 supplier 链 | registry 次序、Holder 绑定、对象身份与回调 |
| S3-R03 | 重复 key、重复 value、漏绑定 intrusive holder、冻结后写入 | 异常类型 / 阶段；不得静默覆盖 |
| S3-R04 | 自定义静态 registry，遗漏 NewRegistryEvent 提交 | 构造与提交边界、同步标记、失败归属 |
| S3-R05 | 两个世界使用不同动态 JSON；关闭后再打开 | RegistryAccess 隔离、无旧 Holder 泄漏 |
| S3-R06 | 自定义动态 codec / network codec，空与非空内容 | 路径、同步字段、缺失 codec 的连接结果 |
| S3-R07 | 互相立即读取尚未注册的跨生态条目 | 可解释的依赖环；不以占位实例骗过 |
| S3-R08 | 客户端注册顺序与服务端相反后连接、退出、再连接 | ID remap、tags / holder 引用、基线恢复 |

[f-datapack]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/registries/DataPackRegistryEvent.java
[f-gamedata]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/registries/GameData.java
[fa-dynamic]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/api/event/registry/DynamicRegistries.java
[fa-regsync]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.java
[fl-entrypatch]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/patch/EntrypointPatch.java
[fl-hooks]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java
[n-common]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/internal/CommonModLoader.java
[n-datapack]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DataPackRegistryEvent.java
[n-deferred]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DeferredRegister.java
[n-gamedata]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/GameData.java
[n-holder]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/DeferredHolder.java
[n-mapped]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/core/MappedRegistry.java.patch
[n-regload]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/resources/RegistryDataLoader.java.patch
[n-regmanager]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/registries/RegistryManager.java
[n-reloadreg]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/ReloadableServerResources.java.patch
