# 首个可运行原型

本文记录首个 Java 夹具阶段；其 `--fixture` 模式与原生模组拒绝策略仍保留。新增真实 Minecraft / 有限 Fabric Java profile 的运行、验收和限制见 [Minecraft 实测说明](minecraft-bootstrap.md)。

仓库已从调查进入实现。本次交付的是 **Java 21 启动与类加载机制原型**，目标游戏版本仍为 Minecraft 1.21.1。它运行三个独立的自有样例 JAR 和一个游戏夹具 JAR；Minecraft、Fabric / Forge / NeoForge 原生入口尚未接入。

## 运行

在仓库根目录使用 JDK 21。PowerShell 示例中的路径换成自己的 JDK 21 安装目录；本机默认 `java` 指向 Java 8，需要显式设置当前终端的 `JAVA_HOME`。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot'
./gradlew.bat runFixture
./gradlew.bat :loader:test
```

首次执行会下载 Gradle 和 Maven Central 依赖。预期夹具输出：

```text
FIXTURE_OK mods=A,B,C sameGameLoader=true sharedValue=42
```

审计记录在 `build/fixture/audit.json`；测试报告在 `loader/build/reports/tests/test/index.html`。这些是执行产物，不入版本控制。2026-10-06 已在 Windows / Microsoft OpenJDK 21.0.10 下验证；其他系统尚未实测。

也可以生成并直接运行分发包，启动脚本的父类路径只包含内核及其工具依赖：

```powershell
./gradlew.bat :loader:installDist prepareFixture
./loader/build/install/loader/bin/loader.bat --fixture `
  --game ./fixtures/game/build/libs/game-0.1.0-SNAPSHOT.jar `
  --mods ./build/fixture/mods --main demo.game.GameMain `
  --audit ./build/fixture/standalone-audit.json
```

识别已有模组的顶层元数据时使用：

```powershell
./loader/build/install/loader/bin/loader.bat --inspect `
  --mods ./mods --audit ./build/inspection.json
```

`--inspect` 读取 `fabric.mod.json`、`META-INF/mods.toml` 或 `META-INF/neoforge.mods.toml` 中的身份字段，记录生态、id、版本、路径和 JAR SHA-256。它不验证完整原生依赖或入口契约，也不加载模组类、调用语言 provider 或解包嵌套库。每个顶层 JAR 必须恰好具有一个可识别的描述文件；普通无描述文件的库 JAR 和多描述文件混合包当前会报错。

CLI 的成功退出码为 0，启动失败为 1，参数错误为 2。启动成功或失败都会尝试写审计文件；参数解析失败发生在创建启动实例之前。审计文件无法写入也会使启动报错。

## IDE 导入与依赖校验

IntelliJ IDEA 导入 Gradle 时还会解析依赖的源码与 Javadoc。它们是独立于编译 JAR 的产物，也需要登记 SHA-256；仅运行 `build` 不会覆盖这条解析路径。当前校验文件已覆盖主依赖、测试依赖及其传递依赖的 43 个 sources / Javadoc 产物（22 个锁定依赖）。

可以在保持严格校验的情况下单独验证 IDE 使用的产物：

```powershell
./gradlew.bat :loader:verifyIdeArtifacts
```

这个任务查询四个已锁定 main / test classpath 的实际外部组件，不新增 classifier 配置或另一套依赖版本。修复后在 IDEA 重新加载 Gradle 项目即可。

后续有意更新依赖时，维护者同时运行 `:loader:verifyIdeArtifacts --write-verification-metadata sha256`，核对新增校验值，再使用不带写入选项的任务验证。普通构建和 IDE 导入继续使用严格校验。

## 已实现的启动路径

```text
JVM → org.neoforbric.bootstrap.Main
    → DISCOVER：读取不可变 JAR 快照与描述文件
    → RESOLVE：侧别筛选、依赖检查、确定初始化顺序
    → PREPARE：规划类来源、拒绝重复类和父类路径污染
    → 创建 G，定义屏障保持关闭
    → 固定转换器依赖顺序，SEALED，开放定义屏障
    → 预检全部原型入口和 main 的形状，期间不初始化候选类
    → MOD_INIT：顺序调用原型入口
    → GAME_MAIN：调用夹具 main
    → 关闭 G、恢复启动线程 TCCL、输出审计
