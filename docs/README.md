# NeoForbric 前期调查

调查日期：2026-10-06（日本时间）。目标：Minecraft Java Edition **1.21.1**。

根据 [项目 README](../readme.md) 调查生命周期统一、Minecraft 基础代码与映射统一、Forge / NeoForge Patch 的 Hook 化，随后补充映射与 Mixin、第三阶段的 11 个兼容领域，以及跨生态互操作 / 自主启动两个最终验证专项。调查结果来自官方文档、固定源码与版本化源码包的静态检查和映射文件核对。调查已收口，仓库已有 Java 21 启动内核、夹具，以及真实 Minecraft 1.21.1 的有限 Fabric Java 入口 / 静态物品注册实测；运行边界见 [Minecraft 实测说明](minecraft-bootstrap.md)。完整原生态整合包兼容尚未验证。

## 主要结论

1. **统一生命周期与公共 API 有可行性。** 需要先定义阶段、线程、注册窗口、取消结果与资源重载契约，再让三个生态的适配器接入。
2. **统一映射可以减少源码维护，但不能自动统一运行行为。** 既有模组仍可能依赖 Forge / NeoForge 新增的方法、接口、父类、状态和网络协议。
3. **Patch 应按功能拆解。** Tick、世界加载等插入点可以作为 Hook 候选；能力查询、数据附件、伤害流水线、渲染与协议扩展需要专门的服务或结构转换。
4. **一个实例应由一个内核拥有类定义、注册表和加载阶段。** 三个适配器不能各自再启动完整的原生加载器。一个同名 Minecraft 类只能有一份供模组共同使用的实际定义。
5. **“开发 Common Mod”与“兼容现成 JAR”是两层工作。** 前者是统一 API；后者还要实现原生态入口、元数据、ABI、Mixin、访问转换和行为契约。Common Mod 原型通过并不代表已有三端模组可以直接混装。
6. **映射与 Mixin 兼容是统一内核的前置工作。** 名称、refmap 和访问规则转换之后，还要处理注入位置、局部变量、取消语义和多模组冲突；注入附着与行为一致分别验收。
7. **统一注册服务必须保留源 API 的合法窗口。** 直接写入后的可见性、Deferred supplier 绑定、Holder 身份、冻结和世界动态内容分别处理；跨生态立即查询依赖不一定存在可行顺序。
8. **原生客户端连接需要完整协议与内容兼容。** 通道 optional、payload 注册或 mod list 相同都不是充分条件；Fabric 与 NeoForge 客户端分别列出条件，目前均未连线测试。
9. **启动接管必须覆盖 main、服务选择、loader 创建和实际 define。** 原 Knot / ModLauncher 都主动创建游戏类加载器；复用内部组件需要明确宿主服务和 module 契约，不能只替换入口名称。
10. **跨生态互操作要验证同 Item / ItemStack / 世界 Holder 与状态。** Forge / NeoForge 的具体父类、provider 和 config 路径仍有差异；同名 API 或三个入口都执行不是互操作证据。

这些是本次调查形成的建议，尚未作为项目最终架构决策。

## 阅读顺序

