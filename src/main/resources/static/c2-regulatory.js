'use strict';
const c2$ = id => document.getElementById(id);
const c2Esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const c2Api = async (path, opt = {}) => {
    const res = await fetch('/api/c2' + path, {
        ...opt,
        headers: {'Content-Type': 'application/json', ...(opt.headers || {})}
    });
    const data = await res.json().catch(() => ({error: '服务器未返回 JSON'}));
    if (!res.ok) throw new Error(data.error || data.message || '请求失败：' + res.status);
    return data;
};
const c2State = {
    currencies: [], countries: [], tags: [], rules: [], requiredTags: [],
    editingId: null, regVersion: null, legacyCondition: null
};

function c2CountryLabel(code) {
    const country = c2State.countries.find(x => x.code === code);
    return country && country.name ? `${code} · ${country.name}` : code;
}

function c2TagName(code) {
    return c2State.tags.find(t => t.tagCode === code)?.tagName || code;
}

/** 勾选的能力标签按字典顺序展示 */
function c2SelectedTags() {
    return c2State.tags.map(t => t.tagCode).filter(code => c2State.requiredTags.includes(code))
        .concat(c2State.requiredTags.filter(code => !c2State.tags.some(t => t.tagCode === code)));
}

function c2FillScopeOptions() {
    c2$('c2Currency').innerHTML = c2State.currencies.length
        ? c2State.currencies.map(v => `<option value="${c2Esc(v)}">${c2Esc(v)}</option>`).join('')
        : '<option value="">字段库缺少「交易币种」可选值</option>';
    c2$('c2BankCountry').innerHTML = c2State.countries.map(x =>
        `<option value="${c2Esc(x.code)}">${c2Esc(x.name ? `${x.code} · ${x.name}` : x.code)}</option>`).join('');
}

/** 标准规则直接回填范围；旧版规则按原条件树里的币种、国家尽量回填，保存后即转为标准形态。 */
function c2SetScope(r) {
    const pick = r.legacy ? c2LegacyPicks(r.condition) : r;
    const currency = String(pick.currency || '').toUpperCase(), country = String(pick.bankCountry || '').toUpperCase();
    c2$('c2Currency').value = c2State.currencies.includes(currency) ? currency : (c2State.currencies[0] || '');
    c2$('c2BankCountry').value = c2State.countries.some(x => x.code === country) ? country : 'ALL';
}

function c2LegacyPicks(tree) {
    const picks = {currency: null, bankCountry: null};
    (function walk(node) {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node.children)) return node.children.forEach(walk);
        const value = node.operator === 'IN' ? (Array.isArray(node.value) ? node.value[0] : node.value) : node.value;
        if (node.operator !== 'NE' && typeof value === 'string') {
            if (node.field === 'paymentCurrency' && !picks.currency) picks.currency = value;
            if (node.field === 'beneficiaryBankCountry' && !picks.bankCountry) picks.bankCountry = value;
        }
    })(tree);
    return picks;
}

function c2RenderPreview() {
    const tags = c2SelectedTags();
    const scope = `当交易币种为 <b>${c2Esc(c2$('c2Currency').value || '—')}</b> 且受益行国家为 <b>${c2Esc(c2CountryLabel(c2$('c2BankCountry').value))}</b> 时`;
    const body = tags.length
        ? `${scope}，候选合作方须具备 ${tags.map(code => `<b title="${c2Esc(c2TagName(code))}">${c2Esc(code)}</b>`).join('、')}（共 ${tags.length} 项，须全部具备）；缺少任一项即剔除该候选路由，返回 <b>REG_RULE_NOT_MET</b>。`
        : `${scope}：<span class="c2-warn">尚未选择能力标签，保存前至少勾选一项。</span>`;
    const legacy = c2State.legacyCondition ? `<div class="c2-legacy">该规则由旧版条件树创建，当前仍按原条件执行：<span class="mono">${c2Esc(c2State.legacyCondition)}</span>。
  请核对下方范围后保存，保存后改为按「币种 + 受益行国家」执行。</div>` : '';
    c2$('c2RegPreview').innerHTML = legacy + `<div>${body}</div>`;
}

function c2RenderRequiredTags() {
    c2$('c2RequiredTags').innerHTML = c2State.tags.map(t =>
        `<label class="tagcheck"><input type="checkbox" value="${c2Esc(t.tagCode)}" ${c2State.requiredTags.includes(t.tagCode) ? 'checked' : ''} onchange="c2UpdateRequiredTag(this)"><span>${c2Esc(t.tagCode)}</span><span class="muted">${c2Esc(t.tagName)}</span></label>`).join('')
        || '<div class="muted">能力标签字典为空。</div>';
}

