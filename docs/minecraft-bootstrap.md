# Minecraft 1.21.1 启动与物品注册实测

已接通真实服务端 JAR、映射转换、NeoForbric 游戏类加载器、Fabric Java 入口和原版静态注册表。当前运行的是 Minecraft 的 `--initSettings` 路径：初始化游戏、注册物品、冻结注册表、生成配置后退出。尚未启动世界、Tick 或网络，也没有接受 EULA；这不是完整整合包兼容声明。

## 运行与复现

在仓库根目录使用 JDK 21。本机默认 Java 为 8，需设置当前终端的 `JAVA_HOME`：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot'
./gradlew.bat runMinecraftProbe
./gradlew.bat :loader:test :loader:minecraftTest :loader:verifyIdeArtifacts
```

首次执行下载锁定的 Minecraft 服务端、官方映射和 Fabric intermediary 映射，从官方 bundle 提取游戏与 30 个库，再生成映射后的运行输入。转换任务和 Gradle 探针使用 2 GiB Java 堆。缓存位于 `build/minecraft`，不提交游戏 JAR；后续运行复核缓存校验值。

成功输出包含：

```text
FABRIC_ITEM_REGISTERED neoforbric_probe:shared_item
MINECRAFT_PROBE_OK version=1.21.1 main=1 server=1 itemIdentity=true stackIdentity=true holderIdentity=true keyIdentity=true frozen=true lateRejected=true libraryIsolation=true gameLoader=NeoForbric-Game
```

`build/minecraft/audit.json` 记录输入哈希、模组映射、转换、类定义、注册窗口和最终结果。`build/minecraft/run` 存放 `server.properties`、值为 false 的 `eula.txt` 和日志，不生成世界。首次运行时原版可能先记录尚不存在的 properties 文件，再写入默认配置；应结合最终退出码、审计结果与探针输出判断。

也已验证分发脚本直接运行：

```powershell
./gradlew.bat prepareFabricProbe :loader:installDist
Push-Location ./build/minecraft/run
try {
    ../../../loader/build/install/loader/bin/loader.bat --minecraft-server `
        --runtime ../runtime.json --mods ../mods --verify demo.fabricprobe.ItemProbe `
        --audit ../standalone-audit.json -- --initSettings
} finally { Pop-Location }
```

CLI 的 Minecraft 模式目前要求 `--initSettings`，固定服务端 main 与 server side；不允许替换游戏 main 或直接启动持续运行的服务端。

## 启动与注册窗口

```text
JVM → NeoForbric Main
    → 元数据发现、依赖检查、运行输入校验
    → 模组 intermediary → Mojang 映射
    → 规划游戏 / 库 / 模组类来源，准备转换器
    → 打开 NeoForbric-Game 的定义屏障
    → Minecraft 服务端 Main
        → 原版 BuiltInRegistries.createContents
        → Fabric main Java 入口：注册 Item
        → 原版 BuiltInRegistries.freeze
        → Fabric server Java 入口：查询同一 Item
        → 初始化配置并退出
    → 验证 Item / ItemStack / Holder / ResourceKey 身份与冻结
    → 恢复标准输出及线程上下文类加载器，写审计并关闭实例
