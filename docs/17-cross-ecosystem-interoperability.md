# 17 — 跨生态对象互操作与最终验证

调查日期：2026-10-06。目标：Minecraft **1.21.1 / Java 21**。本篇接续 [注册表](07-registries.md)、[事件](10-event-semantics.md)、[对象数据](11-data-lifecycle.md)、[网络](12-network-protocol.md) 与 [资源](15-resources-and-datagen.md)，补查配置、命令权限和 worldgen，并将它们收敛为三端共同操作对象的验收条件。启动与语言加载器见 [18](18-bootstrap-and-classloading.md)。

**结论：同一游戏类定义是必要条件；同一注册内容、同一对象状态和三套 API 的生命周期契约才是互操作成立的条件。** 目前仓库没有加载器实现，本篇没有运行三模组、存档、命令、worldgen 或联网实验。源码事实、设计建议和待执行探针分别标明；本篇不宣称三端已兼容。

## 1. “三者能启动”与“互相操作”之间的缺口

目标链应当是：

```text
Fabric A：注册 probe:shared_item → 创建 ItemStack S
                         ↓ 同一注册表中的 Item
NeoForge B：找到该 Item → 取得 S → 修改 S 的组件 / 数量
                         ↓ 同一 S，不复制成生态包装对象
Forge C：接收 S → getCapability → 读取 / 修改 S 的实际状态
                         ↓
Fabric A：重新观察修改 → 保存 → 退出 → 重载后核对内容与数据
```

跨生态的外层 `RegistryObject`、`DeferredHolder`、provider、事件对象可以不同；它们指向的游戏对象不能成为三个独立世界。不要用 ID 相等代替对象一致，也不要要求所有包装器或所有生命周期的 Holder 都 `==`。JVM 类型身份由二进制类名与定义它的 ClassLoader 共同决定；同名类型由两个加载器定义会产生不同类型。见 [JVMS §5.3](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.3)。

| 层 | 必须检验的身份 / 语义 | 不能用来替代的证据 |
| --- | --- | --- |
| 类 | A/B/C 使用相同的 `Item.class`、`ItemStack.class`、`Holder.class` 定义；共享描述符中的 API 类型同源 | 类名或映射字符串相等 |
| 静态注册内容 | 同一 registry 内查 `probe:shared_item` 得到同一 Item；已绑定 holder 的 `value()` 指向它 | 三个门面各维护一个 Item map |
| 运行对象 | 同一次函数传递的 S 保持引用身份；B 写入后 C/A 读到该 S 的改变 | 复制 S 后仅回填部分字段 |
| 动态内容 | 同一世界 / registry epoch 内的 Reference Holder 属于正确 owner，标签、绑定和引用可用 | 仅凭相同 ResourceKey 跨世界复用 Holder |
| 数据 | provider / attachment / component 的所有者、复制、失效和保存契约成立 | 给两个 API 返回看起来一样的初始值 |
| 网络 / 存档 | 重建对象后内容 key、组件和关联数据正确；客户端按协议重建自己的对象 | 跨进程或保存重载后仍要求 Java 引用 `==` |

## 2. 注册窗口决定互操作何时成立

静态内容必须由一个真实注册表拥有，生态门面保留源 API 的写入和绑定时机。A 的直接 `Registry.register` 成功后，在其合法阶段的直接查询应可见；B/C 的 Deferred supplier 仍在各自注册事件窗口执行，不能发现 JAR 时就提前求值。注册完成后才执行需要三方完整内容的互操作探针。详见 [07 的窗口与 Holder 契约](07-registries.md)。

建议把跨生态依赖分成 **候选模组可见 → 入口可调用 → 指定 registry 已绑定 → setup 工作完成 → 世界动态内容已解析** 五种就绪条件。依赖元数据的 ordering 只能提供部分顺序，不能证明目标 registry 已就绪，也不能为模组凭空插入原 API 不存在的等待点。原 JAR 若在入口同步等待尚未运行的另一生态注册 supplier，可能没有保留双方语义的可行拓扑，应报告阻塞边与阶段，不能后台重试掩盖第一次查询失败。

不支持用“每个生态完成全部初始化后再启动下一个”作为通用安排：Forge/NeoForge 的跨模组注册与事件阶段有自己的屏障，Fabric 静态初始化又可能触发游戏 bootstrap。单一调度器需要按 registry 与阶段协调，并记录 freeze 前后状态；具体可行性由原生轨迹及混装探针决定。

