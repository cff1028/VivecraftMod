# Vivecraft OpenXR — Minecraft 26.2

将 QuestCraft 的 OpenXR 适配移植到 Vivecraft 26.2-1.3.15，面向 Quest 一体机和 Fabric。

- 支持 OpenGL ES 和 Vulkan 双眼输出，各眼使用独立的 FOV 与反向深度投影。
- Touch 手柄输入、震动及手腕／手掌快捷栏选择。
- Vulkan 平面画面输出到 FCL 面板，进入 VR 后使用同一游戏设备提交双眼画面。
- 主菜单默认显示 VR 开关。

## 使用

需要 Minecraft 26.2、Fabric Loader 0.19.3 或更高版本、Java 25。Vulkan 适配对应 FCL Quest VR `0.1.1-local.9`。将 Fabric JAR 放入实例的 `mods` 目录，替换其他 Vivecraft JAR。

在 FCL 实例中开启 Quest VR，选择 Vulkan。游戏先在平面加载资源。可从主菜单进入 VR，也可使用记住的 VR 启动设置。在同一游戏进程中可切回平面并再次进入 VR。若提示需要启用额外的 Vulkan 扩展，请选择“保存并退出”，再从 FCL 启动。

## 构建

使用 JDK 25，默认构建 Fabric：

```sh
./gradlew :fabric:jar
```

Windows 使用 `gradlew.bat :fabric:jar`。产物位于 `build/libs/`，Android OpenXR 绑定位于 `quest-libs/`。

## 来源

基于 [Vivecraft 26.2-1.3.15](https://github.com/Vivecraft/VivecraftMod/tree/26.2-1.3.15)，OpenXR 移植自 [QuestCraft VivecraftMod](https://github.com/QuestCraftPlusPlus/VivecraftMod)。许可证见 [LICENSE.md](LICENSE.md)。
