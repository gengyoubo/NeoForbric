package org.neoforbric.installer;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        if (args.length == 0) {
            if (GraphicsEnvironment.isHeadless()) { System.err.println("没有图形环境。使用 --help 查看命令行安装方式。"); System.exit(2); }
            SwingUtilities.invokeLater(InstallerWindow::show); return;
        }
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("NeoForbric 安装器 · Minecraft 1.21.1 · Fabric Loader 0.19.5 · Windows x64 / Java 21\n"
                    + "双击 JAR 打开图形界面，或使用：\n"
                    + "java -jar neoforbric-installer.jar --install <.minecraft目录> [--version-id <名称>] [--no-profile]\n"
                    + "已有 NF 游戏缓存可加 --runtime-source <缓存目录>，复制后会重新校验。");
            return;
        }
        try {
            Map<String, String> values = new HashMap<>(); boolean profile = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--no-profile")) { profile = false; continue; }
                String key = args[i];
                if (!Set.of("--install", "--version-id", "--runtime-source").contains(key) || i + 1 == args.length || args[i + 1].startsWith("--") || values.putIfAbsent(key, args[++i]) != null)
                    throw new IllegalArgumentException("无效参数：" + key);
            }
            if (!values.containsKey("--install")) throw new IllegalArgumentException("需要 --install <.minecraft目录>");
            Path installed = Installer.install(new Installer.Options(Path.of(values.get("--install")),
                    values.getOrDefault("--version-id", Installer.catalog().get("versionId").getAsString()), profile,
                    values.containsKey("--runtime-source") ? Path.of(values.get("--runtime-source")) : null), System.out::println);
            System.out.println("INSTALL_OK " + installed);
        } catch (Exception error) { System.err.println("安装失败：" + error.getMessage()); System.exit(1); }
    }
}
