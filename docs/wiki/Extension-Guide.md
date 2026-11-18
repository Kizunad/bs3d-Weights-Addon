# 扩展边界与 API

本附属使用 BS3D 的只读库存、界面信息和物品元数据入口，BS3D 自身不依赖重量类。以下 API 属于未发布的 `0.1.0-SNAPSHOT`，尚未冻结。

`InventoryAccess.snapshot(ServerPlayer)` 只允许在逻辑服务器主线程调用。返回穿戴存档、原版槽位栈副本（26.2 为 43 格，含身体盔甲与鞍）和当前已获准打开的地面根容器副本；不返回可提交的事务对象。所有权与地面距离检查仍由 BS3D 实施。光标栈从当前服务端玩家的 AbstractContainerMenu 读取。

`Burden.effectiveGrams(ServerPlayer)` 与 `Burden.totalGrams(ServerPlayer)` 位于 `dev.kizuna.bs3d.weights.server`，供其他服务端附属读取有效负重和随身实重。2026-10-30 源码已实现，计算由单元测试覆盖，API 尚未冻结。两者单位为克，返回 `OptionalLong`；库存损坏或有效负重溢出时为空，由调用方决定回退。只允许在逻辑服务器主线程调用，其他线程抛出 `IllegalStateException`。每名玩家每 5 个服务端 tick 最多读一次库存，和订阅推送共用同一份读数，期间复用上次结果；重读时库存、光标和重量规则都没变就不重算（2026-11-12 起，见[验证记录](Validation.md)）。系数见[有效负重系数](Weight-Rules.md)。本附属不据此限制玩家；调用方在 `fabric.mod.json` 依赖 `bs3d_weights_addon`，并按运行端引用对应的 Weights JAR 编译。

本附属只有一处可选模组检测：`BurdenSlots.detect()` 按 `bs3d_pocket`（用户自定义的可选 Pocket JAR）是否加载，选择有效负重的主库存范围（未安装为 9–35 号槽，安装后为 9–17 号槽）。检测只看模组 ID，两种范围都是本仓常量，不反射读取 Pocket 的类。BS3D 与 Kizuna Inventory UI Framework 等其余依赖都是硬依赖，不写缺依赖时的降级分支；新增可选检测前须先改本节。

`ContainerDetails.register(Identifier, Provider)` 在客户端初始化时登记短文本。`Provider.summary()` 提供背包页总览文本；`Provider.container(String containerId)` 提供按容器 UUID 查找的短文本。未知容器返回 `Component.empty()`。BS3D 负责排版、裁切和主题，Addon 不接管窗口管理或原版 HUD。

重量逻辑位于 `server/src/main/java`，客户端构建复用这份源码以支持内置服务器；专用服务器产物没有客户端类。`common/src/main/resources` 保存两端产物都需携带的服务端默认数据包和默认有效负重系数表 `bs3d_weights/burden.toml`；两份产物各自内嵌 tomlj 与 ANTLR 运行库来解析该表。`shared/src/main/proto` 和 `WeightMessages` 定义消息，不保存客户端猜测值。

2026-10-14 源码新增 `ItemMetadata.of(ItemStack).optional(Identifier)` 读取附属字段副本。本附属仅在逻辑服务器读取 `bs3d_weights:weight` 中的整数 `grams`，复用数据包同一数值校验。合法注册字段优先；缺失或无效时 WARN 一次，再回退到[数据包规则和默认自重](Weight-Rules.md)。BS3D 的 `ContainerItems.register` 必须显式传入 `optional`，原版及第三方物品可通过 `ItemMetadata.register` 补登记；类型注册字段不来自物品自定义数据或客户端请求。两端需要[安装指南](Getting-Started.md)中包含此接口的兼容 BS3D 产物，API 尚未冻结。

数据包仍可为未登记重量字段的物品配置自重，无须另写 Java 或修改模组。当前没有额外库存类型的递归扩展 API；不要通过读取客户端物品数据计算权威重量。物品上的原版 `minecraft:container`、`minecraft:bundle_contents` 组件会自动展开计重（2026-11-11 起），其他模组用这两个组件存放物品时无须另行接入；私有 NBT 或自定义组件里的库存仍只计整件自重。

单机所需的基础改动位于 Framework 和 BS3D。早期兼容提交中，Framework 客户端包含公共服务端路由，BS3D 客户端包含服务器库存实现和持久化 mixin。Framework 当前提交 997b993 与 BS3D 当前未提交源码已把这些内容移到两端共用的服务端产物，客户端产物依赖它；本仓库客户端构建因此同时从两者的服务端 JAR 编译。26.2 起房主与其他玩家一样只从 `players/data` 读档，`level.dat` 不再保存房主数据，已提交事务不会被旧 level.dat 覆盖。

