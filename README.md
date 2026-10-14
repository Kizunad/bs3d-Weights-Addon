# BS3D Weights Addon

Backpack and Storage 3D 的独立重量附属，MIT 许可。服务端计算，客户端展示，通过 `.proto` 定义的数据复用 Inventory UI Framework 统一路由。

权威实现位于 `server/src/main/java`：读取服务端真实库存、校验并计算重量，再推送客户端。客户端只显示快照；单机复用同一份逻辑服务器代码，不从客户端库存推算重量。

- 背包页显示随身总重，容器标题显示自重与内容的合计重量。
- 统计原版物品栏、双手、盔甲、光标栈及穿戴/嵌套的 BS3D 容器；地面容器单列，不计随身总重。
- 数据包可按物品 ID 或 item tag 配置整数克，支持 `/reload`。客户端显示 kg，保留克精度。
- 单机内置服务器与独立服务器共用实现。第一版仅统计和显示。

当前为 `0.1.0-SNAPSHOT` 源码，尚未发布版本，API 尚未冻结。需要支持单机和附属接口的 Framework、BS3D 产物，旧同名 SNAPSHOT 不兼容。

配套 Framework、BS3D 源码已公开。当前接口要求与已验证的兼容提交见[安装构建](docs/wiki/Getting-Started.md)，请按其说明依次构建三个仓库；尚无正式 Release 或 Maven 坐标。

接入文档唯一来源为 [docs/wiki](docs/wiki/Home.md)：[安装构建](docs/wiki/Getting-Started.md)、[重量规则](docs/wiki/Weight-Rules.md)、[Protobuf 契约](docs/wiki/Protocol.md)、[扩展边界](docs/wiki/Extension-Guide.md)、[验证记录](docs/wiki/Validation.md)。
