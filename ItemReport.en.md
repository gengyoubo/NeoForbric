[中文版本](ItemReport.md) | English

# Research Question

Can a loader for all three mod ecosystems be built and run most mods and modpacks reliably?

# License and Use

Third-party resources used by this project, such as the Fabric logo, are subject to their respective licenses. Adaptations of this project must retain either the Chinese or English version of this document.

Use for commercial profit is prohibited.

# Project Start Date

2026/10/6

# Initial Prediction

Theoretically feasible, but mods that make extensive use of Mixins or optimization mods might fail to load.

# Project Contributors

耿悠博 (project planning/programming): Lead planner, responsible for the project's core approach, key source code, selection of mods and modpacks, and modpack stress testing.  
gpt-6.1sol (programming/research): Initially investigated feasibility; later worked primarily on the loader framework and external source code. Also assists with modpack stress testing.  
deepseek4.1flash (programming): Primarily responsible for modpack stress testing.

# Minecraft Version

1.21.1

# Test Subjects

fabric:Slime Advanture<br>
neoforge:ATM10<br>

# Test Methods

## Individual Mods

Place mods in `run/client`, launch the game, and observe whether any errors occur.

## Modpacks

Place the modpack's `config`, `datapacks`, `kubejs`, `mods`, and other files in `run/client`, launch the game, and observe whether any errors occur.

# Testing Milestones

Stage 1: Load individual mods and observe errors. √<br>
Stage 2: Load small modpacks (about 100mods) and observe errors. √<br>
Stage 3: Load large modpacks (about 500mods) and observe errors. √<br>
Stage 4: Test optimization mods individually and observe errors.<br>
Stage 5: Test combinations of mods from different loader ecosystems and observe errors.<br>

# Modpack Scores

## Scoring Rules

Load the modpack and observe its behavior. Deduct points for errors attributable to this project. Reaching the game's main menu reliably earns 80 points.

## Scores

Slime Advanture 48/100<br>
ATM 60/100<br>
BlazeandCave's Expanded+ 64/100

These modpack scores are carried over from the original manual tests and are reported separately from the pass rates of the individual-mod batch tests below.

# Experimental Results

## Stage 1: Full Mod Test

**Completed on 2026/10/09. All 1136 samples in Stage 1 received the applicable stage verdicts.** Samples 1–500 use previously saved statistics; samples 501–1136 use the latest results from the second batch and its resumed runs. The duplicate record for sample 501 in the older data is not counted twice.

The Minecraft version was **1.21.1**. The original report listed `0.1.4-SNAPSHOT`, while the saved stage records and the second batch's `batch.json` identify the loader, NeoForge bridge, and Forge bridge JAR filenames as **0.1.5-SNAPSHOT**. These version labels differ; the data is recorded using the paths and hashes saved for each batch. The old batch's build manifest is no longer on disk, and binary equivalence between the two batches was not verified during this update. The following results therefore combine observations from two batches.

Mods were collected from ATM10, Arcane Dragons [FABRIC], BlazeandCave's Expanded+, Fabulously Optimized, Farming Experience, FlawlesslyOptimized, and Forgeulously Optimized. The root JAR files in `benchmark/mods` defined **1136 samples**. Different versions and platform variants were counted separately; this does not mean 1136 distinct logical mods.

| Original descriptor classification | Samples |
| --- | ---: |
| Fabric | 492 |
| NeoForge | 540 |
| Forge | 96 |
| Ecosystem not identifiable from standard descriptors | 8 |
| Total | 1136 |

**1128 is the sum of the first three ecosystem categories, not the number of completed tests or valid samples with satisfied dependencies.** Passive dependency planning for the full list produced **1117 READY and 19 INPUT_ERROR** cases. READY means only that the required local dependency closure can be resolved; it does not indicate a successful client launch.

Tests ran serially. Each sample used a separate directory and JVM containing the target mod and recursively resolved required dependencies, while reusing the built Minecraft libraries and NF runtime. The maximum heap was 4 GiB, with a configured timeout of 180 seconds per stage. Commands, input hashes, audits, logs, exit codes, and durations were saved, and the process tree was cleaned up afterward.

| Stage or status | Meaning |
| --- | --- |
| dependencies | Passive resolution of local metadata, required dependencies, and version constraints |
| admission PASS | Static discovery, resolution, preparation, remapping, class indexing, and sealing passed; the full mod lifecycle was not executed |
| menu PASS | Main-menu probe, final successful audit, and clean-exit evidence were all present |
| FAIL | The relevant stage failed; root-cause analysis is still required |
| TIMEOUT | Execution did not complete normally within timeout control |
| INPUT_ERROR | Metadata, dependencies, or version requirements were not satisfied; this cannot be counted as a confirmed NF compatibility failure |
| INTERRUPTED | Execution was interrupted; even an exit code of 0 does not constitute a pass |
| SKIPPED | The stage was not executed; this does not count as a compatibility failure |

This batch executed only admission and menu. World tests and mod-specific functional checks had not yet been performed. admission PASS does not imply menu PASS, and menu PASS does not imply that every feature works. FAIL cases cannot all be described as crashes before the game's loading screen appears.

