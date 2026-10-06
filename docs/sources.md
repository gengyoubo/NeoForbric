# 来源、快照与复现

调查日期：2026-10-06（日本时间）。技术事实优先采用 1.21.1 源码或明确版本的官方文档；没有用论坛转述作为技术依据。

## 1. 固定源码快照

| 项目 | 检索 ref | 完整提交 SHA | 用途 |
| --- | --- | --- | --- |
| [MinecraftForge](https://github.com/MinecraftForge/MinecraftForge/tree/a11d1936c53f1f93787f239ff42cb8e93a1d037a) | `1.21.1` | `a11d1936c53f1f93787f239ff42cb8e93a1d037a` | Patch、加载与网络实现 |
| [NeoForge](https://github.com/neoforged/NeoForge/tree/a2d6402a3c1eec093aef7e7d10ac5145906c199e) | `1.21.1` | `a2d6402a3c1eec093aef7e7d10ac5145906c199e` | Patch、接口注入、生命周期与网络 |
| [Fabric API](https://github.com/FabricMC/fabric/tree/83c07162d88a703d15c8dae50cbbc12f4e9e63c3) | `1.21.1` | `83c07162d88a703d15c8dae50cbbc12f4e9e63c3` | 生命周期、交互、资源与网络 API |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader/tree/fda9a7b84f0898f57b46fbbfda58795e7fa31cef) | `0.16.10` | `fda9a7b84f0898f57b46fbbfda58795e7fa31cef` | 固定入口与 Knot 行为样本；非最新版建议 |
| [Connector](https://github.com/Sinytra/Connector/tree/5a2f668ad492c1586c279a197423f5a1d1a16509) | `1.21.x` | `5a2f668ad492c1586c279a197423f5a1d1a16509` | 1.21.1 兼容路径与 JAR 转换 |
| [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI/tree/c2cb2ae0db344bd2efcf2d4fc8770d298377faa0) | `1.21.1` | `c2cb2ae0db344bd2efcf2d4fc8770d298377faa0` | Fabric API 在 NeoForge 上的实现参考 |
| [Forbric 候选参考](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/tree/520d7aad11cc7c9b8c8278b5f541e4429e1f7623) | `main` | `520d7aad11cc7c9b8c8278b5f541e4429e1f7623` | 26.2 自主内核与测试方法参考 |

这些提交通过 GitHub API 解析后固定。分支以后发生变化不影响本文的引用；调查快照不表示它们构成一个已经验证的运行时组合。

## 2. 官方文档

| 来源 | 支持的事实 / 使用范围 |
| --- | --- |
| [Fabric Loader](https://docs.fabricmc.net/develop/loader/) | Loader 与 API 的职责区分；跨版本概念以目标源码补证 |
| [fabric.mod.json](https://docs.fabricmc.net/develop/loader/fabric-mod-json) | 入口、侧别和元数据概念；具体入口调用顺序以固定 Hooks.java 为准 |
| [Fabric mappings](https://wiki.fabricmc.net/tutorial:mappings) / [迁移 mappings](https://wiki.fabricmc.net/tutorial:migratemappings) | 开发映射与命名转换；本报告限定混淆版本 1.21.1 |
| [Forge lifecycle](https://docs.minecraftforge.net/en/1.21.x/concepts/lifecycle/) | 构造、注册、setup、并行事件与排队任务 |
| [Forge registries](https://docs.minecraftforge.net/en/1.21.x/concepts/registries/) | 延迟注册与注册事件 |
| [Forge access transformers](https://docs.minecraftforge.net/en/1.21.x/advanced/accesstransformers/) | 成员访问转换与 SRG 命名要求 |
| [Forge SimpleImpl](https://docs.minecraftforge.net/en/1.21.x/networking/simpleimpl/) | 背景参考，示例存在与目标源码不一致之处 |
| [NeoForge events，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/events/) | 两类事件总线、阶段、优先级、取消和侧别 |
| [NeoForge registries，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/registries/) | 延迟注册、查询时机与数据包注册表 |
| [NeoForge sides，1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/sides/) | 物理侧与逻辑侧 |
| [NeoForge payload，1.21.1](https://docs.neoforged.net/docs/1.21.1/networking/payload/) | 类型注册、方向、协议阶段与处理线程 |
| [NeoForge capabilities，1.21.1](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/) | 行为查询、provider 与缓存失效 |
| [NeoForge attachments，1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/) | 数据附件与持久化；具体支持能力仍需按锁定运行时确认 |
| [NeoForge access transformers，1.21.1](https://docs.neoforged.net/docs/1.21.1/advanced/accesstransformers/) | 访问转换配置 |
| [Architectury API](https://github.com/architectury/architectury-api) | 多平台开发抽象的用途；未用于证明 1.21.1 混装能力 |

## 3. 本轮方法

1. 读取本地 `readme.md`，确认三条研究方向和 1.21.1 目标；检查仓库文件与工作区状态。
2. 访问官方文档，读取上游元数据；将对应 ref 解析成完整提交 SHA。
3. 通过 `https://api.github.com/repos/{owner}/{repo}/git/trees/{sha}?recursive=1` 获取完整 tree，确认 `truncated == false` 后统计 Patch 路径。
4. 从 `https://raw.githubusercontent.com/{owner}/{repo}/{sha}/{path}` 获取七个 Patch 与支撑 API / 配置样本，静态检查相关片段。文档保留调查结论与永久链接；未逐片段审计全部文件内容。
5. 将源码事实、推论 / 设计建议和待验证实验分开记录。没有下载安装游戏、执行上游安装器或运行上游测试。

Patch 统计的等价 Python 逻辑如下；`forge_paths`、`neo_paths` 是对应完整 tree 中 `type == "blob"` 的文件路径：

```python
forge = {
    p.removeprefix("patches/minecraft/")
    for p in forge_paths
    if p.startswith("patches/minecraft/") and p.endswith(".java.patch")
}
neo = {
    p.removeprefix("patches/")
    for p in neo_paths
    if p.startswith("patches/net/minecraft/") and p.endswith(".java.patch")
}
assert len(forge) == 642
assert len(neo) == 733
assert len(forge & neo) == 570
assert len(forge - neo) == 72
assert len(neo - forge) == 163
```

[upstream-snapshot.json](upstream-snapshot.json) 保存 ref、提交、完整 tree URL、统计结果和取样源码的 SHA-256。样本哈希针对下载内容解码后统一换行为 LF、编码为 UTF-8 的字节，供同一提交的内容复核；不代表发布 JAR 哈希，也不表示已审计样本内全部语义。

## 4. 版本与证据陷阱

- Fabric 当前通用文档可能覆盖更新的 Minecraft；本报告的具体类名、回调和注入位置优先引用 1.21.1 分支源码。
- Forge `1.21.x` SimpleImpl 页仍出现 `NetworkRegistry.newSimpleChannel` 等示例；本次目标源码采用 `ChannelBuilder`，不要把网页代码直接当作可编译的 1.21.1 实现。
- NeoForge 的 1.21.1 文档还会区分不同 `21.1.x` 修订；同一游戏版本不保证所有修订的注解或 API 一致。
- Connector 的实际目标分支是 `1.21.x`，其固定版本目录写明游戏为 `1.21.1`。主分支 / 默认页的最新状态不能替代此版本快照。
- Forbric 候选项目目前面向 26.2。它的架构描述和测试方法可以参考，版本相关实现与成功率不能外推到本项目。
- Patch 文件数、共同目标类数量、接口注入目标键数都是静态统计，不是已经完成的 Hook 数量、冲突数量或兼容率。
