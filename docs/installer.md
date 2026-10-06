# NeoForbric 安装器

成品为项目根目录的 `neoforbric-installer.jar`。使用 Windows x64 和 Java 21 双击运行，选择启动器的 `.minecraft` 目录并点击“安装 NeoForbric”。默认生成 `NeoForbric-1.21.1` 版本；安装完成后在启动器选择它。模组放入启动器为该版本使用的游戏目录下的 `mods`。

安装器内置 NF 内核、客户端 UI 和锁定的工具依赖，包括 Fabric Loader 0.19.5。Minecraft、Mojang 映射、natives 和资源由 `ClientPreparation` 从锁定的官方地址下载并校验；可复用所选 Minecraft 目录的库与资源缓存。

```powershell
./gradlew.bat assembleInstaller
java -jar neoforbric-installer.jar
java -jar neoforbric-installer.jar --install 'C:/Games/MC/.minecraft'
```

命令行支持 `--version-id <名称>` 和 `--no-profile`。已有 NF 准备缓存时可使用 `--runtime-source <缓存目录>`，复制后仍重新验证全部输入。安装中保留状态记录，失败后可以重试；不同内容的现有依赖库会报告校验冲突。

## 安装结构

- `versions/<版本>/<版本>.json`：启动参数、Java 21 要求、资源索引和工具依赖。
- `versions/<版本>/<版本>.jar`：NF 内核，主类为 `org.neoforbric.bootstrap.InstalledClient`。
- `versions/<版本>/neoforbric/client-ui.jar`：在游戏类加载器中使用的 UI。
- `versions/<版本>/neoforbric/runtime`：校验后的游戏、映射、库、natives 和资源。
- `libraries`：按 Maven 路径保存的 NF 工具依赖。
- `assets`：内容寻址的资源对象和独立命名的 NF 资源索引。

`InstalledClient` 接收启动器传入的账户与游戏目录参数，然后交给 NF bootstrap。Minecraft 和游戏库在 G 中加载，因此版本 JSON 仅列工具依赖，游戏 JAR 不进入启动器的父类加载器。版本与 profiles 文件布局参考 [Fabric 安装器](https://github.com/FabricMC/fabric-installer/blob/master/src/main/java/net/fabricmc/installer/client/ClientInstaller.java) 和 [启动配置处理](https://github.com/FabricMC/fabric-installer/blob/master/src/main/java/net/fabricmc/installer/client/ProfileInstaller.java)。

选择创建官方启动配置时，安装器保留已有 profiles 和其他 JSON 字段，并在写入前保存带 UUID 的备份。新 profile 使用独立游戏目录、Java 21 和 3 GB 最大堆；已有 NF profile 的游戏目录与 JVM 参数保留。Microsoft Store 版的 profiles 文件也支持。客户端的平台与模组适配范围见 [客户端说明](minecraft-client.md)。

生成资源目录通过 `builtBy(preparePayload)` 注册，因此 `processResources` 和 `sourcesJar` 都会等待资源准备。普通 `:installer:build` 和 `assembleInstaller` 均可构建。

## 实测

2026-10-06 验证：52 项 loader 单元测试和 4 项 installer 检查通过；`installer:build` 与 `sourcesJar` 成功。界面预览为 `installer/build/installer-preview.png`。

成品 JAR 在 `build/installer-smoke/Minecraft With Spaces` 中完成首次安装和重复安装。准备器校验 46 个游戏库、Windows natives 和 3,888 个资源对象。随后按生成的版本 JSON 构造 Java 命令，使用已安装的内核与依赖启动实际客户端，Fabric 客户端探针输出 `CLIENT_PROBE_OK`，主菜单渲染后正常退出，审计为 `SUCCESS`。启动记录位于 `build/installer-launch.log`，安装记录位于 `build/installer-install.log` 和 `build/installer-reinstall.log`。该验证覆盖版本 JSON 的启动命令，尚未操作各第三方启动器界面。
