# Minecraft 1.21.1 客户端与 Mods 列表

`runClient` 使用 NeoForbric 自主 bootstrap、Mojang 命名游戏 JAR 和单一游戏类加载器，启动真实 Minecraft 窗口。`mods` 目录含 Fabric 模组时默认进入 NeoForbric Fabric runtime（Mixin、AW、nested JAR、依赖图、Fabric Loader API 与 Fabric API 模块）；`-PfabricPlainProfile` 可切回有限的 plain Fabric Java 入口 profile 用于回归测试。平台暂限定 **Windows x64 / JDK 21**，需要桌面会话及可用 OpenGL 驱动。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21
./gradlew.bat runClient
```

游戏目录为 `run/client`，用户模组放在 `run/client/mods`。准备任务更新演示用 Fabric 客户端探针，不清空用户模组目录。账号是本地离线开发身份 `NeoForbricDev`，可用 `-PclientUsername=名字` 修改；此任务没有 Microsoft 登录流程。

`runClient` 默认最大堆为 6 GiB，可用 `-PclientHeap=4g` 等参数覆盖。完整包重映射缓存位于 `build/minecraft-client/fabric-remap-cache`；命中前逐一校验输入、游戏和映射、转换代码、工具依赖及输出 SHA-256，任何变化都会重建。`-PclientDebug=true` 输出异常原因链，`-PclientProbeFrames=40` 在主菜单探针成功后渲染 40 帧并自动结束。

归档读取及重映射规范化支持压缩体 1 GiB、单项 32 MiB、展开总量 1 GiB，仍限制为 100,000 个文件。Fabric 只发现 `jars` 元数据声明的嵌套 JAR；其他内附 JAR 保留为普通资源，不展开进类索引，例如 LambDynamicLights 附带的 NeoForge 版本。Minecraft 1.21.1 自带 ICU 73.2 作为 `com_ibm_icu_icu4j` builtin 参与依赖解析，避免 Cobblemon 的 generated ICU 71.1 与游戏库定义同名类；显式不满足的 ICU 版本要求仍失败。

Fabric 描述文件的发现、受限入口检查和 refmap 路径读取统一使用固定 Fabric Loader 的 `JsonReader`，不开启 lenient。这与实际 Fabric 一样接受引号字符串里的原始控制字符，并让重复键采用最后一个值；`neoforbric.mod.json` 仍使用严格 JSON 解析。`bwncr` 3.20.3 / 3.20.4 描述里的三处原始换行因此可直接读取，无需修改原始 JAR。`FabricJsonTest` 对照真实 `ModMetadataParser` 验证控制字符、缓冲区边界、重复键和非法语法；旧的 `tools/repair_client_metadata.py` 仅作为离线修包工具保留。

Kotlin 模组在字节码重映射后，通过固定 `kotlin-metadata-jvm:2.2.20` 同步转换 `@kotlin.Metadata` 中的类 / 类型图和 JVM 属性、函数、构造器签名，避免 `kotlin-reflect` 从旧的 intermediary 描述符加载不存在的类（Cobblemon 的 `SpeciesAdditions`）。解析和序列化使用[官方 Kotlin Metadata JVM API](https://kotlinlang.org/docs/metadata-jvm.html)，不改写普通字符串；不支持的元数据转换会以 `KOTLIN_METADATA` 终止启动。`FabricKotlinMetadataTest` 使用真实 Kotlin 反射验证转换前失败、转换后成功解析 getter。元数据工具及其 Kotlin 标准库也纳入重映射缓存指纹。

目标命名空间可能使原本不同的父子方法重名，例如 UniLib 开放 `EditBox.method_20316()` 后转换为 final `isEditable()`，与 Konkrete 自定义的 `AdvancedTextField.isEditable()` 相撞。重映射前扫描游戏祖先方法，给这类独立模组方法及其字节码引用分配 `neoforbric$distinct$` 名称；保留游戏方法的访问 / final 规则和原有覆盖关系。Mixin 声明由 Mixin 专项重映射处理。回归测试加载实际转换后的父子类并验证两种方法的独立返回值及组合调用结果。

同一个 JAR 可以包含多平台描述文件，统一按 `neoforbric.mod.json → fabric.mod.json → META-INF/neoforge.mods.toml → META-INF/mods.toml` 选择第一份，只发现和加载一次，不合并各平台入口。Explorify 等同时携带 Fabric / Forge / NeoForge 描述的模组会选择 Fabric。选中描述文件无效时直接报错，不回退到其他平台；审计 `mod-discovered.details.descriptor` 记录选择结果。该优先级决定模组使用哪个适配器，不改变依赖版本检查或让尚不支持的 Forge / NeoForge 入口执行。

Fabric Loader 被动组件已升级到 **0.19.5**；构建依赖与两个 Fabric 探针共用根构建中的版本配置，默认 runtime、plain profile 和启动审计均从实际依赖的 `FabricLoaderImpl.VERSION` 获取内建身份。Mixin 0.17.4+mixin.0.8.7、ASM 9.10.1 和 MixinExtras 0.5.5 对齐 [Loader 0.19.5 的上游配置](https://github.com/FabricMC/fabric-loader/blob/0.19.5/gradle.properties)。默认 runtime 使用新版 `loadClassTweakers()` 读取已转换到 Mojang 的 AW，并通过 GameProvider 声明运行命名空间和转换范围。`fabricloader >=0.17` 不再被旧的 0.16.10 身份阻挡；更高且未满足的版本要求仍由解析器拒绝。

依赖升级同时更新 Gradle 锁文件和 `gradle/verification-metadata.xml`，包括 IDE 使用的 POM、sources 和 Javadoc。可用 `./gradlew.bat :loader:verifyIdeArtifacts :client-ui:verifyIdeArtifacts` 在正常校验模式下复核这些依赖。

Mixin 的 `org.spongepowered.asm.synthetic.*` 动态类（例如 `@ModifyArgs` 生成的 `Args$1`）由游戏域 G 的生成类提供器查询 Mixin 注册表后定义；其 Mixin 来源和最终字节码哈希写入审计。该命名空间禁止输入 JAR 直接定义，未注册的动态类仍拒绝加载；Mixin 自身 API 保持父域共享。

已选中的 nested 模组与根模组一起进入 remap 和类归属扫描。Fabric 解析器可能因依赖不满足而不选中可选的 nested 库；审计的 `fabric-runtime-excluded` 和 Mods 状态原因会列出其缺少或版本不满足的必需依赖。`named` / `mojang` AW 只有在所有目标类、成员名和描述符均能通过 Mojang 映射校验时才规范化为 `mojang`；其他命名空间与无法验证的符号继续拒绝。SSC 样本及缺失依赖说明见 [SSC 兼容性](ssc-compatibility.md)。

Loom 标记为 `fabric-loom:generated=true`、没有入口、语言适配器、Mixin 或访问规则的 nested 依赖库在 G 中独立加载，例如模组 ANTLR 4.13.1 与引导层 TOML 解析器所用的 4.11.1。根模组不获得此豁免；共享 API、受保护包和游戏域内部类冲突仍拒绝。Mixin 插件可以在 G 中链接游戏接口与辅助类，这些依赖经过完整转换流程，循环定义仍失败。LWJGL natives 路径在预启动入口执行前就绪。客户端构造器向 Mixin 提供标准 `Hooks.startClient(File,Object)` 注入锚点，Mixin 完成后由内核 API 分发初始化，确保 main/client 入口只执行一次并保留 owo 等模组的初始化后钩子。

Fabric 快照按 Java 21 选择 multi-release 类并移除 module 描述符；外部 manifest `Class-Path` 不会引入新输入。新版 `classTweaker` v1/v2 与传统 AW 都转换到 Mojang 命名。Mixin 引用映射表按配置声明选取，支持 `trender.refmap.mixins.json` 等非标准文件名，并转换 record 的 `comp_*` 成员及 Mojang 包内部类。

Loom `static` 模组的 Mixin 注解中已烘焙的 intermediary 选择器也转换到 Mojang，包括 `remap=false` 第三方目标里的嵌套 `@At`（ImmediatelyFast / Iris）。普通字符串常量与其他注解不改写，传统 refmap 模组保留注解键。游戏资源同时支持包目录扫描和 unnamed module 的 `Module.getResourceAsStream`，供 Framework 扫描及 Kotlin builtins 使用；二者读取同一份不可变快照，仍不回退到父域私有资源。

重映射的继承图先应用所有选中模组声明的 AW / classTweaker，使其与运行时可见性一致。原为 private、经 `extendable` 开放的方法能够向子类传播新名称（例如 Fusion 的 `SpriteContents.createAnimatedTexture`）；未声明开放的 private 方法保留独立关系。分析视图不会改写原始游戏 JAR，也不会提前执行入口。

传统 refmap 模组中显式 `remap=false` 的 intermediary 选择器同样按运行命名空间翻译（例如 YUNG’s API 的结构池权重钩子）；默认启用 remap 的注解仍保留 refmap 查询键。资源关闭时释放经资源 URL 打开的流、JarFile 与目录扫描遗留的 JDK 缓存句柄，再关闭 ZIP 文件系统，避免 Windows 上退出后仍锁住快照 JAR。

本地完整包的兼容修复备份保存在 `run/client/mod-backups`：JEI 19.51 更新到 Fabric 1.21.1 的 19.57.0.451 以满足 Polymorph 1.2.0 新 API；Beyond Adventures 三份动画的 20 个三维向量对象修正为数组；trorigins 删除一份名称无效、内容与有效文件完全相同的纹理元数据副本。修复记录包含原始与最终 SHA-256，除记录中的条目外其余 JAR 内容逐项校验不变。

首次运行下载锁定的官方客户端、映射、46 个 Java 库、Windows x64 natives、资源索引及完整资源对象。后续启动复核缓存。游戏、资源与库不提交到 Git。`audit.json` 位于游戏目录。

## 主菜单与模组列表

主菜单新增带图形图标的 `Mods` 按钮，保留原版菜单功能。按钮由固定 1.21.1 `TitleScreen.init` ASM Hook 添加，渲染代码位于独立 `client-ui` 模块，随 Minecraft 在游戏类加载器 G 中加载。

数据链为 `Discovery → Metadata → Resolver / profile admission → ModCatalog → LoadedMods.snapshot() → Mods Screen`。界面不会重新扫描 mods 目录或解析各生态元数据。列表显示名称、版本、ID、来源生态和状态；选中后的详情与悬浮说明显示运行适配器、命名空间和原因。

- **Source ecosystem**：Fabric、Forge、NeoForge 或 NeoForbric。
- **Runtime adapter**：例如 `NeoForbric Fabric Adapter` 或 `Native`；不支持执行的生态显示 `Unavailable`。
- **计划与执行**：未加载的候选在详情中显示 `Runtime adapter (planned)` 和 `Namespace (planned)`，表示预期适配路径，不表示已经执行或完成 remap。
- **状态**：Loaded、Disabled、Failed、Unsupported。内置 NeoForbric 表示当前内核运行；发现 Forge / NeoForge JAR 不意味着已加载。
- **原始来源**：保留发现时 JAR 路径、SHA-256 和图标字节快照，Fabric remap 后仍指向原始 JAR。

客户端 profile 把 side 排除的候选标为 Disabled，把尚无适配器的 Forge / NeoForge 及已识别但不支持的 Fabric 特性标为 Unsupported，不执行这些候选。活动模组依赖被跳过的候选时，依赖解析仍失败。无效元数据、入口失败、类冲突和运行崩溃仍终止实例；Failed 状态写入审计，失败实例不会强行进入菜单。

## Unsupported 诊断

鼠标悬停在 Unsupported、Disabled 或 Failed 列表项上即可查看原因。长提示会引导选中模组查看完整报告；右侧详情支持鼠标滚轮、拖动滚动条，获得焦点后可使用方向键、Page Up / Page Down、Home / End。较窄的窗口点击列表项进入详情，Back / Esc 先返回列表。

Fabric 准入会一次收集所有已识别的阻挡项，而不是遇到第一项就结束。报告保留字段和具体值，包括 Mixin 配置文件、AW 路径、nested JAR 路径、语言适配器、未实现的依赖规则、依赖数组、自定义 entrypoint 组以及方法 / 字段入口。例如：

```text
Unsupported features:
- mixins[0]: example.mixins.json
  Requires Fabric Mixin support
