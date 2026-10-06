# Minecraft 1.21.1 客户端与 Mods 列表

`runClient` 使用 NeoForbric 自主 bootstrap、Mojang 命名游戏 JAR、单一游戏类加载器和有限 Fabric Java 入口 profile，启动真实 Minecraft 窗口。平台暂限定 **Windows x64 / JDK 21**，需要桌面会话及可用 OpenGL 驱动。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21
./gradlew.bat runClient
```

游戏目录为 `run/client`，用户模组放在 `run/client/mods`。准备任务更新演示用 Fabric 客户端探针，不清空用户模组目录。账号是本地离线开发身份 `NeoForbricDev`，可用 `-PclientUsername=名字` 修改；此任务没有 Microsoft 登录流程。

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
```

探针验证实际 OpenGL 窗口、资源加载、主菜单、Fabric main / client 各执行一次、注册物品身份和原生清理。Mods 专项点击实际按钮，验证 Loaded / Unsupported / Disabled 决策、完整结构化诊断、实际悬停提示中的配置名、详情实际滚动、渲染截图和返回主菜单后按钮无重复；失败探针验证 vanilla 崩溃仍回到内核审计。客户端测试截图保存在 `loader/build/client-evidence`（包括 `neoforbric-mod-diagnostics.png` 和 `neoforbric-mod-tooltip.png`）；运行任务截图在 `run/client/screenshots`。

完整 Fabric API、Mixin / AW、Forge / NeoForge 原生执行、资源包与物品模型接入、联网握手及三生态整合包兼容尚未实现。Fabric client 入口当前在静态注册表冻结后、Minecraft 客户端构造前执行，不等同于完整 Fabric Loader 客户端生命周期。
