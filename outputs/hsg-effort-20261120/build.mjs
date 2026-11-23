import fs from 'node:fs/promises';
import {Workbook, SpreadsheetFile} from '@oai/artifact-tool';

// Engineering estimates, not measured actuals. Rates are mid-level engineer person-days.
const lines = `
01|需求设计|范围及验收|梳理一期范围、角色、验收用例|有BRD，需行方确认|1|项|3|0.5|不含定价、筛查、最终双人复核重建
02|需求设计|流程及接口|S2/S3/S4、NEW/RERUN、取消及人工覆盖契约|Demo未接HSG|1|套|4|0.5|需HSG支付团队确认接口及状态流转
03|需求设计|技术方案|模块边界、时序、故障降级与并发方案|已有单体Demo|1|套|3|0.5|按Java/Spring、关系库、既有基础设施
04|需求设计|评审及计划|规则边界评审、数据口径、任务拆分|已有部分规则梳理|1|轮|2|0|只计工程人员投入
05|Web|公共框架|菜单、路由、表格表单、错误处理、组件规范|原生HTML样式可复用|1|套|2.5|0.5|假设复用现成后台组件库
06|Web|合作方管理|列表详情、上线检查、标签及审计|已有演示页面|1|模块|2.5|1.5|补真实用户与权限、统一交互
07|Web|C1配置|准入、客户排除、个案审批、MTD页面|已有演示页面|1|模块|4|2|补正式字典、角色与有效期校验
08|Web|C2配置|限制清单、监管条件、能力标签页面|已有演示页面|1|模块|3.5|1.5|补发布版本及复杂输入场景
09|Web|C3/C5/C6|覆盖、截单日历、起息评分、限额配置|C3/C5已有，C6无独立页面|1|组合模块|4.5|2|覆盖行共用，避免三次重复建设
10|Web|C7费用|手续费、点差成本配置及试算明细|仅JSON参考配置|1|模块|2|0.25|含最低/最高、币种及扣费方式
11|Web|C8偏好|条件编辑、奖励分、优先级与试算|仅计算及JSON配置|1|模块|2|0.25|配置条件空值语义需完整显示
12|Web|C9履约|月度目标、金额/笔数及进度展示|仅计算及JSON配置|1|模块|1.5|0.25|按自然月与HKT口径
13|Web|C10外汇|资金部/合作方窗口、假日与单笔上限|仅计算及JSON配置|1|模块|2.5|0.25|含货币对方向、非工作时段参数
14|Web|权重及字典|权重、流转类别、能力、国家地区、日历|少量字段库及列表|1|组合模块|3.5|0.5|权重合计100；字典无需代码发布
15|Web|运营S2/S3/S4|推荐详情、审核队列、手选合作方及重跑|只有单笔模拟结果页|3|页面|3|0.75|含失败警告、起息日变化、OTHER备注
16|Web|模拟及报表|单笔、批量导入、结果下载、每日报表|单笔演示可复用|1|组合模块|5|1|批量最多10000笔；下载和异步进度
17|Web|权限及审计|角色按钮、只读审计导出、系统参数|仅展示及演示用户|1|组合模块|3|0.5|不重复计公共表格组件
18|后端|服务基础|配置、统一异常、校验、API文档与日志链路|已有Spring Boot骨架|1|套|3|1|复用公司服务脚手架
19|后端|合作方服务|字段、上线检查、能力关联与版本冲突|已有业务Demo|1|模块|2.5|1|角色和正式用户来源待接
20|后端|C1/C2|准入多类别、排除审批、限制及监管表达式|已有算法和服务|2|模块|3|2|包括安全表达式边界与单元自测
21|后端|C3/C5|精确覆盖匹配、截单工作日和0/50/100分|已有共用计算器|2|模块|2|1.5|C5不剔除；正式日历源接入另列
22|后端|C4/C6|账户币种资金判断、单笔与日累计限额|仅本地余额/快照|2|模块|2.5|0.75|真实余额及占用来源另列
23|后端|C7/C8/C9/C10|成本、偏好、月目标、FX窗口与评分|已有内存计算逻辑|4|模块|2.5|2|补配置版本、精度与边界
24|后端|候选及排序|生成FX候选、过滤全量原因、权重、排名及REFER|已有模拟引擎|1|引擎|5|2|主路由+最多两备选；默认权重兜底
25|后端|参考配置服务|字典、费用、目标、窗口、权重、参数等CRUD|部分仍存整块JSON|12|配置域|0.65|1|只计通用及配置服务，不重复规则算法
26|后端|真实数据适配|客户档案/UBO、账户余额、汇率与日历适配|均为本地演示数据|4|接口域|2.5|0|含映射、超时、异常和契约自测
27|后端|路由接口契约|BRD完整请求/响应、校验、超时REFER和来源渠道|只有简化simulate接口|1|接口域|4|0.5|实际支付报文生成/发送由HSG承担
28|后端|占用及幂等|payment/request幂等、资金/日限/月限原子占用|尚未实现|1|模块|8|0|核心并发风险，不可用静态MTD代替
29|后端|重跑及取消|排除本笔占用、原路由保留、取消释放、重复通知|尚未实现|1|模块|5|0|含失败回滚与重复/乱序处理
30|后端|运营审核接口|确认、覆盖、回退、候选合法性及覆盖审计|尚未实现|1|模块|5|0|复核释放复用HSG，不重建审批引擎
31|后端|配置版本与审计|生效区间、发布快照、逐行版本及7年归档|已有覆盖式修改和演示日志|1|模块|5|1|含不可变决策证据；归档策略复用行方能力
32|后端|批量及报表|10000笔模拟任务、进度重试、统计与导出|仅单笔模拟|1|组合模块|6|0.5|离线任务不占真实额度
33|后端|权限及性能|SSO/RBAC接入、权限隔离、缓存及查询优化|无身份认证和生产缓存|1|组合模块|6|0|复用行方身份服务；压测执行在测试类另列
34|数据库|领域模型|合作方/配置/版本/决策/占用/操作/报表等模型|少量实体，部分JSON聚合|1|模型套|4|0.5|逻辑表约20–30张，实际以设计评审为准
35|数据库|表结构及索引|DDL、约束、金额精度、索引与查询计划|已有演示DDL|25|逻辑表|0.25|1|不逐页重复计建表
36|数据库|账本与并发设计|占用表、唯一键、锁序、幂等键和状态机|尚未实现|1|方案|3|0|后端占用行负责代码，此行负责数据设计
37|数据库|迁移与归档|版本迁移、回滚、7年审计分区/归档、恢复验证|仅增量演示脚本|1|套|4|0.5|不含硬件与存储采购费用
38|数据跑数|数据盘点与映射|来源、字段、主键、缺失值、业务口径映射|未取得真实数据样本|1|套|3|0|行方提供可用样本与字段说明
39|数据跑数|基础主数据|币种、国家地区、角色、能力、流转类别、日历整理|只有演示枚举|6|数据域|0.5|0.25|不含行方业务人员收集外部政策耗时
40|数据跑数|参考数据整理|合作方、reach、费用、准入、审批、限制、FX等清洗|只有少量示例配置|15|数据域|0.4|0.25|按域验重、编码映射、有效期及关联校验
41|数据跑数|导入脚本|数据模板、转换校验、幂等导入、拒绝明细和回滚|尚未实现|1|套|4|0|工具脚本在此计，不再计入配置CRUD
42|数据跑数|历史及脱敏样本|客户、交易、UBO样本脱敏映射，准备10000笔回放|只有极少示例|1|批|3|0|默认源数据可导出；跨系统提取改造另议
43|数据跑数|累计量初始化|按HKT重建当日/MTD、按类别聚合并核对占用|静态MTD示例|1|批|3|0|区分已记账、未记账、取消、重跑本笔
44|数据跑数|导入演练|空库/增量导入、失败重跑、记录数和金额对账|尚未执行真实演练|3|轮|1.5|0|技术执行及问题定位，业务签字由行方负责
45|数据跑数|历史回放与差异|人工结果对照、过滤原因/评分/排序差异归因|仅功能测试样例|2|轮|2.5|0.5|测试类负责用例验证，本行负责数据结果对账
46|数据跑数|上线初始化|最终导入、切换前后核对、跑数报告与移交|尚未实现|1|轮|2|0|不含新增交易域、额外历史年份补数
47|测试联调|用例与数据矩阵|BRD示例、边界值、角色、币种地区及回归矩阵|已有65项开发测试|1|套|3|0.75|开发单元自测已在各开发行内，不重复计算
48|测试联调|功能与端到端|页面/API、运营全链路、失败恢复与回归|仅Demo及少量浏览器验证|2|轮|4|0.5|含批量导出、真实数据适配的集成场景
49|测试联调|HSG联合调试|客户端、余额、汇率、支付队列和操作回调联调|均未接入|4|接口域|1.5|0|后端行含编码，本行只计联合调试执行
50|测试联调|并发及性能|幂等/占用竞态、取消重跑、100合作方/50000配置、P99|无生产压测|1|专项|5|0|P99≤500ms、超时≤2s；吞吐待行方给出
51|测试联调|权限及安全验证|角色隔离、数据脱敏、审计只读、表达式攻击验证|尚未形成专项|1|专项|3|0|不含第三方渗透采购及整改新增需求
52|测试联调|UAT与修复|验收支持、缺陷修复、回归及业务签字配合|尚未验收|2|轮|3|0|本行计验收期修复；风险预留不再重复计常规缺陷
53|交付运维|环境及流水线|开发/UAT/生产参数、构建发布、密钥配置|本地启动|1|套|3|0.25|假设行方已有云、网络、CI及权限
54|交付运维|监控与恢复|健康/时延/失败/占用告警、备份恢复及演练|只有actuator health|1|套|3|0.25|含99.9%目标的部署与运行支撑方案
55|交付运维|切换及观察|发布回滚演练、投产支持与首轮运行观察|尚未投产|1|轮|3|0|数据导入工时在数据类，此行计发布操作
56|交付运维|文档与培训|部署、接口、运维、数据字典及用户培训|有Demo README|1|套|2|0.5|工程交付文档及一次培训
`.trim().split('\n').map(l => l.split('|'));

