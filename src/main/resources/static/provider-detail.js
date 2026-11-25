'use strict';
// 合作方详情：/provider-detail.html?code=合作方编码[&tab=appetites|usage|exclusions|approvals|restrictions|audit]
// 基础资料只读；准入规则、MTD、客户排除和个案审批在本页按合作方管理。
const state = {view: null, tags: [], tab: 'base', request: 0};
const params = new URLSearchParams(location.search);
const code = params.get('code');
const TABS = ['base', 'appetites', 'usage', 'balances', 'reaches', 'limits', 'fees', 'preferences', 'agreements', 'schedules', 'exclusions', 'approvals', 'restrictions', 'audit'];

// 基础资料在独立编辑页维护
function editHref() {
    return `/provider-edit.html?code=${encodeURIComponent(state.view.provider.code)}`;
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
    $('usageView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · MTD 使用情况 · ${rows.length} 条</h2><div class="muted">C1 只读取香港当月（${month}）的合作方、流转类别累计金额；模拟校验不占用 MTD。</div></div><button type="button" class="btn" data-usage-new>＋ 新增 MTD</button></div>`
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
    prepareTabEditor('usageView', 'usageForm', 'MTD 使用情况');
}

function hkDateTime(offsetDays = 0, length = 16) {
    return new Date(Date.now() + offsetDays * 86400000).toLocaleString('sv-SE', {timeZone: 'Asia/Hong_Kong', hour12: false}).replace(' ', 'T').slice(0, length);
}

function clientOptions(clients) {
    return clients.map(c => `<option value="${esc(c.clientId)}">${esc(c.clientId)} · ${esc(c.classificationStatus)}</option>`).join('');
}

function prepareTabEditor(viewId, contentId, title) {
    const view = $(viewId), content = $(contentId);
    const backdrop = document.createElement('div');
    backdrop.className = 'modalback';
    backdrop.dataset.tabEditor = contentId;
    backdrop.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="rowbetween"><h2>${esc(title)}</h2><button type="button" class="btn secondary tiny" data-editor-close>✕ 关闭</button></div></div>`;
    backdrop.firstElementChild.append(content);
    view.append(backdrop);
    backdrop.addEventListener('click', e => { if (e.target === backdrop || e.target.closest('[data-editor-close]')) closeTabEditor(contentId); });
}
function openTabEditor(contentId) {
    const backdrop = $(contentId).closest('.modalback');
    if (backdrop) backdrop.classList.add('show');
}
function closeTabEditor(contentId) {
    const backdrop = $(contentId)?.closest('.modalback');
    if (backdrop) backdrop.classList.remove('show');
}
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') document.querySelectorAll('[data-tab-editor].show').forEach(backdrop => backdrop.classList.remove('show'));
});

function renderExclusions({rows, clients}) {
    exclusionRows = rows;
    const now = hkDateTime();
    $('exclusionsView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · 客户排除管理</h2><div class="muted">合规维护的「客户 + 合作方」排除记录；解除后保留历史与审计。</div></div><button type="button" class="btn" data-exclusion-new>＋ 新增客户排除</button></div>`
        + `<form id="exclusionForm" class="panel" style="background:#f8fbff"><div class="layout3"><div class="field"><label>客户 *</label><select name="clientId" required>${clientOptions(clients)}</select></div><div class="field"><label>排除原因 *</label><input name="reason" required maxlength="512" placeholder="合规复核原因"></div><div class="field"><label>生效时间 HKT *</label><input name="effectiveFrom" type="datetime-local" value="${hkDateTime()}" required></div><div class="field"><label>截止时间 HKT</label><input name="effectiveTo" type="datetime-local"></div></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存排除</button></div><div id="exclusionError" class="err" role="alert"></div></form>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>客户</th><th>排除原因</th><th>生效时间</th><th>截止时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(x => {const active=x.effectiveFrom<=now&&(!x.effectiveTo||x.effectiveTo>now);return `<tr><td>#${x.id}</td><td>${esc(x.clientId)}</td><td>${esc(x.reason)}</td><td>${esc(x.effectiveFrom)}</td><td>${esc(x.effectiveTo||'—')}</td><td>${active?'<span class="badge fail">有效</span>':'已结束 / 未生效'}</td><td><button type="button" class="btn secondary tiny" data-exclusion-edit="${x.id}">编辑</button> ${active?`<button type="button" class="btn secondary tiny" data-release="${x.id}">解除</button>`:'—'}</td></tr>`;}).join('')||'<tr><td colspan="7">当前合作方暂无客户排除记录</td></tr>'}</tbody></table></div><div class="good" id="exclusionGood" role="status"></div>`;
    prepareTabEditor('exclusionsView', 'exclusionForm', '客户排除');
}

function renderApprovals({rows, clients}) {
    approvalRows = rows;
    const now = hkDateTime();
    $('approvalsView').innerHTML = `<div class="rulebar"><div><h2>${esc(code)} · 个案审批管理</h2><div class="muted">审批仅适用于当前合作方、指定客户与流转类别。</div></div><button type="button" class="btn" data-approval-new>＋ 新增个案审批</button></div>`
        + `<form id="approvalForm" class="panel" style="background:#f8fbff"><div class="layout3"><div class="field"><label>客户 *</label><select name="clientId" required>${clientOptions(clients)}</select></div><div class="field"><label>流转类别 *</label><select name="flowCategory"><option>STANDARD</option><option>PSP</option><option>WEB3</option></select></div><div class="field"><label>审批参考号 *</label><input name="approvalReference" required maxlength="128" placeholder="APR-2026-001"></div><div class="field"><label>生效时间 HKT *</label><input name="effectiveFrom" type="datetime-local" value="${hkDateTime()}" required></div><div class="field"><label>过期时间 HKT *</label><input name="expiresAt" type="datetime-local" value="${hkDateTime(30)}" required></div></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存审批</button></div><div id="approvalError" class="err" role="alert"></div></form>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>客户</th><th>流转类别</th><th>审批编号</th><th>生效时间</th><th>失效时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(x => {const active=x.effectiveFrom<=now&&x.expiresAt>now;return `<tr><td>#${x.id}</td><td>${esc(x.clientId)}</td><td>${esc(x.flowCategory)}</td><td>${esc(x.approvalReference)}</td><td>${esc(x.effectiveFrom)}</td><td>${esc(x.expiresAt)}</td><td>${active?'<span class="badge pass">有效</span>':'已失效 / 未生效'}</td><td><button type="button" class="btn secondary tiny" data-approval-edit="${x.id}">编辑</button> ${active?`<button type="button" class="btn secondary tiny" data-revoke="${x.id}">撤销</button>`:'—'}</td></tr>`;}).join('')||'<tr><td colspan="8">当前合作方暂无个案审批记录</td></tr>'}</tbody></table></div><div class="good" id="approvalGood" role="status"></div>`;
    prepareTabEditor('approvalsView', 'approvalForm', '个案审批');
}

let exclusionRows = [], approvalRows = [];
let restrictionRows = [], restrictionGood = '';
function renderRestrictions(rows) {
    restrictionRows = rows;
    const list = rows.length ? rows.map(r => {
        const [label, reason] = restrictTypes[r.restrictionType] || [];
        return `<tr><td>#${r.id}</td><td title="${esc(r.restrictionType)}">${esc(label || r.restrictionType)}<br><span class="muted">${esc(reason || '')}</span></td>`
            + `<td>${esc(r.blockedValues)}</td><td>${esc((r.effectiveFrom || '').replace('T', ' ').slice(0, 16))}</td>`
            + `<td>${esc((r.effectiveTo || '—').replace('T', ' ').slice(0, 16))}</td><td>v${r.versionNo}</td>`
            + `<td>${r.enabled ? '<span class="badge pass">启用</span>' : '<span class="badge pending">停用</span>'}</td>`
            + `<td>${esc(r.remark || '—')}</td><td><button type="button" class="btn secondary tiny" data-restriction-edit="${r.id}">编辑</button> <button type="button" class="btn secondary tiny" data-restriction-toggle="${r.id}">${r.enabled ? '停用' : '启用'}</button></td></tr>`;
    }).join('') : '<tr><td colspan="9">该合作方暂无限制记录。</td></tr>';
    $('restrictionsView').innerHTML = `<div class="rulebar"><div class="ptitle" style="margin:0">${esc(code)} · 合作方限制（制裁及监管屏蔽）· ${rows.length} 条</div>`
        + `<button type="button" class="btn" data-restriction-new>＋ 新增限制</button></div>`
        + `<div class="tablewrap"><table><thead><tr><th>ID</th><th>限制类型</th><th>屏蔽值</th><th>生效时间</th><th>失效时间</th><th>版本</th><th>状态</th><th>备注</th><th>操作</th></tr></thead>`
        + `<tbody>${list}</tbody></table></div>`
        + `<div class="notice">合作方限制按合作方独立配置，C2 校验实时读取：受益行国家/地址国家、客户国家/发起方国家、扣账币种/交易币种分别采用 OR 匹配；董事及 UBO 国籍遍历全部值；中间行为空时不触发该项。</div>`
        + `<div id="restrictionGood" class="good" role="status">${esc(restrictionGood)}</div><div id="restrictionPageError" class="err" role="alert"></div>`;
}

function renderRestrictionForm(row = null) {
    const panel = $('restrictionFormPanel');
    if (!panel) return;
    $('restrictionModalTitle').textContent=row?`编辑合作方限制 · #${row.id}`:`新增合作方限制 · ${code}`;
    panel.innerHTML = `<form id="restrictionForm" novalidate>
      <div class="layout2">
        <div class="field"><label>限制类型 *</label><select name="restrictionType">${Object.entries(restrictTypes).map(([value, [label, reason]]) => `<option value="${esc(value)}"${row?.restrictionType === value ? ' selected' : ''}>${esc(label)}（${esc(reason)}）</option>`).join('')}</select></div>
        <div class="field"><label>屏蔽编码（逗号分隔）*</label><input name="blockedValues" value="${esc(row?.blockedValues || '')}" placeholder="KG,RU" required></div>
        <div class="field"><label>启用状态</label><select name="enabled"><option value="true"${!row || row.enabled ? ' selected' : ''}>启用</option><option value="false"${row && !row.enabled ? ' selected' : ''}>停用</option></select></div>
        <div class="field"><label>生效时间（HKT）*</label><input name="effectiveFrom" type="datetime-local" value="${esc(row?.effectiveFrom?.slice(0, 16) || hkDateTime())}" required></div>
        <div class="field"><label>失效时间（HKT）</label><input name="effectiveTo" type="datetime-local" value="${esc(row?.effectiveTo?.slice(0, 16) || '')}"></div>
        <div class="field"><label>备注</label><input name="remark" value="${esc(row?.remark || '')}"></div>
      </div><div id="restrictionError" class="err" role="alert"></div>
      <div class="actions" style="justify-content:flex-end;margin-top:16px"><button type="button" class="btn secondary" data-restriction-close>取消</button><button type="submit" class="btn">保存合作方限制</button></div>
    </form>`;
    panel.dataset.editingId = row?.id || '';
    $('restrictionModal').classList.add('show');
}
function closeRestrictionModal() {
    $('restrictionModal').classList.remove('show');
    $('restrictionFormPanel').innerHTML='';
    $('restrictionFormPanel').dataset.editingId='';
}

function renderAudit(data) {
    $('auditView').innerHTML = data.length ? data.map(a => `<div class="trace"><b>${esc(a.operation)}</b> ${esc(a.operatedAt?.replace('T', ' '))} · ${esc(a.operatorName)} · ${esc(a.objectType)}`
        + `<details style="margin-top:10px"><summary>查看变更前后</summary><div class="subheading">变更前</div><pre class="code">${esc(pretty(a.beforeJson))}</pre>`
        + `<div class="subheading">变更后</div><pre class="code">${esc(pretty(a.afterJson))}</pre></details></div>`).join('')
        : '<div class="notice">暂无合作方基础信息或能力标签变更记录。</div>';
}

let balanceListing = null, balanceGood = '';
const balanceMoney = value => Number(value).toLocaleString('en-US', {maximumFractionDigits: 2});
function renderBalances(data) {
    balanceListing = data;
    const rows = data.rows.filter(row => row.balance.provider === code);
    const funding = data.fundingSwitches.find(item => item.code === code);
    $('balancesView').innerHTML = `<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 往来账余额与占用</h2><div class="muted">配置版本 ${data.revision} · 资金校验开关 ${funding?.fundingCheckEnabled ? 'ON' : 'OFF'}（基础信息中维护）</div></div><button type="button" class="btn" data-balance-new>＋ 新增余额行</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>币种</th><th>账户余额</th><th>未记账占用</th><th>可用头寸</th><th>操作</th></tr></thead><tbody>${rows.map(row => {const b=row.balance, available=Number(b.balance)-Number(b.reserved);return `<tr><td>${esc(b.currency)}</td><td>${esc(balanceMoney(b.balance))}</td><td>${esc(balanceMoney(b.reserved))}</td><td>${esc(balanceMoney(available))}</td><td><button type="button" class="btn secondary tiny" data-balance-edit="${row.index}">编辑</button> <button type="button" class="btn secondary tiny" data-balance-delete="${row.index}">删除</button></td></tr>`;}).join('')||'<tr><td colspan="5">该合作方暂无往来账余额行。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">同一合作方、同一币种仅一行。可用头寸＝账户余额 − 未记账占用；保存后下一次路由试算立即生效。</div><div id="balancePageGood" class="good" role="status">${esc(balanceGood)}</div><div id="balancePageError" class="err" role="alert"></div><div id="balanceFormPanel"></div>`;
    prepareTabEditor('balancesView', 'balanceFormPanel', '往来账余额与占用');
}
function renderBalanceForm(row = null) {
    const currencies=[...new Set([...(balanceListing?.currencies||[]),row?.balance.currency])].filter(Boolean).sort();
    $('balanceFormPanel').dataset.index=row?.index ?? '';
    $('balanceFormPanel').innerHTML=`<form id="providerBalanceForm" class="panel" style="background:#f8fbff" novalidate><h2 style="margin:0 0 12px">${row ? '编辑' : '新增'}往来账余额 · ${esc(code)}</h2><div class="layout3"><div class="field"><label>币种 *</label><select name="currency">${currencies.map(currency=>`<option value="${esc(currency)}"${currency===(row?.balance.currency||'USD')?' selected':''}>${esc(currency)}</option>`).join('')}</select></div><div class="field"><label>账户余额 *</label><input name="balance" type="number" min="0" step="0.01" value="${esc(row?.balance.balance??'0')}" required></div><div class="field"><label>未记账占用 *</label><input name="reserved" type="number" min="0" step="0.01" value="${esc(row?.balance.reserved??'0')}" required></div></div><div id="balanceFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存余额</button>${row?'<button type="button" class="btn secondary" data-balance-cancel>取消编辑</button>':''}</div></form>`;
    openTabEditor('balanceFormPanel');
}

let scheduleListing = null, scheduleGood = '';
let reachListing = null, reachGood = '';
function renderReaches(data) {
    reachListing = data;
    const rows = data.rows.filter(item => item.reach.provider === code);
    $('reachesView').innerHTML = `<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 业务覆盖</h2><div class="muted">配置版本 ${data.revision} · ${rows.length} 条覆盖行</div></div><button type="button" class="btn" data-reach-new>＋ 新增覆盖行</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>行号</th><th>币种</th><th>国家 / 地区</th><th>银行范围</th><th>扣费方式</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(({index,reach:r})=>`<tr><td>#${index+1}</td><td>${esc(r.currency)}</td><td>${esc(r.country)} / ${esc(r.region)}</td><td>${r.bics.length?`LIST<br>${esc(r.bics.join(', '))}`:'ALL'}</td><td>${esc(r.charges.join(' / '))}</td><td><span class="badge ${r.enabled===false?'pending':'pass'}">${r.enabled===false?'停用':'启用'}</span></td><td><button type="button" class="btn secondary tiny" data-reach-edit="${index}">编辑</button> <button type="button" class="btn secondary tiny" data-reach-toggle="${index}">${r.enabled===false?'启用':'停用'}</button> <button type="button" class="btn secondary tiny" data-reach-delete="${index}">删除</button></td></tr>`).join('')||'<tr><td colspan="7">该合作方暂无覆盖行。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">地区填 ALL 可匹配未提供地区的交易；银行范围选 LIST 时须填写 BIC 前 8 位。C3 按地区、国家、银行范围选取最精确行。覆盖行同时承载 C5 截单日历和 C6 限额参数。</div><div id="reachPageGood" class="good" role="status">${esc(reachGood)}</div><div id="reachPageError" class="err" role="alert"></div><div id="reachFormPanel"></div>`;
    prepareTabEditor('reachesView', 'reachFormPanel', '业务覆盖');
}
function renderReachForm(entry = null) {
    const r = entry?.reach;
    const currencies = [...new Set([...(reachListing?.currencies || []), r?.currency])].filter(Boolean).sort();
    $('reachFormPanel').dataset.index = entry?.index ?? '';
    $('reachFormPanel').innerHTML = `<form id="providerReachForm" class="panel" style="background:#f8fbff" novalidate><h2 style="margin:0 0 12px">${r?'编辑':'新增'}业务覆盖 · ${esc(code)}</h2><div class="layout3"><div class="field"><label>交易币种 *</label><select name="currency" required>${currencies.map(c=>`<option value="${esc(c)}"${c===(r?.currency||'USD')?' selected':''}>${esc(c)}</option>`).join('')}</select></div><div class="field"><label>受益行国家 *</label><input name="country" value="${esc(r?.country||'ALL')}" maxlength="3" required></div><div class="field"><label>受益人地区 *</label><input name="region" value="${esc(r?.region||'ALL')}" maxlength="6" required></div><div class="field"><label>银行范围 *</label><select name="bankScope"><option value="ALL"${r?.bics?.length?'':' selected'}>ALL · 全部银行</option><option value="LIST"${r?.bics?.length?' selected':''}>LIST · 指定 BIC8</option></select></div><div class="field"><label>状态</label><select name="enabled"><option value="true"${r?.enabled===false?'':' selected'}>启用</option><option value="false"${r?.enabled===false?' selected':''}>停用</option></select></div></div><div class="field" style="margin-top:12px" data-reach-bics><label>BIC8 列表（逗号或换行分隔）</label><textarea name="bics" placeholder="ICBKCNBJ, BKCHCNBJ">${esc((r?.bics||[]).join(', '))}</textarea></div><div class="field" style="margin-top:12px"><label>支持的扣费方式 *</label><div class="checks">${['OUR','SHA','BEN'].map(c=>`<label><input type="checkbox" name="charges" value="${c}"${(r?.charges||['OUR','SHA','BEN']).includes(c)?' checked':''}>${c}</label>`).join('')}</div></div><div class="notice">截单与节假日在“截单与工作日日历”页签维护；现有覆盖行的 C5/C6 参数会保留。</div><div id="reachFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存业务覆盖</button><button type="button" class="btn secondary" data-reach-cancel>取消</button></div></form>`;
    syncReachBics();
    openTabEditor('reachFormPanel');
}
function syncReachBics() {
    const form=$('providerReachForm'); if(!form)return;
    const isList=form.elements.bankScope.value==='LIST';
    form.querySelector('[data-reach-bics]').classList.toggle('hidden',!isList);
    form.elements.bics.disabled=!isList;
}
async function deleteReach(index, entry) {
    try {
        return await api(`/c3/reaches/${index}/delete`,{method:'POST',body:JSON.stringify({revision:reachListing.revision})});
    } catch(error) {
        if(error.message!=='Not Found')throw error;
        // 运行中的旧版服务尚未加载删除接口时，沿用已有的带版本校验的完整配置保存接口。
        const current=await api('/routing/config');
        if(current.revision!==reachListing.revision || current.config.reaches[index]?.provider!==code || JSON.stringify(current.config.reaches[index])!==JSON.stringify(entry.reach))
            throw new Error('配置已更新，请刷新后重试');
        current.config.reaches.splice(index,1);
        return api('/routing/config',{method:'PUT',body:JSON.stringify(current)});
    }
}
let limitListing = null, limitGood = '';
const limitMoney = value => Number(value ?? 0).toLocaleString('en-US',{maximumFractionDigits:2});
function renderLimits(data) {
    limitListing = data;
    const rows=data.rows.filter(item=>item.reach.provider===code);
    const hasToday=Object.prototype.hasOwnProperty.call(data.todayUsd,code);
    $('limitsView').innerHTML=`<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 覆盖行限额与当日累计</h2><div class="muted">配置版本 ${data.revision} · ${rows.length} 条覆盖行</div></div><button type="button" class="btn" data-limit-new>＋ 新增覆盖行</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>覆盖行</th><th>币种 / 国家 / 地区</th><th>单笔最大限额 USD</th><th>每日最大限额 USD</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(({index,reach:r})=>`<tr><td>#${index+1}</td><td>${esc(r.currency)} / ${esc(r.country)} / ${esc(r.region)} · ${r.bics.length?'LIST':'ALL'}</td><td>${esc(limitMoney(r.perPaymentUsd))}</td><td>${esc(limitMoney(r.dailyUsd))}</td><td>${r.enabled===false?'停用':'启用'}</td><td><button type="button" class="btn secondary tiny" data-limit-edit="${index}">编辑限额</button> <button type="button" class="btn secondary tiny" data-limit-delete="${index}">删除覆盖行</button></td></tr>`).join('')||'<tr><td colspan="6">该合作方暂无覆盖行，请先新增。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">限额保存在业务覆盖行中。新增或删除覆盖行会同时影响 C3 覆盖判断和 C5 截单配置；修改两项限额不会影响覆盖条件。</div><div id="limitFormPanel"></div>`
      + `<div class="panel" style="background:#f8fbff;margin-top:16px"><div class="rulebar"><h2 style="margin:0">当日累计交易金额 · USD</h2><span class="badge ${hasToday?'pass':'pending'}">${hasToday?'已配置':'未配置，按 0 计算'}</span></div><div class="muted">当前值：${esc(limitMoney(data.todayUsd[code]??0))} USD。香港当日已路由金额的演示快照；重算时不含本笔自身。</div><button type="button" class="btn secondary tiny" data-today-edit>调整当日累计</button><form id="providerTodayForm" class="layout3" style="margin-top:12px" novalidate><div class="field"><label>当日累计金额 USD *</label><input name="todayUsd" type="number" min="0" step="0.01" value="${esc(data.todayUsd[code]??0)}" required></div><div class="actions" style="align-items:end"><button type="submit" class="btn">保存当日累计</button>${hasToday?'<button type="button" class="btn secondary" data-today-delete>删除累计记录</button>':''}</div></form><div id="todayPageError" class="err" role="alert"></div></div>`
      + `<div id="limitPageGood" class="good" role="status">${esc(limitGood)}</div><div id="limitPageError" class="err" role="alert"></div>`;
    prepareTabEditor('limitsView', 'limitFormPanel', '交易限额');
    prepareTabEditor('limitsView', 'providerTodayForm', '当日累计');
    $('providerTodayForm').append($('todayPageError'));
}
function renderLimitForm(entry) {
    const r=entry.reach;
    $('limitFormPanel').dataset.index=entry.index;
    $('limitFormPanel').innerHTML=`<form id="providerLimitForm" class="panel" style="background:#f8fbff;margin-top:16px" novalidate><h2 style="margin:0 0 12px">覆盖行 #${entry.index+1} · ${esc(r.currency)} / ${esc(r.country)} / ${esc(r.region)}</h2><div class="layout3"><div class="field"><label>单笔最大限额 USD *</label><input name="perPaymentUsd" type="number" min="0" step="0.01" value="${esc(r.perPaymentUsd)}" required></div><div class="field"><label>每日最大限额 USD *</label><input name="dailyUsd" type="number" min="0" step="0.01" value="${esc(r.dailyUsd)}" required></div></div><div id="limitFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存限额</button><button type="button" class="btn secondary" data-limit-cancel>取消</button></div></form>`;
    openTabEditor('limitFormPanel');
}
async function deleteTodaySnapshot() {
    try{return await api(`/c6/today/${encodeURIComponent(code)}/delete`,{method:'POST',body:JSON.stringify({revision:limitListing.revision})});}
    catch(error){
        if(error.message!=='Not Found')throw error;
        const current=await api('/routing/config');
        if(current.revision!==limitListing.revision)throw new Error('配置已更新，请刷新后重试');
        delete current.config.todayUsd[code];
        return api('/routing/config',{method:'PUT',body:JSON.stringify(current)});
    }
}
function renderSchedules(data) {
    scheduleListing = data;
    const rows = data.rows.filter(item => item.reach.provider === code);
    $('schedulesView').innerHTML = `<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 截单与工作日日历</h2><div class="muted">配置版本 ${data.revision} · 每条 C3 覆盖行独立维护</div></div></div>`
      + `<div class="tablewrap"><table><thead><tr><th>覆盖行</th><th>币种 / 国家 / 地区</th><th>截单 / 时区</th><th>偏移工作日</th><th>节假日日历</th><th>操作</th></tr></thead><tbody>${rows.map(({index,reach:r})=>`<tr><td>#${index+1}</td><td>${esc(r.currency)} / ${esc(r.country)} / ${esc(r.region)}</td><td>${esc(r.cutoff)}<br>${esc(r.zone)}</td><td>+${esc(r.offsetDays)}</td><td>${r.holidays.length?`<details><summary>${r.holidays.length} 个节假日</summary>${r.holidays.map(day=>`<div>${esc(day)}</div>`).join('')}</details>`:'仅周末'}</td><td><button type="button" class="btn secondary tiny" data-schedule-edit="${index}">维护</button></td></tr>`).join('')||'<tr><td colspan="6">该合作方暂无覆盖行，请先在“业务覆盖”页签新增。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">截单与日历存储在 C3 业务覆盖行中；新增或删除覆盖行在“业务覆盖”页签管理，节假日可在编辑弹窗中维护并保存。</div><div id="schedulePageGood" class="good" role="status">${esc(scheduleGood)}</div><div id="scheduleFormPanel"></div>`;
    prepareTabEditor('schedulesView', 'scheduleFormPanel', '截单与工作日日历');
}
function renderScheduleForm(entry) {
    const r=entry.reach;
    $('scheduleFormPanel').dataset.index=entry.index;
    $('scheduleFormPanel').innerHTML=`<form id="providerScheduleForm" class="panel" style="background:#f8fbff" novalidate><h2>覆盖行 #${entry.index+1} · ${esc(r.currency)} / ${esc(r.country)} / ${esc(r.region)}</h2><div class="layout3"><div class="field"><label>截单时区 *</label><input name="zone" value="${esc(r.zone)}" required></div><div class="field"><label>截单时间 *</label><input name="cutoff" type="time" step="1" value="${esc(r.cutoff)}" required></div><div class="field"><label>起息偏移工作日 *</label><input name="offsetDays" type="number" min="0" max="5" value="${esc(r.offsetDays)}" required></div></div><div class="field" style="margin-top:12px"><label>节假日（每行一个日期；可新增或删去日期）</label><textarea name="holidays" placeholder="2026-12-25">${esc(r.holidays.join('\n'))}</textarea></div><div id="schedulePageError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存截单与日历</button><button type="button" class="btn secondary" data-schedule-cancel>取消</button></div></form>`;
    openTabEditor('scheduleFormPanel');
}

let feeListing = null, feeGood = '';
const feeMoney = value => value == null ? '—' : Number(value).toLocaleString('en-US', {maximumFractionDigits: 2});
function renderFees(data) {
    feeListing = data;
    const rows = data.rows.filter(item => item.fee.provider === code);
    $('feesView').innerHTML = `<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 手续费配置</h2><div class="muted">配置版本 ${data.revision} · ${rows.length} 条手续费行</div></div><button type="button" class="btn" data-fee-new>＋ 新增手续费</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>行号</th><th>交易币种 / 扣费</th><th>固定费</th><th>费率</th><th>最低 / 最高</th><th>电报费</th><th>中间行费</th><th>费用币种</th><th>操作</th></tr></thead><tbody>${rows.map(({index,fee:f})=>`<tr><td>#${index+1}</td><td>${esc(f.currency)} / ${esc(f.charge)}</td><td>${esc(feeMoney(f.fixed))}</td><td>${esc(f.rate)}</td><td>${esc(feeMoney(f.minimum))} / ${f.maximum==null?'不封顶':esc(feeMoney(f.maximum))}</td><td>${esc(feeMoney(f.cable))}</td><td>${esc(feeMoney(f.intermediary))}</td><td>${esc(f.feeCurrency)}</td><td><button type="button" class="btn secondary tiny" data-fee-edit="${index}">编辑</button> <button type="button" class="btn secondary tiny" data-fee-delete="${index}">删除</button></td></tr>`).join('')||'<tr><td colspan="9">该合作方暂无手续费配置。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">维度为合作方＋交易币种＋扣费方式。精确扣费方式优先于 ALL；费率填小数，0.001 表示 0.1%。删除后若没有可匹配的费用行，成本试算会提示缺少手续费配置。</div><div id="feePageGood" class="good" role="status">${esc(feeGood)}</div><div id="feePageError" class="err" role="alert"></div><div id="feeFormPanel"></div>`;
    prepareTabEditor('feesView', 'feeFormPanel', '手续费配置');
}
function renderFeeForm(entry = null) {
    const f = entry?.fee || {currency:'USD',charge:'ALL',feeCurrency:'HKD',fixed:50,rate:0,minimum:0,maximum:null,cable:30,intermediary:20};
    const currencies = [...new Set([...(feeListing?.currencies || []), f.currency, f.feeCurrency])].filter(Boolean).sort();
    const currencyOptions = selected => currencies.map(c => `<option value="${esc(c)}"${c===selected?' selected':''}>${esc(c)}</option>`).join('');
    $('feeFormPanel').dataset.index = entry?.index ?? '';
    $('feeFormPanel').innerHTML = `<form id="providerFeeForm" class="panel" style="background:#f8fbff;margin-top:16px" novalidate><h2 style="margin:0 0 12px">${entry?'编辑':'新增'}手续费 · ${esc(code)}</h2><div class="layout3"><div class="field"><label>交易币种 *</label><select name="currency" required>${currencyOptions(f.currency)}</select></div><div class="field"><label>扣费方式 *</label><select name="charge">${['ALL','OUR','SHA','BEN'].map(c=>`<option value="${c}"${c===f.charge?' selected':''}>${c}</option>`).join('')}</select></div><div class="field"><label>费用币种 *</label><select name="feeCurrency" required>${currencyOptions(f.feeCurrency)}</select></div><div class="field"><label>固定手续费 *</label><input name="fixed" type="number" min="0" step="0.01" value="${esc(f.fixed)}" required></div><div class="field"><label>百分比费率（小数）*</label><input name="rate" type="number" min="0" step="0.000001" value="${esc(f.rate)}" required></div><div class="field"><label>最低手续费 *</label><input name="minimum" type="number" min="0" step="0.01" value="${esc(f.minimum)}" required></div><div class="field"><label>最高手续费（留空不封顶）</label><input name="maximum" type="number" min="0" step="0.01" value="${esc(f.maximum??'')}"></div><div class="field"><label>电报费 *</label><input name="cable" type="number" min="0" step="0.01" value="${esc(f.cable)}" required></div><div class="field"><label>预估中间行费用 *</label><input name="intermediary" type="number" min="0" step="0.01" value="${esc(f.intermediary)}" required></div></div><div id="feeFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存手续费</button><button type="button" class="btn secondary" data-fee-cancel>取消</button></div></form>`;
    openTabEditor('feeFormPanel');
}

let preferenceListing = null, preferenceGood = '';
const preferenceShow = value => value == null || value === '' || value === 'ALL' ? '全部' : String(value);
const preferenceRange = rule => rule.minUsd == null && rule.maxUsd == null ? '不限' : rule.minUsd == null ? `≤ ${feeMoney(rule.maxUsd)}` : rule.maxUsd == null ? `≥ ${feeMoney(rule.minUsd)}` : `${feeMoney(rule.minUsd)} – ${feeMoney(rule.maxUsd)}`;
function renderPreferences(data) {
    preferenceListing = data;
    const rows=data.rows.filter(item=>item.rule.provider===code);
    $('preferencesView').innerHTML=`<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 偏好规则</h2><div class="muted">配置版本 ${data.revision} · ${rows.length} 条规则</div></div><button type="button" class="btn" data-preference-new>＋ 新增偏好规则</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>行号</th><th>币种</th><th>受益行国家</th><th>受益人地区</th><th>金额范围 USD</th><th>交易类型</th><th>客户分层</th><th>奖励分</th><th>操作</th></tr></thead><tbody>${rows.map(({index,rule:r})=>`<tr><td>#${index+1}</td><td>${esc(preferenceShow(r.currency))}</td><td>${esc(preferenceShow(r.country))}</td><td>${esc(preferenceShow(r.region))}</td><td>${esc(preferenceRange(r))}</td><td>${esc(preferenceShow(r.paymentType))}</td><td>${esc(preferenceShow(r.segment))}</td><td>＋${esc(r.points)}</td><td><button type="button" class="btn secondary tiny" data-preference-edit="${index}">编辑</button> <button type="button" class="btn secondary tiny" data-preference-delete="${index}">删除</button></td></tr>`).join('')||'<tr><td colspan="9">该合作方暂无偏好规则。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">空值或 ALL 表示匹配全部。每条命中规则给该合作方加分，多条累加封顶 100；金额按美元等价比较。</div><div id="preferencePageGood" class="good" role="status">${esc(preferenceGood)}</div><div id="preferencePageError" class="err" role="alert"></div><div id="preferenceFormPanel"></div>`;
    prepareTabEditor('preferencesView', 'preferenceFormPanel', '偏好规则');
}
function renderPreferenceForm(entry = null) {
    const r=entry?.rule||{currency:null,country:null,region:null,minUsd:null,maxUsd:null,paymentType:null,segment:null,points:100};
    $('preferenceFormPanel').dataset.index=entry?.index??'';
    $('preferenceFormPanel').innerHTML=`<form id="providerPreferenceForm" class="panel" style="background:#f8fbff;margin-top:16px" novalidate><h2 style="margin:0 0 12px">${entry?'编辑':'新增'}偏好规则 · ${esc(code)}</h2><div class="layout3"><div class="field"><label>币种（空＝全部）</label><input name="currency" maxlength="3" value="${esc(r.currency??'')}" placeholder="USD / ALL"></div><div class="field"><label>受益行国家（空＝全部）</label><input name="country" maxlength="5" value="${esc(r.country??'')}" placeholder="KG / ALL"></div><div class="field"><label>受益人地区（空＝全部）</label><input name="region" value="${esc(r.region??'')}" placeholder="CN-GD / ALL"></div><div class="field"><label>金额下限 USD</label><input name="minUsd" type="number" min="0" step="0.01" value="${esc(r.minUsd??'')}" placeholder="不限"></div><div class="field"><label>金额上限 USD</label><input name="maxUsd" type="number" min="0" step="0.01" value="${esc(r.maxUsd??'')}" placeholder="不限"></div><div class="field"><label>交易类型（空＝全部）</label><select name="paymentType">${['','TRADE','TREASURY','INTRAGROUP','PAYROLL','FEE','REFUND','OTHER'].map(type=>`<option value="${type}"${type===(r.paymentType??'')?' selected':''}>${type||'匹配全部'}</option>`).join('')}</select></div><div class="field"><label>客户分层（空＝全部）</label><input name="segment" value="${esc(r.segment??'')}" placeholder="SME / CORPORATE"></div><div class="field"><label>奖励分 1–100 *</label><input name="points" type="number" min="1" max="100" step="1" value="${esc(r.points)}" required></div></div><div id="preferenceFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存偏好规则</button><button type="button" class="btn secondary" data-preference-cancel>取消</button></div></form>`;
    openTabEditor('preferenceFormPanel');
}

let agreementListing = null, agreementGood = '';
const agreementMoney = value => Number(value??0).toLocaleString('en-US',{maximumFractionDigits:2});
const agreementMetric = metric => metric==='AMOUNT'?'月累计金额 USD':'月累计笔数';
function renderAgreements(data) {
    agreementListing=data;
    const row=data.rows.find(item=>item.provider===code);
    if(!row){$('agreementsView').innerHTML='<div class="err">合作方履约数据不存在</div>';return;}
    $('agreementsView').innerHTML=`<div class="rulebar"><div><h2 style="margin:0">${esc(code)} · 商业协议与月度进度</h2><div class="muted">配置版本 ${data.revision} · ${row.agreements.length} 条协议</div></div><button type="button" class="btn" data-agreement-new>＋ 新增协议</button></div>`
      + `<div class="tablewrap"><table><thead><tr><th>履约指标</th><th>月度目标</th><th>当前 MTD</th><th>进度状态</th><th>C9 分数</th><th>操作</th></tr></thead><tbody>${row.agreements.map(a=>{const item=row.calculation.items.find(i=>i.metric===a.metric);return `<tr><td>${esc(agreementMetric(a.metric))}</td><td>${esc(agreementMoney(a.monthlyTarget))}</td><td>${esc(agreementMoney(a.metric==='AMOUNT'?row.monthUsd:row.monthCount))}</td><td>${esc(item?.outcome||'—')}</td><td>${esc(item?.score??0)} 分</td><td><button type="button" class="btn secondary tiny" data-agreement-edit="${esc(a.metric)}">编辑</button> <button type="button" class="btn secondary tiny" data-agreement-delete="${esc(a.metric)}">删除</button></td></tr>`;}).join('')||'<tr><td colspan="6">该合作方暂无商业协议，C9 得分为 0。</td></tr>'}</tbody></table></div>`
      + `<div class="notice">当月第 ${esc(row.calculation.dayOfMonth)} / ${esc(row.calculation.daysInMonth)} 天 · 时间进度 ${esc(row.calculation.timePercent)}% · 多条协议取最高分：${esc(row.calculation.score)} 分。月度目标为 0 视为无目标。</div><div id="agreementFormPanel"></div>`
      + `<div class="panel" style="background:#f8fbff;margin-top:16px"><h2>MTD 月累计（演示数据）</h2><button type="button" class="btn secondary tiny" data-agreement-mtd-edit>调整月度进度</button><form id="providerAgreementMtdForm" class="layout3" novalidate><div class="field"><label>月累计金额 USD *</label><input name="monthUsd" type="number" min="0" step="0.01" value="${esc(row.monthUsd)}" required></div><div class="field"><label>月累计笔数 *</label><input name="monthCount" type="number" min="0" step="1" value="${esc(row.monthCount)}" required></div><div class="actions" style="align-items:end"><button type="submit" class="btn">保存月度进度</button>${data.mtdConfigured?'<button type="button" class="btn secondary" data-agreement-mtd-delete>清除月度进度</button>':''}</div></form><div class="muted" style="margin-top:10px">实际环境由交易统计按月回填；清除演示记录后，两项累计按 0 计算。</div><div id="agreementMtdError" class="err" role="alert"></div></div>`
      + `<div id="agreementPageGood" class="good" role="status">${esc(agreementGood)}</div><div id="agreementPageError" class="err" role="alert"></div>`;
    prepareTabEditor('agreementsView', 'agreementFormPanel', '商业协议');
    prepareTabEditor('agreementsView', 'providerAgreementMtdForm', '月度进度');
    $('providerAgreementMtdForm').append($('agreementMtdError'));
}
function renderAgreementForm(entry = null) {
    $('agreementFormPanel').dataset.metric=entry?.metric??'';
    $('agreementFormPanel').innerHTML=`<form id="providerAgreementForm" class="panel" style="background:#f8fbff;margin-top:16px" novalidate><h2 style="margin:0 0 12px">${entry?'编辑':'新增'}商业协议 · ${esc(code)}</h2><div class="layout3"><div class="field"><label>履约指标 *</label><select name="metric"${entry?' disabled':''}><option value="AMOUNT"${entry?.metric==='AMOUNT'?' selected':''}>AMOUNT · 月累计金额 USD</option><option value="COUNT"${entry?.metric==='COUNT'?' selected':''}>COUNT · 月累计笔数</option></select></div><div class="field"><label>月度目标 *</label><input name="monthlyTarget" type="number" min="0" step="0.01" value="${esc(entry?.monthlyTarget??'')}" required></div></div><div id="agreementFormError" class="err" role="alert"></div><div class="actions" style="margin-top:12px"><button type="submit" class="btn">保存协议</button><button type="button" class="btn secondary" data-agreement-cancel>取消</button></div></form>`;
    openTabEditor('agreementFormPanel');
}
async function deleteAgreementMtd() {
    try{return await api('/c9/mtd/delete',{method:'POST',body:JSON.stringify({revision:agreementListing.revision,provider:code})});}
    catch(error){
        if(error.message!=='Not Found')throw error;
        const current=await api('/routing/config');
        if(current.revision!==agreementListing.revision)throw new Error('配置已更新，请刷新后重试');
        if(!Object.prototype.hasOwnProperty.call(current.config.monthUsd,code)&&!Object.prototype.hasOwnProperty.call(current.config.monthCount,code))throw new Error('月度进度记录不存在');
        delete current.config.monthUsd[code];delete current.config.monthCount[code];
        return api('/routing/config',{method:'PUT',body:JSON.stringify(current)});
    }
}

// 各页签的数据来源：准入规则、合作方限制取列表后按合作方筛选；变更记录取合作方管理的聚合接口；
// MTD 取全部记录按合作方筛选，并取本合作方的准入规则用于显示月度上限与当月剩余额度
const LOAD = {
    appetites: () => api(`/appetites?providerCode=${encodeURIComponent(code)}`),
    usage: async () => { const [rows, rules] = await Promise.all([api('/usage'), api(`/appetites?providerCode=${encodeURIComponent(code)}`)]); return {rows: rows.filter(x => x.providerCode === code), rules}; },
    balances: () => api('/c4/balances'),
    reaches: () => api('/c3/reaches'),
    limits: () => api('/c6/limits'),
    fees: () => api('/c7/fees'),
    preferences: () => api('/c8/rules'),
    agreements: async () => {
        const [listing, config] = await Promise.all([api('/c9/agreements'), api('/routing/config')]);
        return {...listing, mtdConfigured: Object.prototype.hasOwnProperty.call(config.config.monthUsd,code) || Object.prototype.hasOwnProperty.call(config.config.monthCount,code)};
    },
    schedules: () => api('/c5/schedules'),
    exclusions: async () => { const [rows, clients] = await Promise.all([api(`/exclusions?providerCode=${encodeURIComponent(code)}`), api('/clients')]); return {rows, clients}; },
    approvals: async () => { const [rows, clients] = await Promise.all([api(`/approvals?providerCode=${encodeURIComponent(code)}`), api('/clients')]); return {rows, clients}; },
    restrictions: async () => (await api('/c2/restrictions')).filter(r => r.providerCode === code),
    audit: () => api(`/provider-management/${encodeURIComponent(code)}/audit`)
};
const RENDER = {appetites: renderAppetites, usage: renderUsage, balances: renderBalances, reaches: renderReaches, limits: renderLimits, fees: renderFees, preferences: renderPreferences, agreements: renderAgreements, schedules: renderSchedules, exclusions: renderExclusions, approvals: renderApprovals, restrictions: renderRestrictions, audit: renderAudit};

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
    const editingId = form.dataset.editingId;
    if (editingId) data.id = Number(editingId);
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
$('exclusionsView').addEventListener('click', e => {
    if(e.target.closest('[data-exclusion-new]')){ const form=$('exclusionForm');form.reset();form.dataset.editingId='';openTabEditor('exclusionForm');return; }
    const edit=e.target.closest('[data-exclusion-edit]');if(!edit)return;
    const row=exclusionRows.find(x=>x.id===Number(edit.dataset.exclusionEdit));if(!row)return;
    const form=$('exclusionForm');form.dataset.editingId=row.id;
    for(const [key,value] of Object.entries(row)){const input=form.elements.namedItem(key);if(input)input.value=value??'';}
    openTabEditor('exclusionForm');
});
$('exclusionsView').addEventListener('click', async e => {
    const id = e.target.closest('[data-release]')?.dataset.release;
    if (!id || !confirm('确定解除这条客户排除？历史记录会保留。')) return;
    try { await api(`/exclusions/${id}/release`, {method:'POST'}); await activate('exclusions'); }
    catch (error) { $('exclusionError').textContent = error.message; }
});
$('approvalsView').addEventListener('click', e => {
    if(e.target.closest('[data-approval-new]')){ const form=$('approvalForm');form.reset();form.dataset.editingId='';openTabEditor('approvalForm');return; }
    const edit=e.target.closest('[data-approval-edit]');if(!edit)return;
    const row=approvalRows.find(x=>x.id===Number(edit.dataset.approvalEdit));if(!row)return;
    const form=$('approvalForm');form.dataset.editingId=row.id;
    for(const [key,value] of Object.entries(row)){const input=form.elements.namedItem(key);if(input)input.value=value??'';}
    openTabEditor('approvalForm');
});
$('approvalsView').addEventListener('click', async e => {
    const id = e.target.closest('[data-revoke]')?.dataset.revoke;
    if (!id || !confirm('确定撤销这条个案审批？历史记录会保留。')) return;
    try { await api(`/approvals/${id}/revoke`, {method:'POST'}); await activate('approvals'); }
    catch (error) { $('approvalError').textContent = error.message; }
});
$('restrictionsView').addEventListener('click', async e => {
    if (e.target.closest('[data-restriction-new]')) {
        restrictionGood = '';
        $('restrictionGood').textContent = '';
        renderRestrictionForm();
        return;
    }
    const edit = e.target.closest('[data-restriction-edit]');
    if (edit) {
        const row = restrictionRows.find(r => r.id === Number(edit.dataset.restrictionEdit));
        if (row) { restrictionGood = ''; $('restrictionGood').textContent = ''; renderRestrictionForm(row); }
        return;
    }
    const toggle = e.target.closest('[data-restriction-toggle]');
    if (!toggle) return;
    const row = restrictionRows.find(r => r.id === Number(toggle.dataset.restrictionToggle));
    if (!row || !confirm(`确定${row.enabled ? '停用' : '启用'}限制记录 #${row.id}？`)) return;
    toggle.disabled = true;
    try {
        const saved = await api(`/c2/restrictions/${row.id}/toggle`, {method:'POST'});
        markChanged();
        restrictionGood = `限制记录 #${saved.id} 已${saved.enabled ? '启用' : '停用'}，版本 v${saved.versionNo}。`;
        await show(true);
    } catch (error) { $('restrictionPageError').textContent = error.message; toggle.disabled = false; }
});
$('restrictionModal').addEventListener('click', e => {
    if(e.target === $('restrictionModal') || e.target.closest('[data-restriction-close]'))closeRestrictionModal();
});
document.addEventListener('keydown', e => {if(e.key==='Escape' && $('restrictionModal').classList.contains('show'))closeRestrictionModal();});
$('restrictionModal').addEventListener('submit', async e => {
    if (e.target.id !== 'restrictionForm') return;
    e.preventDefault();
    const form = e.target, error = $('restrictionError'), save = form.querySelector('[type="submit"]');
    const previous = restrictionRows.find(r => r.id === Number($('restrictionFormPanel').dataset.editingId));
    const fields = Object.fromEntries(new FormData(form).entries());
    error.textContent = '';
    if (!fields.blockedValues.trim()) { error.textContent = '屏蔽编码不能为空'; return; }
    if (!fields.effectiveFrom) { error.textContent = '请选择生效时间'; return; }
    if (fields.effectiveTo && fields.effectiveTo <= fields.effectiveFrom) { error.textContent = '失效时间须晚于生效时间'; return; }
    save.disabled = true;
    try {
        const saved = await api('/c2/restrictions', {method:'POST', body:JSON.stringify({
            id:previous?.id || null, versionNo:previous?.versionNo || null, providerCode:code,
            restrictionType:fields.restrictionType, blockedValues:fields.blockedValues,
            enabled:fields.enabled === 'true', effectiveFrom:fields.effectiveFrom,
            effectiveTo:fields.effectiveTo || null, remark:fields.remark || null
        })});
        markChanged();
        restrictionGood = `限制记录 #${saved.id} 保存成功，版本 v${saved.versionNo}。`;
        closeRestrictionModal();
        await show(true);
    } catch (errorValue) { error.textContent = errorValue.message; save.disabled = false; }
});
$('balancesView').addEventListener('click', async e => {
    if (e.target.closest('[data-balance-cancel]')) { closeTabEditor('balanceFormPanel'); return; }
    if (e.target.closest('[data-balance-new]')) { renderBalanceForm(); return; }
    const edit=e.target.closest('[data-balance-edit]');
    if (edit) { const row=balanceListing.rows.find(r=>r.index===Number(edit.dataset.balanceEdit)&&r.balance.provider===code);if(row)renderBalanceForm(row);return; }
    const remove=e.target.closest('[data-balance-delete]');
    if (!remove) return;
    const row=balanceListing.rows.find(r=>r.index===Number(remove.dataset.balanceDelete)&&r.balance.provider===code);
    if (!row || !confirm(`确定删除 ${code} / ${row.balance.currency} 的余额行？删除后该币种可用头寸按 0 计。`)) return;
    remove.disabled=true;
    try { await api('/c4/balances/delete',{method:'POST',body:JSON.stringify({revision:balanceListing.revision,index:row.index})});markChanged();balanceGood=`已删除 ${row.balance.currency} 余额行。`;await activate('balances',true); }
    catch(error){$('balancePageError').textContent=error.message;remove.disabled=false;}
});
$('balancesView').addEventListener('submit', async e => {
    if(e.target.id!=='providerBalanceForm')return;
    e.preventDefault();
    const form=e.target, values=Object.fromEntries(new FormData(form).entries()), error=$('balanceFormError');
    const index=$('balanceFormPanel').dataset.index;
    const previous=index===''?null:balanceListing.rows.find(r=>r.index===Number(index)&&r.balance.provider===code);
    error.textContent='';
    if(values.balance===''||values.reserved===''||!Number.isFinite(Number(values.balance))||!Number.isFinite(Number(values.reserved))||Number(values.balance)<0||Number(values.reserved)<0){error.textContent='余额与占用必须为非负数字';return;}
    const save=form.querySelector('[type="submit"]');save.disabled=true;
    try { await api('/c4/balances',{method:'PUT',body:JSON.stringify({revision:balanceListing.revision,index:previous?.index??null,balance:{provider:code,currency:values.currency,balance:Number(values.balance),reserved:Number(values.reserved)}})});markChanged();balanceGood=`${values.currency} 余额与占用已保存。`;await activate('balances',true); }
    catch(failure){error.textContent=failure.message;save.disabled=false;}
});
$('reachesView').addEventListener('change',e=>{if(e.target.name==='bankScope')syncReachBics();});
$('reachesView').addEventListener('click',async e=>{
    if(e.target.closest('[data-reach-new]')){renderReachForm();return;}
    if(e.target.closest('[data-reach-cancel]')){closeTabEditor('reachFormPanel');return;}
    const edit=e.target.closest('[data-reach-edit]');
    if(edit){const entry=reachListing.rows.find(item=>item.index===Number(edit.dataset.reachEdit)&&item.reach.provider===code);if(entry)renderReachForm(entry);return;}
    const toggle=e.target.closest('[data-reach-toggle]'),remove=e.target.closest('[data-reach-delete]'),button=toggle||remove;
    if(!button)return;
    const index=Number(toggle?.dataset.reachToggle??remove.dataset.reachDelete);
    const entry=reachListing.rows.find(item=>item.index===index&&item.reach.provider===code);
    if(!entry)return;
    if(remove&&!confirm(`确定删除 ${code} 的覆盖行 #${index+1}？该行的 C5 截单日历和 C6 限额参数也会一并删除。`))return;
    button.disabled=true;
    try{if(remove)await deleteReach(index,entry);else await api(`/c3/reaches/${index}/toggle`,{method:'POST',body:JSON.stringify({revision:reachListing.revision})});markChanged();reachGood=`覆盖行 #${index+1} 已${remove?'删除':entry.reach.enabled===false?'启用':'停用'}。`;await activate('reaches',true);}
    catch(error){$('reachPageError').textContent=error.message;button.disabled=false;}
});
$('reachesView').addEventListener('submit',async e=>{
    if(e.target.id!=='providerReachForm')return;
    e.preventDefault();
    const form=e.target,error=$('reachFormError'),values=Object.fromEntries(new FormData(form).entries());
    const index=$('reachFormPanel').dataset.index;
    const previous=index===''?null:reachListing.rows.find(item=>item.index===Number(index)&&item.reach.provider===code);
    if(index!==''&&!previous){error.textContent='覆盖行已变化，请刷新后重试';return;}
    const charges=[...form.querySelectorAll('[name="charges"]:checked')].map(input=>input.value);
    const bics=values.bankScope==='LIST'?values.bics.toUpperCase().split(/[,\s]+/).map(value=>value.trim()).filter(Boolean):[];
    const country=values.country.trim().toUpperCase(),region=values.region.trim().toUpperCase();
    if(!country||!region){error.textContent='受益行国家和受益人地区必填';return;}
    if(!charges.length){error.textContent='至少选择一种扣费方式';return;}
    if(values.bankScope==='LIST'&&!bics.length){error.textContent='LIST 范围至少填写一个 BIC8';return;}
    const reach={...(previous?.reach||{zone:'Asia/Hong_Kong',cutoff:'17:00',offsetDays:0,perPaymentUsd:2000000,dailyUsd:10000000,holidays:[]}),provider:code,currency:values.currency,country,region,bics,charges,enabled:values.enabled==='true'};
    const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
    try{await api('/c3/reaches',{method:'POST',body:JSON.stringify({revision:reachListing.revision,index:previous?.index??null,bankScope:values.bankScope,reach})});markChanged();reachGood=`${values.currency} / ${country} / ${region} 覆盖行已保存。`;await activate('reaches',true);}
    catch(failure){error.textContent=failure.message;save.disabled=false;}
});
$('limitsView').addEventListener('click',async e=>{
    if(e.target.closest('[data-today-edit]')){openTabEditor('providerTodayForm');return;}
    if(e.target.closest('[data-limit-new]')){
        await activate('reaches');
        renderReachForm();
        return;
    }
    if(e.target.closest('[data-limit-cancel]')){closeTabEditor('limitFormPanel');return;}
    const edit=e.target.closest('[data-limit-edit]');
    if(edit){const entry=limitListing.rows.find(item=>item.index===Number(edit.dataset.limitEdit)&&item.reach.provider===code);if(entry){renderLimitForm(entry);}return;}
    const remove=e.target.closest('[data-limit-delete]');
    if(remove){
        const index=Number(remove.dataset.limitDelete);
        const entry=limitListing.rows.find(item=>item.index===index&&item.reach.provider===code);
        if(!entry||!confirm(`确定删除 ${code} 的覆盖行 #${index+1}？C3 覆盖、C5 截单和 C6 限额参数都会一并删除。`))return;
        remove.disabled=true;
        try{
            reachListing=await api('/c3/reaches');
            const current=reachListing.rows.find(item=>item.index===index&&item.reach.provider===code);
            if(reachListing.revision!==limitListing.revision||!current||JSON.stringify(current.reach)!==JSON.stringify(entry.reach))throw new Error('配置已更新，请刷新后重试');
            await deleteReach(index,current);markChanged();limitGood=`覆盖行 #${index+1} 已删除。`;await activate('limits',true);
        }catch(error){$('limitPageError').textContent=error.message;remove.disabled=false;}
        return;
    }
    const delToday=e.target.closest('[data-today-delete]');
    if(delToday){
        if(!confirm(`确定删除 ${code} 的当日累计记录？删除后本次累计按 0 USD 计算。`))return;
        delToday.disabled=true;
        try{await deleteTodaySnapshot();markChanged();limitGood='当日累计记录已删除，当前按 0 USD 计算。';await activate('limits',true);}
        catch(error){$('todayPageError').textContent=error.message;delToday.disabled=false;}
    }
});
$('limitsView').addEventListener('submit',async e=>{
    if(e.target.id==='providerLimitForm'){
        e.preventDefault();
        const form=e.target,error=$('limitFormError'),index=Number($('limitFormPanel').dataset.index);
        const entry=limitListing.rows.find(item=>item.index===index&&item.reach.provider===code);
        if(!entry){error.textContent='覆盖行已变化，请刷新后重试';return;}
        const per=form.elements.perPaymentUsd.value,daily=form.elements.dailyUsd.value;
        if(per===''||daily===''||!Number.isFinite(Number(per))||!Number.isFinite(Number(daily))||Number(per)<0||Number(daily)<0){error.textContent='两项限额必须为非负数字';return;}
        const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
        try{await api('/c6/limits',{method:'PUT',body:JSON.stringify({revision:limitListing.revision,index,perPaymentUsd:Number(per),dailyUsd:Number(daily)})});markChanged();limitGood=`覆盖行 #${index+1} 限额已保存。`;await activate('limits',true);}
        catch(failure){error.textContent=failure.message;save.disabled=false;}
        return;
    }
    if(e.target.id==='providerTodayForm'){
        e.preventDefault();
        const form=e.target,error=$('todayPageError'),value=form.elements.todayUsd.value;
        if(value===''||!Number.isFinite(Number(value))||Number(value)<0){error.textContent='当日累计必须为非负数字';return;}
        const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
        try{await api('/c6/today',{method:'PUT',body:JSON.stringify({revision:limitListing.revision,provider:code,todayUsd:Number(value)})});markChanged();limitGood=`当日累计已保存：${limitMoney(value)} USD。`;await activate('limits',true);}
        catch(failure){error.textContent=failure.message;save.disabled=false;}
    }
});
$('feesView').addEventListener('click', async e => {
    if (e.target.closest('[data-fee-new]')) { renderFeeForm(); return; }
    if (e.target.closest('[data-fee-cancel]')) { closeTabEditor('feeFormPanel'); return; }
    const edit=e.target.closest('[data-fee-edit]');
    if (edit) { const entry=feeListing.rows.find(item=>item.index===Number(edit.dataset.feeEdit)&&item.fee.provider===code); if(entry){renderFeeForm(entry);} return; }
    const remove=e.target.closest('[data-fee-delete]');
    if (!remove) return;
    const index=Number(remove.dataset.feeDelete);
    const entry=feeListing.rows.find(item=>item.index===index&&item.fee.provider===code);
    if (!entry || !confirm(`确定删除 ${code} / ${entry.fee.currency} / ${entry.fee.charge} 的手续费行？若没有其他匹配行，C7 将无法预估该维度的成本。`)) return;
    remove.disabled=true;
    try { await api('/c7/fees/delete',{method:'POST',body:JSON.stringify({revision:feeListing.revision,index})}); markChanged(); feeGood=`${entry.fee.currency} / ${entry.fee.charge} 手续费行已删除。`; await activate('fees',true); }
    catch(error) { $('feePageError').textContent=error.message; remove.disabled=false; }
});
$('feesView').addEventListener('submit', async e => {
    if(e.target.id!=='providerFeeForm')return;
    e.preventDefault();
    const form=e.target,error=$('feeFormError'),values=Object.fromEntries(new FormData(form).entries());
    const index=$('feeFormPanel').dataset.index;
    const previous=index===''?null:feeListing.rows.find(item=>item.index===Number(index)&&item.fee.provider===code);
    if(index!==''&&!previous){error.textContent='手续费行已变化，请刷新后重试';return;}
    const fee={provider:code,currency:values.currency,charge:values.charge,feeCurrency:values.feeCurrency};
    for(const key of ['fixed','rate','minimum','cable','intermediary']){
        const raw=values[key];
        if(raw===''||!Number.isFinite(Number(raw))||Number(raw)<0){error.textContent='所有必填费用必须为非负数字';return;}
        fee[key]=Number(raw);
    }
    fee.maximum=values.maximum===''?null:Number(values.maximum);
    if(fee.maximum!==null&&(!Number.isFinite(fee.maximum)||fee.maximum<fee.minimum)){error.textContent='最高手续费不可小于最低手续费';return;}
    const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
    try{await api('/c7/fees',{method:'PUT',body:JSON.stringify({revision:feeListing.revision,index:previous?.index??null,fee})});markChanged();feeGood=`${fee.currency} / ${fee.charge} 手续费行已保存。`;await activate('fees',true);}
    catch(failure){error.textContent=failure.message;save.disabled=false;}
});
$('preferencesView').addEventListener('click', async e => {
    if(e.target.closest('[data-preference-new]')){renderPreferenceForm();return;}
    if(e.target.closest('[data-preference-cancel]')){closeTabEditor('preferenceFormPanel');return;}
    const edit=e.target.closest('[data-preference-edit]');
    if(edit){const entry=preferenceListing.rows.find(item=>item.index===Number(edit.dataset.preferenceEdit)&&item.rule.provider===code);if(entry){renderPreferenceForm(entry);}return;}
    const remove=e.target.closest('[data-preference-delete]');
    if(!remove)return;
    const index=Number(remove.dataset.preferenceDelete);
    const entry=preferenceListing.rows.find(item=>item.index===index&&item.rule.provider===code);
    if(!entry||!confirm(`确定删除 ${code} 的偏好规则 #${index+1}？`))return;
    remove.disabled=true;
    try{await api('/c8/rules/delete',{method:'POST',body:JSON.stringify({revision:preferenceListing.revision,index})});markChanged();preferenceGood=`偏好规则 #${index+1} 已删除。`;await activate('preferences',true);}
    catch(error){$('preferencePageError').textContent=error.message;remove.disabled=false;}
});
$('preferencesView').addEventListener('submit',async e=>{
    if(e.target.id!=='providerPreferenceForm')return;
    e.preventDefault();
    const form=e.target,error=$('preferenceFormError'),values=Object.fromEntries(new FormData(form).entries());
    const index=$('preferenceFormPanel').dataset.index;
    const previous=index===''?null:preferenceListing.rows.find(item=>item.index===Number(index)&&item.rule.provider===code);
    if(index!==''&&!previous){error.textContent='规则已变化，请刷新后重试';return;}
    const clean=value=>value?.trim()?value.trim().toUpperCase():null;
    const rule={provider:code,currency:clean(values.currency),country:clean(values.country),region:clean(values.region),minUsd:values.minUsd===''?null:Number(values.minUsd),maxUsd:values.maxUsd===''?null:Number(values.maxUsd),paymentType:values.paymentType||null,segment:clean(values.segment),points:Number(values.points)};
    if(!Number.isInteger(rule.points)||rule.points<1||rule.points>100){error.textContent='奖励分数必须为 1–100 的整数';return;}
    if(rule.minUsd!==null&&(!Number.isFinite(rule.minUsd)||rule.minUsd<0)){error.textContent='金额下限必须为非负数字';return;}
    if(rule.maxUsd!==null&&(!Number.isFinite(rule.maxUsd)||rule.maxUsd<0||(rule.minUsd!==null&&rule.maxUsd<rule.minUsd))){error.textContent='金额上限必须非负且不小于下限';return;}
    const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
    try{await api('/c8/rules',{method:'PUT',body:JSON.stringify({revision:preferenceListing.revision,index:previous?.index??null,rule})});markChanged();preferenceGood=`${rule.currency||'全部币种'} 偏好规则已保存。`;await activate('preferences',true);}
    catch(failure){error.textContent=failure.message;save.disabled=false;}
});
$('agreementsView').addEventListener('click',async e=>{
    if(e.target.closest('[data-agreement-mtd-edit]')){openTabEditor('providerAgreementMtdForm');return;}
    if(e.target.closest('[data-agreement-new]')){renderAgreementForm();return;}
    if(e.target.closest('[data-agreement-cancel]')){closeTabEditor('agreementFormPanel');return;}
    const row=agreementListing.rows.find(item=>item.provider===code);
    const edit=e.target.closest('[data-agreement-edit]');
    if(edit){const entry=row?.agreements.find(item=>item.metric===edit.dataset.agreementEdit);if(entry){renderAgreementForm(entry);}return;}
    const remove=e.target.closest('[data-agreement-delete]');
    if(remove){const metric=remove.dataset.agreementDelete;if(!row?.agreements.some(item=>item.metric===metric)||!confirm(`确定删除 ${code} 的 ${agreementMetric(metric)} 协议？该指标将按无月度目标评分。`))return;remove.disabled=true;try{await api('/c9/agreements/delete',{method:'POST',body:JSON.stringify({revision:agreementListing.revision,provider:code,metric})});markChanged();agreementGood=`${agreementMetric(metric)}协议已删除。`;await activate('agreements',true);}catch(error){$('agreementPageError').textContent=error.message;remove.disabled=false;}return;}
    const clear=e.target.closest('[data-agreement-mtd-delete]');
    if(clear){if(!confirm(`确定清除 ${code} 的月度进度演示数据？金额与笔数将按 0 计算。`))return;clear.disabled=true;try{await deleteAgreementMtd();markChanged();agreementGood='月度进度记录已清除，两项累计按 0 计算。';await activate('agreements',true);}catch(error){$('agreementMtdError').textContent=error.message;clear.disabled=false;}}
});
$('agreementsView').addEventListener('submit',async e=>{
    if(e.target.id==='providerAgreementForm'){
        e.preventDefault();const form=e.target,error=$('agreementFormError');
        const metric=$('agreementFormPanel').dataset.metric||form.elements.metric.value;
        const raw=form.elements.monthlyTarget.value;
        if(raw===''||!Number.isFinite(Number(raw))||Number(raw)<0){error.textContent='月度目标必须为非负数字';return;}
        const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
        try{await api('/c9/agreements',{method:'PUT',body:JSON.stringify({revision:agreementListing.revision,provider:code,metric,monthlyTarget:Number(raw)})});markChanged();agreementGood=`${agreementMetric(metric)}协议已保存。`;await activate('agreements',true);}
        catch(failure){error.textContent=failure.message;save.disabled=false;}
        return;
    }
    if(e.target.id==='providerAgreementMtdForm'){
        e.preventDefault();const form=e.target,error=$('agreementMtdError');
        const usd=form.elements.monthUsd.value,count=form.elements.monthCount.value;
        if(usd===''||count===''||!Number.isFinite(Number(usd))||Number(usd)<0||!Number.isInteger(Number(count))||Number(count)<0){error.textContent='月累计金额须为非负数字，笔数须为非负整数';return;}
        const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
        try{await api('/c9/mtd',{method:'PUT',body:JSON.stringify({revision:agreementListing.revision,provider:code,monthUsd:Number(usd),monthCount:Number(count)})});markChanged();agreementGood='月度进度已保存。';await activate('agreements',true);}
        catch(failure){error.textContent=failure.message;save.disabled=false;}
    }
});
$('schedulesView').addEventListener('click', e => {
    if(e.target.closest('[data-schedule-cancel]')){closeTabEditor('scheduleFormPanel');return;}
    const edit=e.target.closest('[data-schedule-edit]');if(!edit)return;
    const entry=scheduleListing.rows.find(item=>item.index===Number(edit.dataset.scheduleEdit)&&item.reach.provider===code);
    if(entry){renderScheduleForm(entry);}
});
$('schedulesView').addEventListener('submit',async e=>{
    if(e.target.id!=='providerScheduleForm')return;
    e.preventDefault();
    const form=e.target,values=Object.fromEntries(new FormData(form).entries()),error=$('schedulePageError');
    const index=Number($('scheduleFormPanel').dataset.index);
    const entry=scheduleListing.rows.find(item=>item.index===index&&item.reach.provider===code);
    if(!entry){error.textContent='覆盖行已变化，请刷新后重试';return;}
    const holidays=values.holidays.split(/[,\s]+/).map(day=>day.trim()).filter(Boolean);
    if(holidays.some(day=>!/^\d{4}-\d{2}-\d{2}$/.test(day))){error.textContent='节假日请使用 YYYY-MM-DD 格式';return;}
    const save=form.querySelector('[type="submit"]');save.disabled=true;error.textContent='';
    try{await api('/c5/schedules',{method:'PUT',body:JSON.stringify({revision:scheduleListing.revision,index,zone:values.zone.trim(),cutoff:values.cutoff,offsetDays:Number(values.offsetDays),holidays})});markChanged();scheduleGood=`覆盖行 #${index+1} 的截单与日历已保存。`;await activate('schedules',true);}
    catch(failure){error.textContent=failure.message;save.disabled=false;}
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
    if (e.target.closest('[data-usage-new]')) { usageForm = usageReset({flowCategory:'STANDARD',monthKey:usageMonth()}); renderUsage({rows:usageRows,rules:usageRules}); openTabEditor('usageForm'); return; }
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
        openTabEditor('usageForm');
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
    openTabEditor('usageForm');
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
