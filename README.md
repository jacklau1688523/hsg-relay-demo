# HSG RELAY — C1 + C2 可视化规则配置演示项目

基于 RELAY V1.10 C1/C2 规格、原管理后台截图和字段库/AND/OR/能力标签需求制作：Java 17 / Spring Boot 3.3.5 / Spring Data
JPA / H2（默认） / MySQL（可选）/ AviatorScript 5.4.1 / 原生 HTML/CSS/JS（无需 Node）。

## 启动

```bash
java -version  # Java 17 或更新版本
mvn spring-boot:run
```

浏览器访问：**http://localhost:8080/**（C1）和 **http://localhost:8080/c2.html**（C2）。左侧导航可在 C1 / C2 间切换。

静态页面、JS、CSS 由服务端返回 `Cache-Control: no-cache`（`application.yml` 的 `spring.web.resources.cache.cachecontrol`）：浏览器每次打开
页面都会向服务端确认是否有更新，未变化返回 304。否则浏览器会按 `Last-Modified` 自行判定旧页面仍可使用，页面更新后继续显示旧菜单；若遇到这种情况，强制刷新一次（macOS
⌘⇧R）即可。

默认 H2 文件数据库：`./data/relaydb`；自动建表，第一次启动自动灌入示例合作方、C1 准入规则、客户、个案审批、MTD、C2
限制规则、监管规则、能力标签。

数据库控制台：`http://localhost:8080/h2-console`，JDBC URL `jdbc:h2:file:
./data/relaydb;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1`，用户名 `sa`，密码留空。

### 用 MySQL 启动

