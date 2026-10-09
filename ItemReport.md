# 题目
关于三端加载器是否能成立，并且能正常稳定运行大部分模组和整合包
# 许可与使用
本项目的资源以外的资源（如fabeiclogo等）按照对应的许可，对于改编项目时需要保留本文件的中文版本或英文版本. 
不能用于盈利目的使用
# 项目成立时间
2026/10/6
# 预测
理论可行，但是对于高强度的mixin的模组或者优化模组可能无法正常加载？
# 项目负责
耿悠博（项目策划/编程）：担任本项目的主策划，提供实现本项目的核心思路与编写关键核心源码与挑选模组与整合包，并负责整合包的高压测试。  
gpt-6.1sol（编程/查询）：前期主要收集该项目的实现可能性，后期主要编写模组加载器的框架与外部源代码。同时担任整合包的高压测试的副担当。  
deepseek4.1flash（编程）：主要担任整合包的高压测试。  
# MC版本
1.21.1
# 测试对象
fabric:Slime Advanture<br>
neoforge:ATM10<br>
# 测试方法
## 模组
将模组放进run/client里，然后运行。然后观察是否会出现异常
## 整合包
将整合包的config,datapacks,kubejs,mods等一并放进run/client里，然后运行。然后观察是否会出现异常
# 阶段记录
阶段1：主要测试把模组放进去然后观察是否会出现异常 √<br>
阶段2：主要测试把小型整合包（about 100mods)放进去然后观察是否会出现异常 √<br>
阶段3：主要测试把大型整合包（about 500mods)放进去然后观察是否会出现异常 √<br>
阶段4：把优化模组单独放进去然后观察是否会出现异常<br>
阶段5：模组混合测试：将不同端端模组运行，然后观察是否会出现异常<br>
# 整合包评分
## 评分规则
将整合包放进run/client里，然后观察。如果出现项目本身的异常就扣分，如果能稳定启动至游戏主界面为80分
## 整合包评分
Slime Advanture 48/100<br>
ATM 60/100<br>
BlazeandCave's Expanded+ 64/100
以上整合包评分沿用原人工测试记录，与下方独立模组批量测试的通过率分开统计。

# 实验结果

## 第一阶段：模组全测试

**完成日期：2026/10/09。第一阶段全部 1136 个样本已得到对应阶段判定。**编号 1–500 使用先前保存的统计，编号 501–1136 使用第二批及其续跑后的最新结果；旧记录中重复的第 501 个不重复计数。

Minecraft 版本为 **1.21.1**。原报告标注版本为 `0.1.4-SNAPSHOT`，保存的阶段记录及第二批 `batch.json` 中 loader、NeoForge bridge、Forge bridge 的 JAR 文件名为 **0.1.5-SNAPSHOT**。二者存在版本标签差异，本次数据按各批次保存的路径与哈希记录。旧批次构建清单已不在磁盘，此次未核对两批二进制完全一致，因此以下为两批次的合并观察结果。

本次收集 ATM10、Arcane Dragons [FABRIC]、BlazeandCave's Expanded+、Fabulously Optimized、Farming Experience、FlawlesslyOptimized、Forgeulously Optimized 中的模组，按 `benchmark/mods` 的根 JAR 文件建立 **1136 个样本**。不同版本和不同平台的变体分别计数，不等同于 1136 个不同逻辑模组。

| 原始描述符分类 | 样本数 |
| --- | ---: |
| Fabric | 492 |
| NeoForge | 540 |
| Forge | 96 |
| 无法按标准描述符识别生态 | 8 |
| 合计 | 1136 |

**1128 是前三种生态分类之和，不是已完成测试或已满足依赖要求的有效样本数。**全清单的被动依赖规划结果为 **1117 个 READY、19 个 INPUT_ERROR**；READY 仅说明本地必需依赖闭包可解析，不表示客户端已经启动成功。

测试串行执行，每个样本使用独立目录和 JVM，放入目标模组与递归解析出的必需前置，复用已构建的 Minecraft 库和 NF 运行时。堆上限 4 GiB，单阶段配置超时 180 秒；保存命令、输入哈希、审计、日志、退出码和耗时，结束后清理进程树。

