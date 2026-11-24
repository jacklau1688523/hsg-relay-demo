'use strict';
const c2$ = id => document.getElementById(id);
const c2Esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const c2Key = str => String(str || '');
const c2Api = async (path, opt = {}) => {
    const res = await fetch('/api/c2' + path, {
        ...opt,
        headers: {'Content-Type': 'application/json', ...(opt.headers || {})}
    });
    const data = await res.json().catch(() => ({error: '服务器未返回 JSON'}));
    if (!res.ok) throw new Error(data.error || data.message || '请求失败：' + res.status);
    return data;
};
const c2State = {providers: [], fields: [], rules: []};
// 模拟面板有专门输入项的九个交易要素；字段库里其余已启用的 C2 字段由 c2RenderVariableInputs 自动生成取值控件
const c2FixedFields = ['paymentCurrency', 'debitCurrency', 'beneficiaryBankCountry', 'beneficiaryAddressCountry', 'beneficiaryRegion', 'orderingCountry', 'clientCountry', 'intermediaryCountry', 'purpose'];

function c2Field(code) {
    return c2State.fields.find(f => f.fieldCode === code) || c2State.fields[0];
}

async function c2SyncSimCapabilities() {
    try {
        const tags = await c2Api('/providers/' + encodeURIComponent(c2$('c2SimProvider').value) + '/capabilities');
        c2$('c2SimTags').textContent = tags.join(', ') || '无';
    } catch (e) {
        c2$('c2SimTags').textContent = e.message;
    }
}

async function c2Simulate() {
    const keys = {
        paymentCurrency: 'c2SimPaymentCurrency',
        debitCurrency: 'c2SimDebitCurrency',
        beneficiaryBankCountry: 'c2SimBeneficiaryBankCountry',
        beneficiaryAddressCountry: 'c2SimBeneficiaryAddressCountry',
        beneficiaryRegion: 'c2SimBeneficiaryRegion',
        orderingCountry: 'c2SimOrderingCountry',
        clientCountry: 'c2SimClientCountry',
        intermediaryCountry: 'c2SimIntermediaryCountry',
        purpose: 'c2SimPurpose'
    };
    const req = {
        providerCode: c2$('c2SimProvider').value,
        directorUboNationalities: c2$('c2SimNationalities').value.split(',').map(x => x.trim()).filter(Boolean),
        variables: {}
    };
    for (const [key, id] of Object.entries(keys)) req[key] = c2$(id).value || null;
    // 附加触发条件字段的取值：选「（不提供）」或留空的不提交，该字段按「本次没有取值」处理
    for (const field of c2State.fields.filter(f => !c2FixedFields.includes(f.fieldCode))) {
        const value = c2$('c2Var_' + field.fieldCode)?.value.trim();
        if (value) req.variables[field.fieldCode] = value;
    }
    c2$('c2SimResult').innerHTML = '<div class="notice">模拟计算中...</div>';
    try {
        const res = await c2Api('/simulate', {method: 'POST', body: JSON.stringify(req)});
        let html = `<div class="result ${res.passed ? '' : 'error'}">${res.passed ? '✓ C2 校验通过' : '✕ C2 校验失败'}</div>
 <p class="caps">返回原因码：<b>${c2Esc(res.reasonCode)}</b></p><p class="muted">${c2Esc(res.note)}</p>`;
        if (res.hits.length) {
            html += '<h2>命中限制（按 C2 序号排序）</h2>';
            html += res.hits.map(h => `<div class="trace"><span class="badge fail">${c2Esc(h.reasonCode)}</span>
 <div style="margin-top:5px"><b>${c2Esc(h.ruleId)}</b> · ${c2Esc(h.field)}</div><div class="muted">${c2Esc(h.detail)}</div></div>`).join('');
        }
        if (res.regulatoryChecks.length) {
            html += '<h2 style="margin:15px 0 8px">监管规则执行过程</h2>';
            html += res.regulatoryChecks.map(r => {
                // 附加条件编译出了表达式却未触发、也没有缺值 / 失效说明：说明范围已命中，是附加条件本身未命中
                const miss = r.triggerExpression && !r.triggered && !r.note
                    ? '交易落在规则范围内，但附加触发条件未命中'
                    : `条件未命中（本次交易币种 / 受益行国家：${c2Esc(req.paymentCurrency)} / ${c2Esc(req.beneficiaryBankCountry)}）`;
                return `<div class="trace"><b>${c2Esc(r.ruleId)}</b>
 <span class="badge ${r.triggered ? 'pending' : 'pass'}">${r.triggered ? '触发' : '未触发'}</span>
 <div class="muted">${r.triggered ? '缺失标签：' + (r.missingTags.join(', ') || '无') : miss}</div>
 <div class="code" style="margin-top:6px">${c2Esc(r.condition)}</div>${r.triggerCondition ? `<div class="code" style="margin-top:4px">附加触发条件：${c2Esc(r.triggerCondition)}${r.triggerExpression ? `\n表达式：${c2Esc(r.triggerExpression)}` : ''}</div>` : ''}${r.note ? `<div class="c2-note">${c2Esc(r.note)}</div>` : ''}${r.expression ? `<div class="muted" style="margin-top:4px">旧版条件树，按原表达式执行：${c2Esc(r.expression)}</div>` : ''}</div>`;
            }).join('');
        }
        c2$('c2SimResult').innerHTML = html;
    } catch (e) {
        c2$('c2SimResult').innerHTML = `<div class="result error">${c2Esc(e.message)}</div>`;
    }
}