1. `CREATE DATABASE hsg_relay CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
2. 设置 `DB_USERNAME` 和 `DB_PASSWORD` 环境变量。
3. `mvn spring-boot:run -Dspring-boot.run.profiles=mysql`

开发环境 `ddl-auto=update` 会根据 Entity 创建表。生产环境应使用 Flyway/Liquibase，不能使用 `update` 自动变更表结构。对于已有
C1 MySQL 数据库，自动更新会增加 `relay_rule_field.value_options` 列并新建四张 C2 表；若采用手工 DDL，请只执行一次
`sql/migrate-c1-to-c2.sql` 的字段迁移，并运行 `sql/schema-mysql.sql` 中 C2 新表的 `CREATE TABLE IF NOT EXISTS` 语句。

2026-11-24 起 `relay_regulatory_rule` 多一列可空的 `trigger_condition_json`（C2 监管规则的附加触发条件，见「C2 功能」）。已有的 H2 / MySQL
数据库在 `ddl-auto=update` 下重启即自动补列，旧规则该列为空、照常按范围执行；采用手工 DDL 的 MySQL 数据库执行一次
`sql/migrate-c2-trigger-condition.sql`（新执行 `schema-mysql.sql` 建表的库已包含该列，无需再执行）。

## C1 功能

- 准入规则列表、筛选、新增、编辑、启用/停用、版本号、审计记录、相同维度有效期冲突检查
- 客户排除记录和有效期检查
- CASE_BY_CASE 个案审批及有效期检查
- HKT 自然月 MTD 查询，C1 模拟校验，逐步骤 PASS / FAIL 和原因码
- MTD 模拟数据页面（工具 → MTD 模拟数据）：按「合作方＋流转类别＋月份」通过 `PUT /api/usage` 覆盖月累计金额，用于演示月度上限 `APPETITE_CAP_REACHED`；无定时任务，C1 只读取香港当月记录
- 字段库与 AND/OR 条件组 → 后端生成安全受控表达式 → AviatorScript 执行试算
- **C1 全局准入条件按元数据中心字段库动态构建**：首页「① 全局准入条件：字段库 + 函数库」通过 `GET/PUT /api/c1/condition` 独立读取和保存一棵条件树；每次 C1 判定先执行该树，表达式不满足即过滤候选合作方并返回 `C1_CONDITION_NOT_MATCHED`，满足才继续客户排除、准入规则、个案审批和 MTD。字段下拉读取 `GET /api/field-metadata`，运算符和值均受字段元数据约束
- C1 模拟校验支持附加条件字段：除固定的交易要素外，模拟面板按字段库为其余 C1 字段自动生成取值控件；条件缺值或失效的规则按不命中处理并在校验过程中给出 `SKIP` 说明（见下文「附加条件的取值与不命中处理」）
- **C1 触发条件接入函数库**：条件组里可以添加字段条件、子条件组和函数条件；「ƒ 添加函数」选择器列出可用于 C1 的业务函数（名称、编码、返回类型、参数与数据来源），选好比较方式和期望值后加入条件树。客户排除、准入匹配、个案审批、MTD 四项检查由 C1 执行器在代码里强制落实（系统必检），与条件树里有没有对应节点无关（见下文「C1 条件接入函数库」）
- 演示模拟不会扣减 MTD

## C2 功能（本次新增）

- **监管路由规则页面**（参照合作方管理，列表与表单分成两个页面，监管规则的维护全部在这两页完成）：
  - 列表 `/c2-regulatory.html`（各页左侧「合作方管理」下方的「监管路由规则」）：顶部四张汇总卡（规则总数、当前生效、已停用、附加条件已失效）；筛选条件为编码 / 名称、交易币种、状态、附加触发条件（全部规则、
    有附加条件、无附加条件、附加条件已失效）；表格列出规则编码与名称、适用范围、附加触发条件、必需能力标签、有效期（HKT）、状态、版本，行内提供「编辑」「停用 / 启用」「删除」（启停和删除需确认）。
    未到生效时间、已过失效时间的规则在有效期列标出，这两种规则当前不执行。
  - 新增 `/c2-regulatory-edit.html`、编辑 `/c2-regulatory-edit.html?rule=规则编码`（由列表行「编辑」进入）：规则编码、名称、生效 / 失效时间、备注、适用范围、必需能力标签、附加触发条件在同一个表单里，一次保存，
    版本号加 1；编码仅在新增时可填（默认给出尚未占用的 `REG-00N`），保存后地址栏变为该规则的编辑地址。右侧「规则预览」随表单实时更新；编辑页另有「C2 模拟验证 ↗」打开 C2 模拟页。
- **适用范围 = 交易币种 + 受益行国家**：规则是全局配置，适用范围不再使用自由 AND/OR 条件树。交易币种单选，可选值取自元数据中心「交易币种」（`paymentCurrency`）字段；受益行国家为
  `ALL`（不限）或任一 ISO 3166-1 两位代码（下拉带中文名称，`GET /api/c2/regulatory-rules/options` 返回全部选项）。表单右侧实时预览整条规则。
- **落库形态不变**：范围仍以标准条件树写入 `condition_json`（`paymentCurrency EQ X` 且 `beneficiaryBankCountry EQ Y`，不限国家时只有前一个叶子），C2
  模拟和全流程 Check 照常读取；标准规则按币种、国家直接比对，不依赖字段库里这两个字段的状态。旧版页面保存的自由条件树在列表里标记为「旧版条件」，仍按原条件编译执行，在页面重新保存后转为标准形态。
- **C2 触发条件按字段库构建（附加触发条件）**：在监管路由规则的新增 / 编辑页（`/c2-regulatory-edit.html`）维护，和适用范围、能力标签同在一个表单里、随规则一次保存。交易须落在规则范围（币种 +
  受益行国家）内**且**满足该条件，规则才触发；不启用则范围内一律触发。勾选「启用附加触发条件」后出现条件编辑器：字段下拉读取 `GET /api/c2/fields`（元数据中心里已启用且适用规则含 C2 的全部字段），运算符取字段的允许运算符，
  有可选值的字段渲染下拉，支持嵌套 AND / OR；提供「校验并生成表达式」「↻ 刷新字段库」（重新读取下拉项和字段库；操作员改过编辑器里的条件时保留编辑中的条件）「复制规则 JSON」（复制本次保存的请求体）。
  取消勾选后保存即清除已保存的附加条件（清除前页面先确认）。已保存的条件失效时，列表的「附加触发条件」列标出「附加条件已失效」，进入编辑页核对后重新保存即可。
  `/c2.html` 只做模拟校验，不再承载规则配置：原列表的「配置 →」链接和 `/c2.html?rule=` 深链接已移除，带该参数打开等同于直接打开模拟页。列表在「附加触发条件」一列显示条件摘要；启停不涉及附加条件。
- **监管能力要求**：每条 GLOBAL 监管规则可勾选多个 `requiredTags`（ALL 语义），范围命中但合作方缺失任何一项，返回
  `REG_RULE_NOT_MET`。原因码不可修改。
- **合作方限制**：`BENEFICIARY_COUNTRY` / `DIRECTOR_UBO_NATIONALITY` / `BENEFICIARY_REGION` / `ORDERING_COUNTRY` /
  `INTERMEDIARY_COUNTRY` / `CURRENCY` / `PURPOSE`，逐项配置屏蔽名单，自动映射固定返回码。维护入口在合作方详情页（
  `/provider-detail.html?code=合作方编码&tab=restrictions`）的「合作方限制」页签，按合作方显示其限制列表并支持新增 /
  编辑 / 启停（C2 校验实时读取）。
- **合作方能力标签**：按合作方编辑标签（如 `SWIFT`、`CNY_ONSHORE`）。维护入口在合作方新增 / 编辑页（`/provider-edit.html`）的「C2
  能力标签」勾选区——原 C2 页面的「合作方能力标签」标签页已并入此处，C2 页面仅保留模拟校验中的标签只读展示。
- **C2 校验顺序与配置来源**（`/c2.html` 顶部面板，对应 C1 首页的「过滤顺序与配置来源」）：按规则编辑器设计的 C2 条件结构展示——根条件组 8 项、全部满足（AND），每项是一个规则组，组内是若干「字段命中屏蔽 等于 false」的叶子条件，
  叶子之间也是 AND（只有一个叶子的组标为「单项判断」）：① 受益方国家未被限制（受益行国家、受益人地址国家）→ `RESTRICTED_COUNTRY`；② 董事 / UBO 国籍未被限制（客户董事国籍、客户 UBO 国籍）→
  `RESTRICTED_NATIONALITY`；③ 受益人地区未被限制 → `RESTRICTED_REGION`；④ 付款 / 发起方国家未被限制（付款客户国家、发起方国家）→ `RESTRICTED_COUNTRY`；⑤ 中间行国家未被限制 →
  `RESTRICTED_COUNTRY`；⑥ 交易币种未被限制（扣账币种、交易币种）→ `RESTRICTED_CURRENCY`；⑦ 交易用途未被限制 → `RESTRICTED_PURPOSE`；⑧ 监管路由要求已满足（监管能力要求不满足）→
  `REG_RULE_NOT_MET`。最终判定：全部字段均为 false → C2 PASS；任意字段为 true → C2 FAIL，记录全部命中原因。每张卡片下方是配置来源：第 1–7 项的「合作方限制 ↓」把下方的「合作方限制查询」筛选到该限制类型并滚动过去，
  第 8 项链接到「监管路由规则」页。右上角可「收起规则组 / 展开规则组」，收起后保留根条件组和最终判定（不记忆状态）。
- **C2 模拟测试**：右侧填写交易国家、币种、中间行、董事国籍和 UBO 国籍（两项分开填写）、用途等字段，返回全部命中限制、主原因码、监管规则是否触发（附规则的适用范围和本次交易的币种
  / 受益行国家）和缺失标签；旧版规则另附编译表达式。受益行国家下拉在字段库可选值之外补齐全部 ISO 代码，任意国家的监管规则都能在这里模拟；不会释放交易或占用额度。
  配置了附加触发条件、且条件引用了九个固定输入项之外的字段时，右侧自动出现这些字段的取值控件（「附加触发条件字段（按字段库生成）」）；执行过程里每条规则另附附加条件摘要、
  编译后的表达式，以及缺值 / 失效时的处理说明。
  结果里的「校验过程」与顶部面板同一结构：8 个规则组逐组 PASS / FAIL，每个叶子给出「等于 false → 实际 true / false」，并附本次取值（未填写时标「本次未填写」）、命中的限制记录，或监管规则缺少的能力标签；
  不满足的组标出原因码，末行为最终判定。逐组结果来自接口返回的 `groups`（见「C2 REST API」），页面不自行推断；国籍取值只取自表单输入，接口响应不含国籍取值。董事国籍、UBO
  国籍分开提交，第 2 项的两个叶子分别给出结果。
- **版本与审计**：每次修改规则递增 `version_no` 并写入 `relay_rule_audit`（新增 / 编辑页的保存记为 `CREATE` / `UPDATE`，附加触发条件随规则一并保存时也是这一条记录；仅单独调用 `PUT /api/c2/regulatory-rules/{id}/condition` 时记为
  `UPDATE_CONDITION`）；演示项目为覆盖式编辑+审计，生产需改为不可变发布版本。

**附加触发条件的取值与处理：**

- 附加条件是监管规则的触发条件：交易须落在「币种 + 受益行国家」范围内**且**附加条件命中，规则才触发并检查能力标签；范围不命中时不求附加条件。
- 附加条件可以引用字段库里任何已启用且适用 C2 的字段。`paymentCurrency`、`debitCurrency`、`beneficiaryBankCountry`、`beneficiaryAddressCountry`、`beneficiaryRegion`、`orderingCountry`、`clientCountry`、`intermediaryCountry`、`purpose`
  九个交易要素固定取自模拟表单的同名请求字段，不能用 `variables` 覆盖；其余字段的取值来自模拟请求的 `variables`（字段编码 → 值）。C2
  模拟面板为它们自动生成取值控件：布尔 → true / false 下拉，数字 → 数字输入框，有可选值 → 下拉，其它 → 文本框，首项均为「（不提供）」。
- 条件引用的字段本次**没有取值**（选了「（不提供）」、留空，或调用方没有传 `variables`）时，该规则按**未触发**处理，`regulatoryChecks[].note` 说明原因，校验不报错——与
  C2「未填写的可选字段按未命中处理」一致。
- 条件**已失效**（规则保存后，所引用的字段在元数据中心被停用、不再适用 C2、运算符被取消，或条件值已不在可选值中）时，该规则按**触发**处理并给出 `note`：监管规则是限制而不是准入，
  失效时不能放宽，退化成只按范围触发；列表里标记「附加条件已失效」，需在「监管路由规则」编辑页核对后重新保存。（C1 附加条件失效时按不命中处理、默认拒绝；两者方向相反，但都是不放宽。）
- 取值与字段类型不符（布尔字段传 `yes`、数字字段传非数字）返回 400 并指出字段，与 C1 相同。
- 编辑器载入的条件引用了当前不可用的字段或运算符时，会把该条件换成当前可用项并提示核对，条件区下方同时标明「编辑器里的条件与已保存的不同」；操作员没有另外改动时，字段恢复可用后点「↻
  刷新字段库」即还原为已保存的条件。条件值已不在可选值中时，下拉原样列出该值并标注「已不在可选值中」，不会悄悄改成其它值。保存前按元数据中心校验，
  不通过返回 400、规则不变（连同一起提交的范围、标签等整条规则都不保存）；保存时带上页面载入时的版本号，规则在别处被改过则提示「规则版本已变化，请刷新后重试」。清除附加条件即不勾选「启用附加触发条件」
  （接口层为提交 `trigger.enabled=false`）。
- C1–C10 Check（`/routing.html`）的 C2 校验只传九个固定交易要素、不传 `variables`：附加条件只引用这九个要素（例如 `purpose`）的监管规则在 Check
  中照常生效；引用了其它字段的规则在 Check 中按未触发处理（缺值）。Check 页的 C2 规则表在规则名下方显示附加触发条件摘要（已失效的条件标注「已失效，按触发处理」）。

2026-11-24：监管路由规则由 AND/OR 条件树改为「交易币种 + 受益行国家 + 必需能力标签」的全局配置。全套202项测试通过（新增13项）：`RegulatoryScopeTest` 4项覆盖标准条件树与币种
/ 国家的互转、自由条件树不被误读为范围、不限国家与大小写匹配、ALL + ISO 国家选项；`C2RegulatoryRuleTest` 5项覆盖保存与落库形态、字段库停用后照常求值、不限国家、旧版条件树继续执行并在重新保存后转换、
非法币种 / 国家拒绝；`C2RegulatoryControllerTest` 3项覆盖 HTTP 契约与 400 响应；`FieldMetadataControllerTest` 新增1项覆盖 BOOLEAN / 数字字段勾选 C2
后可试算。在独立端口与临时数据库上以无头 Chrome 完成 59 项检查：列表载入与行高亮、编辑回显、新增（空名称、重复编码拦截）、实时预览、保存后落库为标准条件树并被 C2
模拟命中、非 ISO 国家与非字段库币种返回 400、启停、改为不限国家、删除、直接入库的旧版条件树规则标为「旧版条件」并按原条件执行且重新保存后转换、C2
模拟页受益行国家下拉含全部 ISO 代码、元数据中心 BOOLEAN 字段勾选 C2 后保存并试算、全流程 Check 的 C2 规则表；桌面与 390 宽度布局正常，无前端 JavaScript
异常。验证未读写 8080 / 8081 实例的数据库。

2026-11-24（附加触发条件与菜单位置）：C2 模拟页新增「① 触发条件：按字段库构建」，各页侧边栏的「监管路由规则」入口移到「合作方管理」下方（17 个页面）。全套216项测试通过（新增14项）：
`C2RegulatoryRuleTest` 新增7项覆盖附加条件收窄规则、缺值按未触发并附说明、失效条件仍触发并报告问题、布尔 / 数字叶子按字段类型比较、九个固定输入不被 `variables`
覆盖、非法条件 / 未知规则 / 版本过期被拒绝、范围与标签的修改不改动附加条件且可清除；`C2RegulatoryControllerTest` 新增3项覆盖试算按元数据中心校验、保存 / 列表 / 求值 /
清除的 HTTP 契约、未知规则 / 版本过期 / 非法条件树返回 400；`RegulatoryTriggerTest` 3项覆盖无附加条件、条件文本、非法 JSON；`SchemaRestartTest` 新增1项覆盖既有库升级时
补上 `trigger_condition_json` 列且数据不丢。在独立端口与临时数据库上以无头 Chrome 复跑上述 59 项检查，并对最终构建完成 21 项冒烟：面板载入（字段下拉 = `/api/c2/fields`、
规则下拉、默认选中首条规则）、元数据中心新增 BOOLEAN 字段和新增规则后刷新可见并生成 true / false 控件、组装条件 → 生成表达式 → 保存（版本 2，范围与标签不变）、模拟命中 /
缺值未触发 / 条件未命中、监管路由规则页的标识与「配置 →」链接、编辑预览、`/c2.html?rule=` 深链接、Check 页的附加条件摘要、清除（版本 3）；C2 模拟页、监管路由规则页、
Check 页三个页面的侧边栏顺序在浏览器里核对，17 个页面的入口位置逐页文本核对；无前端 JavaScript 异常。失效条件在编辑器里的状态、刷新时保留 / 还原编辑中的条件、版本过期时的
保存提示、新面板在 390 宽度下的布局未做浏览器验证（处理逻辑由上述测试覆盖）。验证未读写 8080 / 8081 实例的数据库。
这一轮加入 C2 模拟页的「① 触发条件」面板、列表的「配置 →」链接和 `/c2.html?rule=` 深链接，已在下一条调整中移除。

2026-11-24（监管路由规则改为独立的新增 / 编辑页）：参照合作方管理，`/c2-regulatory.html` 改为纯列表页（汇总卡、筛选、行内操作），新增 / 编辑移到独立页 `/c2-regulatory-edit.html`，附加触发条件并入同一个表单、随规则一次保存；
`POST /api/c2/regulatory-rules` 增加可选的 `trigger` 字段，新增 `GET /api/c2/regulatory-rules/{id}`；C2 模拟页（`/c2.html`）只保留模拟校验。全套218项测试通过（新增2项）：`C2RegulatoryRuleTest.ruleAndTriggerConditionAreSavedInOneRequest`
覆盖规则与附加条件一次保存（版本 1）、不带 `trigger` 的保存保留附加条件、带 `trigger` 替换、条件不合法或缺少条件树时整条规则不保存、版本过期被拒绝、`enabled=false` 清除、每次保存一条 `CREATE` / `UPDATE`
审计；`C2RegulatoryControllerTest.editPageSavesTheTriggerWithTheRuleAndLoadsOneRule` 覆盖新增 / 编辑页用到的 HTTP 契约（带 `trigger` 的新增、按编码载入单条规则与不存在的编码返回 400、条件不合法返回 400
且不保存、版本过期、清除）。在独立端口与临时数据库上以无头 Chrome 完成 52 项检查：列表（汇总卡、编码 / 币种 / 状态 / 附加条件四类筛选、「旧版条件」标记、启停的确认文案与版本变化）、新增页（默认编码、
未选能力标签被拦截、启用附加触发条件 → 组装条件 → 生成表达式 → 实时预览 → 保存后地址栏变为编辑地址、编码变为只读）、保存后的条件驱动 C2 模拟（`INVESTMENT` 触发、`GOODS`
不触发）、编辑页（回显、修改条件后保存为版本 2、取消勾选后确认清除并保存为版本 3）、规则不存在时的提示、旧版规则的回填提示、附加条件失效（列表标记与筛选、编辑页说明、模拟仍触发并提示到「监管路由规则」页处理）、
未到生效时间 / 已过失效时间的标记、删除（确认文案、行与汇总卡更新），以及 `/c2.html`（`?rule=` 参数不再有专门处理、「▶ 执行 C2 校验」结果与接口一致、窄屏时表单在结果上方）；列表表格在 1500 / 1440 /
1366 / 1280 / 1100 宽度下均不横向溢出；无前端 JavaScript 异常。验证未读写 8080 / 8081 实例的数据库。

2026-11-25（C2 条件结构）：C2 模拟页（`/c2.html`）顶部新增「C2 校验顺序与配置来源」面板，按规则编辑器设计的 C2 条件结构展示根条件组（8 项、全部满足 AND）、每个规则组的叶子条件（「字段命中屏蔽 = false」）、
不满足时返回的原因码、配置来源和最终判定；模拟结果新增「校验过程」，按同样的 8 个规则组逐组给出 PASS / FAIL 和每个叶子的实际 true / false。逐组结果由后端返回（`POST /api/c2/simulate` 响应新增
`groups`），页面不自行推断；请求增加可选的 `directorNationalities` / `uboNationalities`，董事、UBO 国籍分别判断，不传时沿用合并的 `directorUboNationalities`，全流程 Check
与原有调用方结果不变；`hits`、`reasonCode` 口径不变，响应不含国籍取值。全套227项测试通过（本次新增6项）：`C2ServiceTest` 5项（规则组的顺序、叶子字段和原因码；合并国籍名单的回退；董事 / UBO
分别判断；同一规则组的两个叶子各自命中；监管能力缺失使第 8 项不满足），`C2RegulatoryControllerTest` 1项（新入参与 `groups` 的 HTTP 契约、响应不含国籍取值）。在独立端口与临时数据库上以无头 Chrome
完成 51 项检查：面板内容与设计结构逐项一致（8 个规则组的顺序、逻辑、叶子条件、AND 连接、原因码、配置来源链接）、收起 / 展开、「合作方限制 ↓」筛选并滚动到限制查询、三种模拟的「校验过程」（监管能力缺失；
第 1、2 项同时不满足且仅 UBO 国籍命中；全部通过），前两种的逐组结果与接口返回一致，请求体分开提交董事 / UBO 国籍，只传合并名单时两个国籍叶子均命中，1500 / 1440 / 1366 / 1280 /
1100 / 390 宽度下不横向溢出、1100 及以上宽度卡片内不折行；无前端 JavaScript 异常。README「合作方限制」一条的维护入口同步更正为合作方详情页。验证未读写 8080 / 8081 实例的数据库。

### C2 预置数据验证场景

| 场景        | 合作方及交易                   | 预期结果                                    |
|-----------|--------------------------|-----------------------------------------|
| 监管能力缺失    | `PROV-HO` + CNY + 受益行 CN | `REG_RULE_NOT_MET`（缺少 CNY_ONSHORE）      |
| 监管能力满足    | `PROV-T1` + CNY + 受益行 CN | C2 PASS（预置了 CNY_ONSHORE）                |
| Wise 国家限制 | `PROV-W` + 受益行 KG        | `RESTRICTED_COUNTRY`                    |
| Wise 国籍限制 | `PROV-W` + 董事/UBO 国籍 RU  | `RESTRICTED_NATIONALITY`（若没有更高优先级的其他命中） |
| 中间行国家限制   | `PROV-W` + 中间行 RU        | `RESTRICTED_COUNTRY`                    |

**重要：** C2 每条限制都要判断并收集命中项。默认按需求中的 1→8 顺序确定 `reasonCode`（主原因），所有命中保存在 `hits`。只做 C2
模拟，**C2 PASS 不代表其他过滤器通过**。生产客户董事/UBO 国籍应从权威客户档案获取，不能相信任意外部填报值；本 Demo
使用表单输入仅用于测试。

### 重要：C1 规则约束

- 客户分类状态不在 Java 中写死；它与交易字段、函数结果一起代入 C1 页面保存的全局准入表达式。表达式不满足时过滤，满足时继续后续 C1 流程。
- 流转类别多选：所有流转类别必须分别通过；无类别按 STANDARD。
- 按币种+国家精确性选最高优先级：精确+精确 > 精确+ALL > ALL+精确 > ALL+ALL。
- 无匹配默认拒绝，`EXCLUDE` 禁止；CASE_BY_CASE 只检查有效审批；`ACCEPT` 有上限则查实时 MTD。
- C1 内部遇第一失败即返回；完整 RELAY 系统应继续检查 C2/C3/C4/C6/C10，以记录候选全部失败原因。
- 页面上的字段库 / 函数库条件是独立的 C1 全局过滤判断，不绑定某条准入规则，也不受右侧模拟客户切换影响；规则本身仍按精确度匹配和默认拒绝执行。
- 系统必检（客户排除、准入匹配、个案审批、MTD）由执行器在代码里强制落实，条件树里有没有对应的函数节点都执行；函数条件只是把函数返回值作为一个可选条件参与比较，见下文「C1 全局条件接入函数库」。

**全局过滤条件的取值与不命中处理：**

- 全局条件是 C1 的候选准入门槛：对客户的每个流转类别求值，任一类别不满足即返回 `C1_CONDITION_NOT_MATCHED`；全部满足才进入合作方客户排除和准入规则匹配。
- `flowCategory`、`paymentCurrency`、`beneficiaryBankCountry`、`amountUsd` 四个交易要素固定取自模拟表单（客户档案的流转类别、交易币种、受益行国家、本笔金额 USD），不能用 `variables` 覆盖；其余 C1 字段的取值来自模拟请求的 `variables`（字段编码 → 值）。
- C1 模拟面板为字段库里其余已启用的 C1 字段自动生成取值控件：有可选值 → 下拉（首项「（不提供）」，默认选中第一个可选值）；数字 → 数字输入框；布尔 → true / false 下拉；其它 → 文本框。选「（不提供）」或留空的字段不提交。
- 全局条件引用字段本次没有取值时按**未命中**处理，并在「C1 过滤条件 <流转类别>」步骤说明；条件保存后字段被停用、不再适用 C1 或值不再合法时，执行会明确报出全局条件已失效，要求重新保存，避免静默改变过滤语义。
- 已保存的条件引用了当前不可用的字段时，条件编辑器会把该条件换成第一个可用字段并在表达式区提示；条件值已不在可选值中时，下拉原样列出该值并标注「已不在可选值中」，不会悄悄改成其它值。两种情况都需核对后重新保存。
- C1–C10 Check（`/routing.html`）也调用同一个 C1 执行器，因此会读取同一棵全局过滤条件；未提供的扩展字段按未命中处理并写入步骤说明。

### C1 全局条件接入函数库（字段 / 函数 / 条件组）

C1 全局过滤条件树支持三类节点：函数库的业务函数既可以作为条件节点直接比较返回值，也可以绑定为字段的取值来源。旧版 `relay_appetite.condition_json` 仍兼容执行，但首页新配置统一保存到全局条件。C1 的后续系统检查保持排除优先于准入、
流转类别无类别按 STANDARD、四级精确度匹配、`APPETITE_EXCLUDED` / `APPETITE_APPROVAL_MISSING` / `APPETITE_CAP_REACHED`、所有流转类别都须通过）。

**条件树节点：**

- 条件组 `{type:"GROUP",logic:"AND"|"OR",children:[…]}`：最多 6 层、每组 20 项。
- 字段条件 `{type:"FIELD",field,operator,value}`：与原有叶子相同；绑定了函数的字段取值来自函数（见下文「字段 ↔ 函数绑定」）。
- 函数条件 `{type:"FUNCTION",functionCode,arguments,operator,value}`：函数返回值与期望值比较，`arguments` 是参数编码到取值说明的对象。
- 旧数据兼容：升级前保存的 C1 / C2 / C3 条件 JSON 没有 `type`，有 `children` 的读作条件组，否则读作字段条件，照常执行、照常重新保存，不需要迁移；节点序列化只输出节点自身的属性。

函数条件示例（`clientExcluded` 的返回值为 false 时条件成立）：

```json
{"type":"FUNCTION","functionCode":"clientExcluded","arguments":{"providerCode":"$candidate.providerCode","clientId":"$transaction.clientId","decisionTime":"$context.decisionTime"},"operator":"EQ","value":false}
```

**哪些函数可以添加：**

- 函数须可用于 C1：在函数库里已启用、适用规则含 C1、后端实现（Java Handler）已在代码白名单注册、返回类型与实现一致。函数库元数据只登记编码、名称等描述信息，能执行的是实现了 `BusinessFunction` 接口并注册为 Spring Bean
  的类（元数据的 `handlerCode` 与之绑定）；元数据不会生成代码，没有注册 Handler 的函数不能进入条件。
- 目前只有 C1 执行器登记了受控取值路径，所以函数条件和函数绑定字段只能用于 C1，用于其它规则返回 400（「目前只有 C1 的条件编辑器支持添加函数，不能用于 C2」）。
- 演示数据的 13 个函数里，C1 当前可添加的是 `clientExcluded`、`hasValidCaseApproval`、`getProviderMtd` 三个；`findBestAppetiteRule` 由执行器内部落实，没有独立的 Handler，不能作为条件节点。

**使用策略（系统必检 / 可选）：**

- 使用策略由规则执行器在代码里声明（`C1Service` 实现 `RequiredFunctionProvider`），函数库元数据和页面配置都改不了它，也不落库。被声明的是系统必检（`REQUIRED`），其余是可选（`OPTIONAL`）。
- C1 的系统必检清单：`clientExcluded`（命中有效排除记录即拒绝 `CLIENT_EXCLUDED`，先于准入规则匹配）、`findBestAppetiteRule`（每个流转类别按四级匹配，无有效匹配默认拒绝 `APPETITE_NOT_SET`）、
  `hasValidCaseApproval`（命中 CASE_BY_CASE 规则时须有有效审批 `APPETITE_APPROVAL_MISSING`）、`getProviderMtd`（命中设有月度上限的 ACCEPT 规则时校验 MTD + 本笔金额 `APPETITE_CAP_REACHED`）。
- 执行器的系统必检直接调用 Java Handler，不经过函数库元数据，也不看条件树里有没有对应节点：在函数库里停用、删除、改绑定都绕不过。
- 系统必检函数可以加入条件树（节点带「系统必检」标记，此时只是把它的返回值作为一个可选条件参与比较），也可以从条件树删除，删除前页面提示执行器每次判定仍会执行这项检查。页面右侧的「系统必检清单」只读展示这四项，没有删除按钮。
- 函数库里系统必检函数不能删除（「C1 正在使用该系统必检函数，请停用而非删除」），可以停用；可选函数被字段绑定或已保存的 C1 条件引用时同样不能删除。可选函数只有在条件树里时才执行。

**参数解析（只允许受控取值路径或字面量）：**

- 参数声明以 Java Handler 为准（参数编码、类型 STRING / INTEGER / DECIMAL / BOOLEAN / DATE、来源 CANDIDATE / TRANSACTION / CONTEXT、是否必填），选择器和保存校验都按它读取，函数库里登记的参数不参与校验。
- 每个参数的取值说明只有两种：受控路径（`"$candidate.providerCode"`）或字面量（按参数类型校验）。路径是代码级白名单 `RuleExecutionContext.C1_PATHS`，共 9 条：`candidate.providerCode`、`candidate.flowCategory`、
  `transaction.clientId`、`transaction.paymentCurrency`、`transaction.beneficiaryBankCountry`、`transaction.amountUsd`、`context.decisionTime`、`context.monthKey`、`context.ruleCode`，取值全部来自本次 C1 判定请求，
  条件配置覆盖不了。不做表达式求值，也不能借参数调用任何 Java 方法、SQL 或命令。
- 路径的类型和来源须与参数声明一致（例如 `providerCode` 只能绑定 STRING / CANDIDATE 的路径）；以 `$` 开头但没有登记的路径直接报错，不会当成字面量。校验顺序：未知参数 → 缺少必填参数 → 路径 / 字面量的类型与来源。
- 默认绑定：Handler 可为可选参数声明默认路径（`decisionTime` → `context.decisionTime`、`monthKey` → `context.monthKey`），没有配置时在执行前补上（系统必检同样适用）；选择器另外按「同来源、同类型、且末段与参数同名」的路径预填
  （`providerCode` → `$candidate.providerCode`、`clientId` → `$transaction.clientId`）。落库的 `arguments` / `argumentBindings` 只保存运营配置的绑定。

**调用、复用与失败处理：**

- 保存 / 编译条件时（`PUT /api/appetites/{id}/condition`、`POST /api/appetites/compile`、准入规则新增 / 编辑）校验函数条件：函数存在、已启用、适用 C1、Handler 已注册、返回类型一致、参数绑定合法、运算符在返回类型允许的范围内
  （BOOLEAN / STRING：EQ、NE；DATE / INTEGER / DECIMAL：EQ、NE、GT、GTE、LT、LTE）；任何一项不通过返回 400，条件不能保存。
- 函数条件编译成「函数返回值变量与期望值比较」的受控表达式（`(__fn0 == false)`），页面展示可读形式 `(clientExcluded(providerCode=$candidate.providerCode, clientId=$transaction.clientId, decisionTime=$context.decisionTime) == false)`。
  C1 判定时先解析入参、执行函数，返回值代入后由同一条 AviatorScript 表达式完成比较；期望值按函数返回类型做与字段条件相同的转换（字符串 `"false"` 也可以）。
- 请求级复用：一次 C1 判定内，相同 Handler + 相同有效入参只真正查询一次（不跨交易、客户、合作方串用）；复用的调用在 `functionCalls` 里 `cached=true`。只有只读函数复用，失败的结果不缓存。
- 失败不放行：函数抛异常、没有返回结果、返回类型与声明不符，都按执行失败报错，不会当成 false 或「未命中」。条件树内的函数执行失败时整个模拟请求返回 400（`C1 规则 #n 条件执行失败: …`）。
- 规则保存后函数在函数库里被停用、不再适用 C1 或 Handler 不再可用：引用它的规则在第 3 步按不命中处理，并在校验过程中给出 `SKIP` 说明（「附加条件已失效」），全部不命中时为 `APPETITE_NOT_SET`，与字段失效的处理一致；
  系统必检照常执行。条件编辑器把这样的函数条件标为「不可用」并在表达式区提示，不会悄悄换成别的函数，需删除后按需重新添加。

