# 批量模组回归

`./gradlew.bat benchmarkMods` 一次构建共享运行环境，然后由 Python 串行直接启动 `org.neoforbric.bootstrap.Main`，每个样本不再调用 Gradle。需要 JDK 21、Python 3.10+；真实客户端阶段沿用 Windows x64、桌面会话和 OpenGL 的要求。Python runner 只使用标准库。

## 输入

已有完整样本目录时，可用 `python tools/benchmark_inventory.py benchmark/mods` 生成 `benchmark/experiment.csv` 和 `build/benchmark/inventory.json`，每个顶层 JAR 对应一个 case。`.connector` 内的重映射缓存不作为目标，也不参与依赖池选择。

每个 case 是**目标模组及其必需依赖的递归闭包**。编辑 `benchmark/mods.csv`，路径相对 **CSV 所在目录**，多个 JAR 用分号分隔。`dependencies` 可以显式固定前置版本；其余必需依赖自动从本地模组池补齐。目标和闭包都复制到该轮独立 `mods/`；不会自动下载依赖，也不会混入 `run/client/mods`。仓库默认只有一个无模组 smoke case，用来确认运行环境。

```csv
case_id,mods,dependencies,profile,stages,timeout_s,enabled
vanilla-smoke,,,fabric,,180,true
jade-fabric,../mods/test-set/jade-fabric.jar,dependencies/fabric-api.jar,fabric,,180,true
native-sample,../mods/test-set/sample-neoforge.jar,dependencies/core-neoforge.jar,neoforge,,180,true
mixed-sample,../mods/test-set/sample-fabric.jar;../mods/test-set/sample-neoforge.jar,dependencies/fabric-api.jar,neoforge,admission;menu;world,180,true
```

文件名为示例，换成实际 JAR。空 `stages` / `timeout_s` 使用命令默认值；`enabled=false` 排除一行；每个 case_id 唯一。空 mods 是合法的 vanilla smoke。所有输入会先校验描述符、文件名冲突和哈希，再开始批次；复制时复核哈希，检测测试期间源 JAR 被替换。

默认模组池为项目根目录的 `mods/test-set/` 与 `benchmark/dependencies/`，递归扫描 JAR。自定义使用 `'-PbenchmarkModPools=mods/test-set;benchmark/dependencies;D:/ModPool'`，或直接 runner 重复传 `--mod-pool <目录>`。不存在的目录视为空池，缺失前置仍按 case 记为输入错误，不会启动游戏。目标可以只写一个 JAR；池中的无关模组不会一起复制。

准备器一次启动独立 metadata JVM，仅读取描述符、manifest 和声明内嵌 JAR，复用项目实际的 Fabric 元数据 / 版本谓词和 native Maven 范围逻辑，不加载模组类。必需依赖继续递归展开；Minecraft、Java 和各 profile 提供的 loader 版本作为内置依赖。Fabric `provides`、声明 nested JAR、native JarJar 和多 mod 描述符会进入可用依赖索引；`recommends` / `suggests` 不自动添加，native 的 SERVER-only 和 absent OPTIONAL 前置不自动添加。多个版本时按版本优先尝试并回溯，要求整个闭包满足已声明约束，显式列出的版本固定不替换。

准备结果写入 `dependencies/resolved.json`，保留每个选中 JAR 的 mod ID、版本和依赖边。缺前置、版本冲突、元数据不可读会记录 `dependencies: INPUT_ERROR`，该 case 的 admission/menu/world 都 SKIPPED，继续下一个。正式 NF resolver 仍是最终权威：它验证 nested / JarJar 版本仲裁、排序、别名替代及平台规则；若它仍发现依赖缺失、版本或 JarJar 冲突，也归 `INPUT_ERROR`，不能算 NF 兼容失败。模组未声明但运行时才访问的隐式前置无法仅靠元数据推断，应补到 CSV 的 dependencies 后重测。

`profile` 支持 `fabric`、`neoforge`、`forge`、`auto`（默认）。自动选择读取目标及依赖的根描述符，规则与开发 `runClient` 一致；需要 Forge → NeoForge 桥接的混合集合应显式选 `neoforge`。准入和客户端都要求显式输入确实被选中；客户端静默排除的未知 / 不支持 / server-only 模组不能被报成 PASS。客户端测试会检查全部输入，而不只是 target JAR。

## 分层运行