## 3. 最硬的 ABI 冲突：对象父类

固定源码中，Forge 的 `Entity` 将父类改为 `CapabilityProvider<Entity>`，NeoForge 将其改为 `AttachmentHolder`；Forge 的 `ItemStack` 同样继承 `CapabilityProvider<ItemStack>`。这是实际继承结构差异，不能只加 Hook 或接口解决。证据：[Forge Entity Patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/entity/Entity.java.patch)、[NeoForge Entity Patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/Entity.java.patch)、[Forge ItemStack Patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/item/ItemStack.java.patch)。

建议为每个共享对象生成 **要求的父类 / 接口 / 字段 / 方法 / 调用点** 清单。不要把两个原本互不继承的父类直接写成同一类的父类。可选方案及边界如下，尚未实现或选定：

| 方案 | 能覆盖的范围 | 必须证明的限制 |
| --- | --- | --- |
| 保留一侧父类，另一侧状态用字段组合，补方法和接口 | 经过适配的普通 API 查询、存档与生命周期调用 | 另一侧具体父类的 cast、`instanceof`、反射层级、受保护调用与 `invokespecial` 未自动兼容 |
| 改造门面父类，构建一条兼容继承链 | 有机会同时保留某些父类可赋值关系 | 泛型擦除、构造器、final 方法、字段初始化、super 调用和 Mixin 对层级的假设都需逐类验证 |
| 重写已知消费者调用点 / cast | 限定样本可运行 | 不能覆盖未知反射、MethodHandle、插件生成代码；不能宣称任意原 JAR 兼容 |

NeoForge 确实提供 `AttachmentHolder.AsField` 供无法继承时组合状态，但这不会使宿主对象自动成为 `AttachmentHolder` 子类。见 [AttachmentHolder 源码](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/attachment/AttachmentHolder.java)。应将“普通 API 互操作通过”和“依赖具体父类的 ABI 通过”分别报告。

## 4. Capability 与 Attachment 如何围绕同一状态工作

Forge `Capability<T>` / `LazyOptional<T>` 与 NeoForge `ItemCapability<T,C>`、entity/block provider 不是同一种 token 或返回协议。Fabric 模组注册一个普通 Item，并不意味着它自动拥有任意 capability。C 查询 A 的 ItemStack 能否得到结果，取决于 C 或其他 provider 是否为该 Stack 安装能力，以及相应 Forge ABI 与生命周期是否被实现。详见 [11 的固定来源](11-data-lifecycle.md)。

**首个可验证方案不需要自动翻译任意 capability：** A 注册物品；B 为该物品注册 NeoForge ItemCapability provider；C 为该物品的 Stack 安装 Forge provider；两者都操作 S 的同一份 vanilla component 数据。这样可证明不同 API 围绕同一对象工作，同时保留各自 token 和 wrapper。第二阶段再为某一明确协议，例如物品槽位 / 流体 / 能量，声明双向转换；协议名称相同仍不足以证明单位、事务、simulate、方向/context、返回空值和失效契约一致。

建议提供按宿主对象管理的状态协调层：分别记录 capability provider、attachment 与组件的生命周期；只有已声明共享存储的适配器才指向同一份状态。禁止默认将同名 key 的三种数据合并，也禁止把 `LazyOptional` 当普通值缓存到对象失效之后。状态协调层必须覆盖：

1. `ItemStack.copy` 产生新 Stack 后，数据按原契约复制；Item 身份保持，Stack 引用改变；provider 不意外共享可变状态。
2. Entity 死亡克隆 / 跨维度涉及新旧宿主与 provider 失效；Level、Chunk、BlockEntity 的卸载与重新加载分别处理。
3. 每个存档 key 有明确所有者；Forge capability NBT、Neo attachment NBT 和 components 按各自编码保留，避免重复写入或覆盖。
4. 是否同步以固定版本 API 的声明为准；本次固定 Fabric / NeoForge 源码具有附件同步接口，不能统一假定全部需要手写网络。组件、capability 与 attachment 的同步仍分别验收。

## 5. Config：保留加载时机，而非强行统一格式