const rows = lines.map(a => ({
    id: a[0],
    category: a[1],
    module: a[2],
    task: a[3],
    status: a[4],
    quantity: +a[5],
    unit: a[6],
    rate: +a[7],
    credit: +a[8],
    note: a[9]
}));
const categories = ['需求设计', 'Web', '后端', '数据库', '数据跑数', '测试联调', '交付运维'];
const totals = categories.map(c => {
    const r = rows.filter(x => x.category === c);
    return {
        category: c,
        full: r.reduce((s, x) => s + x.quantity * x.rate, 0),
        credit: r.reduce((s, x) => s + x.credit, 0),
        remaining: r.reduce((s, x) => s + Math.max(0, x.quantity * x.rate - x.credit), 0)
    };
});
console.log(JSON.stringify({
    totals,
    full: totals.reduce((s, x) => s + x.full, 0),
    credit: totals.reduce((s, x) => s + x.credit, 0),
    remaining: totals.reduce((s, x) => s + x.remaining, 0)
}));

const out = new URL('./', import.meta.url).pathname;
const wb = Workbook.create();
const sum = wb.worksheets.add('汇总与参数'), detail = wb.worksheets.add('工作量清单');
sum.tabColor = '#183B63';
detail.tabColor = '#6481A5';

function base(sheet, range) {
    sheet.showGridLines = false;
    sheet.getRange(range).format.font = {name: 'Arial', size: 10, color: '#23364D'};
    sheet.getRange(range).format.verticalAlignment = 'center';
    sheet.getRange(range).format.rowHeight = 24;
}