| 阶段或状态 | 含义 |
| --- | --- |
| dependencies | 被动解析本地元数据、必需前置与版本约束 |
| admission PASS | 静态发现、解析、准备、remap、类索引及 seal 通过；不执行完整模组生命周期 |
| menu PASS | 主菜单探针、最终成功审计、正常退出证据齐全 |
| FAIL | 对应阶段失败；需要继续分析根因 |
| TIMEOUT | 未在超时控制下正常完成 |
| INPUT_ERROR | 元数据、依赖或版本要求不满足，不能计为已确认的 NF 兼容失败 |
| INTERRUPTED | 执行被中断，即使退出码为 0 也不算通过 |
| SKIPPED | 该阶段未执行，不算兼容失败 |

本批次只执行 admission 与 menu，尚未进行世界测试和模组专属功能验证。仅 admission PASS 不等于 menu PASS；menu PASS 也不等于全部功能正常。发生 FAIL 的位置不能统一表述为“游戏加载界面出现前崩溃”。

### 编号 1–500 的结果

| 最终分类 | 数量 |
| --- | ---: |
| 主菜单通过 | 453 |
| admission 失败 | 2 |
| 主菜单失败 | 30 |
| 主菜单超时 | 6 |
| 依赖或元数据输入错误，未启动游戏 | 9 |
| 合计 | 500 |

500 = 453 个主菜单通过 + 32 个阶段失败 + 6 个超时 + 9 个输入错误。32 个阶段失败由 2 个 admission FAIL 和 30 个 menu FAIL 组成，尚未逐一确认是否属于 NF 缺陷。

前 500 个的依赖规划为 491 PASS、9 INPUT_ERROR；admission 为 489 PASS、2 FAIL，另有 9 个未执行。实际执行的主菜单阶段共 489 个，453 PASS、30 FAIL、6 TIMEOUT；11 个未执行主菜单，原因是 9 个输入错误和 2 个 admission 失败。

排除输入错误后，已有完整游戏测试终态的 491 个样本中，主菜单通过比例为 **453/491 = 92.26%**。若只统计实际执行的主菜单阶段，则为 **453/489 = 92.64%**。两个分母不同；这都是按清单顺序得到的部分样本比例，不是全批次最终兼容率。


| profile | 样本数 | 输入错误 | admission PASS / FAIL | menu PASS / FAIL / TIMEOUT |
| --- | ---: | ---: | --- | --- |
| fabric | 224 | 7 | 216 / 1 | 191 / 20 / 5 |
| neoforge | 232 | 2 | 229 / 1 | 218 / 10 / 1 |
| forge | 44 | 0 | 44 / 0 | 44 / 0 / 0 |

profile 是 runner 为该样本选择的运行环境。无法识别描述符的输入可能暂归为 fabric 后报告 INPUT_ERROR，因此该表不能替代原始生态分类。

### 编号 501–1136 的结果

第二批 `20261008T101027Z-70e67c30` 及其断点续跑已完成，最终状态为 `COMPLETE`。2026/10/09 04:53:43（Asia/Tokyo）为状态文件落盘时间，不等同于精确进程退出时间。先前第 502 个中断，以及第 610 个 menu 因磁盘不足跳过的记录，已经被续跑后的最新结果替代。

| 最终分类 | 数量 |
| --- | ---: |
| 主菜单通过 | 588 |
| admission 失败 | 1 |
| 主菜单失败 | 31 |
| 主菜单超时 | 6 |
| 依赖或元数据输入错误，未启动游戏 | 10 |
| 合计 | 636 |

该段依赖规划为 626 PASS、10 INPUT_ERROR；admission 为 625 PASS、1 FAIL、10 SKIPPED；menu 为 588 PASS、31 FAIL、6 TIMEOUT、11 SKIPPED。共有 626 个实际进入游戏测试流程的样本，主菜单通过比例为 **588/626 = 93.93%**。

### 第一阶段总结果（编号 1–1136）

| 分段 | 样本数 | menu PASS | admission FAIL | menu FAIL | menu TIMEOUT | INPUT_ERROR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1–500 | 500 | 453 | 2 | 30 | 6 | 9 |
| 501–1136 | 636 | 588 | 1 | 31 | 6 | 10 |
| 合计 | 1136 | 1041 | 3 | 61 | 12 | 19 |

