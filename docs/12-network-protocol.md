# 登录、配置、Play 握手与原生客户端边界

本轮静态调查的答案：**Fabric 客户端或原生 NeoForge 客户端都有可能连接满足其完整协议要求的 NeoForbric 服务端，但目前没有可运行实现或连线实测，不能声称任一组合已兼容。** 同一个服务端实例可按连接选择协议适配器，世界内容本身仍必须被对应客户端理解。

## 1. 不能把 payload 注册当握手完成

需要分开核对：vanilla 登录认证 / 压缩 / encryption 与 login query，configuration 状态的频道与版本协商、配置任务、注册表 / tag / config 同步，以及 play 状态的真实业务包和 registry-dependent codec。

| 阶段 / 能力 | Fabric 样本 | Forge 样本 | NeoForge 样本 |
| --- | --- | --- | --- |
| Login | ServerLoginNetworking 与 login query / response、查询事务和异步等待 | forge:login、LoginWrapper 及 SimpleChannel login 路径 | 本轮重点确认 configuration 协商；不能套用旧 Forge login 包 |
| Configuration | 独立 payload type registry / receiver、配置任务、可发送频道声明 | forge:handshake configuration 消息，mod / channel versions、registry / config 数据 | modded network query / setup、版本 / flow / optional 协商；Common register 等非 Neo 通道路径 |
| Play | 独立 play type / receiver，canSend 与实际 codec | SimpleChannel play、消息 discriminator / codec / 方向等 | payload registration 的 play 表、协商结果、处理线程策略 |
| 注册同步 | Fabric registry sync 自定义任务；动态 registry 另有 codec同步 | RegistryList / RegistryData 与快照 | NeoForge registry / data map 等任务及 vanilla 同步顺序 |

依据：[Fabric login Mixin][fa-login]、[Fabric config Mixin][fa-config]、[PayloadTypeRegistry][fa-payload]、[Fabric RegistrySyncManager][fa-regsync]、[Forge NetworkInitialization][f-netinit]、[ForgePacketHandler][f-packet]、[NeoForge NetworkRegistry][n-network]。

Fabric Loader metadata 并没有规定一个所有 Fabric mod 通用的严格 mod-list handshake；Fabric API 和各 mod 的频道、内容、版本检查仍可能拒绝连接。Forge 的固定 NetworkInitialization 则明确包含 ModVersions / ChannelVersions / RegistryList / RegistryData / ConfigData 等不同消息，不能只发送一份 Fabric mod ID 列表。[Fabric metadata parser][fl-metadata]、[Forge NetworkInitialization][f-netinit]

NeoForge 样本的核心协商以 payload 注册项的 ID、version、flow、optional 为输入，并有 extensible enum、feature flags、配置等其他条件。不要将“mod list 相同”作为充要条件，也不要为满足对端显示列表虚构本机不存在的协议功能。[NetworkComponentNegotiator][n-negotiator]、[NetworkRegistry][n-network]

## 2. 原生 NeoForge 协商的限制

`NetworkRegistry.register` 区分 CONFIGURATION / PLAY 和 PacketFlow；不允许注册阶段后补 payload，空协议列表 / 空版本 / 重复 ID 也会失败。协商版本不匹配、对端缺失必需项会拒绝；optional 的主要作用是允许对端没有它，双方都有时仍要检查版本与方向。[NetworkRegistry][n-network]、[NetworkComponentNegotiator][n-negotiator]

服务端 `initializeOtherConnection` 用本机项与对端空集先检查缺失的非 optional 项，再建立其他连接的频道声明。`initializeNeoForgeConnection` 则使用 NeoForge 查询与 setup。非 NeoForge 路径及后续 `minecraft:register` / `c:register` 支持，**不等于任意 Fabric 频道自动被当作已满足的 NeoForge 必需项**。[NetworkRegistry][n-network]

NeoForge `ServerConfigurationPacketListenerImpl` Patch 在 vanilla 配置前做网络协商，并明确要求在 vanilla 同步 tags 前同步 NeoForge registry。统一内核的配置队列必须遵守这种依赖。[Server configuration Patch][n-serverconfig]