### Results for Samples 1–500

| Final classification | Count |
| --- | ---: |
| Main menu passed | 453 |
| admission failed | 2 |
| Main menu failed | 30 |
| Main menu timed out | 6 |
| Dependency or metadata input error; game not launched | 9 |
| Total | 500 |

500 = 453 main-menu passes + 32 stage failures + 6 timeouts + 9 input errors. The 32 stage failures consist of 2 admission FAIL and 30 menu FAIL cases. They have not all been individually confirmed as NF defects.

Dependency planning for the first 500 samples produced 491 PASS and 9 INPUT_ERROR cases. admission produced 489 PASS and 2 FAIL cases, with 9 not executed. The main-menu stage actually ran for 489 samples: 453 PASS, 30 FAIL, and 6 TIMEOUT. It did not run for 11 samples because of 9 input errors and 2 admission failures.

Excluding input errors, the main-menu pass rate among the 491 samples with terminal game-test outcomes was **453/491 = 92.26%**. Counting only main-menu stages actually executed gives **453/489 = 92.64%**. These denominators differ. Both rates describe the partial sample set in list order, rather than the final compatibility rate of the full batch.

| profile | Samples | Input errors | admission PASS / FAIL | menu PASS / FAIL / TIMEOUT |
| --- | ---: | ---: | --- | --- |
| fabric | 224 | 7 | 216 / 1 | 191 / 20 / 5 |
| neoforge | 232 | 2 | 229 / 1 | 218 / 10 / 1 |
| forge | 44 | 0 | 44 / 0 | 44 / 0 / 0 |

profile is the runtime environment selected by the runner for a sample. Inputs with unrecognized descriptors may temporarily be assigned to fabric before reporting INPUT_ERROR, so this table does not replace the original ecosystem classification.

### Results for Samples 501–1136

The second batch, `20261008T101027Z-70e67c30`, and its resumed runs completed with a final status of `COMPLETE`. The time 2026/10/09 04:53:43 (Asia/Tokyo) records when the status file was written, not the exact process exit time. The earlier interruption at sample 502 and the skipped menu stage at sample 610 due to insufficient disk space were replaced by the latest resumed results.

| Final classification | Count |
| --- | ---: |
| Main menu passed | 588 |
| admission failed | 1 |
| Main menu failed | 31 |
| Main menu timed out | 6 |
| Dependency or metadata input error; game not launched | 10 |
| Total | 636 |

For this segment, dependency planning produced 626 PASS and 10 INPUT_ERROR cases; admission produced 625 PASS, 1 FAIL, and 10 SKIPPED; menu produced 588 PASS, 31 FAIL, 6 TIMEOUT, and 11 SKIPPED. A total of 626 samples entered the game-testing workflow, with a main-menu pass rate of **588/626 = 93.93%**.

### Overall Stage 1 Results (Samples 1–1136)

| Segment | Samples | menu PASS | admission FAIL | menu FAIL | menu TIMEOUT | INPUT_ERROR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1–500 | 500 | 453 | 2 | 30 | 6 | 9 |
| 501–1136 | 636 | 588 | 1 | 31 | 6 | 10 |
| Total | 1136 | 1041 | 3 | 61 | 12 | 19 |

**1136 = 1041 main-menu passes + 64 stage failures + 12 timeouts + 19 input errors.** The 64 stage failures include 3 admission FAIL and 61 menu FAIL cases; they have not all been individually confirmed as NF defects.

Excluding the 19 samples that did not satisfy test-input requirements, **1117 samples** entered admission and received terminal outcomes, giving a main-menu pass rate of **1041/1117 = 93.20%**. Counting only the 1114 menu stages actually executed gives **1041/1114 = 93.45%**. Using all 1136 planned samples as the denominator gives **1041/1136 = 91.64%**. This report primarily uses 93.20%, excluding input errors, while listing the other denominators to avoid mixing them.

| All stages | PASS | FAIL | TIMEOUT | INPUT_ERROR | SKIPPED |
| --- | ---: | ---: | ---: | ---: | ---: |
| dependencies | 1117 | 0 | 0 | 19 | 0 |
| admission | 1114 | 3 | 0 | 0 | 19 |
| menu | 1041 | 61 | 12 | 0 | 22 |

All 41 SKIPPED records resulted from an earlier blocking outcome: 19 input errors blocked both admission and menu, and 3 admission failures blocked menu. No valid-input samples remain awaiting resumption due to interruption or disk shortages. Some original reason strings still say LOW_DISK_SPACE, although the preceding stage clearly records an input error or admission failure. Statistics use the final preceding-stage status without rewriting the original records. COMPLETE means the execution loop ended, not that every sample passed.

### All Samples Grouped by Harness Runtime Profile

| profile | Samples | menu PASS | admission FAIL | menu FAIL | menu TIMEOUT | INPUT_ERROR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fabric | 500 | 442 | 1 | 33 | 9 | 15 |
| neoforge | 540 | 503 | 2 | 28 | 3 | 4 |
| forge | 96 | 96 | 0 | 0 | 0 | 0 |
| Total | 1136 | 1041 | 3 | 61 | 12 | 19 |