默认 `admission,menu`，可用 `'-PbenchmarkStages=admission,menu,world'`。CSV 可以按行覆盖阶段，顺序固定且允许子集；上一阶段失败会把该样本后续阶段标成 SKIPPED，但继续运行其他样本。

| 阶段 | 实际覆盖 | 成功条件 |
| --- | --- | --- |
| dependencies | 目标及必需前置的递归闭包、内置依赖、版本选择；无窗口 | 完整闭包 READY；缺失或冲突为 INPUT_ERROR |
| admission | DISCOVER、RESOLVE、PREPARE、remap、CLASS_INDEX、seal；独立 JVM，不创建窗口 | 退出码 0 + `ADMITTED` 审计 + `benchmark-admission-complete` |
| menu | 实际模组初始化 / 原生生命周期、注册、资源重载、渲染主菜单 | 主菜单探针 + `client-complete` + 最终 `SUCCESS` 审计 + 退出码 0 |
| world | 再启动全新客户端和随机名称存档，确认集成服务端与玩家，至少 60 个游戏 tick，截图并正常停止 | 主菜单 + `benchmark-world-complete` + `client-complete` + `SUCCESS` + 退出码 0 |

无窗口 admission **不执行模组构造、注册生命周期或资源重载**，不能据此宣称这些契约或任意 Mixin 应用已通过。通用世界 smoke 不验证模组专属功能；JEI 配方、网络和人工抽样仍需各自的功能验收。每阶段都用新目录，menu 与 world 不复用已有进程或存档。只有成功证据加上正常清理才算 PASS；仅出现日志关键词或退出码 0 不足以通过。

```powershell
./gradlew.bat benchmarkMods
./gradlew.bat benchmarkMods '-PbenchmarkStages=admission,menu,world' -PbenchmarkTimeout=180 -PbenchmarkHeap=4g
./gradlew.bat benchmarkMods -PbenchmarkProfiles=fabric '-PbenchmarkCases=benchmark/fabric.csv'
./gradlew.bat benchmarkMods -PbenchmarkDryRun=true
./gradlew.bat benchmarkHarnessTest :loader:test
```

Linux 风格命令使用 `./gradlew`，但目前项目的真实 Minecraft 客户端输入仍限定 Windows x64。runner 的 POSIX 进程组清理可用于其他支持该平台的 launcher / 测试。

`benchmarkProfiles` 默认准备 `fabric,neoforge,forge`；仅测一个 profile 可以缩小准备范围。菜单在成功后默认渲染 10 帧退出，可用 `-PbenchmarkFrames=40`。`benchmarkPython` 可指定 Python 可执行文件；`benchmarkCases` 和 `benchmarkOutput` 的 Gradle 路径相对项目根目录。每阶段单独计时和超时，默认 180 秒。

后续批次可完全绕过 Gradle，复用已导出的 classpath、库、natives、资源和 NF JAR：

```powershell
./gradlew.bat prepareBenchmarkRuntime
python tools/benchmark_mods.py --runtime build/benchmark/runtime.json --cases benchmark/mods.csv --stages admission,menu,world --timeout 180
```

修改 NF 源码或运行库后重新执行 `prepareBenchmarkRuntime`，共享 manifest 是本机绝对路径，不能直接搬到另一台机器。runner 验证共享文件存在，NF 启动时仍按原有 runtime 锁与哈希校验内容。

## 证据和恢复

中断后可以在原批次内续跑，使用该批次保存的运行时、依赖闭包、case 顺序、阶段、超时和堆参数：

```powershell
python tools/benchmark_mods.py --resume build/benchmark/runs/20261007T160956Z-cbb00d7e --dry-run
python tools/benchmark_mods.py --resume build/benchmark/runs/20261007T160956Z-cbb00d7e --discard-mod-copies --min-free-mib 1024
./gradlew.bat benchmarkMods '-PbenchmarkResume=build/benchmark/runs/20261007T160956Z-cbb00d7e' -PbenchmarkDiscardModCopies=true -PbenchmarkMinFreeMiB=1024
```

`--resume` 会核对保存的共享文件和所有输入 JAR 的 SHA-256；文件缺失或内容改变则拒绝续跑，应为新构建另建批次。Gradle 的 `benchmarkResume` 会跳过运行时构建。续跑保留已有 PASS、FAIL、TIMEOUT、INPUT_ERROR、ERROR，不重测这些阶段；重试 INTERRUPTED 和因中断或磁盘不足而尚未执行的阶段。因为前置阶段失败而跳过的阶段仍不启动。

