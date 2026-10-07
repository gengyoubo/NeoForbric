# Forge 固定版本无界面基线

当前完成的是 **无参考模组的原生契约基线**：准备真实 Forge 补丁游戏输入，把 Forge API 和内部 probe 定义进 NF 的 G，再执行工具、注册表、事件与配置的独立契约。没有调用 Minecraft Main、Forge 客户端 / 服务端加载入口或原生 ModLauncher，也没有加载外部模组。`runClient` 尚未接入 Forge 原生模组运行 profile。

## 输入与锚点

| 输入 | 固定版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Forge | 52.1.0 |
| FML | 1.21.1-52.1.0 |
| ModLauncher | 10.2.4 |
| Mixin | 0.8.7 |

Forge 版本取自[官方 1.21.1 推荐版本](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html)。安装器 SHA-256、39 个规范化输入的完整 inventory / SHA-256、SRG 命名输入哈希固定在 `forge-1.21.1.lock.json`。输入准备只运行安装器声明且版本在白名单中的 installertools / FART / binarypatcher，逐项验证 processor 输出；不会执行安装器的启动脚本。

`forge-1.21.1.anchors.json` 固定 30 个真实上游类的 SHA-256 和完整方法 descriptor，包括 Launcher、FML、状态提供器、注册表、config、network、AT 与 coremod。完整类哈希同时锚定指令调用顺序。打开 G 前核验全部锚点，偏离直接 `FORGE_ANCHOR`，不尝试相邻版本。报告保存未变换的 native 方法调用序列及状态定义，失败报告保留 cause 链。

Forge 输入工具库由安装器确定；共享 ASM 仍由 kernel 提供 9.10.1。此次只证明固定 Forge 工具在这套共享 ASM 上的以下契约，未宣称任意模组的 ASM ABI 都兼容。

## 独立实现与转换

kernel 侧 `org.neoforbric.forge.ForgeRuntime` 管理输入、转换顺序和反射边界；`forge-runtime` 模块中的 `ForgeBridge` 只包含 Forge 原生 API 实现，始终由 G 定义。没有把 Forge 分支放入 NeoForge runtime。

本基线的已注册顺序为：

```text
forge-native-host
→ forge-native-plugins（Dist cleaner / eventbus）
→ forge-access-transformers
→ forge-coremods
```

使用 Forge 自己的 AT 引擎和 JS CoreModProvider。固定 universal 中两个 JS coremod 生成六个 transformer，五个 field-to-method 目标以及 Zombie 的 finalizeSpawn 方法重定向都实际执行并检查输出变化。AT 与 coremod 是不同阶段，没有用一个不透明的“transform”替代它们。此顺序是 NF 当前基线契约；完整原生启动的顺序对照尚未运行。

Launcher facade 填充真实 Environment / blackboard，提供 SRG→Mojang 成员映射查询。已适配的 launch plugin 查询返回本次真正注册的 eventbus / AT / Dist cleaner 对象；没有注册的 plugin、launch handler 与 module layer 查询返回空 Optional。`Launcher.main/run`、原生 classloader 构造和 FML 原生扫描 / 启动入口明确拒绝 `FORGE_LAUNCH_OWNERSHIP`。没有启动第二个 G 或自动发现 transformation services。

`Launcher.launchPlugins` 反射读取返回被动 handler，其 `plugins` 是实时、只读的实际 adapter 状态；没有把这个字段留空。动态插入插件明确抛出 `FORGE_PLUGIN_REGISTRATION`，原生 handler 的独立转换 / 启动入口也被拒绝。其他私有 ModLauncher 接管字段没有完整 native 实现，不宣称任意反射使用已适配。

外部 JS / Java coremod、custom transformation service、Mixin / MixinExtras / MixinSquared，以及其他 launch plugins 均未进入本基线的输入图；尚未适配这些外部契约。固定的 Mixin 输入版本不等于已经验证了 Forge Mixin 执行。普通 Forge 模组也尚未准入，因此此阶段没有三端混装。

## 实际生命周期差异

报告从真实 `ModStateProvider` 与 `ForgeStatesProvider` 读取状态定义及其 `previous` / native phase，**没有执行整套 FML 加载流程**。

```text
GATHER: VALIDATE → CONSTRUCT → CREATE_REGISTRIES → OBJECT_HOLDERS
        → INJECT_CAPABILITIES → UNFREEZE_DATA → LOAD_REGISTRIES
LOAD:   CONFIG_LOAD → COMMON_SETUP → SIDED_SETUP
COMPLETE: ENQUEUE_IMC → PROCESS_IMC → COMPLETE → FREEZE_DATA → NETWORK_LOCK
```

因此不能把 NeoForge 的“冻结后执行 common setup”移植到 Forge。未来 canonical `REGISTRY_OPEN` 应对应成功的 native unfreeze，`REGISTRY_FROZEN` 应对应 COMPLETE 阶段成功的 native freeze；canonical 名称可共用，其事件顺序和实现必须保持 Forge 自己的语义。

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

全局 ForgeMod 构造、完整 native lifecycle、内建 item / data component / entity serializer / menu / sound 注册、creative tab、存档 serverconfig、SimpleChannel login/play/version negotiation、集成服务端、独立服务端和主菜单仍待实测。该版本 `SimpleChannel` 暴露 `messageBuilder` / `ChannelBuilder`，不能直接按旧版本 `registerMessage` 假定 ABI。

## 无界面运行

```powershell
./gradlew.bat prepareForgeClient
./gradlew.bat forgeClientBaseline forgeServerBaseline
./gradlew.bat :loader:test
```

两个 baseline 任务都不启动游戏。`forgeServerBaseline` 使用同一固定补丁输入测试 DEDICATED_SERVER 的 Dist / API 契约，**不是独立服务端运行验证**。不读取 ATM10 或 `run/client/mods`。

证据写入 `build/forge-baseline/client/` 和 `build/forge-baseline/server/` 的 `report.json` / `audit.json`。负向 Dist probe 会由原生工具输出一条预期 ERROR；以整体 PASS 结果和对应拒绝断言判断。loader 单元测试另覆盖相邻版本拒绝、完整输入 inventory、checksum 自证拒绝、字节码 / descriptor 偏离及稳定转换顺序。