**源码事实：** Forge 固定快照的 `ConfigTracker` 管理 CLIENT / COMMON / SERVER；`ModStateProvider.CONFIG_LOAD` 在 COMMON_SETUP 前读 CLIENT（物理客户端）与 COMMON，server config 在 `ServerAboutToStart` 前从世界 `serverconfig` 加载，并在停服时卸载。见 [Forge 状态安排](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/fml/core/ModStateProvider.java)、[ConfigTracker](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlcore/src/main/java/net/minecraftforge/fml/config/ConfigTracker.java)、[ServerLifecycleHooks](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/server/ServerLifecycleHooks.java)。

NeoForge 本次取样 FML **4.0.45** 还包含 STARTUP：注册时立即读取。NeoForge 的 COMMON / CLIENT 仍在 common setup 前加载；固定 1.21.1 源码中的 SERVER 采用 **实例 config 作为基路径，已存在的世界 serverconfig 文件覆盖它**，不是一律把文件新建到世界目录。加载发生在 biome modifiers 与 `ServerAboutToStartEvent` 前，停服卸载。这与 Forge 本次快照不同，也说明仅锁 `Minecraft=1.21.1` 不够。见 [NeoForge CommonModLoader](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/internal/CommonModLoader.java)、[ServerLifecycleHooks](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/server/ServerLifecycleHooks.java)、[FML 4.0.45 源码包](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar)。