function c2UpdateRequiredTag(el) {
    const tag = el.value;
    if (el.checked) {
        if (!c2State.requiredTags.includes(tag)) c2State.requiredTags.push(tag);
    } else c2State.requiredTags = c2State.requiredTags.filter(x => x !== tag);
    c2RenderPreview();
}

function c2RenderList() {
    const tags = r => c2SelectedTagsOf(r).map(code => `<span class="tagchip" title="${c2Esc(c2TagName(code))}">${c2Esc(code)}</span>`).join('');
    c2$('c2RegCount').textContent = c2State.rules.length ? `共 ${c2State.rules.length} 条` : '';
    c2$('c2RegRows').innerHTML = c2State.rules.map(r => `<tr class="${c2State.editingId === r.ruleId ? 'row-selected' : ''}">
  <td>${c2Esc(r.ruleId)}</td><td>${c2Esc(r.ruleName)}</td>
  ${r.legacy ? `<td colspan="2"><span class="badge pending">旧版条件</span> <span class="mono">${c2Esc(r.conditionText)}</span></td>` : `<td>${c2Esc(r.currency)}</td><td>${c2Esc(c2CountryLabel(r.bankCountry))}</td>`}
  <td class="tags-cell">${tags(r)}</td><td>v${r.versionNo}</td>
  <td>${r.enabled ? '<span class="dot"></span>启用' : '停用'}</td>
  <td><button type="button" class="btn secondary tiny" data-reg-edit="${c2Esc(r.ruleId)}">编辑</button> <button type="button" class="btn secondary tiny" data-reg-toggle="${c2Esc(r.ruleId)}">${r.enabled ? '停用' : '启用'}</button> <button type="button" class="btn secondary tiny" data-reg-delete="${c2Esc(r.ruleId)}">删除</button></td>
  </tr>`).join('') || '<tr><td colspan="8" class="muted">暂无监管规则，点击右上角「新增监管规则」创建。</td></tr>';
}

function c2SelectedTagsOf(rule) {
    const order = c2State.tags.map(t => t.tagCode);
    return rule.requirement.requiredTags.map(String).slice().sort((a, b) => {
        const x = order.includes(a) ? order.indexOf(a) : order.length, y = order.includes(b) ? order.indexOf(b) : order.length;
        return x - y || a.localeCompare(b);
    });
}

function c2DateNow() {
    return new Date().toLocaleString('sv-SE', {
        timeZone: 'Asia/Hong_Kong',
        hour12: false
    }).replace(' ', 'T').slice(0, 16);
}

function c2NewRegulatory(scroll = true) {
    c2State.editingId = null;
    c2State.regVersion = null;
    c2State.legacyCondition = null;
    c2State.requiredTags = [];
    c2$('c2FormTitle').textContent = '新增监管规则';
    let next = 1;
    while (c2State.rules.some(r => r.ruleId === `REG-${String(next).padStart(3, '0')}`)) next++;
    c2$('c2RuleId').value = `REG-${String(next).padStart(3, '0')}`;
    c2$('c2RuleId').disabled = false;
    c2$('c2RuleName').value = '';
    c2$('c2From').value = c2DateNow();
    c2$('c2To').value = '';
    c2$('c2Enabled').value = 'true';
    c2$('c2Remark').value = '';
    c2$('c2Currency').value = c2State.currencies[0] || '';
    c2$('c2BankCountry').value = 'ALL';
    c2$('c2RegError').textContent = '';
    c2$('c2RegGood').textContent = '';
    c2RenderRequiredTags();
    c2RenderPreview();
    c2RenderList();
    if (scroll) c2$('c2RegForm').scrollIntoView({behavior: 'smooth', block: 'start'});
}

function c2EditRegulatory(id, scroll = true) {
    const r = c2State.rules.find(x => x.ruleId === id);
    if (!r) return;
    c2State.editingId = r.ruleId;
    c2State.regVersion = r.versionNo;
    c2State.requiredTags = [...r.requirement.requiredTags];
    c2State.legacyCondition = r.legacy ? r.conditionText : null;
    c2$('c2FormTitle').textContent = '编辑监管规则 · ' + id;
    c2$('c2RuleId').value = id;
    c2$('c2RuleId').disabled = true;
    c2$('c2RuleName').value = r.ruleName;
    c2$('c2From').value = r.effectiveFrom?.slice(0, 16) || '';
    c2$('c2To').value = r.effectiveTo?.slice(0, 16) || '';
    c2$('c2Enabled').value = String(r.enabled);
    c2$('c2Remark').value = r.remark || '';
    c2SetScope(r);
    c2$('c2RegError').textContent = '';
    c2$('c2RegGood').textContent = '';
    c2RenderRequiredTags();
    c2RenderPreview();
    c2RenderList();
    if (scroll) c2$('c2RegForm').scrollIntoView({behavior: 'smooth', block: 'start'});
}

