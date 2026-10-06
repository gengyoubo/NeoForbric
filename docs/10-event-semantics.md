# 事件总线、结果组合与线程

统一 Hook 可以决定何时触发生态门面，但“事件同名”不足以证明等价。取消、结果、异常、线程和重入都属于行为契约。

## 1. 源总线语义

| 维度 | Fabric callback | Forge EventBus | NeoForge EventBus |
| --- | --- | --- | --- |
| 分派 | invokerFactory 自定义 listener 调用方式 | 同一个事件对象给监听器，priority / 取消过滤 | 同一个事件对象，priority / ICancellableEvent 等 |
| 排序 | 注册顺序及可配置 phase DAG | HIGHEST→LOWEST，同优先级保留相应注册顺序 | 五级 priority；跨 mod dispatch 还受 FML 模式影响 |
| 取消 | 回调自定义返回值和短路规则 | cancelable 标记、setCanceled、receiveCanceled | ICancellableEvent、setCanceled、receiveCanceled |
| 结果 | 每个 invoker 自定义，无通用合并规则 | 部分事件 HasResult / DEFAULT、ALLOW、DENY | 具体事件自己的 TriState / Result / 可变字段 |
| 线程 | 在调用 invoker 的线程执行，框架不统一搬线程 | EventBus post 本身不等于并行 lifecycle 调度 | EventBus post 与 FML parallel dispatch 也是不同层 |
| 排队工作 | 回调没有全局 enqueueWork 等价物 | ParallelDispatchEvent 对应 DeferredWorkQueue | ParallelDispatchEvent 对应 DeferredWorkQueue 与阶段屏障 |

依据：[Fabric ArrayBackedEvent][fa-arrayevent]、[UseBlockCallback][fa-useblock]、[Forge EventBus 系列源码][f-bus]、[Forge Event 对象][f-event]、[NeoForge Bus 源码包][nbus-src]、[NeoForge FML 源码包][nfml-src]。Forge Bus 系列提交与精确依赖包一致性尚未验证。

## 2. priority、cancel 和结果并非同一件事

Fabric `UseBlockCallback` 的 invoker 遇到非 PASS 就返回，后续 listener 不运行。换成 Forge / NeoForge 风格的共享可变事件后，取消通常只过滤不接收 canceled 的监听器；receiveCanceled 的监听器仍可执行并修改状态。这两种路径不能用一个“boolean canceled”自动等价。[UseBlockCallback][fa-useblock]、[Forge ASMEventHandler][f-asmhandler]、[NeoForge Bus 源码包][nbus-src]（EventBus.passNotGenericFilter）

cancel 可被后续有权限的 listener 改回；不保证是不可逆终态。Result setter 也不是总线统一执行“DENY 永远优先”或“首次非 DEFAULT 胜出”：源事件对象可以反复写，最后调用点如何解释结果还要查对应 Hook。NeoForge 的 TriState 字段应按具体事件定义解释，不把所有事件塞进 Forge Event.Result。

Fabric phase 是用户定义标识及 before/after 约束；简单将 Forge NORMAL 对应 Fabric 默认 phase，会丢失 Fabric mod 已经声明的 phase 边。priority 还不能决定 Mixin 的先后；三个不同的排序系统应分别记录。

## 3. 并行 lifecycle 与 enqueueWork

Forge `ParallelDispatchEvent` 持有对应 DeferredWorkQueue。NeoForge FML 4.0.45 在 `dispatchParallelEvent` 中为阶段创建 work queue，分派 mod 任务，结束后通过 syncExecutor 执行排队工作。common / sided setup 的并行来自 FML 调度，而非每次游戏 EventBus post 都并行。[Forge ParallelDispatchEvent][f-parallel]、[Forge DeferredWorkQueue][f-workqueue]、[FML 源码包][nfml-src]（ModLoader、DeferredWorkQueue）

统一内核应在阶段屏障前完成源 API 的队列与 future / 异常处理。不能在发完 setup 后立即进入下一阶段，再让 enqueueWork 在后台碰注册表或客户端 renderer。运行事件应保留调用线程；网络 listener、渲染 listener 和逻辑服务器 listener 不能全部强行挪到同一个 main executor。