function c2FillSelect(id, fieldCode, defaultValue, optional = false) {
    const field = c2Field(fieldCode);
    const options = (optional ? '<option value="">— 不填写 —</option>' : '') + field.options.map(v => `<option value="${c2Esc(v)}" ${v === defaultValue ? 'selected' : ''}>${c2Esc(v)}</option>`).join('');
    c2$(id).innerHTML = options;
    c2$(id).value = defaultValue || '';
}

/** 监管规则可按任意 ISO 国家配置；受益行国家在字段库可选值之外补齐其余 ISO 代码，这类规则才能在这里模拟。 */
function c2AddIsoCountries(id, countries) {
    const select = c2$(id), known = new Set(Array.from(select.options, option => option.value.toUpperCase()));
    select.insertAdjacentHTML('beforeend', countries.filter(x => x.code !== 'ALL' && !known.has(x.code))
        .map(x => `<option value="${c2Esc(x.code)}">${c2Esc(x.name ? `${x.code} · ${x.name}` : x.code)}</option>`).join(''));
}

/** 模拟面板为字段库里其余已启用的 C2 字段（九个固定交易要素之外）自动生成取值控件；保留仍有效的已填取值。 */
function c2RenderVariableInputs() {
    const extra = c2State.fields.filter(f => !c2FixedFields.includes(f.fieldCode));
    const kept = Object.fromEntries(extra.map(f => [f.fieldCode, c2$('c2Var_' + f.fieldCode)?.value.trim() || '']));
    c2$('c2SimVariables').innerHTML = extra.length ? '<div class="c2-subhead">附加触发条件字段（按字段库生成）</div><div class="layout2">' + extra.map(f => {
        const id = 'c2Var_' + f.fieldCode, label = `${c2Esc(f.fieldName)} <span class="muted">${c2Esc(f.fieldCode)}</span>`;
        const blank = '<option value="">（不提供）</option>';
        const control = f.dataType === 'BOOLEAN'
            ? `<select id="${id}" aria-label="${c2Esc(f.fieldName)}">${blank}<option value="true">true</option><option value="false">false</option></select>`
            : ['DECIMAL', 'INTEGER'].includes(f.dataType)
                ? `<input id="${id}" type="number" step="any" placeholder="（不提供）" aria-label="${c2Esc(f.fieldName)}">`
                : f.options.length
                    ? `<select id="${id}" aria-label="${c2Esc(f.fieldName)}">${blank}${f.options.map(v => `<option value="${c2Esc(v)}">${c2Esc(v)}</option>`).join('')}</select>`
                    : `<input id="${id}" placeholder="（不提供）" aria-label="${c2Esc(f.fieldName)}">`;
        return `<div class="field"><label>${label}</label>${control}</div>`;
    }).join('') + '</div>' : '';
    // 重绘（例如点「刷新规则 / 字段库」）后恢复之前填的取值；该值已不是可选项时不恢复
    for (const f of extra) {
        const input = c2$('c2Var_' + f.fieldCode), value = kept[f.fieldCode];
        if (value && (input.tagName !== 'SELECT' || Array.from(input.options, o => o.value).includes(value))) input.value = value;
    }
}