**`clientExcluded` 的完整链路：**

1. 参数：`providerCode`（必填，STRING / CANDIDATE）、`clientId`（必填，STRING / TRANSACTION）、`decisionTime`（可选，DATE / CONTEXT，未配置时取 `context.decisionTime`）。
2. 取值：`$candidate.providerCode` 是本次判定的合作方，`$transaction.clientId` 是本次判定的客户，`$context.decisionTime` 是判定时间（香港时间）；没有任何写死的合作方或客户。
3. 查询 `relay_client_exclusion`（合作方详情「客户排除管理」维护的记录）：同一合作方 + 同一客户，且 `effectiveFrom <= 决策时间 < effectiveTo`（`effectiveTo` 为空表示长期有效）；未到生效时间、已过失效时间、已解除的记录
   都不算有效，命中多条取 ID 最小的一条。
4. 返回 BOOLEAN：命中为 `true`，`recordId` 为排除记录 ID；未命中为 `false`；数据来源 `CLIENT_EXCLUSION`。
5. C1 执行顺序：`1. 客户分类` → `2. 客户排除`（系统必检，直接调用 Handler，不看条件树）→ `3. 规则匹配 <流转类别>`（在这里求值条件树）→ `4. 个案审批 / MTD <流转类别>` → `5. C1 汇总`。
6. 第 2 步命中即返回 `CLIENT_EXCLUDED`，先于规则匹配。所以运营把 `clientExcluded` 从条件树删除后，C1 仍返回 `CLIENT_EXCLUDED`；未命中且条件树里还有 `clientExcluded` 节点时，第 3 步复用第 2 步的查询结果
   （`functionCalls` 两条，其中一条 `cached=true`），一次判定只查询一次。
7. 查询失败（数据库异常）时第 2 步 FAIL，返回 `CLIENT_EXCLUSION_CHECK_FAILED`，不会按「未排除」放行；函数库里未配置、停用或删除 `clientExcluded` 都不影响第 2 步。模拟请求 `variables` 里的 `clientProviderExcluded`
   不生效（取系统必检的结果）。

**试算与调用记录：**

- 「ƒ 添加函数」选择器的每张函数卡片可以直接试算：`POST /api/function-metadata/{code}/simulate`，提交 `{arguments,operator,value,sample}`，`sample` 取右侧模拟面板的要素（`clientId`、`providerCode`、`paymentCurrency`、
  `beneficiaryBankCountry`、`amountUsd`、`flowCategory`），返回 `{functionCode,functionName,expression,arguments,bindings,value,recordId,dataSource,durationMs,matched,error}`。参数、运算符或期望值不合法返回 400；函数执行失败
  返回 200，`matched` 为 null、`error` 为原因（不会当成 false）。结果框逐项显示条件结果、表达式、每个参数的实际入参与所用绑定、返回值、命中记录、数据来源、耗时和异常。
- `POST /api/c1/simulate` 的响应新增 `functionCalls`：每次调用一条 `{functionCode,handlerCode,arguments,value,recordId,dataSource,durationMs,cached,error}`，系统必检、条件节点、函数绑定字段都在其中，页面「C1 规则模拟校验」的
  「函数调用」逐条展示。
- `POST /api/expression/preview`（元数据中心的表达式试算）没有规则执行上下文，条件含函数条件或函数绑定字段时返回 400；页面在运行前拦截并提示。

**字段 ↔ 函数绑定：**

- 字段新增「取值来源」：`REQUEST` 请求输入（默认，取值来自模拟请求的 `variables`）或 `FUNCTION` 函数计算（选择函数库里的函数，并为每个参数指定受控路径）。保存为 `relay_rule_field` 的 `value_source`、`function_code`、`argument_bindings`。
- 校验：绑定函数的字段只能适用于 C1；函数须可用于 C1；返回类型与字段类型一致（枚举字段对应 STRING）；参数绑定合法。
- 执行：函数计算字段的取值来自函数，请求 `variables` 里的同名字段取值不采用；保存 / 编译条件时同样校验。调用方不需要、也不会在 `variables` 里为它提供取值；字段下拉里带「ƒ 函数名」标注。
- 启动自动绑定：升级前 `clientProviderExcluded`（客户是否被合作方排除）由执行器直接赋值。应用启动时（`ApplicationReadyEvent`）若该字段存在、取值来源从未配置过、适用规则仅为 C1、类型为 BOOLEAN、且 `clientExcluded` 可用于 C1，
  就自动绑定（`providerCode` → `$candidate.providerCode`、`clientId` → `$transaction.clientId`），并写审计 `FIELD_METADATA / BIND_FUNCTION`（操作人 `system`）；运营保存过的取值配置不会被覆盖。演示种子没有这个字段，全新库不会触发。
- 接口兼容：`POST` / `PUT /api/field-metadata` 增加 `valueSource`、`functionCode`、`argumentBindings`（JSON 对象）三个入参；三项都不传表示请求没有表达取值来源，新增字段留空（按请求输入），修改时保留已有配置并按本次提交的类型 / 适用规则复核。

**页面：**

- C1 页「① 触发条件：字段库 + 函数库」：条件组的操作按钮为「添加条件」「添加子条件组」「ƒ 添加函数」；选择器为每个可添加的函数显示一张卡片（名称、编码、系统必检 / 可选、返回类型、数据来源、参数与绑定、比较方式、期望值、试算），
  沿用蓝白配色；右侧「系统必检清单」只读，没有删除按钮。函数条件在条件树里显示为带虚线框的一行：`ƒ 函数名`、比较方式和期望值。
- 元数据中心（字段 ↔ 函数绑定、函数库的使用策略）见「元数据中心页面」一章。

