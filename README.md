# Vivecraft OpenXR — Minecraft 26.2

将 QuestCraft 的 OpenXR 适配移植到 Vivecraft 26.2-1.3.15，面向 Quest 一体机和 Fabric。

- 双眼数组纹理直接渲染、独立眼部 FOV 与反向深度投影。
- Touch 手柄输入、震动及手腕／手掌快捷栏选择。
- 对接 FCL Quest VR 0.1.1 和 LWJGL 3.4.1。
- 主菜单默认显示 VR 开关。

## 使用

需要 Minecraft 26.2、Fabric Loader 0.19.3 或更高版本、Java 25 和 FCL Quest VR 0.1.1。FCL 使用 LWJGL 3.4.1 与 Quest MobileGlues，并提供 OpenXR 和 `vloader`。将 Fabric JAR 放入实例的 `mods` 目录，替换其他 Vivecraft JAR。

## 构建

使用 JDK 25，默认构建 Fabric：

```sh
./gradlew :fabric:jar
```

Windows 使用 `gradlew.bat :fabric:jar`。产物位于 `build/libs/`，Android OpenXR 绑定位于 `quest-libs/`。

## 来源

基于 [Vivecraft 26.2-1.3.15](https://github.com/Vivecraft/VivecraftMod/tree/26.2-1.3.15)，OpenXR 移植自 [QuestCraft VivecraftMod](https://github.com/QuestCraftPlusPlus/VivecraftMod)。许可证见 [LICENSE.md](LICENSE.md)。
