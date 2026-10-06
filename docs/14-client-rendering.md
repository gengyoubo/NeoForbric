# 客户端渲染、模型、GUI 与资源

**服务端逻辑通过不能推出客户端兼容。** 本轮调查主要扩展类型与阶段，未全面审计三端所有 client Patch，也未测试第三方替代渲染器。

## 1. 必须拆开的支持领域

| 领域 | Fabric 样本 | Forge / NeoForge 样本 | 统一服务的检查点 |
| --- | --- | --- | --- |
| 世界 render events | WorldRenderEvents 多个调用阶段与 context | RenderLevelStageEvent 的 stage / context | 相对 terrain、entity、translucent 的位置，矩阵 / buffer 是否可用 |
| entity / BlockEntity renderer | 各 renderer registry 与 callback | EntityRenderersEvent.RegisterRenderers / AddLayers 等 | 注册窗口、context、layer 构建与 reload 重建 |
| 模型加载 | ModelLoadingPlugin，加载解析与 bake modifier | ModelEvent、geometry loader / baked model 扩展 | 输入模型类型、材质依赖、bake 生命周期和最终对象 ABI |
| Shader | CoreShaderRegistrationCallback | RegisterShadersEvent | resource provider、vertex format、创建 / 替换与旧资源释放 |
| GUI / Screen / HUD | ScreenEvents / HudRenderCallback 等 | screen 注册、GUI 渲染事件与扩展 | init / resize / render / input、取消和 focus 规则 |
| Keybind | KeyBindingHelper | RegisterKeyMappingsEvent | 注册时机、冲突 / context、按下 / 消费事件 |
| Particle | type / provider 注册与 sprite set | RegisterParticleProvidersEvent | provider 类型、资源准备和纹理重载 |
| RenderType / 扩展模型 | Fabric renderer / mesh 体系及 vanilla render layer | RenderType、模型数据、baked model 扩展 | 层次、buffer ownership、AO / 光照 / tint / quad 数据 |

依据：[Fabric WorldRenderEvents][fa-worldrender]、[Fabric ModelLoadingPlugin][fa-model]、[NeoForge RenderLevelStageEvent][n-renderstage]、[EntityRenderersEvent][n-renderers]、[ModelEvent][n-model]、[Forge ModelEvent][f-model]、[NeoForge shader 事件][n-shader]、[Fabric shader callback][fa-shader]。剩余入口源码列于 [来源索引](stage3-sources.md)。

同为“渲染完成”可能一个发生在 translucent flush 前，另一个发生在它之后。阶段名字相似，图像、深度和 buffer 行为仍可能不同。不能把所有 AFTER 类型事件汇总到 frame TAIL。

## 2. 模型兼容包含对象 ABI

Fabric ModelLoadingPlugin 提供加载上下文和 before / after bake 等修改能力；NeoForge ModelEvent 在不同阶段注册 geometry loader、修改 bake 结果或通知 baking 完成。它们有不同模型对象和扩展接口，需明确支持的是哪一层。[Fabric ModelLoadingPlugin][fa-model]、[NeoForge ModelEvent][n-model]

NeoForge 自定义 loader JSON 指向加载器 / geometry 定义；把资源文件放入包不代表 Fabric 客户端会解释该模型。跨生态需要实现格式、烘焙与渲染结果，或声明模型类型不支持。[NeoForge Model Loaders 文档](https://docs.neoforged.net/docs/1.21.1/resources/client/models/modelloaders/)

renderer 扩展可能直接调用 injected interface 和 Patch 成员。图像正确还不足以验证动态模型数据、block entity updates、material / render type 查询。应将接口依赖列入 [ABI 清单](08-access-and-transformers.md)。

## 3. 线程与资源生命周期

模型或 shader prepare 可以异步，实际应用、GPU 创建和销毁须遵守对应调用点的线程要求。用游戏逻辑 main executor 替代 render thread 不是完整保证；必须确认当前上下文与 render task 队列。

reload 后 model / shader / sprite / renderer cache 可能需要替换。统一服务应记录 resource generation、prepare / apply 和释放顺序，不能让 listener 保留上一代 shader 或 sprite。client init 一次性注册与 reload 多次重建分别处理，参见 [资源专项](15-resources-and-datagen.md)。

Screen resize / reopen 与 resource reload 也不是同一生命周期；不要每次 resize 重复注册 keybind / screen listener。输入事件的取消、屏幕 focus 和 widget 先后需要单独行为测试。

## 4. 替代渲染器与首批范围

Sodium / Iris 等替代渲染器会改变调用路径和 mesh / shader 行为，本轮未审计其实现；应固定具体 JAR 后单独扩展支持矩阵。不要从 WorldRenderEvents 名称可用推断全部路径仍执行。

首批建议只覆盖 vanilla renderer 下的简单 entity renderer、particle、screen、keybind、HUD 和一种基本模型；复杂 geometry、Fabric mesh 与 NeoForge model-data 互通、第三方 shader 和替代渲染器分开声明。首批范围是建议，未有实现通过。

## 5. 待执行探针

| 编号 | 场景 | 验收 |
| --- | --- | --- |
| S3-C01 | 各阶段绘制 marker，实体 / terrain / translucent 交叠 | 调用时序、深度与 buffer flush，参考截图 |
| S3-C02 | 自定义模型 loader 与 bake modifier，资源 reload 两次 | 模型 ABI、依赖解析和最终几何 / 材质 |
| S3-C03 | shader 创建、替换、损坏资源 | 正确线程、诊断、释放与失败状态 |
| S3-C04 | entity layer、particle sprite、block entity 动态模型 | 注册与资源代次、网络更新后可见结果 |
| S3-C05 | GUI init / resize / focus / reopen / 输入取消 | 次数、焦点、按键消费与不重复监听 |
| S3-C06 | 专用服务器加载同组 mod | 无客户端类首次定义和 GPU 初始化 |
| S3-C07 | 固定替代渲染器的单独组合 | 指定调用路径与图像结果；单列兼容结论 |

[f-model]: https://github.com/MinecraftForge/MinecraftForge/blob/a11d1936c53f1f93787f239ff42cb8e93a1d037a/src/main/java/net/minecraftforge/client/event/ModelEvent.java
[fa-model]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.java
[fa-shader]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/CoreShaderRegistrationCallback.java
[fa-worldrender]: https://github.com/FabricMC/fabric/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents.java
[n-model]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/ModelEvent.java
[n-renderers]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/EntityRenderersEvent.java
[n-renderstage]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java
[n-shader]: https://github.com/neoforged/NeoForge/blob/a2d6402a3c1eec093aef7e7d10ac5145906c199e/src/main/java/net/neoforged/neoforge/client/event/RegisterShadersEvent.java