base(sum, 'A1:J49');
base(detail, `A1:O${rows.length + 7}`);

function header(s, range) {
    s.getRange(range).format = {
        fill: '#183B63',
        font: {name: 'Arial', size: 10, bold: true, color: '#FFFFFF'},
        rowHeight: 32,
        wrapText: true,
        horizontalAlignment: 'center',
        verticalAlignment: 'center'
    };
}

sum.getRange('A2').values = [['HSG RELAY 一期工程工作量估算']];
sum.getRange('A2').format.font = {size: 15, bold: true};
sum.getRange('A3').values = [['估算日期：2026-11-20。按中级工程师、现成企业后台及行方基础设施估算，单位：人日。']];
sum.getRange('A5:D5').values = [['交付口径', '工程人日', '工程人时', '说明']];
header(sum, 'A5:D5');
const end = rows.length + 5;
sum.getRange('A6:A11').values = [['从零实现一期范围'], ['现有Demo预计复用抵扣'], ['基于当前Demo的剩余基准'], ['较顺利情况'], ['谨慎估算'], ['建议预留后工作量']];
sum.getRange('B6:B11').formulas = [['=SUM(B17:B23)'], ['=SUM(C17:C23)'], ['=SUM(D17:D23)'], ['=B8*$H$8'], ['=B8*$H$9'], ['=B8*(1+$H$7)']];
sum.getRange('C6').formulas = [['=B6*$H$5']];
sum.getRange('C6:C11').fillDown();
sum.getRange('D6:D11').values = [['完整一期需求，非当前Demo已耗工时'], ['仅按可复用实现折抵，须经正式代码评审'], ['当前Demo到一期上线验收的新增投入'], ['使用顺利系数，未另加风险预留'], ['已包含不确定性，不再叠加风险预留'], ['基准加风险储备，用于内部资源预算']];
sum.getRange('G4:J4').values = [['可调整参数', '数值', '单位', '含义']];
header(sum, 'G4:J4');
sum.getRange('G5:J12').values = [
    ['每天工程工时', 8, '小时/人日', '人日与人时换算'],
    ['每周工作日', 5, '日/周', '不含周末和公共假期'],
    ['预算风险预留', 0.15, '比例', '接口与数据质量尚未确定'],
    ['顺利系数', 0.85, '倍', '需求清晰、数据可用、接口及时'],
    ['谨慎系数', 1.3, '倍', '外部依赖、额外修复与数据问题'],
    ['排期有效投入率', 0.8, '比例', '到岗时间中可用于估算任务的比例'],
    ['阶段依赖加成', 0.2, '比例', '设计先行、串行验收和交接损耗'],
    ['外部等待', 2, '周', '行方接口、网络和验收等待假设']
];
sum.getRange('H7:H7').setNumberFormat('0%');
sum.getRange('H10:H11').setNumberFormat('0%');
sum.getRange('H8:H9').setNumberFormat('0.00');
sum.getRange('H5:H12').format.fill = '#FFF3CF';
sum.getRange('A13').values = [['复用抵扣不是完成率。演示逻辑仍需正式数据、权限、版本审计、性能及验收验证。']];
sum.getRange('A15').values = [['按职责汇总及负载估算']];
sum.getRange('A15').format.font.bold = true;
sum.getRange('A16:H16').values = [['职责', '从零人日', '复用人日', '剩余人日', '剩余人时', '配置人数FTE', '负载下限(周)', '职责范围']];
header(sum, 'A16:H16');
const staff = [0.5, 1, 2, 0.5, 0.5, 1, 0.25];
const scope = ['需求/方案/工程评审', '后台配置和运营页面', '规则、服务、适配和占用', '模型、DDL、索引和迁移', '清洗、导入、回放和对账', '系统测试、联调及UAT', '环境、发布和运维文档'];
for (let i = 0; i < 7; i++) {
    const r = 17 + i;
    sum.getRange(`A${r}`).values = [[categories[i]]];
    sum.getRange(`B${r}:E${r}`).formulas = [[
        `=SUMIFS('工作量清单'!$I$6:$I$${end},'工作量清单'!$B$6:$B$${end},A${r})`,
        `=SUMIFS('工作量清单'!$J$6:$J$${end},'工作量清单'!$B$6:$B$${end},A${r})`,
        `=SUMIFS('工作量清单'!$K$6:$K$${end},'工作量清单'!$B$6:$B$${end},A${r})`,
        `=D${r}*$H$5`]];
    sum.getRange(`F${r}`).values = [[staff[i]]];
    sum.getRange(`G${r}`).formulas = [[`=IF(F${r}>0,D${r}/(F${r}*$H$6*$H$10),"未配置人员")`]];
    sum.getRange(`H${r}`).values = [[scope[i]]];
}
sum.getRange('F17:F23').format.fill = '#FFF3CF';
sum.getRange('A24').values = [['合计']];
for (const col of ['B', 'C', 'D', 'E', 'F']) sum.getRange(`${col}24`).formulas = [[`=SUM(${col}17:${col}23)`]];
sum.getRange('A24:H24').format.fill = '#EAF0F8';
sum.getRange('A24:H24').format.font.bold = true;
sum.getRange('A26:D26').values = [['排期指标', '周数', '月数(按4.33周)', '说明']];
header(sum, 'A26:D26');
sum.getRange('A27:A28').values = [['按职责负载的下限'], ['含阶段依赖及外部等待的初步排期']];
sum.getRange('B27:B28').formulas = [['=IF(COUNT(G17:G23)=7,MAX(G17:G23),"人员不足")'], ['=IF(ISNUMBER(B27),B27*(1+$H$11)+$H$12,"人员不足")']];
sum.getRange('C27:C28').formulas = [['=IF(ISNUMBER(B27),B27/4.33,"人员不足")'], ['=IF(ISNUMBER(B28),B28/4.33,"人员不足")']];
sum.getRange('D27:D28').values = [['按各职责投入量取最大值，不等同于承诺交付日期'], ['作为资源讨论用排期；接口到位及人员可并行仍需确认']];
sum.getRange('A30:D30').values = [['阶段', '建议顺序', '关键交付物', '退出条件']];
header(sum, 'A30:D30');
sum.getRange('A31:D36').values = [
    ['设计与数据盘点', '先行', '接口契约、数据库方案、数据源映射', '行方确认业务口径和样本可用'],
    ['配置与规则服务', '可并行', '全部配置页、规则及不可变版本', '固定用例和计算明细一致'],
    ['真实接口与账本', '可并行后汇合', '余额/汇率适配、占用幂等、取消重跑', '并发无重复占用且可恢复'],
    ['运营与报表', '依赖接口契约', 'S2/S3/S4、批量模拟、审计报表', '运营全链路验证通过'],
    ['跑数与UAT', '联调就绪后', '数据演练、历史回放、差异对账', '行方确认规则差异及验收'],
    ['投产与移交', '验收后', '最终导入、发布回滚、运维交接', '核对报告、发布记录及交接完成']
];
sum.getRange('A38').values = [['估算假设与范围']];
sum.getRange('A38').format.font.bold = true;
sum.getRange('A39').values = [['包含：BRD一期C1–C10、配置、运营审核对接、占用、模拟、报表、安全和投产工程工作。']];
sum.getRange('A40').values = [['复用：行方SSO、基础设施、支付队列、筛查、定价及最终双人复核流程；不重建这些系统。']];
sum.getRange('A41').values = [['不含：机器学习/AI代理、来账支付、报文发送后的拒绝退回、第三方采购、行方业务人员工时。']];
sum.getRange('A42').values = [['开发估时含组件级单元/接口自测；测试类单列系统回归、联调、并发性能与UAT，避免重复。']];
sum.getRange('A43').values = [['跑数假设：15个参考数据域、10000笔历史样本、3轮导入演练、2轮差异回放。数据量不是已确认事实。']];
sum.getRange('A44').values = [['性能目标：P99≤500ms、全部请求≤2s、100合作方及50000配置行。真实日交易量和10倍吞吐待确认。']];
sum.getRange('A45').values = [['图示S2/S3/S4完整字段、接口数量/质量、数据来源及归档标准需最终评审；额外需求另估。']];
sum.getRange('A46').values = [['估计为工程判断。未把AI生成速度作为人效标准，也未把Demo已写代码视为已通过正式验收。']];
sum.getRange('A47').values = [['公式：单项从零＝数量×单价；剩余＝MAX(从零－可复用抵扣,0)；人时＝剩余×每日工时。']];
sum.getRange('A48').values = [['蓝色浅黄底为可编辑输入；清单可调整数量、单价及复用抵扣，再查看汇总。单位单价均为人日。']];

