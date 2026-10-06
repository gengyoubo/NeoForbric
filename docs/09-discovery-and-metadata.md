# 模组发现、依赖求解与元数据

目标是加载**现有 JAR**，发现成功只是第一步。运行能力、语言入口和 ABI 检查也必须在实例化模组前接上，参见 [第三阶段总览](06-stage3-investigation.md)。

## 1. 元数据差异

| 项目 | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 核心文件 | fabric.mod.json | META-INF/mods.toml | META-INF/neoforge.mods.toml |
| 入口 | main / client / server / preLaunch 等 keyed entrypoint，支持 language adapter | modLoader / loaderVersion，JavaFML 等语言 provider 与 @Mod 扫描 | 语言 provider 与 @Mod 扫描；构造参数等按 FML 版本适配 |
| 版本 | SemanticVersion / 字符串版本与 Fabric VersionPredicate | Maven ArtifactVersion / VersionRange | Maven 范围与版本比较 |
| 硬依赖 | depends；breaks 表示硬冲突 | mandatory=true；缺失或不匹配报错 | type=required；incompatible 表示禁止组合 |
| 软关系 | recommends / suggests / conflicts，严重性不同 | mandatory=false 允许缺失，但存在时仍需匹配范围 | optional 允许缺失，但存在时检查范围；discouraged 为警告类关系 |
| 排序 | 求解器与入口顺序不能假装具有 TOML ordering 契约 | BEFORE / AFTER / NONE 与 side 约束 | BEFORE / AFTER / NONE 与 side 约束 |
| 侧别 | metadata environment 与 entrypoint 分别筛选 | dependency side、事件订阅侧别等各司其职 | dependency side、@Mod / subscriber 侧别等按 FML 修订解释 |
| 内嵌库 | jars 列表、嵌套候选与版本选择、provides alias | JarJar 元数据、坐标 / 范围选择、library / language provider 等文件类型 | JarJar 选择与候选 locator / discovery pipeline |

依据：[Fabric metadata parser][fl-metadata]、[版本谓词][fl-version]、[Fabric Resolver][fl-resolver]、[Forge ModInfo][f-modinfo]、[Forge ModSorter][f-sorter]、[Forge JarInJar locator][f-jij]、[FML 4.0.45 源码包][nfml-src]（ModInfo、ModSorter、moddiscovery）。

不能把 `^1.2` 直接当 Maven 范围，也不能将 `[1.2,2)` 拆成文本比较。Fabric 的 predicates 可以组合、版本还可能是非 SemVer 字符串；建议保留各生态原谓词求值器，仅统一候选和约束图。显示规范化版本时也保留原始字符串与原谓词。

**optional 不等于版本任意。** 固定 NeoForge ModSorter 对已存在 optional 依赖仍检查 version range；Forge 的 mandatory=false 也不能擅自解释为“安装了错误版本仍通过”。Fabric suggests 和 recommends 更不能一律转成强依赖。[FML 源码包][nfml-src]（ModSorter）、[Forge ModSorter][f-sorter]

## 2. 统一发现流水线建议

```text
外层 JAR / classpath / locator 候选
→ 记录来源和内容哈希
→ 递归发现声明的 nested JAR
→ 按原生态规则选择库版本和 mod 候选
→ 元数据 / 语言 provider / 物理侧过滤
→ 依赖约束求解 + ordering 图检查
→ 支持能力 / 映射 / 变换预检查
→ 固定实际类路径和资源视图
→ 服务发现 / preLaunch / 构造 / keyed entrypoint
```

实际原生服务发现可能早于 mod 图完成，特别是 ModLauncher 引导服务。图中最后一项只针对已经被统一内核接管的受支持服务；引导侧服务应单独建立 BOOT / PLUGIN / GAME 可见域，不能全部延迟到模组初始化。