This table groups samples by the runtime environment actually selected, including unknown-descriptor inputs temporarily assigned to fabric before reporting INPUT_ERROR. The 500 fabric-profile samples cannot replace the 492 samples classified by original Fabric descriptors. The sample composition differs across environments, so these counts are not ecosystem compatibility rankings from random sampling.

#### Failure Leads and Data Sources

The three admission failures were BadOptimizations (`PROTECTED_PACKAGE`, defining a protected shared class), ConnectorExtras (`CLASS_NAME_MISMATCH`, class-name mismatch), and midnightcontrols-neoforge (`PARENT_CONTAMINATION`, a class duplicated on the bootstrap classpath). Main-menu failures also included missing language providers, transformation failures, instance contamination, entrypoint exceptions, and missing final probe evidence. Timeouts and dependency-version issues were recorded separately. The full lists of 64 stage failures, 12 timeouts, and 19 input errors are in the [complete Stage 1 record](benchmark/experiment-stage1-final-2026-10-09.md). These are saved investigation leads, not substitutes for root-cause confirmation.

Stage 1 snapshot: [final statuses of all 1136 samples](benchmark/experiment-stage1-final-2026-10-09.json). Original resumed output: [user output from 2026/10/09](benchmark/experiment-stage1-final-2026-10-09-console.txt), containing 1040 stage results that match the second batch's latest NDJSON records individually. The snapshot also records SHA-256 hashes for the old statistics, the second batch's build manifest, results, status, and this output. The earlier [sample 501 stage record](benchmark/experiment-progress-2026-10-08-0501.md) is retained as historical material.

As previously chosen, only statistics and output were retained for samples 1–500; their original per-stage audits, logs, and build manifest are no longer on disk. Logs for samples 501–1136 were also not all retained: at the time of organization, 282 admission consoles and 282 admission audits were available, along with 282 menu consoles and 277 menu audits. This update relies on the saved summaries and user output and did not reverify every historical PASS audit. Subsequent root-cause analysis should use the specific failure logs still available; deleted evidence cannot be replaced with inference.

## Stage 2: Individual Retests of Abnormal Mods

After excluding the 1041 samples that passed the main menu, **95 abnormal samples** were scheduled for individual retesting to rule out external factors and identify specific errors, with logs used to analyze the main crash causes. Here, “normal” means only that Stage 1's main-menu test passed. This stage produced **11 retest passes, 65 stage failures, 19 input errors, and 0 timeouts**. The game was not launched for the 19 input-error cases.

### Stage 2 Test Subjects

| Stage 1 classification | Samples scheduled for retest |
| --- | ---: |
| Stage failures (3 admission, 61 menu) | 64 |
| Main-menu timeouts | 12 |
| Dependency or metadata input errors | 19 |
| Total | 95 |

`benchmark/mods` retained only these 95 abnormal targets. Of the 1041 normal samples, 1012 were deleted; 29 were still required dependencies of abnormal samples and were moved to `benchmark/dependencies/abnormal-retest/` for separate retention. The 95 abnormal targets were not launched together as a single modpack.

### Test Conditions and Preflight

Each target used an independent JVM and a fresh run directory, with required dependencies resolved recursively, followed by admission and menu. The maximum heap remained 4 GiB. The timeout was increased from Stage 1's 180 seconds to **300 seconds**, and this change was recorded. Stage 2 retained console.log, game logs, crash reports, and audits, prioritizing the earliest exception and cause chain to distinguish input issues, mod initialization, registration, Mixin/class transformation, resource loading, and other causes.

Preparation included dependency preflight without launching the game: **76 READY and 19 INPUT_ERROR** cases. The latter matched Stage 1's input-error list; cleanup introduced no additional missing-dependency cases. These were preflight results, not actual Stage 2 retest results. The 19 cases require compatible dependencies, descriptor/language-provider corrections, or confirmation of Java/Minecraft requirements before they can enter valid game testing.

The case list is [abnormal-retest.csv](benchmark/abnormal-retest.csv). Preparation, launch instructions, and log-analysis requirements are in the [Stage 2 preparation record](benchmark/abnormal-retest-plan-2026-10-09.md); detailed preflight results are in the [preflight snapshot](benchmark/abnormal-retest-preflight-2026-10-09.json). Running the current list still reports INPUT_ERROR and skips game stages for those cases; this cannot be described as completed client retesting of all 95 samples.

### Retest Results and Main Crash Causes

Batch `20261008T225753Z-83046e6b` reached COMPLETE. The 285 stage-output records provided by the user match the batch's latest results individually.

| Stage | PASS | FAIL | INPUT_ERROR | SKIPPED | TIMEOUT |
| --- | ---: | ---: | ---: | ---: | ---: |
| dependencies | 76 | 0 | 19 | 0 | 0 |
| admission | 73 | 3 | 0 | 19 | 0 |
| menu | 11 | 62 | 0 | 22 | 0 |

