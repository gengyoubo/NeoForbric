# Minecraft 1.21.1 持续服务端

`runServer` 已进入真实世界加载、Tick、保存与正常停止路径，继续使用 NeoForbric bootstrap 和有限 Fabric Java 入口 profile。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot' # 换成自己的 JDK 21
./gradlew.bat runServer -PacceptEula=true
```

`-PacceptEula=true` 表示接受 Minecraft EULA，仅在已经同意时使用。工作目录为 `run/server`，包括 EULA、配置、世界和审计。已经有 `eula=true` 时后续可直接运行 `runServer`。默认绑定 `127.0.0.1:25565`，开启 online-mode；`-PserverPort=端口` 可修改运行端口。控制台输入 `stop` 正常保存并退出。

```powershell
./gradlew.bat runServer -PserverProbeTicks=5
./gradlew.bat :loader:minecraftServerTest -PacceptEula=true
```

探针验证世界 / server thread、Tick、注册的 ItemStack 保存与下一次世界加载读取、控制台停止、占用端口失败，以及 Tick 回调异常仍终止并审计。内核等服务端线程退出后再关闭游戏类加载器和资源；原版停止与保存路径仍执行。

这不代表 Forge / NeoForge 原生入口、动态注册表兼容或原生态网络握手已完成。静态物品注册与映射见 [最初的 bootstrap 探针](minecraft-bootstrap.md)，客户端见 [runClient 与 Mods 列表](minecraft-client.md)。
