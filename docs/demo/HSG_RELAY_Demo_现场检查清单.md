# HSG RELAY Demo 现场检查清单

范围：`hsg-relay-demo` 演示环境。所有 GET 是只读检查；POST `/simulate` 会在 `RoutingService.simulate` 写入一条 `DECISION-*` 演示记录，正式展示前请在隔离演示库操作。不要对当前库执行新增/修改/发布、重置或批量清理。

| 检查 | 只读办法 | 合格标准 / 备用方案 |
|---|---|---|
| 启动与入口 | `GET /actuator/health`；打开 `/`、`/fields.html`、`/c5.html`、`/routing.html`、`/score-weights.html` | 200、页面 JS 正常。失败时展示已录屏/截图并讲源码链路，勿在现场改生产配置 |
| 演示数据 | `GET /api/clients`、`/api/provider-management`、`/api/routing/config` | 选真实存在的客户、合作方和币种；`DemoData` 仅表示初始种子，不保证现场仍在 |
| 合作方可测 | 查看 LIVE 状态、覆盖行、能力标签和资金开关 | 合作方与交易维度一致；不强行包含非 LIVE 合作方 |
| C1 PASS/FAIL | `GET /api/c1/condition`、`/api/appetites`、`/api/clients`；核对客户分类与规则 | 预先列出一组预期 PASS、一组预期 FAIL；无可靠组合时讲解已保存步骤，不临时改规则 |
| 集合函数 | `GET /api/function-metadata`、`/api/field-metadata` | `collectionContains` 已启用且 `implemented=true`，排除集合解析字段存在；否则只展示架构，不做现场试算 |
| C5 不同分数 | `GET /api/c5/score-rule`、`/api/c3/reaches`、`/api/c5/schedules` | 生效评分版本和覆盖行存在，提前在隔离库试算两组时间；无版本则展示日期计算与版本列表，不承诺分数 |
| 权重和路由 | `GET /api/routing/score-weights`、`/api/routing/config`；打开 `/routing.html` | 对交易类型/层级存在生效权重；默认表单可能 REFER，提前确认并准备局部规则演示 |
| 异常数据 | 查看当前条件版本、停用字段/函数、无效日历/覆盖、C5 `NOT_PUBLISHED`、权重失效 | 只记录问题与备选样例；需要修复时安排演示前受控流程，现场不写共享库 |

建议演示前在**隔离数据库**完成一次 C1 与一次路由模拟并保存响应截图；这些 POST 属演示记录写入，不属于纯只读检查。真实支付、授权、扣款接口不在本 Demo 演示范围。