Fabric `ModDiscoverer` 与 `ModResolver` 处理嵌套候选和版本选择；Forge JarInJarDependencyLocator 调 JarSelector，按 metadata 选择库，并根据父文件类型归类 library。把 ZIP 里的所有 JAR 直接展开进一个 classpath 会改变版本选择，制造重复类和重复入口。[Fabric Discoverer][fl-discoverer]、[Fabric Resolver][fl-resolver]、[Forge JarInJar locator][f-jij]

每个候选至少记录外层 JAR、内嵌路径、内容哈希、metadata 类型、mod IDs、provides / 坐标、版本范围、激活理由和淘汰理由。一份 TOML JAR 可以声明多个 mod；库文件也不一定有 mod ID。以“一个文件就是一个 mod”为基本单位会失去这些关系。

## 3. ordering、cycle 和混装约束

required 约束和执行 ordering 是两张关联但不同的图。双向 required 可能只表达“必须共同存在”，并不自动等于不能加载；双向 BEFORE / AFTER 构成的执行环则无法按原契约调度。固定 NeoForge ModSorter 用拓扑排序并报告 cycle。[FML 源码包][nfml-src]（ModSorter）

跨生态依赖应明确别名策略。不能因为提供 NeoForge 门面就自动宣称满足 `forge` 的任意版本要求；Forge mod 会依赖 Forge 特有 ABI。`fabricloader`、`fabric-api`、`forge`、`neoforge` 等内建身份和版本必须描述实际实现范围，不用虚构高版本骗过检查。

多生态 metadata 同时存在时，建议明确选定一个启动身份，并记录理由；不得把同一 multi-loader JAR 的多个入口都执行一遍。混装 mod ID 重复需区分同文件多声明、别名、同库副本与两个真正冲突的 mod。

## 4. 服务发现和 entrypoint 的边界

`META-INF/services` / module-info provides、JavaFML 语言 provider、ModLauncher ITransformationService、Fabric language adapter 和 keyed entrypoint 不是同一类入口。转换 JAR 时应保留相关资源并显式转换可映射的类名，不能只扫描 @Mod。受支持服务需要加载层、初始化时机、生命周期所有者和异常归属。

Fabric Hooks 调用 main 后再调用 client 或 server；preLaunch 由 Loader 更早的启动路径负责，不能挪到 main 后。entrypoint 还可使用自定义 language adapter，Kotlin 等语言依赖不是替换类名就能执行。[Hooks][fl-hooks]、[FabricLoaderImpl][fl-impl]

静态 metadata 筛侧不能替代类级侧别剥离与安全的首次加载。仅客户端的 shader / renderer 类出现在服务端扫描过程中时，应避免反射初始化；需要同时验证 annotation scan、服务和 Mixin plugin 的侧别。

## 5. 待执行探针

| 编号 | 场景 | 验收结果 |
| --- | --- | --- |
| S3-D01 | Fabric SemVer 谓词、非 SemVer；TOML 开闭区间与 qualifier | 与原求值器相同的选中 / 拒绝结果 |
| S3-D02 | optional 缺失、存在且范围内、存在但范围外 | 分别允许、允许、报告不匹配 |
| S3-D03 | mutual required 无排序环；BEFORE / AFTER 真正成环 | 两类图分别诊断，列出冲突边来源 |
| S3-D04 | 两外层 JAR 内嵌同库不同版本与不相交范围 | 原生选择规则、冲突解释、单次入口 |
| S3-D05 | provides、多个 mod IDs、双生态 metadata、重复 ID | 唯一激活身份与稳定来源追踪 |
| S3-D06 | custom adapter / language provider / 服务 / preLaunch | 时机与可见域；过早类定义可定位 |
| S3-D07 | 客户端 mod 放进专用服务器与 side 条件依赖 | 正确筛选且不加载客户端实现类 |

[f-jij]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator.java
[f-modinfo]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/moddiscovery/ModInfo.java
[f-sorter]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/ModSorter.java
[fl-discoverer]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java
[fl-hooks]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java
[fl-impl]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java
[fl-metadata]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java
[fl-resolver]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/discovery/ModResolver.java
[fl-version]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/util/version/VersionPredicateParser.java
[nfml-src]: https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar
