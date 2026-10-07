# Forge 固定版本模组运行适配

已把独立的 Forge runtime 接入 `runClient` profile：被动发现 Forge 描述符和 JarJar，使用同一个 G 构造原生 FML metadata / container，执行 Forge 自己的加载状态机。无界面验证分别使用内部注册 / 配置 / Mixin 模组，以及指定的 `jei-1.21.1-forge-19.57.0.451.jar` 和其内嵌 `mezz_config`。实际客户端已完成 JEI / mezz_config 构造、资源加载、窗口和主菜单，渲染 120 帧后正常退出。JEI 的世界内配方界面和进入世界仍未验证；没有启动原生 ModLauncher。

## 输入与锚点

| 输入 | 固定版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Forge | 52.1.0 |
| FML | 1.21.1-52.1.0 |
| ModLauncher | 10.2.4 |
| Mixin | 0.8.7 |

Forge 版本取自[官方 1.21.1 推荐版本](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html)。安装器 SHA-256、39 个规范化输入的完整 inventory / SHA-256、SRG 命名输入哈希固定在 `forge-1.21.1.lock.json`。输入准备只运行安装器声明且版本在白名单中的 installertools / FART / binarypatcher，逐项验证 processor 输出；不会执行安装器的启动脚本。

`forge-1.21.1.anchors.json` 固定 50 个真实上游类的 SHA-256 和完整方法 descriptor，包括 Launcher、FML、状态提供器、注册表、config、network、AT、coremod、原生 launch plugins、窗口交接、命名与版本兼容矩阵。完整类哈希同时锚定指令调用顺序。打开 G 前核验全部锚点，偏离直接 `FORGE_ANCHOR`，不尝试相邻版本。报告保存未变换的 native 方法调用序列及状态定义，失败报告保留 cause 链。

Forge 输入工具库由安装器确定；共享 ASM 仍由 kernel 提供 9.10.1。此次只证明固定 Forge 工具在这套共享 ASM 上的以下契约，未宣称任意模组的 ASM ABI 都兼容。

## 独立实现与转换

kernel 侧 `org.neoforbric.forge.ForgeRuntime` 管理输入、转换顺序和反射边界；`forge-runtime` 模块中的 `ForgeBridge` 只包含 Forge 原生 API 实现，始终由 G 定义。没有把 Forge 分支放入 NeoForge runtime。

模组 profile 的已注册顺序为：

```text
NF 客户端生命周期 / 主菜单结构补丁（客户端 profile）
→ forge-native-host
→ forge-lifecycle（被动服务与成功状态观察）
→ forge-native-plugins（Dist cleaner / enum / ObjectHolder / CapabilityToken / eventbus）
→ forge-access-transformers
→ forge-coremods
→ forge-mixin
```

使用 Forge 自己的 AT 引擎和 JS CoreModProvider。固定 universal 中两个 JS coremod 生成六个 transformer，五个 field-to-method 目标以及 Zombie 的 finalizeSpawn 方法重定向都实际执行并检查输出变化。AT 与 coremod 是不同阶段，没有用一个不透明的“transform”替代它们。此顺序是 NF 当前基线契约；完整原生启动的顺序对照尚未运行。

模组声明的 `META-INF/coremods.json` 也交给固定 CoreModProvider，脚本和附加数据从对应的已准入 JAR 读取。准入校验声明类型、路径与脚本存在性，原生初始化错误立即失败；当前支持 CLASS / PRE_CLASS 目标，其他目标类型仍明确拒绝。内部模组验证 JS 把 worker 加载的目标返回值从 1 改为 7；完整包使用 NekosEnchantedBooks 的两份脚本，未修改原始 JAR。

原生 launch plugins 按其 `handlesClass` 返回的阶段和请求 reason 分发。Mixin 元数据读取保留 `mixin` reason，真正定义使用 `classloading`；固定 Forge EventBus 只在后者处理类，避免读取 Mixin 父类时提前定义 Screen。原生工具类提前初始化并排除自身的 game / Mixin 转换。原生 plugin 请求重算 frames 时，通过字节码层级求公共父类，不靠定义目标类。契约检查 CapabilityToken 类型与身份、ObjectHolder 去 final、动态 enum 创建与 values / valueOf，以及元数据读取没有定义 Screen。

命名查询按固定 MCPNamingService 的 `getOrDefault` 行为保留未知名字，旧 AT 规则因此能按原生语义找不到匹配成员；实际字节码重映射仍拒绝未知 SRG 引用。multi-release 输入在发现与派生 JAR 中均保留已选定的 Java 21 视图。MixinExtras 的 Forge 平台入口由 G 定义，其核心引擎仍使用 P 上单一的共享实现。

