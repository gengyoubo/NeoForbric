# MixinExtras：包装链、表达式与局部结构

这里是 [05 映射与 Mixin](05-mapping-and-mixin.md) 的专项补充，不能用“普通 Inject 测试通过”覆盖。静态样本读取了 **MixinExtras common 0.4.1** 与 **neoforge 0.5.5** 的源码包；未加载或执行 injector。[0.4.1 源码包][extras041-src]、[0.5.5 源码包][extras055-src]

## 1. 四种功能的迁移要求

| 功能 | 原语义 | 结构迁移要保留什么 |
| --- | --- | --- |
| @WrapOperation | 包装指定 operation，handler 接收 receiver / args 和 Operation | 被包指令的准确身份、descriptor、receiver、调用链以及 original.call 的行为 |
| @ModifyExpressionValue | 修改选定表达式的产出值；多个 modifier 可以串联 | 表达式产出位置、类型、使用处；不能仅因同方法有相同返回类型就迁移 |
| @WrapWithCondition | 以 handler 返回条件控制指定 void operation 是否执行 | 精确副作用边界和其他条件包装组合；不能替换成整个方法 HEAD 取消 |
| @Local | 按类型与 ordinal / slot index / name 或隐式唯一性捕获局部值 | 在被修复注入点的活跃局部变量、类型、身份、argsOnly；LocalRef 还涉及写回 |

以上结论依据对应源码包中的注解、injector 与 LocalSugarApplicator。旧 / v2 WrapWithCondition 包路径必须保留原 JAR 的身份，不能只按注解简单类名识别。

## 2. WrapOperation 不是 Redirect 的同义词

WrapOperation 支持多模组在同一 operation 上形成包装链。`original.call(...)` 代表下一层 operation，不一定就是最内层 vanilla 指令；handler 可以改变参数、调用零次或多次，并改变结果。上游注解明确提示：不调用 original 可能让其他人的代码不执行。[WrapOperation 官方说明](https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation)、[0.4.1 源码包][extras041-src]（WrapOperation、WrapOperationInjector）

将它迁成普通 Redirect、或强制 original 只调用一次，会改变源语义。审计应记录 wrapper chain、执行层次与下游调用次数；“A 和 B 都附着”不能保证两者都在实际操作中运行。

比如 NeoForge Patch 把目标 INVOKE 移到 helper：需要重新定位包装的原 operation，并核对 receiver、参数及取消边界。把 handler 挂到 helper 的 RETURN 只能模拟少数值修改，通常不能完整模拟 Operation 的零 / 多次执行能力。

## 3. 表达式与条件

`ModifyExpressionValue` 必须修改原选择器选中的那个表达式值。Patch 若引入另一次相同调用、临时变量或提前返回，仅修改 ordinal 有时仍会选错数据来源。修复规则应同时核对生产指令、消费指令、控制流、descriptor 和所在 slice。

`WrapWithCondition` 省略的是局部 operation 的副作用，不是整个 enclosing method 后续行为。若 Patch 增加事务捕获或事件回调，跳过的操作和周围状态更新仍需在原 native 对照中检查。[0.4.1 源码包][extras041-src]（ModifyExpressionValueInjector、WrapWithConditionInjector）

0.5.x 还包含 expression 等扩展能力；本轮仅确认所下载包内容和主要结构风险，没有完成 expression DSL 全部语法与版本间迁移审计。首批可以显式限制这类功能，但发现时必须识别并报告。

## 4. @Local、LVT 和参数迁移

Local 的 implicit 模式要求选定类型只有一个候选；多个同型局部时会失败。ordinal 是**同类型局部序号**，不同于注入点指令 ordinal；index 是局部 slot；name 在混淆环境不可靠。`print=true` 是诊断并中止注入，不是正常兼容方式。[0.4.1 源码包][extras041-src]（Local）

方法移动或 static/instance 变化会影响 slot 0、参数位置、宽类型的双 slot、活跃范围和 frame。修复需要分析准确注入点的 locals；不能将 index 简单 +1 套到所有方法。LocalRef 的写回必须更新正确局部，不能创建一份值快照假装可变引用。

必须检查 class-retention 的 sugar 注解及 generated handler / helper；只读取 runtime-visible annotation 会漏 Local。Mixin / Extras 多阶段准备和 late injection 的状态也应记录，最终方法体以全部 post-processing 后为准。[0.4.1 源码包][extras041-src]（LocalSugarApplicator、SugarPostProcessingExtension、LateInjectionApplicatorExtension）

## 5. 运行时版本与所有权

一个统一游戏类路径使用一个实际协调的 Mixin / Extras runtime。内嵌 Extras 副本和服务协商按其初始化规则处理，不能简单删除所有嵌套库，也不能启动三份互不知晓的 injector 注册。

Loader 0.16.10 样本引用 Extras 0.4.1；NeoForge 当前固定分支属性声明 0.5.5。这不是证明任意老 mod 的 shaded 类、service、注解和 handler 自动兼容新 runtime，也不是可运行组合的锁定结果。[Loader build.gradle][fl-build]、[NeoForge 属性][n-properties]

审计需保存 mod 编译 / 声明版本（能取得时）、实际选中 runtime、来源 JAR、重复副本处理理由，以及每个 injector 的具体实现。避免“Extras 初始化了，所以所有 Extras 注解已兼容”的报告。

## 6. 待执行探针

| 编号 | 输入 | 必须比较 |
| --- | --- | --- |
| S3-X01 | 两个 mod 包装同一调用，其中一个不调用 original | 包装链、下游零次执行和副作用 |
| S3-X02 | original 调用两次、修改参数、修改结果 | 两次实际调用与后续控制流 |
| S3-X03 | Patch 额外添加同型 INVOKE / 移到 helper | 准确 operation 的迁移，而非仅数量正确 |
| S3-X04 | 两个 ModifyExpressionValue 串联，expression 中间值变化 | 修改的值来源、顺序与最终值 |
| S3-X05 | 多个 WrapWithCondition，附带周围状态变更 | 只跳过目标 operation；其他流程正确 |
| S3-X06 | Local implicit 歧义、同型 ordinal、slot、argsOnly、LocalRef | 候选与写回；歧义不得猜测 |
| S3-X07 | 0.4.1 / 0.5.5 声明与内嵌副本组合 | 单一 runtime、版本协商、晚注入和最终 frames |

[extras041-src]: https://repo.maven.apache.org/maven2/io/github/llamalad7/mixinextras-common/0.4.1/mixinextras-common-0.4.1-sources.jar
[extras055-src]: https://maven.neoforged.net/releases/io/github/llamalad7/mixinextras-neoforge/0.5.5/mixinextras-neoforge-0.5.5-sources.jar
[fl-build]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/build.gradle
[n-properties]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/gradle.properties
