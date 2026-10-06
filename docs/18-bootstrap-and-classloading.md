# 18 — 自主 Bootstrap、类加载边界与失败策略

调查日期：2026-10-06。目标：Minecraft **1.21.1 / Java 21**。本篇把 [02 的运行模型](02-runtime-model.md)、[05 的映射与 Mixin](05-mapping-and-mixin.md)、[08 的转换顺序](08-access-and-transformers.md)、[09 的发现](09-discovery-and-metadata.md) 收敛为启动所有权与类定义契约，补查 library / Jar-in-Jar、语言加载器、错误隔离与升级策略。三方共同操作对象的验收见 [17](17-cross-ecosystem-interoperability.md)。

**结论：NeoForbric 必须从 JVM 入口到游戏 `defineClass` 都拥有控制权。** 发现组件、转换协议、语言适配器可以有条件复用；完整 Knot / ModLauncher / FML 启动链不能在同一实例里各自运行。以下启动方案属于设计建议；没有启动 Minecraft、创建运行时 ModuleLayer、运行转换或验证语言模组。固定来源与待执行探针见 [快照](bootstrap-interoperability-snapshot.json)。

## 1. 原生启动链为什么不能直接拼接

| 原生样本 | 实际控制行为 | 对 NeoForbric 的约束 |
| --- | --- | --- |
| Fabric Loader 0.16.10 | `Knot.launch` 调 `init`，后者创建 KnotClassLoader、设 TCCL、load / freeze 模组图、加载 AW、bootstrap Mixin、初始化 transformer、调用 preLaunch，最后 provider.launch | 不能把 Knot 当单纯发现函数；`freeze()` 此处是 loader 模组图冻结，不是 Minecraft registry freeze |
| BootstrapLauncher 2.0.2 | 根据 legacy classpath / module 输入建立 `MC-BOOTSTRAP` ModuleClassLoader 和 layer，设置 TCCL，再选择 `Consumer<String[]>` 服务 | 原启动器是类加载与服务选择的参与者，调用它仍可能移交所有权 |
| ModLauncher 11.0.5 | 建 PLUGIN / GAME layer 输入、初始化 transformation services / transformers，创建 TransformingClassLoader、设 TCCL，交给 launch handler | 自己创建 NeoForbric main 后再调用 `Launcher.main`，仍把游戏定义权交给 ModLauncher |
| NeoForge FML 4.0.45 | FMLServiceProvider 扫描、组装环境和 transformers；CommonLaunchHandler 在 game layer 的 `minecraft` module 中寻找 main 并执行 | metadata、module、loading context 和语言 container 都依赖原宿主，不能仅绕过一次 main 就视为被动化 |

