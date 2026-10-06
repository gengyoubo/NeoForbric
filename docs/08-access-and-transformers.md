# AT、AW、接口 ABI 与变换排序

接续 [映射与 Mixin 调查](05-mapping-and-mixin.md)。本文查到的是明确版本的源码机制；**没有实际启动插件排序日志**。精确包与版本系列源码的区别见 [来源索引](stage3-sources.md)。

## 1. 四种修改不能合并成“访问放宽”

| 机制 | 改什么 | 对现有 JAR 的约束 |
| --- | --- | --- |
| Fabric AW | class / method accessible、extendable，field mutable；namespace 与 descriptor 明确 | 除 flags 还要检查方法分派、内部类访问及调用指令；transitive 前缀有依赖传播含义 |
| Forge / NeoForge AT | public / protected / default / private 与 final 增减；有通配规则 | 按生态 namespace 转换 owner / member / descriptor；逐条保留来源与合并结果 |
| injected interface | implements、泛型签名及接口可用方法 | 改变类型系统和 ABI；需要实际实现或 default 方法所依赖的宿主成员 |
| structural repair | 父类、字段、方法、调用点和注入元数据迁移 | 必须针对已知输入指纹与行为契约，不能把任意改结构当通用修复 |

AW 2.1.0 的 `AccessWidener` 与 `AccessWidenerClassVisitor` 表明：accessible 与 extendable 不是同一个 final 规则，私有方法变 public 时还要保护原分派语义；visitor 处理有关 invokespecial / handle 的转换。仅把 AW 文本翻成 `public-f` AT 会丢失约束。它不为接口增加业务实现。[AW 源码包][aw-src]

