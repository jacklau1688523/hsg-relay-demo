# HSG RELAY 功能完成度与可行性评估

审查对象：`hsg-relay-demo` 当前源码，2026-11-26。状态含义：**已实现且有测试证据**表示源代码有执行链且仓库有针对性测试；本轮未运行全量测试，不能等同当前环境全部通过。**部分实现**表示 Demo 链路存在但业务数据或生产控制未闭环。证据路径相对工程根目录；测试结果需由现场独立构建确认。

| 模块 | 目标功能 | 当前实现 | 代码证据 | 完成状态 | 风险 | 建议 |
|---|---|---|---|---|---|---|
| 合作方管理 | 基础档案和配置维护 | 列表、详情、配置页和 API | `provider/ProviderManagementService.java`、`static/provider-detail.js` | 已实现且有测试证据 | 配置来源仍混合 JPA 与 CONFIG JSON | 明确配置归属与迁移 |
| C1 | 客户及合作方准入 | 全局条件树 + Appetite + 审批/MTD；排除需在表达式引用 | `service/C1Service.java:112-180`、`service/C1ConditionService.java`、`C1ServiceTest` | 部分实现 | 排除不是无条件必检，政策可能被配置绕过 | 与合规确认强制项并加不可绕过边界 |
| C2 | 限制/监管能力 | 限制记录、规则及能力标签检查 | `c2/C2Service.java`、`C2ServiceTest` | 部分实现 | 客户国籍等由演示输入提供 | 对接权威 KYC/交易筛查 |
| C3 | 业务覆盖 | 行匹配、最精确选择和全局条件 | `routing/C3Matcher.java`、`C3ConditionService.java`、`C3ServiceTest` | 已实现且有测试证据 | 覆盖数据是本地配置 | 建发布审批与版本治理 |
| C4 | 头寸 | 余额减占用校验 | `routing/C4Calculator.java`、`C4ServiceTest` | 仅演示/Mock 数据 | 无实时账务、资金预占 | 接核心账务与原子预占 |
| C5 | 日期和速度分 | 截单、工作日和发布版 FIRST_MATCH | `routing/C5ScoreService.java`、`service/ScoreRuleService.java`、`C5ScoreServiceTest` | 已实现且有测试证据 | 日历为覆盖行配置，评分取决发布版 | 演示前确认版本；接生产日历 |
| C6 | 单笔/日限额 | 检查覆盖行限额和 `todayUsd` | `routing/C6Calculator.java`、`C6ServiceTest` | 仅演示/Mock 数据 | 无并发占用，超额竞态 | 额度账本、预占与回滚 |
| C7 | 成本 | 手续费、FX spread 和相对成本评分 | `routing/C7Calculator.java`、`C7ServiceTest` | 部分实现 | 汇率/费用来自演示配置 | 对接报价与费用生效版本 |
| C8 | 偏好 | 匹配偏好规则打分 | `routing/C8Calculator.java`、`C8ServiceTest` | 已实现且有测试证据 | 策略 JSON 缺单独发布流程 | 增审批和回放 |
| C9 | 商业履约 | 协议目标与月进度打分 | `routing/C9Calculator.java`、`C9ServiceTest` | 仅演示/Mock 数据 | 月累计是配置输入 | 接实际履约数据 |
| C10 | FX 窗口 | Venue/时间/额度判断 | `routing/C10Calculator.java`、`C10ServiceTest` | 部分实现 | 无真实交易锁价或执行 | 对接 Treasury/合作方 |
| 元数据与函数库 | 动态字段/函数 | 字段 CRUD、Handler 白名单、函数试算、集合包含/交集 | `api/FieldMetadataController.java`、`function/FunctionMetadataService.java`、`CollectionFunctionTest` | 部分实现 | 元数据登记不自动生成 Java；未实现通用函数链 | 建函数契约与发布治理 |
| 综合权重/排序 | W1–W4、主备路由 | JPA 权重版本、合计100、三级匹配、BigDecimal、稳定排序 | `domain/RoutingScoreWeight.java`、`RouteRankingService.java`、`ScoreWeightRoutingTest` | 已实现且有测试证据 | 单机缓存，多实例同步未实现 | 发布事件刷新、集成压测 |
| 决策审计 | 可回查过程 | `DECISION-*` JSON + 排序审计 | `RoutingService.java:243-252`、`RouteRankingService.java:108-143` | 部分实现 | 非不可篡改审计；敏感日志 | 建审计留存、脱敏与权限 |
| 支付执行 | 扣款/占额/清算 | 只返回模拟建议与 Ops Check 标识 | `RoutingService.Result`、`evaluate` 返回值 | 未实现 | 不能称生产自动支付 | 独立设计授权、幂等、补偿 |

