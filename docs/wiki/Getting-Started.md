# 安装与构建

适用：未发布的 `0.1.0-SNAPSHOT`。需要 Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1；客户端还需要 owo 0.11.2+1.20。

## 配套依赖状态

2026-10-14 配套源码已推送公开 `main`，本附属已在以下提交上完成 Java 17 构建和单机／独服联调：

| 仓库 | 兼容源码提交 | 所需能力 |
|---|---|---|
| [Inventory UI Framework](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework) | [b2f6e30](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/commit/b2f6e3021c40f052702d48301408b333b3ed378f) | 统一逻辑服务端路由、单机注册与公共 Protobuf |
| [Backpack and Storage 3D](https://github.com/Kizunad/backpack-and-storage-3D) | [6a09d7a](https://github.com/Kizunad/backpack-and-storage-3D/commit/6a09d7ade862f5741a581f890dbc7ae4c4dfde05) | 权威库存、`InventoryAccess`、`ContainerDetails`、物品描述与 `ItemMetadata` 附属字段 |

三个源码仓库放在同级目录。复现本次验证时，先克隆两个依赖并固定提交，再执行下方构建步骤：

```sh
git clone "https://github.com/Kizunad/Kizuna-Inventory-UI-Framework.git" "Kizuna-Inventory-UI-Framework"
git -C "Kizuna-Inventory-UI-Framework" checkout b2f6e3021c40f052702d48301408b333b3ed378f
git clone "https://github.com/Kizunad/backpack-and-storage-3D.git" "backpack-and-storage-3D"
git -C "backpack-and-storage-3D" checkout 6a09d7ade862f5741a581f890dbc7ae4c4dfde05
```

这些是已验证的源码提交，不是稳定 API 或正式发布版本；没有 Maven 坐标。

本次物品 `optional` 重量支持要求 BS3D 包含 `ItemMetadata` 和八参数 `ContainerItems.register`。上表中的 BS3D 提交已包含这些入口；两端都须使用由该提交构建的产物，旧的同名 SNAPSHOT JAR 不满足依赖要求。Framework 本次无改动，继续使用表中的兼容提交。

## 从配套源码构建

先构建包含单机支持的 Framework，再构建包含 `InventoryAccess`、`ContainerDetails` 和 `ItemMetadata` 的 BS3D，最后构建本仓库：

```sh
# 在 Framework 仓库运行
./gradlew test build

# 在 BS3D 仓库运行
./gradlew -PframeworkDir="/path/to/Kizuna-Inventory-UI-Framework" test build

# 在本仓库运行
./gradlew -PframeworkDir="/path/to/Kizuna-Inventory-UI-Framework" \
  -Pbs3dDir="/path/to/backpack-and-storage-3D" test build
python3 scripts/wiki.py check
```

缺省从同级 `Kizuna-Inventory-UI-Framework`、`backpack-and-storage-3D` 目录取构建产物。Windows 使用 `gradlew.bat`。尚未发布 Maven 坐标；两个依赖仓库均需上述接口，文件名同为 SNAPSHOT 不代表内容兼容。

| 安装位置 | JAR |
|---|---|
| 客户端和单机 | Framework 客户端 + BS3D 客户端 + `client/build/libs/bs3d-weights-addon-client-0.1.0-SNAPSHOT.jar` |
| 专用服务器 | Framework 服务端 + BS3D 服务端 + `server/build/libs/bs3d-weights-addon-server-0.1.0-SNAPSHOT.jar` |

每端都安装 Fabric API。两份 Addon JAR 使用同一模组 ID，在同一实例只安装对应的一份；单机客户端产物已经包含逻辑服务端，不再加装专用服务端 JAR。服务端产物不含客户端渲染类或 owo。

进入世界，按 **B** 打开背包。左上角显示随身重量，容器打开后标题旁显示该容器总重。数据初次到达前显示等待；服务端未安装重量附属时显示未启用，BS3D 基础库存仍可用。单机世界使用本地内置服务器的数据包。

生产 JAR 不创建测试物品、不自动移动。`scripts/smoke.py` 仅用于隔离自动验收，见[验证记录](Validation.md)。重量规则见[配置说明](Weight-Rules.md)。