detail.getRange('A2').values = [['一期工程工作量清单']];
detail.getRange('A2').format.font = {size: 15, bold: true};
detail.getRange('A3').values = [['依据：BRD_1.3c_RELAY_HSG_spec_v1.10_中文译文.md 第3、6–11、13–14章及2026-11-20当前Demo。估时和数据规模为工程假设。']];
detail.getRange('A4').values = [['每行只归属一个职责；自测包含在开发单价中。系统联调、数据对账和发布操作分别单列。']];
detail.getRange('A5:O5').values = [['编号', '职责', '模块', '工作内容及交付', '当前实现', '数量', '单位', '单价人日', '从零人日', '复用抵扣', '剩余人日', '顺利人日', '谨慎人日', '剩余人时', '依赖及验收口径']];
header(detail, 'A5:O5');
const grid = rows.map(x => [x.id, x.category, x.module, x.task, x.status, x.quantity, x.unit, x.rate, null, x.credit, null, null, null, null, x.note]);
detail.getRange(`A6:O${end}`).values = grid;
detail.getRange('I6').formulas = [['=F6*H6']];
detail.getRange(`I6:I${end}`).fillDown();
detail.getRange('K6:N6').formulas = [[`=IF(J6>I6,NA(),MAX(I6-J6,0))`, `=K6*'汇总与参数'!$H$8`, `=K6*'汇总与参数'!$H$9`, `=K6*'汇总与参数'!$H$5`]];
detail.getRange(`K6:N${end}`).fillDown();
const tr = end + 1;
detail.getRange(`A${tr}`).values = [['合计']];
for (const c of ['I', 'J', 'K', 'L', 'M', 'N']) detail.getRange(`${c}${tr}`).formulas = [[`=SUM(${c}6:${c}${end})`]];
detail.getRange(`A${tr}:O${tr}`).format.fill = '#EAF0F8';
detail.getRange(`A${tr}:O${tr}`).format.font.bold = true;
for (const range of [`F6:F${end}`, `H6:H${end}`, `J6:J${end}`]) {
    detail.getRange(range).format.fill = '#FFF3CF';
    detail.getRange(range).format.font.color = '#145AB5';
}
detail.getRange(`F6:F${end}`).setNumberFormat('0');
detail.getRange(`H6:M${tr}`).setNumberFormat('0.00');
detail.getRange(`N6:N${tr}`).setNumberFormat('0.0');
detail.getRange(`A6:O${end}`).format.rowHeight = 52;
detail.getRange(`C6:E${end}`).format.wrapText = true;
detail.getRange(`O6:O${end}`).format.wrapText = true;
detail.freezePanes.freezeRows(5);
detail.freezePanes.freezeColumns(3);
detail.tables.add(`A5:O${end}`, true, 'EffortItems');
detail.getRange(`F6:F${end}`).dataValidation = {rule: {type: 'decimal', operator: 'greaterThanOrEqual', formula1: 0}};
detail.getRange(`H6:J${end}`).dataValidation = {rule: {type: 'decimal', operator: 'greaterThanOrEqual', formula1: 0}};
detail.getRange(`J6:J${end}`).dataValidation = {
    rule: {
        type: 'decimal',
        operator: 'between',
        formula1: 0,
        formula2: 'I6'
    }
};
detail.getRange(`J6:J${end}`).conditionalFormats.addCustom('J6>I6', {fill: '#FDE8E8', font: {color: '#A92626'}});
sum.getRange('H5:H6').dataValidation = {rule: {type: 'whole', operator: 'between', formula1: 1, formula2: 24}};
sum.getRange('H6').dataValidation = {rule: {type: 'whole', operator: 'between', formula1: 1, formula2: 7}};
sum.getRange('H7:H7').dataValidation = {rule: {type: 'decimal', operator: 'between', formula1: 0, formula2: 1}};
sum.getRange('H8:H9').dataValidation = {rule: {type: 'decimal', operator: 'greaterThan', formula1: 0}};
sum.getRange('H10:H10').dataValidation = {rule: {type: 'decimal', operator: 'between', formula1: 0.01, formula2: 1}};
sum.getRange('H11').dataValidation = {rule: {type: 'decimal', operator: 'between', formula1: 0, formula2: 1}};
sum.getRange('H12').dataValidation = {rule: {type: 'decimal', operator: 'greaterThanOrEqual', formula1: 0}};
sum.getRange('F17:F23').dataValidation = {rule: {type: 'decimal', operator: 'greaterThanOrEqual', formula1: 0}};
sum.getRange('B6:B11').setNumberFormat('0.00');
sum.getRange('C6:C11').setNumberFormat('0.0');
sum.getRange('B17:D24').setNumberFormat('0.00');
sum.getRange('E17:E24').setNumberFormat('0.0');
sum.getRange('F17:F24').setNumberFormat('0.00');
sum.getRange('G17:G23').setNumberFormat('0.0');
sum.getRange('B27:C28').setNumberFormat('0.0');
sum.getRange('A5:D11').format.rowHeight = 30;
sum.getRange('A31:D36').format.rowHeight = 42;
sum.getRange('A31:D36').format.wrapText = true;
sum.getRange('A17:H23').format.rowHeight = 30;
sum.getRange('A27:D28').format.rowHeight = 42;
sum.getRange('A27:D28').format.wrapText = true;
const summaryWidths = {A: 29, B: 16, C: 16, D: 53, E: 17, F: 16, G: 23, H: 25, I: 13, J: 45};
for (const [c, w] of Object.entries(summaryWidths)) sum.getRange(`${c}1:${c}49`).format.columnWidth = w;
const detailWidths = {
    A: 7,
    B: 14,
    C: 24,
    D: 48,
    E: 31,
    F: 9,
    G: 14,
    H: 12,
    I: 13,
    J: 13,
    K: 13,
    L: 13,
    M: 13,
    N: 14,
    O: 52
};
for (const [c, w] of Object.entries(detailWidths)) detail.getRange(`${c}1:${c}${tr}`).format.columnWidth = w;

