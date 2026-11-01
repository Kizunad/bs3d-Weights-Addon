# 验证记录

状态：2026-10-14 初版源码，尚未发布版本；配套基础源码已公开，兼容提交见[安装指南](Getting-Started.md)。

## 2026-11-02：BS3D 移除草包、背篓、收纳箱

BS3D 删除这三件容器，本仓随之删除 `grass_pouch`、`back_basket`、`storage_box` 三条内置重量规则（物品 ID 不存在时整份规则重载会失败）。BS3D 自带的九件 Bong 容器没有内置规则，按未命中计 100 g。旧存档里 BS3D 按损坏容器保留的三件旧容器，`WeightCalculator` 改为跳过不计重，不再使整份随身重量变为不可用；其他损坏数据仍整体拒绝。说明见[重量规则](Weight-Rules.md)。

单元测试与验收夹具改用灵草囊（`bs3d:herb_pouch`）和骨架背篓（`bs3d:gu_jia_bei_lou`）；骨架背篓内外尺寸相同，用于逐层自套的嵌套上限测试。Java 25 `test --rerun build :client:compileSmokeJava :server:compileSmokeJava` 通过：服务端 **28 项**、客户端 **5 项**，共 **33 项**，无失败或跳过；新增 1 项覆盖身上与容器内的旧容器跳过计重。日志为 `build/tabclean-test-build.log`。

真实 Fabric 专服与 Xvfb 客户端两种模式均通过：

- `build/smoke/7bf30655147c-dedicated/client/weights-ok.txt`
- `build/smoke/0190ec84e8ee-integrated/client/weights-ok.txt`

覆盖项不变；两件 Bong 容器各按 100 g 计，预期值随之改为初始 4050 g、灵草囊转移后 2.1 kg、热重载后 7050 g，截图显示「实重 7.05 kg / 负重 4.1 kg」与「灵草囊 4.1 kg」。缺少字段告警的物品变为石头、骨架背篓和灵草囊。旧存档跳过计重只有单元测试，没有在游戏中加载旧世界验证。

## 2026-11-01：迁移到 Minecraft 26.2 / Java 25

运行环境改为 Minecraft 26.2、Fabric Loader 0.20.1、Fabric API 0.161.0+26.2、owo 0.13.1+26.2、Java 25，构建使用 Gradle 9.8.0、Fabric Loom 1.19.3。重量规则、系数表、Addon 协议 v1 与显示不变，配套 Inventory UI Framework 与 BS3D 为同日迁移的 26.2 版本。

随 26.2 调整的地方：

- 规则重载改用 Fabric resource-loader v1 的 `ResourceLoader` 登记（ID `bs3d_weights:rules`），规则类改实现原版 `ResourceManagerReloadListener`。
- BS3D 容器内容改按数据组件编码，`WeightCalculator.calculate` 增加注册表参数，订阅会话与 `Burden` 传入玩家的注册表。
- 26.2 玩家库存在副手之后另有身体盔甲（41 号）与鞍（42 号）两格，`InventoryAccess` 快照随之为 43 格。这两格计入实重、不属于任何有效负重来源；越界槽号判断改为 43 格。
- 示例与夹具数据包的 `pack.mcmeta` 改用 26.x 的 `min_format`/`max_format`。

Java 25 `test build :client:compileSmokeJava :server:compileSmokeJava` 通过：服务端 **27 项**、客户端 **5 项**，共 **32 项测试**，与迁移前相同，无失败或跳过。服务端测试改为每个测试类独占 JVM：物品登记须在注册表冻结前，标签绑定须在冻结后。两端安装产物为 `jar` 任务输出，均不含 smoke 夹具或 refmap；专服 JAR 不含客户端或 owo 类，两端都内嵌 tomlj 与 ANTLR 运行库。

真实 Fabric 专服与 Xvfb（llvmpipe 软件渲染）客户端联调两种模式均通过：

- `build/smoke/861ea22efb89-dedicated/client/weights-ok.txt`
- `build/smoke/c864222f879a-integrated/client/weights-ok.txt`

覆盖项与此前相同：注册金锭后初始 5750 g、伪造订阅被拒、转移拒绝与提交、嵌套计重、数据包热重载后 8750 g、Protobuf 与界面文字。缺少字段的石头、背篓和草包首次计重各告警一次，每次成功重载后再告警一次，日志次数与迁移前相同。

单机重开一步按 26.2 的存档方式改写。26.2 的房主与其他玩家一样只存 `players/data/<UUID>.dat`，`level.dat` 不再带 `Player`，原先「把 level.dat.Player 改旧」所模拟的覆盖来源已不存在。探针现在先确认 `level.dat` 没有 `Player`、房主 playerdata 存在，再把同目录的 `.dat_old` 备份换成空玩家数据；重开后要求得到新连接会话，库存修订号仍为 2，总重仍为 8.75 kg。

两种模式的 `weights.png` 与 2026-10-31 的 1.20.1 截图对比：总览「实重 8.75 kg / 负重 4.25 kg」、草包标题「4.25 kg」、人物与内景布局一致。天空多了云层（26.2 在渲染距离 2 时也绘制云）；内景底面与石块的明暗略有不同，来自 BS3D 内景渲染。未在 Windows 实例上验证。

## 有效负重

2026-10-30 源码新增有效负重，尚无正式发布版本。Java 17 `test build` 通过 **32 项**测试（服务端 27，客户端 5），其中新增 `BurdenTest` 7 项：