2026-11-25（C1 条件接入函数库）：全套 258 项测试通过（本次新增 28 项）：`C1FunctionTest` 13 项、`C1FunctionFailureTest` 2 项、`FunctionMetadataApiTest` 8 项、`ConditionJsonCompatibilityTest` 4 项、`SchemaRestartTest` 1 项（明细见「测试用例」）。
在独立端口与临时数据库上以无头 Chrome 完成 48 项检查：函数库接口与内置字段的启动自动绑定（旧字段 → 重启 → 已绑定）、C1 页面的三个操作按钮与只读的系统必检清单、选择器卡片内容、选择器里的函数试算、把 `clientExcluded`
加入条件树并保存、无排除记录时的模拟校验（一次执行 + 一次复用）、加入有效排除记录后的 `CLIENT_EXCLUDED`、从条件树删除 `clientExcluded` 节点后保存仍返回 `CLIENT_EXCLUDED`（验收场景）、元数据中心的字段绑定 / 函数库使用策略 /
表达式页拦截，以及页面没有脚本异常或控制台错误。未读写 8080 / 8081 实例的数据库。

## API

- `GET /api/providers`
- `GET /api/appetites?providerCode=PROV-T1`
- `POST /api/appetites`
- `POST /api/appetites/{id}/toggle`
- `GET /api/clients`, `/api/exclusions`, `/api/approvals`, `/api/usage`, `/api/audit`, `/api/fields`
- `POST /api/exclusions`, `POST /api/approvals`
- `PUT /api/usage`：演示用，模拟统计任务回填 MTD。提交 `{providerCode,flowCategory,monthKey,amountUsd}`，按三者组合覆盖写入（不存在则新增），金额必填、保留两位小数并写审计
- `DELETE /api/usage/{id}`：删除一条 MTD 记录
- `PUT /api/appetites/{id}/condition`：保存准入规则的附加条件，提交 `{enabled,tree}`；`enabled=false` 清除条件。保存前按元数据中心校验：字段存在、已启用、适用规则含 C1，运算符在允许运算符内，有可选值时条件值须在其中，最多 6 层、每组 20 项；不通过返回 400。`tree` 结构：条件组 `{logic:"AND"|"OR",children:[…]}`，叶子 `{field,operator,value}`，也可以是函数条件 `{type:"FUNCTION",functionCode,arguments,operator,value}`（保存前按函数库和白名单校验，见「C1 条件接入函数库」）
- `POST /api/appetites/compile`：提交 `{tree}`，只校验并返回 `{expression}`，不保存
- `POST /api/c1/simulate`：提交 `{clientId,providerCode,paymentCurrency,beneficiaryBankCountry,amountUsd,variables?}`；`variables` 为附加条件字段的取值（可选），没有提供取值的字段按「条件不命中」处理；绑定了函数的字段不取 `variables` 里的值。响应新增 `functionCalls`（本次判定的函数调用记录）；命中有效排除记录返回 `CLIENT_EXCLUDED`，排除记录查询失败返回 `CLIENT_EXCLUSION_CHECK_FAILED`（不按「未排除」放行），条件树内的函数执行失败返回 400
- `POST /api/expression/preview`：元数据中心的表达式试算，提交 `{tree,variables}`，返回 `{matched,expression}`；条件含函数条件或函数绑定字段时返回 400（没有规则执行上下文，函数只在 C1 判定和函数试算中执行）
- `GET /api/function-metadata`：函数库全量（按函数编码排序），每项带 `usagePolicy`（`REQUIRED` 系统必检 / `OPTIONAL` 可选，只读自执行器代码，不落库）和 `implemented`（后端实现是否已注册）；`POST` 新增、`PUT /{code}` 修改、`POST /{code}/toggle` 启用 / 停用、`DELETE /{code}` 删除（系统必检函数，以及被字段绑定或已保存 C1 条件引用的函数不能删除，返回 400）；`GET /api/function-metadata/handlers` 返回可绑定的 Java Handler 编码
- `GET /api/function-metadata/available?ruleCode=C1`：「ƒ 添加函数」选择器的数据 `{ruleCode,paths,required,functions}`：`paths` 受控取值路径，`required` 系统必检清单，`functions` 当前可添加的函数（参数、默认绑定、可用运算符）；`ruleCode` 不是 C1 时返回 400
- `POST /api/function-metadata/{code}/simulate`：函数试算，提交 `{arguments,operator,value,sample}`，返回入参、绑定、返回值、命中记录 ID、数据来源、耗时、条件结果和异常；参数不合法返回 400
- `POST /api/function-metadata/clientExcluded/check`：提交 `{providerCode,clientId}`，按香港当前时间查询有效排除记录，返回 `{functionCode,value,dataSource}`；函数库未配置或已停用时返回 400

示例模拟请求：

```bash
curl -X POST http://localhost:8080/api/c1/simulate \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"C10001","providerCode":"PROV-HO","paymentCurrency":"USD","beneficiaryBankCountry":"KG","amountUsd":150000}'
```

将返回 `PASS`（示例数据预置了有效 PSP 个案审批）。改成 `PROV-T1` 返回 `APPETITE_EXCLUDED`。

准入规则配置了引用其它 C1 字段的附加条件时（例如元数据中心里的 `clientClassificationStatus`，其适用规则须勾选 C1），通过 `variables` 提供取值；不提供则该规则按不命中处理，并在校验过程中给出 `SKIP` 说明：

```bash
curl -X POST http://localhost:8080/api/c1/simulate \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"C10001","providerCode":"PROV-HO","paymentCurrency":"USD","beneficiaryBankCountry":"KG","amountUsd":150000,"variables":{"clientClassificationStatus":"COMPLETE"}}'
```

演示环境的 MTD 没有定时任务，可用 `PUT /api/usage` 直接写入来模拟（`monthKey` 须为香港当月才参与 C1 校验，下例以 2026-11 为例）：

```bash
curl -X PUT http://localhost:8080/api/usage \
  -H 'Content-Type: application/json' \
  -d '{"providerCode":"PROV-T1","flowCategory":"STANDARD","monthKey":"2026-11","amountUsd":9900000}'
```

之后模拟客户 `C10002` 向 `PROV-T1` 付 `150000`（STANDARD，上限 10,000,000）即返回 `APPETITE_CAP_REACHED`；改回 `1800000` 则通过。页面入口见「MTD
模拟数据页面」一章。

## C2 REST API

- `GET /api/c2/fields`：返回 C2 字段库（DB 驱动）
- `GET /api/c2/capability-tags`：能力标签字典
- `GET /api/c2/regulatory-rules`：监管规则列表（含 `currency`、`bankCountry`、`legacy`、`conditionText`，以及附加触发条件 `triggerCondition`（条件树）、`triggerText`（摘要）、`triggerProblem`（条件按当前字段库不能执行的原因，可执行时为空）；未配置附加条件时三者均为空）
- `GET /api/c2/regulatory-rules/{id}`：单条监管规则（新增 / 编辑页载入），形态同列表项；规则不存在返回 400 + `error`（规则不存在）
- `GET /api/c2/regulatory-rules/options`：表单下拉项——`currencies`（字段库「交易币种」可选值）、`countries`（ALL + ISO 3166-1，带中文名）、`tags`
- `POST /api/c2/regulatory-rules`：新增或更新监管规则（按 ruleId），提交 `currency` + `bankCountry`，可带 `versionNo`（与当前版本不一致返回 400）和 `trigger`（附加触发条件，形态见下文）；不带 `trigger` 时不改动已保存的附加触发条件
- `DELETE /api/c2/regulatory-rules/{id}?versionNo=`：删除监管规则
- `POST /api/c2/regulatory-rules/{id}/toggle`：启用/停用
- `POST /api/c2/regulatory-rules/compile`：按字段库校验附加触发条件并返回 `{expression}`，不保存；校验失败返回 400 + `error`
- `PUT /api/c2/regulatory-rules/{id}/condition`：单独保存 / 清除监管规则的附加触发条件（接口保留；页面已改为随规则一并保存），提交 `{enabled, tree, versionNo?}`；`enabled` 缺省或为 `true` 保存 `tree`，`enabled=false` 清除。`versionNo`
  可选，带上时须与规则当前版本一致；返回更新后的规则（`versionNo` +1），规则不存在、条件不合法或版本不一致返回 400
- `GET /api/c2/restrictions`、`POST /api/c2/restrictions`、`POST /api/c2/restrictions/{id}/toggle`：合作方限制管理
- `GET /api/c2/providers/{code}/capabilities`、`PUT /api/c2/providers/{code}/capabilities`：合作方标签
- `POST /api/c2/simulate`：C2 模拟；请求可带 `variables`（附加触发条件字段的取值，字段编码 → 值），以及可选的 `directorNationalities` / `uboNationalities`（董事、UBO
  国籍各自的名单，用于分别判断第 2 项「董事 / UBO 国籍未被限制」的两个叶子；不传或为 `null` 时沿用合并的 `directorUboNationalities`，原有调用方结果不变）。响应在原有字段之外增加 `groups`：按规则组返回的校验过程，8
  项、顺序同「C2 校验顺序与配置来源」，每项 `{order, code, passed, reasonCode, leaves}`，`leaves` 每项 `{field, hit, ruleIds}`——`hit` 为该字段的取值命中本合作方当前有效的同类限制（第 8
  项为监管规则触发且缺少能力标签），`ruleIds` 为命中的限制记录（`C2-RESTRICTION-<id>`）或监管规则。任一项 `passed=false` 即整体 `passed=false`；`hits`、`reasonCode`
  的口径不变（同一条限制记录在组内命中多个字段时只记一条、取首个命中字段，国籍命中仍记为合并字段 `directorUboNationalities`）。响应不含任何字段取值，国籍取值尤其如此。

**监管规则创建请求**（范围只提交币种和受益行国家，后端存为标准条件树）：

```json
{
  "ruleId": "REG-001",
  "ruleName": "CNY 境内清算能力要求",
  "ruleCode": "C2",
  "scope": "GLOBAL",
  "currency": "CNY",
  "bankCountry": "CN",
  "requirement": {
    "type": "PROVIDER_CAPABILITY",
    "requiredTags": [
      "CNY_ONSHORE"
    ]
  },
  "onFailure": {
    "reasonCode": "REG_RULE_NOT_MET"
  },
  "enabled": true,
  "effectiveFrom": "2025-10-01T00:00:00",
  "effectiveTo": null
}
```

新增 / 编辑页提交的请求另带 `trigger`（不带则保持已保存的附加触发条件不变）：`{"enabled": true, "tree": {…}}` 保存条件树（`tree` 形态见下文「附加触发条件」，保存前按字段库校验，不通过返回 400、整条规则不保存）；
`{"enabled": false}` 清除。规则与附加触发条件一起保存时版本号只加 1。

**模拟示例：**

```bash
curl -s -X POST http://localhost:8080/api/c2/simulate \
 -H 'Content-Type: application/json' \
 -d '{"providerCode":"PROV-HO","paymentCurrency":"CNY","debitCurrency":"CNY","beneficiaryBankCountry":"CN","beneficiaryAddressCountry":"CN","orderingCountry":"HK","directorUboNationalities":[]}'
```

预期 `passed=false`、`reasonCode=REG_RULE_NOT_MET`。

响应另带按规则组的校验过程 `groups`（8 项，与页面「校验过程」一致）。上面这笔的第 1 项（通过）和第 8 项（不满足）分别是：

```json
{"order": 1, "code": "BENEFICIARY_COUNTRY", "passed": true, "reasonCode": "RESTRICTED_COUNTRY",
 "leaves": [{"field": "beneficiaryBankCountry", "hit": false, "ruleIds": []},
            {"field": "beneficiaryAddressCountry", "hit": false, "ruleIds": []}]}
{"order": 8, "code": "REGULATORY", "passed": false, "reasonCode": "REG_RULE_NOT_MET",
 "leaves": [{"field": "providerCapabilities", "hit": true, "ruleIds": ["REG-001"]}]}
```

`groups` 每项的 `passed` 为组内全部叶子均为 `false`，`reasonCode` 为不满足时返回的原因码。

**附加触发条件**（形态与 C1 附加条件相同：条件组 `{logic, children}`，叶子 `{field, operator, value}`；字段须已启用且适用规则含 C2，运算符和条件值按字段库校验）：

```json
{
  "enabled": true,
  "versionNo": 2,
  "tree": {
    "logic": "AND",
    "children": [
      {
        "field": "purpose",
        "operator": "EQ",
        "value": "INVESTMENT"
      },
      {
        "logic": "OR",
        "children": [
          {
            "field": "highRisk",
            "operator": "EQ",
            "value": "true"
          },
          {
            "field": "riskScore",
            "operator": "GTE",
            "value": "1000000"
          }
        ]
      }
    ]
  }
}
```

`PUT /api/c2/regulatory-rules/REG-001/condition` 提交上述内容后（在监管路由规则新增 / 编辑页保存时，同一棵 `tree` 作为保存请求的 `trigger` 字段随规则一并提交，页面用的就是这种方式），规则只在「币种 + 受益行国家」命中、且用途为 `INVESTMENT`、同时 `highRisk` 为 `true` 或 `riskScore ≥ 1000000`
时触发；返回的 `triggerText` 为 `(purpose = INVESTMENT 且 (highRisk = true 或 riskScore ≥ 1000000))`（`highRisk`、`riskScore` 需先在元数据中心建为适用 C2 的字段）。模拟时通过
`variables` 提供取值：

```bash
curl -s -X POST http://localhost:8080/api/c2/simulate \
  -H 'Content-Type: application/json' \
  -d '{"providerCode":"PROV-HO","paymentCurrency":"CNY","beneficiaryBankCountry":"CN","purpose":"INVESTMENT","variables":{"highRisk":"true","riskScore":"1200000"}}'
```