Launcher facade 填充真实 Environment / blackboard，提供 SRG→Mojang 成员映射查询。已适配的 launch plugin 查询返回本次真正注册的原生 plugin 对象。被动 CommonLaunchHandler 提供实际 Dist、非 data、production、Mojang 命名与 Minecraft 输入路径，FML 和 Launcher 查询返回同一个对象，避免 Block 初始化访问空 handler。没有注册的 plugin、handler 与 module layer 查询返回空 Optional。handler 的启动入口、`Launcher.main/run`、原生 classloader 构造和 FML 原生扫描 / 启动入口明确拒绝 `FORGE_LAUNCH_OWNERSHIP`。没有启动第二个 G 或自动发现 transformation services。

`Launcher.launchPlugins` 反射读取返回被动 handler，其 `plugins` 是实时、只读的实际 adapter 状态；没有把这个字段留空。动态插入插件明确抛出 `FORGE_PLUGIN_REGISTRATION`，原生 handler 的独立转换 / 启动入口也被拒绝。其他私有 ModLauncher 接管字段没有完整 native 实现，不宣称任意反射使用已适配。

准入 `javafml` / `lowcodefml` 模组，加载其 AT 与 manifest `MixinConfigs`，通过 NF 的 Mixin service 执行 Mixin / MixinExtras，转换 SRG 成员和 refmap 为 Mojang 名称。EventBus 动态生成的 dispatcher 也由 G 定义并记录所属输入。Java coremod、custom transformation service、其他 language / state provider 和外部 launch plugin 在构造前拒绝 `FORGE_FEATURE_UNSUPPORTED`；MixinSquared 尚未验证。Forge profile 当前只接入 Forge 模组，不支持三端混装。

客户端结构 hook 必须读取原始固定输入。实际失败 audit 显示 native plugin 已改写 `Main` 的哈希，而客户端生命周期 hook 随后仍按原始哈希校验；现通过显式排序依赖让结构 hook 在 native plugin 之前执行。Forge `Minecraft` 的退出调用锚点是五处，不能照搬 NeoForge 的两处。回归测试只变换 `Main` / `Minecraft` 字节码，不执行入口，并保留对错误输入的拒绝。

NF 提前暴露 `ModList` metadata 供 Mixin plugin 查询，因此 Minecraft 的 crash-report preload 可能早于原生 container 构造。准备阶段使用 Forge 自己的 `setLoadedMods(emptyList)` 初始化空索引；查询 container 返回 empty，报告状态为 `NONE`，没有提前构造模组。之后原生 gather 创建并索引真实 containers。内部模组和 JEI probe 均验证构造前的查询和 crash report，以及构造后的真实 G 对象。

客户端的 ImmediateWindowProvider 直接委托固定 Forge 的 NoVizFallback 完成普通 Minecraft 窗口交接、定位与 loading overlay。原生 dummy provider 按原生模块名反射绑定窗口方法，NF 的模块图不采用该模块名，因此不能照搬这条绑定路径。窗口仍由 Minecraft 创建，没有另起原生 launcher。

G 的模块描述符保留每个 SecureJar 声明的 SPI providers，使具名模块中的 ServiceLoader 能找到 JEI 的平台实现。内部模组在原生构造 worker 上查询 SPI，并检查实现属于 G；实际客户端验证 JEI 与 mezz_config 构造完成。

## Forge 依赖兼容矩阵

依赖检查先匹配实际版本，失败后复刻固定 Forge 52.1.0 的 `VersionSupportMatrix`。Minecraft 1.21.1 下，矩阵包含 `mod.minecraft=1.21`、`mod.forge=51.0.33`、`languageloader.javafml=51`。JEI 声明的 `[1.21,1.21.1)` 因此被接受；实际 Minecraft 版本仍为 1.21.1，JAR 和 metadata 不改写，也不移除依赖约束。audit 的 `DEPENDENCY_VERSION_COMPAT` 记录 owner、范围、实际版本、兼容版本及原生依据。不相关 ID、类型、Minecraft 版本和矩阵外范围不会获得放行。