// ---- ① 触发条件：按字段库构建（监管路由规则的附加触发条件） ----
// ruleId：左侧当前选中的规则；version：编辑器载入条件时的规则版本（保存时带上）；edited：操作员改过条件；
// substituted：载入的条件引用了当前不可用的字段 / 运算符，编辑器里已替换成可用项（与已保存的条件不同）
const c2Trigger = {tree: null, ruleId: new URLSearchParams(location.search).get('rule'), version: null, edited: false, substituted: false, shown: []};
const c2Operators = {EQ: '等于 =', NE: '不等于 ≠', IN: '属于 IN', GT: '大于 >', GTE: '大于等于 ≥', LT: '小于 <', LTE: '小于等于 ≤'};
const c2TriggerRule = () => c2State.rules.find(r => r.ruleId === c2Trigger.ruleId) || null;
const c2DefaultValue = f => f?.options?.[0] || (f?.dataType === 'BOOLEAN' ? 'true' : ['DECIMAL', 'INTEGER'].includes(f?.dataType) ? '0' : '');
const c2DefaultLeaf = () => {
    const f = c2State.fields[0];
    return {field: f?.fieldCode || '', operator: f?.operators?.[0] || 'EQ', value: c2DefaultValue(f)};
};

function c2Node(path) {
    let node = c2Trigger.tree;
    for (const part of path.split('.').filter(Boolean)) node = node.children[Number(part)];
    return node;
}

function c2RemoveTriggerNode(path) {
    const parts = path.split('.'), index = Number(parts.pop()), parent = c2Node(parts.join('.'));
    if (parent.children.length === 1) {
        alert('条件组至少保留一项');
        return;
    }
    parent.children.splice(index, 1);
    c2Trigger.edited = true;
    c2RenderTriggerTree();
}

function c2AddTriggerNode(path, kind) {
    const group = c2Node(path);
    if (group.children.length >= 20) {
        alert('每组最多20项');
        return;
    }
    if (kind === 'group' && path.split('.').filter(Boolean).length >= 5) {
        alert('最多6层条件组');
        return;
    }
    group.children.push(kind === 'group' ? {logic: 'OR', children: [c2DefaultLeaf()]} : c2DefaultLeaf());
    c2Trigger.edited = true;
    c2RenderTriggerTree();
}

function c2HasOptions(node) {
    const f = c2State.fields.find(x => x.fieldCode === node.field);
    return node.operator !== 'IN' && (f?.dataType === 'BOOLEAN' || f?.options?.length > 0);
}

function c2ChangeTriggerNode(path, key, value) {
    const node = c2Node(path);
    node[key] = value;
    if (key === 'field') {
        const f = c2State.fields.find(x => x.fieldCode === value);
        node.operator = f?.operators?.[0] || 'EQ';
        node.value = c2DefaultValue(f);
    }
    c2Trigger.edited = true;
    // 有下拉可选值时改值需要重绘以清掉失效提示；文本输入不重绘，避免打断输入，只更新状态提示
    if (key === 'field' || key === 'operator' || key === 'logic' || (key === 'value' && c2Trigger.shown.length && c2HasOptions(node))) c2RenderTriggerTree();
    else c2RenderTriggerStatus();
}

