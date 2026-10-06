# Forge / NeoForge Patch 审计

范围：Minecraft 1.21.1；2026-10-06 获取的上游固定提交。完成了完整 Git tree 的**文件路径统计**和七个代表性 Patch 文件中相关片段的静态检查；没有逐个审计全部修改片段，没有构建或运行修改后的游戏。

## 1. 数量与口径

| 项目 | 固定提交 | Minecraft Java Patch 文件 | 路径含 `/client/` |
| --- | --- | ---: | ---: |
| Forge | `a11d1936c53f1f93787f239ff42cb8e93a1d037a` | 642 | 152 |
| NeoForge | `a2d6402a3c1eec093aef7e7d10ac5145906c199e` | 733 | 185 |

Forge 统计 `patches/minecraft/` 下的 `.java.patch`；NeoForge 统计 `patches/net/minecraft/` 下的 `.java.patch`。两个 Git tree 响应均未截断。去掉各自 Patch 根目录后，有 **570 个相同目标路径**，另有 Forge 独有 72 个、NeoForge 独有 163 个。

相同目标路径只说明修改了同一个类，不能解释为相同内容、等价行为或冲突数。`/client/` 计数也只是目录过滤，不是完整物理侧分析。统计输入见 [调查快照](upstream-snapshot.json)，复现步骤见 [来源说明](sources.md)。

## 2. 不能只看 `.patch`

NeoForge 的 [injected-interfaces.json](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/resources/META-INF/injected-interfaces.json) 在此提交包含 **90 个目标类型键**，其中 `ItemStack`、`Entity`、`LivingEntity` 等被赋予扩展接口。其 [访问转换规则](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/resources/META-INF/accesstransformer.cfg) 也独立于 Patch 文件。

因此，“完整审计 Minecraft 修改”还必须包括接口注入、AT、coremod、Mixin、构建期转换与运行时服务。90 是配置中的目标键数量，不是已完成的接口实现数量。

## 3. 建议按修改片段分类

| 类别 | 典型内容 | 统一方向 | 既有 JAR 的额外条件 |
| --- | --- | --- | --- |
| A：通知插入 | Tick、世界加载、服务器停止 | 统一 Hook | 保留真实触发位置、次数与线程 |
| B：可变决策 | 伤害、掉落、交互取消、结果改写 | 领域事件与决策上下文 | 保留早退、优先级、结果与副作用顺序 |
| C：结构 / ABI | 新方法、字段、父类、接口 | 必要结构转换及服务门面 | 方法描述符、继承和对象类型必须实际匹配 |
| D：状态 / 持久化 | capability、attachment、缓存、存档 | 统一状态服务及生态视图 | 生命周期、复制、序列化、失效和同步 |
| E：协议 / 渲染 / 资源 | 注册同步、网络扩展、模型管线 | 专项子系统 | 完整协议或渲染契约，无法用一个通知代替 |
| F：行为修正 | 原版逻辑修正、算法替换 | 明确保留、重写或排除 | 做原版与原生态行为对照 |

分类是建议，不是已完成的全量结果。一个 Patch 文件可以属于多类，审计单位应是具体修改片段与调用路径。

## 4. 七个 Patch 文件样本

### MinecraftServer：两端各一份

- [Forge MinecraftServer.java.patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/server/MinecraftServer.java.patch)
- [NeoForge MinecraftServer.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/MinecraftServer.java.patch)

**观察**：两端都插入服务器生命周期、世界加载 / 卸载与 Tick 调用；文件还包含数据包配置和额外状态。NeoForge 样本另有时间同步自定义 payload 分支。

**建议**：先提取服务器与世界通知作为 A 类原型；数据包选择、状态和时间协议分别处理。Fabric [MinecraftServerMixin](https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-lifecycle-events-v1/src/main/java/net/fabricmc/fabric/mixin/event/lifecycle/MinecraftServerMixin.java) 展示了另一组真实注入位置，可用于对照。

**待验证**：正常启动、启动失败、崩溃退出和正常停止是否各触发正确事件；Pre / Post Tick 的范围是否一致；主世界与其他维度是否重复或遗漏。不能同时保留原通知、再额外插入相同统一 Hook。

