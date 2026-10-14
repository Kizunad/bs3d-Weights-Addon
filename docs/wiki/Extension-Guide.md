# 扩展边界与 API

本附属使用 BS3D 的只读库存、界面信息和物品元数据入口，BS3D 自身不依赖重量类。以下 API 属于未发布的 `0.1.0-SNAPSHOT`，尚未冻结。

`InventoryAccess.snapshot(ServerPlayerEntity)` 只允许在逻辑服务器主线程调用。返回穿戴存档、原版槽位栈副本和当前已获准打开的地面根容器副本；不返回可提交的事务对象。所有权与地面距离检查仍由 BS3D 实施。光标栈从当前服务端玩家的 ScreenHandler 读取。

`ContainerDetails.register(Identifier, Provider)` 在客户端初始化时登记短文本。`Provider.summary()` 提供背包页总览文本；`Provider.container(String containerId)` 提供按容器 UUID 查找的短文本。未知容器返回 `Text.empty()`。BS3D 负责排版、裁切和主题，Addon 不接管窗口管理或原版 HUD。

重量逻辑位于 `server/src/main/java`，客户端构建复用这份源码以支持内置服务器；专用服务器产物没有客户端类。`common/src/main/resources` 仅保存两端产物都需携带的服务端默认数据包。`shared/src/main/proto` 和 `WeightMessages` 定义消息，不保存客户端猜测值。

2026-10-14 源码新增 `ItemMetadata.of(ItemStack).optional(Identifier)` 读取附属字段副本。本附属仅在逻辑服务器读取 `bs3d_weights:weight` 中的整数 `grams`，复用数据包同一数值校验。合法注册字段优先；缺失或无效时 WARN 一次，再回退到[数据包规则和默认自重](Weight-Rules.md)。BS3D 的 `ContainerItems.register` 必须显式传入 `optional`，原版及第三方物品可通过 `ItemMetadata.register` 补登记；类型注册字段不来自物品 NBT 或客户端请求。两端需要[安装指南](Getting-Started.md)中包含此接口的兼容 BS3D 产物，API 尚未冻结。

数据包仍可为未登记重量字段的物品配置自重，无须另写 Java 或修改模组。当前没有额外库存类型的递归扩展 API；不要通过读取客户端 NBT 计算权威重量。

单机所需的基础改动位于 Framework 和 BS3D：Framework 客户端包含公共服务端路由；BS3D 客户端包含服务器库存实现和持久化 mixin。房主读取已有业务 playerdata，避免旧 level.dat 覆盖已提交事务。未安装业务字段的旧世界仍走原版读档路径。

运行证据和限制见[验证记录](Validation.md)。
