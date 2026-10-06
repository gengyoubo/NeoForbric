# 免责声明！！！
本项目仅为学习目的制作的forbric的改版NeoForbric,这个模组加载器所造成的崩溃和稳定性不能保证。
# 目标版本
1.21.1
# 实现思路

NeoForbric 的目标不是简单地在 Forge/Fabric/NeoForge 之间建立兼容层，
而是尝试重新统一三种加载器的底层运行模型。

## 1. 生命周期统一

将 Fabric、Forge 和 NeoForge 不同的 Mod 生命周期转换为 NeoForbric
内部统一的生命周期，例如：

- Mod 初始化
- Client 初始化
- Server 初始化
- Registry
- Resource Reload
- Networking
- World Events

模组主要面向 NeoForbric 的统一生命周期，而不是分别处理三个 Loader。

## 2. Minecraft 源码统一

尽可能让三端使用同一套 Minecraft 基础代码/映射，
避免维护三套具有大量差异的 Minecraft 源码。

## 3. Forge / NeoForge Patch

Forge 和 NeoForge 对 Minecraft 本体存在大量 Patch。

NeoForbric 不计划简单复制这些 Patch，而是分析 Patch 的实际功能，
将必要的行为转换为统一 Hook / API，再由 NeoForbric 实现。

目标结构：

Fabric ─────┐
Forge ──────┼─> NeoForbric Lifecycle / Hooks ─> Common Mod
NeoForge ───┘