The final total was **95 = 11 main-menu passes + 65 stage failures (3 admission, 62 menu) + 19 input errors**, with no TIMEOUT cases. Among Stage 1's 64 FAIL cases, 7 recovered and passed while 57 still failed. Among its 12 TIMEOUT cases, 4 recovered and passed while 8 became failures. The 19 INPUT_ERROR cases remained unresolved. Cases that never launched the game cannot be described as completed client retests.

For each of the 65 failed cases, console.log, available game logs, crash reports, and audits were examined. They were classified by the first fatal exception and its cause chain as follows. Each target counts toward one primary category; multiple targets may fail because of the same dependency.

| Primary cause, classified by each failed case's first blocking issue | Count |
| --- | ---: |
| Fabric API modules absent from inputs | 9 |
| owo downloader exited early | 8 |
| Language provider not ready | 2 |
| Missing runtime dependencies or libraries | 25 |
| Class-index rejection | 3 |
| Unavailable legacy shaded-library paths | 3 |
| Java bytecode version mismatch | 1 |
| Configuration lifecycle contract conflict | 1 |
| Mixed dependency ecosystems and mappings | 5 |
| Dynamically generated Mixin classes not visible | 3 |
| Entrypoint type mismatch | 1 |
| Incorrect loader profile selected | 2 |
| Main-menu probe incomplete | 1 |
| GLFW runtime and exception-handling chain failures | 1 |
| Total | 65 |

**Key finding: preflight PASS does not guarantee that all dependencies needed at runtime are present.** Nine cases lacked Fabric API modules, 24 lacked other runtime library classes, and REI explicitly reported that Fabric API, Architectury, and Cloth Config were all absent, for a total of 34 cases. Read-only inspection of the selected JARs, including nested libraries, confirmed that the required classes were absent from these cases' inputs. Some dependencies were undeclared or marked OPTIONAL; OmegaConfig's dependency-table name differed from its modId; the selected Moonlight version for Supplementaries lacked the required API implementation. Dependencies must be completed or corrected before retesting; these 34 cases cannot directly be classified as NF compatibility defects.

Eight cases ran owo-sentinel during FABRIC_PREPARE and exited with code 0 after successful downloading, without reaching the main menu or writing a final audit. Their original JAR metadata declared provides owo/owo-lib, and bytecode inspection confirmed System.exit(0) at completion. These were early downloader exits, not passes or game crashes. Actual owo-lib/owo-impl should be supplied to the case in advance before evaluating compatibility.

The first error in five cases came from invisible net.minecraft.class_8710/class_2540 classes in the selected Fabric-mapped ConfigLib; subsequent registration or fluid_type errors were cascading failures. Two JourneyMap cases were automatically assigned to Fabric, triggering their wrong-loader protection. Input ecosystem selection, dual-descriptor priority, and mapping paths must first be corrected.

Other actual errors requiring investigation included three mm dynamic Mixins with an invisible MassExport_1; three BetterEnd/BetterNether-related samples missing the old Sponge shaded Guava path; three admission class-index rejections; and two language providers not ready. C2ME used class file 66.0 while the test Java supported only up to 65.0. CC:Tweaked called getFullPath() on a default SERVER configuration with no file path. MedievalEnd had an entrypoint-interface mismatch. ModernFix was confirmed only to have an incomplete main-menu probe; no earlier crash cause had yet been identified. XaeroPlus first encountered a JNI null pointer in glfwGetTime, followed by a CallbackI type error while generating the crash report; the last error cannot be treated as the sole root cause.

ConnectorExtras had a mismatch between a relocated class entry and its internal name, confirmed statically in the original embedded emi-bridge JAR. It can be observed without NF transformation. Whether NF should support its specific relocation convention still requires comparison testing. Trailing messages such as INSTANCE_TAINTED, registry window, and Rendersystem wrong thread were consequences in several cases; the report uses earlier fatal exceptions.

### Retest Pass List

| case_id | Stage 1 | menu duration in this retest |
| --- | --- | ---: |
| mod0015-adorabuild-structures-2.11.0-fabric-1.21.3 | FAIL | 30.760 seconds |
| mod0104-bclib-21.0.13 | TIMEOUT | 127.144 seconds |
| mod0149-bottleyourxp-1.21.1-3.5 | FAIL | 49.823 seconds |
| mod0279-DeleteWorldsToTrash-v21.1.0-1.21.1-Fabric | FAIL | 35.913 seconds |
| mod0302-dyenamics-1.21.1-3.4.2 | FAIL | 25.229 seconds |
| mod0372-fabric-api-0.115.0-1.21.1 | TIMEOUT | 19.247 seconds |
| mod0381-fabrishot-1.14.1 | FAIL | 19.274 seconds |
| mod0649-memorysettings-1.21-6.0 | TIMEOUT | 20.501 seconds |
| mod0755-notenoughanimations-neoforge-1.8.1-mc1.21 | FAIL | 19.011 seconds |
| mod0948-sophisticatedbackpacks-1.21.1-3.23.4.3.106 | FAIL | 21.057 seconds |
| mod1080-worldweaver-21.0.13 | TIMEOUT | 23.542 seconds |