`regulatoryChecks[]` 每条规则返回 `triggered`、`missingTags`、`condition`（范围），以及配置了附加条件时的 `triggerCondition`（摘要）、`triggerExpression`（编译后的表达式，缺值 / 失效时为空）和
`note`（缺值按未触发、失效按触发的说明）。

## 表结构

Java Entity 对应原有 8 张：`relay_provider`、`relay_appetite`、`relay_client_profile`、`relay_client_exclusion`、
`relay_case_approval`、`relay_provider_usage`、`relay_rule_field`、`relay_rule_audit`；本次新增 4 张：
`relay_regulatory_rule`、`relay_provider_restriction`、`relay_capability_tag`、`relay_provider_capability`。

`relay_regulatory_rule` 除范围（`condition_json`）和能力标签（`required_tags_json`）外，另有可空列 `trigger_condition_json`：监管规则的附加触发条件树，形态同
`relay_appetite.condition_json`；为空表示交易落在范围内即触发。

`sql/schema-mysql.sql` 提供独立 MySQL DDL，默认不需要手工执行。

`relay_rule_field` 另有三个可空列（2026-11-25 新增，旧数据保持为空）：`value_source`（`REQUEST` 请求输入 / `FUNCTION` 函数计算，为空视为 `REQUEST`）、`function_code`（取值来源为 `FUNCTION` 时绑定的函数编码）、
`argument_bindings`（参数绑定 JSON 对象：参数编码 → 受控路径或字面量）。已按旧版 `schema-mysql.sql` 建表且未开启 `spring.jpa.hibernate.ddl-auto=update` 的数据库执行一次 `sql/migrate-field-function-binding.sql`
（只有 `ALTER TABLE ... ADD COLUMN`，不改已有数据，已保存的 C1 / C2 / C3 条件 JSON 不需要改动）；开启了 `ddl-auto=update` 的库启动时自动补列，不要重复执行。函数库表 `relay_business_function` 由 Hibernate 按实体建表，
`schema-mysql.sql` 里尚未收录。

## 生产化前需补充

该项目为**参考 Demo，不是完整 C1–C10 支付引擎，更不是可直接投产的银行路由服务**。

1. 接入真正 HSG 客户档案及分类、客户审批、合作方角色权限；本项目客户档案及审批均为本地模拟。
2. 接入 HSG 路由 MTD 账本：按 paymentId 幂等占用，竞态时原子检查，取消释放，RERUN 排除本笔；演示环境 MTD 为预置数据，仅提供 `PUT /api/usage`
   用于模拟，不做占用/释放。
3. 规则配置变更应采用 append-only 版本、双时间轴、发布快照和完整审计；Demo 中编辑是覆盖式更新并留审计，不适合正式审计保留要求。
4. 加强安全：身份认证、访问控制、CSRF、表达式安全隔离与资源限制、请求限流；当前没有做这些，只供本机测试。**切勿将当前不带认证的管理
   API 暴露到公网。**
5. 将新增 C1–C10 试算接入真实参考数据、统一发布快照和 Ops Check 工作流。
6. 规则维度目前限制 `STANDARD/PSP/WEB3` 等固定示例枚举，正式项目应改为受控字典；真实业务币种/国家/时区来源应采用正式主数据。

## 测试用例

`src/test/java/com/hsg/relay/c2/C2ServiceTest.java` 包含 CNY/CN 能力校验、Wise 国家与国籍屏蔽、嵌套 AND/OR、非法字段拒绝等测试；`C2RegulatoryRuleTest`、`C2RegulatoryControllerTest`、`RegulatoryScopeTest`
覆盖监管规则按「币种 + 受益行国家」的保存、校验、求值、旧版条件树兼容及 HTTP 契约。附加触发条件由 `C2RegulatoryRuleTest`（附加条件收窄触发、缺值 / 失效的处理、布尔和数字比较、固定要素不能被
`variables` 覆盖、非法条件拒绝、不带 `trigger` 的范围重新保存与启停保留条件、清除与审计、规则与附加条件随同一个请求保存）、`C2RegulatoryControllerTest`（compile / condition / simulate 的 HTTP
契约，以及新增 / 编辑页的保存与单条载入）、`RegulatoryTriggerTest`（条件树解析与摘要）和
`SchemaRestartTest`（旧库自动补 `trigger_condition_json` 列且数据不丢）覆盖。模拟响应里的 C2 条件结构（`groups`）另由 `C2ServiceTest` 的 5 项（8 个规则组的顺序、叶子字段和原因码；只传合并国籍名单时两个国籍叶子结果一致；
董事、UBO 国籍分别判断且响应不含国籍取值；同一规则组的两个叶子各命中一条限制记录；监管能力缺失使第 8 项不满足）和 `C2RegulatoryControllerTest` 的 1 项（`simulate` 的新入参与 `groups` 的 HTTP 契约）覆盖。

函数库接入 C1 条件由 `C1FunctionTest`（13 项：无有效排除记录时 `clientExcluded` 为 false；从条件树删除节点后有效排除记录仍被拒绝；未到生效时间 / 已过失效时间 / 已解除的记录不算有效；可选函数只有在条件树里才执行；改函数库配置绕不过系统必检；
函数条件在 AND / OR 子条件组内可用；缺少或类型错误的入参被拒绝；DECIMAL 返回值按数字比较；旧版字段 / 条件组 JSON 照常执行；个案审批与 MTD 检查不受影响并记入调用记录；函数计算字段的取值来自函数；模拟结果报告入参、返回值和命中记录；
内置字段只自动绑定一次且不覆盖运营的明确选择）、`C1FunctionFailureTest`（2 项：排除记录查询失败不按「未排除」处理；同一函数同一入参一次判定只查询一次）、`FunctionMetadataApiTest`（8 项：选择器数据；仅 C1 可用；试算结果；
试算参数不合法返回 400；系统必检函数只能停用不能删除；被引用的可选函数不能删除；列表带使用策略和实现状态；字段绑定经接口保存且旧版请求体保留已有绑定）、`ConditionJsonCompatibilityTest`（4 项：旧版不带 `type` 的 JSON 照常读取；
函数节点往返；序列化只含节点属性；字段请求体兼容旧版与绑定两种形态）和 `SchemaRestartTest` 的 1 项（旧库自动补 `relay_rule_field` 的三个函数绑定列且数据不丢）覆盖。执行：

```bash
mvn clean test
```

## C1–C10 全流程 Check（路由试算）

访问 `/routing.html`，侧边栏菜单为「▶ C1–C10 Check」（原名「C1–C10 全流程路由」，已更名，页面同时重构为与各配置页一致的侧边栏布局）。C1/C2
等页的工具栏也有入口。提供候选生成、全部过滤器、四项评分、主路由与两条备选、REFER 原因优先级、下一个外汇窗口和持久化决策查询。C1/C2
的规则各自存在独立数据库表并由对应页面维护，Check 时实时读取；页面内提供「C1 / C2 规则查看（只读）」面板：C1
准入规则表（含生效比例、客户排除/个案审批计数）与 C2 监管规则表（币种、受益行国家、必需能力标签；旧版规则显示原条件摘要，带附加触发条件的规则在规则名下方显示条件摘要），并附各自维护页入口。Check
的 C2 校验只传九个固定交易要素、不传附加条件字段的取值，相关规则的处理见「C2 功能」下的「附加触发条件的取值与处理」；「参考配置」JSON
仅覆盖 C3–C10 共享的 `relay_routing_document` CONFIG（评分权重等尚无独立页面的项；C4 余额已可由 `/c4.html` 页面维护）。

- C3：匹配币种、国家、地区、BIC8、扣费方式；优先指定地区、国家及银行列表。
- C4：合作方资金校验开关 OFF 直接通过；按外汇渠道选择头寸账户（NONE/TREASURY 取交易币种，PROVIDER
  取扣账账户币种），可用头寸＝账户余额−未记账占用，不足则剔除并返回 FUNDING_SHORTFALL；计算器与 `/c4.html` 页面共用。
- C5：使用覆盖行时区、截单时间、周末及节假日计算工作日偏移和请求起息日。
- C6：同时记录单笔及当日限额失败。
- C7：固定及比例手续费、最低/最高费、电报费、中间行费和外汇点差折港币；用剩余候选最低成本计算得分。
- C8：全部命中偏好奖励相加，上限100。
- C9：金额/笔数月度目标按当月日期进度评分，计算器与 `/c9.html` 页面共用。
- C10：按货币对、工作日/节假日、窗口时间和单笔外汇上限判断。

所有过滤器都会执行；C1 内部仍按规格首个失败返回。只有通过过滤的候选计算成本、偏好、履约及综合排名。使用 Decimal128
计算，展示金额/总分保留两位小数；平分按成本、起息日、合作方优先级、代码排序。所有试算结果标记 `simulation=true`、
`opsCheckRequired=true`。

### 接口

- `GET /api/routing/config`：配置及修订号。
- `PUT /api/routing/config`：提交 `{revision, config}`，检查汇率、权重总和、限额、维度重复及窗口时间，保存数据库并写配置审计。
- `POST /api/routing/simulate`：执行全流程试算。
- `GET /api/routing/decisions/{decisionId}`：读取保存的原始试算请求和完整结果。

配置页面的 JSON 编辑器可维护 C3–C10 的参考数据；默认配置见 `src/main/resources/routing-demo.json`。覆盖行 BIC 列表为空表示
ALL，手续费 rate 为小数（0.001 即0.1%），hkdRates
是每单位币种折合港币。外汇窗口为香港时间，采用起点包含、终点不包含；跨午夜需拆成两条。节假日需自行维护；默认空列表仅用于演示。todayUsd/monthUsd/monthCount
是试算时点的累计快照，修改模拟日期时应同步调整。C1 月累计仍取现有 provider_usage 表，按模拟时间所在自然月查询。

示例请求（JSON 使用当前 Demo 的 camelCase 字段，尚未做 BRD snake_case 报文适配）：

```json
{
  "paymentId": "DEMO-001",
  "clientId": "C10002",
  "debitCurrency": "USD",
  "paymentCurrency": "USD",
  "amount": 10000,
  "beneficiaryBankCountry": "US",
  "beneficiaryAddressCountry": "US",
  "beneficiaryBankBic": "CHASUS33",
  "chargeOption": "SHA",
  "orderingCountry": "HK",
  "clientCountry": "HK",
  "directorUboNationalities": [],
  "paymentType": "TRADE",
  "clientSegment": "STANDARD",
  "requestedValueDate": "2026-11-20",
  "at": "2026-11-20T10:00:00+08:00"
}
```

默认场景推荐 Wise。扣账币种改为 HKD，时间改为 `2026-11-21T10:00:00+08:00`，返回 `FX_WINDOW_CLOSED`，下一窗口为 11月23日09:
00。扣账 HKD、交易 EUR 返回 `NO_FX_VENUE`。

### 当前范围与验证

这是可运行的完整 C1–C10 **路由计算演示**。真实 HSG 汇率/余额/用量接口、交易幂等资金占用与取消释放、NEW/RERUN
的占用排除、强制运营审核工作流及最终支付发送仍需接入。当前不提供真实交易提交接口。请求中的客户号会查询本地分类及流转类别，国籍仍为试算输入。

2026-11-20 已通过 Maven 测试24项（C1 4项、C2 4项、全流程16项），并验证实际 HTTP 路由、周末外汇关闭及无外汇渠道场景。本机 Chrome
页面实测通过：正常路由、周末 REFER、从 C1 导航到路由页；无前端 JavaScript 异常。

本次为避免与 IDEA 已运行的8080服务及其数据库冲突，新版演示启动在8081，使用 `./data/routing-demo` 独立数据库。常规
`mvn spring-boot:run` 仍使用8080和原数据库；停止旧服务后重新启动即可加载新版代码。

### H2 重启时重复创建索引的修复

