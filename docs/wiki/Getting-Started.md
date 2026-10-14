# 安装与构建

适用：未发布的 `0.1.0-SNAPSHOT`。需要 Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1；客户端还需要 owo 0.11.2+1.20。

## 配套依赖状态

2026-10-14 已核验两个依赖仓库的公开 `main`：

| 仓库 | 远端提交 | 当前限制 |
|---|---|---|
| [Inventory UI Framework](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework) | `1a184a6105a40a44a099a47a87e3d8a46a8d1183` | 尚未包含本附属所需的服务端路由与单机注册 |
| [Backpack and Storage 3D](https://github.com/Kizunad/backpack-and-storage-3D) | `fd84319e7154a9d70d042d5292037b806f48e853` | 仅有 README 和 LICENSE，Java 实现尚未推送 |

本附属的本地验证使用了两个基础仓库尚未提交的实现。所以下列命令要求先取得配套源码，不能声称从当前公开 `main` 克隆后即可运行；也没有可供固定的兼容依赖提交或 Maven 版本。基础源码同步后应更新此表并记录可复现的提交号。

## 从配套源码构建

先构建包含单机支持的 Framework，再构建包含 `InventoryAccess` 和 `ContainerDetails` 的 BS3D，最后构建本仓库：

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