Audits for all 11 PASS cases were reread and checked for SUCCESS, main-menu, client-complete, and exit code 0. These results establish only that the main-menu and exit sequence passed in this run; the earlier failure causes were not confirmed through controlled comparison tests. Because the timeout changed from 180 to 300 seconds and other runtime conditions may have changed, this stage retains separate statistics without overwriting Stage 1's historical data.

### Input Errors and Complete Evidence

Of the 19 INPUT_ERROR cases, 8 lacked an available client descriptor for the current profile, 9 had missing or version-incompatible required dependencies, 1 had a Java version requirement, and 1 had a Minecraft version requirement. There were no game-stage logs. Inputs must be corrected before valid client testing.

Actual exceptions, log line numbers, confidence levels, and follow-up actions for every failed target are recorded in the [individual Stage 2 log analysis](benchmark/abnormal-log-analysis-2026-10-09.md), with machine-readable data in the [results and analysis snapshot](benchmark/abnormal-log-analysis-2026-10-09.json). Original logs, crash reports, and concise audit evidence were separately archived in benchmark/abnormal-log-analysis-2026-10-09-evidence/, retaining their original SHA-256 hashes without duplicating entire large audits. This update only analyzed and recorded data; it did not launch a new experiment or delete material.

## Stage 3: Retesting

Stage 2 log analysis identified **34 samples** with missing runtime dependencies in their inputs, omitted dependency declarations, or selected library versions lacking required APIs. Actual runtime dependencies were added or corrected for these 34 samples in preparation for further testing. The number 34 refers to target samples, not 34 distinct dependency JARs.

### Preparation and Verification

Stage 3 retained the original target JARs, case_id values, and profiles. Its case list contained **16 Fabric and 18 NeoForge** samples. Dependencies were explicitly specified through the CSV's dependencies field, with their declared required dependencies resolved recursively. Other Stage 2 failures, the 19 input errors, and the 11 recovered passes were not included in this batch.

The new dependency pool, benchmark/dependencies/stage3/, retained **17 JARs**: 12 restored from the local cache named by SHA-256 and recorded at preparation as original archives, and 5 downloaded from release files with published hashes verified. Previously retained dependencies were also reused. Original target files were not rewritten. The main additions were Fabric API, GeckoLib, Bundle API, Sodium, ResourcefulLib, Titanium, EMF, ExtendedAE/Glodium, Immersive Engineering, Mekanism, Cloth Config, Architectury, MidnightLib, Silent Lib, Ranged Weapon API, and the Kotlin standard library.

For Supplementaries, Moonlight 3.6.3 was replaced with 2.17.16, which included its required BoolConfigValue. CreeperOverhaul was pinned to the NeoForge version of ResourcefulConfig. Iris 1.8.0 was pinned to Sodium 0.6.0, while Iris 1.8.14 and related samples were pinned to Sodium 0.8.13. The Aether-related case additionally received the actual owo-lib to prevent an early downloader exit from a newly added dependency.

Observable itself used javafml, and no references to symbols exclusive to Kotlin for Forge were found in its original JAR. The retained Fabric Language Kotlin package's Kotlin 2.4.20 standard library was explicitly added, and the presence of the missing EnumEntriesKt was verified. This was an NF input combining dependencies from different ecosystems; language services and runtime behavior remained subject to actual retesting.

Read-only dependency planning produced **34 READY and 0 INPUT_ERROR** cases. Inspection of each newly selected JAR and its nested libraries found all **36 checks**: 33 previously reported missing classes plus representative classes for REI's three dependencies. This established only dependency-closure completeness and class presence, not full ABI, Mixin, lifecycle, or main-menu behavior. All shared runtime file hashes matched those saved for Stage 2; NF was not rebuilt.

### Launch Command for This Batch (Completed)

Testing remained serial, with a 4 GiB heap and 300 seconds per stage, executing admission and menu while retaining logs, crash reports, and audits. This was a new batch launched with the following command:

```powershell
Set-Location 'C:\Users\gengy\Desktop\NeoForbric'
python tools/benchmark_mods.py --runtime build/benchmark/runtime.json --cases benchmark/stage3-retest.csv --mod-pool benchmark/mods --mod-pool benchmark/dependencies/abnormal-retest --mod-pool benchmark/dependencies/stage3 --output build/benchmark/stage3-runs --stages admission,menu --timeout 300 --heap 4g --discard-mod-copies --min-free-mib 1024
```

Output was saved under build/benchmark/stage3-runs/. Each sample and stage used an independent JVM/runDir. Stage failures retained evidence and allowed testing to continue to the next sample; COMPLETE did not mean every sample passed. If interrupted, use --resume with the actual batch directory printed by the terminal, rather than resuming Stage 2's old results.

### Stage 3 Results

Batch `20261008T235419Z-06dd7cbd` reached COMPLETE with 102 stage-result records. All 34 samples passed dependencies and admission. menu produced **29 PASS and 5 FAIL, with no timeouts, input errors, or skips**. **29/34 = 85.29%** was the main-menu pass rate for this group of samples whose runtime dependencies had previously been incomplete. Original audits for the 29 passes were rechecked for SUCCESS, main-menu, client-complete, and exit code 0.