证据：[Knot](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/Knot.java)、[MinecraftGameProvider](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/MinecraftGameProvider.java)、[BootstrapLauncher 2.0.2 源码包](https://maven.neoforged.net/releases/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2-sources.jar)、[ModLauncher 11.0.5 源码包](https://maven.neoforged.net/releases/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5-sources.jar)、[FML 4.0.45 源码包](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar)。Forge 的固定 FMLServiceProvider 同样实现 ModLauncher transformation service，见 [Forge 源码](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlloader/src/main/java/net/minecraftforge/fml/loading/FMLServiceProvider.java)。这些版本是调查样本，未证明可共同解析成一套发布运行时。

BootstrapLaunchConsumer 实际调用 `Launcher.main`；Fabric provider 从传入 loader 取得游戏入口类并调用静态 main。因而接管点必须覆盖 **启动 profile / server script、服务选择、实际 game loader 创建、游戏入口调用** 四处，不能只改显示名称。

“不再运行完整原加载器”不代表 FabricLoaderImpl / FML 全部可以删掉。原 JAR、language adapter、Mixin service 和 locator 会访问 launcher singleton、容器、模组列表、环境、bytecode provider、路径和 ModuleLayer。需要按功能做适配 / fork 或提供有契约的门面；每个被复用组件都记录它要求的宿主服务。名称相同但返回空对象的假门面会把失败拖到初始化之后。

## 2. 建议的唯一启动链与屏障

下面的类名和阶段是**拟定接口**，仓库中尚不存在：

```text
JVM → neoforbric.bootstrap.Main
  → 校验平台、游戏 / library 输入和实际依赖锁
  → archive discovery → 模组 / nested library / 语言需求图
  → resolve → 类 / package / module / resource ownership plan
  → 映射、基底 ABI、AW/AT/接口、修复与转换顺序准备
  → 创建唯一 G（游戏定义门保持关闭）
  → 绑定 bytecode / Mixin host、受控插件与服务视图
  → seal plan → 开启 G 的定义门
  → 源生态允许的 preLaunch
  → G 定义并执行 Minecraft client / server main
  → 游戏 bootstrap 中的受控入口 / 注册 / setup 回调
  → 世界加载、运行、保存和退出
```

“transform preparation → single game classloader”应解释为 **先确定输入与规则，再定义游戏类**。实际 Mixin host 初始化可能需要一个已创建的 target-loader 对象，因此允许先分配关闭定义门的 G，提供原始 / 阶段字节查询，再完成 host 准备；不能因此提前定义目标 Minecraft 类。转换通常按类惰性执行，准备完成不等于启动前已经转换完所有类。

| 屏障 | 允许行为 | 必须拒绝 / 记录的行为 |
| --- | --- | --- |
| DISCOVER / RESOLVE | 读 JSON / TOML / manifest、嵌套描述、class 注解和依赖；不实例化普通 mod | 为“读注解”使用 Class.forName；未经筛选执行任意 ServiceLoader provider |
| PREPARE | 转命名空间、读取继承字节、准备结构与转换图；仅加载准入的工具 / 插件 | 插件触发未准备目标的 define；调用原 Knot / ML 引导入口 |
| SEALED | 输入、owner、rule 与配置集合固定；每类转换按同一计划执行 | 新增模组、临时覆盖共享包、第二套 Mixin / Extras host 接管 |
| GAME / MOD INIT | G 定义游戏、门面、mod；按源合法窗口实例化 / 注册 / dispatch | discovery 时 supplier 早执行；三个 loader 同时发布 lifecycle |
| WORLD | 每会话独立动态 registry、配置、协议与数据生命周期 | 全局缓存旧世界 Holder / SERVER config / capability |

客户端 profile 的 main-class 与专用服务器启动脚本应进入 NeoForbric 的 Main；它按输入命名空间调用最终的 `net.minecraft.client.main.Main` 或 `net.minecraft.server.Main`。服务端 bundled server JAR 中的游戏 / library 内容要由内核验证、分类和展开；不能另走 bundler 的第二条 classpath / loader 路径。原版游戏 main **只执行一次**，mod 构造与 registry bootstrap 可能发生在游戏 main 之后的 Hook，不能把所有 mod init 强行搬到 main 前。

Fabric preLaunch / Mixin plugin 是真实可执行代码，可能加载游戏类型。不是所有现有插件都遵守上面的准备屏障：首版应把无法在准备期安全运行的插件列为未支持，或为它建立明确、完整的早期目标转换契约。TCCL、`Class.forName(..., false, ...)` 或先开一条“临时游戏 loader”都不能消除类型提前定义问题；`initialize=false` 只控制初始化，不禁止类加载与链接。

## 3. 四种 classpath 不等于四份游戏类型

这里的 **NeoForbric bootstrap classpath** 是应用引导输入，不是 JVM 的 bootstrap loader / `-Xbootclasspath/a`。建议先采用一个引导 / 工具域 P 和一个游戏定义域 G；下面四类描述输入责任，不要求每类都有独立 ClassLoader。

| 输入类别 | 内容与建议定义域 | 边界 |
| --- | --- | --- |
| bootstrap classpath | Main、输入锁 / 哈希、最小日志、纯 JDK DTO / SPI；P | 不含游戏 JAR / mod JAR；Main 字段、静态初始化、异常类型也不能直接依赖 Minecraft |
| loader classpath | archive parser、resolver、remapper、transform planner、固定 ASM / Mixin tooling；P。依赖 Minecraft 的运行门面实现归 G | 按实际类型依赖闭包划分，不能把整个 FML 或 Fabric API 随包名扔到 P |
| game classpath | 唯一游戏基底、游戏库、typed services、Fabric API / Forge / NeoForge 游戏门面；G | `net.minecraft.*` 与含其描述符的事件 / 扩展类型保持相同定义；不向 P 回退取得另一份游戏类 |
| mod classpath | 选择后的现有 mod JAR、作为 mod 的 nested JAR、规范化输出；首版也由 G 定义 | mod 类型 / 私有库不因生态拆成三个 loader；nested 不等于隔离 |

Fabric **Loader API** 中不依赖 Minecraft 的类型与 Fabric **API 模块** 中的游戏事件应分开。前者如 LanguageAdapter 接口可由受控 P 提供一份，后者及其 Minecraft 描述符属于 G；实际放置还需扫描父类、接口、字段 / 方法描述符、注解、static 初始化和反射依赖。共享 ASM `ClassNode` / transformer SPI 若跨边界必须同一来源。调用侧传 `Object` 也不会自动修复已经重复定义的类型。

JVMS 还规定加载约束与运行时包身份；重载同名 API 会导致 cast / linkage / 包访问问题。见 [JVMS §5.3–5.3.4](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.3)。默认 ClassLoader 的委派和定义约束不能直接充当 mod 兼容策略，见 [Java 21 ClassLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)。

### 3.1 每个 binary name 只有一个准入 owner

建议生成精确类 / package owner 表，并让所有查找共享它：

- JDK / platform 类委派给平台；核心 SPI、ASM / Mixin tooling 委派给 P 的指定来源。
- Minecraft、typed 游戏门面和选择后的 mod 类型由 G 定义；缺失时报告，不回退到混杂的 system classpath。
- 相同 binary name 的非相同实现先解决冲突再允许 define；不按 JAR 文件名或发现先后择一。
- metadata 注解扫描与 transformer 层级查询读取 bytecode，不通过普通反射触发目标类加载；继承帧计算也遵守这一规则。
- Class、resource、module、服务 provider 的可见性联合规划；查询 `.class` 的 raw / mapped / repaired / final 视图区分开，避免 Mixin 读到错误阶段的字节。

因此，**parent-first 与 child-first 是逐域 / 逐包的策略**。全 parent-first 可能取到未转换游戏或错误库版本；全 child-first 可能复制 SPI / ASM / API。原 Knot 也不是简单的 URLClassLoader 默认委派：它本地尝试后用 parent code-source 限制控制回退。见 [KnotClassLoader](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassLoader.java)、[KnotClassDelegate](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java)。

### 3.2 单一 G 仍可能需要 module view

FML Java / Kotlin / Scala container 使用 `gameLayer.findModule(modFile.moduleName())` 与 `Class.forName(module, entrypoint)`。只设置 TCCL、全部塞进 unnamed module，不能原样运行这些 container。一个 G 可以负责多个 module，但这还需要内核建立正确的 module ownership / reads / exports / opens / services；split package、重复 module name 和 package 到 loader 的映射都要解决。ModuleLayer 不等于必须再启动 ModLauncher。

首版 Java 入口建议由内核自己的门面 container 按固定构造与订阅规则实例化，仅承诺已覆盖的 API 子集。若后续复用原 language container，必须补齐其真实 module / context 契约；不能返回一个空 layer 假装支持。运行依赖图解析与 module graph 验证是不同门槛。

## 4. 转换准备与 define 的实际契约

建议为每个类保留以下证据链，基底构建与运行时转换分开：

```text
原输入 bytes + 命名空间 + 原生 clean 结构
  → 统一名称 / 选择唯一基底
  → 所需 ABI、接口、访问与 structural repair（依赖图决定次序）
  → 该 Mixin 所看到的实际目标 bytes / dirty 结构
  → Mixin / Extras 与兼容 transformer 阶段
  → final verifier / ABI 后置条件
  → G.defineClass（CodeSource / package / module 已确定）
```

这不是新的全局固定排序。NeoForge 构建期 AT / 接口注入 / source patch 与 ModLauncher 运行阶段的 BEFORE / PRE_CLASS / FIELD / METHOD / CLASS / AFTER 不能合并成一条凭空猜的顺序；继续遵循 [08 的源码证据与排序约束](08-access-and-transformers.md)。某个原 transformer 必须在 Mixin 后运行时，应保留该合同并验证 final bytes；**会改变目标结构的 NeoForbric repair 默认必须先于依赖该结构的 Mixin**，特殊后置修复逐项证明不会破坏注入与语义。

原 ModLauncher transformer 不能只拿其 `transform` 方法反射调用：还涉及 target、vote、阶段、reason、环境、服务与 launch plugin 差异。需要协议适配层及支持清单；第三方 `ITransformationService` 若试图自行接管启动或要求无法提供的 layer / 环境，应在 prepare 阶段拒绝。Mixin 必须绑定 NeoForbric 的 bytecode provider、唯一 transformer 和 namespace / refmap 视图，不能同时初始化 Knot host 与 ML host。

定义阶段还需处理每类加载锁、并行 transformer 的安全性和重入：同一 binary name 只定义一次，目标正在转换时的递归请求不能重新开始另一套转换。若某插件导致未解决的递归或提前定义，记录加载请求栈与 transformer owner 后失败；不能绕过变换提供原始类。已经定义的类不能靠替换磁盘缓存获得新的父类 / 接口 / 字段结构。

## 5. Library / Jar-in-Jar：一个解析结果，一份类来源

Fabric nested JAR 的 mod 候选与 FML Jar-in-Jar 的 artifact / version range 元数据有不同选择语义；沿用 [09](09-discovery-and-metadata.md) 的图解析，不能递归 unzip 后把全部内容加入 classpath。建议给每个 archive 记录外层来源、内部路径、原始哈希、artifact 坐标 / mod ID、版本约束、所选候选、淘汰原因及最终 owner。

| 类别 | 建议处理 | 无法自动解决的冲突 |
| --- | --- | --- |
| ASM / Mixin | 转换工具域一套经过验证的版本；所有跨域 SPI 参数指向同一 Class / ClassNode | 模组带另一份同包 ASM 并向内核传 node；不能靠“最新版”保证二进制 API / host 一致 |
| MixinExtras | 固定 bootstrap / injector / helper 的兼容集合，尊重其 service / version 协议；见 [13](13-mixinextras.md) | JiJ 两份都初始化、选择之后运行 helper 仍来自另一份 |
| Guava / Brigadier / DFU 等游戏库 | 锁游戏所需版本与实际 API，归共同游戏库域；工具需要它时显式共享或私有处理 | 一方调用所选版本不存在的方法；“向上兼容”未核实即升级 |
| Kotlin stdlib / reflect / coroutines / serialization | 按语言模组和消费者需求解析兼容集合；同一 Kotlin 类型跨模组保持身份 | metadata / inline 编译版本、反射和 serializer ABI 不满足；不是只提供 stdlib |
| Architectury / 其他平台库模组 | 作为具有平台实现、元数据与 lifecycle 的模组处理 | Fabric 与 Forge variant 不能仅按库坐标去重为任意一份 |
| 真正私有的库 | 已隔离 / 已 relocate 的命名空间，或仅经共享 SPI 调用的私有工具域 | 暴露到 mod / game 描述符的类型不能在两个域独立定义 |

共享库先求各消费者约束与所需 ABI 的可满足集合；无解应失败并列出消费者。字节相同的重复 archive 可按明确策略去重，但 manifest、资源、服务与来源不能丢。字节不同却同名的类、sealed package 或两个不兼容 module 不能用“谁先找到谁赢”处理。

**单一 G 对私有库的限制：** 若两个普通 mod 的字节码都直接引用 `com.example.lib.Foo`，由 G 解析的 Foo 只能有一个实际定义。只增加两个 child library loader 无法使 G 按调用 mod 自动选两个 Foo。要么选择共同版本，要么经已验证的 relocation / 字节码改写，或者另设计隔离插件协议。首版拒绝不兼容的未 relocate 重复库；不承诺任意整合包都能 package isolate。

Relocation 不只修改 class constant pool：还可能涉及反射字符串、Kotlin metadata、Mixin target / refmap、ServiceLoader 文件、资源查找、module descriptor 和序列化类名。已有私有命名空间可以保留；自动 relocation 必须以指定库及夹具验证，不是冲突处理的默认兜底。

### 5.1 ServiceLoader 与 TCCL

`ServiceLoader.load(service)` 使用 TCCL；也可以显式指定 loader 或 layer。provider 必须可赋值到调用方那一份 service Class。见 [Java 21 ServiceLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html)。建议：

1. discovery 先读 provider 声明；引导阶段只实例化已准入的 bootstrap 服务，普通 mod 服务在 G ready 后发现。
2. transform / locator / language / game 四种服务视图独立；普通 mod 的 `META-INF/services/java.util.function.Consumer` 不能被选成启动 consumer。
3. 选择后的 archive 有稳定资源枚举顺序；服务聚合、去重、来源和加载失败均入 audit，不能丢掉第二个 JAR 的声明。
4. 进入 mod callbacks、线程池任务与配置 watcher 时按契约设置 TCCL，在 finally 恢复；测试线程复用、重入和断服泄漏。

TCCL 只是上下文查找输入，不会改变已有 Class 身份，也不会改变无 loader 参数的 `Class.forName` 所使用的调用方定义域。语言适配器的放置因此必须单独审计。

### 5.2 Signed JAR、manifest 与模块

Java JAR manifest 包含 Class-Path、Automatic-Module-Name、版本、sealing、Multi-Release 等行为；签名针对相应 archive 内容，变换并重打包后原签名不能再证明新内容。Java 21 多版本条目选择也必须一致，不能把根目录类与 `META-INF/versions/21` 当成两份普通重复类。见 [Java 21 JAR 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html)。

建议保留原 archive 的校验 / 签名结果及 CodeSource，另记录每步变换和派生产物哈希。加载原始签名条目后在内存变换，与重打包已签名 JAR 是两种路径；原始签名 / signer 来源可保留为 provenance，不能宣称签名认证了 final bytes。生成缓存 JAR 时明确其派生身份，不把旧 `.SF/.RSA/.DSA/.EC` 当作有效新签名。sealing、同包 signer 一致性和 module 约束独立检查，不通过 blanket 关闭校验来宣告支持。

manifest 的 Class-Path 应纳入输入图，路径来源可追踪；FML 的特定 Add-Opens / Add-Exports 处理也不是所有自定义 loader 的自动行为。Forge Java container 有显式实现，见 [FMLModContainer](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/javafmllanguage/src/main/java/net/minecraftforge/fml/javafmlmod/FMLModContainer.java)。SecureJarHandler 3.0.8 提供 module / signing / filesystem 机制，但引用它不代表所有原启动 layer 已复现。见 [3.0.8 源码包](https://maven.neoforged.net/releases/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8-sources.jar)。

## 6. 语言加载器：入口协议也是 ABI

本仓库当前没有任何可执行语言支持。以下是源码调查与建议支持顺序，**不是现有支持列表或已配套的发布版本组合**：

| 语言 / provider | 核对到的实际要求 | 建议边界 |
| --- | --- | --- |
| Fabric 默认 Java | LanguageAdapter 的类型检查、构造 / 成员入口及 Fabric launcher 的 target loader | 首版子集；不能把所有 entrypoint 都假设为无参类 |
| Forge JavaFML | 固定 container 优先尝试 FMLJavaModLoadingContext 构造器，再无参；module、active context、自动订阅与 `useModLauncher()` 的总线集成 | 首版按该固定源协议逐项覆盖，改造宿主集成 |
| NeoForge JavaFML | FML 4.0.45 要求一个 public constructor；允许 IEventBus、ModContainer、FMLModContainer、Dist 的指定注入，拒绝重复参数；自动订阅 | 与 Forge 规则分开实现，不能共用一个“任意反射构造”函数 |
| Fabric Language Kotlin | `languageAdapters.kotlin`；KotlinAdapter 处理 objectInstance / createInstance 和 `Class::member`，调用 kotlin-reflect；无显式 loader 的 Class.forName | Java 稳定后优先；adapter 应在能找到 G mod 的定义域，不能只改 TCCL |
| KotlinForForge 5.x | Forge / NeoForge 不同 SPI；Kotlin object 入口及 event subscribers；Neo container 查 module、loading context 并注入构造参数 | 两 variant 分开，object 与 class / subscribers 都通过后声明支持 |
| Scala：SLP / Scalable Cat's Force | `kotori_scala` provider；Forge / NeoForge SPI 分开，Scala object 通过 `MODULE$` 获取实例，另有构造和 context / module 契约 | 后续指定 provider 和版本；“Scala 字节码”不等于任意 Scala language loader |
| Groovy：GML | `gml` IModLanguageLoader、GModContainer、特定注解和生成订阅方法、TCCL / module / Groovy runtime | 后续限定编译 JAR；脚本 / 动态 metadata 另立支持项，不能自动等同编译模组 |

来源：[Fabric DefaultLanguageAdapter](https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/util/DefaultLanguageAdapter.java)、前述 Forge container 与 FML 4.0.45 源码包；[FLK 1.12.3+kotlin.2.0.21 的 KotlinAdapter](https://github.com/FabricMC/fabric-language-kotlin/blob/8e016c8109414898c4b45b9b7ab738da1ee14f5a/src/main/kotlin/net/fabricmc/language/kotlin/KotlinAdapter.kt)、[其 metadata](https://github.com/FabricMC/fabric-language-kotlin/blob/8e016c8109414898c4b45b9b7ab738da1ee14f5a/src/main/resources/fabric.mod.json)；[KFF 5.x 固定 Neo container](https://github.com/thedarkcolour/KotlinForForge/blob/0e8e3b579661acdad6ff76b9620dcc06f8885cab/src/kfflang/neoforge/kotlin/thedarkcolour/kotlinforforge/neoforge/KotlinModContainer.kt)、[Forge container](https://github.com/thedarkcolour/KotlinForForge/blob/0e8e3b579661acdad6ff76b9620dcc06f8885cab/src/kfflang/forge/kotlin/thedarkcolour/kotlinforforge/KotlinModContainer.kt)。

SLP 取样提交明确声明 Minecraft 1.21.1、Forge 52.0.9、NeoForge 21.1.34，避免把其现今 1.21.0 分支中更高版本模块当成 1.21.1 证据。见 [版本声明](https://github.com/Kotori316/SLP/blob/3ae2ac491e94ca66586414e376197676ecd87111/gradle/libs.versions.toml)、[Neo ScalaModContainer](https://github.com/Kotori316/SLP/blob/3ae2ac491e94ca66586414e376197676ecd87111/neoforge/src/main/java/com/kotori316/scala_lib/ScalaModContainer.java)。KFF 是 5.x 分支机制样本，未取一个对应发布 JAR 验证与本次 FML 的组合。

GML 6.0.2 源码声明 NeoForge 21.0.14-beta 并依赖 **gml-core 7.0.3**，不能仅凭外层版本把整个协议说成已验证 1.21.1。core 的 `GMLLangLoader` / `GModContainer` 已读取：其 script-mod 路径有 TODO 和注释禁用代码，`ModLocatorInjector` 仍引用 FML 私有字段，`ModsDotGroovyCompiler` 有 GroovyShell metadata 编译逻辑；这些存在不证明脚本路径在该版本可用。只拟定编译 JAR 支持，未知 private ABI 或脚本需求提前失败。见 [GML 构建](https://github.com/GroovyMC/GroovyModLoader/blob/4e9c837937796ca192f1eb8ccbac9dc62e5f0d0f/build.gradle)、[构建属性](https://github.com/GroovyMC/GroovyModLoader/blob/4e9c837937796ca192f1eb8ccbac9dc62e5f0d0f/gradle.properties)、[gml-core 7.0.3 源码包](https://repo.maven.apache.org/maven2/org/groovymc/gml/gml-core/7.0.3/gml-core-7.0.3-sources.jar)。

所有 provider 进入准入检查时至少声明：SPI 版本、所需 FML / Fabric context、entrypoint 形式、构造规则、侧别、自动订阅、runtime 库集合与 module 假设。未知必需 `modLoader` / language adapter 提前报错，不回退成 Java。普通 JVM 编译类只用已覆盖 Java 入口且依赖齐全时可以另行验证，但不据此声称对应语言 provider 全部支持。

## 7. 失败策略：能否继续取决于有没有越过提交点

建议把 severity、发生阶段、恢复范围三项分开记录：

| 严重程度 | 例子 | 默认动作 / 恢复前提 |
| --- | --- | --- |
| INFO | 重复的相同 archive 依既定规则去重 | 保留来源与选择证据 |
| DEGRADED | 模组声明可选的功能，明确支持禁用路径 | 仅在尚未变更目标 / 注册 / 写盘且合同允许时跳过，列出功能缺失 |
| MOD_REJECTED | 未支持语言、不可满足可选候选依赖、可排除候选的 ABI 不满足 | 在执行候选代码前排除候选并处理 required 依赖闭包，重建图 / 规则；明确这是缩减集合启动 |
| INSTANCE_FATAL | 必需 Mixin / structural repair / type identity / library ABI / 阶段屏障失败 | 在游戏启动前停止；定义或注册已提交后停止实例并完整重启 |
| WORLD_FATAL / CONNECTION_FATAL | 世界 codec / registry 不闭合，或连接协议缺失 | 阻止该世界开始 / 拒绝连接；只有状态未发布且隔离边界已验证时才允许其他会话继续 |

**可选依赖 ≠ 可选 Mixin ≠ 可以热卸载模组。** Mixin config 的 `required=false`、某个 injector `require=0` 只表达相应转换的错误 / 次数合同，不证明整个 mod 在目标行为缺失时仍正确。一个可选 Fabric Mixin 修复失败时：

1. 若模组本身提供明确 feature gate，尚未应用该功能的变更，且该 gate 的行为验收通过，可禁用该功能并标 DEGRADED。
2. 若只知道候选 mod 可以排除，在任何候选代码执行 / class define / 注册前做依赖闭包排除并重新规划；需要执行过插件时，保守采用新进程重建，避免单例污染。
3. 已修改共享 ClassNode、已经定义类、注册内容或开始世界持久化后，不能移除 listener 就假称模组卸载；默认整个实例失败并重新启动。世界 / 网络事务有经证明的独立隔离时按其边界处理。

Transformer 可以对副本工作并在校验后提交，但 Mixin / plugin / 脚本的全局状态不自动可回滚。Coremod 包装器内部吞异常而返回 node 的情况见 [08](08-access-and-transformers.md)：仅检查返回值无法证明成功；状态 unknown 在必需功能上视为失败。不要使用 `catch Throwable` 后继续作为兼容层默认策略。

必要 audit 字段在 [16](16-transform-audit.md) 上补充：`severity`、`phase`、`commit_point`、`state_tainted`、`recoverability`、`original_mod_set` / `effective_mod_set`、required 依赖闭包、被禁用 feature / rule、失败前最后成功类定义与世界会话。启动前错误报告可以继续收集独立的静态问题；不要继续执行未知状态的 mod 来“搜集更多错误”。

## 8. 升级边界：哪些可复用，哪些必须重新验证

| 可复用内核机制 | 按 Minecraft / loader 修订固定的输入与规则 |
| --- | --- |
| archive 图、版本约束、owner 图、服务视图、定义屏障、TCCL 恢复 | 具体 metadata schema、JiJ / language SPI 及 FML container 构造规则 |
| 按类变换图、审计、失败提交点与输入哈希缓存 | clean / dirty 游戏字节、命名空间、Patch ABI、Mixin 目标 / locals / ordinal |
| registry / world 会话与阶段合同的表示方式 | 1.21.1 冻结位置、Hook 位置、动态 registry 集合、协议任务与 codec |
| provider / config / reload 生命周期的协调框架 | capability / attachment 父类与 API、config 路径 / STARTUP / sync 修订、worldgen getter / 缓存 |
| 原生对照与三方互操作夹具的测试方法 | native 输入 JAR、运行依赖组合、目标内容与预期行为轨迹 |

建议每条版本规则包含：`rule_id`、功能 owner、游戏和 loader 版本 / artifact 哈希、namespace、目标描述符、结构指纹与 preconditions、依赖 / 先后边、postconditions、关联探针、失败等级。fingerprint 不匹配即暂停该规则并报告，不能扩大 ordinal 搜索范围假装自动迁移。缓存 key 覆盖基底、模组图、映射、规则、Java / transformer / language 库版本及侧别；仅按文件名或 Minecraft 版本缓存不够。

升级先 diff 上游结构与行为，再标出依赖这些目标的规则及探针闭包；结构未变化可减少逐条人工重写，仍须保留行为回归。不能承诺下一版无需审计 700 个 Patch：Patch 数量不是规则数量，文本没变也可能因调用者、执行线程或依赖 API 变化而失效。应把 [03](03-patch-audit.md) 的功能分解结果转成可追踪规则，而不是复制完整 patch 列表到每一版。

同一 1.21.1 修订也属于升级：本次 NeoForge build properties 与 FML POM 的 ModLauncher / Coremods / Bus / Mixin 声明不同；实际运行版本要由依赖解析结果锁定。继续保留 [stage3-sources](stage3-sources.md) 的缺口，不能用本篇新增的 BootstrapLauncher / SecureJarHandler 源码包声称完成运行锁。

## 9. 启动与类加载验收探针

以下全部 **待执行**。前六项适合先用 Java 21 的小型 class / JAR 夹具验证机制，再接真实游戏；夹具通过不能替代 Minecraft 和原生态模组实验。

| ID | 故意构造的输入 / 操作 | 通过条件与失败信号 |
| --- | --- | --- |
| B-01 | NeoForbric main 起进程，记录所有定义、入口和 loader 创建 | Minecraft main 一次；Minecraft 类型只由 G 定义；Knot / ML 启动 owner 不运行 |
| B-02 | system classpath / nested JAR 放第二份游戏类、API / ASM 类型 | discovery / owner 校验拒绝冲突；共享 SPI Class 一致，不在首次 cast 才发现 |
| B-03 | discovery 注解与 prepare plugin 故意引用游戏类 | bytecode 扫描不加载；未准备 define 被拒绝并记录请求栈；有早期契约的目标按该合同转换 |
| B-04 | 两线程请求同一类；transformer 重入请求本目标 / 父类 | 定义一次，必要层级查询走 bytes；递归可归因、无绕过转换和死锁 |
| B-05 | 两个 archive 同名类、不同 library 版本；mod 描述符传库类型 | 选择有证明的共同 ABI 或提前失败；不能按调用 mod 在同一 G 定义两份 Foo |
| B-06 | outer / nested service、同名 provider、错误 SPI 身份；线程池重复用 TCCL | 准入服务来源与枚举可追踪；拒绝非子类型；任务结束恢复上下文，无启动 consumer 被 mod 接管 |
| B-07 | 原始 signed / sealed / multi-release JAR；变换并缓存；manifest Class-Path | 原始校验与派生身份区分；条目选择 / CodeSource / package 来源一致，无伪称旧签名认证新内容 |
| B-08 | module name 冲突 / split package；原语言 container 查 mod module | graph 提前报错；已支持 provider 获得正确 module / reads / opens 与唯一 G |
| B-09 | client / dedicated server / datagen 启动；bundled server 输入 | side 与入口正确，只执行一次 main；client 类不泄漏到专用服务器；datagen 按独立合同报告 |
| B-10 | Java Fabric 成员入口；Forge context / 无参；Neo 注入与重复参数 | 分生态原生对照一致；构造 / subscriber 次数、active context 和线程记录正确 |
| B-11 | FLK / KFF 的 object、class、成员入口、object subscriber；不同 Kotlin 库版本 | 分 provider 原生对照；Class.forName 视图与 reflect / runtime ABI 满足，不只打印入口成功 |
| B-12 | SLP MODULE$ 入口、class 构造；GML 编译 JAR 与脚本需求 | 只报告已验证 provider / 版本 / 形式；未知脚本 / private FML ABI 明确拒绝 |
| B-13 | 可选 feature / mod 失败与 required 依赖；必需 Mixin 失败 | 按 severity / 提交点决策；缩减集合单独报告，变更后失败不继续进入世界 |
| B-14 | Mixin 后 transformer 改目标、coremod 吞异常、repair 指纹失配 | final bytes 与后置条件失败可归因；活动记录不等同成功；必需 unknown 阻止启动 |
| B-15 | 同版本修订 / 下一游戏版本变化；缓存命名不变但输入哈希变化 | 缓存失效、受影响规则与探针闭包可列出；旧目标规则不能静默使用 |

建议按 **B-01～B-06 → B-09 / B-10 → 17 的首批 Java 互操作 → library / module / Kotlin → 数据与 worldgen** 实现。signed / sealed、未知 module 或语言要求若未覆盖，则首版 discovery 明确拒绝相关输入，不把后续探针待执行解释成已支持。

两篇专项至此结束通用前期调查。下一步交付应是可运行的自主启动夹具、固定原生态 JAR、native 轨迹与失败报告；围绕实际失败补局部研究。未定位 README 所指 Forbric 的准确 1.21.1 基线、未解析最终运行依赖、未决定共享对象父类桥接策略等缺口仍存在，但可以先用独立 Java 夹具验证所有权 / 屏障机制，不必再写一轮广泛调查。
