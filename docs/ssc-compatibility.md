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

## GeckoLib 世界渲染访问权限

进入世界后的 `GeoRenderer.checkAndRefreshBuffer` 抛出 `IllegalAccessError`，因为 NF 的 `NeoGameProvider.getBuiltinTransforms` 只把 `net.minecraft.*` 识别为游戏类。`VertexMultiConsumer$Double` 和 `BufferBuilder` 属于 `com.mojang.blaze3d.*`；GeckoLib 的 AW 虽已完成 remap / 注册，定义这些类时却没有执行 `CLASS_TWEAKS`。

现在游戏包范围与 [Fabric Loader 0.19.5 的 MinecraftGameProvider](https://github.com/FabricMC/fabric-loader/blob/0.19.5/minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/MinecraftGameProvider.java) 对齐，包括 `com.mojang.blaze3d.*`、`math.*`、`realmsclient.*` 等游戏包。GeckoLib 指定的内部类、`first` / `second` 字段及 `BufferBuilder.building` 在类定义前由 Fabric ClassTweaker 放宽。

新增回归在独立 JVM 中使用实际 `FabricTransformer`，验证其他包的调用者能读取内部类字段，并验证未声明 AW 的类保留原有访问权限。该检查在修复前失败，修复后通过。

```powershell
./gradlew.bat :loader:test
./gradlew.bat runClient -PsscRenderProbe
```

`sscRenderProbe` 调用真实 GeckoLib `checkAndRefreshBuffer`：先检查两个正在构建的缓冲区，然后结束构建、检查两个缓冲区被重新获取。随后创建单独的 `neoforbric-ssc-probe-<UUID>` 世界，检查第一人称渲染 100 tick、保存截图并退出。结果写入 `run/client/ssc-render-probe.txt`，截图为 `run/client/screenshots/neoforbric-ssc-world.png`。

19:15 新增到用户 mods 目录的 `shape-shifter-curse-addon-8.0.0-dev.1+1.21.1.jar` 包含一份未声明的 Yarn `named` AW（例如 `net/minecraft/item/ItemStack`）。旧版 NF 扫描了所有具有 AW 标头的资源，因此 PREPARE 在 `FABRIC_ACCESS_NAMESPACE` 校验时拒绝。最初的 GeckoLib 验证将此前 13 个模组 JAR 复制到 `build/ssc-compat/render-mods`，使用以下命令；新增 addon 保留在用户目录。

```powershell
./gradlew.bat runClient -PsscRenderProbe -PclientModsDir=build/ssc-compat/render-mods
```

2026-10-06 19:21（日本时间）独立副本实测通过：真实 GeckoLib 方法输出 `GECKO_BUFFER_PROBE_OK originalAndRefreshed=true`，测试世界渲染 100 tick 后输出 `SSC_RENDER_PROBE_OK` 并正常退出，Gradle 为 `BUILD SUCCESSFUL`。日志位于 `build/ssc-render-isolated.log`。这一检查覆盖内部类 / 字段访问、缓冲区刷新及新世界第一人称渲染；完整变身玩法仍需单独验证。

## 未启用的 addon AW 资源

addon 的代码已经是 `intermediary`，其 `fabric.mod.json` 没有 `accessWidener` 属性，`ssc_addon.accesswidener` 是未启用的资源。[Fabric Loader 0.19.5](https://github.com/FabricMC/fabric-loader/blob/0.19.5/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java#L485) 只读取元数据选择的访问文件；NF 现在使用同一个 Fabric 元数据解析器的 `getClassTweaker()` 结果来决定 remap 的资源。

未声明的访问文件保留原始内容；实际声明的文件必须存在并经过原有命名空间 / 符号校验，失败消息包含资源路径和来源 JAR。Apoli 的 named Mojang AW 和 GeckoLib 的 intermediary AW 仍正常转换。新增四项回归覆盖未声明资源、扩展名与路径不同的声明文件、声明文件缺失，以及声明的未知 named 规则。完整组合的启动验证日志为 `build/fabric-declared-access-rules.log`。

## refmap 模组的 Shadow 成员

addon 的 `SscAddonTravelMixin` 声明 `protected @Shadow field_6282:Z`，别名包括 `jumping`，但其 refmap 只有 `travel` 注入选择器。`field_6282` 对应 Mojang `LivingEntity.jumping`。旧版 NF 只对标记 `Fabric-Loom-Mixin-Remap-Type: static` 的 JAR 启用 TinyRemapper 的 Mixin 扩展；这留下了原名声明及引用。Mixin 查找别名时命中 protected `jumping`，因其不是 private / synthetic 字段而拒绝应用。

NF 现在对所有 Fabric 输入启用 Mixin 扩展的 `HARD` 部分，转换 Shadow 等成员声明并传播到字节码引用；`SOFT` 部分仍仅用于 Loom static 输入，旧式注入选择器继续通过 refmap 解析。`@Shadow(remap=false)` 保留原名，Mixin 自身的别名校验保持有效。三项 TinyRemapper 回归覆盖 refmap 模式、static 模式和禁用 Shadow remap 的声明。完整启动日志为 `build/fabric-shadow-remap.log`。

2026-10-06 20:01（日本时间）包含 addon 8.0.0-dev.1 的完整 mods 组合实测进入主菜单，输出 `CLIENT_PROBE_OK`，渲染 5 帧后正常退出，Gradle 为 `BUILD SUCCESSFUL`；原 Shadow 别名错误未再出现。59 项 loader 测试和 4 项 installer 测试通过，根目录 `neoforbric-installer.jar` 已重新打包。启动日志仍包含 remapper 对混合命名空间 / 不存在的可选目标的诊断提示；本次验证范围为初始化、Mixin 应用及主菜单，尚未验证 addon 的世界内玩法。