| case_id | menu duration in this run | Primary blocking issue |
| --- | ---: | --- |
| mod0032-aether_enhanced_extinguishing-1.21.1-1.0.0-fabric | 6.718 seconds | Dynamically generated me.shedaniel.gen.mixin.MassExport_1 was not visible during Mixin PREPARE. |
| mod0204-colorwheel-neoforge-1.3.0-beta3-mc1.21.1 | 11.225 seconds | The selected Sodium 0.8.13 lacked the NativeWindowHandle platform class, causing Window definition to fail. |
| mod0357-expandedae-2.1.3 | 22.737 seconds | AE2wtlibItems from the complete ae2wtlib was still absent; the embedded API did not contain this implementation. |
| mod0538-iris-neoforge-1.8.14-beta.1-mc1.21.1 | 11.233 seconds | Shared the same missing Sodium platform class as Colorwheel. |
| mod0539-IrisSearch-1.8.1-neoforge | 11.314 seconds | Depended on the same Iris/Sodium combination; its own functionality had not yet been tested. |

The five remaining failures fell into three groups: **one invisible dynamic Mixin class, one still missing the complete ae2wtlib, and three sharing a missing Sodium platform implementation class**. The Aether-related sample's earlier missing DeferredRegister class was resolved; the current blocker was mm_shedaniel's dynamically generated MassExport_1 being invisible during PREPARE. Logs showed that MM had started, so generated-class registration and bytecode-query timing needed investigation. Expanded AE's earlier missing EAEConfig was supplied, but a deferred task then referenced AE2wtlibItems. Only ae2wtlib_api was present, without the complete item implementation.

Colorwheel, Iris 1.8.14, and IrisSearch all lacked NativeWindowHandle while defining Minecraft Window. Read-only inspection confirmed that WindowMixin in the Sodium 0.8.13 JAR selected for Stage 3 referenced this class, but neither the root archive nor nested content provided it, and the file contained no embedded service JAR. The service JARs in 0.6.0 and the local 0.6.13 did contain the class. Checking only the previously reported VertexSerializer had not revealed this subsequent type requirement; a complete Sodium platform implementation still needed to be matched. INSTANCE_TAINTED was a cascading state after the first definition failure. The evidence at this point applied to the selected file and combination, rather than confirming three independent NF defects or a problem in every Sodium release archive of the same version.

Stage 1 had saved a standalone Sodium 0.8.13 target as PASS, whereas the current cases used an Iris combination. Historical results were retained unchanged, with companion packages and specific loading paths requiring comparison. Detailed evidence, log line numbers, and next steps are in the [analysis of remaining Stage 3 failures](benchmark/stage3-results-analysis-2026-10-09.md) and the [batch results snapshot](benchmark/stage3-results-analysis-2026-10-09.json). That update only analyzed and archived results; it did not change code or launch retests.

See the [Stage 3 case list](benchmark/stage3-retest.csv), [preparation record](benchmark/stage3-plan-2026-10-09.md), [dependency sources and hashes](benchmark/stage3-dependency-acquisition-2026-10-09.json), [input changes](benchmark/stage3-input-changes-2026-10-09.json), [dependency preflight](benchmark/stage3-preflight-2026-10-09.json), and [missing-class verification](benchmark/stage3-class-verification-2026-10-09.json) for individual dependency changes and their sources.

## Stage 4: Retesting

Of the 34 samples in Stage 3, five had menu FAIL results, including four with incomplete runtime-dependency inputs. After completing those inputs, the four samples were assigned to a separate retest batch. The dynamic Mixin issue in the other Aether extension sample remained under separate investigation.

### Dependency Additions and Verification

| Number | Target mod | Change in this stage |
| --- | --- | --- |
| 0204 | Colorwheel 1.3.0-beta3 | Replaced the cached Sodium 0.8.13 child archive with the complete official release of the same version; retained Iris 1.8.14-beta.1. |
| 0357 | Expanded AE 2.1.3 | Added complete AE2WTLib 19.2.5; retained AE2 19.2.17, GuideMe 21.1.19, ExtendedAE 2.2.38, and Glodium 2.2. |
| 0538 | Iris 1.8.14-beta.1 | Used the complete Sodium 0.8.13 release archive. |
| 0539 | IrisSearch 1.8.1 | Retained Iris 1.8.14-beta.1 and used the complete Sodium 0.8.13 release archive. |

**The Sodium cause was confirmed: Stage 3 preparation had mistaken an extracted cached runtime child JAR for the complete release archive.** Its SHA-256 matched the `*-mod.jar` inside the complete official download exactly; the missing NativeWindowHandle was located at the root of the official outer archive. Stage 4 replaced it with the complete release, still version 0.8.13, without patching in classes extracted from older versions. The three Stage 3 FAIL records were retained unchanged. Their specific blocker came from an input-preparation error and cannot count as three independent NF compatibility defects.

