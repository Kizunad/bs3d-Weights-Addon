# 验证记录

状态：2026-10-14 初版源码，尚未发布版本；配套基础源码的公开构建限制见[安装指南](Getting-Started.md)。

Java 17 下 `test build` 通过：Addon 19 项测试（服务端 14，客户端 5）；Framework 87 项、BS3D 87 项原有测试通过。

覆盖堆叠/盔甲/副手/光标计重、穿戴/嵌套不重计、最深合法容器中的普通物品、超深拒绝、地面容器排除、损坏与重复身份、int64 边界、零重量、物品和 tag 规则、优先级及字段边界、失败热重载保留旧目录、Protobuf 往返、旧修订拒绝和断线清空。

真实 Fabric 独立服务器与单机内置服务器验收均已通过，使用 Linux Xvfb 隔离运行：

- 初始随身重量 5 kg：原版手持石头、背篓中的嵌套草包与石头、穿戴空草包，三个容器均有明细。
- 计时转移取消后库存修订号仍为 1，重量不变；再次转移提交至修订号 2，草包为 2.25 kg，总重仍为 5 kg。
- 服务器写入实际数据包并 `/reload`，石头单重从 1 kg 改为 2 kg；客户端收到总重 8 kg、草包 4.25 kg，验证 Protobuf 结果和界面提供者文本。
- 单机退出后重量缓存清空；将隔离世界的 level.dat.Player 改旧后重开，得到新连接会话，库存修订号仍为 2、总重仍为 8 kg。确认已提交 playerdata 没有被旧 level.dat 回滚。

首轮证据：独服 `build/smoke/c43889ca8dcb-dedicated/`、单机 `build/smoke/71dd49e88eac-integrated/`。每个目录内有客户端日志、`client/weights-ok.txt` 和 `client/screenshots/weights.png`；独服另有服务器日志。两种环境均正常保存退出。

完整复验在 `build/smoke/aac18f22e26a-dedicated/`、`build/smoke/aac18f22e26a-integrated/` 再次通过。基础 BS3D 四阶段回归也通过，库存修订号依次为 6、16、19、20；Framework 原 wire 验收通过，包含缺少可选模块、请求回执和 HUD 隐藏/恢复。

2026-10-14 已将配套的 Framework、BS3D 和重量附属生产 JAR 部署至既有 Windows Native 手动实例，逐一核对 SHA-256；更新前备份原世界、配置和旧 JAR。Windows Java 17 / Minecraft 1.20.1 / Fabric 0.16.10 下，两端加载本附属，客户端成功连接原测试世界。首次启动发现 BS3D 客户端共享服务器 mixin 缺少 refmap 引用，修复打包后重启成功。此项只确认生产环境加载和登录，重量显示及交互留待用户手动体验；没有运行自动夹具或修改玩家物品。

未进行双人手动体验、单机地面容器交互专项或主观 UI 审美验收。地面容器的重量归属由服务端测试覆盖。本附属 Wiki 独立检查通过。

## 可读性与协作规则检查

2026-10-14 回读全部手写 Java、测试夹具、构建脚本及接入文档，并按仓库 AGENTS.md 与此前约定整理：

- 保持规则解析、整树计算、连接推送、客户端快照和 UI 文本的职责分离；未增加业务行为或扩展框架。
- 将复合校验拆成身份、状态和数值的顺序检查；递归计算分别报告堆叠、深度、重复身份及数量问题。
- Protobuf 构建链按字段展开；规则模型解释类型 ID、实例身份、单位和优先级；关键分支及资源管理补中文意图注释。
- 单位换算和阈值使用命名常量，自动验收阶段使用英文枚举替代数字状态；条件和循环保留完整花括号。
- 自动夹具独立于生产源码；生产 JAR 检查确认没有 smoke 类，专服产物没有客户端或 owo 类，也没有重复打包 Protobuf 运行库。
- 忽略构建目录、日志、崩溃报告和本地依赖；公开文档使用通用概念，不含开发者本机绝对路径。

整理后的 Java 17 `test build` 通过 19 项测试。真实 Fabric 独服与单机完整复验再次通过，证据为 `build/smoke/db19c744daad-dedicated/` 与 `build/smoke/db19c744daad-integrated/`；两者的 `client/weights-ok.txt` 均记录成功，覆盖上述嵌套计重、转移取消与提交、热重载、Protobuf、UI，以及单机世界重开。Wiki 检查通过 7 页、19 个内部链接。

该检查是人工回读与实际构建核验，不代表 API 已冻结或代码不存在缺陷。

```sh
./gradlew test build
python3 scripts/wiki.py check
python3 scripts/smoke.py --java-home "/path/to/jdk17"
```

smoke 在全新 `build/smoke/<run-id>-<mode>/` 创建世界，使用端口 25583，仅 Linux Xvfb 自动运行。它会生成物品、测试计时转移与取消、通过数据包热重载改重量，并在完成后正常退出。测试源码不打入生产 JAR，不能用它替代玩家手动体验。

文档与构建入口见[安装指南](Getting-Started.md)。
