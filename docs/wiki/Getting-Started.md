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

需要包含单机支持的 Framework 与包含 `InventoryAccess`、`ContainerDetails` 和 `ItemMetadata` 的 BS3D 源码。两者以组合构建接入，随本仓构建先行编译：

```sh
./gradlew test build
python3 scripts/wiki.py check
```

缺省使用同级 `Kizuna-Inventory-UI-Framework`、`backpack-and-storage-3D` 源码目录，其他布局用 `-Pkizuna.dir.inventory-ui=…`、`-Pkizuna.dir.bs3d=…` 指定。构建还需要相邻的 `Kizuna-Meridian-Mod-Framework`：`settings.gradle` 应用其 `scripts/workspace.gradle`，Gradle 8.14.6、Fabric Loom 1.13.6 与第三方版本都取自其版本目录 `scripts/libs.versions.toml`。Gradle 须在 Java 21 上运行（Loom 1.13 的要求），产物仍以 Java 17 为目标。Windows 使用 `gradlew.bat`。尚未发布 Maven 坐标；两个依赖仓库均需上述接口，文件名同为 SNAPSHOT 不代表内容兼容。

| 安装位置 | JAR |
|---|---|
| 客户端和单机 | Framework 客户端 + BS3D 客户端 + `client/build/libs/bs3d-weights-addon-client-0.1.0-SNAPSHOT.jar` |
| 专用服务器 | Framework 服务端 + BS3D 服务端 + `server/build/libs/bs3d-weights-addon-server-0.1.0-SNAPSHOT.jar` |

2026-10-30 起，同级的 Framework（提交 997b993）与 BS3D（未提交源码）已把服务端路由和库存实现移出客户端产物，客户端模组依赖各自的服务端模组。用这两份源码构建时，单机除上表客户端 JAR 外还要安装 Framework 服务端和 BS3D 服务端 JAR；本仓库客户端构建已同时引用两者。此组合目前只验证了编译和单元测试，未重跑 smoke。

每端都安装 Fabric API。两份 Addon JAR 使用同一模组 ID，在同一实例只安装对应的一份；单机客户端产物已经包含逻辑服务端，不再加装专用服务端 JAR。服务端产物不含客户端渲染类或 owo。

进入世界，按 **B** 打开背包。左上角显示「实重 / 负重」，即随身实重与按[系数](Weight-Rules.md)加权的有效负重；容器打开后标题旁显示该容器总重。数据初次到达前显示等待；服务端未安装重量附属时显示未启用，BS3D 基础库存仍可用。单机世界使用本地内置服务器的数据包。

生产 JAR 不创建测试物品、不自动移动。`scripts/smoke.py` 仅用于隔离自动验收，见[验证记录](Validation.md)。重量规则见[配置说明](Weight-Rules.md)。