**1136 = 1041 个主菜单通过 + 64 个阶段失败 + 12 个超时 + 19 个输入错误。**64 个阶段失败包括 3 个 admission FAIL 和 61 个 menu FAIL，尚未逐一确认是否属于 NF 缺陷。

排除 19 个未满足测试输入要求的样本，实际进入 admission 并得到终态的样本为 **1117 个**，主菜单通过比例为 **1041/1117 = 93.20%**。若只统计实际执行的 1114 个 menu 阶段，则为 **1041/1114 = 93.45%**；以全部 1136 个计划样本为分母，主菜单通过占比为 **1041/1136 = 91.64%**。本报告主要采用排除输入错误的 93.20%，同时列明其他分母，避免混用。

| 全体阶段 | PASS | FAIL | TIMEOUT | INPUT_ERROR | SKIPPED |
| --- | ---: | ---: | ---: | ---: | ---: |
| dependencies | 1117 | 0 | 0 | 19 | 0 |
| admission | 1114 | 3 | 0 | 0 | 19 |
| menu | 1041 | 61 | 12 | 0 | 22 |

全部 41 条 SKIPPED 均由前置状态阻断：19 个输入错误阻断 admission 和 menu，另有 3 个 admission 失败阻断 menu。已无有效输入样本因中断或磁盘不足而等待续跑。部分原始原因字符串仍沿用 LOW_DISK_SPACE，但其前置阶段已明确为输入错误或 admission 失败；统计按最终前置状态分类，未改写原始记录。COMPLETE 表示执行循环结束，不表示所有样本通过。

### 全体按 harness 运行 profile 分组

| profile | 样本数 | menu PASS | admission FAIL | menu FAIL | menu TIMEOUT | INPUT_ERROR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fabric | 500 | 442 | 1 | 33 | 9 | 15 |
| neoforge | 540 | 503 | 2 | 28 | 3 | 4 |
| forge | 96 | 96 | 0 | 0 | 0 | 0 |
| 合计 | 1136 | 1041 | 3 | 61 | 12 | 19 |

此表按实际选择的运行环境分组，含暂归 fabric 后报告 INPUT_ERROR 的未知描述符样本，不能将 fabric profile 的 500 个直接替代原始 Fabric 描述符分类的 492 个。不同运行环境的样本组成不同，这些数量不是随机抽样下的生态兼容率排名。

#### 异常线索与资料来源

三项 admission 失败分别为 BadOptimizations（`PROTECTED_PACKAGE`，定义受保护共享类）、ConnectorExtras（`CLASS_NAME_MISMATCH`，类名不一致）和 midnightcontrols-neoforge（`PARENT_CONTAMINATION`，类与 bootstrap classpath 重复）。主菜单失败还包括语言提供器缺失、变换失败、实例污染、入口点异常、缺少最终探针证据等情况；超时和依赖版本问题单独记录。完整 64 个阶段失败、12 个超时及 19 个输入错误清单见 [第一阶段完整记录](benchmark/experiment-stage1-final-2026-10-09.md)。这些是保存的排查线索，不能替代根因确认。

第一阶段结果快照：[全部 1136 个样本的最终状态](benchmark/experiment-stage1-final-2026-10-09.json)。原始续跑输出：[2026/10/09 用户输出](benchmark/experiment-stage1-final-2026-10-09-console.txt)，共 1040 条阶段结果，与第二批最新 NDJSON 逐条一致。该快照同时记录旧统计、第二批构建清单、结果、状态及本次输出的 SHA-256；原先的 [第 501 个阶段记录](benchmark/experiment-progress-2026-10-08-0501.md)保留作历史资料。

编号 1–500 按此前选择仅保留统计和输出，其原逐阶段审计、日志及构建清单已不在磁盘。编号 501–1136 的日志也并非全部保留：整理时可见 admission console/audit 各 282 份，menu console 282 份、menu audit 277 份。此次填写依据保存的汇总与用户输出，没有重新核验每一条历史 PASS 审计；后续根因分析应使用仍保留的具体失败日志，已删除的证据不能补作推断。

## 第二阶段：异常模组单独测试

在排除 1041 个主菜单通过的正常样本后，为排除外部因素并判断报错的具体原因，对 **95 个异常样本**分别规划重测，并结合日志分析主要崩溃原因。这里的“正常”仅指第一阶段主菜单通过；本次结果为 **11 个重测通过、65 个阶段失败、19 个输入错误、0 个超时**。19 个输入错误未启动游戏。