AT 允许表达 final 的增减，也并非全部都是单调放宽。合并多份规则不能自行“取最宽、永远去 final”；应先按原生态引擎求值，再对跨生态矛盾给出明确策略或拒绝。尤其虚方法覆盖与 final 冲突需要检查子类。[Forge AT 文档](https://docs.minecraftforge.net/en/1.21.x/advanced/accesstransformers/)、[NeoForge AT 引擎源码包][nat-src]

## 2. NeoForge injected interfaces：构建与运行的区分

固定 NeoForge 源码明确写出构建顺序：NeoForm 反编译输入 → 对源码应用 AT → interface injection → source patches → 编译；生成生产 source patches 时，从**没有单独 interface injection 的 AT 后源码**与最终 patched 源码做差，再生成发布用 binary patches。`TransformSources` 把 injected-interfaces.json 传给 JST 的接口注入选项。[NeoDevPlugin][n-devplugin]、[TransformSources][n-transources]

因此此 JSON 在这条上游构建链中是独立的结构输入，最终生产结构通过发布 Patch / 产物体现。**不能据 JSON 在 META-INF 就断言 FML 在每次类加载时读取它并再次注入。** 本轮没有发现可据以建立该结论的运行读取路径。

`injected-interfaces.json` 例如为 Entity / ItemStack 指定扩展接口；Entity 同时受 AttachmentHolder 父类等 Patch 影响。接口中的 default 方法还会调用真实宿主成员或进行宿主类型转换。给 class.interfaces 加一个字符串，只能解决部分类型关系，不能确保方法体、状态、父类和初始化全部存在。[接口清单][n-interfaces]、[IEntityExtension][n-entityext]、[Entity Patch][n-entitypatch]

Fabric 的 Loom 开发期 interface injection 也不能当成 Fabric Loader 自动实现接口的证据；运行类型通常还需 Mixin 等实际修改。跨生态接口适配应验证 `instanceof`、invokeinterface、反射、default 方法冲突和子类覆盖。

## 3. Fabric 的可确认加载链

固定 Loader 的 KnotClassDelegate 先取 pre-Mixin bytes：入口补丁或原始 bytes → FabricTransformer（访问、环境剥离、AW 等）→ `IMixinTransformer.transformClassBytes`。因此 AW 在该路径中先于 Mixin；初始化前或不能转换的类有旁路，不能忽略。[KnotClassDelegate][fl-knot]、[FabricTransformer][fl-transformer]

AW / Mixin 配置需要在目标类首次定义前收齐。模组 preLaunch、Mixin plugin、ServiceLoader 或扫描器若过早加载目标，之后再补 AW / 接口结构不会自动修复已定义 class。记录 load reason 与首次定义时刻属于必需审计。

## 4. ModLauncher 的排序事实

NeoForge 属性声明的 **ModLauncher 11.0.5** 样本，其 ClassTransformer 依次执行：

```text
读取已准备的输入类
→ LaunchPlugin BEFORE
→ PRE_CLASS transformer 投票
→ FIELD transformer 投票
→ METHOD transformer 投票
→ CLASS transformer 投票
→ LaunchPlugin AFTER
→ 按 compute flags 输出类
```

每个投票阶段区分 YES / NO / DEFER / REJECT，选择可执行转换器后继续投票；全体 DEFER 或 REJECT 会失败。Coremods 6.0.4 与 FML POM 声明的 7.0.3 样本，其脚本包装器创建 Class / Method / Field transformer、投 YES，走这条管线。[ModLauncher 源码包][ml-src]（ClassTransformer）、[Coremods 6.0.4 源码包][core-src]、[7.0.3 源码包][corefml-src]（CoreMod、CoreModBaseTransformer）。

CoreModBaseTransformer 还存在“捕获脚本异常、记日志、返回当前 node”的路径；脚本若原地修改后抛错，甚至可能留下部分改写。因此 ModLauncher 调用返回不等于该 coremod 成功，审计必须捕获这类内部失败和输出变化，不能只依赖向上抛出的异常。[Coremods 7.0.3 源码包][corefml-src]

Mixin 0.8.7 的 ModLauncher integration 中，实际 MixinTransformationHandler 返回 AFTER_ONLY；Mixin launch plugin 本身还集合其他 processor 的阶段请求，不能把整个服务的所有行为都简写为 AFTER。[MixinTransformationHandler][mx-handler]、[MixinLaunchPluginLegacy][mx-plugin]

Forge 10.2 系列 ClassTransformer 静态样本有相同的大阶段结构；AT 8.2 系列 service 请求 BEFORE，Coremods 5.2 系列包装器投 YES。**这些系列提交尚未与 Forge 固定构建所选精确发布包核对二进制一致性**，不能直接用作部署锁定。[Forge ClassTransformer][f-mlclass]、[Forge AT service][f-atservice]、[Forge Coremod wrapper][f-corewrapper]

NeoForge FML 4.0.45 查找 `accesstransformer` launch plugin；其 POM 另声明 `net.neoforged.accesstransformers:at-modlauncher:10.0.1`。补查该 service 源码包可确认请求 BEFORE；RuntimeEnumExtender 也请求 BEFORE，RuntimeDistCleaner 请求 AFTER。[AT ModLauncher service 源码包][natml-src]、[FML 源码包][nfml-src]（FMLLoader、两个 Runtime 类）。

这里必须区分“属性声明”和“实际选中”：NeoForge 属性中 Coremods=6.0.4、Bus=8.0.2，而 FML 4.0.45 POM 中 Coremods=7.0.3、Bus=8.0.5。具体最终版本需解析完整构建依赖与发布 profile，本轮未执行。上述阶段机制取样不是一套已组装成功的启动环境。

另一个重要边界：ModLauncher LaunchPluginHandler 收集 service 后按插件集合遍历建立 phase 列表。它没有提供“模组 dependencies.ordering 自动决定同 phase 插件顺序”的保证；Mixin、dist cleaner 和其他 AFTER 插件之间的具体顺序须以启动实例审计为准。Mixin priority 也不能排序外部 transformer。[ModLauncher 源码包][ml-src]（LaunchPluginHandler）

## 5. NeoForge Patch 放在哪一层

source / binary patches 用来准备游戏基底，属于上游构建与生产安装产物准备。mod AT、coremod、Mixin 属于之后的运行变换。不能列成“Mixin → NeoForge .java.patch”并指望运行时应用源码 diff。选择 NeoForge patched 基底时还需避免再次重复增加其接口 / 方法；从 vanilla 构建自主基底则必须重建必要 ABI 与行为。[NeoDevPlugin][n-devplugin]、[Forge binary patch 配置][f-build]

## 6. 建议的 NeoForbric 管线契约

以下是新内核的建议，不声称复制任一原生 Loader 全部内部顺序：

1. **离线输入准备**：固定游戏 / patch 产物、映射和 mod JAR；转换名字与元数据引用；生成原生态 clean view 与统一基底 view。
2. **类定义前 ABI**：补足选定父类、接口、字段、方法与初始化；应用已收集的访问规则。构建期已存在的变更只验证，不重复做。
3. **受支持的外部 transformer**：按显式阶段、目标粒度、投票或约束执行；不能把任意 Forge service 直接塞进 Fabric 类加载器。
4. **结构核对与注入计划修复**：用实际 pre-Mixin 目标与源 clean view 比较，修正 Mixin / Extras 选择器、handler 签名和局部变量。修复既可能改模组注入元数据，也可能补目标 helper；两类改动分别记账。
5. **单一 Mixin / Extras 所有者**：应用注入、包装链和必要 post-processing。
6. **最终审计与 JVM 定义**：检查访问、类型层级、frames、目标存活和方法调用；默认不允许未知后置步骤删除 / 迁移已注入的业务体。

若 coremod 必须以已注入方法为输入，或 AFTER 插件仍要改结构，应显式声明后置步骤及被影响的注入，重新做最终检查与行为探针。不能把这样的要求自动塞到第 3 步。未知顺序依赖应报“不支持的顺序约束”，不能靠交换两次碰运气作为正式兼容。

每个步骤声明 input namespace、expected ABI / fingerprint、read/write class set、阶段前后置条件和幂等性。缓存键包括**顺序图与所有变换规则哈希**；改插件顺序必须使缓存失效。修复后的 Mixin 附着数量正确，也不证明后续 transformer 保留了同样的可执行行为。

## 7. 待执行探针

| 编号 | 输入变化 | 验收重点 |
| --- | --- | --- |
| S3-T01 | 同方法 AW accessible / extendable 与 AT final 规则组合 | flags、invoke opcode、覆盖行为与原生一致 |
| S3-T02 | 注入接口具有 default 方法，宿主缺必需方法；再加入子类 | 类型可赋值性、初始化与实际 default 调用 |
| S3-T03 | coremod 插入同名 INVOKE，随后 ordinal Mixin | 修复基于实际 pre-Mixin 结构，不能误选 |
| S3-T04 | AFTER transformer 移动 / 删除已注入 helper | final audit 指向破坏步骤，而非误报成功 |
| S3-T05 | 服务发现顺序换位、投票全 DEFER / REJECT | 顺序日志、失败说明与可复现性 |
| S3-T06 | preLaunch / plugin 提前加载 AW 目标 | 首次定义归因，禁止迟到变更伪装应用成功 |
| S3-T07 | 已 patched 基底再次输入接口与 patch规则 | 不重复修改；不兼容指纹明确拒绝 |

[aw-src]: https://maven.fabricmc.net/net/fabricmc/access-widener/2.1.0/access-widener-2.1.0-sources.jar
[core-src]: https://maven.neoforged.net/releases/net/neoforged/coremods/6.0.4/coremods-6.0.4-sources.jar
[corefml-src]: https://maven.neoforged.net/releases/net/neoforged/coremods/7.0.3/coremods-7.0.3-sources.jar
[f-atservice]: https://github.com/MinecraftForge/AccessTransformers/blob/c89aba52660fb341e7e4ba663ff78df240611f0c/at-mlservice/src/main/java/net/minecraftforge/accesstransformer/service/AccessTransformerService.java
[f-build]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/build_forge.gradle
[f-corewrapper]: https://github.com/MinecraftForge/CoreMods/blob/e7503dd0cdd2607a2244ece31174d5c8e01bf306/src/main/java/net/minecraftforge/coremod/transformer/CoreModBaseTransformer.java
[f-mlclass]: https://github.com/MinecraftForge/ModLauncher/blob/ffce32c1bfa79f443baba9ee58030c2aea5c26b0/src/main/java/cpw/mods/modlauncher/ClassTransformer.java
[fl-knot]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java
[fl-transformer]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/transformer/FabricTransformer.java
[ml-src]: https://maven.neoforged.net/releases/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5-sources.jar
[mx-handler]: https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/modlauncher/java/org/spongepowered/asm/service/modlauncher/MixinTransformationHandler.java
[mx-plugin]: https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/modlauncher/java/org/spongepowered/asm/launch/MixinLaunchPluginLegacy.java
[n-devplugin]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/buildSrc/src/main/java/net/neoforged/neodev/NeoDevPlugin.java
[n-entityext]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/common/extensions/IEntityExtension.java
[n-entitypatch]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/Entity.java.patch
[n-interfaces]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/resources/META-INF/injected-interfaces.json
[n-transources]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/buildSrc/src/main/java/net/neoforged/neodev/TransformSources.java
[nat-src]: https://maven.neoforged.net/releases/net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1-sources.jar
[natml-src]: https://maven.neoforged.net/releases/net/neoforged/accesstransformers/at-modlauncher/10.0.1/at-modlauncher-10.0.1-sources.jar
[nfml-src]: https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar
