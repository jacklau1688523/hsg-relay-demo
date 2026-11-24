'use strict';
// 合作方详情：/provider-detail.html?code=合作方编码[&tab=appetites|usage|exclusions|approvals|restrictions|audit]
// 基础资料只读；准入规则、MTD、客户排除和个案审批在本页按合作方管理。
const state = {view: null, tags: [], tab: 'base', request: 0};
const params = new URLSearchParams(location.search);
const code = params.get('code');
const TABS = ['base', 'appetites', 'usage', 'exclusions', 'approvals', 'restrictions', 'audit'];

// 打开独立编辑页；带 tab 时直接切到对应页签
function editHref(tab) {
    return `/provider-edit.html?code=${encodeURIComponent(state.view.provider.code)}${tab ? `&tab=${tab}` : ''}`;
}

function pretty(text) {
    try {
        return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
        return text || '无';
    }
}

function fact(label, value) {
    return `<div class="fact"><span>${label}</span><b>${value}</b></div>`;
}

function info(label, value) {
    return `<div class="info"><span>${label}</span><b>${value}</b></div>`;
}

function reviewBadge(status) {
    return {OVERDUE: 'fail', DUE_SOON: 'pending', CURRENT: 'pass'}[status] || 'neutral';
}

function renderProfile(v) {
    const p = v.provider, live = p.status === 'LIVE';
    $('profile').innerHTML = `<div class="profile"><div><div class="profile-code">${esc(p.code)}</div>`
        + `<div class="profile-name">${esc(p.name)}</div>`
        + `<div class="badges"><span class="badge ${live ? 'pass' : 'pending'}">${live ? 'LIVE · 正式上线' : 'NOT_LIVE · 未上线'}</span>`
        + `<span class="badge neutral">${esc(types[type(p)] || p.providerType)}</span>`
        + `<span class="badge ${v.ready ? 'pass' : 'pending'}">上线准备${v.ready ? '已齐备' : '待完善'}</span>`
        + `<span class="badge ${reviewBadge(v.reviewStatus)}">${esc(reviewLabel(v.reviewStatus))}</span></div></div>`
        + `<div class="facts">${fact('报文通道', esc(p.channel))}${fact('BIC', esc(p.bic || '未设置'))}`
        + `${fact('排序优先级', esc(p.priority))}${fact('数据版本', esc(p.version))}</div></div>`;
}

function renderBase(v) {
    const p = v.provider, live = p.status === 'LIVE';
    const tags = v.capabilityTags.map(c => {
        const t = state.tags.find(x => x.tagCode === c);
        return `<span class="chip">${esc(c)}${t ? ` · ${esc(t.tagName)}` : ''}</span>`;
    }).join('');
    $('baseView').innerHTML = `<div class="info-grid">`
        + info('合作方编码', esc(p.code))
        + info('合作方名称', esc(p.name))
        + info('合作方类型', `${esc(types[type(p)] || p.providerType)}${p.providerType === 'PAYMENT_NETWORK' ? ' <span class="muted">（原始值 PAYMENT_NETWORK，保存后为 NETWORK）</span>' : ''}`)
        + info('上线状态', `<span class="badge ${live ? 'pass' : 'pending'}">${esc(p.status)}</span> ${live ? '正式上线，参与正式路由' : '未上线，仅可参与模拟'}`)
        + info('报文通道', esc(p.channel))
        + info('BIC', esc(p.bic || '未设置'))
        + info('C4 资金校验', p.fundingCheckEnabled ? 'ON · 校验往来账余额' : 'OFF · 不校验往来账余额')
        + info('排序优先级', `${esc(p.priority)} <span class="muted">1–99，小值优先${p.priority > 99 ? '；旧数据超出范围，保存后按 99 规范化' : ''}</span>`)
        + info('对接负责人', esc(p.relationshipOwner || '未设置'))
        + info('准入复核提醒日期', `${p.appetiteReviewDate ? esc(p.appetiteReviewDate) : '未设置'} <span class="muted">${esc(reviewLabel(v.reviewStatus))}</span>`)
        + info('HSG 往来账号', esc(p.nostroAccount || '未设置'))
        + info('HSG 总账编码', esc(p.glCode || '未设置'))
        + `</div>`
        + `<div class="subheading">C2 监管能力标签 · ${v.capabilityTags.length} 项</div>`
        + `<div class="tag-grid">${tags || '<span class="muted">未设置能力标签</span>'}</div>`
        + `<div class="notice">监管条件命中时，合作方必须具备规则要求的全部标签。优先级仅在总分、成本和起息日相同时参与排序；状态、资金开关、通道及优先级在下次路由试算生效。</div>`;
}

// 准入规则配置：只显示当前合作方的规则，表单与启停操作均在此页。
let appetiteRows = [];
function renderAppetites(rows) {
    appetiteRows = rows;
    const when = text => String(text ?? '').replace('T',' ').slice(0,16) || '—';
    const on = rows.filter(a => a.enabled).length;
    const list = rows.length ? rows.map(a => `<tr class="appetite"><td>#${esc(a.id)}</td><td>${esc(a.flowCategory)}</td><td>${esc(a.currency)}</td><td>${esc(a.bankCountry)}</td><td><span class="badge ${esc(a.action)}">${esc(a.action)}</span></td><td>${a.monthlyCapUsd == null ? '—' : esc(Number(a.monthlyCapUsd).toLocaleString('en-US'))}</td><td>${esc(when(a.effectiveFrom))}</td><td>${a.enabled ? '<span class="badge pass">启用</span>' : '<span class="badge pending">停用</span>'}</td><td><button type="button" class="btn secondary tiny" data-appetite-edit="${a.id}">编辑</button> <button type="button" class="btn secondary tiny" data-appetite-toggle="${a.id}">${a.enabled?'停用':'启用'}</button> <a href="/?rule=${a.id}" class="btn secondary tiny">条件</a></td></tr><tr class="appetite-more"><td colspan="9">${esc(`失效时间 ${when(a.effectiveTo)} · 证据文档号 ${a.evidenceDocNo ?? '—'} · 下次复核 ${a.nextReviewDate ?? '—'} · 版本 ${a.versionNo ?? '—'} · 备注 ${a.remark || '—'}`)}</td></tr>`).join('') : '<tr><td colspan="9">该合作方暂无准入规则。</td></tr>';
    $('appetitesView').innerHTML = `<div class="rulebar"><div class="ptitle" style="margin:0">${esc(code)} · 准入规则配置 · ${rows.length} 条${rows.length ? `（启用 ${on} · 停用 ${rows.length-on}）` : ''}</div><button type="button" class="btn" data-appetite-new>＋ 新增准入规则</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>规则ID</th><th>流转类别</th><th>币种</th><th>受益行国家</th><th>准入方式</th><th>月度上限 USD</th><th>生效时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${list}</tbody></table></div>`
      + `<div class="notice">币种、国家为 ALL 时匹配全部；同范围且生效期重叠的规则不能同时启用。附加条件在 C1 页面配置。无命中的流转类别默认拒绝。</div><div id="appetitesPageError" class="err" role="alert"></div>`;
}

// MTD 使用情况：列表与调整表单都在本页，按「流转类别＋月份」覆盖写入（PUT /api/usage），不再跳转 MTD 模拟数据页
const USAGE_FLOWS = ['STANDARD', 'PSP', 'WEB3'], MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
let usageRows = [], usageRules = [], usageForm = null, usageGood = '';
const usageMonth = () => hkDateTime().slice(0, 7);
const usageMoney = v => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: 2});
const usageExisting = f => usageRows.find(x => x.flowCategory === f.flowCategory && x.monthKey === f.monthKey) || null;
const usageTitle = f => `${usageExisting(f) ? '调整' : '新增'} MTD · ${code} / ${f.flowCategory} / ${f.monthKey}`;
// 表单初始值与「重置」：带出该流转类别＋月份已保存的金额，没有记录则为空
const usageReset = f => ({flowCategory: f.flowCategory, monthKey: f.monthKey, amountUsd: String(usageExisting(f)?.amountUsd ?? '')});

// 与 C1Service.active 口径一致：已启用、已到生效时间、未过失效时间
function usageRuled(flow) {
    const now = hkDateTime(0, 19);
    return usageRules.filter(r => r.flowCategory === flow && r.enabled && !!r.effectiveFrom && String(r.effectiveFrom).slice(0, 19) <= now && (!r.effectiveTo || now < String(r.effectiveTo).slice(0, 19)));
}
// 月度上限：当前合作方＋流转类别下生效 ACCEPT 规则的月度上限（升序）。实际命中哪条规则还取决于币种 / 受益行国家 / 附加条件
function usageCaps(flow) {
    return [...new Set(usageRuled(flow).filter(r => r.action === 'ACCEPT' && r.monthlyCapUsd != null).map(r => Number(r.monthlyCapUsd)).filter(n => !isNaN(n)))].sort((a, b) => a - b);
}