### 第二阶段测试对象

| 第一阶段分类 | 待重测样本数 |
| --- | ---: |
| 阶段失败（3 个 admission、61 个 menu） | 64 |
| 主菜单超时 | 12 |
| 依赖或元数据输入错误 | 19 |
| 合计 | 95 |

`benchmark/mods` 已仅保留这 95 个异常目标。1041 个正常样本中，1012 个已删除，29 个仍是异常样本的必需前置，移入 `benchmark/dependencies/abnormal-retest/` 单独保留；不会将 95 个异常目标一起作为一个整合包启动。

### 测试条件与预检

每个目标使用独立 JVM 和全新运行目录，递归补齐必需前置，继续执行 admission 与 menu。堆上限保持 4 GiB，超时从第一阶段 180 秒放宽至 **300 秒**，并记录这一条件变化。第二阶段保留 console.log、游戏日志、崩溃报告和审计，优先追溯最早的异常及 cause 链，区分输入问题、模组初始化、注册、Mixin/类变换、资源加载等原因。

准备阶段已完成不启动游戏的依赖预检：**76 个 READY，19 个 INPUT_ERROR**，后者与第一阶段输入错误名单一致；没有因清理新增缺前置问题。这是输入预检结果，不是第二阶段实际重测结果。19 个样本需补齐符合版本要求的依赖、处理描述符/语言提供器或确认 Java/Minecraft 版本要求后，才能进入有效的游戏测试。

清单为 [abnormal-retest.csv](benchmark/abnormal-retest.csv)，准备过程、启动指令与日志分析要求见 [第二阶段准备记录](benchmark/abnormal-retest-plan-2026-10-09.md)，预检明细见 [预检快照](benchmark/abnormal-retest-preflight-2026-10-09.json)。若直接运行当前清单，输入错误样本仍会报告 INPUT_ERROR 并跳过游戏阶段，不能称为 95 个样本全部完成重测。

### 重测结果与主要崩溃原因

批次 `20261008T225753Z-83046e6b` 已 COMPLETE。用户提供的 285 条阶段输出与本批次最新结果逐条一致。

| 阶段 | PASS | FAIL | INPUT_ERROR | SKIPPED | TIMEOUT |
| --- | ---: | ---: | ---: | ---: | ---: |
| dependencies | 76 | 0 | 19 | 0 | 0 |
| admission | 73 | 3 | 0 | 19 | 0 |
| menu | 11 | 62 | 0 | 22 | 0 |

最终 **95 = 11 个主菜单通过 + 65 个阶段失败（3 admission、62 menu）+ 19 个输入错误**，无 TIMEOUT。第一阶段的 64 个 FAIL 中，7 个恢复通过、57 个仍失败；12 个 TIMEOUT 中，4 个恢复通过、8 个转为失败；19 个 INPUT_ERROR 未修复。不能把未启动游戏的 19 个称为已经完成客户端重测。

对 65 个失败 case 逐个查看 console.log、可见游戏日志、崩溃报告和审计，按首个致命异常及 cause 链归类如下。一个目标只计入一个主要类别；多个目标可能因同一个前置而失败。

| 主要原因（按每个失败 case 的首要阻断分类） | 数量 |
| --- | ---: |
| Fabric API 模块未进入输入 | 9 |
| owo 下载器提前退出 | 8 |
| 语言提供器未就绪 | 2 |
| 运行时前置或库缺失 | 25 |
| 类索引拒绝 | 3 |
| 历史 shaded 库路径不可用 | 3 |
| Java 字节码版本不匹配 | 1 |
| 配置生命周期契约冲突 | 1 |
| 依赖生态与映射混用 | 5 |
| 动态 Mixin 生成类不可见 | 3 |
| 入口点类型不匹配 | 1 |
| 加载器 profile 选错 | 2 |
| 主菜单探针未完成 | 1 |
| GLFW 运行及异常处理链失败 | 1 |
| 合计 | 65 |

