# Forge 固定版本模组运行适配

已把独立的 Forge runtime 接入 `runClient` profile：被动发现 Forge 描述符和 JarJar，使用同一个 G 构造原生 FML metadata / container，执行 Forge 自己的加载状态机。无界面验证分别使用内部注册 / 配置 / Mixin 模组，以及指定的 `jei-1.21.1-forge-19.57.0.451.jar` 和其内嵌 `mezz_config`。本轮没有启动客户端、Minecraft Main、窗口、世界或原生 ModLauncher；JEI 的客户端界面和进入世界仍未验证。

## 输入与锚点

| 输入 | 固定版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Forge | 52.1.0 |
| FML | 1.21.1-52.1.0 |
| ModLauncher | 10.2.4 |
| Mixin | 0.8.7 |

Forge 版本取自[官方 1.21.1 推荐版本](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html)。安装器 SHA-256、39 个规范化输入的完整 inventory / SHA-256、SRG 命名输入哈希固定在 `forge-1.21.1.lock.json`。输入准备只运行安装器声明且版本在白名单中的 installertools / FART / binarypatcher，逐项验证 processor 输出；不会执行安装器的启动脚本。

`forge-1.21.1.anchors.json` 固定 40 个真实上游类的 SHA-256 和完整方法 descriptor，包括 Launcher、FML、状态提供器、注册表、config、network、AT、coremod 与版本兼容矩阵。完整类哈希同时锚定指令调用顺序。打开 G 前核验全部锚点，偏离直接 `FORGE_ANCHOR`，不尝试相邻版本。报告保存未变换的 native 方法调用序列及状态定义，失败报告保留 cause 链。

Forge 输入工具库由安装器确定；共享 ASM 仍由 kernel 提供 9.10.1。此次只证明固定 Forge 工具在这套共享 ASM 上的以下契约，未宣称任意模组的 ASM ABI 都兼容。

## 独立实现与转换

kernel 侧 `org.neoforbric.forge.ForgeRuntime` 管理输入、转换顺序和反射边界；`forge-runtime` 模块中的 `ForgeBridge` 只包含 Forge 原生 API 实现，始终由 G 定义。没有把 Forge 分支放入 NeoForge runtime。

模组 profile 的已注册顺序为：

```text
NF 客户端生命周期 / 主菜单结构补丁（客户端 profile）
→ forge-native-host
→ forge-lifecycle（被动服务与成功状态观察）
→ forge-native-plugins（Dist cleaner / eventbus）
→ forge-access-transformers
→ forge-coremods
→ forge-mixin
```

使用 Forge 自己的 AT 引擎和 JS CoreModProvider。固定 universal 中两个 JS coremod 生成六个 transformer，五个 field-to-method 目标以及 Zombie 的 finalizeSpawn 方法重定向都实际执行并检查输出变化。AT 与 coremod 是不同阶段，没有用一个不透明的“transform”替代它们。此顺序是 NF 当前基线契约；完整原生启动的顺序对照尚未运行。

Launcher facade 填充真实 Environment / blackboard，提供 SRG→Mojang 成员映射查询。已适配的 launch plugin 查询返回本次真正注册的 eventbus / AT / Dist cleaner 对象；没有注册的 plugin、launch handler 与 module layer 查询返回空 Optional。`Launcher.main/run`、原生 classloader 构造和 FML 原生扫描 / 启动入口明确拒绝 `FORGE_LAUNCH_OWNERSHIP`。没有启动第二个 G 或自动发现 transformation services。

`Launcher.launchPlugins` 反射读取返回被动 handler，其 `plugins` 是实时、只读的实际 adapter 状态；没有把这个字段留空。动态插入插件明确抛出 `FORGE_PLUGIN_REGISTRATION`，原生 handler 的独立转换 / 启动入口也被拒绝。其他私有 ModLauncher 接管字段没有完整 native 实现，不宣称任意反射使用已适配。

准入 `javafml` / `lowcodefml` 模组，加载其 AT 与 manifest `MixinConfigs`，通过 NF 的 Mixin service 执行 Mixin / MixinExtras，转换 SRG 成员和 refmap 为 Mojang 名称。EventBus 动态生成的 dispatcher 也由 G 定义并记录所属输入。外部 JS / Java coremod、custom transformation service、其他 language / state provider 和 launch plugin 在构造前拒绝 `FORGE_FEATURE_UNSUPPORTED`；MixinSquared 尚未验证。Forge profile 当前只接入 Forge 模组，不支持三端混装。