若独立 `result.json` 已保存，但汇总尚未写入，则先核对身份、输入和 PASS 审计证据并恢复到汇总。没有完整结果的阶段在 `menu-attempt002` 等新目录重试，保留原目录。批次使用操作系统文件锁防止新版 runner 重复启动；异常退出会自动释放锁。每次续跑另存脚本快照和 `resume-*.json`，原 `batch.json` 与原脚本快照保留。

JSON/CSV 替换遇到拒绝访问会短暂重试；阶段结果和状态文件持续被锁时另存 `*.fallback-*.json`，恢复时读取最新有效快照。NDJSON 作为追加日志保留所有尝试；CSV/JSON 汇总每个 case/stage 的最新结果，因此续跑后的重试不增加逻辑样本数。若末尾因异常退出出现不完整 NDJSON 行，续跑先将原尾部另存 `journal-tail-*.bin` 再恢复追加；中间损坏会拒绝自动恢复。`--dry-run --resume` 仅核对并报告接续位置，不启动依赖规划或游戏 JVM，也不更新结果。

每次创建 `build/benchmark/runs/<UTC时间>-<随机ID>/`，保留全部历史，不递归删除已有目录：

```text
batch.json                    # 参数、classpath、输入与哈希
dependencies/request.json     # 全批次的目标与候选池
dependencies/resolved.json    # 递归依赖闭包、版本、依赖边与输入错误
dependencies/console.log
benchmark_mods.py             # 本次 runner 源码快照
benchmark_process.py
results.ndjson                # 每阶段追加并 fsync，可恢复已完成记录
results.csv / results.json    # 每阶段更新的汇总
case-0001-<case_id>/
  admission/                  # 各阶段隔离
    mods/
    launch.json               # 完整 argv 和输入哈希
    console.log               # JVM stdout/stderr，包括日志初始化前的失败
    audit.json                # 或 NF 的 audit.json.fallback-*.json
    result.json
  menu/
    logs/latest.log
    crash-reports/
  world/
    saves/neoforbric-benchmark-<UUID>/
    benchmark-world.png
```

汇总包含 case、profile、阶段、PASS / FAIL / INPUT_ERROR / TIMEOUT / ERROR / INTERRUPTED / SKIPPED、退出码、总耗时、就绪耗时、最终审计结果、失败码和证据目录。`batch.json` 也记录共享 NF JAR、classpath 与 runtime plan 的 SHA-256。admission 的 `startup_ms` 表示静态准入完成时间，其余为主菜单就绪时间。超时强制终止产生的退出码保留，但判定为 TIMEOUT；后续错误清理记为 ERROR。每阶段结束才读取最终审计，优先最近的有效 fallback；崩溃报告、非零退出或审计失败优先于成功证据。

Windows 先挂起创建 JVM，分配带 kill-on-close 的 Job Object 后恢复主线程；正常退出、超时和 Ctrl+C 都清理该 Job 的全部子进程。POSIX 使用独立进程组。Ctrl+C 会保存当前阶段为 INTERRUPTED，将未跑阶段记为 SKIPPED 并退出 130。所有阶段通过退出 0；任一样本失败或依赖输入不完整退出 1；整个 CSV 或环境配置错误退出 2。Gradle 会保留这些证据并将非零退出作为任务失败。Excel / 编辑器锁住汇总文件时，会另存带编号的最新快照并继续批次，NDJSON 追加记录仍保留。

大批次可在直接 runner 中使用 `--discard-mod-copies`：进程树结束后只删除本轮未被改写的 JAR 副本，原始样本、哈希、命令、日志和审计保留。`--min-free-mib` 默认 512；下一轮复制所需空间加上保留空间不足时停止启动 JVM，余下阶段 SKIPPED，`status.json` 为 `STOPPED_LOW_DISK`，退出 3，不把磁盘问题计为 NF 不兼容。NDJSON 每条落盘；CSV/JSON 在每个实际 JVM 完成、每十条快速跳过记录及批次结束时更新。

第一版有意只支持串行。300 个 case、两个阶段、每阶段 180 秒的最坏上限约 30 小时，实际通过样本通常更短。应先积累串行基线，再根据 GPU、内存、磁盘及共享 remap 缓存的行为决定是否加入两个 worker。