function c2RegulatoryRequest() {
    return {
        ruleId: c2$('c2RuleId').value.trim(),
        ruleName: c2$('c2RuleName').value.trim(),
        ruleCode: 'C2',
        scope: 'GLOBAL',
        currency: c2$('c2Currency').value,
        bankCountry: c2$('c2BankCountry').value,
        requirement: {type: 'PROVIDER_CAPABILITY', requiredTags: [...c2State.requiredTags]},
        onFailure: {reasonCode: 'REG_RULE_NOT_MET'},
        enabled: c2$('c2Enabled').value === 'true',
        versionNo: c2State.regVersion,
        effectiveFrom: c2$('c2From').value || null,
        effectiveTo: c2$('c2To').value || null,
        remark: c2$('c2Remark').value.trim() || null
    };
}

async function c2CopyJson() {
    try {
        await navigator.clipboard.writeText(JSON.stringify(c2RegulatoryRequest(), null, 2));
        c2$('c2RegGood').textContent = '已复制到剪贴板';
    } catch (e) {
        c2$('c2RegError').textContent = '浏览器禁止复制，请使用开发者工具查看';
    }
}

async function c2SaveRegulatory() {
    c2$('c2RegError').textContent = '';
    c2$('c2RegGood').textContent = '';
    try {
        const req = c2RegulatoryRequest();
        if (!c2State.editingId && c2State.rules.some(r => r.ruleId === req.ruleId)) throw new Error('规则编码已存在，请编辑现有规则或更换编码');
        if (!req.ruleName) throw new Error('请填写规则名称');
        if (!c2State.requiredTags.length) throw new Error('请至少选择一个能力标签');
        if (!req.effectiveFrom) throw new Error('请填写生效时间');
        if (req.effectiveTo && req.effectiveTo <= req.effectiveFrom) throw new Error('失效时间必须晚于生效时间');
        const saved = await c2Api('/regulatory-rules', {method: 'POST', body: JSON.stringify(req)});
        await c2LoadRegulatory();
        c2EditRegulatory(saved.ruleId, false);
        c2$('c2RegGood').textContent = '保存成功 · 规则 ' + saved.ruleId + ' · 版本 ' + saved.versionNo;
    } catch (e) {
        c2$('c2RegError').textContent = e.message;
    }
}

async function c2ToggleRegulatory(id) {
    if (!confirm('确定切换监管规则 ' + id + ' 的启用状态？')) return;
    try {
        await c2Api('/regulatory-rules/' + encodeURIComponent(id) + '/toggle', {method: 'POST'});
        await c2LoadRegulatory();
        if (c2State.editingId === id) c2EditRegulatory(id, false);
    } catch (e) {
        alert(e.message);
    }
}

async function c2LoadRegulatory() {
    c2State.rules = await c2Api('/regulatory-rules');
    c2RenderList();
}

async function c2DeleteRegulatory(id) {
    const rule = c2State.rules.find(r => r.ruleId === id);
    if (!rule || !confirm(`确定删除全局监管规则 ${id}？删除后所有合作方都不再执行此规则。`)) return;
    c2$('c2RegError').textContent = '';
    try {
        await c2Api('/regulatory-rules/' + encodeURIComponent(id) + '?versionNo=' + rule.versionNo, {method: 'DELETE'});
        await c2LoadRegulatory();
        if (c2State.rules.length) c2EditRegulatory(c2State.rules[0].ruleId, false);
        else c2NewRegulatory(false);
        c2$('c2RegGood').textContent = `全局规则 ${id} 已删除`;
    } catch (error) {
        c2$('c2RegError').textContent = error.message;
    }
}

c2$('c2RegRows').addEventListener('click', event => {
    const edit = event.target.closest('[data-reg-edit]');
    if (edit) {
        c2EditRegulatory(edit.dataset.regEdit);
        return;
    }
    const toggle = event.target.closest('[data-reg-toggle]');
    if (toggle) {
        c2ToggleRegulatory(toggle.dataset.regToggle);
        return;
    }
    const remove = event.target.closest('[data-reg-delete]');
    if (remove) c2DeleteRegulatory(remove.dataset.regDelete);
});

async function c2InitRegulatory() {
    try {
        const [options, rules] = await Promise.all([c2Api('/regulatory-rules/options'), c2Api('/regulatory-rules')]);
        c2State.currencies = options.currencies || [];
        c2State.countries = options.countries || [];
        c2State.tags = options.tags || [];
        c2State.rules = rules;
        c2FillScopeOptions();
        if (rules.length) c2EditRegulatory(rules[0].ruleId, false);
        else c2NewRegulatory(false);
    } catch (error) {
        c2$('c2RegError').textContent = error.message;
    }
}

c2InitRegulatory();
