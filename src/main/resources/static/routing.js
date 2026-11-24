'use strict';
const el = id => document.getElementById(id), esc = x => String(x ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
let revision;
const fieldDefs = [['includeProviders', '包含未上线合作方（逗号分隔）', new URLSearchParams(location.search).get('includeProvider') || ''], ['paymentId', '交易编号', 'DEMO-001'], ['clientId', '客户', 'C10002', 'client'], ['debitCurrency', '扣账币种', 'USD', 'currency'], ['paymentCurrency', '交易币种', 'USD', 'currency'], ['amount', '交易金额', '10000', 'number'], ['chargeOption', '扣费方式', 'SHA', ['OUR', 'SHA', 'BEN']], ['beneficiaryBankCountry', '受益行国家', 'US'], ['beneficiaryAddressCountry', '受益人国家', 'US'], ['beneficiaryBankBic', '受益行 BIC', 'CHASUS33'], ['beneficiaryRegion', '受益地区', ''], ['orderingCountry', '发起方国家', 'HK'], ['clientCountry', '客户国家', 'HK'], ['paymentType', '交易类型', 'TRADE', ['TRADE', 'TREASURY', 'INTRAGROUP', 'PAYROLL', 'FEE', 'REFUND', 'OTHER']], ['clientSegment', '客户层级', 'STANDARD'], ['requestedValueDate', '请求起息日', '', 'date'], ['at', '香港模拟时间', '', 'datetime-local'], ['intermediaryCountry', '中间行国家', ''], ['purpose', '交易用途', ''], ['directorUboNationalities', '董事/UBO国籍（逗号分隔）', '']];

async function api(path, options) {
    const res = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const body = await res.json();
    if (!res.ok) throw Error(body.error || body.message || `HTTP ${res.status}`);
    return body;
}

function renderResult(r) {
    const top = r.route;
    el('result').innerHTML = `<h2>Check 结果 <span class="${top ? 'ok' : 'bad'}">${esc(r.result)}</span></h2><div class="summary">${top ? `主路由 <b>${esc(top.providerCode)} / ${esc(top.fxVenue)}</b> · 总分 ${esc(top.totalScore)} · 成本 HKD ${esc(top.estimatedCostHkd)} · 起息日 ${esc(top.valueDate)}` : `转人工处理：<b>${esc(r.reasonCode)}</b>${r.nextFxWindow ? ` · 下一窗口 ${esc(r.nextFxWindow)}` : ''}`}</div><p>备选：${r.alternatives.map(c => `${esc(c.providerCode)} / ${esc(c.fxVenue)}`).join('；') || '—'}</p><p class="muted">决策 ${esc(r.decisionId)} · 配置版本 ${r.configRevision} · 必须运营审核</p><div class="scroll"><table><thead><tr><th>排名 / 候选</th><th>过滤结果</th><th>起息日</th><th>成本 HKD</th><th>C5 / C7 / C8 / C9</th><th>总分</th></tr></thead><tbody>${r.candidates.slice().sort((a, b) => (a.rank ?? 999) - (b.rank ?? 999)).map(c => `<tr><td>${c.rank ?? '剔除'} · ${esc(c.providerCode)}<br>${esc(c.fxVenue)}</td><td>${c.checks.map(x => `<div class="${x.passed ? 'ok' : 'bad'}">${esc(x.code)} ${x.passed ? '通过' : esc(x.reasons.join(', '))}</div>`).join('')}</td><td>${esc(c.valueDate)}</td><td>${esc(c.estimatedCostHkd)}</td><td>${['C5', 'C7', 'C8', 'C9'].map(k => esc(c.scores[k])).join(' / ')}</td><td>${esc(c.totalScore)}</td></tr>`).join('')}</tbody></table></div>${r.candidates.map(c => `<details><summary>${esc(c.providerCode)} / ${esc(c.fxVenue)} · 判断依据</summary><pre>${esc(JSON.stringify({
        filters: c.checks,
        C5: c.c5
    }, null, 2))}</pre></details>`).join('')}<p class="muted">${r.messages.map(esc).join('<br>')}</p><details><summary>完整响应 JSON</summary><pre>${esc(JSON.stringify(r, null, 2))}</pre></details>`;
}

async function loadConfig() {
    const v = await api('/routing/config');
    revision = v.revision;
    el('config').value = JSON.stringify(v.config, null, 2);
    el('configMessage').textContent = `当前版本 ${revision}`;
    return v;
}

async function init() {
    try {
        const [clients, v] = await Promise.all([api('/clients'), loadConfig()]);
        const currencies = Object.keys(v.config.hkdRates);
        el('fields').innerHTML = fieldDefs.map(([id, label, value, type]) => {
            let options = Array.isArray(type) ? type : type === 'currency' ? currencies : type === 'client' ? clients.map(c => c.clientId) : null;
            return `<label>${esc(label)}${options ? `<select id="${id}">${options.map(o => `<option ${o === value ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select>` : `<input id="${id}" value="${esc(value)}" type="${type || 'text'}" ${type === 'number' ? 'min="0.000001" step="any"' : ''}>`}</label>`;
        }).join('');
    } catch (e) {
        el('error').textContent = e.message;
    }
}

el('routeForm').addEventListener('submit', async e => {
    e.preventDefault();
    el('error').textContent = '';
    el('run').disabled = true;
    try {
        let req = {};
        for (const [id] of fieldDefs) req[id] = el(id).value || null;
        req.amount = Number(req.amount);
        req.includeProviders = (req.includeProviders || '').split(',').map(x => x.trim()).filter(Boolean);
        req.directorUboNationalities = (req.directorUboNationalities || '').split(',').map(x => x.trim().toUpperCase()).filter(Boolean);
        if (req.at) req.at += '+08:00';
        renderResult(await api('/routing/simulate', {method: 'POST', body: JSON.stringify(req)}));
    } catch (e) {
        el('error').textContent = e.message;
    } finally {
        el('run').disabled = false;
    }
});
el('save').onclick = async () => {
    el('save').disabled = true;
    try {
        const v = await api('/routing/config', {
            method: 'PUT',
            body: JSON.stringify({revision, config: JSON.parse(el('config').value)})
        });
        revision = v.revision;
        el('configMessage').textContent = `已保存，版本 ${revision}`;
    } catch (e) {
        el('configMessage').textContent = e.message;
    } finally {
        el('save').disabled = false;
    }
};
el('reload').onclick = () => loadConfig().catch(e => el('configMessage').textContent = e.message);

function condText(n) {
    if (!n) return '—';
    if (Array.isArray(n.children) && n.children.length) return '(' + n.children.map(condText).join(n.operator === 'OR' ? ' 或 ' : ' 且 ') + ')';
    return n.field ? [n.field, n.operator, String(n.value ?? '')].join(' ') : '—';
}

async function loadRulesSummary() {
    try {
        const [appetites, exclusions, approvals, c2rules, restrictions] = await Promise.all([api('/appetites'), api('/exclusions'), api('/approvals'), api('/c2/regulatory-rules'), api('/c2/restrictions')]);
        const c2on = c2rules.filter(x => x.enabled);
        el('rulesSummary').innerHTML = `
  <details open><summary>C1 准入规则 · ${appetites.filter(x => x.enabled).length}/${appetites.length} 条生效<a href="/" onclick="event.stopPropagation()">去维护 →</a></summary><div class="scroll"><table><thead><tr><th>合作方</th><th>流转类别</th><th>币种</th><th>受益行国家</th><th>准入方式</th><th>月上限 USD</th><th>状态</th></tr></thead><tbody>${appetites.map(x => `<tr><td>${esc(x.providerCode)}</td><td>${esc(x.flowCategory)}</td><td>${esc(x.currency)}</td><td>${esc(x.bankCountry)}</td><td>${esc(x.action)}</td><td>${x.monthlyCapUsd == null ? '—' : esc(x.monthlyCapUsd)}</td><td>${x.enabled ? '<span class="ok">生效</span>' : '<span class="bad">停用</span>'}</td></tr>`).join('')}</tbody></table></div><p class="muted">另有客户排除 ${exclusions.length} 条（排除记录优先于准入规则）、个案审批 ${approvals.length} 条（CASE_BY_CASE 的放行依据），均在首页维护。</p></details>
  <details open><summary>C2 监管规则 · ${c2on.length}/${c2rules.length} 条生效<a href="/c2-regulatory.html" onclick="event.stopPropagation()">去维护 →</a></summary><div class="scroll"><table><thead><tr><th>规则</th><th>币种</th><th>受益行国家</th><th>必需能力标签</th><th>状态</th></tr></thead><tbody>${c2rules.map(x => `<tr><td>${esc(x.ruleId)} · ${esc(x.ruleName)}${x.triggerCondition ? `<br><span class="muted">附加触发条件：${esc(x.triggerText)}</span>${x.triggerProblem ? ` <span class="bad" title="${esc(x.triggerProblem)}">已失效，按触发处理</span>` : ''}` : ''}</td>${x.legacy ? `<td colspan="3">旧版条件：${esc(condText(x.condition))}</td>` : `<td>${esc(x.currency)}</td><td>${esc(x.bankCountry)}</td><td>${esc(x.requirement.requiredTags.join(', '))}</td>`}<td>${x.enabled ? '<span class="ok">生效</span>' : '<span class="bad">停用</span>'}</td></tr>`).join('')}</tbody></table></div><p class="muted">另有 C2 能力标签限制 ${restrictions.length} 条（合作方维度，命中即不满足）与能力标签配置，见 C2 页面。Check 只提交表单里的交易要素：附加触发条件引用了表单之外字段的监管规则，在这里按未触发处理。</p></details>`;
    } catch (e) {
        el('rulesSummary').innerHTML = `<p class="bad">C1/C2 规则加载失败：${esc(e.message)}</p>`;
    }
}

init();
loadRulesSummary();

if (location.hash === '#configPanel') el('configPanel').open = true;
