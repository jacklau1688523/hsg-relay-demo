'use strict';
// 监管路由规则：列表、筛选、启停和删除。新增 / 编辑（适用范围、能力标签、附加触发条件）在独立页面 /c2-regulatory-edit.html；
// 共用的接口、字典和展示规则在 c2-regulatory-common.js（须先引入）。
const state = {rules: [], busy: false};

// 有效期提示：未到生效时间、已过失效时间的规则当前不执行（判断口径与引擎一致）
const VALIDITY = {PENDING: ['pending', '未到生效时间'], EXPIRED: ['fail', '已过失效时间']};

function render() {
    const keyword = c2$('search').value.trim().toLowerCase(), currency = c2$('filterCurrency').value,
        status = c2$('filterStatus').value, trigger = c2$('filterTrigger').value;
    const shown = state.rules.filter(r => (!keyword || [r.ruleId, r.ruleName].some(v => String(v || '').toLowerCase().includes(keyword)))
        && (!currency || r.currency === currency)
        && (!status || String(r.enabled) === status)
        // 「附加条件已失效」是「有附加条件」里字段在元数据中心被停用等情况导致条件无法执行的那部分
        && (!trigger || (trigger === 'without' ? !r.triggerCondition : trigger === 'stale' ? !!r.triggerProblem : !!r.triggerCondition)));
    c2$('rows').innerHTML = shown.map(r => {
        const code = encodeURIComponent(r.ruleId), validity = VALIDITY[c2Validity(r)];
        // 旧版规则仍按原自由条件树执行，表单里无法用「币种 + 受益行国家」表达，进入编辑页重新保存后转为标准形态
        const scope = r.legacy
            ? `<span class="badge pending">旧版条件</span><br><span class="mono">${c2Esc(r.conditionText)}</span>`
            : `<b>${c2Esc(r.currency)}</b> 且受益行国家 ${c2Esc(c2CountryLabel(r.bankCountry))}`;
        const condition = r.triggerCondition
            ? `<span class="badge ${r.triggerProblem ? 'pending' : 'pass'}"${r.triggerProblem ? ` title="${c2Esc(r.triggerProblem)}"` : ''}>${r.triggerProblem ? '附加条件已失效' : '附加触发条件'}</span><br><span class="mono">${c2Esc(r.triggerText)}</span>`
            : '<span class="muted">无 · 落在适用范围内即触发</span>';
        const period = `${c2Esc(c2ShowTime(r.effectiveFrom))} 起<br>${r.effectiveTo ? c2ShowTime(r.effectiveTo) + ' 止' : '<span class="muted">长期有效</span>'}${validity ? `<br><span class="badge ${validity[0]}">${validity[1]}</span>` : ''}`;
        return `<tr><td><b>${c2Esc(r.ruleId)}</b><br>${c2Esc(r.ruleName)}</td><td>${scope}</td><td class="wrap">${condition}</td><td class="tags-cell">${c2SortedTags(r.requirement.requiredTags).map(tag => `<span class="tagchip" title="${c2Esc(c2TagName(tag))}">${c2Esc(tag)}</span>`).join('')}</td><td>${period}</td><td>${r.enabled ? '<span class="dot"></span>启用' : '停用'}</td><td>v${r.versionNo}</td><td class="nowrap"><a class="btn secondary tiny" href="/c2-regulatory-edit.html?rule=${code}">编辑</a><button type="button" class="btn secondary tiny" data-toggle="${code}">${r.enabled ? '停用' : '启用'}</button><button type="button" class="btn secondary tiny" data-delete="${code}">删除</button></td></tr>`;
    }).join('') || `<tr><td colspan="8">${state.rules.length ? '没有符合筛选条件的监管规则' : '暂无监管规则，点击右上角「新增监管规则」创建'}</td></tr>`;
    // 当前生效：启用且在有效期内
    const metrics = [
        ['规则总数', state.rules.length],
        ['当前生效', state.rules.filter(r => r.enabled && c2Validity(r) === 'ACTIVE').length],
        ['已停用', state.rules.filter(r => !r.enabled).length],
        ['附加条件已失效', state.rules.filter(r => r.triggerProblem).length]
    ];
    c2$('metrics').innerHTML = metrics.map(([title, count]) => `<div class="metric"><span>${title}</span><b>${count}</b></div>`).join('');
}

async function reload() {
    state.rules = await c2Api('/regulatory-rules');
    render();
    c2$('pageError').textContent = '';
    c2$('pageNote').textContent = '';
    markReloaded();
}

// 启停：停用后所有合作方的 C2 判定都不再执行此规则，启用后立即恢复执行
async function toggle(rule) {
    const on = !rule.enabled;
    if (!confirm(on ? `确定启用监管规则 ${rule.ruleId}？启用后对所有合作方的 C2 判定生效。`
        : `确定停用监管规则 ${rule.ruleId}？停用后所有合作方的 C2 判定都不再执行此规则。`)) return;
    try {
        await c2Api(`/regulatory-rules/${encodeURIComponent(rule.ruleId)}/toggle`, {method: 'POST'});
        c2MarkChanged();
        await reload();
        c2$('pageNote').textContent = `已${on ? '启用' : '停用'}监管规则 ${rule.ruleId}。`;
    } catch (e) {
        // 多为列表已过期（版本变化）：重新读取后保留原因
        await reload().catch(() => {
        });
        c2$('pageError').textContent = e.message;
    }
}

// 删除：版本号取列表加载时的值，规则在别处被改过时服务端拒绝
async function remove(rule) {
    if (!confirm(`确定删除全局监管规则 ${rule.ruleId}（${rule.ruleName}）？删除后所有合作方都不再执行此规则，且不可恢复。`)) return;
    try {
        await c2Api(`/regulatory-rules/${encodeURIComponent(rule.ruleId)}?versionNo=${rule.versionNo}`, {method: 'DELETE'});
        c2MarkChanged();
        await reload();
        c2$('pageNote').textContent = `已删除监管规则 ${rule.ruleId}。`;
    } catch (e) {
        await reload().catch(() => {
        });
        c2$('pageError').textContent = e.message;
    }
}

c2$('refresh').onclick = () => reload().catch(e => c2$('pageError').textContent = e.message);
c2$('query').onclick = render;
c2$('search').oninput = render;
for (const id of ['filterCurrency', 'filterStatus', 'filterTrigger']) c2$(id).onchange = render;
c2$('rows').onclick = async e => {
    const button = e.target.closest('[data-toggle], [data-delete]');
    // 确认框关闭后请求仍在进行时列表行还未刷新：忽略重复点击
    if (!button || state.busy) return;
    state.busy = true;
    try {
        const rule = state.rules.find(r => r.ruleId === decodeURIComponent(button.dataset.toggle || button.dataset.delete));
        if (rule) await (button.dataset.toggle ? toggle(rule) : remove(rule));
    } finally {
        state.busy = false;
    }
};
const markReloaded = c2ReloadIfChangedSinceLoad();

(async () => {
    try {
        const [, rules] = await Promise.all([c2LoadDict(), c2Api('/regulatory-rules')]);
        c2$('filterCurrency').innerHTML = '<option value="">全部币种</option>' + c2Dict.currencies.map(v => `<option value="${c2Esc(v)}">${c2Esc(v)}</option>`).join('');
        state.rules = rules;
        render();
    } catch (e) {
        c2$('pageError').textContent = e.message;
    }
})();