function c2RenderTriggerLeaf(node, path, notices) {
    const fields = c2State.fields, f = fields.find(x => x.fieldCode === node.field) || fields[0];
    if (!f) return '<div class="err">请先在元数据中心启用适用规则含 C2 的字段</div>';
    // 已保存条件引用的字段在元数据中心停用或不再适用 C2 时，换成当前首个可用字段，并提示核对后再保存
    if (!fields.some(x => x.fieldCode === node.field)) {
        notices.push(`字段 ${node.field || '（空）'} 已在元数据中心停用或不再适用 C2，对应条件已换成当前可用字段，请核对后再保存`);
        Object.assign(node, c2DefaultLeaf());
        c2Trigger.substituted = true;
    }
    // 已保存条件使用的运算符被元数据中心取消时同样换成该字段的第一个运算符
    if (!f.operators.includes(node.operator)) {
        notices.push(`字段 ${f.fieldName} 已不支持运算符「${c2Operators[node.operator] || node.operator || '（空）'}」，对应条件已换成「${c2Operators[f.operators[0]]}」，请核对后再保存`);
        node.operator = f.operators[0];
        c2Trigger.substituted = true;
    }
    const values = node.operator !== 'IN' ? (f.dataType === 'BOOLEAN' ? ['true', 'false'] : f.options) : [];
    // 还没有取值时（例如刚从 IN 的文本框切到 EQ）取第一个可选值，与下拉实际显示的保持一致
    if (values.length && !String(node.value ?? '').trim()) node.value = values[0];
    const current = String(node.value ?? '');
    // 已保存的条件值不在元数据中心当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
    const stale = values.length && !values.includes(current);
    if (stale) notices.push(`字段 ${f.fieldName} 的条件值「${current}」不在元数据中心当前可选值中，请重新选择`);
    const listed = stale ? [current, ...values] : values;
    const change = `onchange="c2ChangeTriggerNode('${path}','value',this.value)"`;
    const control = values.length
        ? `<select aria-label="条件值" ${change}>${listed.map(v => `<option value="${c2Esc(v)}" ${v === current ? 'selected' : ''}>${c2Esc(v)}${stale && v === current ? '（已不在可选值中）' : ''}</option>`).join('')}</select>`
        : `<input aria-label="条件值" value="${c2Esc(node.value)}" placeholder="${node.operator === 'IN' ? '多个值用逗号分隔' : '输入条件值'}" oninput="c2ChangeTriggerNode('${path}','value',this.value)" ${['DECIMAL', 'INTEGER'].includes(f.dataType) ? 'type="number" step="any"' : ''}>`;
    return `<div class="c2-line"><select aria-label="条件字段" onchange="c2ChangeTriggerNode('${path}','field',this.value)">${fields.map(x => `<option value="${c2Esc(x.fieldCode)}" ${x.fieldCode === node.field ? 'selected' : ''}>${c2Esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="c2ChangeTriggerNode('${path}','operator',this.value)">${f.operators.map(op => `<option value="${c2Esc(op)}" ${op === node.operator ? 'selected' : ''}>${c2Esc(c2Operators[op] || op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c2RemoveTriggerNode('${path}')" aria-label="删除此条件">✕</button></div>`;
}

function c2RenderTriggerGroup(group, path, depth, notices) {
    const logic = group.logic === 'OR' ? 'OR' : 'AND';
    group.logic = logic;
    const head = `<div class="c2-head"><strong>${depth ? '子条件组' : '根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c2ChangeTriggerNode('${path}','logic',this.value)"><option value="AND" ${logic === 'AND' ? 'selected' : ''}>全部满足 AND</option><option value="OR" ${logic === 'OR' ? 'selected' : ''}>任一满足 OR</option></select>${depth ? `<button type="button" class="btn secondary tiny" onclick="c2RemoveTriggerNode('${path}')">删除子组</button>` : ''}</div>`;
    const body = group.children.map((child, i) => {
        const childPath = path ? path + '.' + i : String(i);
        return `${i ? `<div class="c2-join">${logic}</div>` : ''}${child.children ? c2RenderTriggerGroup(child, childPath, depth + 1, notices) : c2RenderTriggerLeaf(child, childPath, notices)}`;
    }).join('');
    return `<div class="c2-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c2AddTriggerNode('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c2AddTriggerNode('${path}','group')">▧ 添加子条件组</button></div></div>`;
}

function c2RenderTriggerTree() {
    const notices = [];
    c2$('c2TriggerTree').innerHTML = c2State.fields.length ? c2RenderTriggerGroup(c2Trigger.tree, '', 0, notices) : '<div class="err">元数据中心没有已启用且适用规则含 C2 的字段，请先在元数据中心配置。</div>';
    c2Trigger.shown = [...new Set(notices)];
    c2RenderTriggerStatus();
}

/** 表达式区的提示：已保存条件失效、编辑器替换掉的失效字段 / 条件值，以及当前条件相对已保存条件的状态。 */
function c2RenderTriggerStatus() {
    const rule = c2TriggerRule(), lines = [];
    if (rule?.triggerProblem) lines.push(`已保存的附加触发条件已失效（${rule.triggerProblem}）：失效期间交易只要落在规则范围内规则即触发，请核对后重新保存`);
    lines.push(...c2Trigger.shown);
    lines.push(c2Trigger.edited
        ? rule?.triggerCondition ? '附加触发条件已修改，请重新校验并保存' : '附加触发条件尚未保存，请校验后保存'
        : c2Trigger.substituted
            ? '编辑器里的条件与已保存的不同（不可用的字段或运算符已换成当前可用项），请核对后重新校验并保存'
            : rule?.triggerCondition ? '当前显示的是已保存的附加触发条件'
                : '该规则当前没有附加触发条件：交易落在「币种 + 受益行国家」范围内即触发；下方条件保存后生效');
    c2$('c2TriggerCompiled').textContent = lines.join('\n');
}

function c2InitTriggerTree(condition) {
    c2Trigger.tree = condition && Array.isArray(condition.children) && condition.children.length ? condition : {logic: 'AND', children: [c2DefaultLeaf()]};
    c2Trigger.edited = false;
    c2Trigger.substituted = false;
    c2RenderTriggerTree();
}

function c2RenderTriggerRuleOptions() {
    c2$('c2TriggerRule').innerHTML = c2State.rules.map(r =>
        `<option value="${c2Esc(r.ruleId)}" ${r.ruleId === c2Trigger.ruleId ? 'selected' : ''}>${c2Esc(r.ruleId)} · ${c2Esc(r.ruleName)}${r.enabled ? '' : '（已停用）'}${r.legacy ? '（旧版条件）' : ''}${r.triggerCondition ? '（有附加条件）' : ''}</option>`)
        .join('') || '<option value="">暂无监管路由规则</option>';
}

/**
 * 左侧配置对象：规则下拉当前选中的监管路由规则；右侧模拟输入不更换配置对象。
 * keepEditing：保留编辑器里正在编辑的条件（刷新规则 / 字段库时用）；否则载入该规则已保存的条件。
 */
function c2ShowTriggerRule(rule, keepEditing = false) {
    const tags = rule?.requirement?.requiredTags || [];
    const noField = rule && !c2State.fields.length;
    c2$('c2TriggerSave').disabled = !rule || noField;
    c2$('c2TriggerSave').textContent = rule ? `保存条件至规则 ${rule.ruleId}` : '保存条件至规则';
    c2$('c2TriggerSave').title = !rule ? '请先在「监管路由规则」页新增规则' : noField ? '请先在元数据中心启用适用规则含 C2 的字段' : '';
    c2$('c2TriggerClear').disabled = !rule?.triggerCondition;
    c2$('c2TriggerScope').innerHTML = rule
        ? `规则范围：${c2Esc(rule.conditionText)}${rule.legacy ? '（旧版条件，按原条件执行）' : ''}；必需能力标签：${tags.map(c2Esc).join(', ') || '无'}；${rule.enabled ? '启用中' : '<b>已停用，模拟时不执行</b>'}；版本 v${rule.versionNo}。范围和标签在 <a href="/c2-regulatory.html">监管路由规则</a> 页修改。`
        : '暂无监管路由规则，请先在 <a href="/c2-regulatory.html">监管路由规则</a> 页新增。';
    if (keepEditing) c2RenderTriggerTree();
    else {
        // 保存时带上载入时的版本号：规则在别处被改过则保存失败，不会覆盖别人的修改
        c2Trigger.version = rule?.versionNo ?? null;
        c2InitTriggerTree(rule?.triggerCondition || null);
    }
}

function c2SelectTriggerRule() {
    c2Trigger.ruleId = c2$('c2TriggerRule').value || null;
    c2$('c2TriggerError').textContent = '';
    c2$('c2TriggerGood').textContent = '';
    c2ShowTriggerRule(c2TriggerRule());
}

async function c2CompileTrigger() {
    try {
        const out = await c2Api('/regulatory-rules/compile', {method: 'POST', body: JSON.stringify({tree: c2Trigger.tree})});
        c2$('c2TriggerCompiled').textContent = out.expression;
        c2$('c2TriggerError').textContent = '';
    } catch (error) {
        c2$('c2TriggerCompiled').textContent = '条件校验失败';
        c2$('c2TriggerError').textContent = error.message;
    }
}

async function c2SaveTrigger() {
    const rule = c2TriggerRule();
    if (!rule) return;
    c2$('c2TriggerError').textContent = '';
    c2$('c2TriggerGood').textContent = '';
    try {
        const saved = await c2Api('/regulatory-rules/' + encodeURIComponent(rule.ruleId) + '/condition', {method: 'PUT', body: JSON.stringify({enabled: true, tree: c2Trigger.tree, versionNo: c2Trigger.version})});
        c2ReplaceRule(saved);
        c2RenderTriggerRuleOptions();
        c2ShowTriggerRule(saved);
        c2$('c2TriggerGood').textContent = `附加触发条件已保存 · 规则 ${saved.ruleId} · 版本 ${saved.versionNo}`;
    } catch (error) {
        c2$('c2TriggerError').textContent = error.message;
    }
}

async function c2ClearTrigger() {
    const rule = c2TriggerRule();
    if (!rule?.triggerCondition) return;
    if (!confirm(`确定清除规则 ${rule.ruleId} 的附加触发条件？清除后，交易只要落在「${rule.conditionText}」范围内规则即触发。`)) return;
    c2$('c2TriggerError').textContent = '';
    c2$('c2TriggerGood').textContent = '';
    try {
        const saved = await c2Api('/regulatory-rules/' + encodeURIComponent(rule.ruleId) + '/condition', {method: 'PUT', body: JSON.stringify({enabled: false, versionNo: c2Trigger.version})});
        c2ReplaceRule(saved);
        c2RenderTriggerRuleOptions();
        c2ShowTriggerRule(saved);
        c2$('c2TriggerGood').textContent = `已清除规则 ${saved.ruleId} 的附加触发条件 · 版本 ${saved.versionNo}`;
    } catch (error) {
        c2$('c2TriggerError').textContent = error.message;
    }
}

function c2ReplaceRule(saved) {
    const index = c2State.rules.findIndex(r => r.ruleId === saved.ruleId);
    if (index >= 0) c2State.rules[index] = saved;
    else c2State.rules.push(saved);
}

/**
 * 重新读取规则列表和 C2 字段库（元数据中心新增 / 停用字段、调整适用规则或可选值后使用）。
 * 选中的规则还在、载入后没有被改过、且操作员改过编辑器里的条件：保留正在编辑的条件；否则载入它当前已保存的条件。
 */
async function c2RefreshLibrary() {
    try {
        const selected = c2Trigger.ruleId;
        const [fields, rules] = await Promise.all([c2Api('/fields'), c2Api('/regulatory-rules')]);
        c2State.fields = fields;
        c2State.rules = rules;
        c2RenderVariableInputs();
        c2RenderTriggerRuleOptions();
        // 选中的规则已被删除：回到下拉当前显示的那条
        c2Trigger.ruleId = c2$('c2TriggerRule').value || null;
        // 选中的规则还是原来那条、载入后没有被改过、且操作员改过编辑器里的条件：保留正在编辑的条件；否则载入规则当前保存的条件
        // （载入时因字段不可用而被替换的条件不算操作员的修改，字段恢复可用后刷新即可还原）
        const rule = c2TriggerRule();
        const keep = rule?.ruleId === selected && rule.versionNo === c2Trigger.version && c2Trigger.edited;
        c2ShowTriggerRule(rule, keep);
        c2$('c2TriggerError').textContent = '';
        c2$('c2TriggerGood').textContent = keep ? '规则和字段库已刷新，编辑中的条件已保留' : '规则和字段库已刷新，已载入规则当前保存的附加触发条件';
    } catch (error) {
        c2$('c2TriggerError').textContent = error.message;
    }
}

async function c2Init() {
    try {
        const [providers, fields, options, rules] = await Promise.all([fetch('/api/providers').then(r => r.json()), c2Api('/fields'), c2Api('/regulatory-rules/options').catch(() => null), c2Api('/regulatory-rules')]);
        c2State.providers = providers;
        c2State.fields = fields;
        c2State.rules = rules;
        if (!fields.length) throw new Error('C2 字段库为空');
        c2$('c2SimProvider').innerHTML = providers.map(p => `<option value="${c2Esc(p.code)}">${c2Esc(p.code)} · ${c2Esc(p.name)}</option>`).join('');
        c2$('c2SimProvider').value = 'PROV-HO';
        c2FillSelect('c2SimPaymentCurrency', 'paymentCurrency', 'CNY');
        c2FillSelect('c2SimDebitCurrency', 'debitCurrency', 'CNY', true);
        c2FillSelect('c2SimBeneficiaryBankCountry', 'beneficiaryBankCountry', 'CN');
        c2AddIsoCountries('c2SimBeneficiaryBankCountry', options?.countries || []);
        c2FillSelect('c2SimBeneficiaryAddressCountry', 'beneficiaryAddressCountry', 'CN', true);
        c2FillSelect('c2SimBeneficiaryRegion', 'beneficiaryRegion', '', true);
        c2FillSelect('c2SimOrderingCountry', 'orderingCountry', 'HK', true);
        c2FillSelect('c2SimClientCountry', 'clientCountry', '', true);
        c2FillSelect('c2SimIntermediaryCountry', 'intermediaryCountry', '', true);
        c2FillSelect('c2SimPurpose', 'purpose', '', true);
        c2RenderVariableInputs();
        // 规则下拉：优先带 rule 参数（监管路由规则页的链接）或上次选中的规则，否则取第一条
        c2RenderTriggerRuleOptions();
        c2SelectTriggerRule();
        await c2SyncSimCapabilities();
    } catch (e) {
        c2$('c2SimResult').innerHTML = `<div class="result error">页面初始化失败：${c2Esc(e.message)}</div>`;
        c2$('c2TriggerError').textContent = e.message;
    }
}

c2Init();
