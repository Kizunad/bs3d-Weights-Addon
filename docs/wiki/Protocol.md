# Protobuf 通信契约

唯一源为 `shared/src/main/proto/weights.proto`，Java 类由 protoc 4.36.2 生成。Addon v1 复用 Framework wire v2 的 `kizuna_inventory_ui:message` 通道，不注册第二条网络通道。

模块 ID：`bs3d_weights:weights`。

| 方向 | 消息 ID | Protobuf 类型 | 用途 |
|---|---|---|---|
| C2S | `bs3d_weights:subscribe` | `kizuna.bs3d.weights.v1.SubscribeRequest` | 空请求，订阅当前连接自己的重量，也用于主动完整重同步 |
| S2C | `bs3d_weights:state` | `kizuna.bs3d.weights.v1.WeightState` | 完整替换重量状态 |

能力均为可选。任意一端缺少重量附属时不会阻断基础库存协商。玩家身份由服务器连接提供；C2S 不接受目标玩家、库存 NBT、重量数值或规则修改。

v1 的 `SubscribeRequest` 必须为空，未知字段也会被拒绝。客户端伪造 C2S `WeightState` 时，Framework 在调用业务处理器前返回 `INVALID_DIRECTION`；拒绝请求不会修改服务器库存、规则或权威重量。若以后增加订阅字段，必须同步调整契约版本和校验。

| 字段号 | WeightState 字段 | 含义 |
|---|---|---|
| 1 | `session` | Framework 连接 UUID，换世界或重连变化 |
| 2 | `revision` | 本连接递增修订号，与 BS3D 库存修订号独立 |
| 3 | `status` | `OK` 或 `INVALID_INVENTORY`；非 OK 时数值不可显示 |
| 4 | `total_grams` | 随身总重，int64 克，不包含地面容器 |
| 5 | `containers` | 当前可见容器全部明细，含嵌套与已获准打开的地面容器 |

`ContainerWeight` 依次为：`container_id=1`（实例 UUID）、`title=2`（展示名）、`contents_grams=3`（含嵌套内容）、`total_grams=4`（自重加内容）、`carried=5`（是否随身）。地面树内所有容器的 carried 都为 false。字段号不得复用。

订阅成功立即推送；之后每 5 个服务端 tick 检查一次，20 TPS 时最多约 250 ms 延迟，仅变化时推送。计时转移提交前不改变库存，取消后重量不变。暂停的单机不继续 tick，恢复后正常更新。

传输和解码大小、方向、版本、类型、请求回执及旧连接隔离由 Framework 负责。客户端忽略同连接旧修订，断线清空缓存。服务端只读取个人库存及 BS3D 当前已授权的地面根容器，不扫描其他玩家或全世界容器。

构建同时生成 `build/distributions/bs3d-weights-addon-0.1.0-SNAPSHOT-proto.zip`，包含业务 schema、公共 wire schema 和业务描述符；Protobuf 运行库由 Framework 提供，Addon 不重复打包。

当前契约未冻结、尚未发布。安装步骤见[接入指南](Getting-Started.md)。