**主要发现：预检 PASS 不能保证运行所需前置实际齐全。**9 个案例缺少 Fabric API 模块，24 个缺少其他运行库类，另有 REI 明确报告 Fabric API、Architectury、Cloth Config 都未安装，共 34 个 case。只读检查所选 JAR（含嵌套库）后确认，所需类没有进入这些 case 的输入。部分前置未声明或被标为 OPTIONAL；OmegaConfig 依赖表名与 modId 不一致；Supplementaries 当前所选 Moonlight 缺少所需 API 实现。需补齐/校正前置再重测，不能将这 34 个直接认定为 NF 兼容缺陷。

8 个 case 在 FABRIC_PREPARE 运行 owo-sentinel，下载成功后退出码为 0，却未到主菜单、未写最终审计。其原 JAR 元数据声明 provides owo/owo-lib，字节码确认结束会 System.exit(0)；它们属于下载器提前退出，不能写成“通过”或“游戏崩溃”。应预先向 case 提供真正 owo-lib/owo-impl，再判断兼容性。

5 个 case 的首错来自所选 Fabric 映射 ConfigLib 的 net.minecraft.class_8710/class_2540 不可见，后面的注册或 fluid_type 错误为连锁异常。JourneyMap 两个 case 被自动选为 Fabric，触发其错误加载器保护；需先修正输入生态、双描述符优先级和映射路径。

其余需要重点追查的实际异常包括：3 个 mm 动态 Mixin 的 MassExport_1 不可见；3 个 BetterEnd/BetterNether 关联样本缺旧 Sponge shaded Guava 路径；3 个 admission 类索引拒绝；2 个语言提供器未就绪；C2ME 使用 class file 66.0 而本次 Java 只支持到 65.0；CC:Tweaked 在无文件路径的默认 SERVER 配置上调用 getFullPath()；MedievalEnd 的入口点接口不匹配。ModernFix 只确认主菜单探针未完成，尚未找到更早崩溃原因。XaeroPlus 先发生 glfwGetTime 的 JNI 空指针，随后生成崩溃报告时又发生 CallbackI 类型异常，不能把末尾错误当作唯一根因。

ConnectorExtras 的 relocated class entry 与 internal name 不一致，已在原始内嵌 emi-bridge JAR 静态确认；无需 NF 变换即可观察到。是否应由 NF 支持它的专用重定位约定仍需对照验证。INSTANCE_TAINTED、registry window、Rendersystem wrong thread 等末尾信息在多项案例中属于后果，报告采用更早的致命异常。

### 重测通过名单

| case_id | 第一阶段 | 本次 menu 耗时 |
| --- | --- | ---: |
| mod0015-adorabuild-structures-2.11.0-fabric-1.21.3 | FAIL | 30.760 秒 |
| mod0104-bclib-21.0.13 | TIMEOUT | 127.144 秒 |
| mod0149-bottleyourxp-1.21.1-3.5 | FAIL | 49.823 秒 |
| mod0279-DeleteWorldsToTrash-v21.1.0-1.21.1-Fabric | FAIL | 35.913 秒 |
| mod0302-dyenamics-1.21.1-3.4.2 | FAIL | 25.229 秒 |
| mod0372-fabric-api-0.115.0-1.21.1 | TIMEOUT | 19.247 秒 |
| mod0381-fabrishot-1.14.1 | FAIL | 19.274 秒 |
| mod0649-memorysettings-1.21-6.0 | TIMEOUT | 20.501 秒 |
| mod0755-notenoughanimations-neoforge-1.8.1-mc1.21 | FAIL | 19.011 秒 |
| mod0948-sophisticatedbackpacks-1.21.1-3.23.4.3.106 | FAIL | 21.057 秒 |
| mod1080-worldweaver-21.0.13 | TIMEOUT | 23.542 秒 |

全部 11 个 PASS 已重新读取审计，核对 SUCCESS、main-menu、client-complete 和退出码 0。它们仅代表本次主菜单和退出流程通过，旧失败原因未通过对照实验确认。由于超时由 180 改为 300 秒，且其他运行条件可能变化，本阶段保持独立统计，不覆盖第一阶段历史数据。

### 输入错误与完整证据

19 个 INPUT_ERROR 中，8 个为当前 profile 无可用客户端描述符，9 个为必需前置缺失/版本不匹配，1 个为 Java 版本要求，1 个为 Minecraft 版本要求。没有游戏阶段日志；需先修正输入，再进入有效客户端测试。

