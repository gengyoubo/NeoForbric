# 来源、快照与复现

调查日期：2026-10-06（日本时间）。技术事实优先采用 1.21.1 源码或明确版本的官方文档；没有用论坛转述作为技术依据。

## 1. 固定源码快照

| 项目 | 检索 ref | 完整提交 SHA | 用途 |
| --- | --- | --- | --- |
| [MinecraftForge](https://github.com/MinecraftForge/MinecraftForge/tree/a11d1936c53f1f93787f239ff42cb8e93a1d037a) | `1.21.1` | `a11d1936c53f1f93787f239ff42cb8e93a1d037a` | Patch、加载与网络实现 |
| [NeoForge](https://github.com/neoforged/NeoForge/tree/a2d6402a3c1eec093aef7e7d10ac5145906c199e) | `1.21.1` | `a2d6402a3c1eec093aef7e7d10ac5145906c199e` | Patch、接口注入、生命周期与网络 |
| [Fabric API](https://github.com/FabricMC/fabric/tree/83c07162d88a703d15c8dae50cbbc12f4e9e63c3) | `1.21.1` | `83c07162d88a703d15c8dae50cbbc12f4e9e63c3` | 生命周期、交互、资源与网络 API |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader/tree/fda9a7b84f0898f57b46fbbfda58795e7fa31cef) | `0.16.10` | `fda9a7b84f0898f57b46fbbfda58795e7fa31cef` | 固定入口与 Knot 行为样本；非最新版建议 |
| [Connector](https://github.com/Sinytra/Connector/tree/5a2f668ad492c1586c279a197423f5a1d1a16509) | `1.21.x` | `5a2f668ad492c1586c279a197423f5a1d1a16509` | 1.21.1 兼容路径与 JAR 转换 |
| [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI/tree/c2cb2ae0db344bd2efcf2d4fc8770d298377faa0) | `1.21.1` | `c2cb2ae0db344bd2efcf2d4fc8770d298377faa0` | Fabric API 在 NeoForge 上的实现参考 |
| [Forbric 候选参考](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/tree/520d7aad11cc7c9b8c8278b5f541e4429e1f7623) | `main` | `520d7aad11cc7c9b8c8278b5f541e4429e1f7623` | 26.2 自主内核与测试方法参考 |

这些提交通过 GitHub API 解析后固定。分支以后发生变化不影响本文的引用；调查快照不表示它们构成一个已经验证的运行时组合。

## 2. 官方文档

| 来源 | 支持的事实 / 使用范围 |
| --- | --- |
| [Fabric Loader](https://docs.fabricmc.net/develop/loader/) | Loader 与 API 的职责区分；跨版本概念以目标源码补证 |
| [fabric.mod.json](https://docs.fabricmc.net/develop/loader/fabric-mod-json) | 入口、侧别和元数据概念；具体入口调用顺序以固定 Hooks.java 为准 |
| [Fabric mappings](https://wiki.fabricmc.net/tutorial:mappings) / [迁移 mappings](https://wiki.fabricmc.net/tutorial:migratemappings) | 开发映射与命名转换；本报告限定混淆版本 1.21.1 |
| [Forge lifecycle](https://docs.minecraftforge.net/en/1.21.x/concepts/lifecycle/) | 构造、注册、setup、并行事件与排队任务 |
| [Forge registries](https://docs.minecraftforge.net/en/1.21.x/concepts/registries/) | 延迟注册与注册事件 |
| [Forge access transformers](https://docs.minecraftforge.net/en/1.21.x/advanced/accesstransformers/) | 成员访问转换与 SRG 命名要求 |
| [Forge SimpleImpl](https://docs.minecraftforge.net/en/1.21.x/networking/simpleimpl/) | 背景参考，示例存在与目标源码不一致之处 |
| [NeoForge events，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/events/) | 两类事件总线、阶段、优先级、取消和侧别 |
| [NeoForge registries，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/registries/) | 延迟注册、查询时机与数据包注册表 |
| [NeoForge sides，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/sides/) | 物理侧与逻辑侧 |
| [NeoForge payload，1.21.1](https://docs.neoforged.net/docs/1.21.1/networking/payload/) | 类型注册、方向、协议阶段与处理线程 |
| [NeoForge capabilities，1.21.1](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/) | 行为查询、provider 与缓存失效 |
| [NeoForge attachments，1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/) | 数据附件与持久化；具体支持能力仍需按锁定运行时确认 |
| [NeoForge access transformers，1.21.1](https://docs.neoforged.net/docs/1.21.1/advanced/accesstransformers/) | 访问转换配置 |
| [Architectury API](https://github.com/architectury/architectury-api) | 多平台开发抽象的用途；未用于证明 1.21.1 混装能力 |

## 3. 本轮方法

1. 读取本地 `readme.md`，确认三条研究方向和 1.21.1 目标；检查仓库文件与工作区状态。
2. 访问官方文档，读取上游元数据；将对应 ref 解析成完整提交 SHA。
3. 通过 `https://api.github.com/repos/{owner}/{repo}/git/trees/{sha}?recursive=1` 获取完整 tree，确认 `truncated == false` 后统计 Patch 路径。
4. 从 `https://raw.githubusercontent.com/{owner}/{repo}/{sha}/{path}` 获取七个 Patch 与支撑 API / 配置样本，静态检查相关片段。文档保留调查结论与永久链接；未逐片段审计全部文件内容。
5. 将源码事实、推论 / 设计建议和待验证实验分开记录。没有下载安装游戏、执行上游安装器或运行上游测试。

Patch 统计的等价 Python 逻辑如下；`forge_paths`、`neo_paths` 是对应完整 tree 中 `type == "blob"` 的文件路径：

```python
forge = {
    p.removeprefix("patches/minecraft/")
    for p in forge_paths
    if p.startswith("patches/minecraft/") and p.endswith(".java.patch")
}
neo = {
    p.removeprefix("patches/")
    for p in neo_paths
    if p.startswith("patches/net/minecraft/") and p.endswith(".java.patch")
}
assert len(forge) == 642
assert len(neo) == 733
assert len(forge & neo) == 570
assert len(forge - neo) == 72
assert len(neo - forge) == 163
```

[upstream-snapshot.json](upstream-snapshot.json) 保存 ref、提交、完整 tree URL、统计结果和取样源码的 SHA-256。样本哈希针对下载内容解码后统一换行为 LF、编码为 UTF-8 的字节，供同一提交的内容复核；不代表发布 JAR 哈希，也不表示已审计样本内全部语义。

## 4. 版本与证据陷阱

- Fabric 当前通用文档可能覆盖更新的 Minecraft；本报告的具体类名、回调和注入位置优先引用 1.21.1 分支源码。
- Forge `1.21.x` SimpleImpl 页仍出现 `NetworkRegistry.newSimpleChannel` 等示例；本次目标源码采用 `ChannelBuilder`，不要把网页代码直接当作可编译的 1.21.1 实现。
- NeoForge 的 1.21.1 文档还会区分不同 `21.1.x` 修订；同一游戏版本不保证所有修订的注解或 API 一致。
- Connector 的实际目标分支是 `1.21.x`，其固定版本目录写明游戏为 `1.21.1`。主分支 / 默认页的最新状态不能替代此版本快照。
- Forbric 候选项目目前面向 26.2。它的架构描述和测试方法可以参考，版本相关实现与成功率不能外推到本项目。
- Patch 文件数、共同目标类数量、接口注入目标键数都是静态统计，不是已经完成的 Hook 数量、冲突数量或兼容率。

## 5. 映射与 Mixin 专项补充

补充调查同样于 2026-10-06 完成，详见 [专项文档](05-mapping-and-mixin.md) 与 [专项快照](mapping-mixin-snapshot.json)。前期快照保留原始调查范围，新增证据记录在专项快照中。

| 补充来源 | 固定版本 / 提交 | 用途与限制 |
| --- | --- | --- |
| [SpongePowered Mixin](https://github.com/SpongePowered/Mixin/tree/4053421aa10aaac6127d969028a29c94fe3054f6) | `0.8.7` 分支，`4053421aa10aaac6127d969028a29c94fe3054f6` | 注入次数、plugin、Redirect 竞争、导出 / 校验；不是 Fabric fork 的完整替代性证明 |
| [Sinytra Adapter](https://github.com/Sinytra/Adapter/tree/38d859495426fadb5db8b52afdb88deaa7132322) | `1.21.x` 分支，`38d859495426fadb5db8b52afdb88deaa7132322` | clean / dirty 结构对照、ordinal 与目标迁移；未核对其与 Connector 发布依赖的二进制一致性 |
| [Mixin 注入点参考](https://github.com/SpongePowered/Mixin/wiki/Injection-Point-Reference) | 官方 Wiki，检索于调查日 | target、ordinal、slice / 指令查询的概念；准确要求用固定源码补证 |
| [Mixin Callback Injectors](https://github.com/SpongePowered/Mixin/wiki/Advanced-Mixin-Usage---Callback-Injectors) | 官方 Wiki，检索于调查日 | `require` / `expect` 与 injector group 背景 |
| [Mojang 1.21.1 元数据](https://piston-meta.mojang.com/v1/packages/22a1966494dfa4eeb5ee778c8e6ed5b774839582/1.21.1.json) | `client_mappings` 内容对象 `2244b6f072256667bcd9a73df124d6c58de77992` | 官方类 / 成员映射与 Java 21 要求；下载 SHA-1 已核对；未解析 server mappings |
| [Intermediary 映射](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar) | `1.21.1`，v2 | 原始混淆名与 Intermediary 对应 |
| [Yarn 映射](https://maven.fabricmc.net/net/fabricmc/yarn/1.21.1+build.3/yarn-1.21.1+build.3-v2.jar) | `1.21.1+build.3`，v2 | 固定 Yarn named 输入 |
| [MCP config](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146.zip) | `1.21.1-20240808.132146` | `joined.tsrg` 原始 SRG 输入，不能直接等同于部署产物的完整命名 |
| [TinyRemapper 源码包](https://maven.fabricmc.net/net/fabricmc/tiny-remapper/0.10.4/tiny-remapper-0.10.4-sources.jar) | `0.10.4` | Loader 固定依赖样本的命名转换与 MixinExtension；未调用工具转换 JAR |
| [mapping-io 源码包](https://maven.fabricmc.net/net/fabricmc/mapping-io/0.5.0/mapping-io-0.5.0-sources.jar) | `0.5.0` | Loader 固定依赖样本的映射格式读取；本轮核对脚本直接解析文件，并未调用该库 |

复核映射样本的方法：按专项快照中的 URL 下载输入并核对 SHA-256；从两个 Fabric JAR 读取 `mappings/mappings.tiny`，从 MCP ZIP 读取 `config/joined.tsrg`；通过同版本原始混淆类 / 方法与完整描述符连接三份映射，Mojang ProGuard 文件用于核对可读名称。已检查三个类及 `ItemStack#getCount()I`，没有声称完成全量合成。

专项快照区分发布映射 / 工具源码包的原始下载字节哈希和 GitHub 源码的 UTF-8 / LF 哈希。补充源码检查仍以相关片段为单位；没有下载游戏 JAR、运行安装器、编译夹具或测试真实 Mixin。

## 6. 第三阶段：运行契约调查

同日补充 [第三阶段总览与 11 个方向](06-stage3-investigation.md)，使用此前固定的上游 SHA，并补查 ModLauncher、FML、AT、Coremods、Bus、AW 和 MixinExtras 的版本化源码。详细引用与复现方法在 [第三阶段来源索引](stage3-sources.md)，哈希、声明版本差异、取样范围和待执行探针在 [stage3-snapshot.json](stage3-snapshot.json)。

Forge 四个精确版本源码包下载返回 HTTP 403，采用对应版本系列的固定 Git 提交作为机制证据，未证明二进制一致性。NeoForge 构建属性与 FML POM 的 Bus / Coremods 等版本有差异，补查了两种声明输入，但未运行完整依赖解析。第三阶段同样没有游戏、转换器、存档或连接实测；所有新探针均为待执行。

## 7. 最终验证专项：互操作与 Bootstrap

同日补充 [17](17-cross-ecosystem-interoperability.md) 与 [18](18-bootstrap-and-classloading.md)。继续使用此前固定的 Fabric Loader / API、Forge、NeoForge 提交与 FML 4.0.45 / ModLauncher 11.0.5 源码样本；新增核对入口、classloader、config、commands / permissions、biome modifier 和对象继承结构。新增来源和 29 项待执行探针记录在 [bootstrap-interoperability-snapshot.json](bootstrap-interoperability-snapshot.json)，不改写前三份调查快照的范围。

| 新增来源 | 固定输入 | 用途与限制 |
| --- | --- | --- |
| [BootstrapLauncher](https://maven.neoforged.net/releases/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2-sources.jar) | `2.0.2` 原始源码包 | bootstrap layer、TCCL、Consumer 服务移交；没有运行原启动链 |
| [SecureJarHandler](https://maven.neoforged.net/releases/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8-sources.jar) | `3.0.8` 原始源码包 | module、signing、filesystem 机制；不是已构建的 NeoForbric classloader |
| [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin/tree/8e016c8109414898c4b45b9b7ab738da1ee14f5a) | tag `1.12.3+kotlin.2.0.21` 的固定 SHA | object / 成员入口、kotlin-reflect 与调用方类加载视图；未运行语言模组 |
| [KotlinForForge](https://github.com/thedarkcolour/KotlinForForge/tree/0e8e3b579661acdad6ff76b9620dcc06f8885cab) | `5.x` 固定 SHA | 两套语言 SPI / container / 自动订阅；没有对发布 JAR 与实际依赖组合验收 |
| [Scala language provider / SLP](https://github.com/Kotori316/SLP/tree/3ae2ac491e94ca66586414e376197676ecd87111) | 2024-08-31 提交，显式目标 `1.21.1 / Forge 52.0.9 / NeoForge 21.1.34` | MODULE$、构造、context / module；后来 1.21.x 分支样本不用于证明 1.21.1 |
| [GroovyModLoader](https://github.com/GroovyMC/GroovyModLoader/tree/4e9c837937796ca192f1eb8ccbac9dc62e5f0d0f) | tag `6.0.2`；构建声明 `NeoForge 21.0.14-beta / gml-core 7.0.3` | 外层模组不含完整语言实现，需查 core；不能把外层兼容标签当实际 1.21.1 验收 |
| [gml-core](https://repo.maven.apache.org/maven2/org/groovymc/gml/gml-core/7.0.3/gml-core-7.0.3-sources.jar) | `7.0.3` 原始源码包 | language loader、container、生成订阅及带禁用代码的脚本路径；不是承诺脚本可运行 |
| [JVMS Java 21](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html)、[ClassLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)、[ServiceLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html)、[JAR 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html) | 官方 Java 21 规范 / API，检索于调查日 | 类型身份、委派、加载约束、服务与 archive / module 行为；架构决策仍需夹具验证 |

NeoForge 当前 1.21.1 config 文档及固定源码采用实例 config + 已存在世界文件覆盖的 SERVER 路径，与本次 Forge 世界 serverconfig 基路径不同。准确兼容应锁所选 `21.1.x` 修订和 FML 产物；不能依生态名字套用一条路径规则。语言库及构建声明也只是来源样本，不是完整运行依赖锁。

复核方法：快照 `source_files` 按 URL 下载，统一 UTF-8 / LF 后核对 SHA-256；`source_artifacts` 核对原始源码包字节哈希，再检查 `inspected_entries`。来源收集可能包含未逐行审计的文件，取样不能外推为全量审计；没有下载游戏 JAR、构建启动器、运行签名 / 模块夹具或测试语言加载。本轮建议结束通用调查，转入原型；29 项探针状态全部为 pending。