NeoForge [1.21.1 配置文档](https://docs.neoforged.net/docs/1.21.1/misc/config/) 也描述了 STARTUP、COMMON / CLIENT 的加载窗口与 SERVER 世界覆盖 / 同步。网页及分支会更新，应以所选发布修订及实际依赖锁为运行基线。Fabric Loader 的配置目录是路径服务；Cloth Config / AutoConfig 属于模组库及其调用约定，不构成一个与 FML config lifecycle 等价的统一协议。

| 方面 | NeoForbric 建议契约 |
| --- | --- |
| spec 与值 | 保留 ForgeConfigSpec / ModConfigSpec 和第三方 config 库类型；spec 构建 / 注册不等于值已加载 |
| 文件路径 | 一个实例根目录，生态门面解析各自规则；记录最终路径与世界覆盖来源；同路径冲突显式失败，不能静默加生态后缀改变原 JAR 的路径假设 |
| 世界绑定 | SERVER 状态按当前服务器 / 世界会话绑定；单人退出再进入另一世界必须卸载旧值、watcher、override 和客户端会话副本 |
| reload | 分开 config file watcher、资源 reload 与世界动态内容；读值更新、Loading / Reloading / Unloading 事件及线程遵守源契约 |
| 同步 | 分开 COMMON 的本地文件与 SERVER 的会话值；记录发包阶段和客户端恢复策略，不能把收到的服务端值默认永久覆盖客户端磁盘文件 |
| 修改生效 | `worldRestart` / game restart 等标志及模组缓存行为需保留；收到 watcher 通知不意味着允许重新注册或改写已生成世界 |

FML 4.0.45 `ConfigWatcher` 在 watcher 线程设定保存的 TCCL、持锁并发出 reload；不能把所有配置回调默认调到服务器主线程，改变原生顺序。若模组需要游戏线程工作，使用相应队列并验收。NeoForge `ConfigSync` 生成 SERVER 文件 payload，非本地服务器客户端调用 `acceptSyncedConfig`，该样本更新内存值并发 Reloading 事件。源码显示初始同步入口，**未证明文件每次 reload 都会重新广播**；这需要单独追踪及实测。见 [ConfigSync](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/ConfigSync.java)。

## 6. 命令与权限：适合最早开展的行为探针

Fabric `CommandRegistrationCallback` 将同一 dispatcher、registry access、registration environment 依次传给 callbacks；Forge/NeoForge `RegisterCommandsEvent` 是游戏事件总线事件，带 dispatcher、CommandBuildContext 与 CommandSelection。NeoForge 源码明确命令会在 `ReloadableServerResources` 重建时重建，不能把“注册一次”理解成整个进程只有一次。见 [Fabric callback](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-command-api-v2/src/main/java/net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback.java)、[Forge event](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/event/RegisterCommandsEvent.java)、[NeoForge event](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/event/RegisterCommandsEvent.java)。

建议以内核拥有的一份 Brigadier dispatcher 为共同对象，每轮命令树构建只向每个门面派发一次；保留各自总线 priority 与 callback 顺序，并记录跨生态顺序策略。源 API 内部顺序可以保留；**跨生态不存在原生共同顺序可直接照搬**。同名命令、节点合并、redirect、suggestion provider、权限 predicate 的结果必须实际检查，不能把 listener ordering 当成通用冲突解决器。

权限尤其不能只映射成 `hasPermission(opLevel)`：Forge/NeoForge PermissionAPI 有已注册的泛型 PermissionNode、默认 resolver、在线 / 离线玩家和 dynamic contexts，查询未注册 node 会失败。Fabric 常用的 [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) 是独立第三方 API，不能假称 Fabric Loader 自带。证据：[Forge PermissionAPI](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/server/permission/PermissionAPI.java)、[NeoForge PermissionAPI](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/server/permission/PermissionAPI.java)。

首批只验证各自权限 provider 的原契约：普通玩家、OP、控制台、命令方块、离线查询与 dimension context 分别记录。之后才能为明确的 boolean node 建可选共享权限后端；缺少 provider、类型不匹配和显式 deny 不应变成允许。命令执行、suggestions 和权限检查的实际线程也要记录；异步 provider 不应直接修改服务器对象。

## 7. Worldgen / Codec / Bootstrap：同一对象不等于同一生成模型

世界内容链建议按以下契约检查，不能把所有项都塞进静态 `Registry.register`：

```text
静态 type / Codec / MapCodec serializer 注册
   → 世界数据源与 datapack 选择
   → RegistryOps + lookup / bootstrap context 建立依赖
   → 解码 biome、dimension、configured/placed feature、density function、noise settings
   → 解析 Holder / HolderSet / tags，验证引用闭合及 registry owner
   → 各生态 biome / structure 修改阶段
   → 生成缓存、世界启动、客户端所需内容同步
```

`BootstrapContext` / `RegistrySetBuilder` 的 Java bootstrap 常用于构造 datagen 输出或显式构建的 registry set；**存在一个 bootstrap 方法不代表运行时必然自动调用它或自动把结果注入世界**。现有 JAR 的运行 JSON、codec 注册、加载器扩展与 datagen 入口分别识别。详见 [07](07-registries.md) 与 [15](15-resources-and-datagen.md)。

固定 NeoForge `BiomeModifier` 使用 serializer registry 的 dispatch codec 与 RegistryOps 下的引用 codec；其阶段包括 BEFORE_EVERYTHING / ADD / REMOVE / MODIFY / AFTER_EVERYTHING。Fabric 的 `BiomeModificationImpl` 按自己的 phase/order/id 排序，在服务器构造对应的动态 registry manager 上 finalize，一份 manager 重复应用会触发其 marker 检查，并处理 generation settings 与 feature 索引缓存。见 [NeoForge BiomeModifier](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/common/world/BiomeModifier.java)、[Fabric 实现](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-biome-api-v1/src/main/java/net/fabricmc/fabric/impl/biome/modification/BiomeModificationImpl.java)。

建议先建共同世界 registry epoch，再明确每个源 modifier 在何时看原始值、前序修改值或 builder；合并阶段枚举之前先比较 selector 读视图、tag 解析时机和最终生成器读取的字段 / 缓存。仅让两个事件都运行一次仍可能使一个生态读原字段、另一个读扩展 getter，产生两个生成结果。

必须覆盖缺少 serializer、跨 registry 引用、无效 Holder owner、错误维度 stem、自定义 density function codec，以及 feature ordering cycle。NeoForge [biome modifier 文档](https://docs.neoforged.net/docs/1.21.1/worldgen/biomemodifier/) 明确提示 feature 顺序可能形成 cycle；“feature 在 registry 里存在”不能证明 worldgen 可执行。`/reload`、重建世界 registry、重进世界、新生成区块与旧区块分别验收，不承诺 reload 后重新生成已存在区块。

## 8. 三模组夹具：使用原生态 JAR，不让 Common API 代替互操作

建议建立 A / B / C 三个独立构建、固定哈希的原生态测试模组。它们通过内容 key 与源生态 API 协作；不强制改写成只调用 NeoForbric Common API。原生 Fabric、Forge、NeoForge 环境分别提供各自 API 的对照轨迹；三端原生环境无法给出同进程混装基线，混装新增顺序 / 共享规则必须单列为 NeoForbric 的设计契约。

按以下顺序扩大夹具，避免一次引入所有复杂能力：

1. A 注册 Item；B 在合法就绪阶段查到它；C 的命令取得该 Item 创建的 S；A/B/C 对比 Item / 类型身份。
2. 一次命令执行把 S 依次交给 B/C/A：改数量、读取 vanilla custom-data component，再改回；证明引用与写入可见。
3. B/C 注册各自 provider，围绕同一 S 的计数数据读写；单列空 provider、copy、失效与重新查询。
4. 加入 config 值决定命令行为，再加入世界 codec、biome modifier 和存档重载；网络同步使用 [12](12-network-protocol.md) 的条件，不外推客户端兼容。

以下均为 **待执行**。每项记录输入 JAR / 基底 / 映射 / 规则哈希、侧别、线程、阶段、对象观测编号、registry owner / epoch 与失败原因；对象编号使用 audit 分配 ID，不能仅依赖可能碰撞的 `identityHashCode`。

| ID | 输入 / 操作 | 通过条件与失败信号 |
| --- | --- | --- |
| I-01 | A 直接注册；B/C 在各自合法就绪点查 `probe:shared_item` | 同一 Item 和已绑定 value；提前求值或三份 registry 是失败 |
| I-02 | 三方收同一个 S；B 改数量、C 改 custom data、A 读取 | 同一过程引用身份及读写可见；门面拷贝 / 丢字段失败 |
| I-03 | B Neo provider 与 C Forge provider 读写该计数；另查未注册能力 | 两套 token / 返回协议正确，底层 S 状态一致；默认凭空生成能力失败 |
| I-04 | S.copy、替换 Stack；对新旧 provider 重查 | Item 相同、Stack 不同；复制规则成立，无非预期可变状态共享 |
| I-05 | Entity 死亡克隆、跨维度；卸载 BlockEntity / Chunk | 按各生态契约复制 / 失效 / 重建；保留失效 LazyOptional 或旧 owner 失败 |
| I-06 | 新建世界、保存、退出、重载；另一世界同 key | key / 编码 / 数据恢复正确；不要求跨会话引用相同；旧 Holder 泄漏失败 |
| I-07 | CLIENT / COMMON / STARTUP / SERVER 读值；不同世界配置及 watcher reload | 值加载窗口、有效路径、事件、线程符合各固定基线；源 API 不支持 STARTUP 不伪造支持 |
| I-08 | 远程连接、SERVER config 初始同步、修改文件、断开再连 | 会话值与磁盘值分开；逐步确认是否广播 reload 及断开恢复，不将未验证行为算通过 |
| I-09 | 三方注册不同命令；`/reload` 重建；专用 / 集成服务端 | 同一 dispatcher 构建轮次内各派发一次；侧别选择正确，无旧 context 缓存 |
| I-10 | 同名节点、redirect、suggestions；普通玩家 / OP / 控制台 / 权限 deny | 记录实际合并与顺序；已声明权限语义成立，未知 / deny 不放行 |
| I-11 | 一个世界 datapack 引用 A type、B feature、C modifier；含自定义 density codec | 同 epoch 的 Holder 引用闭合，最终 settings / 新区块符合声明；serializer 缺失显式失败 |
| I-12 | 两 modifier 构造 feature cycle；错误 codec / Holder；reload 与新旧区块对比 | cycle / 解码失败可归因且阻止不完整世界启动；旧区块不被默默重写 |
| I-13 | cast 到两个生态具体父类；反射、super / MethodHandle、结构 Mixin | 单独核对支持清单；组合状态方案不能把这些检查标为自动通过 |
| I-14 | 构造跨生态注册窗口等待环；config 同路径冲突 | 启动前给出依赖 / 路径证据并失败；不靠延迟重试或私改文件名蒙混 |

## 9. 调查结束后的最小交付

下一步建议进入原型，不再新增一轮通用领域调查。先通过 [18 的启动与类身份门槛](18-bootstrap-and-classloading.md)，再做 **I-01 / I-02 / I-09 / I-10** 的 Java 服务端夹具；接着做 I-03 / I-04 / I-07，再进入数据持久化、worldgen 与协议。每次遇到失败只补该调用点的规则和证据。

三方全部各自打印“初始化完成”不是验收。至少要交付：相同类型定义、同 Item 与同 S 的观测轨迹、共同命令树及权限结果、明确的 provider 边界，以及失败时不留下部分注册 / 部分变换状态的证据。完整互操作仍需 I-05 / I-06 / I-08 / I-11 等后续场景，不能用首批通过率外推全整合包。

本轮新增来源的固定提交、源码包哈希和探针状态见 [最终验证调查快照](bootstrap-interoperability-snapshot.json)；旧领域的精确版本缺口保留在 [第三阶段来源索引](stage3-sources.md)。