wb.recalculate();
console.log((await wb.inspect({
    kind: 'table',
    range: '汇总与参数!A5:D11',
    include: 'values,formulas',
    tableMaxRows: 10,
    tableMaxCols: 4,
    maxChars: 4000
})).ndjson);
console.log((await wb.inspect({
    kind: 'match',
    searchTerm: '#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!',
    options: {useRegex: true, maxResults: 50},
    maxChars: 2000
})).ndjson);
// Verify one editable rate propagates all the way to the summary, then restore it.
const prior = sum.getRange('B8').values[0][0];
const oldRate = detail.getRange('H6').values[0][0];
detail.getRange('H6').values = [[oldRate + 1]];
wb.recalculate();
const changed = sum.getRange('B8').values[0][0];
if (Math.abs(changed - prior - 1) > 1e-6) throw Error(`Input propagation failed: ${prior} -> ${changed}`);
detail.getRange('H6').values = [[oldRate]];
wb.recalculate();
const actual = sum.getRange('B8').values[0][0], expected = totals.reduce((s, x) => s + x.remaining, 0);
if (Math.abs(actual - expected) > 1e-6) throw Error('Independent sum mismatch');
for (const [sheet, range, name] of [['汇总与参数', 'A1:J28', 'summary-preview.png'], ['汇总与参数', 'A30:J48', 'scope-preview.png'], ['工作量清单', 'A1:O13', 'detail-preview.png'], ['工作量清单', `A47:O${tr}`, 'detail-tail-preview.png']]) {
    const p = await wb.render({sheetName: sheet, range, scale: 1.5, format: 'png'});
    await fs.writeFile(out + name, new Uint8Array(await p.arrayBuffer()));
}
const output = await SpreadsheetFile.exportXlsx(wb);
await output.save(out + 'HSG_RELAY_一期工作量估算_中级工程师.xlsx');
await fs.writeFile(out + 'estimate-totals.json', JSON.stringify({
    totals,
    remaining: actual,
    full: sum.getRange('B6').values[0][0],
    credit: sum.getRange('B7').values[0][0],
    budget: sum.getRange('B11').values[0][0],
    weeks: sum.getRange('B28').values[0][0]
}, null, 2));
