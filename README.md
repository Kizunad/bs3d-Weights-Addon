# BS3D Weights Addon

Backpack and Storage 3D 的独立重量附属，MIT 许可。服务端计算，客户端展示，通过 `.proto` 定义的数据复用 Inventory UI Framework 统一路由。

- 背包页显示随身总重，容器标题显示自重与内容的合计重量。
- 统计原版物品栏、双手、盔甲、光标栈及穿戴/嵌套的 BS3D 容器；地面容器单列，不计随身总重。
- 数据包可按物品 ID 或 item tag 配置整数克，支持 `/reload`。客户端显示 kg，保留克精度。
- 单机内置服务器与独立服务器共用实现。第一版仅统计和显示。

当前为 `0.1.0-SNAPSHOT` 源码，尚未发布版本，API 尚未冻结。需要支持单机和附属接口的 Framework、BS3D 产物，旧同名 SNAPSHOT 不兼容。

构建限制（2026-10-14）：配套基础改动仍在本地，两个依赖仓库的公开 `main` 尚不满足本附属。仅克隆三个公开仓库目前不能完整构建；所需接口和已核验的远端版本见[安装构建](docs/wiki/Getting-Started.md)。

接入文档唯一来源为 [docs/wiki](docs/wiki/Home.md)：[安装构建](docs/wiki/Getting-Started.md)、[重量规则](docs/wiki/Weight-Rules.md)、[Protobuf 契约](docs/wiki/Protocol.md)、[扩展边界](docs/wiki/Extension-Guide.md)、[验证记录](docs/wiki/Validation.md)。
