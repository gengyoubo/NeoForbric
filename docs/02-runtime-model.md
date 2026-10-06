# 生命周期、类加载与映射

目标：Minecraft 1.21.1。以下统一阶段名称与接口均为设计草案，尚未实现。

映射文件的真实对应关系、refmap 转换、Mixin 结构修复与最小实验见 [映射与 Mixin 兼容专项](05-mapping-and-mixin.md)。它们是统一内核的前置能力。

## 1. 生命周期不是同一组事件的不同名字

Fabric Loader 负责入口等基础加载功能，游戏领域的事件主要由 Fabric API 提供，见 [Fabric Loader 官方说明](https://docs.fabricmc.net/develop/loader/)。1.21.1 模组不能仅靠 Loader 获得完整的 Registry、资源和网络 API。

固定 Loader 0.16.10 的 [Hooks.java](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java) 显示：客户端依次调用 `main`、`client`；专用服务器依次调用 `main`、`server`。这是一份入口行为样本，**不是生产版本选择**。

Forge / NeoForge 则区分模组事件总线和游戏事件总线，并拥有构造、注册、common setup、物理侧 setup 等阶段。部分 setup 并行派发，`enqueueWork` 用于推迟主线程工作。见 [Forge 生命周期](https://docs.minecraftforge.net/en/1.21.x/concepts/lifecycle/) 与 [NeoForge 1.21.1 事件](https://docs.neoforged.net/docs/1.21.1/concepts/events/)。

| 领域 | Fabric 1.21.1 | Forge / NeoForge 1.21.1 | 统一时必须处理 |
| --- | --- | --- | --- |
| 模组启动 | `main` 入口可执行注册并安装监听器 | 构造阶段安装监听器，注册先于 common setup | 不能把 Fabric `main` 直接放到注册结束后的 common setup |
| 客户端初始化 | `client` 入口；运行就绪另有 API 事件 | `FMLClientSetupEvent`；另有专项客户端注册事件 | 配置入口与游戏就绪分开 |
| 专用服务器初始化 | `server` 入口 | `FMLDedicatedServerSetupEvent` | 仅物理专用服务器；不等于每次开启世界 |
| 注册 | 原版 Registry 配合 Fabric 各模块机制 | `DeferredRegister` / `RegisterEvent` 等 | 各注册表的合法写入窗口、对象解析与冻结 |
| 服务器 / 世界运行 | Fabric API 生命周期和 Tick 回调 | 游戏总线服务器、Level、Tick 事件 | 触发位置、一次或多次、逻辑侧、服务器实例 |
| 资源重载 | Resource Loader 与数据包生命周期回调 | 服务端和客户端分别注册 reload listener | prepare / apply、依赖顺序、失败处理 |
| 网络 | 按阶段与方向注册 payload 类型及接收器 | Forge channel；NeoForge payload registrar | 阶段、方向、codec、线程、握手与可选支持 |

“客户端进单人世界”同时具有物理客户端和逻辑服务器；不能以客户端环境为由跳过服务器逻辑。见 [NeoForge 侧别说明](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)。

## 2. 将进程启动与运行事件分开

建议的进程启动状态：

```text
DISCOVER → RESOLVE → PREPARE_TRANSFORMS → CONSTRUCT
         → REGISTRATION → COMMON_SETUP → PHYSICAL_SIDE_SETUP → LOAD_COMPLETE
```

它表示内核必须拥有的阶段屏障，不表示三端入口一一对应。`REGISTRATION` 内还需区分新注册表、数据包注册表声明、各静态注册表写入等子步骤。Fabric 入口的位置必须以原生调用点与注册状态为依据，可能落在构造之后、注册完成之前；不能仅凭名字移动入口。

另外独立建模：

| 运行领域 | 草案中的周期 | 所属范围 |
| --- | --- | --- |
| 服务器 | STARTING / STARTED / STOPPING / STOPPED | 每个服务器实例，单人世界也包括在内 |
| 世界 | LOAD / UNLOAD，TICK_BEGIN / TICK_END | 每个维度实例，不仅主世界 |
| 客户端 | STARTED / STOPPING 与每帧 / 每 Tick 事件 | 物理客户端 |
| 重载 | PREPARE / APPLY / COMPLETE 或 FAILED | 每次重载，携带资源种类与代次 |
| 网络 | CONFIGURATION / PLAY | 每个连接与消息类型 |

Fabric [ServerLifecycleEvents](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-lifecycle-events-v1/src/main/java/net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java) 与 [ClientLifecycleEvents](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-lifecycle-events-v1/src/client/java/net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents.java) 为上述区分提供了源码依据。

建议每个统一事件至少明确：触发阶段、线程、物理 / 逻辑侧、对象有效期、是否可取消、结果合并方式，以及异常是否中止阶段。新 API 的通知回调可先串行；适配原生态并行事件时，必须另行验证其排队任务与阶段完成条件，不能宣称串行执行等同原实现。

## 3. 注册模型

[NeoForge 注册文档](https://docs.neoforged.net/docs/1.21.1/concepts/registries/) 和 [Forge 注册文档](https://docs.minecraftforge.net/en/1.21.x/concepts/registries/) 都说明延迟注册与注册事件的关系。建议统一 API 使用“声明 → 合法窗口执行 supplier → 解析 handle → 冻结”的模式。

具体规则建议：

- 一条注册声明具有所属模组、registry key、entry id 和对象 supplier。
- 提前解析尚未绑定的 handle 时，报告模组与阶段；不返回假对象。
- 同一注册表的重复 id 和冻结后写入都作为明确错误。
- 区分静态注册表与随数据包 / 世界上下文加载的动态注册表；不能让全部注册表共享一次永久冻结。
- 原生态模组可能直接调用原版注册方法，兼容层必须在真实调用路径中维护注册窗口；公共 API 的延迟队列不能自动捕获所有这些调用。
- 客户端与服务器的注册标识、网络所用条目映射必须验证一致；进程内注册成功不代表联机成功。

## 4. 事件结果不能压成一个 boolean

Fabric [UseBlockCallback](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/api/event/player/UseBlockCallback.java) 以 `ActionResult` 控制继续处理，并且不同返回值会影响客户端是否发包。

Forge [RightClickBlock](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/event/entity/player/PlayerInteractEvent.java) 分别控制 `useBlock` / `useItem`，使用 `Result`；[NeoForge 对应事件](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java) 使用 `TriState`。这里不存在完整的一对一 boolean 映射。

建议按领域定义结果对象，例如分别保留“是否继续后续监听器”“方块使用决策”“物品使用决策”“交互结果”“客户端发包决策”。兼容层逐项填写，必要时标记无法表达的组合；还要保留原调用点、优先级和接收已取消事件的规则。

## 5. 网络与资源重载

Fabric [PayloadTypeRegistry](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java) 区分 configuration / play 和 C2S / S2C；[ServerPlayNetworking](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java) 的对象式处理器运行在服务器线程。不能将“Fabric 接收器都在网络线程”作为统一方案的前提。

NeoForge [RegisterPayloadHandlersEvent](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent.java) 也默认包装到主线程，允许显式改变处理线程，见 [1.21.1 payload 文档](https://docs.neoforged.net/docs/1.21.1/networking/payload/)。Forge 应以目标源码的 [ChannelBuilder](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/ChannelBuilder.java) 和 [SimpleChannel](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/SimpleChannel.java) 为准；官网 SimpleImpl 页的部分示例与本次 1.21.1 源码不同，不能直接复制。

统一消息声明建议包含：id、协议版本、阶段、方向、codec、接收线程、对端是否必须支持。统一 API 可以定义新协议；现有生态连接若要求原握手，仍需要适配其注册同步和协商流程。三端都能读写一个 codec，并不能证明三端原生客户端可互相连接。

Fabric [ResourceManagerHelper](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/api/resource/ResourceManagerHelper.java) 提供 listener 注册；NeoForge [AddReloadListenerEvent](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/event/AddReloadListenerEvent.java) 在服务端重载时收集 listener。统一服务需要明确注册一次与每次重载实例创建的区别，并保留 prepare / apply 屏障。建议新 API 使用临时结果并在完整成功后提交；这项事务性保证不能未经验证就推广到任意第三方 listener 的副作用。

## 6. 统一命名与实际 ABI

[Fabric 官方映射说明](https://wiki.fabricmc.net/tutorial:mappings) 区分开发命名与编译产物命名；[迁移说明](https://wiki.fabricmc.net/tutorial:migratemappings) 展示了使用 Mojang mappings 的方式。Forge 的 [gradle.properties](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/gradle.properties) 同样选择 `official` 开发映射，但 [FMLLoader](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/FMLLoader.java) 中仍有 `srg` 命名转换处理。

| 来源 | 1.21.1 调查依据 | 对 NeoForbric 的含义 |
| --- | --- | --- |
| Fabric | 通常发布为 intermediary；开发可用 Yarn 或 Mojang names | 同时处理方法描述符、Mixin 目标 / refmap、访问规则；开发使用 Mojmap 也不意味着发行 JAR 无需 remap |
| Forge | 官方开发映射与 SRG 运行路径并存 | 按具体产物的命名空间导入，不能只看源码名字 |
| NeoForge | 目标源码与 AT 使用 Mojang 方法名；Connector 将目标命名空间设为 `mojang` | 可以作为统一命名方向，但其扩展接口与方法还需单独实现 |

建议统一开发及内核游戏命名为 Mojang names，并显式维护 `原版混淆名 ↔ intermediary ↔ SRG ↔ 统一命名` 的版本化关系。这里的 `official` 在不同工具中可能指原始混淆空间或 Mojang 映射选择，缓存和配置应使用明确的空间标识。

**共享原版输入、共享命名、共享最终 ABI 是三件事。** Registry 名称或类名转换不会创建缺失的父类、方法或字段；Mixin 的 ordinal、局部变量和被调用位置也可能已经被 Patch 改变。

## 7. 类加载和转换所有权

Fabric [Knot](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/Knot.java) 在入口之前配置访问规则和 Mixin；[KnotClassDelegate](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java) 参与类字节转换。Forge 则通过 FML / ModLauncher 的扫描和转换体系接入。各自初始化同一批游戏类会造成所有权冲突。

建议划分引导侧和游戏侧：前者不依赖 Minecraft 类型，负责发现、依赖、缓存与转换配置；后者在唯一游戏类加载器中定义 Minecraft、模组及引用游戏类型的适配代码。统一 API 的类型也需要明确唯一的类加载归属，防止相同类名来自不同加载器导致转换失败。

转换流程必须覆盖映射、结构变更、AT / AW、Mixin、必要 coremod，并保证目标类在配置完成之前没有被定义。各转换的具体排序需要以样本验证，不能直接假定一条排序能兼容全部 coremod。建议为最终字节码保存来源、转换链和失败目标，并以输入 JAR 哈希、游戏版本、映射及转换器版本共同决定缓存键。