// 表单下方的提示：记录是否已存在、月度上限、按当前输入保存后的当月剩余额度、非当月提醒
function usageHint(f) {
    const row = usageExisting(f), caps = usageCaps(f.flowCategory), amount = Number(f.amountUsd);
    const preview = caps.length && f.monthKey === usageMonth() && f.amountUsd !== '' && amount >= 0 ? caps[0] - amount : null;
    return [
        row ? `记录已存在（id ${row.id}，当前 ${usageMoney(row.amountUsd)} USD），保存将覆盖为新的月累计金额。` : '该流转类别＋月份尚无记录，保存将新增一行。',
        caps.length ? `生效 ACCEPT 规则的月度上限：${caps.map(usageMoney).join(' / ')} USD。` : '该流转类别没有设置月度上限的生效 ACCEPT 规则，MTD 不影响 C1 结果。',
        preview == null ? '' : `按当前输入保存后，当月剩余额度为 ${usageMoney(preview)} USD${preview < 0 ? '（下一笔即超限）' : ''}。`,
        f.monthKey === usageMonth() ? '' : '注意：非当月记录不参与 C1 校验。'
    ].join('');
}

function renderUsage({rows, rules}) {
    usageRows = rows; usageRules = rules;
    const month = usageMonth();
    if (!usageForm) usageForm = usageReset({flowCategory: 'STANDARD', monthKey: month});
    const f = usageForm, existing = !!usageExisting(f);
    const flows = [...new Set([...USAGE_FLOWS, ...rows.map(x => x.flowCategory)])];
    const list = rows.slice().sort((a, b) => b.monthKey.localeCompare(a.monthKey) || a.flowCategory.localeCompare(b.flowCategory)).map(x => {
        const caps = usageCaps(x.flowCategory), current = x.monthKey === month, remaining = current && caps.length ? caps[0] - Number(x.amountUsd) : null;
        return `<tr><td>${esc(x.flowCategory)}</td><td>${esc(x.monthKey)} ${current ? '<span class="badge pass">当月 · C1 读取</span>' : '<span class="muted">不参与校验</span>'}</td>`
          + `<td>${esc(usageMoney(x.amountUsd))}</td><td>${caps.length > 1 ? esc(`${usageMoney(caps[0])} ~ ${usageMoney(caps[caps.length - 1])}`) : caps.length ? esc(usageMoney(caps[0])) : `<span class="muted">${usageRuled(x.flowCategory).length ? '未设上限' : '未配置准入规则'}</span>`}</td>`
          + `<td>${remaining == null ? '—' : `${esc(usageMoney(remaining))}${remaining < 0 ? ' <span class="badge fail">已超限</span>' : ''}`}</td>`
          + `<td><button type="button" class="btn secondary tiny" data-usage-edit="${x.id}">调整</button></td></tr>`;
    }).join('');
    $('usageView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · MTD 使用情况 · ${rows.length} 条</h2><div class="muted">C1 只读取香港当月（${month}）的合作方、流转类别累计金额；模拟校验不占用 MTD。</div></div></div>`
      + `<form id="usageForm" class="panel" style="background:#f8fbff" novalidate><div class="rulebar"><h2 id="usageFormTitle" style="margin:0">${esc(usageTitle(f))}</h2><span class="badge pending">仅影响 C1 模拟校验</span></div><div class="layout3">`
      + `<div class="field"><label>流转类别 *</label><select name="flowCategory">${flows.map(v => `<option value="${esc(v)}"${v === f.flowCategory ? ' selected' : ''}>${v === 'STANDARD' ? 'STANDARD · 标准流转' : esc(v)}</option>`).join('')}</select></div>`
      + `<div class="field"><label>月份（HKT）*</label><input name="monthKey" type="month" value="${esc(f.monthKey)}" required></div>`
      + `<div class="field"><label>月累计金额 USD *（覆盖写入）</label><input name="amountUsd" type="number" min="0" step="0.01" value="${esc(f.amountUsd)}" placeholder="请输入月累计金额" required></div></div>`
      + `<div class="muted" id="usageHint" style="margin-top:10px">${esc(usageHint(f))}</div>`
      + `<div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存 MTD</button><button type="button" class="btn secondary" name="delete"${existing ? '' : ' disabled'}>删除此行</button>`
      + `<button type="button" class="btn secondary" name="reset">重置</button><button type="button" class="btn secondary" name="sample" value="near">示例：接近月度上限</button><button type="button" class="btn secondary" name="sample" value="zero">示例：清零</button></div>`
      + `<div class="err" id="usageError" role="alert"></div><div class="good" id="usageGood" role="status">${esc(usageGood)}</div></form>`
      + `<div class="tablewrap"><table><thead><tr><th>流转类别</th><th>月份（HKT）</th><th>月累计金额 USD</th><th>月度上限 USD</th><th>当月剩余额度</th><th>操作</th></tr></thead><tbody>${list || '<tr><td colspan="6">该合作方暂无 MTD 记录，可在上方表单新增。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">同一流转类别＋月份仅一行，保存即覆盖。月度上限取自「准入规则配置」中生效的 ACCEPT 规则（实际命中哪条还取决于币种、受益行国家与附加条件）；当月剩余额度＝最低上限 − MTD，仅当月记录显示。保存后可到 <a href="/">C1 业务准入</a> 的「规则模拟校验」查看效果。</div>`;
    // 不支持 type=month 的浏览器退化为文本框，提交前按同一格式校验
    const monthInput = $('usageForm').elements.monthKey;
    if (monthInput.type !== 'month') { monthInput.type = 'text'; monthInput.placeholder = 'YYYY-MM'; }
}

function hkDateTime(offsetDays = 0, length = 16) {
    return new Date(Date.now() + offsetDays * 86400000).toLocaleString('sv-SE', {timeZone: 'Asia/Hong_Kong', hour12: false}).replace(' ', 'T').slice(0, length);
}

function clientOptions(clients) {
    return clients.map(c => `<option value="${esc(c.clientId)}">${esc(c.clientId)} · ${esc(c.classificationStatus)}</option>`).join('');
}

function renderExclusions({rows, clients}) {
    const now = hkDateTime();
    $('exclusionsView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · 客户排除管理</h2><div class="muted">合规维护的「客户 + 合作方」排除记录；解除后保留历史与审计。</div></div></div>`
        + `<form id="exclusionForm" class="panel" style="background:#f8fbff"><div class="layout3"><div class="field"><label>客户 *</label><select name="clientId" required>${clientOptions(clients)}</select></div><div class="field"><label>排除原因 *</label><input name="reason" required maxlength="512" placeholder="合规复核原因"></div><div class="field"><label>生效时间 HKT *</label><input name="effectiveFrom" type="datetime-local" value="${hkDateTime()}" required></div><div class="field"><label>截止时间 HKT</label><input name="effectiveTo" type="datetime-local"></div></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">＋ 新增排除</button></div></form>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>客户</th><th>排除原因</th><th>生效时间</th><th>截止时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(x => {const active=x.effectiveFrom<=now&&(!x.effectiveTo||x.effectiveTo>now);return `<tr><td>#${x.id}</td><td>${esc(x.clientId)}</td><td>${esc(x.reason)}</td><td>${esc(x.effectiveFrom)}</td><td>${esc(x.effectiveTo||'—')}</td><td>${active?'<span class="badge fail">有效</span>':'已结束 / 未生效'}</td><td>${active?`<button type="button" class="btn secondary tiny" data-release="${x.id}">解除</button>`:'—'}</td></tr>`;}).join('')||'<tr><td colspan="7">当前合作方暂无客户排除记录</td></tr>'}</tbody></table></div><div class="err" id="exclusionError" role="alert"></div><div class="good" id="exclusionGood" role="status"></div>`;
}

function renderApprovals({rows, clients}) {
    const now = hkDateTime();
    $('approvalsView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · 个案审批管理</h2><div class="muted">审批仅适用于当前合作方、指定客户与流转类别。</div></div></div>`
        + `<form id="approvalForm" class="panel" style="background:#f8fbff"><div class="layout3"><div class="field"><label>客户 *</label><select name="clientId" required>${clientOptions(clients)}</select></div><div class="field"><label>流转类别 *</label><select name="flowCategory"><option>STANDARD</option><option>PSP</option><option>WEB3</option></select></div><div class="field"><label>审批参考号 *</label><input name="approvalReference" required maxlength="128" placeholder="APR-2026-001"></div><div class="field"><label>生效时间 HKT *</label><input name="effectiveFrom" type="datetime-local" value="${hkDateTime()}" required></div><div class="field"><label>过期时间 HKT *</label><input name="expiresAt" type="datetime-local" value="${hkDateTime(30)}" required></div></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">＋ 新增审批</button></div></form>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>客户</th><th>流转类别</th><th>审批编号</th><th>生效时间</th><th>失效时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(x => {const active=x.effectiveFrom<=now&&x.expiresAt>now;return `<tr><td>#${x.id}</td><td>${esc(x.clientId)}</td><td>${esc(x.flowCategory)}</td><td>${esc(x.approvalReference)}</td><td>${esc(x.effectiveFrom)}</td><td>${esc(x.expiresAt)}</td><td>${active?'<span class="badge pass">有效</span>':'已失效 / 未生效'}</td><td>${active?`<button type="button" class="btn secondary tiny" data-revoke="${x.id}">撤销</button>`:'—'}</td></tr>`;}).join('')||'<tr><td colspan="8">当前合作方暂无个案审批记录</td></tr>'}</tbody></table></div><div class="err" id="approvalError" role="alert"></div><div class="good" id="approvalGood" role="status"></div>`;
}

function renderRestrictions(rows) {
    const list = rows.length ? rows.map(r => {
        const [label, reason] = restrictTypes[r.restrictionType] || [];
        return `<tr><td>#${r.id}</td><td title="${esc(r.restrictionType)}">${esc(label || r.restrictionType)}<br><span class="muted">${esc(reason || '')}</span></td>`
            + `<td>${esc(r.blockedValues)}</td><td>${esc((r.effectiveFrom || '').replace('T', ' ').slice(0, 16))}</td>`
            + `<td>${esc((r.effectiveTo || '—').replace('T', ' ').slice(0, 16))}</td><td>v${r.versionNo}</td>`
            + `<td>${r.enabled ? '<span class="badge pass">启用</span>' : '<span class="badge pending">停用</span>'}</td>`
            + `<td>${esc(r.remark || '—')}</td></tr>`;
    }).join('') : '<tr><td colspan="8">该合作方暂无限制记录。</td></tr>';
    $('restrictionsView').innerHTML = `<div class="rulebar"><div class="ptitle" style="margin:0">${esc(code)} · 合作方限制（制裁及监管屏蔽）· ${rows.length} 条</div>`
        + `<a class="btn secondary tiny" href="${editHref('restrictions')}">前往维护 ↗</a></div>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>限制类型</th><th>屏蔽值</th><th>生效时间</th><th>失效时间</th><th>版本</th><th>状态</th><th>备注</th></tr></thead>`
        + `<tbody>${list}</tbody></table></div>`
        + `<div class="notice">合作方限制按合作方独立配置，C2 校验实时读取：受益行国家/地址国家、客户国家/发起方国家、扣账币种/交易币种分别采用 OR 匹配；董事及 UBO 国籍遍历全部值；中间行为空时不触发该项。</div>`;
}

function renderAudit(data) {
    $('auditView').innerHTML = data.length ? data.map(a => `<div class="trace"><b>${esc(a.operation)}</b> ${esc(a.operatedAt?.replace('T', ' '))} · ${esc(a.operatorName)} · ${esc(a.objectType)}`
        + `<details style="margin-top:10px"><summary>查看变更前后</summary><div class="subheading">变更前</div><pre class="code">${esc(pretty(a.beforeJson))}</pre>`
        + `<div class="subheading">变更后</div><pre class="code">${esc(pretty(a.afterJson))}</pre></details></div>`).join('')
        : '<div class="notice">暂无合作方基础信息或能力标签变更记录。</div>';
}

// 各页签的数据来源：准入规则、合作方限制取列表后按合作方筛选；变更记录取合作方管理的聚合接口；
// MTD 取全部记录按合作方筛选，并取本合作方的准入规则用于显示月度上限与当月剩余额度
const LOAD = {
    appetites: () => api(`/appetites?providerCode=${encodeURIComponent(code)}`),
    usage: async () => { const [rows, rules] = await Promise.all([api('/usage'), api(`/appetites?providerCode=${encodeURIComponent(code)}`)]); return {rows: rows.filter(x => x.providerCode === code), rules}; },
    exclusions: async () => { const [rows, clients] = await Promise.all([api(`/exclusions?providerCode=${encodeURIComponent(code)}`), api('/clients')]); return {rows, clients}; },
    approvals: async () => { const [rows, clients] = await Promise.all([api(`/approvals?providerCode=${encodeURIComponent(code)}`), api('/clients')]); return {rows, clients}; },
    restrictions: async () => (await api('/c2/restrictions')).filter(r => r.providerCode === code),
    audit: () => api(`/provider-management/${encodeURIComponent(code)}/audit`)
};
const RENDER = {appetites: renderAppetites, usage: renderUsage, exclusions: renderExclusions, approvals: renderApprovals, restrictions: renderRestrictions, audit: renderAudit};

// silent：重读期间保留已显示的内容，不先换成「正在加载…」
async function activate(tab, silent = false) {
    state.tab = tab;
    document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('sel', b.dataset.tab === tab));
    for (const id of TABS) $(id + 'View').classList.toggle('hidden', id !== tab);
    if (tab === 'base') return;
    const container = $(tab + 'View'), token = ++state.request;
    if (!silent) container.innerHTML = '<p class="muted">正在加载…</p>';
    try {
        const data = await LOAD[tab]();
        if (token !== state.request) return;
        RENDER[tab](data);
    } catch (e) {
        if (token === state.request) container.innerHTML = `<p class="err">${esc(e.message)}</p>`;
    }
}

async function load() {
    if (!code) throw Error('缺少合作方编码（code），请从合作方管理列表点击「详情」进入。');
    const [view, tags] = await Promise.all([api(`/provider-management/${encodeURIComponent(code)}`), api('/c2/capability-tags')]);
    state.view = view;
    state.tags = tags;
    const p = view.provider;
    renderProfile(view);
    renderBase(view);
    renderReadiness(view.checks);
    $('reviewInfo').textContent = reviewText(p.appetiteReviewDate, p.relationshipOwner);
    $('edit').href = editHref();
    $('simulate').href = `/routing.html?includeProvider=${encodeURIComponent(code)}`;
}

// quiet：不显示「正在加载…」，用于浏览器「后退」恢复页面时的静默重读
async function show(quiet = false) {
    $('pageError').textContent = '';
    if (!quiet) $('loading').hidden = false;
    try {
        await load();
        $('loading').hidden = true;
        $('detail').classList.remove('hidden');
        for (const id of ['edit', 'simulate']) $(id).style.display = '';
        await activate(state.tab, quiet);
    } catch (e) {
        $('loading').hidden = true;
        $('detail').classList.add('hidden');
        for (const id of ['edit', 'simulate']) $(id).style.display = 'none';
        $('pageError').textContent = e.message;
    }
}

document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => activate(b.dataset.tab));
let providerAppetiteId = null;
function appetiteSelect(name, label, values, selected) {
    return `<div class="field"><label>${label} *</label><select name="${name}">${values.map(v=>`<option value="${esc(v)}" ${v===selected?'selected':''}>${esc(v)}</option>`).join('')}</select></div>`;
}
function appetiteInput(name, label, value='', type='text', required=true, extra='') {
    return `<div class="field"><label>${label}${required?' *':''}</label><input name="${name}" type="${type}" value="${esc(value)}" ${required?'required':''} ${extra}></div>`;
}
function openProviderAppetite(id) {
    const row=appetiteRows.find(x=>x.id===id)||{};providerAppetiteId=id||null;
    $('appetiteModalTitle').textContent=id?`编辑准入规则 #${id}`:`${code} · 新增准入规则`;
    const flows=[...new Set(['STANDARD','PSP','WEB3',...appetiteRows.map(x=>x.flowCategory)])];
    const currencies=[...new Set(['ALL','USD','HKD','CNH','EUR','AUD',...appetiteRows.map(x=>x.currency)])];
    const countries=[...new Set(['ALL','CN','HK','US','KG','SG',...appetiteRows.map(x=>x.bankCountry)])];
    $('appetiteFields').innerHTML=`<div class="notice">所属合作方：<b>${esc(code)}</b>。附加条件保存后可从列表“条件”入口配置。</div><div class="layout3" style="margin-top:14px">`
      +appetiteSelect('flowCategory','流转类别',flows,row.flowCategory||'STANDARD')
      +appetiteSelect('currency','交易币种',currencies,row.currency||'ALL')
      +appetiteSelect('bankCountry','受益行国家',countries,row.bankCountry||'ALL')
      +appetiteSelect('action','准入方式',['ACCEPT','EXCLUDE','CASE_BY_CASE'],row.action||'ACCEPT')
      +appetiteInput('monthlyCapUsd','月度上限 USD',row.monthlyCapUsd??'','number',false,'min="0" step="0.01"')
      +appetiteInput('effectiveFrom','生效时间 HKT',row.effectiveFrom?.slice(0,16)||hkDateTime(),'datetime-local')
      +appetiteInput('effectiveTo','失效时间 HKT',row.effectiveTo?.slice(0,16)||'','datetime-local',false)
      +appetiteInput('evidenceDocNo','证据文档号',row.evidenceDocNo||`APP-${code}-${hkDateTime().slice(0,10)}`)
      +appetiteInput('nextReviewDate','下次复核日期',row.nextReviewDate||hkDateTime(365).slice(0,10),'date')
      +appetiteInput('remark','备注',row.remark||'','text',false)+'</div>';
    const action=$('appetiteForm').querySelector('[name="action"]'),cap=$('appetiteForm').querySelector('[name="monthlyCapUsd"]');
    action.onchange=()=>{cap.disabled=action.value!=='ACCEPT';};action.onchange();
    $('appetiteError').textContent='';$('appetiteModal').classList.add('show');
}
function closeProviderAppetite(){$('appetiteModal').classList.remove('show');providerAppetiteId=null;}
$('appetitesView').addEventListener('click',async e=>{
    if(e.target.closest('[data-appetite-new]')){openProviderAppetite();return;}
    const edit=e.target.closest('[data-appetite-edit]');if(edit){openProviderAppetite(Number(edit.dataset.appetiteEdit));return;}
    const toggle=e.target.closest('[data-appetite-toggle]');if(!toggle)return;
    const id=Number(toggle.dataset.appetiteToggle);if(!confirm(`确定切换准入规则 #${id} 的启用状态？`))return;
    try{await api(`/appetites/${id}/toggle`,{method:'POST'});await show(true);}catch(error){$('appetitesPageError').textContent=error.message;}
});
$('appetiteForm').addEventListener('submit',async e=>{
    e.preventDefault();const data=Object.fromEntries(new FormData(e.target).entries());
    for(const key of Object.keys(data))if(data[key]==='')data[key]=null;
    data.providerCode=code;data.enabled=providerAppetiteId?appetiteRows.find(x=>x.id===providerAppetiteId).enabled:true;
    if(providerAppetiteId)data.id=providerAppetiteId;
    $('appetiteError').textContent='';
    try{await api('/appetites',{method:'POST',body:JSON.stringify(data)});closeProviderAppetite();await show(true);}
    catch(error){$('appetiteError').textContent=error.message;}
});
async function saveProviderRecord(kind, form) {
    const data = Object.fromEntries(new FormData(form).entries());
    data.providerCode = code;
    for (const key of Object.keys(data)) if (data[key] === '') data[key] = null;
    const error = $(kind === 'exclusions' ? 'exclusionError' : 'approvalError');
    error.textContent = '';
    if (kind === 'exclusions' && data.effectiveTo && data.effectiveTo <= data.effectiveFrom) {error.textContent = '截止时间须晚于生效时间';return;}
    try { await api(kind === 'exclusions' ? '/exclusions' : '/approvals', {method:'POST', body:JSON.stringify(data)}); await activate(kind); }
    catch (e) { error.textContent = e.message; }
}
for (const kind of ['exclusions', 'approvals']) {
    $(kind + 'View').addEventListener('submit', e => {e.preventDefault(); saveProviderRecord(kind, e.target);});
}
$('exclusionsView').addEventListener('click', async e => {
    const id = e.target.closest('[data-release]')?.dataset.release;
    if (!id || !confirm('确定解除这条客户排除？历史记录会保留。')) return;
    try { await api(`/exclusions/${id}/release`, {method:'POST'}); await activate('exclusions'); }
    catch (error) { $('exclusionError').textContent = error.message; }
});
$('approvalsView').addEventListener('click', async e => {
    const id = e.target.closest('[data-revoke]')?.dataset.revoke;
    if (!id || !confirm('确定撤销这条个案审批？历史记录会保留。')) return;
    try { await api(`/approvals/${id}/revoke`, {method:'POST'}); await activate('approvals'); }
    catch (error) { $('approvalError').textContent = error.message; }
});
// MTD 表单：读取当前输入；任一字段变化后刷新标题、提示与「删除此行」的可用状态
function usageRead(form) {
    return {flowCategory: form.elements.flowCategory.value, monthKey: form.elements.monthKey.value.trim(), amountUsd: form.elements.amountUsd.value.trim()};
}
function syncUsageForm(form) {
    usageForm = usageRead(form);
    $('usageFormTitle').textContent = usageTitle(usageForm);
    $('usageHint').textContent = usageHint(usageForm);
    form.elements.namedItem('delete').disabled = !usageExisting(usageForm);
}
const onUsageInput = e => { const form = e.target.closest('#usageForm'); if (form) syncUsageForm(form); };
$('usageView').addEventListener('input', onUsageInput);
$('usageView').addEventListener('change', onUsageInput);
$('usageView').addEventListener('submit', async e => {
    if (e.target.id !== 'usageForm') return;
    e.preventDefault();
    const form = e.target, f = usageRead(form), error = $('usageError'), save = form.querySelector('[type="submit"]');
    error.textContent = ''; usageGood = ''; $('usageGood').textContent = '';
    if (!MONTH_PATTERN.test(f.monthKey)) { error.textContent = f.monthKey ? '月份格式应为 YYYY-MM' : '请选择月份'; return; }
    // 留空须单独拦：Number('') 是 0，否则会把已有记录覆盖成 0
    if (f.amountUsd === '' || !(Number(f.amountUsd) >= 0)) { error.textContent = '月累计金额必须为不小于 0 的数字'; return; }
    save.disabled = true;
    try {
        const existed = !!usageExisting(f);
        const saved = await api('/usage', {method: 'PUT', body: JSON.stringify({providerCode: code, flowCategory: f.flowCategory, monthKey: f.monthKey, amountUsd: Number(f.amountUsd)})});
        usageForm = {flowCategory: saved.flowCategory, monthKey: saved.monthKey, amountUsd: String(saved.amountUsd)};
        usageGood = `已${existed ? '覆盖' : '新增'} ${saved.flowCategory} / ${saved.monthKey} 的 MTD = ${usageMoney(saved.amountUsd)} USD（id ${saved.id}）。`;
        await activate('usage', true);
    } catch (err) { error.textContent = err.message; }
    finally { save.disabled = false; }
});
$('usageView').addEventListener('click', async e => {
    const form = e.target.closest('#usageForm'), error = $('usageError');
    const remove = e.target.closest('[name="delete"]');
    if (form && remove) {
        const f = usageRead(form), row = usageExisting(f);
        if (!row || !confirm(`确定删除 ${code} / ${row.flowCategory} / ${row.monthKey} 的 MTD 记录？删除后该组合按 0 参与校验。`)) return;
        error.textContent = ''; usageGood = ''; $('usageGood').textContent = '';
        remove.disabled = true;
        try {
            await api(`/usage/${row.id}`, {method: 'DELETE'});
            // 金额直接置空：此时列表仍是删除前的数据，usageReset 会把刚删掉的金额再带出来
            usageForm = {...f, amountUsd: ''};
            usageGood = `已删除 ${row.flowCategory} / ${row.monthKey} 的 MTD 记录（id ${row.id}）。`;
            await activate('usage', true);
        } catch (err) { error.textContent = err.message; remove.disabled = false; }
        return;
    }
    if (form && e.target.closest('[name="reset"]')) {
        usageForm = usageReset(usageRead(form));
        usageGood = '';
        renderUsage({rows: usageRows, rules: usageRules});
        return;
    }
    const sample = e.target.closest('[name="sample"]');
    if (form && sample) {
        error.textContent = ''; usageGood = ''; $('usageGood').textContent = '';
        if (sample.value === 'zero') form.elements.amountUsd.value = 0;
        else {
            const caps = usageCaps(form.elements.flowCategory.value);
            if (!caps.length) { error.textContent = `${code} / ${form.elements.flowCategory.value} 没有设置月度上限的生效 ACCEPT 规则，无法演示超限。`; return; }
            // 只有当月记录参与 C1 校验：上限 − 100,000，再到 C1 模拟一笔 150,000 即 APPETITE_CAP_REACHED
            form.elements.monthKey.value = usageMonth();
            form.elements.amountUsd.value = Math.max(0, caps[0] - 100000);
        }
        syncUsageForm(form);
        return;
    }
    // 列表「调整」：把该行带入本页表单
    const edit = e.target.closest('[data-usage-edit]');
    if (!edit) return;
    const row = usageRows.find(x => x.id === Number(edit.dataset.usageEdit));
    if (!row) return;
    usageForm = {flowCategory: row.flowCategory, monthKey: row.monthKey, amountUsd: String(row.amountUsd)};
    usageGood = '';
    renderUsage({rows: usageRows, rules: usageRules});
    $('usageForm').scrollIntoView({behavior: 'smooth', block: 'start'});
});
$('refresh').onclick = () => show();
const initial = params.get('tab');
state.tab = TABS.includes(initial) ? initial : 'base';
reloadIfChangedSinceLoad();
// 浏览器「后退」回到本页（如到 C1 业务准入页维护后返回）时，页面来自往返缓存、脚本不会重新执行，看到的还是离开时的数据：
// 静默重读一次，让概要、上线准备检查和当前页签都回到最新。编辑页保存后的整页刷新仍由上面的 reloadIfChangedSinceLoad 负责。
window.addEventListener('pageshow', e => {
    if (e.persisted) show(true);
});
show();