| 文档 | 内容 |
| --- | --- |
| [当前原型与运行](prototype.md) | 已实现的启动 / 类加载机制、运行命令、25 项测试、准入边界与下一步 |
| [Minecraft 启动与物品注册实测](minecraft-bootstrap.md) | 真实 1.21.1 JAR、映射、Fabric Java 入口、注册窗口、31 项测试与运行边界 |
| [持续服务端](minecraft-server.md) | runServer、世界 / Tick、保存重载、正常停止与失败处理 |
| [客户端与 Mods 列表](minecraft-client.md) | runClient、窗口 / 资源、来源生态与运行适配器、Loader 最终状态和官方图标 |
| [NeoForge 客户端首个实测](neoforge-runtime.md) | 固定 NeoForge 21.1.248、被动 FML 宿主、EcologicalGarden 注册 / 世界验证与当前边界 |
| [Forge 模组运行适配](forge-runtime.md) | 固定 Forge 52.1.0、单 G 原生 FML 状态机、JarJar / AT / Mixin / 版本矩阵、内部模组与 JEI 无界面验证，以及 JEI 客户端主菜单实测 |
| [01 — 可行性与参考项目](01-feasibility.md) | README 三个方向的判断；Forbric、Connector、Architectury 的边界 |
| [02 — 生命周期、类加载与映射](02-runtime-model.md) | 三端差异、统一模型草案、注册与网络约束 |
| [03 — Patch 审计](03-patch-audit.md) | 全量文件计数、七个 Patch 样本、Hook 化边界 |
| [04 — 原型路线与验收](04-prototype-roadmap.md) | 分阶段交付物、原生对照、失败条件与待决问题 |
| [05 — 映射与 Mixin 兼容专项](05-mapping-and-mixin.md) | 1.21.1 真实映射、Connector 修复案例、转换管线和最小验证矩阵 |
| [06 — 第三阶段总览](06-stage3-investigation.md) | 11 个方向的结论、优先级、依赖和证据边界 |
| [07 — 注册表](07-registries.md) | 合法窗口、冻结、Holder、动态内容与同步 |
| [08 — 访问与 Transformer](08-access-and-transformers.md) | AW / AT、接口 ABI、构建 Patch 与运行变换顺序 |
| [09 — 发现与元数据](09-discovery-and-metadata.md) | 版本谓词、依赖、侧别、嵌套 JAR、服务与入口 |
| [10 — 事件语义](10-event-semantics.md) | priority、cancel、结果、并行、队列、线程和重入 |
| [11 — 对象数据](11-data-lifecycle.md) | capability、attachment、component、复制、存档与同步 |
| [12 — 网络协议](12-network-protocol.md) | 登录 / 配置 / Play、协商任务与原生客户端连接条件 |
| [13 — MixinExtras](13-mixinextras.md) | 包装链、表达式、条件和 Local 迁移 |
| [14 — 客户端渲染](14-client-rendering.md) | 渲染阶段、模型、shader、GUI、输入、particle 和扩展 |
| [15 — 数据与资源](15-resources-and-datagen.md) | runtime reload、动态 bootstrap、client resources 与 datagen |
| [16 — Audit 草案](16-transform-audit.md) | 变换和行为记录字段、冲突分类、失败验收 |
| [17 — 跨生态对象互操作](17-cross-ecosystem-interoperability.md) | 同对象 / 数据、父类冲突、config、命令权限、worldgen 与 14 项待执行探针 |
| [18 — Bootstrap 与类加载](18-bootstrap-and-classloading.md) | 启动屏障、类 / module / library / 服务边界、语言、失败与升级策略、15 项待执行探针 |
| [来源与复现说明](sources.md) | 固定提交、官方文档、统计口径和版本陷阱 |
| [机器可读调查快照](upstream-snapshot.json) | 提交 SHA、源码样本哈希、Patch 统计；不是构建依赖锁文件 |
| [映射与 Mixin 专项快照](mapping-mixin-snapshot.json) | 映射文件哈希、核对样本和补充源码快照 |
| [第三阶段来源索引](stage3-sources.md) | 固定引用、源码包、版本解析缺口与复现方法 |
| [第三阶段快照](stage3-snapshot.json) | 来源哈希、声明版本差异与 70 项待执行探针 |
| [最终验证调查快照](bootstrap-interoperability-snapshot.json) | 新增固定来源、源码包哈希、版本缺口与 29 项待执行探针；不是运行锁 |

## 当前建议

通用前期调查在 17 / 18 两篇收口。[首个原型](prototype.md) 已执行 Java 21 发现 / owner / 定义屏障与同对象夹具，[Minecraft 实测](minecraft-bootstrap.md) 已接自主 main、有限 Fabric Java 入口和原版 Item 注册；后续已接 [持续服务端](minecraft-server.md) 与 [客户端和 Mods 列表](minecraft-client.md)。接下来闭环 Mixin / AW，再扩展 Forge / NeoForge 入口与三模组互操作；只针对实际失败补局部调查。29 项研究探针的完整验收仍待执行，语言 provider 和完整原生启动组件没有已验证支持声明。

早期可用三端原生环境验证统一 API 的契约，但它们是实验对照。最终的“统一底层运行模型”仍需要 NeoForbric 自己拥有启动、类定义与调度，详见 [原型路线](04-prototype-roadmap.md)。
