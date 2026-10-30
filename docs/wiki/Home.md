# BS3D Weights Addon

适用：未发布的 `0.1.0-SNAPSHOT`。Addon 协议 v1，Framework wire v2，Minecraft 1.20.1、Fabric、Java 17。

服务端读取真实库存并递归计算重量，再按服务端 `burden.toml` 的来源系数算出有效负重；客户端只格式化服务端快照，背包页总览显示「实重 / 负重」。本附属只计数，没有负重上限、减速或其他惩罚；需要惩罚的附属通过服务端 `Burden` API 读取有效负重并自行决定。原版 HUD 的隐藏规则继续由宿主决定；附属不会增加常驻 HUD 或新的大面板。

- [安装与构建](Getting-Started.md)
- [数据包重量规则](Weight-Rules.md)
- [Protobuf 通信契约](Protocol.md)
- [扩展边界与 API](Extension-Guide.md)
- [验证记录](Validation.md)