客户端结构 hook 必须读取原始固定输入。实际失败 audit 显示 native plugin 已改写 `Main` 的哈希，而客户端生命周期 hook 随后仍按原始哈希校验；现通过显式排序依赖让结构 hook 在 native plugin 之前执行。Forge `Minecraft` 的退出调用锚点是五处，不能照搬 NeoForge 的两处。回归测试只变换 `Main` / `Minecraft` 字节码，不执行入口，并保留对错误输入的拒绝。

NF 提前暴露 `ModList` metadata 供 Mixin plugin 查询，因此 Minecraft 的 crash-report preload 可能早于原生 container 构造。准备阶段使用 Forge 自己的 `setLoadedMods(emptyList)` 初始化空索引；查询 container 返回 empty，报告状态为 `NONE`，没有提前构造模组。之后原生 gather 创建并索引真实 containers。内部模组和 JEI probe 均验证构造前的查询和 crash report，以及构造后的真实 G 对象。

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

## 已执行的契约

| 领域 | 已验证 |
| --- | --- |
| 单 G | Forge 类型与 probe 内容类型属于同一 loader；Minecraft client 类能被索引，但没有被 define |
| Launcher | environment、blackboard、映射及适配 plugin 查询；原生 main 拒绝 |
| MOD / GAME bus | 独立且稳定的对象身份；listener 隔离；GAME bus 启动前不分发；原异常传播 |
| 并行 / deferred | parallel event 不在 owner 线程；enqueueWork 不提前执行；在 native sync driver 执行 |
| 注册表 | 独立真实 ForgeRegistry；DeferredRegister 经 RegisterEvent 绑定 RegistryObject；跨拥有者查询同一对象；迟注册和 freeze 后写入失败 |
| config | CLIENT / COMMON 从配置目录加载；SERVER 未随二者自动加载；显式原生默认加载、Loading 次数、内存默认不创建 SERVER 文件；acceptSyncedConfig 替换值并触发 Reloading |
| Dist | CLIENT 和 DEDICATED_SERVER 环境各自绑定；相反侧 `@OnlyIn` 在 definition 前被原生 Dist cleaner 拒绝 |
| coremod | 原生脚本初始化、SRG 映射、六个固定 transformer 和输出变化 |

注册表 probe 使用原生 standalone RegistryBuilder factory，不运行 `NewRegistryEvent.fill` 对全局 Minecraft root registry 的改写。配置同步 probe 直接调用原生同步 API，没有建立网络连接。SERVER 默认加载仅用于测量原生 API，**没有照搬 NeoForge 的 setup 注入修法**。

模组 probe 另验证全局 ForgeMod 构造、完整加载状态、原生内建 item 和自定义 registry、COMMON 配置在 setup 可读、SERVER 配置在世界 / sync 前未加载、自动 subscriber、worker setup / owner enqueueWork 及实际 Mixin 注入。外部 JEI probe 的 Dist 为 DEDICATED_SERVER，只证明其公共入口、内嵌依赖与原生完整加载契约；客户端 renderer / model / UI、存档 serverconfig、SimpleChannel login/play/version negotiation、集成服务端、独立服务端和主菜单仍待实测。

## 无界面运行

```powershell
./gradlew.bat prepareForgeClient
./gradlew.bat forgeClientBaseline forgeServerBaseline
./gradlew.bat forgeModProbe forgeJeiProbe
./gradlew.bat :loader:test
```

以上任务不调用 Minecraft Main。`forgeServerBaseline` 使用同一固定补丁输入测试 DEDICATED_SERVER 的 Dist / API 契约，**不是独立服务端运行验证**。`forgeJeiProbe` 将指定 JEI 复制到独立 build 目录，默认引用 `run/client/mods/jei-1.21.1-forge-19.57.0.451.jar`；可用 `-PforgeReferenceMod=<本地路径>` 指定。依赖检查始终启用，无跳过 Minecraft 范围的诊断模式。

实际客户端 profile 的选择参数为 `-PforgeProfile=true -PneoForgeProfile=false`，模组目录可用 `-PclientModsDir=<Forge 模组目录>` 指定。检测到纯 Forge 描述符且未选择 NeoForge 时可自动选择 Forge。此轮只接通入口和做无界面检查，没有执行 `runClient`。

证据写入 `build/forge-baseline/client/`、`build/forge-baseline/server/`、`build/forge-mod-probe/` 和 `build/forge-jei/server/` 的 `report.json` / `audit.json`。原生 `ForgeContractProbe` 实际调用固定 `VersionSupportMatrix` 和 `ModSorter` 断言接受 / 拒绝案例；kernel 单测覆盖相同范围、audit 依据、输入哈希和转换顺序。负向 Dist probe 会输出预期 ERROR，以整体 PASS 及拒绝断言判断。