固定源码还有一个细节：52.1.0 的 `LanguageLoadingProvider.findLanguage` 直接检查实际 provider 版本，未调用矩阵。NF 的 loaderVersion 校验保留这个行为；矩阵内的 javafml 条目由独立原生契约 probe 验证，不据此扩大 language loader 准入。依据见 [VersionSupportMatrix](https://github.com/MinecraftForge/MinecraftForge/blob/1.21.1/fmlloader/src/main/java/net/minecraftforge/fml/loading/VersionSupportMatrix.java)、[ModSorter](https://github.com/MinecraftForge/MinecraftForge/blob/1.21.1/fmlloader/src/main/java/net/minecraftforge/fml/loading/ModSorter.java)，并已核对固定 52.1.0 JAR 字节码。

## 实际生命周期差异

契约 baseline 读取真实状态定义；模组 probe 则实际执行 `ModLoader.gatherAndInitializeMods` / `loadMods` / `finishMods`，使用原生 parallel executor 和 driven sync executor。状态操作与异常传播均由 Forge 保持。

```text
GATHER: VALIDATE → CONSTRUCT → CREATE_REGISTRIES → OBJECT_HOLDERS
        → INJECT_CAPABILITIES → UNFREEZE_DATA → LOAD_REGISTRIES
LOAD:   CONFIG_LOAD → COMMON_SETUP → SIDED_SETUP
COMPLETE: ENQUEUE_IMC → PROCESS_IMC → COMPLETE → FREEZE_DATA → NETWORK_LOCK
```

不能把 NeoForge 的“冻结后执行 common setup”移植到 Forge。canonical `REGISTRY_OPEN` 对应成功的 native unfreeze，`REGISTRY_FROZEN` 对应 COMPLETE 阶段成功的 native freeze。客户端 / 服务端原生入口在 owner 线程捕获 kernel session，再交给 worker 回调，避免从 worker 的 ThreadLocal 查询 owner session。

Forge `syncExecutor()` 是非 self-driven 的队列。真实 probe 中 parallel event 在 `modloading-worker-*` 执行，`enqueueWork` 等待队列运行，由调用 `driveOne()` 的线程执行；自定义注册任务也通过同一原生 driven executor 分发。另用原生 `wrappedExecutor` 验证资源 worker。报告保存 owner、parallel event、deferred work、sync driver、registry 和 resource 线程 ID / 名称，不能假定未来每种启动路径都由同一个线程驱动。

并行构造还要求 G 的加载锁与串行转换管线遵循同一锁顺序：先取得管线 monitor，再取得 per-class monitor。原生 EventBus transformer 会在转换中加载父类；反向顺序会让一个 worker 持有父类锁等待管线，另一个 worker 持有管线等待父类，造成加载画面永久停留。`ClassLoadingTest.nativePluginDependencyLoadCannotDeadlockWithAnotherLoadingWorker` 强制制造竞争，验证插件可重入加载依赖、两个 worker 完成、同一类身份及每类恰好一次转换。此修复保留 Forge 原生并行构造，不降低 worker 数量。

## 已执行的契约

| 领域 | 已验证 |
| --- | --- |
| 单 G | Forge 类型与 probe 内容类型属于同一 loader；无界面 baseline 只索引 client 类；实际客户端中 Forge / JEI / mezz_config 对象属于 G |
| Launcher | environment、blackboard、映射及适配 plugin / 被动 launch handler 查询；原生 main 和 handler 启动入口拒绝 |
| MOD / GAME bus | 独立且稳定的对象身份；listener 隔离；GAME bus 启动前不分发；原异常传播 |
| 并行 / deferred | parallel event 不在 owner 线程；enqueueWork 不提前执行；在 native sync driver 执行 |
| 注册表 | 独立真实 ForgeRegistry；DeferredRegister 经 RegisterEvent 绑定 RegistryObject；跨拥有者查询同一对象；迟注册和 freeze 后写入失败 |
| config | CLIENT / COMMON 从配置目录加载；SERVER 未随二者自动加载；显式原生默认加载、Loading 次数、内存默认不创建 SERVER 文件；acceptSyncedConfig 替换值并触发 Reloading |
| Dist | CLIENT 和 DEDICATED_SERVER 环境各自绑定；相反侧 `@OnlyIn` 在 definition 前被原生 Dist cleaner 拒绝 |
| coremod | 原生脚本初始化、SRG 映射、六个固定 transformer 和输出变化 |

注册表 probe 使用原生 standalone RegistryBuilder factory，不运行 `NewRegistryEvent.fill` 对全局 Minecraft root registry 的改写。配置同步 probe 直接调用原生同步 API，没有建立网络连接。SERVER 默认加载仅用于测量原生 API，**没有照搬 NeoForge 的 setup 注入修法**。

模组 probe 另验证全局 ForgeMod 构造、完整加载状态、原生内建 item 和自定义 registry、COMMON 配置在 setup 可读、SERVER 配置在世界 / sync 前未加载、自动 subscriber、worker setup / owner enqueueWork、SPI 与实际 Mixin 注入。外部 JEI 无界面 probe 的 Dist 为 DEDICATED_SERVER，验证公共入口、内嵌依赖与原生完整加载契约。另已实测 JEI 客户端构造、资源加载与主菜单；世界内渲染 / 模型 / JEI 配方界面、存档 serverconfig、SimpleChannel login/play/version negotiation、集成服务端和独立服务端仍待实测。

## 无界面运行

```powershell
./gradlew.bat prepareForgeClient
./gradlew.bat forgeClientBaseline forgeServerBaseline
./gradlew.bat forgeModProbe forgeJeiProbe
./gradlew.bat :loader:test
```

以上任务不调用 Minecraft Main。`forgeServerBaseline` 使用同一固定补丁输入测试 DEDICATED_SERVER 的 Dist / API 契约，**不是独立服务端运行验证**。`forgeJeiProbe` 将指定 JEI 复制到独立 build 目录，默认引用 `run/client/mods/jei-1.21.1-forge-19.57.0.451.jar`；可用 `-PforgeReferenceMod=<本地路径>` 指定。依赖检查始终启用，无跳过 Minecraft 范围的诊断模式。

## 客户端主菜单实测

实际客户端 profile 的选择参数为 `-PforgeProfile=true -PneoForgeProfile=false`，模组目录可用 `-PclientModsDir=<Forge 模组目录>` 指定。自动选择时先看只有 Forge 或只有 NeoForge 描述符的 JAR；存在只有 Forge 的模组且没有只有 NeoForge 的模组时，双描述符 JAR 不再把整包错误切到 NeoForge。只有双描述符且未显式选择时仍保留 NeoForge 默认值。在 `forgeJeiProbe` 准备参考模组后，使用独立运行目录执行：

```powershell
./gradlew.bat runClient -PforgeProfile=true -PneoForgeProfile=false `
  -PclientRunDir=build/forge-client-ui/run `
  -PclientModsDir=build/forge-jei/server/mods `
  -PforgeClientProbe=true -PclientProbeFrames=120 -PclientHeap=4g
```

probe 在 Render thread 验证 TitleScreen、资源 overlay 已完成、有效窗口、原生加载状态与全部 Java 模组对象的 G 身份；lowcode 模组验证原生 container 的 G 身份，允许其占位对象来自 JDK。NF 的 Mods 按钮复用原生 Forge 预留的半宽位置，保留 Realms / Options / Quit 的原始布局；probe 检查按钮都在屏幕内、恰好一个 Mods 按钮且所有按钮矩形不相交。2026-10-07 JEI 小集合实测 PASS，截图已人工检查，120 帧后退出码 0。证据为 `build/forge-client-ui/run/forge-client-report.json`、`forge-main-menu.png` 和 `audit.json`。

同日又用当前 `run/client/mods` 的 144 个原始 JAR，在全新配置目录实测完整客户端。101 个 JAR 只有 Forge 描述符，43 个同时包含 Forge / NeoForge 描述符；`forge_medieval_buildings_end_edition` 的 Forge 描述符有效，附带的 NeoForge 文件仍含未展开模板。本次没有修写 JAR 或放宽 TOML 校验，通过正确的 Forge profile 选择解决最初的 `METADATA_INVALID`。原生加载共 151 个条目（含 Minecraft、Forge 与内嵌模组），主菜单、所有模组 container、按钮矩形和 120 帧正常退出均 PASS；未进入世界。

```powershell
./gradlew.bat runClient -PclientModsDir=run/client/mods `
  -PclientRunDir=build/forge-pack/fresh-run `
  -PforgeClientProbe=true -PclientProbeFrames=120 -PclientHeap=6g
```

完整包证据为 `build/forge-pack/fresh-run/forge-client-report.json`、`forge-main-menu.png` 和 `audit.json`，根 JAR 清单与源哈希核验见 `build/forge-pack/validation.json`。155 项 kernel 单测、Forge client / server 契约 baseline 与含外部 JS coremod / SPI / Mixin 的内部模组 probe 均通过。

证据写入 `build/forge-baseline/client/`、`build/forge-baseline/server/`、`build/forge-mod-probe/` 和 `build/forge-jei/server/` 的 `report.json` / `audit.json`。原生 `ForgeContractProbe` 实际调用固定 `VersionSupportMatrix` 和 `ModSorter` 断言接受 / 拒绝案例；kernel 单测覆盖相同范围、audit 依据、输入哈希和转换顺序。负向 Dist probe 会输出预期 ERROR，以整体 PASS 及拒绝断言判断。
