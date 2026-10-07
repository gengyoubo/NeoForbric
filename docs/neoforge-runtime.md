# NeoForge 客户端首个实测

开发客户端已接入固定 **Minecraft 1.21.1 / NeoForge 21.1.244 / FML 4.0.43**。首个验证目标为原始 `ecologicalgarden-1.3.2.jar`，SHA-256 为 `256d62e641ab118686381ef9dbfc7f87b205db53932f53f08ed866d3982dcc54`。

## 运行

将 NeoForge 模组放入 `run/client/mods` 后运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21
./gradlew.bat runClient
```

`runClient` 检查模组描述文件，存在仅选中 NeoForge 描述的 JAR 时自动选择 NeoForge profile。多平台描述仍遵循 `neoforbric.mod.json → fabric.mod.json → META-INF/neoforge.mods.toml → META-INF/mods.toml` 的优先级。可用 `-PneoForgeProfile=true` 强制选择，或 `-PneoForgeProfile=false` 回到原来的 Fabric / vanilla 客户端路径。当前自动选择只作用于开发任务；发布安装器尚未接入 NeoForge 输入准备和桥接模块。

首次准备从[固定的官方安装器](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.244/neoforge-21.1.244-installer.jar)读取 profile，验证 SHA-256，下载其中带 SHA-1 的库，执行固定版本的官方客户端处理工具。补丁输出与未修改的 Mojang 命名类、额外资源合并，避免把增量补丁误当作完整游戏。缓存及校验计划保存在 `build/neoforge-client`，后续启动复核文件哈希。

## 加载机制

NeoForbric 继续拥有 JVM 入口、发现、准入、类索引、转换屏障及游戏类加载器 G。原生 ModLauncher 不启动、不创建第二份 Minecraft 定义。`neoforge-runtime` 在 G 中被动调用上游 FML 的元数据解析、依赖排序、类扫描、Java 语言提供器、ModContainer 构造与生命周期事件；注册、客户端初始化和世界事件使用真实 NeoForge API。

Minecraft、NeoForge 和用户模组的命名 module 绑定到同一个 G。module 类查询和资源查询仍读取已准入的不可变快照，原始 JAR 路径、哈希与来源生态保留在 Mods 列表和审计中。NeoForge 的 Access Transformer 通过固定上游 AT 引擎纳入定义前转换；两个内建 accessor Mixin 的行为以明确字段和方法锚点实现。构造或游戏启动失败会终止实例，模组只在主菜单就绪后标为 Loaded。

主菜单只保留 NeoForbric 的 Mods 按钮，移除 NeoForge 额外添加的按钮和布局行。统一 UI 按实际按钮排列主菜单，按钮高 20、行距 28，底部控制按钮另留间距，避免 NeoForge 的起始位置差异导致按钮重叠。

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

这是固定版本、单个目标模组的客户端实测，尚未验证任意 NeoForge 整合包或原生远程服务器互联。模组侧 Mixin、coremod、枚举扩展和其他语言加载器在执行入口前显示 Unsupported；nested JAR、签名和 multi-release 等布局仍受现有准入限制。NeoForge 内建 coremods 尚未接入统一转换管线，因此不能据此认定全部 NeoForge 行为已等价实现。

此 profile 可运行有限的 plain Fabric Java 入口探针，完整 Fabric Mixin runtime 与 NeoForge 补丁游戏的混装尚未实现。Forge 原生执行和 NeoForge 独立服务端也尚未接入。后续应逐项增加转换与行为探针，扩大实际可运行的模组范围。