每一个失败目标的实际异常、日志行号、确认程度与后续处理均已写入 [第二阶段逐项日志分析](benchmark/abnormal-log-analysis-2026-10-09.md)，机器可读数据见 [结果与分析快照](benchmark/abnormal-log-analysis-2026-10-09.json)。原始日志、崩溃报告和精简审计证据另存于 benchmark/abnormal-log-analysis-2026-10-09-evidence/，保留原 SHA-256，不重复复制整份大审计。本次只分析和记录，没有启动新实验或删除资料。

## 第三阶段：再测试

根据第二阶段日志分析，**34 个样本**存在运行前置未加入、前置声明遗漏或选定库版本缺少所需 API 的问题。已为这 34 个样本补齐或校正真实运行前置，准备继续测试。34 是目标样本数，不代表 34 个不同前置 JAR。

### 准备与验证

第三阶段沿用原目标 JAR、case_id 与 profile，清单包含 **16 个 Fabric、18 个 NeoForge** 样本。前置通过 CSV 的 dependencies 显式指定，递归补齐其声明的必需依赖。第二阶段的其他失败、19 个输入错误和 11 个恢复通过样本不进入本批次。

新增依赖池 benchmark/dependencies/stage3/ 保留 **17 个 JAR**：12 个从本地 SHA-256 命名的原始缓存恢复、5 个从发布文件下载并核对发布哈希；同时复用先前保留的前置。原始目标文件未改写。主要补充 Fabric API、GeckoLib、Bundle API、Sodium、ResourcefulLib、Titanium、EMF、ExtendedAE/Glodium、Immersive Engineering、Mekanism、Cloth Config、Architectury、MidnightLib、Silent Lib、Ranged Weapon API 和 Kotlin 标准库。

对 Supplementaries，将 Moonlight 3.6.3 替换为含其所需 BoolConfigValue 的 2.17.16；对 CreeperOverhaul，固定 NeoForge 版 ResourcefulConfig；Iris 1.8.0 固定 Sodium 0.6.0，Iris 1.8.14 及关联样本固定 Sodium 0.8.13。Aether 关联 case 额外补齐真正 owo-lib，避免新增前置的下载器提前退出。

Observable 本身使用 javafml，原 JAR 未发现 Kotlin for Forge 专属符号引用。本次显式加入保留的 Fabric Language Kotlin 所带 Kotlin 2.4.20 标准库，验证其缺失的 EnumEntriesKt 存在；这是 NF 的混合依赖输入，语言服务与运行表现仍由实际重测判断。

只读依赖规划结果为 **34 READY、0 INPUT_ERROR**；逐个检查新选中 JAR 及嵌套库，之前报缺的 33 项类与 REI 三项前置的代表类共 **36 项均存在**。这只证明输入闭包与类存在性，尚未验证完整 ABI、Mixin、生命周期或主菜单。共享运行文件与第二阶段保存的哈希全部一致，没有重新构建 NF。

### 本轮启动命令（已完成）

保持串行、4 GiB 堆、每阶段 300 秒，执行 admission 和 menu，保留日志、崩溃报告及审计。这是新批次，使用下列命令启动：

```powershell
Set-Location 'C:\Users\gengy\Desktop\NeoForbric'
python tools/benchmark_mods.py --runtime build/benchmark/runtime.json --cases benchmark/stage3-retest.csv --mod-pool benchmark/mods --mod-pool benchmark/dependencies/abnormal-retest --mod-pool benchmark/dependencies/stage3 --output build/benchmark/stage3-runs --stages admission,menu --timeout 300 --heap 4g --discard-mod-copies --min-free-mib 1024
```

本批次输出位于 build/benchmark/stage3-runs/，每个样本与阶段使用独立 JVM/runDir。阶段失败会保留证据并继续下一个；执行结束为 COMPLETE 也不表示全部通过。若中断，按终端输出的实际批次目录使用 --resume，而不是续跑第二阶段的旧结果。

### 第三阶段结果

批次 `20261008T235419Z-06dd7cbd` 已 COMPLETE，共 102 条阶段结果。全部 34 个样本 dependencies 和 admission PASS；menu **29 PASS、5 FAIL，无超时、输入错误或跳过**。29/34 = **85.29%**，是这一组原先运行前置不完整样本在补齐后的主菜单通过比例。29 个 PASS 的原始审计已重新核对 SUCCESS、main-menu、client-complete 和退出码 0。