AE2WTLib 19.2.5 contained the missing AE2wtlibItems. Its required AE2 range was `[19.2.13,20.0.0)`, matching the existing AE2 19.2.17. Its API requirement was fixed at 19.2.5, matching the target's embedded version.

Published hashes were verified for both official release JARs. Recursive dependency preflight was **READY** for all four samples. All seven missing-class/representative-class checks passed on the actual selected inputs, and target JAR and shared runtime file hashes matched Stage 3. These were input and static checks at preparation time, not evidence that the actual main menu had passed.

### Launch Command (Completed)

The user ran this batch serially through admission and menu, with a maximum of 300 seconds per stage and a 4 GiB heap. The existing runtime launched JVMs directly, retaining logs, crash reports, and audits.

```powershell
Set-Location 'C:\Users\gengy\Desktop\NeoForbric'
python tools/benchmark_mods.py --runtime build/benchmark/runtime.json --cases benchmark/stage4-retest.csv --mod-pool benchmark/mods --mod-pool benchmark/dependencies/abnormal-retest --mod-pool benchmark/dependencies/stage3 --mod-pool benchmark/dependencies/stage4 --output build/benchmark/stage4-runs --stages admission,menu --timeout 300 --heap 4g --discard-mod-copies --min-free-mib 1024
```

Output was saved in a new batch under `build/benchmark/stage4-runs/`. Because inputs changed, a new batch was required. Subsequent interruptions can be resumed using `--resume` with this batch's output directory.

### Stage 4 Results

Batch `20261009T002509Z-1a402572` reached COMPLETE with 12 stage-result records: four dependencies PASS, four admission PASS, and four menu PASS. **The main-menu pass rate for this stage was 100% (4/4)**, with no failures, timeouts, input errors, or skips. All four main-menu passes were verified against original audits for SUCCESS, main-menu, client-complete, and exit code 0.

| Number | Target mod | dependencies | admission | menu | admission duration | menu duration |
| --- | --- | --- | --- | --- | ---: | ---: |
| 0204 | Colorwheel | PASS | PASS | PASS | 4.546 seconds | 20.091 seconds |
| 0357 | Expanded AE | PASS | PASS | PASS | 4.765 seconds | 21.574 seconds |
| 0538 | Iris 1.8.14-beta.1 | PASS | PASS | PASS | 4.502 seconds | 19.996 seconds |
| 0539 | IrisSearch | PASS | PASS | PASS | 4.474 seconds | 19.935 seconds |

All four samples completed the main-menu stage after their dependencies were supplied in full. These results support the earlier diagnosis of incomplete runtime inputs; Stage 3's failure records were retained. [Stage 4 results and audit verification](benchmark/stage4-results-2026-10-09.json).

Case list and records: [Stage 4 case list](benchmark/stage4-retest.csv), [preparation record](benchmark/stage4-plan-2026-10-09.md), [official sources and hashes](benchmark/stage4-dependency-acquisition-2026-10-09.json), [input changes](benchmark/stage4-input-changes-2026-10-09.json), [recursive dependency preflight](benchmark/stage4-preflight-2026-10-09.json), [class verification on actual inputs](benchmark/stage4-class-verification-2026-10-09.json), [proof of Sodium child-archive provenance](benchmark/stage4-sodium-package-proof-2026-10-09.json), and [shared runtime file verification](benchmark/stage4-runtime-baseline-2026-10-09.json).

# Conclusion

Of the 1136 test samples, 1117 entered the valid testing scope after excluding 19 INPUT_ERROR cases. Of these, 1085 successfully reached the main menu, giving a main-menu pass rate of **97.14%**.

The remaining failures were not all attributable to NeoForbric itself. Main causes included mods incompletely declaring their actual runtime dependencies, dependency-version or platform-implementation mismatches, incorrect loader profile selection, unmet Java version requirements, early downloader exits, legacy shaded libraries or unusual packaging structures, invisible dynamically generated Mixin classes, and a small number of lifecycle, class-index, or runtime compatibility issues still requiring confirmation.

After Stage 2 and Stage 3 retesting and dependency corrections, many samples that had failed in Stage 1 could reach the main menu normally. This shows that Stage 1 FAIL, TIMEOUT, or missing-class errors cannot directly be equated with NeoForbric compatibility defects.

This experiment therefore indicates that, in Minecraft 1.21.1, NeoForbric achieved a high level of compatibility for the 1136 mod samples collected from several real modpacks, specifically at the level of an individual mod and its required dependencies completing loading and reaching the main menu.

**97.14% represents only the main-menu pass rate. It does not establish that all mod features work, and cannot directly be equated with modpack stability or complete compatibility.** World loading, server operation, networking, rendering features, mod-specific functionality, and combinations of mods from different loader ecosystems still require further testing.

# Postscript

## 2026/10/08 Experiment and Interruption Record

