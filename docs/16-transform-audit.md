# Mixin / Transformer 冲突诊断与 Audit 草案

用户第 11 项尚未列出后续字段；这里根据前两轮和第三轮证据补充建议。**这是设计文档，没有已实现的 NeoForbric audit system。**

## 1. 记录粒度与状态

最小单位应同时包含“输入 JAR”“变换步骤”“目标类 / 成员”“注入器 / wrapper”“运行调用”，通过稳定 ID 相连。一个异常堆栈、Mixin export 或最终成功计数不能代替这组记录。

建议状态：discovered、selected、rejected、planned、rewritten、prepared、attached、defined、executed、behavior_verified、unsupported、failed、unknown；optional_skipped 和 equivalent_by_rule 独立记录，不能自动并入成功。没有执行证据时保留 unknown。

这些状态不是单向无分支流水线：动态生成 class、Mixin plugin 条件排除、可选目标和 late injector 都可能改变计划。每次改变保存原计划与理由，而不是覆盖旧记录。

## 2. 至少记录的字段

| 领域 | 最少字段 | 能解释的问题 |
| --- | --- | --- |
| 环境 | game / Java / Loader / Mixin / Extras 版本，物理与逻辑侧、启动 profile | 同为 1.21.1 但修订 / side 不同 |
| JAR 与发现 | 外层 / 嵌套路径、SHA-256、原 metadata、mod IDs、语言 adapter、候选选择理由 | 错版本库、重复入口、错误侧别 |
| 类定义 | class loader / layer、来源、load reason、首次请求者、定义时间 | 提前加载造成 AW / Mixin 迟到 |
| 映射 | source / target namespace、mapping hash、原 owner/name/desc 和转换后引用 | 名称修复与结构缺失的区分 |
| 基底结构 | game input / patch hashes、父类、接口、关键字段 / 方法的前后指纹 | ABI 由哪个步骤建立或破坏 |
| 访问规则 | AW / AT 原行、owner、作用域、flags 前后、合并 / 冲突理由 | final、访问、调用分派问题 |
| 排序 | step ID、service owner、BEFORE / target vote / AFTER、约束边、实际序号、vote | 实际顺序与配置预期不同 |
| 结构修复 | rule ID / version、clean / actual view hashes、候选、唯一性、改写 diff、拒绝原因 | ordinal 猜错、helper 移动不完整 |
| Mixin | config、plugin 决策、priority、injector、selector、slice / ordinal / shift、require / expect / allow / group | 目标缺失、可选排除、注入数不足 |
| 局部与 Extras | 注入点 frame、local 类型 / slot / live range、候选、wrapper chain、Operation 关系 | 捕获错值、wrapper 吞下游 |
| 输出类 | 每一步 bytes SHA-256、结构 fingerprint、final bytes、frame / linkage 检查 | Mixin export 之后被改坏 |
| 运行行为 | invocation / parent ID、线程、阶段、handler 执行、取消 / 返回 / 结果变化、副作用 | 附着但没有执行或行为错误 |
| 注册 / 数据 | registry key、Holder 绑定、冻结状态、world / connection generation、复制 / save / sync | 不合法窗口、旧 Holder、数据泄漏 |
| 连接 | peer profile、状态、频道版本 / flow / optional、task / ack、registry digest、失败条件 | 断在握手还是内容解码 |
| 验收 | 原生 baseline ID、探针 ID、输入和观察值、比较结果、证据文件 | 可复现的行为结论 |

可以常态记录紧凑摘要，失败时导出局部 bytecode / frame / diff；不要求每次默认保存所有游戏 class。行为采样或探针未开启时，记录“没有观察”，不能因零次日志推断 NEVER_RUNS。

## 3. 冲突分类

| 类型 | 示例 | 建议诊断 |
| --- | --- | --- |
| 命名 | refmap 指向错误 namespace | 原 / 新引用与 mapping 输入 |
| ABI | added interface 在，实际 default 依赖成员缺失 | 缺成员及提供 ABI 的 rule / patch |
| 时机 | 类先定义，后读取 AW；冻结后写 registry | 首次定义 / freeze 轨迹与调用者 |
| 顺序 | 两个 transformer 的输入假设相反 | 冲突约束边与实际步骤 |
| 目标竞争 | Redirect 争同指令、Overwrite 覆盖方法 | 竞争者、priority、源码契约和获选结果 |
| 定位歧义 | 新增同型 INVOKE / local | 所有候选与拒绝猜测原因 |
| 附着后破坏 | 后置 transformer 删除 helper 或不可达路径 | 最早破坏的步骤与 final 方法体 |
| 包装行为 | WrapOperation 未调用 original，后续层不执行 | wrapper 层次与实际 call 次数 |
| 语义 | 取消位置晚于扣耐久或广播 | 原生与统一行为轨迹差异 |
| 协议 / 存档 | 缺网络 codec、错误 registry generation / NBT key | 状态、数据来源与实际失败层 |

