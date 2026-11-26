# HSG RELAY Demo 演示讲解稿

适用对象：业务、产品、技术及项目负责人。演示时长约 18 分钟。审查对象为当前 `hsg-relay-demo` 源码；所有现场结果以演示库当时配置为准。提前完成[现场检查清单](HSG_RELAY_Demo_现场检查清单.md)，全程使用只读查看与模拟按钮，不现场发布或改动共享规则。

## 开场：支付路由解决什么问题（2 分钟）

讲解词：“一笔跨境支付可能有多个合作方、不同换汇渠道。银行要先排除业务、合规、覆盖、资金、额度和窗口不合适的路线，再比较到账速度、成本、偏好和商业履约。这个 Demo 展示可解释的路由建议，不会真实扣款。”

展示 `/routing.html` 的交易输入与结果区域，不点击执行。业务价值：把分散判断汇总为可追踪的候选清单。代码：`RoutingService.evaluate`、`RouteRankingService.rank`。

## 合作方与配置（3 分钟）

1. 打开 `/providers.html`，查看 `PROV-HO` 或现场确认的有效合作方；进入 `/provider-detail.html?code=PROV-HO`。展示基础状态、能力标签、准入、排除、覆盖、余额、手续费及日历页签；只查询。预期：能看到已有配置及其版本/生效信息，具体条数以现场数据为准。讲解词：“路由不是把某家机构写死在代码里；渠道、覆盖和费用从配置读取。这里的余额、费率和累计量是演示参考数据。”价值：按合作方维护关系与运营参数。证据：`ProviderManagementController/Service`、`provider-detail.js`、`RoutingConfig`。风险：页签或数据缺失时改看 `/api/provider-management/PROV-HO` 和 `/api/routing/config` 的只读响应；不要临时新增数据。

## 动态规则（4 分钟）

2. 打开 `/fields.html` 的字段库与函数库，搜索 `providerExcludedClientIds`、`collectionContains`；查看函数的 `implemented`、参数和数据源。再打开 `/`，展示 C1 条件组 AND/OR、函数节点及右侧模拟。建议用 `C10001 / PROV-HO / USD / KG / 150000 USD`，先查看左侧当前保存表达式，再点“执行 C1 校验”。预期：右侧给出每一步 PASS/FAIL 和原因码；最终是否 PASS 必须依据现场表达式与准入数据，不能预告。讲解词：“字段定义描述取什么值，函数库绑定真实 Java 实现，规则以条件树保存。比如合作方排除集合与交易客户 ID 可以用 collectionContains 判断，结果进入 C1 表达式。条件不满足时会留下明确原因。”价值：策略能配置、能解释、能回放排查。证据：`CollectionMetadataSeed`、`ProviderExclusionListResolver`、`CollectionContainsFunction`、`ExpressionService`、`C1Service.evaluate`。风险：若当前条件树不是集合案例，只展示函数定义与只读试算，不声称该场交易用到了集合函数；若此客户被挡住，现场解释具体步骤即可。不要为了演示覆盖共享条件。

3. 备用 FAIL 案例：客户 `C10003` 为 PENDING（`DemoData` 初始数据），选择有对应候选的合作方，运行 C1 模拟。是否返回 `C1_CONDITION_NOT_MATCHED` 取决于当前全局条件；若没有触发，展示现有 `APPETITE_EXCLUDED` 或其他实际原因，不伪造结果。证据：`DemoData`、`C1ConditionService`、`C1ServiceTest`。

## 起息日与版本化评分（3 分钟）

4. 打开 `/c5.html`。先看 C3 覆盖行、截单时间、时区、工作日日历，再看“评分规则”已发布版本及 FIRST_MATCH 组。选择页面已有可覆盖的合作方/币种/国家/BIC/扣费方式，输入请求起息日；选择截单前与截单后时间分别模拟。预期：页面显示最早起息日、候选起息日、延迟工作日、命中组、分数与规则版本；实际分数由已发布组决定，不保证 100/50/0。讲解词：“日期计算和评分政策分开：同一笔交易先得到可实现日期，再由当时生效的评分版本给分。草稿可以试算，但不能影响正式模拟。”价值：运营调整评分可留版本，便于解释历史决策。证据：`C5Calculator.calculate`、`C5ScoreService.evaluate`、`ScoreRuleService`、`ScoreRuleEngine`。风险：若无有效覆盖行或发布版本，展示 `NO_REACH/NOT_PUBLISHED` 及历史版本只读页；不临时发布。

## 综合路由决策（4 分钟）

5. 打开 `/score-weights.html`，展示当前生效权重，说明 W1–W4 总和 100 和三级匹配。再到 `/routing.html`，使用页面默认演示输入（`C10002`、USD/USD、10,000、US、BIC `CHASUS33`、SHA、TRADE/STANDARD），在现场核对日期、合作方状态和配置后点击模拟。预期：候选列出 C1/C2/C3/C4/C6/C10 检查；可评分者显示 C5/C7/C8/C9、总分、Primary 和最多两个 Alternate；若无可用路线则为 REFER 并列原因。讲解词：“同一笔交易的候选使用同一权重版本，先过滤再加权，按总分、成本、起息日、优先级稳定排序。页面保存的是模拟决策，不进行支付执行。”价值：路由选择可比较、可说明。证据：`RoutingService.evaluate`、`ScoreWeightMatcher`、`RouteTotalScoreCalculator`、`RouteRankingService`、`RoutingServiceTest`。风险：默认输入不保证 ROUTE；如遇 REFER，把失败原因作为演示重点，并转到 `/c5.html` 和 `/api/routing/score-weights` 展示已实现的局部闭环。

## 总结（2 分钟）

讲解词：“目前已经有可运行的规则模拟、候选过滤、版本化 C5 评分、权重匹配和主备路由排序。演示数据与真实银行生产数据仍有距离：余额、额度、汇率和合作方接口需接实时系统，生产还需要鉴权、审批发布、多实例一致性、压测及资金预占。下一阶段建议先打通真实数据和配置治理，再推进受控灰度。”

可现场回答的问题：“为什么这笔没通过？”查看候选 `checks` 和 C1 `steps`；“为什么这个分数？”查看 C5 版本、命中组和权重匹配层级；“会不会扣钱？”当前返回 `simulation=true`，并未实现真实扣款。
