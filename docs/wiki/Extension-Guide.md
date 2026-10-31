# 扩展边界与 API

本附属使用 BS3D 的只读库存、界面信息和物品元数据入口，BS3D 自身不依赖重量类。以下 API 属于未发布的 `0.1.0-SNAPSHOT`，尚未冻结。

`InventoryAccess.snapshot(ServerPlayer)` 只允许在逻辑服务器主线程调用。返回穿戴存档、原版槽位栈副本（26.2 为 43 格，含身体盔甲与鞍）和当前已获准打开的地面根容器副本；不返回可提交的事务对象。所有权与地面距离检查仍由 BS3D 实施。光标栈从当前服务端玩家的 AbstractContainerMenu 读取。

`Burden.effectiveGrams(ServerPlayer)` 与 `Burden.totalGrams(ServerPlayer)` 位于 `dev.kizuna.bs3d.weights.server`，供其他服务端附属读取有效负重和随身实重。2026-10-30 源码已实现，计算由单元测试覆盖，API 尚未冻结。两者单位为克，返回 `OptionalLong`；库存损坏或有效负重溢出时为空，由调用方决定回退。只允许在逻辑服务器主线程调用，其他线程抛出 `IllegalStateException`。每名玩家每 5 个服务端 tick 最多重算一次，与订阅推送节奏相同，期间复用上次结果。系数见[有效负重系数](Weight-Rules.md)。本附属不据此限制玩家；调用方在 `fabric.mod.json` 依赖 `bs3d_weights_addon`，并按运行端引用对应的 Weights JAR 编译。

本附属只有一处可选模组检测：`BurdenSlots.detect()` 按 `bs3d_pocket`（用户自定义的可选 Pocket JAR）是否加载，选择有效负重的主库存范围（未安装为 9–35 号槽，安装后为 9–17 号槽）。检测只看模组 ID，两种范围都是本仓常量，不反射读取 Pocket 的类。BS3D 与 Kizuna Inventory UI Framework 等其余依赖都是硬依赖，不写缺依赖时的降级分支；新增可选检测前须先改本节。

`ContainerDetails.register(Identifier, Provider)` 在客户端初始化时登记短文本。`Provider.summary()` 提供背包页总览文本；`Provider.container(String containerId)` 提供按容器 UUID 查找的短文本。未知容器返回 `Component.empty()`。BS3D 负责排版、裁切和主题，Addon 不接管窗口管理或原版 HUD。

重量逻辑位于 `server/src/main/java`，客户端构建复用这份源码以支持内置服务器；专用服务器产物没有客户端类。`common/src/main/resources` 保存两端产物都需携带的服务端默认数据包和默认有效负重系数表 `bs3d_weights/burden.toml`；两份产物各自内嵌 tomlj 与 ANTLR 运行库来解析该表。`shared/src/main/proto` 和 `WeightMessages` 定义消息，不保存客户端猜测值。

2026-10-14 源码新增 `ItemMetadata.of(ItemStack).optional(Identifier)` 读取附属字段副本。本附属仅在逻辑服务器读取 `bs3d_weights:weight` 中的整数 `grams`，复用数据包同一数值校验。合法注册字段优先；缺失或无效时 WARN 一次，再回退到[数据包规则和默认自重](Weight-Rules.md)。BS3D 的 `ContainerItems.register` 必须显式传入 `optional`，原版及第三方物品可通过 `ItemMetadata.register` 补登记；类型注册字段不来自物品自定义数据或客户端请求。两端需要[安装指南](Getting-Started.md)中包含此接口的兼容 BS3D 产物，API 尚未冻结。

数据包仍可为未登记重量字段的物品配置自重，无须另写 Java 或修改模组。当前没有额外库存类型的递归扩展 API；不要通过读取客户端物品数据计算权威重量。

单机所需的基础改动位于 Framework 和 BS3D。早期兼容提交中，Framework 客户端包含公共服务端路由，BS3D 客户端包含服务器库存实现和持久化 mixin。Framework 当前提交 997b993 与 BS3D 当前未提交源码已把这些内容移到两端共用的服务端产物，客户端产物依赖它；本仓库客户端构建因此同时从两者的服务端 JAR 编译。26.2 起房主与其他玩家一样只从 `players/data` 读档，`level.dat` 不再保存房主数据，已提交事务不会被旧 level.dat 覆盖。

运行证据和限制见[验证记录](Validation.md)。
