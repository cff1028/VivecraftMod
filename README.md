# Vivecraft OpenXR

将 QuestCraft 的 Quest 一体机 OpenXR 适配移植到 Vivecraft 1.3.15，面向 Minecraft 1.21.4 Fabric。

- Quest OpenXR 会话、双眼渲染与视场角适配。
- Touch 手柄输入、震动及手腕／手掌快捷栏选择。

从 [Releases](https://github.com/cff1028/VivecraftMod/releases) 下载 Fabric JAR，放入游戏的 `mods` 目录，并移除其他 Vivecraft JAR。启动器需提供 Android OpenXR 与 `vloader`。

## 构建

使用 JDK 21，默认构建 Fabric：

```sh
./gradlew :fabric:remapJar
```

Windows 使用 `gradlew.bat :fabric:remapJar`。产物位于 `build/libs/`；`quest-libs/` 中包含所需的 QuestCraft OpenXR 依赖。

## 来源

基于 [Vivecraft 1.21.4-1.3.15](https://github.com/Vivecraft/VivecraftMod/tree/1.21.4-1.3.15)，OpenXR 适配来自 [QuestCraft](https://github.com/QuestCraftPlusPlus/VivecraftMod/tree/60ffc92889fed3d1a92127b1b66516dd2354bbeb)。许可证见 [LICENSE.md](LICENSE.md)。