```

内核没有 Knot、ModLauncher、FML 依赖，也不会自动发现原生启动服务。默认转换器清单为空；可由内核代码通过 `TransformPipeline.add(...)` 显式注册，必须在屏障打开前固定。测试中使用真实 ASM 转换修改常量，并验证 JVM 执行的是转换后的字节码。

这个生命周期面向同步结束的夹具 main。尚未实现真实客户端 / 专用服务器的线程、停机和长期运行管理；夹具关闭 G 的时机不能直接沿用到 Minecraft。

## 类与资源边界

| 域 | 本次实现 |
| --- | --- |
| JVM / platform | 通过 Java 平台加载器解析 Java 标准类 |
| P：引导与内核 | Main、发现与转换工具、共享 `org.neoforbric.api`、固定版本 ASM，以及 Gson / TOML 工具依赖 |
| G：游戏与已准入模组 | 一个 `GameClassLoader` 实际定义全部游戏夹具与模组类型；每个二进制类名只有一个来源 |

本次的引导和 loader 工具共处 P，还没有单独的 L 加载器、JPMS module layer 或逐模组隔离域。G 只允许 `org.neoforbric.api.*` 和 `org.objectweb.asm.*` 从 P 共享，其他非平台类型必须属于已规划的 G 输入。模组复制共享 API / ASM 或任何内核类会在定义前失败；游戏或模组类出现在父类路径上也会失败。

同名类出现在不同输入 JAR 时，即使字节相同也拒绝；同一真实路径的重复档案输入只索引一次。尚无库版本选择、包重定位或库下载机制。模组所需的其他类必须随已准入档案提供，不能依赖任意父类路径回退。

JAR 在发现时读成内存快照，后续资源和类定义使用同一份字节。模组和游戏档案统一限制为压缩体 1 GiB、单项 32 MiB、展开总量 1 GiB、文件项 100,000 个，以支持 Cobblemon 的资源 / 嵌套语言库、Chipped 等包含大量小资源的模组，以及 Z's Medieval Music 等整张原声带类的大体积模组。重映射规范化阶段使用相同的单项和展开总量限制。超限错误会指出具体限制。资源按游戏 JAR、已解析模组顺序聚合；`META-INF/services` 可以由显式 `ServiceLoader.load(SPI, G)` 枚举，测试验证共享 SPI 身份和多个 provider。内核不会据此自动启动 native loader 或转换服务。

资源 URL 使用 `neoforbric:` 自定义内存协议，`.class` 资源视图仍是原始字节，转换后的 SHA-256 另记在定义记录里。依赖 `jar:` URL、磁盘展开或改写资源的库尚未支持。`CodeSource` 保留原始文件位置，源字节身份以审计哈希为准。

执行准入会明确拒绝 nested JAR、签名段、manifest `Class-Path`、`Automatic-Module-Name`、multi-release JAR 和 `module-info.class`。manifest package sealing 在重映射时保留，并由游戏类加载器按 JVM 语义执行（sealed package 拒绝其他 archive 的类）。其余拒绝项仍待实现，拒绝行为只证明边界有效。`--inspect` 不做执行准入检查。

## 自有原型入口与元数据

当前可执行 JAR 的根目录使用 `neoforbric.mod.json`：

```json
{
  "schemaVersion": 1,
  "id": "sample",
  "version": "1.0.0",
  "environment": "*",
  "entrypoint": "example.SampleInitializer",
  "depends": { "minecraft": "1.21.1", "java": ">=21.0.0 <22.0.0" },
  "optionalDepends": { "another_sample": ">=1.0.0 <2.0.0" },
  "after": ["another_sample"]
}
```

入口是实现共享 `org.neoforbric.api.ModInitializer` 的 public、非 abstract 类，具有 public 无参构造器。这个 SPI 只有 `void onInitialize()`，用于验证入口与加载器身份，尚不是统一 Minecraft 生命周期 API。编译时可参照 `fixtures/mod-a/build.gradle` 对 loader 使用 `compileOnly`，不要把内核复制进模组 JAR。

原型 JSON 使用严格 UTF-8 / JSON 解析，拒绝重复键、额外根值、未知原型字段。Fabric 描述文件使用 Fabric Loader 自己的 JSON reader，不开启 lenient，兼容其引号字符串中的原始控制字符及重复键行为。原型 id 为 `[a-z][a-z0-9_-]{0,63}`；版本为严格三段 SemVer，支持 prerelease 与 build metadata，比较时忽略 build metadata。依赖表达式支持 `*`、精确版本、`= > >= < <=`，空格连接的条件全部满足才算匹配。这里没有实现 Fabric 谓词或 Maven 区间、`^` / `~`、通配版本和 OR。

`environment` 为 `*`、`client` 或 `server`；默认 `*`，CLI 默认选择 server。被排除的模组不能满足必需依赖。必需依赖必须存在且版本匹配；可选依赖缺失可忽略，存在时必须匹配。选中的模组依赖和存在的 `after` 项构成前置关系；无关系的节点按 id 排序，循环直接失败。

内建身份为 `minecraft=1.21.1`、`java=21.0.0`，`neoforbric` 版本在构建时读取根目录 `gradle.properties` 的 `NeoForbricVersion`；模组不能覆盖。Mods 界面、原型依赖解析和 Fabric 运行时共用这个构建版本，修改后重新构建即可。这是原型目标与协议版本声明，**不会读取或证明实际游戏 JAR 的 Minecraft 版本**；Java 补丁版本另记在审计里。

遇到当前侧别选中的原生 Fabric / Forge / NeoForge 模组，执行模式报 `NATIVE_RUNTIME_UNSUPPORTED`，发生在读取游戏档案、创建 G 和定义模组类之前。

## 验证和失败契约

三个样例是独立的自有原型模组，A 创建一个 `SharedItem` / `SharedStack`，B 获取同一引用并写入 40，C 检查后加 2，游戏 main 检查顺序、引用身份、G 身份和结果 42。它们不是 Fabric / Forge / NeoForge 模组，`SharedItem` 也不是 Minecraft `Item`；这个结果只验证 Java 对象与类型身份。

| 测试集 | 覆盖的行为 |
| --- | --- |
| DiscoveryResolverTest | 发现不触发 static initializer；三种原生身份识别；严格元数据；依赖顺序、缺失、版本、重复 id、循环与侧别；版本比较 |
| ClassLoadingTest | 定义屏障；唯一 P API；重复类与父类路径污染；实际转换输出；并发同类只转换 / 定义一次；非法转换重入；转换错误与改名输出；转换顺序；共享 SPI 服务聚合；字节快照；未支持布局的拒绝 |
| BootstrapIntegrationTest | 三 JAR 共享对象；独立 JVM main 启动；入口失败阻止后续入口和 main；预检在任何模组初始化前完成；原生执行拒绝；inspect 不加载类；TCCL 恢复及启动实例不可复用 |

本次共 25 项 JUnit 测试通过，0 失败 / 错误 / 跳过，并独立运行 `runFixture` 与分发包启动脚本。测试结果对应当前 Java 夹具范围。

定义屏障打开后，转换器不得查询 G 中的 Class，必须使用 `Context.bytecode().original(name)` 查询原始游戏域字节。当前转换工具串行执行，同一类的并发请求只定义一次。没有 arbitrary coremod 的跨线程回调或并行安全契约。

转换 / 定义 / 链接失败会记录具体规则或类、来源哈希和错误码，使该 G 后续类请求失败；入口或 main 失败终止整次启动。已定义类或部分入口执行后，启动报告标记 `stateTainted=true`、`severity=INSTANCE_FATAL`、`recovery=restart`。本次不提供禁用单个模组后继续、类定义回滚或在线恢复；已在途的并发调用也不承诺撤回。JVM 致命错误及线程终止信号继续向外传播。

审计 JSON schemaVersion 为 1，包含实际 JVM 版本、阶段、发现身份、类来源规划、转换顺序、成功转换的输入 / 输出哈希、实际 class-defined、入口与 main 起止、TCCL 恢复及失败记录。没有完整的 Mixin 注入匹配、指令 diff 或结构修复 provenance；[16 的完整 audit 草案](16-transform-audit.md) 仍是后续目标。

## 构建固定输入与下一步

Gradle Wrapper 固定 8.14.3，并校验 distribution SHA-256。Wrapper 文件来自 Gradle 的 `v8.14.3` 源码标签；wrapper JAR SHA-256 为 `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`。JDK 工具链固定主版本 21，尚未锁定 JDK 安装包字节。

运行依赖固定为 Gson 2.11.0、tomlj 1.1.1、ASM 9.7.1，测试使用 JUnit 5.12.2；各项目 `gradle.lockfile` 固定传递依赖，`gradle/verification-metadata.xml` 校验已解析产物和元数据的 SHA-256。初始依赖哈希来自本次 Maven Central 解析，用于后续一致性校验；不是独立签名认证。JAR 构建关闭文件时间戳并固定项顺序。调查快照没有被用作构建锁。

这批测试落实了 [18](18-bootstrap-and-classloading.md) 的一部分启动、owner、屏障、服务与失败机制，也建立了 [17](17-cross-ecosystem-interoperability.md) 所需的同对象夹具基础。B-09 / B-10 的真实游戏与原生入口、真实 Item / Holder / Capability 互操作，以及其余研究探针仍未验收；不能把本次结果改写成 29 项全部通过。

下一步沿现有定义路径接入固定版本 Minecraft 服务端输入和映射，再接一个最小 Fabric 原生 Java 模组。每次扩展一个可实测的契约：原生对照入口 → 注册窗口和同 ItemStack → 命令 / 生命周期；Mixin、AW / AT、NeoForge / Forge ABI 与事件接入随实际样本推进。渲染、网络握手、存档与 worldgen 尚未实现。