| Time (Asia/Tokyo) | Record |
| --- | --- |
| Batch started at 01:09 | The first 44 samples completed both stages. Sample 45 passed admission, but replacing the results file encountered WinError 5, delaying the summary write. |
| Resumed at 07:41 | Recovered sample 45's admission result and completed through sample 67. Execution then stopped because free space fell below the 1 GiB reserve, with status STOPPED_LOW_DISK. |
| Resumed again at 09:00 | Completed through sample 501; sample 502's admission was interrupted. 18:33:12 was the status-file write time, not the exact process exit time. |

The harness added resumption, file-replacement retries, a persistent fallback for file locking, and a mutually exclusive batch lock. The relevant 26 tests passed. Historical details are in the [experiment interruption record](benchmark/experiment-interruption-2026-10-08.md).

## 2026/10/08 Cleanup of Passed Samples

At the user's request, **453 target JARs** with menu PASS among samples 1–500 were removed from `benchmark/mods`: 405 were deleted directly, while 48 still required by retained samples were moved to `benchmark/dependencies/retained-passed-1-500/` and hash-verified. Actual deletions freed approximately 868.29 MiB.

The directory retained **683 JARs**: 47 failed, timed-out, or input-error samples from the first 500, plus 636 samples numbered 501–1136. The remaining file count was not the failure count. Passed sample 501 remained because it was outside the cleanup range. Cleanup did not change the historical results above.

Individual actions are in the [cleanup record](benchmark/cleanup-passed-1-500-2026-10-08.md) and [deletion and move list](benchmark/cleanup-passed-1-500-2026-10-08.json). Subsequent new batches can use the [remaining sample list](benchmark/experiment-remaining-after-1-500.csv) together with the retained dependency pool. The original batch's strict `--resume` still referenced paths that had been deleted or moved, so it could not be resumed directly after cleanup. Restoring this report did not launch an experiment.

## 2026/10/09 Recording Stage 1 Results

The second resumed batch for samples 501–1136 completed with final status COMPLETE. Its latest results for 636 samples were merged with the earlier 500 samples numbered 1–500, excluding the duplicated sample 501 in the old snapshot, to produce Stage 1 statistics for all 1136 samples. The latest conclusions and complete abnormal-case lists were saved in the [complete Stage 1 record](benchmark/experiment-stage1-final-2026-10-09.md) and its JSON snapshot, with a separate backup of the main report. This update only recorded results; it did not launch new experiments or delete logs.

## 2026/10/09 Stage 2 Preparation

Normal targets were cleaned up, retaining 95 abnormal samples and 29 required dependencies separately. Incomplete initial dependency information caused cleanup to omit two dependencies required by archers. They were restored from the original release versions and checked against the original SHA-256 hashes. Repeated preflight produced 76 READY and 19 INPUT_ERROR cases, matching Stage 1's abnormal-input list. Individual actions are in the [normal Stage 1 sample cleanup list](benchmark/cleanup-normal-stage1-2026-10-09.json). The Stage 2 list and plan were generated; actual retest results were to be recorded after the user's run.

## 2026/10/09 Stage 2 Results and Log Analysis

All 285 batch-result records were checked. The first fatal exception was traced individually for 65 stage failures, and 19 input errors were listed separately. Final audits and probes for the 11 retest passes were reverified successfully. Logs, crash reports, concise audits, JAR metadata and packaging checks, and result snapshots were independently archived. No new experiment was launched and no files were deleted.

## 2026/10/09 Stage 3 Preparation

A new case list was created for the 34 samples with incomplete runtime dependencies, explicitly adding or matching dependencies. All 34 passive dependency plans were READY, and all 36 missing-class/dependency representative-class checks found matches in the actual selected inputs. Shared runtime files matched Stage 2's hashes. Sources, versions, and SHA-256 hashes were saved, and a launch command for a new batch was supplied. Actual admission/menu results were to be filled in after execution.

## 2026/10/09 Stage 3 Results and Remaining Failures

Of the 34 completed samples, 29 passed the main menu and five failed. The first fatal exception and cause chain for each failure were recorded, grouped as dynamic Mixin (1), still missing the complete ae2wtlib (1), and the current Sodium input lacking platform implementation classes (3). Logs, crash reports, concise audits, and archive checks were saved independently. Audits and probes for the 29 passes were reverified. No new test was launched during this analysis.

## 2026/10/09 Stage 4 Preparation

Complete Sodium 0.8.13 and AE2WTLib 19.2.5 were supplied, and Stage 3's mistaken use of a cached Sodium child archive was documented. The new list contained four targets, all with READY dependency preflight and seven passing static class checks. Target and runtime hashes matched Stage 3. Actual admission/menu testing had not yet started at preparation time; results were to be filled in after the user's run.

## 2026/10/09 Stage 4 Results and Final Main-Menu Pass Rate

All four Stage 4 targets passed dependencies, admission, and menu. Original audits and exit codes were checked. Merging the latest result for each of the 1136 original samples produced 1085 PASS, 32 FAIL, 19 INPUT_ERROR, and 0 TIMEOUT. The final main-menu pass rate for valid inputs was 97.14% (1085/1117), and the proportion across all samples was 95.51% (1085/1136). Original stage results were retained, and a final summary snapshot was added. This update only recorded and verified results; it did not launch a new experiment.