- 随 JAR 发布的默认系数逐来源加权，快照同时带实重与有效负重。
- 64 块石头放快捷栏：实重 64 kg，有效负重 0；放主库存：实重 64 kg，有效负重 128 kg。
- 主库存范围由常量决定：原版到 35 号槽，装了 Pocket 只到 17 号槽；Pocket 隐藏槽只计实重。
- 小数系数按来源只舍入一次；有效负重溢出时整次计算失败。
- 缺键、多余键、负数、`nan`、`inf`、字符串和语法错误都拒绝整份系数表；越界槽号被拒绝。

客户端测试覆盖 `effective_grams` 往返和负数拒绝。本轮未重跑 Xvfb smoke，也未在 Windows 实例部署；smoke 只断言实重，`Burden` API 的真实服务器读取由 Kizuna Meridian Burden 附属的 GameTest 覆盖。

## 物品 optional 字段联调

2026-10-14 源码已实现并验证 BS3D 注册字段支持，尚无正式发布版本；需要[安装指南](Getting-Started.md)中包含 `ItemMetadata` 的兼容 BS3D 产物。Java 17 `test build` 通过 **25 项**测试（服务端 20，客户端 5），配套 BS3D 通过 93 项测试。

新增服务端测试覆盖注册值优先于数据包并进入真实计重、零克和上限、负数/小数/字符串/溢出/null/缺 grams 拒绝并回退、命名空间隔离、WARN 内容及去重、成功和失败重载行为、伪造物品 NBT 无法改写注册值或默认值。

真实 Fabric 独服和单机验收通过，证据分别为 `build/smoke/f56921324191-dedicated/`、`build/smoke/f56921324191-integrated/`，各目录的 `client/weights-ok.txt` 已产生成功标记：

- 在服务端真实库存增加两件通过 `optional` 登记为 375 克的金锭，初始总重为 5750 克，客户端显示来自 Protobuf 快照。
- 草包描述键能够翻译，原版 `ItemStack.getTooltipLines` 包含注册描述；内景继续复用同一 tooltip 流程。
- 缺少字段的石头、背篓和草包在服务端日志中各 WARN 一次；成功重载后重新告警，期间不按 tick 刷屏。
- 伪造订阅被拒绝，修订和总重不变；被拒绝的转移与随后的提交、嵌套计重、服务端数据包重载通过，重载后总重为 8750 克。
- 单机同世界重开、旧 level.dat 恢复及新连接会话通过，两端正常保存退出。

生产 JAR 不包含测试注册或自动夹具，专服产物不含客户端或 owo 类。未启动 Windows Native，也未进行新增悬停位置的完整手动体验。Wiki 检查通过。以下记录保留为历史版本验证，不表示旧 JAR 已有本次接口。

## 初版验证

Java 17 下 `test build` 通过：Addon 20 项测试（服务端 15，客户端 5）；Framework 87 项、BS3D 87 项原有测试通过。

覆盖堆叠/盔甲/副手/光标计重、穿戴/嵌套不重计、最深合法容器中的普通物品、超深拒绝、地面容器排除、损坏与重复身份、int64 边界、零重量、物品和 tag 规则、优先级及字段边界、失败热重载保留旧目录、Protobuf 往返、旧修订拒绝和断线清空。

真实 Fabric 独立服务器与单机内置服务器验收均已通过，使用 Linux Xvfb 隔离运行：

- 初始随身重量 5 kg：原版手持石头、背篓中的嵌套草包与石头、穿戴空草包，三个容器均有明细。
- 被拒绝的转移不改库存修订号（仍为 1）与重量；再次转移提交至修订号 2，草包为 2.25 kg，总重仍为 5 kg。
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
python3 scripts/smoke.py
```

`--java-home` 可指定运行 Gradle 的 JDK（Loom 1.19 与 Minecraft 26.2 须为 Java 25），省略时沿用当前环境。

smoke 在全新 `build/smoke/<run-id>-<mode>/` 创建世界，默认端口 25583，仅 Linux Xvfb 自动运行。端口冲突时可通过 `--port` 改用其他端口，客户端与独服统一使用该值；不连接或停止占用端口的现有服务器。它会生成物品、测试计时转移与取消、通过数据包热重载改重量，并在完成后正常退出。测试源码不打入生产 JAR，不能用它替代玩家手动体验。

## 服务端权威校验

2026-10-14 将权威实现归入 `server/src/main/java`，客户端构建复用该源码以支持内置服务器；客户端展示层没有本地计重或上报重量入口。生产端分别包含服务端计算类，两端 JAR 不含 smoke 夹具，专服 JAR 不含客户端／owo 类。

- 新增路由测试绕过客户端校验，直接发送伪造的 C2S `WeightState(total_grams=0)`；真实 `ServerRouter` 返回 `INVALID_DIRECTION`，业务处理器调用次数为 0，随后合法订阅仍被接受。
- 真实 Fabric 独服和单机均发送携带伪造重量未知字段的 `SubscribeRequest`，返回 `REJECTED`；权威总重仍为 5 kg，重量修订号和库存修订号不变。
- 继续完成转移被拒、提交、嵌套计重、服务端数据包热重载及 UI 显示；单机同世界重开验证新连接会话和旧 level.dat 恢复。

本轮完整证据为 `build/smoke/164141707dc2-dedicated/`、`build/smoke/164141707dc2-integrated/`，两者 `client/weights-ok.txt` 均通过，客户端和独服正常保存退出。默认端口当时被其他进程占用，复验使用 `--port 25584`，保留原进程。三个仓库的 Wiki 检查通过；未启动或更新 Windows Native 实例。

文档与构建入口见[安装指南](Getting-Started.md)。
