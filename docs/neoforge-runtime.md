# NeoForge 客户端首个实测

开发客户端已接入固定 **Minecraft 1.21.1 / NeoForge 21.1.248 / FML 4.0.43**。首个验证目标为原始 `ecologicalgarden-1.3.2.jar`，SHA-256 为 `256d62e641ab118686381ef9dbfc7f87b205db53932f53f08ed866d3982dcc54`。

## 运行

将 NeoForge 模组放入 `run/client/mods` 后运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21
./gradlew.bat runClient
```

`runClient` 检查模组描述文件，存在选中 NeoForge 描述的 JAR 时自动选择 NeoForge profile。进入该 profile 后，多平台 JAR 优先使用 `META-INF/neoforge.mods.toml`；Fabric profile 保留原有描述文件优先级。可用 `-PneoForgeProfile=true` 强制选择，或 `-PneoForgeProfile=false` 回到原来的 Fabric / vanilla 客户端路径。当前自动选择只作用于开发任务；发布安装器尚未接入 NeoForge 输入准备和桥接模块。

首次准备从[固定的官方安装器](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.248/neoforge-21.1.248-installer.jar)读取 profile，验证 SHA-256，下载其中带 SHA-1 的库，执行固定版本的官方客户端处理工具。补丁输出与未修改的 Mojang 命名类、额外资源合并，避免把增量补丁误当作完整游戏。缓存及校验计划保存在 `build/neoforge-client`，后续启动复核文件哈希。

## 加载机制

NeoForbric 继续拥有 JVM 入口、发现、准入、依赖解析、类索引、转换屏障及游戏类加载器 G。原生 ModLauncher 不启动、不创建第二份 Minecraft 定义。NeoForge 依赖、排序和 `config/fml.toml` 中的显式覆盖翻译成 canonical graph；上游 FML 接收其结果，负责被动元数据 facade、注解扫描、语言提供器、ModContainer 构造与生命周期事件。

发现器递归读取 JarJar 声明，并选取满足全部版本范围的内嵌依赖。同一 modId 的不同 artifact 名称也必须满足共同范围。Crash Assistant 和 Sodium 的包装包通过已知发现器布局提取运行包，原包装包作为库保留；不执行它们的原生发现服务。签名先按原始 JAR 校验，再为 Java 21 选择 multi-release 内容；原始哈希和嵌套来源写入审计，外部 manifest Class-Path 不扩展输入。

JarJar 中包含 Fabric 描述文件的模组按模组候选保留，进入准入、重映射和入口流程，避免仅提供类却丢失 main / client 初始化。显式声明 `FMLModType=LIBRARY/GAMELIBRARY/LANGPROVIDER` 的 JAR 仍作为库；顶层已提供的同 ID 模组不会重复初始化。

Minecraft、NeoForge 和用户模组的命名 module 绑定到同一个 G。module 类查询和资源查询仍读取已准入的不可变快照，原始 JAR 路径、哈希与来源生态保留在 Mods 列表和审计中。NeoForge 的 Access Transformer 通过固定上游 AT 引擎纳入定义前转换；两个内建 accessor Mixin 的行为以明确字段和方法锚点实现。构造或游戏启动失败会终止实例，模组只在主菜单就绪后标为 Loaded。

主菜单只保留 NeoForbric 的 Mods 按钮，移除 NeoForge 额外添加的按钮和布局行。统一 UI 按实际按钮排列主菜单，按钮高 20、行距 28，底部控制按钮另留间距，避免 NeoForge 的起始位置差异导致按钮重叠。

公共初始化前通过 `ConfigTracker.loadDefaultServerConfigs()` 初始化内存中的 SERVER 默认配置，供装备属性扫描等启动期访问使用，避免 MineColonies 扫描 JustDireThings / AllTheArcanistGear 装备时出现 `Cannot get config value before config is loaded`。CLIENT / COMMON 仍从配置目录加载；进入世界或连接服务器时，由原生配置加载和同步替换 SERVER 默认值。启动期不读取存档的 `serverconfig`，也不创建 SERVER 配置文件。

配置回归探针验证公共初始化及其延迟任务可以读取默认值、Loading 事件只触发一次，以及服务器同步能替换默认值；分别覆盖 vanilla 注册表契约和 NeoForge 补丁客户端路径：

```powershell
./gradlew.bat runNeoForgeRegistryProbe
./gradlew.bat runNeoForgeRegistryProbe -PneoForgeRegistryContract=false
```

## EcologicalGarden 验证

实际启动完成了模组构造、内容注册、资源加载和主菜单；注册表中存在 **141 个物品、7 个方块、36 种实体类型**。Item 与客户端共享 G 的类身份。世界探针创建独立存档，验证玩家登录、3 个模组生物从集成服务端同步到客户端、60 tick 运行以及正常保存退出。登录后也出现了模组的“选择精灵”界面。

主菜单自动退出验证：

```powershell
./gradlew.bat runClient -PclientProbeFrames=5 -PclientDebug=true
```

世界探针（需要 EcologicalGarden，不传 `clientProbeFrames`）：

```powershell
./gradlew.bat runClient -PneoForgeProfile=true -PneoForgeWorldProbe=true -PclientModsDir=C:/Users/gengy/Desktop/NeoForbric/run/client/mods -PclientDebug=true
```

世界探针只在 `build/neoforge-world/run` 创建随机名称的新存档。结果截图为该目录下的 `neoforge-world-probe.png`，审计为 `audit.json`。日志成功标记分别为 `NEOFORGE_HOST_READY`、`NEOFORGE_CONSTRUCTED`、`NEOFORGE_PROBE_OK` 和 `NEOFORGE_WORLD_PROBE_OK`。

## 当前边界

这是固定版本的客户端适配，完整整合包正在验证。模组 Mixin、脚本 coremod 和枚举扩展已接入内核转换管线；javafml、lowcodefml 以及已发现的 Kotlin / Scala 语言提供器在 G 中工作。Java ICoreMod 服务尚未接入，仍在执行前拒绝。自定义 ModLauncher transformation service 不会接管启动；这些服务提供的额外行为需要逐项适配和验证。不能据此认定全部 NeoForge 行为已等价实现。

此 profile 默认同时启用被动 Fabric runtime。Fabric main 在 NeoForge 模组构造完成、`GameData.unfreezeData()` 之后、`postRegisterEvents()` 之前执行；早期客户端构造器回调暂存到这个窗口，再执行 Fabric client，允许客户端模组在原生冻结前注册内容。Mixin 配置在推进阶段前汇合到单一转换器，AW 和环境转换仍作用于补丁游戏；native AT、枚举及核心转换保持原有顺序。`-PfabricPlainProfile` 保留有限 Java 入口探针，其 client 入口仍在原生冻结后执行。vanilla 注册表契约探针保留原有窗口。两种来源的顶层 JAR 都在审计中记录最终选择的 `descriptor`、`ecosystem` 和原始哈希。

`CommonModLoader.begin` 在启动线程捕获当前 `GameHooks.Session`，随后原生注册 worker 和资源 reload worker 显式使用该会话执行两个回调；不能在 worker 上直接读取启动线程的 `ThreadLocal`。会话串行检查注册阶段、保留入口异常并拒绝关闭后的回调。无界面回归测试覆盖两条 worker 路径及配置 / 注册顺序，不需要启动客户端。

仅有 `Model loader ... not found` 不能证明注册失败：例如 Porting Lib 和 Moonlight 使用不同的 geometry 注册表，需要结合最终模型及对应入口审计确认。空 custom registry 也需要核对是否有消费该 API 的模组注册内容；不能由库存在推断其注册表必然非空。NeoForge 独立服务端尚未接入；Forge 使用单独 profile，三生态混装未验证。

## Fabric / NeoForge 混合实测

2026-10-07 实测组合：Continuity 3.0.0+1.21、Fabric API 0.116.17+1.21.1、Carpet 1.4.147、ViaFabricPlus 3.4.9，以及 NeoForge 版 Sodium 0.8.13、Sophisticated Backpacks 3.26.9、Sophisticated Core 1.5.7。正常完成资源重载、主菜单、集成世界启动、三只实体生成、原生背包同步、60 个客户端 Tick 和世界保存退出。截图位于 `build/mixed-fabric/world-run/neoforge-world-probe.png`。

混合 profile 检测到 Fabric API 依赖时自动缓存固定 [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI) `0.116.7+2.2.4+1.21.1`，SHA-256 为 `a9ed758355cdbcc6ee1e71c8c512f0be3e4a407079a21a4b1283c239acd9bc5f`。原生模块通过其明确声明的 `provides` 满足 Fabric 模块依赖，真实模块版本继续参加约束检查；原始 Fabric API 总包保留声明版本。已有原生 `fabric_api` 总包时使用用户提供的版本，不再下载固定端口。缓存位于模组目录旁的 `.neoforbric/neoforge-nested/fabric-api`，不改写用户模组 JAR。其附带的原生加载器注入服务不接管启动，Facade 由内核统一提供。

被原生模块替代的原始 Fabric API 签名仍进入重映射的分析类路径，使消费者的继承方法正确转换；这些分析快照不定义游戏类。编译器改动引起的 lambda 编号只在原映射和补丁游戏中均有唯一同名语义前缀、同描述符候选时协调，歧义和缺失继续报错。

针对这个组合适配了补丁移动的方法、额外参数和旧版交互注入，包括 Carpet 更新控制、破坏取消及实体事件，Continuity 掉落方块渲染和 ViaFabricPlus 初始化 / 重生 / 铲子行为。旧 Fabric 标签移除 API 通过接口桥接使用 NeoForge 原生标签移除，探针实际验证 codec、API 返回和最终构建的标签值。ViaFabricPlus 的配置保存回调在 G 关闭前完成；字体尚未构造时只省去不存在的缓存清理，设置仍完整读取。必要注入继续检查，其他补丁差异仍会终止启动。

重跑上述模组组合的世界探针（测试世界必须使用独立目录，不传 `clientProbeFrames`）：

```powershell
./gradlew.bat runClient -PclientRunDir=build/mixed-fabric/world-run -PclientModsDir=run/client/mods -PmixedFabricWorldProbe=true -PclientHeap=4g -PclientDebug=true
```

成功标记为 `MIXED_FABRIC_TAG_REMOVAL_OK`、`NEOFORGE_WORLD_PROBE_OK` 和 `MIXED_FABRIC_WORLD_PROBE_OK`。其他版本、外部服务器协议互通及带 CTM / 自发光材质包的视觉效果尚未专项验证。
