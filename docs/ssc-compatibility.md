# SSC / Apoli 客户端样本

当前 `run/client/mods` 的 SSC 是 `1.10.0.30+1.21.1`，原始 SHA-256 为 `24d96eac33317c52eb7b76b444d1cad1965e232d2d12280ae8b48fa0185790ee`。GeckoLib 为 4.9.3，Fabric API 为 0.116.17+1.21.1；NF 使用 Fabric Loader 被动组件 0.19.5。

## 两个实际阻挡项

1. SSC 内置 `Apoli-Legacy-1.10.0.30+1.21.1-dev.jar`，SHA-256 为 `855a1a09c047302deb8b8cd8c3843304081be906652e88a3fd38ac2d161c83b9`。它声明 `calio-legacy` 与 `cloth-config >=15.0.127` 依赖，但没有打包这些依赖；根 SSC 也未把 Apoli 声明为必需依赖。因此 Fabric 的可选 nested 候选选择会排除 Apoli，随后 SSC 的 CCA 入口引用 `PowerHolderComponent` 时失败。已选中列表确实用于 remap 和类扫描，不存在仅准备根候选的问题。
2. 该开发包使用 Mojang 类 / 成员名，但 `apoli.accesswidener` 标头是 `accessWidener v1 named`。NF 验证 AW 所有目标类、字段 / 方法及其描述符与 Mojang 映射一致后，保留符号并规范化标头。Yarn 等其他 named 符号无法通过校验，仍拒绝；不会把任意 named 输入视为 Mojang。

## 补入的依赖

| JAR | 来源 |
| --- | --- |
| Calio-Legacy-1.11.0+1.21.1.jar | SSC 1.10.0.26 发布包内的 Apoli-Legacy-2.11.6.jar |
| PlayerAbilityLib-1.10.0.jar | 同上 |
| additionalentityattributes-2.0.2+1.21.1.jar | 同上 |
| cloth-config-15.0.140-fabric.jar | Modrinth 的 Fabric / Minecraft 1.21.1 发布版本 |

SSC 参考包从 [作者发布的 Modrinth 版本](https://modrinth.com/mod/shape-shifter-curse-unofficial-port/version/BNFvjsss) 下载，校验发布方 SHA-512 `b00aff3eda520c9df02bf806ce2d94e74356db9584f8a80d5a67dae624503729a4315b0ac626d416ef1ea6276b7a35ac623609effb96c6cfa6e38a71a32f0120` 后提取库。Cloth Config 校验 [发布版本](https://modrinth.com/mod/cloth-config/version/HpMb5wGb) 的 SHA-512 `1b3f5db4fc1d481704053db9837d530919374bf7518d7cede607360f0348c04fc6347a3a72ccfef355559e1f4aef0b650cd58e5ee79c73b12ff0fc2746797a00`。库均放入 NF 的 `run/client/mods`；SSC 原始 JAR 保留。

参考下载和提取清单位于 `build/ssc-compat`，启动日志位于 `build/fabric-loader-upgrade-client.log`；审计为 `run/client/audit.json`。`firstperson` 与 `trinkets` 是 SSC 的推荐项，缺少它们不会导致依赖解析失败。

## 验证

```powershell
./gradlew.bat :loader:test
./gradlew.bat runClient -PclientProbeFrames=5
```

回归覆盖 nested 模组选择后真实类在 G 中的加载、缺少依赖时的排除原因与 Mods 状态、依赖别名，以及 AW 的 intermediary 转换和 named Mojang 校验。

2026-10-06 19:02（日本时间）实际启动通过：49 项单元测试全通过；Apoli 1.10.0.30 和 SSC 完成初始化，客户端输出 `CLIENT_PROBE_OK main=1 client=1 menu=TitleScreen resourcesLoaded=true itemIdentity=true gameLoader=NeoForbric-Game`，在主菜单渲染 5 帧后正常退出，Gradle 为 `BUILD SUCCESSFUL`，审计结果为 `SUCCESS`。审计同时包含 `PowerHolderComponent` 的 `class-owner` 和 `class-defined`（loader=G），以及 `Args$1` 的生成类归属。截图位于 `run/client/screenshots/neoforbric-main-menu.png`。

这次验证范围为客户端初始化与主菜单；未验证世界内变身、模型动画和完整玩法。资源加载仍报告部分 SSC geometry 版本与 GeckoLib 支持版本不一致的警告。