```

固定的 `RegistryWindowHook` 只修改 1.21.1 `BuiltInRegistries.bootStrap()`：核对输入类哈希，要求唯一且顺序正确的 `createContents` 和 `freeze` 调用锚点，再在冻结前后插入回调。原版冻结仍执行；没有解除冻结或重建注册表。冻结后的额外注册实测被原版拒绝。

这是当前有限 Fabric profile 的阶段安排，尚未证明完整 Fabric Loader / Fabric API 模组的生命周期等价。Minecraft 自身有捕获启动异常的路径，因此 `GameHooks` 保存入口失败，main 返回后再次检查，避免把入口失败误报成成功。当前只处理同步启动路径。

## 输入与映射

[运行输入锁](../loader/src/main/resources/org/neoforbric/minecraft/1.21.1-inputs.json) 固定官方版本清单、服务端 bundle、官方映射和 intermediary 的 URL 与哈希。工具直接提取 bundle 内容，不执行 Mojang bundler 的启动器。内部游戏与库使用 bundle 清单的 SHA-256 校验；生成的 `runtime.json` 记录原始与派生文件哈希，启动前复核。

`runtime.json` 是本地准备任务产生的运行清单，不是签名的发布清单；派生文件校验不能证明恶意重写清单与文件后的来源可信性。

Mapping IO 0.6.1 合并官方 ProGuard 映射和 Fabric Tiny v2 映射，Tiny Remapper 0.10.4 执行转换：

| 输入 | 输出 | 用途 |
| --- | --- | --- |
| 原版 official 游戏 JAR | Mojang 命名游戏 JAR | 内核运行 |
| 原版 official 游戏 JAR | intermediary 游戏 JAR | 现成 Fabric JAR 的映射类路径 |
| 探针的 Mojang 编译 JAR | intermediary 模组 JAR | 模拟生产 Fabric JAR |
| intermediary 模组 JAR | Mojang 模组 JAR | 装入同一游戏类加载器 |

[Fabric 探针](../probes/fabric-item/src/main/java/demo/fabricprobe/ItemProbe.java) 使用真实 `net.fabricmc.api.ModInitializer`，server 入口使用 `DedicatedServerModInitializer`；入口只调用原版游戏 API，没有依赖 NeoForbric API。`--verify` 是探针专用验收回调，不属于 Fabric 入口契约。

## 类与库的边界

父加载器持有内核、映射工具与共享 Fabric SPI；游戏、官方库和模组只由 `NeoForbric-Game` 定义。Fabric Loader 0.16.10 JAR 提供 SPI 和版本谓词实现，不启动 Knot，也不调用原生 Loader 的游戏引导。

已实测游戏库 Gson 2.10.1 与内核工具 Gson 2.11.0 分别属于游戏和父加载器；共享 SPI 仍保持一个定义。重复游戏 / 模组类以及未经允许的父类路径污染继续在定义前失败。

准备工具按 Java 21 选择官方库中的 multi-release 条目，保留服务描述和资源，生成规范化 JAR，并记录转换后哈希。已验证原始 bundle 后才处理其签名及 manifest 元数据；这不是任意第三方签名库的支持。当前不创建 JPMS module layer，模组的嵌套 JAR、签名布局与 multi-release 布局仍受准入限制。

## Fabric profile 的准入范围

支持 schema 1、当前侧别、Fabric 版本谓词和字符串依赖；支持默认 Java adapter 的类入口，执行 `main` 与 `server`，不执行 `client`。原生入口实例和方法在调用前检查。

`fabricloader=0.16.10` 表示此 profile 使用的 SPI / 版本实现来源，不意味着完整 Loader runtime API 已实现。非空 Mixin、Access Widener、nested jars、自定义语言 adapter，以及暂未实现的冲突 / 建议依赖字段会明确拒绝；依赖数组等未实现形式也不静默接受。Forge、NeoForge Java 入口仍待接入。

## 已执行的验收

2026-10-06，在 Windows / Microsoft OpenJDK 21.0.10 下通过 28 项内核测试和 3 项独立 JVM 的真实 Minecraft 测试：

- 成功路径：main / server 各执行一次，同一 Item 的查询、ItemStack、Holder 与 ResourceKey 身份成立；游戏与模组由同一加载器定义，库隔离成立，冻结后注册失败。
- 入口失败：故意抛出异常，整次启动返回失败，server 入口与成功验证不执行，并记录实例已受污染。
- 输入失败：修改运行清单中的映射校验值，在定义游戏类之前拒绝启动。

原有三个自有模组的同对象夹具也通过回归。严格依赖验证已覆盖 22 个锁定依赖的 43 个 IDE sources / Javadoc 产物，包括本轮新增的 ASM、Mapping IO 和 Tiny Remapper 源码包；IDE 可重新加载 Gradle 项目。

下一步应接 Mixin / AW 与一个固定的 Fabric API 样本，再扩展持续服务端生命周期。当前结果没有覆盖世界动态注册表、三生态互操作、存档、网络握手或客户端。