同优先级、跨 mod 顺序要说明 dispatch 模式：固定 FML 的 `postEvent` 和 `postEventWithWrapInModOrder` 都以 priority 为外层循环，在每个 phase 中遍历 mod order；wrap 路径还设置 active container。注册事件使用该 wrap 路径，parallel lifecycle 则不保证全体监听器的全局 priority 次序。不能因方法名含“mod order”就把一个 mod 的所有 priority 一次跑完。[FML 源码包][nfml-src]（ModLoader）

## 4. 跨生态统一 Hook 的设计建议

对每个 Hook 单独规定：游戏调用点、输入状态、允许修改字段、各生态内部 dispatch、跨生态相对顺序、结果归约器、原游戏行为继续条件和副作用发生边界。

例如 block use：必须说明取消发生在消费物品 / 放置 block / 扣耐久 / 发包之前还是之后。可选的新 Common API 可以规定一个统一结果；既有 Fabric 与 NeoForge 门面仍先各自执行原有 listener 规则，再由内核执行明示的跨生态合并策略。不同原生调用点如果无法同一时刻模拟，应标明支持限制。

不能把同一游戏动作先经真实 NeoForge Hook 再经额外 Fabric Mixin 重复发两遍。每个动作关联一个 call ID；门户和原生实现桥接时标明 ownership，审计事件对象与结果变化。

## 5. 重入、监听器变更与异常

注册 listener 的锁不等于 invocation 不可重入。listener 可以再次触发同类事件；应为每次调用建立独立上下文与 parent call ID，避免全局 canceled / result 状态串扰。也不能无条件禁止重入，改变原模组行为。

Fabric ArrayBackedEvent 注册时重建 invoker，旧调用使用原 invoker 的 handler 视图；Bus 也有自身 listener list / 缓存策略。应以原生探针对“回调内新增监听器是否在当前调用生效”定契约，避免跨生态桥自己遍历可变共享列表。[ArrayBackedEvent][fa-arrayevent]、[NeoForge Bus 源码包][nbus-src]

源 API 的异常处理需要保留或明确转换；不得 catch 后继续并报告所有 handler 成功。并行失败应在屏障收集 mod、listener、排队任务与原因。

## 6. 待执行探针

| 编号 | 场景 | 比较项目 |
| --- | --- | --- |
| S3-E01 | 多 priority、同优先级、Fabric phase DAG | 实际监听器次序及 phase 来源 |
| S3-E02 | 取消→receiveCanceled 监听器取消复原 | 过滤、执行次数、最终游戏结果 |
| S3-E03 | Fabric 首次非 PASS；多个 listener 覆写结果 | 原生内部短路与跨生态合并分别可见 |
| S3-E04 | parallel setup 的 enqueueWork 与失败 future | 调用线程、屏障、任务完成或失败 |
| S3-E05 | listener 内重发事件 / 注册新 listener | 嵌套上下文、当前与后续 handler 视图 |
| S3-E06 | 同一放置操作同时有两个生态监听器 | 不重复触发；取消与扣耐久 / 发包顺序正确 |

[f-asmhandler]: https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/ASMEventHandler.java
[f-bus]: https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/EventBus.java
[f-event]: https://github.com/MinecraftForge/EventBus/blob/d125933aa2577e194e5b9d8c305b445ace07baf9/src/main/java/net/minecraftforge/eventbus/api/Event.java
[f-parallel]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/fml/event/lifecycle/ParallelDispatchEvent.java
[f-workqueue]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/fmlcore/src/main/java/net/minecraftforge/fml/DeferredWorkQueue.java
[fa-arrayevent]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-api-base/src/main/java/net/fabricmc/fabric/impl/base/event/ArrayBackedEvent.java
[fa-useblock]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/api/event/player/UseBlockCallback.java
[nbus-src]: https://maven.neoforged.net/releases/net/neoforged/bus/8.0.5/bus-8.0.5-sources.jar
[nfml-src]: https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45-sources.jar
