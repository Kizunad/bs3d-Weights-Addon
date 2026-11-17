# 仓库协作约定

- 交流、文档和关键方法内部注释使用简体中文，标识符使用英文。
- 有校验、分支、状态转换、线程切换或资源管理的方法，在函数内部说明关键意图、约束和原因；简单访问器不逐行解释语法。
- 条件和循环使用完整花括号，不压缩多条语句；复杂数据模型按字段分行，说明单位、类型 ID 与实例身份的区别。
- 按读取、校验、计算、发布的逻辑顺序编写代码；保持职责单一，不为未需要的功能添加抽象。
- 重量只由逻辑服务器从真实 BS3D 和原版库存计算，客户端只显示 Protobuf 快照。
- 复用 Framework 统一路由和 BS3D 公开扩展接口；不注册私有旁路通道。
- 重量用整数克；界面颜色取自 Framework 主题，尺寸与时间使用有单位的命名常量。
- 禁止魔法数；单位换算、上限和时间阈值使用命名常量，明确的测试数据及通用算术不机械命名。
- 单机与专用服务器共用服务端源码；测试夹具不得打入生产 JAR。
- docs/wiki/ 是接入文档唯一来源。修改契约或接口时同步更新，明确已实现与已验证。
- 提交前运行 `python3 scripts/wiki.py check`；文档使用稳定页名和相对 `.md` 链接，未发布前不编造 Maven 坐标或稳定 API 声明。
- 保留未提交修改；未经授权不提交、推送或发布。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