连接 URL 保留 `DATABASE_TO_UPPER=false` 并追加 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`，使 Hibernate
的元数据识别与数据库标识符行为一致。不要删除数据库或修改已有索引。IDEA 若通过环境变量或启动参数覆盖了
`spring.datasource.url`，对应 URL 也需要追加该设置；重启后生效。

`SchemaRestartTest` 覆盖旧 URL 建库及存入记录、旧 URL 重启失败、新 URL 连续更新两次、原记录及唯一一份索引保留。修复后全套25项测试通过。

## C3 业务覆盖配置页面

访问 `/c3.html`；C1、C2 左侧的「C3 业务覆盖」已接通。页面提供合作方/币种/国家/状态筛选、新增、编辑、启用/停用和独立模拟。表单可维护国家、地区、银行
ALL/LIST、BIC8 列表及支持扣费方式；折叠区维护此行供 C5/C6 使用的时区、截单时间、工作日偏移、限额及节假日。

覆盖行与全流程参考配置共用 `relay_routing_document` 中的 CONFIG，不另建一份数据。保存会递增配置版本并写入配置审计；旧版本保存会被拒绝。旧配置中缺少
enabled 字段的行默认启用。银行范围 ALL 对应空 bics 数组，LIST 对应非空 BIC8 数组。停用的行不会参与 C3/C5/C6 选择。

`C3Matcher` 由独立模拟和全流程路由共用：币种精确匹配；国家和地区支持 ALL；无地区只匹配 ALL；交易 BIC
取前8位；扣费方式必须被该行支持。全部条件同时通过才命中。最精确行按指定地区、指定国家、LIST 的顺序比较；同精确度按覆盖表行顺序选择。C5/C6
使用同一选中行。

接口：

- `GET /api/c3/reaches`：返回配置 revision、覆盖行 `{index, reach}` 及可选币种。
- `POST /api/c3/reaches`：提交 `{revision, index, bankScope, reach}`；index 为空新增，有值编辑。index 为当前配置数组的0基索引，与
  revision 一起使用。
- `POST /api/c3/reaches/{index}/toggle`：提交 `{revision}` 启用或停用。
- `POST /api/c3/simulate`：提交
  `{providerCode,paymentCurrency,beneficiaryBankCountry,beneficiaryRegion,beneficiaryBankBic,chargeOption}`，返回
  PASS/NO_REACH、selectedIndex、selectedReach、每行五项匹配依据和配置版本。

2026-11-20：全套38项测试通过。C3 新增13项测试覆盖地区缺失、广东/非广东、BIC8/11、币种、国家、扣费方式、精确度排序、停用、配置编辑及版本冲突、C5/C6选中行一致性。Chrome
实测验证导航、模拟、编辑保存、BIC匹配、停用与错误校验；验证后恢复了原覆盖行。

## C4 头寸资金校验页面

访问 `/c4.html`；各页面左侧「C3 业务覆盖」下方新增「C4 头寸资金校验」菜单（首页、合作方管理、C2–C10、全流程 Check 及字段库页均已接入，顺序为
C3 → C4 → C5）。C4 为过滤器（BRD 6.4）：由合作方资金校验开关（9.1）、我行在合作方的往来账 nostro 余额、尚未记账的已路由交易占用金额，决定该合作方候选是否保留。

- 步骤 1 资金校验开关：OFF 直接通过。后补头寸的合作方（如 Wise、GD Pay）设为 OFF，资金由资金部在合作方出款前补足，逻辑在 RELAY
  模块之外；开关在合作方管理维护，本页只读展示。
- 步骤 2 账户币种：外汇渠道 NONE / TREASURY 取交易币种；PROVIDER 取扣账账户币种。
- 步骤 3 可用头寸 ＝ 账户余额 − 未记账的已路由交易占用；未配置该币种余额行时可用按 0 计。
- 步骤 4 判定：可用头寸 ≥ 本笔交易账户币种等价金额 → 通过；否则 `FUNDING_SHORTFALL` 并剔除该候选。PROVIDER
  渠道等价金额＝交易金额 × HSG 当日汇率（交易币种汇率 ÷ 扣账币种汇率，经港币交叉）；其余渠道即交易金额。

校验开关开启且仅头寸不足导致失败时，全流程返回 REFER `FUNDING_SHORTFALL`（全部候选未通过且存在仅因 C4 失败的候选，优先级在
`NO_FX_VENUE`、`NO_PROVIDER_ACCEPTS`、`FX_WINDOW_CLOSED` 之后）；运营审核环节，资金部确认补头寸后，运营可人工选用该合作方（示例
6b）。

计算由 `C4Calculator` 供独立模拟和全流程路由共用（原 `RoutingService` 内联的头寸判定已迁入同一计算器，全流程 C4
明细即计算器结果：enabled、fxVenue、accountCurrency、rate、required、balance、reserved、available、accountFound、passed、reasons）。开关
OFF 时仍计算账户与头寸数值，仅供展示，`passed` 恒为 true。

页面提供往来账余额行的新增/编辑/删除（合作方、币种、账户余额、未记账占用；同一合作方同一币种仅一行，金额须非负）、合作方/币种筛选、合作方资金校验开关只读表，以及单合作方模拟：扣账＝交易币种时按
NONE 渠道校验；扣账≠交易币种时按已配置的外汇窗口逐渠道（TREASURY / PROVIDER）校验，与全流程候选生成一致，没有任何渠道窗口时返回
`NO_FX_VENUE`。模拟可选「资金校验开关」覆盖本次试算（不落库）。示例按钮覆盖：通过、头寸不足、资金部渠道、合作方渠道开关 ON（Wise
USD→AUD 7,800 万，扣账 USD 需 5,100 万 > 5,000 万可用）、同一笔开关 OFF（直接通过）、HKD→EUR 无渠道六种场景。

余额行与全流程参考配置共用 `relay_routing_document` 中的 CONFIG `balances`（不另建一份数据）。保存会递增配置版本并写入配置审计；旧版本保存会被拒绝。实时
HSG 余额/占用接口尚未接入，页面数值仅用于演示；试算不占用资金。

接口：

- `GET /api/c4/balances`：返回配置 revision、余额行 `{index, balance:{provider,currency,balance,reserved}}`、币种清单、HSG
  当日汇率（hkdRates）及各合作方资金校验开关 `{code,name,status,fundingCheckEnabled,nostroAccount}`。
- `PUT /api/c4/balances`：提交 `{revision,index?,balance}`；index 为空新增，有值编辑。合作方须存在，币种须为三位大写且已配置汇率，余额与占用须非负，同一合作方同一币种不可重复。
- `POST /api/c4/balances/delete`：提交 `{revision,index}` 删除余额行；删除后该币种可用头寸按 0 计。
- `POST /api/c4/simulate`：提交 `{providerCode,debitCurrency,paymentCurrency,amount,fundingCheckEnabled?}`，返回 PASS /
  FUNDING_SHORTFALL / NO_FX_VENUE、各渠道分步计算结果（`venues[].calculation`）与配置版本；fundingCheckEnabled 留空用合作方已保存的开关。

2026-11-23：全套168项测试通过（新增15项）。`C4CalculatorTest` 6项：开关 OFF、NONE/TREASURY 取交易币种、PROVIDER
取扣账币种与汇率折算、占用扣减与等于边界的通过、仅用本合作方余额行、非法渠道/缺汇率。`C4ServiceTest`
9项：列表、增删改与全流程共享配置并写审计、字段校验与版本冲突、六类模拟场景、试算开关覆盖不落库、与全流程 C4
明细逐字段一致、页面保存余额后全流程返回 REFER `FUNDING_SHORTFALL`。在独立端口与内存库上以 curl 验证全部接口及13个页面的侧边栏顺序，并用无头
Chrome 实测页面：六个示例、筛选与刷新保留条件、编辑/新增/删除、重复与负数校验，无前端 JavaScript 异常。

## 合作方管理（BRD 9.1）

访问 `/providers.html`，C1/C2/C3
左侧「合作方管理」均已接入。列表页负责筛选、列表和删除：支持编码、名称、BIC、类型和状态筛选，展示上线状态、资金校验、能力标签、优先级及临期/逾期复核提示。每行提供三个操作：「详情」打开独立页面
`/provider-detail.html?code=合作方编码` 只读查看（见下文「合作方详情页面」）；「编辑」打开独立编辑页 `/provider-edit.html?code=合作方编码`；「删除」删除该合作方（见下文「删除合作方」）。页面顶部的「＋
新增合作方」打开同一页面的空白新增表单（见下文「合作方新增 / 编辑页面」）。列表页不再内嵌编辑面板。

维护字段：编码（唯一，最长12位、保存后不可改）、名称（80字符）、类型（TIER1_BANK/OTHER_BANK/HEAD_OFFICE/FINTECH/NETWORK）、状态（LIVE/NOT_LIVE）、C4资金校验、报文通道（SWIFT/API/FILE/MANUAL/BOOK）、BIC、C2能力标签、优先级（1–99）、HSG往来账号、HSG总账编码、HSG对接负责人用户编码、准入复核日期。

- 新增默认 NOT_LIVE；SWIFT 通道强制填写8或11位 BIC。
- 保存为 LIVE 时必须具备至少一条启用覆盖行、一条手续费配置、一条当前有效的准入行、往来账号和总账编码。EXCLUDE
  同样是已配置准入行，不代表通过C1；最终候选需执行完整过滤。
- 状态、通道、资金校验和优先级直接使用原 provider 表，下次路由试算生效。能力标签与C2共用，修改留审计；合作方能力标签的维护入口已从
  C2 页面并入合作方编辑页的表单。
- 编辑页的「合作方限制」页签（原 C2
  页「合作方限制」标签页并入）：按合作方显示制裁及监管屏蔽记录（类型、屏蔽值、生效期、版本、启停），支持新增 / 编辑 / 启停，走 C2
  限制接口并留版本号，C2 校验实时读取。详情页的同名页签为只读列表，可经「前往维护」跳回编辑页。
- 「模拟测试」（编辑页与详情页均有入口）将当前合作方带入全流程试算的 includeProviders，NOT_LIVE 也可参与模拟；不修改其上线状态。
- 使用乐观锁 version 防止基础字段被旧版本覆盖；变更记录保存前后快照、用户及时间，在详情页「变更记录」页签查看。当前操作用户仍为
  demo.operator。
- 准入复核按香港日期显示30天内临期/逾期；负责人目录及自动通知尚未连接，页面不发送提醒。
- 旧演示记录的 PAYMENT_NETWORK 在编辑表单映射为 NETWORK，超过99的旧优先级在表单调整至99，保存后规范化。旧记录不自动填充BIC、账号或负责人，缺失资料显示待完善。

删除合作方：列表行的「删除」先查询影响，确认框逐项列出将一并删除的配置及数量，确认后在同一事务内删除合作方及其自有配置，不可恢复。

- 一并删除：C2 能力标签、C1 准入行、C2 合作方限制、MTD 模拟数据，以及路由配置里该合作方的 C3 业务覆盖、C4 往来账余额、C7 手续费、C8
  偏好规则、C9 履约协议、C10 外汇窗口和当日 / 当月累计。路由配置经 `saveConfig` 保存，剩余配置重新校验、版本加 1 并记 `ROUTING_CONFIG`
  审计，其它合作方的行不受影响，C3–C10 页面照常保存。客户排除和个案审批属于客户侧记录，保留不动。
- 不限制状态，LIVE 也可删除；删除后不再参与路由试算。
- 乐观锁：version 取列表加载时的值。版本已变化返回 400「合作方版本已变化，请刷新后重试」，合作方已被删除返回 400「合作方不存在」，页面随即重新读取列表并显示原因。成功后该行消失并提示「已删除合作方
  编码。」，删除过程中的重复点击只发一次请求。
- 审计：写一条 `PROVIDER` / `DELETE` 记录，before 为删除前的快照（合作方含能力标签，以及随之删除的准入、合作方限制、MTD 行），after
  为空。详情页和变更记录只针对现存合作方，这条记录目前可在 `GET /api/audit` 或表 `relay_rule_audit` 中查看。
- 浏览器「后退」：删除成功同样记时间戳，已删除合作方的详情页从往返缓存恢复时会重新读取并提示「合作方不存在」；列表页自己删除或刷新后即视为最新，后退回来不再整页刷新，筛选条件保留。

接口：`GET/POST /api/provider-management`、`GET/PUT /api/provider-management/{code}`、
`GET /api/provider-management/{code}/related`、`GET /api/provider-management/{code}/audit`、
`GET /api/provider-management/{code}/impact`（将随合作方一并删除的各行数量）、`DELETE /api/provider-management/{code}?version=N`（返回
`{code, deleted: true}`）。原 `GET /api/providers` 保持兼容。

数据库新增可空资料字段及 version（默认0），默认 Hibernate update 自动增加列并保留旧数据。禁用自动DDL的既有MySQL库可执行一次
`sql/migrate-provider-management.sql`；新库使用 `sql/schema-mysql.sql`。

2026-11-20：全套49项测试通过；新增10项合作方管理测试及1项旧provider表升级测试。Chrome在独立内存库完成新增、编辑、上线条件拦截及回滚、C2标签同步、关联配置、审计、未上线合作方模拟入口和手机布局验证；实际8081文件数据库升级后页面正常加载，原有合作方保留。

2026-11-24：列表行新增「删除」，后端新增影响查询与删除接口。全套178项测试通过（新增4项：服务层3项覆盖删除种子合作方后路由配置只去掉它自身的引用、其余合作方的行原样保留且仍通过校验与试算，连带删除
/ 客户侧记录保留 / 审计快照，以及版本校验；HTTP 层1项覆盖 DELETE 动词、version 查询参数及 400 / 405
响应）。在独立端口与临时数据库上以无头 Chrome 完成 78 项检查：三个行操作按钮等高对齐且样式一致、确认框取消后无任何写请求、确认框里的数量与各接口独立统计逐项一致、列表过期（他处已修改
/ 已删除）时自动刷新并提示原因、连点两次只发一次请求、键盘 Enter、客户排除与个案审批保留、删除带配置的种子合作方后路由配置版本加 1
且其它合作方的行不变、C3–C10 与路由配置原样保存全部通过、13 个页面重新加载无异常、浏览器后退（已删除合作方的详情页自动刷新并提示不存在，列表页保留筛选条件）、1440
/ 1280 / 390 三种宽度下页面无横向溢出。无前端 JavaScript 异常。

## 合作方详情页面（独立页）

原内嵌在合作方管理列表下方编辑面板里的详情（基础信息 / 关联配置 / 合作方限制 / 变更记录，加上上线准备检查和准入复核）已抽取为独立只读页面
`/provider-detail.html?code=合作方编码`，由列表行的「详情」和编辑页的「查看详情」进入；维护类的「基础信息」「合作方限制」在独立的编辑页（见下节）。页面不提供写操作：

- 顶部是合作方概要：编码、名称，上线状态 / 类型 / 上线准备 / 准入复核四个徽标，以及报文通道、BIC、排序优先级、数据版本。
- 四个页签：基础信息、关联配置（C1 准入、C3 覆盖、C7 手续费、C10 外汇渠道）、合作方限制（只读列表）、变更记录（可展开变更前后快照）。右侧是上线准备检查（取接口返回的
  `checks`）和准入复核提示。
- `&tab=related|restrictions|audit` 直接打开指定页签；「↻ 刷新」保留当前页签。
- 「编辑合作方」打开 `/provider-edit.html?code=编码`，合作方限制页签的「前往维护 ↗」打开
  `/provider-edit.html?code=编码&tab=restrictions`（直接落在编辑页的「合作方限制」页签）；「模拟测试」与编辑页内的入口相同。
- 缺少 `code` 或合作方不存在时只显示一行提示，不渲染半截内容。
- 列表、详情、编辑三个页面共用的接口封装、字典、上线检查和复核文案在 `provider-common.js`（须先于页面脚本引入），共用的样式在
  `provider.css`（须在 `hsg-style.css` 之后引入）；后端接口无变化。

2026-11-23：后端无改动。在独立端口与临时数据库上以无头 Chrome 完成 105 项检查：列表行两个操作、编辑面板的修改 / 保存 / 深链、五个合作方的详情各页签与接口数据逐项比对（变更记录与原渲染函数的输出逐字节一致，关联配置除一句提示语外一致）、编码缺失与不存在的提示、手机布局，无前端
JavaScript 异常。（其中「编辑面板」指当时仍内嵌在列表页的面板，此后已独立为编辑页，见下节；列表行此后增加了「删除」，见上节。）

## 合作方新增 / 编辑页面（独立页）

原内嵌在合作方管理列表下方的编辑面板（基础信息表单、「合作方限制」页签，加上上线准备检查和准入复核）已抽取为独立页面：`/provider-edit.html`
是空白的新增表单，`/provider-edit.html?code=合作方编码` 编辑该合作方，再加 `&tab=restrictions` 直接打开「合作方限制」页签。入口有三处：列表页顶部的「＋
新增合作方」、列表行的「编辑」、详情页的「编辑合作方」。列表页不再承载任何编辑功能。

- 布局与详情页一致：顶部是标题（新增合作方 / 编辑合作方）和副标题（新增时为「编码保存后不可修改」，编辑时为编码 · 数据版本），右侧是「←
  返回合作方管理」「查看详情」「模拟测试」三个按钮，后两个在合作方保存成功后才出现。
- 「基础信息」页签即原编辑表单，字段、默认值、校验和右侧随输入实时更新的上线准备检查 / 准入复核提示都未变：LIVE 须填写往来账号和总账编码，SWIFT
  通道须填写8或11位 BIC，保存为 LIVE 还须已有业务覆盖、手续费和有效准入行，否则服务端拒绝并原样显示原因。
- 「合作方限制」页签按合作方维护制裁及监管屏蔽记录（新增 / 编辑 / 启停，走 C2 限制接口）；尚未保存的新增页面上该页签只提示先保存合作方。
- 保存后停留在本页、切回「基础信息」并提示「已保存 编码，版本 N」。新增成功后地址栏换成该合作方的编辑地址（不新增浏览历史），刷新、后退再进来都是编辑它。服务端新建合作方的版本从
  0 起，每次保存加 1。「取消」直接返回列表且不保存；离开页面不做未保存提醒。
- 乐观锁：提交带页面加载时的 version，版本过期时显示「合作方版本已变化，请刷新后重试」并保留已填内容。
- `code` 不存在时只显示接口返回的提示（如「合作方不存在」），不会退化成空白新增表单；旧的 `/providers.html?code=编码#editor`
  深链不再定位编辑面板，只会打开列表。
