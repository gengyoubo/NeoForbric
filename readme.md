# 免责声明！！！
本项目仅为学习目的制作的forbric的改版NeoForbric,这个模组加载器所造成的崩溃和稳定性不能保证。
# 目标版本
1.21.1
# 如何使用
下载最新构建的neoforbric-installer.jar，然后在java21版本中运行
```
java -jar neoforbric-installer.jar
```
然后指定目录就可以
（PCL可以正常识别这个模组加载器)
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
```
Fabric ─────┐
Forge ──────┼─> NeoForbric Lifecycle / Hooks ─> Common Mod
NeoForge ───┘
```

# 当前实现与运行

客户端安装器：使用 Java 21 运行项目根目录的 `neoforbric-installer.jar`，选择 `.minecraft` 目录后安装。构建命令为 `./gradlew.bat assembleInstaller`，详情见 [安装器说明](docs/installer.md)。

已实现 Java 21 启动内核原型：自有 JVM 入口、JAR 元数据发现、原型依赖图、单一游戏类加载器、转换定义屏障和 JSON 审计。三个独立样例 JAR 在同一游戏夹具对象上协作，验证初始化顺序与类 / 对象身份。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21 路径
./gradlew.bat runFixture
./gradlew.bat runMinecraftProbe
./gradlew.bat runServer -PacceptEula=true # 仅在已同意 Minecraft EULA 时使用
./gradlew.bat runClient
./gradlew.bat :loader:test
```

已接入真实 Minecraft 1.21.1 JAR、映射转换和 NeoForbric Fabric runtime（Mixin、AW、nested JAR、依赖图、Fabric Loader API / Fabric API 模块），实测 Item / ItemStack / Holder / ResourceKey 身份与注册表冻结。`runServer` 启动世界、Tick 并保存退出；Windows x64 的 `runClient` 启动真实窗口和主菜单，新增 Mods 列表与图形图标，区分来源生态、运行适配器和 Loader 最终状态。含原生 NeoForge 模组时自动选择实验性的 NeoForge 21.1.248 profile，已实测 EcologicalGarden 1.3.2 的构造、注册与世界运行；其余 Fabric 客户端默认使用 Fabric runtime（`-PfabricPlainProfile` 可回退 plain 准入）。Forge 原生入口仍待接入，完整 Fabric / NeoForge 混装尚未实现。运行与边界见 [NeoForge 首个实测](docs/neoforge-runtime.md)、[客户端与 Mods](docs/minecraft-client.md)、[持续服务端](docs/minecraft-server.md)、[初始 bootstrap 探针](docs/minecraft-bootstrap.md)，原有夹具见 [原型说明](docs/prototype.md)，前期调查见 [docs 索引](docs/README.md)。
