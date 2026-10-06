# JEI 1.21.1 实验性运行 profile

本阶段以 Fabric JEI 的实际物品列表、搜索和配方界面为验收目标。它使用真实发布 JAR 和 Fabric API 的原有实现；启动入口、输入快照、变换准备与唯一游戏类加载器仍由 NeoForbric 持有。

## 固定输入与运行

| 输入 | 固定版本 | 取得方式 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 现有 `prepareMinecraftClient` 与官方资源锁 |
| JEI | 19.57.0.451，Fabric | [发布版本](https://modrinth.com/mod/jei/version/Mpn2EPaS) |
| Fabric API | 0.116.17+1.21.1 | [发布版本](https://modrinth.com/mod/fabric-api/version/Mys3P7lK) |
| MezzConfig | 0.5.12 | JEI 声明的 nested JAR |
| Fabric Loader 被动组件 | 0.16.10 | Gradle 锁定依赖；不运行 Knot 的发现 / 启动 |
| Fabric Mixin | 0.15.2+mixin.0.8.7 | Gradle 锁定依赖 |
| Access Widener / MixinExtras | 2.1.0 / 0.4.1 | Gradle 锁定依赖 |

`loader/src/main/resources/org/neoforbric/minecraft/jei-inputs.json` 固定两份根 JAR 的下载 URL、SHA-512 与 SHA-256。`prepareJeiInputs` 校验下载和本地缓存，将输入放入 `build/jei-compat/mods`。MezzConfig 来自 JEI 的已校验快照，不额外下载另一个版本。

```powershell
$env:JAVA_HOME = '自己的 JDK 21 路径'
./gradlew.bat runJei                       # 交互客户端
./gradlew.bat runJei -PjeiVerify            # 独立世界中的实际功能验收，成功后退出
./gradlew.bat runJei -PclientProbeFrames=40 # 仅主菜单探针
```

测试实例位于 `build/jei-compat/run`。功能验收创建带随机后缀的新世界，避免读取或覆盖已有存档。资源和原版库复用已经校验的客户端准备目录。完整第一次运行保留 `prepareMinecraftClient` 依赖；本地调试只有在输入已准备且没有变化时才能使用 `-x prepareMinecraftClient`。

## 已接入的依赖链

1. NeoForbric 从不可变 JAR 快照递归发现**元数据声明的** nested JAR，限制深度 / 数量、检查路径和缓存哈希。签名 JAR 必须先通过 JDK 校验；重映射派生物移除已失效的签名。
2. Fabric 的被动元数据解析器与版本解析器处理版本谓词、别名、依赖数组、breaks 与 nested 候选选择。它们收到 NeoForbric 的候选图；不调用 Fabric 的目录发现器或 `load()` / `freeze()`。
3. 一次 TinyRemapper 符号图同时处理所选模组，避免跨模块继承引用缺少上下文。`Fabric-Loom-Mixin-Remap-Type: static` 使用静态注解转换；refmap 保留源注解键、转换已解析的目标值。AW 文件转换到 Mojang。冲突在原始快照与派生类索引两处检查；只允许完全一致的 `package-info` 共享。
4. Fabric Loader runtime API 使用同一所选图和自有 MappingResolver，保留任意 entrypoint group 的惰性实例化。Mixin service 明确为 `NeoForbric`，并校验 service 的实际所有权。
5. 唯一 `NeoForbric-Game` 定义 Minecraft、官方库、Fabric API 模块、JEI 与 MezzConfig。共享 Fabric Loader runtime API、`net.fabricmc.api` 入口 SPI、Mixin / ASM 类型属于父域。Mixin 也处理 DFU 等官方库与模组 accessor；注册过的生成类追溯到所属 Mixin 的已准入 JAR，并记录最终字节码哈希。
6. Fabric API registry-sync 模块会绕开原版 `BuiltInRegistries.bootStrap()` 的冻结路径。该 profile 在 Minecraft 构造函数的 `Thread.currentThread()` 调用前进入 main / client 初始化，此时目录与 User 已就绪；冻结仍由 Fabric API 的真实 Minecraft Mixin 路径执行。原有 plain profile 保持原版 createContents → 初始化 → freeze 顺序。

## 功能验收与证据

`JeiRuntimeProbe` 通过真实的 Fabric `END_CLIENT_TICK` 回调运行，创建集成服务端世界并等待 JEI runtime。随后检查物品数量、真实库存界面中的 overlay、橡木木板搜索、以物品对象创建 OUTPUT focus、crafting 配方数量，以及打开后的 JEI 配方 Screen。Minecraft ItemStack 和 JEI runtime 的定义类加载器必须一致。

成功时输出 `JEI_PROBE_OK` 并生成 `build/jei-compat/run/jei-probe.txt`，截图位于同目录的 `screenshots/neoforbric-jei-{items,search,recipes}.png`。主菜单探针的 `CLIENT_PROBE_OK` 单独出现不能替代这项功能验收。最终审计位于 `build/jei-compat/run/audit.json`，包含入口调用、注册窗口、Mixin 应用、生成类来源与正常退出记录。

## 支持边界

这是面向固定 Fabric JEI 依赖链的实验性客户端 profile。默认 `runClient` 仍采用有限的 plain Java 准入，不能因为另一个 profile 加入能力，就把所有未知模组标记为 Loaded。自定义 languageAdapters 暂未准入；Forge / NeoForge 原生入口、外部客户端协议兼容和三生态对象互操作仍未完成。此验收也不等于整个 Fabric API 的每项契约、任意 Mixin 冲突组合、JEI 所有插件和网络场景已通过。