- 编辑页每次保存成功（含合作方限制的新增 / 编辑 / 启停）后在本标签页的 sessionStorage 记一个时间戳；列表页和详情页从浏览器往返缓存恢复时，若此后有过修改则自动重新读取，否则保持原状（筛选条件不丢）。编辑页自身恢复时不重载，未保存的输入不丢。
- 窄屏（≤640px）下「合作方限制」表单改为单列，避免日期时间选择器遮住分钟位；≤850px
  下限制列表的标题均衡折行，按钮被挤到下一行时靠右（详情页同）。

2026-11-23：后端无改动。在独立端口与临时数据库上以无头 Chrome 完成 144 项检查：新增与编辑的保存、浏览器和服务端校验（编码重复、BIC、上线条件未齐备）、版本冲突（400 与
409）、旧数据提示、编码不存在、合作方限制的新增 / 编辑 / 启停、重复提交与页签切换竞态、列表页和详情页到编辑页的链接、往返缓存恢复（含真实的
history.back()）、手机布局；与改动前逐像素比对，桌面端详情页五个合作方各页签零差异，列表页除移除编辑面板外一致。无前端 JavaScript 异常。

## C5 起息日评分页面

访问 `/c5.html`，各配置页左侧的「C5 起息日 / 截单」已接通。C5
为固定速度打分项：延迟0工作日100分、1工作日50分、2工作日及以上0分；不会向候选过滤器列表添加C5，也不会因为0分剔除候选。综合排序继续按交易类型/客户层级的速度权重加权。

按C3选出的最精确覆盖行维护截单时区（默认香港）、截单时间、0–5工作日偏移和本行节假日列表。日历以周一至五减去配置节假日计算，当前仍是本地演示日历数据，需维护合作方该币种的适用节假日。页面保存只修改C5参数，保留C3覆盖维度、启用状态、C6限额及其他全流程配置；与C3共用数据，不另建一份副本。

计算由 `C5Calculator` 供独立C5试算与全流程路由共用：

1. 当前是工作日且严格早于截单时，计算基准日为今日；否则为下一工作日。恰好截单也顺延。
2. 最早可实现起息日＝基准日＋工作日偏移，跳过周末和节假日。
3. 候选起息日＝max（请求起息日，最早可实现起息日）。
4. 延迟按请求日之后、最早起息日以内的工作日数计算；最早日不晚于请求日则为0。接口返回实际延迟天数，≥2天按0分处理。
5. 请求日期缺失默认取本次评估时刻的香港当日；远期交易若能满足请求日，速度分为100。

页面展示每一步计算依据，提供截单前、恰好截单、周末、远期请求示例。C3没有匹配覆盖行时显示「未评分 · C3 NO_REACH」，不将其表达为C5拒绝。

接口：

- `GET /api/c5/schedules`：覆盖行及当前配置revision。
- `PUT /api/c5/schedules`：提交 `{revision,index,zone,cutoff,offsetDays,holidays}`，只更新该行起息参数并记录配置审计；旧版本拒绝覆盖。
- `POST /api/c5/simulate`：交易字段同C3，增加 `requestedValueDate`、`at`；请求起息日也接受BRD字段名 `requested_value_date`
  。该别名也接入全流程模拟请求。
- 全流程候选新增 `c5` 计算明细，包括评估时间、基准日、最早起息日、请求起息日、候选起息日、实际延迟工作日和速度分；原valueDate返回候选起息日。

2026-11-20：全套65项测试通过（新增16项C5计算及集成测试）。Chrome验证50/0/100分、截单边界、远期请求、节假日保存且与C3同步、重复节假日拒绝、手机布局和零分候选仍保留排名；浏览器写入验证在独立内存库完成，8081原演示配置保留。

## C6 交易限额页面

访问 `/c6.html`，各配置页左侧「C6 交易限额」已接通。C6 为过滤器（BRD 6.6）：数据源为 C3 覆盖行的单笔最大限额、每日最大限额（美元等价）及合作方当日累计金额。

- 覆盖行限额列表：按合作方/币种筛选，展示单笔与每日限额、启用状态；本页只维护两项限额，覆盖维度、截单、节假日等其他字段与 C3/C5
  共用同一行数据。
- 当日累计（演示输入）：按合作方维护 todayUsd 快照；重算不含本笔自身，真实环境由交易统计回填。
- C6 模拟：先按 C3 选中最精确覆盖行（指定地区＞ALL、指定国家＞ALL、LIST＞ALL），再依次执行两项校验并输出原因码；填写「当日累计」仅覆盖本次试算不落库；C3
  未命中时返回 NO_REACH 且限额不适用。

计算由 `C6Calculator` 供独立试算与全流程路由共用：本笔美元等价＝交易金额×HSG中间汇率÷USD汇率；校验1 本笔≤单笔限额，否则
`LIMIT_PER_PAYMENT`；校验2 当日累计＋本笔≤每日限额，否则 `LIMIT_DAILY`；两项校验均执行并留痕（不短路）。

接口：

- `GET /api/c6/limits`：返回配置 revision、覆盖行 `{index, reach}`、币种清单及当日累计快照。
- `PUT /api/c6/limits`：提交 `{revision,index,perPaymentUsd,dailyUsd}`，只更新该行限额并写配置审计；旧版本拒绝覆盖。
- `PUT /api/c6/today`：提交 `{revision,provider,todayUsd}` 更新演示当日累计。
- `POST /api/c6/simulate`：提交交易字段，返回 PASS / LIMIT_PER_PAYMENT / LIMIT_DAILY / NO_REACH 及逐步判定明细。

## C7 综合成本页面

访问 `/c7.html`，左侧导航「C7 综合成本 / 费用」。C7 为打分项（BRD
6.7）：预估总成本（港币）＝合作方手续费＋电报费＋预估中间行费用＋换汇成本；得分＝100×（剩余候选最低成本÷本候选成本），成本为0直接100分。

- 手续费配置：维度【合作方＋币种＋扣费方式（ALL/OUR/SHA/BEN）】；字段含固定手续费、百分比费率（小数）、最低/最高手续费、电报费、预估中间行费用、费用币种；支持新增、编辑、删除，与全流程路由共用配置并写审计。
- 成本预估试算：扣账≠交易币种时按货币对分别展示 TREASURY／PROVIDER 渠道，无需换汇展示
  NONE；每个渠道给出窗口开放状态、点差（含非工作时段加点）、手续费托底/封顶过程与折港币合计；窗口关闭或超单笔上限时标注 C10
  将剔除、点差按0展示仅作参考。

计算由 `C7Calculator` 供独立试算与全流程路由共用：手续费＝固定＋费率×金额，低于最低取最低、高于最高取最高；换汇＝港币金额×点差bps÷10000；全部项目按
HSG 中间汇率折港币。示例（默认配置）：PROV-T1 HKD→USD 20,000 周一 09:05，TREASURY 10bp → 手续费130＋换汇156＝HKD 286；PROV-W
HKD→AUD 20,000 周六，PROVIDER 35＋20＝55bp → 100＋561＝HKD 661。

接口：

- `GET /api/c7/fees`：返回配置 revision、手续费行、币种清单与中间汇率。
- `PUT /api/c7/fees`：提交 `{revision,index?,fee}`；index 为空新增，有值编辑；维度重复由配置校验拒绝。
- `POST /api/c7/fees/delete`：提交 `{revision,index}` 删除该行。
- `POST /api/c7/simulate`：提交 `{providerCode,debitCurrency,paymentCurrency,amount,chargeOption,at}`
  ，返回各外汇渠道的成本明细；无任何货币对窗口时返回空列表并注明 NO_FX_VENUE。

## C8 偏好规则页面

访问 `/c8.html`，左侧导航「C8 偏好规则」。C8 为打分项（BRD 6.8）：条件全部非空匹配时，给规则指定合作方加奖励分；合计封顶100，未命中0分。

- 规则配置：目标合作方＋七个条件（币种、受益行国家、受益人地区、金额上下限 USD、交易类型、客户分层；留空或 ALL＝匹配全部）＋奖励分
  1–100；支持新增、编辑、删除，保存递增版本并写审计。
- 偏好试算：按交易字段逐条评估全部规则，每条规则展示七个条件的「配置值｜交易值｜命中」明细；按合作方汇总累计分与封顶后得分。预置规则：USD≥100万→PROV-T1＋100；受益国家
  KG→PROV-HO＋100；地区 CN-GD→PROV-GD＋100。

计算由 `C8Calculator` 供独立试算与全流程路由共用（路由内原内联匹配已改为同一计算器）：文本条件空/ALL
视为通配；指定地区仅在交易携带相同地区编码时命中；金额上下限按美元等价闭区间比较。

接口：

- `GET /api/c8/rules`：返回配置 revision 及规则列表。
- `PUT /api/c8/rules`：提交 `{revision,index?,rule}`；奖励分范围 1–100。
- `POST /api/c8/rules/delete`：提交 `{revision,index}` 删除规则。
- `POST /api/c8/simulate`：提交交易字段，返回全部规则评估明细与各合作方得分。

## C9 商业协议履约页面

访问 `/c9.html`，各配置页左侧的「C9 商业履约」已接通（全流程 Check 页头部亦增加入口）。C9 为固定履约打分项（BRD
6.9）：数据源为商业协议配置（9.3）及 MTD 月累计金额 / 交易笔数，按香港自然月评分：

1. 合作方无月度目标（无协议或目标为0），或 MTD 已 ≥ 月度目标 → 得0分（协议已履行，不额外引流）。
2. MTD ＜ 月度目标 ×（当月日期 ÷ 当月总天数）→ 得100分（进度落后，路由加权引流）。
3. 其余情况 → 得50分（进度正常）。

同一合作方可维护多条 AMOUNT/COUNT 协议，得分取最高分；C9 不会向候选过滤器列表添加条目，也不会因为任何分数剔除候选，仅按协议（agreement）权重参与综合排序。

计算由 `C9Calculator` 供独立C9试算与全流程路由共用（路由内原内联计算已改为调用同一计算器）：

1. 评估时间取香港时区自然月：第 N 天 / 共 M 天，时间进度＝N÷M。
2. 按协议指标取 MTD：AMOUNT 读月累计金额（USD等价），COUNT 读月累计笔数。
3. 进度基准＝月度目标 ×（当月日期 ÷ 当月总天数），Decimal128 计算。
4. 严格小于基准判进度落后（100分），大于等于基准且未达标判进度正常（50分），达标或无目标为0分；月末最后一天基准即全月目标。

页面提供 0/50/100 分说明卡、合作方/履约状态筛选、每家合作方的协议目标、MTD、当月进度、进度条（含时间基准标记）与逐协议得分；支持维护协议（同一合作方＋指标为编辑，否则新增，可删除）、调整
MTD 演示输入，以及带覆盖参数（指标、目标、MTD、评估时间）的独立试算，试算展示每一步判定依据，提供已达标、进度正常、进度落后、无目标四个示例。协议与
MTD 和 C3–C10 共用 `relay_routing_document` 的 CONFIG，保存递增版本并写配置审计，旧版本拒绝覆盖。