| case_id | 本次 menu 耗时 | 首要阻断 |
| --- | ---: | --- |
| mod0032-aether_enhanced_extinguishing-1.21.1-1.0.0-fabric | 6.718 秒 | 动态生成的 me.shedaniel.gen.mixin.MassExport_1 在 Mixin PREPARE 不可见。 |
| mod0204-colorwheel-neoforge-1.3.0-beta3-mc1.21.1 | 11.225 秒 | 所选 Sodium 0.8.13 缺 NativeWindowHandle 平台类，Window 定义失败。 |
| mod0357-expandedae-2.1.3 | 22.737 秒 | 仍缺完整 ae2wtlib 中的 AE2wtlibItems，内嵌 API 不包含该实现。 |
| mod0538-iris-neoforge-1.8.14-beta.1-mc1.21.1 | 11.233 秒 | 与 Colorwheel 共用同一 Sodium 平台类缺失。 |
| mod0539-IrisSearch-1.8.1-neoforge | 11.314 秒 | 依赖同一 Iris/Sodium 组合，尚未进入自身功能测试。 |


剩余 5 个对应三组问题：**1 个动态 Mixin 类不可见、1 个仍缺完整 ae2wtlib、3 个共同缺 Sodium 平台实现类**。Aether 关联样本的旧 DeferredRegister 缺类已消失，当前阻断为 mm_shedaniel 动态生成的 MassExport_1 在 PREPARE 不可见；日志显示 MM 已启动，需要核对生成类注册/字节码查询时序。Expanded AE 的旧 EAEConfig 已补齐，但延迟任务又引用 AE2wtlibItems；当前只有 ae2wtlib_api，尚无完整物品实现。

Colorwheel、Iris 1.8.14 和 IrisSearch 共同在定义 Minecraft Window 时缺 NativeWindowHandle。只读检查确认，我为第三阶段选入的 Sodium 0.8.13 JAR 中 WindowMixin 自己引用此类，但根目录和嵌套内容均未提供该类，文件也没有内嵌 service JAR；0.6.0 和本地 0.6.13 的 service JAR 则含该类。先前只核对已报缺的 VertexSerializer 不足以发现这一后续类型需求，需进一步匹配完整 Sodium 平台实现。INSTANCE_TAINTED 是首次定义失败后的连锁状态。当前证据针对所选文件和组合，不代表已经确认三个独立 NF 缺陷，或所有同版本 Sodium 发布包都存在问题。

第一阶段曾保存 Sodium 0.8.13 单目标 PASS，当前则是 Iris 组合；保持历史结果原值，后续应对照配套包与具体加载路径。详细证据、日志行号和处理方向见 [第三阶段剩余异常分析](benchmark/stage3-results-analysis-2026-10-09.md)及[本批次结果快照](benchmark/stage3-results-analysis-2026-10-09.json)。本次只分析、存档，未修改代码或启动重测。

清单见 [第三阶段样本清单](benchmark/stage3-retest.csv)，逐项前置变化与来源见 [第三阶段准备记录](benchmark/stage3-plan-2026-10-09.md)、[前置来源及哈希](benchmark/stage3-dependency-acquisition-2026-10-09.json)、[输入变化](benchmark/stage3-input-changes-2026-10-09.json)、[依赖预检](benchmark/stage3-preflight-2026-10-09.json)和[缺失类核对](benchmark/stage3-class-verification-2026-10-09.json)。


# 结论

第一阶段已完成全部 1136 个样本的判定：**1041 个主菜单通过、64 个阶段失败、12 个超时、19 个输入错误**。排除输入错误后，主菜单通过比例为 **93.20%**。通过结果对应目标模组及其选定必需依赖组合能够完成主菜单阶段。第二阶段重测 95 个异常目标，得到 11 个通过、65 个失败、19 个输入错误、0 个超时；逐项日志分析已确认多项失败来自运行前置未入输入、加载器选择或下载器提前退出，65 个失败目标不能直接等同于 65 个 NF 缺陷。

该结果支持第一阶段单样本加载与主菜单的观察结论。世界加载、模组专属功能、不同生态混合以及整合包整体稳定性尚未由本批次验证，不能把 93.20% 表述为全部功能兼容率或整合包稳定率。

