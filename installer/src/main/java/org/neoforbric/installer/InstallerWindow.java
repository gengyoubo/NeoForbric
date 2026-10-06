package org.neoforbric.installer;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class InstallerWindow {
    private InstallerWindow() {}
    static JPanel content() {
        JPanel panel = new JPanel(new BorderLayout(0, 18)); panel.setBorder(new EmptyBorder(24, 28, 24, 28));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 7));
        JLabel title = new JLabel("NeoForbric 安装器"); title.setFont(title.getFont().deriveFont(Font.BOLD, 26f)); heading.add(title);
        heading.add(new JLabel("Minecraft 1.21.1   ·   Fabric Loader 0.19.5   ·   Java 21"));
        heading.add(new JLabel("安装后，在启动器中选择 NeoForbric 版本即可运行。")); panel.add(heading, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 16)), fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints(); constraints.insets = new Insets(5, 0, 5, 10); constraints.anchor = GridBagConstraints.WEST;
        String appData = System.getenv("APPDATA");
        JTextField directory = new JTextField((appData == null ? Path.of(System.getProperty("user.home"), ".minecraft") : Path.of(appData, ".minecraft")).toString(), 30);
        directory.setName("directory");
        JTextField version = new JTextField("NeoForbric-1.21.1", 30); version.setName("version");
        JButton browse = new JButton("浏览…"); browse.setName("browse");
        JCheckBox profile = new JCheckBox("为官方启动器创建启动配置", true); profile.setName("profile");
        constraints.gridx = 0; constraints.gridy = 0; fields.add(new JLabel("Minecraft 目录"), constraints);
        constraints.gridx = 1; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; fields.add(directory, constraints);
        constraints.gridx = 2; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE; fields.add(browse, constraints);
        constraints.gridx = 0; constraints.gridy = 1; fields.add(new JLabel("版本名称"), constraints);
        constraints.gridx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; fields.add(version, constraints);
        constraints.gridy = 2; fields.add(profile, constraints);
        center.add(fields, BorderLayout.NORTH);
        JTextArea logs = new JTextArea("首次安装将下载并校验官方游戏文件、资源和映射。\n当前客户端支持 Windows x64。", 9, 48);
        logs.setName("logs"); logs.setEditable(false); logs.setLineWrap(true); logs.setWrapStyleWord(true); logs.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        center.add(new JScrollPane(logs), BorderLayout.CENTER); panel.add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        JLabel status = new JLabel("准备就绪"); status.setName("status");
        JButton install = new JButton("安装 NeoForbric"); install.setName("install"); install.setPreferredSize(new Dimension(165, 38));
        bottom.add(status, BorderLayout.CENTER); bottom.add(install, BorderLayout.EAST); panel.add(bottom, BorderLayout.SOUTH);
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directory.getText()); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择 .minecraft 目录");
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().toString());
        });
        install.addActionListener(event -> {
            Installer.Options options;
            try { options = new Installer.Options(Path.of(directory.getText().trim()), version.getText().trim(), profile.isSelected(), null); Installer.validateId(options.versionId()); }
            catch (Exception invalid) { JOptionPane.showMessageDialog(panel, invalid.getMessage(), "检查安装路径", JOptionPane.ERROR_MESSAGE); return; }
            for (JComponent component : List.of(directory, version, browse, profile, install)) component.setEnabled(false);
            panel.putClientProperty("installing", true); logs.setText(""); status.setText("正在安装，请稍候…");
            new SwingWorker<Path, String>() {
                @Override protected Path doInBackground() throws Exception { return Installer.install(options, this::publish); }
                @Override protected void process(List<String> lines) {
                    for (String line : lines) logs.append(line + "\n"); logs.setCaretPosition(logs.getDocument().getLength());
                }
                @Override protected void done() {
                    panel.putClientProperty("installing", false);
                    for (JComponent component : List.of(directory, version, browse, profile, install)) component.setEnabled(true);
                    try {
                        Path result = get(); status.setText("安装完成");
                        JOptionPane.showMessageDialog(panel, "安装成功！\n在启动器中选择 " + options.versionId() + "。\n\n模组放入该版本游戏目录的 mods 文件夹。\n" + result.getParent(), "NeoForbric", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception failed) {
                        Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                        status.setText("安装失败，可修复后重试"); logs.append("\n" + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(panel, cause.getMessage(), "安装失败", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });
        return panel;
    }
    static void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        UIManager.put("Label.font", new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        UIManager.put("Button.font", new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        UIManager.put("CheckBox.font", new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        UIManager.put("TextField.font", new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        JFrame frame = new JFrame("NeoForbric Installer"); JPanel panel = content(); frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) {
            if (!Boolean.TRUE.equals(panel.getClientProperty("installing"))) frame.dispose();
        }});
        frame.pack(); frame.setMinimumSize(new Dimension(680, 460)); frame.setLocationRelativeTo(null); frame.setVisible(true);
    }
}