接口：

- `GET /api/c9/agreements`：返回配置 revision 及每家合作方的协议列表、MTD、当月进度和当前评分。
- `PUT /api/c9/agreements`：提交 `{revision,provider,metric,monthlyTarget}` 新增或更新该合作方该指标的月度目标。
- `POST /api/c9/agreements/delete`：提交 `{revision,provider,metric}` 删除协议；删除后按无月度目标计0分。
- `PUT /api/c9/mtd`：提交 `{revision,provider,monthUsd,monthCount}` 更新演示 MTD 累计（null 表示不修改）。
- `POST /api/c9/simulate`：提交 `{providerCode,metric?,monthlyTarget?,monthUsd?,monthCount?,at?}`，留空字段使用已保存值，填写即覆盖且不保存。

2026-11-20：全套88项测试通过（新增13项C9计算及10项服务/集成测试，覆盖0/50/100判定、恰在基准、月末月初边界、COUNT指标、多协议取最高分、纽约时间转香港日历、协议增删改、MTD更新、版本冲突及全流程路由同分一致性）。Chrome验证页面加载、示例试算、协议保存/删除、MTD调整及导航；浏览器写入验证在独立内存库完成，8080/8081原演示配置保留。

## C10 外汇能力校验页面

访问 `/c10.html`，各配置页左侧的「C10 外汇窗口」已接通（全流程 Check 页头部亦增加入口）。C10 为过滤器（BRD
6.10），仅换汇交易适用：扣账币种＝交易币种时无需换汇直接通过；未配置窗口的渠道不生成候选，所有渠道均不支持该货币对时返回 REFER
`NO_FX_VENUE`（8.1）。

- 外汇渠道＝TREASURY 资金部：当前时间落在该货币对资金部窗口内才通过（周一至周五 09:00–17:30，香港节假日一律关闭，即使该行勾选
  HOLIDAY），否则 `FX_TREASURY_CLOSED`。
- 外汇渠道＝PROVIDER 合作方：当前时间落在合作方窗口内且本笔美元等价 ≤ 窗口单笔限额；不在窗口 `FX_PROVIDER_CLOSED`，在窗口但超限额
  `FX_TICKET_LIMIT`。
- 全部渠道仅 C10 失败时，整体返回 `FX_WINDOW_CLOSED` 并给出下一窗口开窗时间；超全部窗口单笔限额的笔无下一窗口可等。

窗口配置（BRD 9.3 示例，即 12 章演示配置）：资金部 HKD/USD、USD/HKD、HKD/CNH、CNH/HKD、USD/CNH、CNH/USD 共 6 对，工作日 09:00–17:
30、点差 10bp；Wise 澳元/新元对（HKD 与 USD 扣账）每日 00:00–23:59、点差 35bp、单笔上限 50 万美元，周末/节假日行以「非工作时段加点」＋20bp
表达；Wise 韩元对工作日 08:00–16:30、点差 50bp、单笔上限 20 万美元。HKD/EUR、HKD/KGS 等未配置货币对提交即 `NO_FX_VENUE`。

计算由 `C10Calculator` 供独立试算与全流程路由共用（原 `RoutingService` 内联窗口判定已迁入同一计算器，C7
页面判断窗口开放同样复用）：按评估时间（香港时区）匹配日期（MON–SUN / HOLIDAY）与时段，多个窗口同时开放取点差合计最低；`next`
自评估时间起最多查 366
天。页面支持窗口增删改（同一渠道同一货币对不可配置日期与时段重叠的窗口；扣账＝交易币种或跨行日期时段重叠将拒绝）、渠道/货币对筛选、香港节假日表展示，以及渠道级模拟：资金部（所有合作方共用）＋每个配置了该货币对窗口的合作方各生成一个
PROVIDER 判定，与全流程候选生成一致；示例按钮覆盖资金部开窗（周一 09:05）、周六资金部关（下一窗口周一 09:00）、Wise
周末加点（55bp）、韩元超限额（FX_TICKET_LIMIT）、HKD→EUR 无渠道、同币种无需换汇六种场景。

接口：

- `GET /api/c10/windows`：返回配置 revision、窗口行 `{index, window}`、币种清单及香港节假日表。
- `PUT /api/c10/windows`：提交 `{revision,index?,window}`；index 为空新增，有值编辑；渠道为 TREASURY 或已存在合作方。
- `POST /api/c10/windows/delete`：提交 `{revision,index}` 删除窗口行。
- `POST /api/c10/simulate`：提交 `{debitCurrency,paymentCurrency,amount,at}`，返回各渠道的窗口匹配、开放窗口（点差合计）、原因码与下一窗口；扣账＝交易币种返回
  NONE 直接通过。

## 元数据中心页面（独立页）

原首页（C1 页面）内嵌的「字段库 / 表达式」标签页已抽取为独立页面 `/fields.html`（页面名「元数据中心」，页内分「字段库」「业务函数库」「表达式菜单与试算」三个页签）
，首页侧边栏及十个配置页（合作方管理、C2/C3/C4/C5/C6/C7/C8/C9/C10）工具区的「元数据中心」均已接通。侧边栏「规则审计」入口及首页「配置历史」标签页已按演示精简需求移除；配置变更审计仍在后端照常记录（每次保存写
`relay_rule_audit`），可通过 `GET /api/audit` 或合作方详情页（`/provider-detail.html`）的「变更记录」页签查看。

页面左侧为字段库全量表格（数据来自 `relay_rule_field` 表，较原标签页增加「可选值」「状态」两列，另有「取值来源」一列）：展示字段编码、名称、类型、适用规则、运算符、取值来源、可选枚举与启用状态，并说明维护方式（H2
控制台 INSERT/UPDATE 或 `DemoData` 种子，后者仅空表生效；`allowedRules` 含 C2 且启用的字段进入试算菜单的 C2 筛选和监管路由规则新增 / 编辑页附加触发条件的字段下拉，C2
模拟页各输入项的下拉可选值也从这里读取，九个固定交易要素之外的字段还会在模拟面板自动生成取值控件；`allowedRules` 含 C1 且启用的字段进入首页 C1「① 触发条件」的字段下拉（取值来源为函数计算的字段带「ƒ 函数名」标注），停用字段从下拉消失但历史规则表达式按原样存档）。

右侧为 AND / OR 可视化表达式试算（字段定义与各规则页面共用）：选字段与运算符组成条件组，有可选值枚举的字段自动渲染下拉；模拟输入
JSON 作为业务变量，`POST /api/expression/preview` 将条件树编译为受控 AviatorScript 表达式并返回命中结果与表达式原文。表达式试算不直接代替
C1 固定业务判断，避免 OR 绕过精确匹配与默认拒绝。示例按钮覆盖：USD 且 KG（AND 命中）、金额 ≥ 100万、PSP 或 WEB3（OR）。

「业务函数库」页签维护函数库（表 `relay_business_function`）：函数编码、名称、分类、返回类型、适用规则、执行作用域、缓存策略、Java 实现绑定、数据来源、输入参数、说明和启用状态。函数库只登记描述信息，能执行的是代码里实现了
`BusinessFunction` 并注册为 Bean 的 Handler。列表的「使用策略 / 实现」列显示系统必检 / 可选 / 执行器内部落实（由规则执行器在代码里声明，这里只读）以及后端实现是否已注册；系统必检函数的删除按钮置灰（只能停用），
实现未注册的函数不能进入条件。

字段编辑器增加「取值来源」：「请求输入」（默认，取值来自调用方在请求中提供的 `variables`）或「函数计算」（选择函数库里的函数，并为每个参数指定受控取值路径）。取值来源为「函数计算」的字段只能适用于 C1：选「函数计算」时只保留 C1，
其余九个规则不可勾选；函数下拉只列出返回类型与字段类型一致的函数（枚举字段对应 STRING），已保存但当前不可用的函数保留并标注「（当前不可用）」；字段列表里这类字段的名称后有 ƒ。表达式菜单里，条件引用了函数计算字段时字段名
同样带 ƒ，点「执行」会提示只能在规则执行中取值（如 C1 页的模拟校验）而不发请求。绑定规则、校验和 C1 执行时的取值见「C1 条件接入函数库」。

## MTD 模拟数据页面（C1 月度上限）

C1「MTD 使用情况」页签的数据来自 `relay_provider_usage`（合作方 × 流转类别 × 月份 → 月累计金额 USD）。演示环境**没有定时任务**：`DemoData`
仅在表为空时按香港当月预置两条（PROV-T1/STANDARD 1,800,000、PROV-W/STANDARD 800,000），之后保持不变；C1 校验只读取「香港当月」的记录，命中
设置了月度上限的 ACCEPT 规则时判断 `MTD + 本笔金额 <= 月上限`，否则返回 `APPETITE_CAP_REACHED`，模拟校验不占用 MTD。

侧边栏「工具 → MTD 模拟数据」（`/mtd.html`）用于模拟统计任务回填：

- 列表展示全部 MTD 记录（月份倒序），标出当月记录，并列出来自生效 ACCEPT 规则的月度上限与当月剩余额度。
- 「调整 MTD 月累计」表单：选择合作方、流转类别、月份，填写金额后保存，即调用 `PUT /api/usage` 覆盖写入（不存在则新增），同一组合仅一行；可删除记录。输入金额时实时预览保存后的当月剩余额度。
- 示例按钮：「接近月度上限」把当月 MTD 设为「月度上限 − 100,000」，随后到 C1 模拟一笔 150,000 即返回 `APPETITE_CAP_REACHED`；「清零」恢复余量。
- 右侧面板按表单内容实时生成 curl 命令，可直接在终端执行。
- C1「MTD 使用情况」页签保持只读，每行提供「调整」跳转到本页并带入该行参数（`/mtd.html?provider=..&flow=..&month=..`）。

接口：`PUT /api/usage`（请求体 `{providerCode,flowCategory,monthKey,amountUsd}`；校验合作方存在、流转类别为 STANDARD/PSP/WEB3、月份格式
`YYYY-MM`、金额必填且 ≥ 0，字段缺失或拼错一律返回 400 而不会按 0 覆盖；返回保存后的记录）和 `DELETE /api/usage/{id}`；每次写入在 `relay_rule_audit`
记录 `USAGE` 类型的 CREATE / UPDATE / DELETE。

注意：C9 商业履约使用的 MTD（协议配置中的 `monthUsd` / `monthCount`）是另一份数据，请在 C9 页面或通过 `PUT /api/c9/mtd` 调整，两者互不影响。

接口（2026-11-20 抽取页面时均为既有接口；2026-11-25 起字段接口增加取值来源与函数绑定，见下文）：

- `GET /api/fields`：返回字段库全量（含停用）。
- `GET /api/c2/fields`：C2 页面使用，仅 `allowedRules` 含 C2 且启用的字段：监管路由规则新增 / 编辑页附加触发条件的字段下拉、C2 模拟页的下拉可选值，以及九个固定交易要素之外字段的取值控件都从这里读取。
- `GET /api/field-metadata`：元数据中心字段库全量（按字段编码排序）；`POST /api/field-metadata` 新增、`PUT /api/field-metadata/{code}` 修改、`POST /api/field-metadata/{code}/toggle` 启用 / 停用。首页 C1「① 触发条件」的字段下拉、C2
  页面和试算菜单都按这里的 `allowedRules` 取字段。「适用规则」的 C2 可对任意数据类型勾选：勾选后字段出现在试算菜单的「C2」筛选下并按字段类型比较取值，进入监管路由规则新增 / 编辑页
  附加触发条件的字段下拉，并在 C2 模拟面板自动生成取值控件；监管路由规则的适用范围（币种 + 受益行国家）本身不使用字段库条件树。
- `POST` / `PUT /api/field-metadata` 的取值来源与函数绑定：入参增加 `valueSource`（`REQUEST` 请求输入 / `FUNCTION` 函数计算）、`functionCode`、`argumentBindings`（JSON 对象：参数编码 → 受控路径 `"$candidate.providerCode"` 或字面量）。
  绑定函数的字段只能适用于 C1，函数须可用于 C1 且返回类型与字段类型一致（枚举字段对应 `STRING`），否则返回 400。三项都不传（升级前的页面或脚本）表示请求没有表达取值来源：新增字段留空（按请求输入），修改时保留已有配置并
  按本次提交的类型 / 适用规则复核。`GET` 返回 `valueSource`、`functionCode` 和 `argumentBindings`（JSON 字符串）。
- `POST /api/expression/preview`：提交 `{tree:{logic,children:[{field,operator,value}]},variables}`，返回
  `{matched,expression}`；条件含函数条件或函数计算字段时返回 400（没有规则执行上下文，函数只在 C1 判定和函数试算中执行）。

2026-11-20：纯前端抽取，151 项测试不受影响；Chrome 验证独立页加载、字段表渲染（演示种子 12
字段）、表达式命中/未命中、三个示例、首页各标签切换、九个子页导航接通。同日按演示精简需求移除侧边栏「规则审计」入口与首页「配置历史」标签页（后端审计写入与
`GET /api/audit` 保留）。

2026-11-25（字段 ↔ 函数绑定）：「字段库」增加「取值来源」列，字段编辑器增加取值来源 / 绑定函数 / 参数绑定；「业务函数库」增加「使用策略 / 实现」列，系统必检函数不能删除；表达式菜单对函数计算字段只展示、不试算。
后端 `POST` / `PUT /api/field-metadata` 增加取值来源与函数绑定入参（旧版请求体照常可用）。无头 Chrome 验证：字段列表的取值来源列、字段编辑器的绑定（选「函数计算」后 C2–C10 置灰）、函数库的使用策略列与置灰的删除按钮、
表达式页对函数计算字段的拦截提示；这几项包含在「C1 条件接入函数库」的 48 项检查里。