### LivingEntity：两端各一份

- [Forge LivingEntity.java.patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/entity/LivingEntity.java.patch)
- [NeoForge LivingEntity.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/LivingEntity.java.patch)

**观察**：Forge 样本包含 `onLivingAttack`、`onLivingHurt`、`onLivingDamage` 等调用；NeoForge 样本采用 damage container，并在伤害不同位置调用 incoming / pre / post 逻辑。它们不是把包名前缀替换后就相同的流水线。

**建议**：将伤害做成独立领域模型，保留原始值、护甲 / 吸收等处理阶段、取消位置及最终副作用。既有监听器分别接入它原来所在的阶段；不把所有事件合并成一个 `DamageEvent`。

**待验证**：取消伤害、修改伤害、盾牌格挡、吸收、死亡、图腾和掉落的顺序；第三方对扩展方法的覆写是否生效。该领域暂不进入最小 MVP。

### ItemStack：两端各一份

- [Forge ItemStack.java.patch](https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/patches/minecraft/net/minecraft/world/item/ItemStack.java.patch)
- [NeoForge ItemStack.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/item/ItemStack.java.patch)

**观察**：Forge 样本将 `ItemStack` 扩展为 `CapabilityProvider<ItemStack>` 并实现 `IForgeItemStack`，且在构造时收集 capability；两端都有物品放置 Hook。NeoForge 的部分扩展类型来自前述独立接口注入配置，不能只凭它的 Patch 顶部判断最终接口集合。

**建议**：放置决策属于 B 类；capability 与扩展类型属于 C / D 类。可以让新 API 使用统一查询服务，但已有字节码对父类 / 方法 / 接口的依赖仍需实际满足。JVM 的直接父类无法同时采用两套不同父类，应明确一个最终类结构并实现必要门面或转换。

**待验证**：构造、复制、拆分、替换堆栈、组件读写和同步是否维护同一份状态。仅让一个查询方法返回对象不能证明生命周期兼容。

### NeoForge Entity：一份

- [NeoForge Entity.java.patch](https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/world/entity/Entity.java.patch)

**观察**：实体继承 `AttachmentHolder`，保存 / 加载路径增加 attachment 序列化，并提供数据同步及 capability 查询入口。

**建议**：数据拥有者、存档生命周期和查询服务一起设计。参照 [NeoForge capability 文档](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/) 与 [attachment 文档](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/) 区分“暴露行为”和“存储数据”；不要假定它们等同于 Forge 的 capability provider。

**待验证**：保存重载、维度移动、实体复制 / 死亡、缓存失效与客户端同步。这里也需要结构与服务结合，不能用一个 `EntityLoaded` 通知替代。

## 5. 单项 Hook 的审计记录

建议后续为每个修改片段记录下表，而不是只登记“支持某事件”。

| 字段 | 必须记录的内容 |
| --- | --- |
| 来源 | 仓库、提交、文件、方法描述符与修改片段 |
| 调用点 | 操作之前 / 之后，发生在原版哪个检查或副作用附近 |
| 语义 | 输入、可变状态、输出、取消、早退与结果合并 |
| 执行条件 | 物理 / 逻辑侧、线程、重入、监听器顺序 |
| ABI | 新增类型、方法、字段、访问权限及第三方覆写入口 |
| 去向 | 统一 Hook / 服务、生态适配器及保留差异 |
| 证据 | 原生对照探针、最终字节码、事件轨迹和行为结果 |
| 状态 | 未审计 / 已设计 / 已实现 / 已验证 / 明确不支持 |

同一行为被多个生态同时监听时，需要指定一个真实调用入口和每个适配器的派发位置。既要防止重复派发，也要防止“Mixin 没报错但永远不会执行”。

## 6. 本轮能够与不能够证明什么

已证明：两个目标分支的 Patch 路径规模、部分接口注入配置，以及样本里同时存在通知、结构、状态和协议改动。

尚未证明：哪些 Patch 可以完全删除、Hook 可以覆盖多少模组、统一事件是否保持行为、具体 JAR 是否可混装。其余 639 个 Forge 和 729 个 NeoForge Patch 文件尚未逐片段阅读分类，后续需围绕原型覆盖范围继续审计。