运行证据和限制见[验证记录](Validation.md)。

## 不依赖玩家的根清单入口

`Burden.measure(ServerLevel level, List<CarriedStack> roots)`、`CarriedStack(ItemStack stack, BurdenSource source)`
及公开的 `BurdenSource` 位于 `dev.kizuna.bs3d.weights.server`。入口同步返回已有
`WeightsProtocol.WeightState`，不读取生物或玩家，不产生订阅消息，不缓存或保留世界／输入栈引用。

**调用方必须先看状态是 `OK` 再读克数；无效时克数字段的默认零不代表空身。**
`OK` 时 `total_grams` 是全部根和内容的实重，`effective_grams` 是按来源加权的负重，单位都是整数克。
空清单、空槽和合法零克都返回 `OK`；损坏内容、重复 BS3D 实例、超限或溢出返回
`INVALID_INVENTORY`，不带部分重量或容器明细。`session` 为空、`revision` 为 0，不是规则版本或缓存失效标记。

世界必须属于这份真实或拟提交库存；只允许在该世界的逻辑服务器主线程调用，注册表取自
`level.registryAccess()`。线程错误、Weights 尚未初始化抛出 `IllegalStateException`；null 世界、清单、
根元素、栈或来源是参数错误，抛出 `NullPointerException`，不转库存状态。清单本身不能证明物品所属世界，
调用方须保证传入正确的世界。

`CarriedStack` 只保存栈引用，不是深不可变快照。调用方在主线程组装清单并立即测量，返回前不改清单或栈；
入口复制根后读取，不初始化原物品的身份、不改数量和组件。每件实物只列一次，容器内容不要另列成根。
普通物品没有可用于追认实物的 BS3D 实例 UUID，不按物品 ID、组件相等或 Java 引用自动合并；
两份内容相同的独立实物都计重，重复提交同一件普通物品由接入方负责避免。
已有 BS3D 容器／穿搭实例 UUID 跨根或嵌套重复时，整份读数不可用。

| 生物实际位置 | `BurdenSource` | 对应现有配置键 |
|---|---|---|
| 18 格背囊的 0–8 手边行，独立主手 | `HOTBAR` | `hotbar` |
| 18 格背囊的 9–17 口袋行 | `MAIN` | `main` |
| 副手 | `OFFHAND` | `offhand` |
| 头、胸、腿、脚、BODY、SADDLE | `ARMOR` | `armor` |
| 实际光标根 | `CURSOR` | `cursor` |
| 通过 BS3D 穿戴接口装备的容器根 | `WORN_CONTAINERS` | `worn_containers` |

Weights 不强制库存格数或物种；上表是 18 格背囊的接入约定。BODY／SADDLE 即使命令才会写入也不得漏计；
双手不能又在全装备遍历中加一遍。玩家主手已在选中快捷格，玩家适配不会额外添加主手；生物主手独立于 18 格。
默认手边不计**有效负重**，仍计实重并完整校验。倍率使用 Weights 当前的同一份 `burden.toml`，调用方不能写死。
放进背囊或手中的 BS3D 包按所在位置归类，不因为是背包就改成穿戴来源。

玩家和根清单共用一次完整的递归遍历、身份集合和访问预算，保留潜影盒、收纳袋、BS3D 容器内容及既有上限。
每种来源先合计全部根与内容，再乘系数并 `HALF_UP` 舍入一次，最后相加；不逐栈舍入。
单件克数仍使用当前类型注册／数据包规则；系数仍在初始化时加载，不新增热更新系数的接口。
BS3D 容器明细按实例 ID 排序，本入口所有明细都属于随身；原版盒子不增加明细。

新入口每次即时计算，适合在拾取前测量拟插入后的**完整库存**。不能用旧重量加单件自重代替整份测量。
玩家 `effectiveGrams(ServerPlayer)`／`totalGrams(ServerPlayer)` 与订阅共用的五 tick 缓存、光标、穿戴、
隐藏槽实重和地面明细均保留；新入口不清空或刷新玩家读数。生物的刷新节拍、无效时停止拾取／保守减速及诊断
由下游决定，Weights 不设重量上限或移动惩罚。依赖 Weights 的新仓必须声明硬依赖，不添加缺失时的降级路径。