## 可展示与暂不展示

可展示：合作方详情只读查询、元数据/函数库、C1 条件与模拟步骤、C5 已发布版本试算、权重匹配、综合候选及主备排序。若数据不满足预置案例，展示真实 REFER 与原因。暂不建议把页面上的余额、额度、汇率、商业进度说成实时值；不建议现场修改 C1 全局条件或发布 C5/权重，也不展示支付扣款。

## 技术可行性

| 维度 | 判断 | 依据与下一步 |
|---|---|---|
| 功能 | 规则过滤、评分排序的 Demo 可行；生产数据闭环待做 | `RoutingService.evaluate` 调用 C1–C10 的相应过滤/评分；复杂策略仍需专用执行器，元数据不等于任意业务逻辑 |
| 架构 | 单实例 Demo 可复用；多实例需完善 | Spring Boot/JPA/Aviator；`ScoreWeightMatcher` 文档明确进程内快照，发布跨实例通知未实现 |
| 性能 | 未验证容量；存在查询与编译放大风险 | `RoutingService` 候选循环逐个调用 C1/C2，`C1Service` 有 `findAll()`；`ExpressionService` 每次 `engine.compile(..., false)`；函数请求级缓存仅部分复用。先测候选数、查询数与 P95，再设计批量读取/预编译 |
| 资金安全 | 仅路由建议，不能执行资金动作 | `RoutingService.Result.simulation=true`，C4/C6 从配置读取；未见真实预占、并发扣减、幂等、补偿 |
| 配置安全 | C5/权重版本有基础；其它需治理 | `ScoreRuleVersion` 发布快照，权重发布校验；C1 可配置排除检查、全局 CONFIG 的审批/回滚不统一。生产需把强制合规校验独立于可选条件 |
| 交付 | 技术原型可继续演进；生产上线尚不具备 | H2 默认、`ddl-auto:update`、H2 Console 与 DEBUG 条件日志；需正式迁移、鉴权、监控、灰度和真实接口联调 |

## 下一阶段优先级

1. **P0：业务与资金边界。** 定稿 C1 合规强制项；接权威客户/名单；设计 C4/C6 原子预占、幂等、异常补偿；禁止用演示配置代表真实可用资金。
2. **P0：配置与安全。** 正式 MySQL 迁移、审批发布/回滚、权限、敏感数据脱敏、审计保留；多实例权重/评分版本同步。
3. **P1：数据与接口。** 合作方能力、覆盖、日历、汇率报价、费用和履约进度的数据所有者、时效与失败策略。
4. **P1：性能与回归。** 候选规模压测，去除循环全表读取，表达式缓存按版本失效，建立端到端、并发与故障测试。
5. **P2：函数链扩展。** 若业务确需 OBJECT/LIST 传递，再设计类型化输出引用、拓扑/深度限制与版本兼容；目前只证明集合包含、交集和受控字段解析。

**结论：** 当前源码证明“从候选生成到模拟主备路由”的技术链路已具备，适合作为负责人的可行性 Demo。它仍是基于本地演示数据的决策建议系统，不能据此宣称完成真实支付、实时资金风控或生产交付。