# 后记

## 2026/10/08 实验与中断记录

| 时间（Asia/Tokyo） | 记录 |
| --- | --- |
| 01:09 开始的批次 | 前 44 个完成两阶段；第 45 个 admission 已通过，结果文件替换出现 WinError 5，汇总未及时写入 |
| 07:41 续跑 | 恢复第 45 个 admission，完成至第 67 个；之后因低于 1 GiB 保留空间停止，状态 STOPPED_LOW_DISK |
| 09:00 再次续跑 | 完成至第 501 个，第 502 个 admission 中断；18:33:12 为状态文件落盘时间，不等同于精确进程退出时间 |

harness 已增加断点恢复、文件替换重试、锁定时的持久化 fallback 和批次互斥锁，相关 26 项测试通过。历史细节见 [实验中断记录](benchmark/experiment-interruption-2026-10-08.md)。

## 2026/10/08 清理通过样本

按用户要求，从 `benchmark/mods` 移除编号 1–500 中 menu PASS 的 **453 个目标 JAR**：405 个直接删除，48 个仍被其他保留样本作为必需前置使用，移入 `benchmark/dependencies/retained-passed-1-500/` 并核对哈希，实际删除释放约 868.29 MiB。

目录剩余 **683 个 JAR**，包括前 500 个中的 47 个失败、超时或输入错误样本，以及编号 501–1136 的 636 个样本。剩余文件数不是失败样本数，第 501 个通过样本仍在范围外而保留。清理不改变上述历史结果。

逐项操作见 [清理记录](benchmark/cleanup-passed-1-500-2026-10-08.md)和[删除与移动清单](benchmark/cleanup-passed-1-500-2026-10-08.json)。后续可使用 [剩余样本清单](benchmark/experiment-remaining-after-1-500.csv)并同时加入保留的依赖池建立新批次；原批次的严格 `--resume` 仍引用已删除或移动的原路径，清理后不能直接续跑。此次恢复报告未启动实验。

## 2026/10/09 第一阶段结果填写

编号 501–1136 的第二批续跑已完成，最终状态为 COMPLETE。将其 636 个样本的最新结果与先前编号 1–500 的 500 个样本合并，排除旧快照中重复的第 501 个，得到完整 1136 个样本的第一阶段统计。最新结论和全部异常清单已保存到 [第一阶段完整记录](benchmark/experiment-stage1-final-2026-10-09.md)及其 JSON 快照，并另存主报告备份。此次只填写结果，没有启动新实验或删除日志。

## 2026/10/09 第二阶段准备

已清理正常目标，保留 95 个异常样本和单独的 29 个必需前置。首批依赖资料不完整导致初次清理漏保留 archers 的两个前置，已从原发布版本恢复并核对原 SHA-256；再次预检为 76 READY、19 INPUT_ERROR，与第一阶段异常输入名单一致。逐项动作见 [正常样本清理清单](benchmark/cleanup-normal-stage1-2026-10-09.json)。已生成第二阶段清单与计划，实际重测结果待用户运行后记录。

## 2026/10/09 第二阶段结果与日志分析

已核对批次全部 285 条结果，对 65 个阶段失败逐项追踪首个致命异常，并单列 19 个输入错误。11 个重测 PASS 的最终审计和探针重新核验通过。日志、崩溃报告、精简审计、JAR 元数据与打包核对及结果快照已独立存档；没有启动新实验或删除文件。

## 2026/10/09 第三阶段准备

已为 34 个运行前置不完整样本建立新清单，显式补齐/匹配前置。34 个被动依赖规划均 READY，36 项缺失类/前置代表类均在实际选中输入中找到；共享运行文件与第二阶段哈希一致。已保存来源、版本与 SHA-256，并给出新批次启动命令，实际 admission/menu 结果待运行后填写。

## 2026/10/09 第三阶段结果与剩余异常

已完成的 34 个样本中，29 个主菜单通过、5 个失败。五个失败的首个致命异常及 cause 链已记录，分为动态 Mixin（1）、仍缺完整 ae2wtlib（1）和当前 Sodium 输入缺平台实现类（3）；日志、崩溃报告、精简审计与包体核对独立保存。29 个 PASS 的审计和探针再次核验。未启动新测试。