每个配置任务有类型和结束条件。需要 client ack 的任务，服务端 enqueue 完不代表对端应用完；也不能跳过 ack 就进入 play。configuration 中不能无条件使用只有 play registry 状态才能解析的 buffer / codec。[NeoForge Configuration Tasks 文档](https://docs.neoforged.net/docs/1.21.1/networking/configuration-tasks/)、[PayloadRegistrar][n-registrar]

## 3. Fabric 客户端 + NeoForbric 服务端

| 服务端内容 / 协议 | 条件判断，尚未实测 |
| --- | --- |
| 只增加服务端行为、保持 vanilla 内容与 wire semantics | 可能经 vanilla / 其他连接路径进入；仍需检查所有加载 mod 的必需协议 |
| Fabric mod 双方同实现，频道、codec、configuration tasks 与 Fabric registry sync 已适配 | 可作为第一批 Fabric 原生客户端连接目标 |
| 混入 NeoForge mod，但所有其新增频道都可选，且客户端不需要其内容 | 可能连接；必须另查 registry、feature flags、枚举、data map 和配置任务，optional 单项不充分 |
| NeoForge mod 要求必需频道或客户端新增物品 / 实体 / codec | 普通 Fabric 客户端通常不满足；需客户端已有等价功能和完整协议适配，不能靠改 brand 绕过 |
| 自定义动态 registry 在客户端缺 codec | 无法保证解码；必须按源契约拒绝或使用明确设计的兼容投影 |

这里的“普通 Fabric 客户端”没有 NeoForbric 客户端插件。若额外安装桥接插件，这是另一种支持场景，应单独报告。

## 4. 原生 NeoForge 客户端 + NeoForbric 服务端

| 服务端模式 | 条件判断，尚未实测 |
| --- | --- |
| vanilla 行为子集、客户端所有必需要求允许非 Neo 服务端 | 可能通过客户端 other-server 路径；不证明服务端模拟了 NeoForge |
| 同一组 NeoForge mod 的兼容门面与基底 | 需要实现所选修订的 query / setup、payload codec / flow / version、registry / config / 其他任务及 play 行为 |
| 服务端另有 Fabric mod 的 server-only 行为 | 可在保持上述协议契约时作为候选；如果引入客户端未知内容仍失败 |
| NeoForge 客户端要求 Fabric mod 的自定义频道 / registry sync，但客户端无接收实现 | 不能仅由服务端适配解决；需要原客户端具备相应功能 |
| 任一客户端必需 payload、feature / enum / registry 条件不满足 | 应明确断开，不伪造成功回应 |

NeoForge `initializeOtherConnection(ClientConfigurationPacketListener)` 也检查本机必需项，并处理扩展枚举、feature flags 和配置等限制。这说明“能连接 vanilla 的某个 NeoForge 客户端”不代表“安装任意 NeoForge mod 后仍能连接”。[NetworkRegistry][n-network]

## 5. 统一网络服务建议

连接建立时保存独立状态：

```text
connection_id / physical side / flow
vanilla protocol state
detected peer profile + detection evidence
negotiated required / optional channels
channel version / codec / directions / permitted states
registry and tag generation
configuration task queue / ack / timeout
handler executor / world-ready barrier
```

协议适配器按连接实例选择，不能用全局 `serverIsNeoForge` 决定所有客户端。发送前检查对端已协商能力；收到方向 / 阶段非法、未协商包时按协议报错。游戏世界变更放到正确线程，但不能改变 login / config 异步等待的完成时机。

跨生态频道 ID 相同但 codec 不同属于冲突，不能按先注册者覆盖。静态 registry remap、动态 codec 内容同步、附件同步和 mod-specific handshake 分别完成；字节级一致性、压缩 / 包大小 / task ack 也需要实测。

## 6. 待执行连接矩阵

| 编号 | 对端与服务端 | 观察 |
| --- | --- | --- |
| S3-N01 | vanilla / Fabric API / NeoForge 无额外内容客户端 → vanilla 子集服务端 | 完整 login→config→play，而非只看到服务器列表 |
| S3-N02 | Fabric 双方固定测试 mod | login query、config ack、Fabric registry sync、play 包 |
| S3-N03 | NeoForge 原生客户端 + 同组 Neo mod | payload negotiation、registry / tags / config、进世界行为 |
| S3-N04 | 缺失必需项、optional 缺失、optional 双方版本不同 | 明确拒绝 / 可选禁用，双方版本差仍正确检查 |
| S3-N05 | 客户端未知静态条目与动态 codec | registry 层可定位失败；不以 ID 投影造出对象 |
| S3-N06 | 不同方向、过早包、超时或重复 ack | 不误推进任务和状态 |
| S3-N07 | Fabric 与 NeoForge 客户端同时连接同一实例 | 独立协商状态；所有共享内容可理解 |
| S3-N08 | 退出重连与 configuration 重入 / reload同步 | 不残留旧频道、registry generation 或任务 |

[f-netinit]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/NetworkInitialization.java
[f-packet]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/network/ForgePacketHandler.java
[fa-config]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerConfigurationNetworkHandlerMixin.java
[fa-login]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/mixin/networking/ServerLoginNetworkHandlerMixin.java
[fa-payload]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java
[fa-regsync]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.java
[fl-metadata]: https://github.com/FabricMC/fabric-loader/blob/fda9a7b84f0898f57b46fbbfda58795e7fa31cef/src/main/java/net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java
[n-negotiator]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/negotiation/NetworkComponentNegotiator.java
[n-network]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java
[n-registrar]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/network/registration/PayloadRegistrar.java
[n-serverconfig]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/patches/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch
