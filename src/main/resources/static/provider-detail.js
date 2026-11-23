'use strict';
// 合作方详情：/provider-detail.html?code=合作方编码[&tab=related|restrictions|audit]
// 只读展示，数据来自 /api/provider-management/{code}；写操作在合作方管理页的编辑面板完成。
const state = {view: null, tags: [], tab: 'base', request: 0};
const params = new URLSearchParams(location.search);
const code = params.get('code');
const TABS = ['base', 'related', 'restrictions', 'audit'];

// 回到合作方管理页的编辑面板；带 tab 时直接切到对应页签
function listHref(tab) {
    return `/providers.html?code=${encodeURIComponent(state.view.provider.code)}${tab ? `&tab=${tab}` : ''}#editor`;
}

function table(headers, rows) {
    return `<div class="tablewrap"><table><thead><tr>${headers.map(h => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length ? rows.map(row => `<tr>${row.map(c => `<td>${esc(c)}</td>`).join('')}</tr>`).join('') : `<tr><td colspan="${headers.length}">暂无配置</td></tr>`}</tbody></table></div>`;
}

function pretty(text) {
    try {
        return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
        return text || '无';
    }
}

function section(title, href, body) {
    return `<div class="subheading">${title} ${href ? `<a href="${href}">去维护 ↗</a>` : ''}</div>${body}`;
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

function renderRelated(d) {
    $('relatedView').innerHTML = `<div class="notice">以下为当前已保存配置。修改配置后可刷新本页重新检查上线条件；最终候选仍须通过完整 C1–C10 校验。</div>`
        + section(`C1 业务准入 · ${d.appetites.length} 条`, '/', table(
            ['流转类别', '币种', '国家', '准入方式', '状态', '生效时间', '截止时间'],
            d.appetites.map(a => [a.flowCategory, a.currency, a.bankCountry, a.action, a.enabled ? '启用' : '停用', a.effectiveFrom, a.effectiveTo])))
        + section(`C3 业务覆盖 · ${d.reaches.length} 条`, '/c3.html', table(
            ['币种', '国家', '地区', '银行范围', '扣费方式', '状态'],
            d.reaches.map(r => [r.currency, r.country, r.region, r.bics.length ? r.bics.join(', ') : 'ALL', r.charges.join('/'), r.enabled === false ? '停用' : '启用'])))
        + section(`C7 手续费 · ${d.fees.length} 条`, '/routing.html#configPanel', table(
            ['交易币种', '扣费方式', '费用币种', '固定费', '比例费率', '最低 / 最高', '电报费', '中间行费'],
            d.fees.map(f => [f.currency, f.charge, f.feeCurrency, f.fixed, `${f.rate * 100}%`, `${f.minimum} / ${f.maximum ?? '无上限'}`, f.cable, f.intermediary])))
        + `<div class="subheading">C2 合作方限制 · ${d.restrictions.length} 条</div>`
        + `<div class="notice">合作方限制明细见本页「合作方限制」页签；新增 / 编辑 / 启停在合作方管理页的编辑面板中操作。</div>`
        + section('C10 可配置外汇渠道', '/routing.html#configPanel', table(
            ['渠道', '扣账 / 交易', '星期', '香港窗口', '点差 bps'],
            d.fxWindows.map(w => [w.provider, `${w.debit} / ${w.payment}`, w.days.join(','), `${w.start}–${w.end}`, `${w.spreadBps} + ${w.extraBps}`])));
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
        + `<a class="btn secondary tiny" href="${listHref('restrictions')}">前往维护 ↗</a></div>`
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

async function activate(tab) {
    state.tab = tab;
    document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('sel', b.dataset.tab === tab));
    for (const id of TABS) $(id + 'View').classList.toggle('hidden', id !== tab);
    if (tab === 'base') return;
    const container = $(tab + 'View'), token = ++state.request;
    container.innerHTML = '<p class="muted">正在加载…</p>';
    try {
        const data = tab === 'restrictions' ? (await api('/c2/restrictions')).filter(r => r.providerCode === code)
            : await api(`/provider-management/${encodeURIComponent(code)}/${tab === 'related' ? 'related' : 'audit'}`);
        if (token !== state.request) return;
        ({related: renderRelated, restrictions: renderRestrictions, audit: renderAudit})[tab](data);
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
    $('edit').href = listHref();
    $('simulate').href = `/routing.html?includeProvider=${encodeURIComponent(code)}`;
}

async function show() {
    $('pageError').textContent = '';
    $('loading').hidden = false;
    try {
        await load();
        $('loading').hidden = true;
        $('detail').classList.remove('hidden');
        for (const id of ['edit', 'simulate']) $(id).style.display = '';
        await activate(state.tab);
    } catch (e) {
        $('loading').hidden = true;
        $('detail').classList.add('hidden');
        for (const id of ['edit', 'simulate']) $(id).style.display = 'none';
        $('pageError').textContent = e.message;
    }
}

document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => activate(b.dataset.tab));
$('refresh').onclick = () => show();
const initial = params.get('tab');
state.tab = TABS.includes(initial) ? initial : 'base';
document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => activate(b.dataset.tab));
$('refresh').onclick = () => show();
show();