冲突不一定导致异常；两个注入都 attached 但一个把另一个的效果覆盖也需要行为探针发现。

## 4. 现有机制可复用什么

ModLauncher 的 TransformerAuditTrail 已记录 reason、plugin 与 transformer activity；ClassTransformer 在实际应用时加入记录。它可以作为排序证据来源，但没有替 NeoForbric 定义跨生态注册、持久化或网络行为验收。[ModLauncher 源码包][ml-src]

活动记录也不一定等于成功。Coremods 样本会在包装器内部捕获脚本异常并返回 node，需把脚本日志 / 失败回调和实际前后结构接入 audit；若只能观察到返回而没有足够证据，状态应保持 unknown。见 [变换专项](08-access-and-transformers.md)。

Mixin 的 export / verify / injection count 与 error handling 可用于目标和结构证据。`expect` 的 debug 检查与 `require` 的强制下限需要分别记录；export 可能早于后续 transformer，必须另存 final bytes。Connector / Adapter 的 clean / dirty 对比和 audit 思路见 [05](05-mapping-and-mixin.md)，不能把改写被接受当行为已证明。

MixinExtras 的多阶段与包装链需要扩展这些审计字段。Local print 会中止注入，只能用于诊断。见 [Extras 专项](13-mixinextras.md)。

## 5. 建议的最小记录样例

以下 JSON 仅示范格式，**不是实际运行结果或真实模组**：

```json
{
  "schema_version": 1,
  "example_only": true,
  "run_id": "example-run",
  "step_id": "repair-001",
  "owner_mod": "example_mod",
  "target": {"owner": "example/Game", "name": "use", "descriptor": "()V"},
  "phase": "pre_mixin_repair",
  "source_namespace": "intermediary",
  "target_namespace": "mojang",
  "status": "failed",
  "rule_id": "move-operation-to-helper/v1",
  "candidate_count": 2,
  "reason_code": "AMBIGUOUS_OPERATION",
  "required": true,
  "attached_count": null,
  "execution_count": null,
  "behavior_verified": false
}
```

正式 schema 应要求真实 hashes 和 input / output artifact refs；不允许用示例字符串替代缺失哈希。记录 schema、规则和探针版本，后续报告才能比较同一含义的数据。

## 6. 验收门槛与待执行探针

必要目标定位歧义、无法满足的 ordering、必要 ABI 缺失应在定义或初始化前尽可能失败；运行才可知的语义缺失，在首次遇到时明确报告。可选功能只按源声明跳过，并保留影响范围。

| 编号 | 注入故障 | 期望审计 |
| --- | --- | --- |
| S3-A01 | 错 namespace 与成员在 Patch 后消失分别发生 | 区分 mapping 与 ABI / structural failure |
| S3-A02 | 同型候选两个；换 transformer 次序后唯一 | 候选、顺序与 cache 失效理由 |
| S3-A03 | attached 后 helper 删除 / 不可达 | final 输出与破坏步骤；没有行为成功结论 |
| S3-A04 | optional plugin decline 与 require 不足 | 不把合法跳过当成功或将必要失败吞掉 |
| S3-A05 | wrapper 附着但吞下游、重入事件 | 实际链、执行次数和 parent call ID |
| S3-A06 | 注册太晚、网络配置 ack 超时、旧 Holder 用于新世界 | 各领域上下文与失败层可定位 |

审计最小闭环应在首个原生态 JAR 实验前实现，而不是到混装崩溃后才补日志。

## 7. 启动与互操作补充记录

[18](18-bootstrap-and-classloading.md) 补充定义屏障和按提交点判断恢复范围；[17](17-cross-ecosystem-interoperability.md) 补充同对象验收。建议在上述记录上增加以下字段，仍未实现：

| 记录 | 新增字段 / 用途 |
| --- | --- |
| 类身份与加载请求 | binary name、defining / initiating loader ID、module、CodeSource、TCCL、请求阶段 / 栈、正在执行的 transformer 与准备 / 定义状态；发现提前 define、双份 API 和重入 |
| archive / 服务选择 | outer archive、nested path、原始哈希、版本约束 / 淘汰原因、最终 owner、provider SPI 与来源、资源枚举顺序；解释 JiJ 与 ServiceLoader 冲突 |
| 失败与恢复 | severity、phase、commit_point、state_tainted、recoverability、原 / 有效模组集合、required 依赖闭包、禁用 feature / rule、最后成功定义及世界会话；区分静态排除与不可恢复变更 |
| 跨生态对象 | audit 分配的对象编号、调用前后状态、registry owner / epoch、内容 key、provider / 数据所有者、copy / invalidate / save / sync 操作；不能只凭 identityHashCode 或 key 相等判断身份 |

这些字段用于 B / I 探针关联，不能把仅有记录的操作标成行为通过。必需转换返回成功但内部异常 / 后置条件无法确定时保持 unknown，并按 18 的建议阻止不完整实例启动。

[ml-src]: https://maven.neoforged.net/releases/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5-sources.jar