- accessWidener: example.accesswidener
  Requires Fabric access widening
Required dependencies (not evaluated):
- fabric-api: >=0.102.0
```

`Required dependencies (not evaluated)` 单独列出被拒绝候选的外部依赖要求，不能据此认定依赖缺失或该依赖本身不支持。通过准入的活动候选仍由 Resolver 校验依赖。报告不会执行候选类，也不会为了列出原因跳过无效元数据检查。

Loader 将每项诊断作为不可变的 `ModDiagnostic(kind, subject, value, explanation)` 列表发布到 `LoadedModInfo.diagnostics()`；界面使用这个已发布的状态和原因。审计中的 `mod-status.details.diagnostics` 保留同一列表的 JSON 字符串，`reason` 保留完整可读报告。Unsupported 表示已识别但当前拒绝执行，不能据此推断 JEI 等模组已兼容。

## 图标

优先显示原始 JAR 元数据声明的 PNG；缺失或无法解码时显示官方生态图标：Fabric 卷布、Forge 铁砧、NeoForge 狐狸。NeoForbric 使用本项目临时图形立方体标记，也用于 Mods 按钮和窗口。图标限制为文件 256 KiB、尺寸 512×512；损坏图片不改变模组状态。

官方素材已随代码保存，运行时不请求网站。固定来源、哈希、ICO 转 PNG 的说明和许可文件随 UI JAR 打包于 [图标来源说明](../client-ui/src/main/resources/META-INF/neoforbric-icons/NOTICE.md)。`tools/GenerateIcons.java` 只生成 NeoForbric 自有图标，不覆盖第三方素材。

## 实测命令与边界

```powershell
./gradlew.bat :loader:test :loader:minecraftClientTest
./gradlew.bat runClient -PclientModsProbe=true -PclientProbeFrames=40
./gradlew.bat runClient -PfabricPlainProfile   # 回归 plain Fabric entrypoint baseline
```

探针验证实际 OpenGL 窗口、资源加载、主菜单、Fabric main / client 各执行一次、注册物品身份和原生清理。Mods 专项点击实际按钮，验证 Loaded / Unsupported / Disabled 决策、完整结构化诊断、实际悬停提示中的配置名、详情实际滚动、渲染截图和返回主菜单后按钮无重复；失败探针验证 vanilla 崩溃仍回到内核审计。客户端测试截图保存在 `loader/build/client-evidence`（包括 `neoforbric-mod-diagnostics.png` 和 `neoforbric-mod-tooltip.png`）；运行任务截图在 `run/client/screenshots`。

完整 Fabric API、Forge / NeoForge 原生执行、资源包与物品模型接入、联网握手及三生态整合包兼容尚未实现。默认 Fabric runtime 目前验证固定 Fabric API / JEI 依赖链；JAR package sealing 由内核在重映射时保留并按 JVM 语义执行（sealed package 拒绝其他 archive 的类），Mixin 注入到已 sealed 包之外的生成类仍按源 archive 归属。
