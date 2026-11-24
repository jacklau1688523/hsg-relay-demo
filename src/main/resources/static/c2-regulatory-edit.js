'use strict';
// 监管路由规则新增 / 编辑：/c2-regulatory-edit.html 为新增，/c2-regulatory-edit.html?rule=规则编码 为编辑。
// 适用范围、能力标签、附加触发条件（按字段库构建的条件树）和备注在同一个表单里，一次提交、一个版本。
// 列表在 /c2-regulatory.html；共用的接口与字典在 c2-regulatory-common.js（须先引入）。
const state = {
    ruleId: new URLSearchParams(location.search).get('rule'), view: null, rules: [], fields: [],
    // 附加触发条件编辑器。tree：条件树（条件组 {logic, children}，叶子 {field, operator, value}）；
    // edited：操作员改过条件；substituted：载入的条件引用了当前不可用的字段 / 运算符，编辑器里已换成可用项；
    // shown：编辑器里的提示（被替换的失效字段 / 运算符、不在可选值中的条件值）；expression：最近一次校验生成的表达式
    tree: null, edited: false, substituted: false, shown: [], expression: null
};
const operators = {EQ: '等于 =', NE: '不等于 ≠', IN: '属于 IN', GT: '大于 >', GTE: '大于等于 ≥', LT: '小于 <', LTE: '小于等于 ≤'};
const symbols = {EQ: '=', NE: '≠', GT: '>', GTE: '≥', LT: '<', LTE: '≤'};

// ---- 表单 ----
/** 新增时的默认编码：列表里还没有的最小 REG-00N */
function nextRuleId() {
    const used = new Set(state.rules.map(r => r.ruleId));
    let next = 1;
    while (used.has(`REG-${String(next).padStart(3, '0')}`)) next++;
    return `REG-${String(next).padStart(3, '0')}`;
}

/** 旧版规则（旧页面保存的自由条件树）里的币种、国家：按原条件树尽量回填。 */
function legacyPicks(tree) {
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

/** 适用范围的下拉项：币种取字段库「交易币种」可选值，受益行国家取 ALL + ISO 3166-1；指定的取值已不可用时回到默认项。 */
function renderScopeOptions(currency, country) {
    c2$('currency').innerHTML = c2Dict.currencies.length
        ? c2Dict.currencies.map(v => `<option value="${c2Esc(v)}">${c2Esc(v)}</option>`).join('')
        : '<option value="">字段库缺少「交易币种」可选值</option>';
    c2$('bankCountry').innerHTML = c2Dict.countries.map(x =>
        `<option value="${c2Esc(x.code)}">${c2Esc(x.name ? `${x.code} · ${x.name}` : x.code)}</option>`).join('');
    c2$('currency').value = c2Dict.currencies.includes(currency) ? currency : (c2Dict.currencies[0] || '');
    c2$('bankCountry').value = c2Dict.countries.some(x => x.code === country) ? country : 'ALL';
}

function renderTags(selected) {
    c2$('tags').innerHTML = c2Dict.tags.map(t => `<label class="tagcheck"><input name="tag" type="checkbox" value="${c2Esc(t.tagCode)}" ${selected.includes(t.tagCode) ? 'checked' : ''}><span>${c2Esc(t.tagCode)}</span><span class="muted">${c2Esc(t.tagName)}</span></label>`).join('')
        || '<div class="muted">能力标签字典为空，请先在合作方新增 / 编辑页维护能力标签。</div>';
}

const checkedTags = () => [...document.querySelectorAll('[name="tag"]:checked')].map(c => c.value);

// 把接口返回的规则填入表单；传空时是空白的新增表单
function fill(view = null) {
    state.view = view;
    const picks = view?.legacy ? legacyPicks(view.condition) : view || {};
    c2$('ruleId').value = view ? view.ruleId : nextRuleId();
    c2$('ruleId').readOnly = !!view;
    c2$('ruleName').value = view?.ruleName || '';
    c2$('enabled').value = String(view ? view.enabled : true);
    c2$('effectiveFrom').value = c2Minute(view?.effectiveFrom) || c2NowHkt();
    c2$('effectiveTo').value = c2Minute(view?.effectiveTo);
    c2$('remark').value = view?.remark || '';
    renderScopeOptions(String(picks.currency || '').toUpperCase(), String(picks.bankCountry || '').toUpperCase());
    renderTags(view ? view.requirement.requiredTags : []);
    c2$('triggerEnabled').checked = !!view?.triggerCondition;
    initTree(view?.triggerCondition || null);
    c2$('legacyHint').style.display = view?.legacy ? 'block' : 'none';
    c2$('legacyHint').textContent = `该规则由旧版条件树创建，当前仍按原条件执行：${view?.conditionText || ''}。表单已按原条件里的币种、国家尽量回填，请核对上方适用范围后保存；保存后改为按「交易币种 + 受益行国家」执行。`;
    c2$('formTitle').textContent = view ? '编辑监管规则' : '新增监管规则';
    c2$('version').textContent = view ? `规则编码 ${view.ruleId} · 数据版本 ${view.versionNo}` : '编码保存后不可修改';
    document.title = view ? `HSG RELAY | 编辑监管规则 · ${view.ruleId}` : 'HSG RELAY | 新增监管规则';
    c2$('simulate').style.display = view ? '' : 'none';
    c2$('formError').textContent = '';
    c2$('formGood').textContent = '';
    renderStatus();
}

/** 新增 / 编辑页的保存请求：规则 + 附加触发条件（未启用即清除，enabled=false）。 */
function request() {
    return {
        ruleId: c2$('ruleId').value.trim(),
        ruleName: c2$('ruleName').value.trim(),
        ruleCode: 'C2',
        scope: 'GLOBAL',
        currency: c2$('currency').value,
        bankCountry: c2$('bankCountry').value,
        requirement: {type: 'PROVIDER_CAPABILITY', requiredTags: checkedTags()},
        onFailure: {reasonCode: 'REG_RULE_NOT_MET'},
        enabled: c2$('enabled').value === 'true',
        versionNo: state.view?.versionNo ?? null,
        effectiveFrom: c2$('effectiveFrom').value || null,
        effectiveTo: c2$('effectiveTo').value || null,
        remark: c2$('remark').value.trim() || null,
        trigger: c2$('triggerEnabled').checked ? {enabled: true, tree: state.tree} : {enabled: false}
    };
}

// ---- 规则预览（右侧）：用几句话复述当前配置 ----
/** 条件树摘要，形态同接口返回的 triggerText，如「(purpose = INVESTMENT 且 debitCurrency ≠ USD)」。 */
function treeText(node) {
    if (node.children) return `(${node.children.map(treeText).join(node.logic === 'OR' ? ' 或 ' : ' 且 ')})`;
    const value = Array.isArray(node.value) ? node.value.join(', ') : node.value;
    return `${node.field || '—'} ${node.operator === 'IN' ? '属于' : symbols[node.operator] || node.operator || '—'} ${value ?? ''}`;
}

function preview() {
    const tags = checkedTags();
    const period = c2$('effectiveFrom').value
        ? `${c2ShowTime(c2$('effectiveFrom').value)} 起 · ${c2$('effectiveTo').value ? c2ShowTime(c2$('effectiveTo').value) + ' 止' : '长期有效'}`
        : '—';
    c2$('preview').innerHTML = `<div>适用范围：交易币种 <b>${c2Esc(c2$('currency').value || '—')}</b> 且受益行国家 <b>${c2Esc(c2$('bankCountry').value ? c2CountryLabel(c2$('bankCountry').value) : '—')}</b></div>
  <div>附加触发条件：${c2$('triggerEnabled').checked ? `<span class="mono">${c2Esc(treeText(state.tree))}</span>` : '<b>无</b>（交易落在适用范围内即触发）'}</div>
  <div>${tags.length ? `命中后：候选合作方须具备 ${tags.map(tag => `<b title="${c2Esc(c2TagName(tag))}">${c2Esc(tag)}</b>`).join('、')}（共 ${tags.length} 项，须全部具备）；缺少任一项即剔除该候选路由，返回 <b>REG_RULE_NOT_MET</b>。`
        : '<span class="c2-warn">尚未选择能力标签，保存前至少勾选一项。</span>'}</div>
  <div>有效期（HKT）：${c2Esc(period)}；状态：<b>${c2$('enabled').value === 'true' ? '启用' : '停用'}</b></div>`;
}

// ---- ③ 附加触发条件：按字段库构建的条件树 ----
const defaultValue = f => f?.options?.[0] || (f?.dataType === 'BOOLEAN' ? 'true' : ['DECIMAL', 'INTEGER'].includes(f?.dataType) ? '0' : '');
const defaultLeaf = () => {
    const f = state.fields[0];
    return {field: f?.fieldCode || '', operator: f?.operators?.[0] || 'EQ', value: defaultValue(f)};
};

/** 载入一条已保存的条件（或给出一条默认条件）并重置编辑状态。 */
function initTree(condition) {
    state.tree = condition && Array.isArray(condition.children) && condition.children.length
        ? JSON.parse(JSON.stringify(condition)) : {logic: 'AND', children: [defaultLeaf()]};
    state.edited = false;
    state.substituted = false;
    state.expression = null;
    c2$('triggerCompiled').textContent = '点击「校验并生成表达式」查看条件对应的表达式';
    c2$('triggerError').textContent = '';
    c2$('triggerGood').textContent = '';
    triggerToggled();
}

function triggerToggled() {
    c2$('triggerEditor').classList.toggle('hidden', !c2$('triggerEnabled').checked);
    renderTree();
}

function nodeAt(path) {
    let node = state.tree;
    for (const part of path.split('.').filter(Boolean)) node = node.children[Number(part)];
    return node;
}

/** 操作员改动了条件：保存时按编辑中的条件提交，之前校验生成的表达式不再对应当前条件。 */
function touch() {
    state.edited = true;
    state.expression = null;
    c2$('triggerCompiled').textContent = '条件已修改，请重新校验';
    c2$('triggerError').textContent = '';
    c2$('triggerGood').textContent = '';
}

function removeNode(path) {
    const parts = path.split('.'), index = Number(parts.pop()), parent = nodeAt(parts.join('.'));
    if (parent.children.length === 1) return alert('条件组至少保留一项');
    parent.children.splice(index, 1);
    touch();
    renderTree();
}

function addNode(path, kind) {
    const group = nodeAt(path);
    if (group.children.length >= 20) return alert('每组最多20项');
    if (kind === 'group' && path.split('.').filter(Boolean).length >= 5) return alert('最多6层条件组');
    group.children.push(kind === 'group' ? {logic: 'OR', children: [defaultLeaf()]} : defaultLeaf());
    touch();
    renderTree();
}

function hasOptions(node) {
    const f = state.fields.find(x => x.fieldCode === node.field);
    return node.operator !== 'IN' && (f?.dataType === 'BOOLEAN' || f?.options?.length > 0);
}

function changeNode(path, key, value) {
    const node = nodeAt(path);
    node[key] = value;
    if (key === 'field') {
        const f = state.fields.find(x => x.fieldCode === value);
        node.operator = f?.operators?.[0] || 'EQ';
        node.value = defaultValue(f);
    }
    touch();
    // 有下拉可选值时改值需要重绘以清掉失效提示；文本输入不重绘，避免打断输入，只更新状态提示
    if (key === 'field' || key === 'operator' || key === 'logic' || (key === 'value' && state.shown.length && hasOptions(node))) renderTree();
    else renderStatus();
}

function renderLeaf(node, path, notices) {
    const fields = state.fields, f = fields.find(x => x.fieldCode === node.field) || fields[0];
    if (!f) return '<div class="err">请先在元数据中心启用适用规则含 C2 的字段</div>';
    // 已保存条件引用的字段在元数据中心停用或不再适用 C2 时，换成当前首个可用字段，并提示核对后再保存
    if (!fields.some(x => x.fieldCode === node.field)) {
        notices.push(`字段 ${node.field || '（空）'} 已在元数据中心停用或不再适用 C2，对应条件已换成当前可用字段，请核对后再保存`);
        Object.assign(node, defaultLeaf());
        state.substituted = true;
    }
    // 已保存条件使用的运算符被元数据中心取消时同样换成该字段的第一个运算符
    if (!f.operators.includes(node.operator)) {
        notices.push(`字段 ${f.fieldName} 已不支持运算符「${operators[node.operator] || node.operator || '（空）'}」，对应条件已换成「${operators[f.operators[0]]}」，请核对后再保存`);
        node.operator = f.operators[0];
        state.substituted = true;
    }
    const values = node.operator !== 'IN' ? (f.dataType === 'BOOLEAN' ? ['true', 'false'] : f.options) : [];
    // 还没有取值时（例如刚从 IN 的文本框切到 EQ）取第一个可选值，与下拉实际显示的保持一致
    if (values.length && !String(node.value ?? '').trim()) node.value = values[0];
    const current = String(node.value ?? '');
    // 已保存的条件值不在元数据中心当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
    const stale = values.length && !values.includes(current);
    if (stale) notices.push(`字段 ${f.fieldName} 的条件值「${current}」不在元数据中心当前可选值中，请重新选择`);
    const listed = stale ? [current, ...values] : values;
    const change = `onchange="changeNode('${path}','value',this.value)"`;
    const control = values.length
        ? `<select aria-label="条件值" ${change}>${listed.map(v => `<option value="${c2Esc(v)}" ${v === current ? 'selected' : ''}>${c2Esc(v)}${stale && v === current ? '（已不在可选值中）' : ''}</option>`).join('')}</select>`
        : `<input aria-label="条件值" value="${c2Esc(node.value)}" placeholder="${node.operator === 'IN' ? '多个值用逗号分隔' : '输入条件值'}" oninput="changeNode('${path}','value',this.value)" ${['DECIMAL', 'INTEGER'].includes(f.dataType) ? 'type="number" step="any"' : ''}>`;
    return `<div class="c2-line"><select aria-label="条件字段" onchange="changeNode('${path}','field',this.value)">${fields.map(x => `<option value="${c2Esc(x.fieldCode)}" ${x.fieldCode === node.field ? 'selected' : ''}>${c2Esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="changeNode('${path}','operator',this.value)">${f.operators.map(op => `<option value="${c2Esc(op)}" ${op === node.operator ? 'selected' : ''}>${c2Esc(operators[op] || op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="removeNode('${path}')" aria-label="删除此条件">✕</button></div>`;
}

function renderGroup(group, path, depth, notices) {
    const logic = group.logic === 'OR' ? 'OR' : 'AND';
    group.logic = logic;
    const head = `<div class="c2-head"><strong>${depth ? '子条件组' : '根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="changeNode('${path}','logic',this.value)"><option value="AND" ${logic === 'AND' ? 'selected' : ''}>全部满足 AND</option><option value="OR" ${logic === 'OR' ? 'selected' : ''}>任一满足 OR</option></select>${depth ? `<button type="button" class="btn secondary tiny" onclick="removeNode('${path}')">删除子组</button>` : ''}</div>`;
    const body = group.children.map((child, i) => {
        const childPath = path ? path + '.' + i : String(i);
        return `${i ? `<div class="c2-join">${logic}</div>` : ''}${child.children ? renderGroup(child, childPath, depth + 1, notices) : renderLeaf(child, childPath, notices)}`;
    }).join('');
    return `<div class="c2-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="addNode('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="addNode('${path}','group')">▧ 添加子条件组</button></div></div>`;
}

function renderTree() {
    const notices = [];
    c2$('triggerTree').innerHTML = state.fields.length ? renderGroup(state.tree, '', 0, notices) : '<div class="err">元数据中心没有已启用且适用规则含 C2 的字段，请先在元数据中心配置。</div>';
    state.shown = [...new Set(notices)];
    renderStatus();
}

/** 条件区下方的提示：已保存条件失效、编辑器替换掉的失效字段 / 条件值，以及当前条件相对已保存条件的状态。 */
function renderStatus() {
    const lines = [...state.shown];
    if (state.view?.triggerProblem) lines.push(`已保存的附加触发条件已失效（${state.view.triggerProblem}）：失效期间交易只要落在适用范围内规则即触发，请核对后重新保存`);
    lines.push(state.edited
        ? '附加触发条件已修改，随规则保存后生效'
        : state.substituted
            ? '编辑器里的条件与已保存的不同（不可用的字段或运算符已换成当前可用项），请核对后保存'
            : state.view?.triggerCondition ? '当前显示的是已保存的附加触发条件' : '尚未保存：启用后请设置条件，随规则保存生效');
    c2$('triggerStatus').textContent = lines.join('\n');
    c2$('triggerStatus').classList.toggle('warn', state.shown.length > 0 || !!state.view?.triggerProblem);
    preview();
}

/** 保存前的本地检查：每条条件都要有可用的字段、运算符和条件值；取值是否在字段库可选值内由服务端校验。 */
function treeProblem(node) {
    if (node.children) return node.children.map(treeProblem).find(Boolean) || '';
    const field = state.fields.find(f => f.fieldCode === node.field);
    return field ? (String(node.value ?? '').trim() ? '' : `请填写字段「${field.fieldName}」的条件值`) : '附加触发条件里有不可用的字段，请重新选择';
}

/** 只校验条件、不保存：按字段库校验并生成表达式（接口 POST /regulatory-rules/compile）。 */
async function compileTrigger() {
    c2$('triggerError').textContent = '';
    c2$('triggerGood').textContent = '';
    try {
        const out = await c2Api('/regulatory-rules/compile', {method: 'POST', body: JSON.stringify({tree: state.tree})});
        state.expression = out.expression;
        c2$('triggerCompiled').textContent = out.expression;
    } catch (e) {
        state.expression = null;
        c2$('triggerCompiled').textContent = '条件校验失败';
        c2$('triggerError').textContent = e.message;
    }
}

/**
 * 重新读取下拉项和 C2 字段库（元数据中心新增 / 停用字段、调整适用规则或可选值后使用）。
 * 操作员改过条件：保留正在编辑的条件；否则重新载入已保存的条件——载入时因字段不可用而被替换的条件不算操作员的修改，字段恢复可用后刷新即还原。
 */
async function refreshLibrary() {
    try {
        const [, fields] = await Promise.all([c2LoadDict(), c2Api('/fields')]);
        state.fields = fields;
        renderScopeOptions(c2$('currency').value, c2$('bankCountry').value);
        renderTags(checkedTags());
        if (state.edited) renderTree();
        else initTree(state.view?.triggerCondition || null);
        c2$('triggerGood').textContent = state.edited ? '字段库已刷新，编辑中的条件已保留' : '字段库已刷新，已载入规则当前保存的附加触发条件';
    } catch (e) {
        c2$('triggerError').textContent = e.message;
    }
}

async function copyJson() {
    try {
        await navigator.clipboard.writeText(JSON.stringify(request(), null, 2));
        c2$('formGood').textContent = '已复制到剪贴板';
    } catch (e) {
        c2$('formError').textContent = '浏览器禁止复制，请使用开发者工具查看';
    }
}

// ---- 页面 ----
async function show() {
    const editing = !!state.ruleId;
    c2$('formTitle').textContent = editing ? '编辑监管规则' : '新增监管规则';
    c2$('version').textContent = editing ? `规则编码 ${state.ruleId}` : '编码保存后不可修改';
    document.title = editing ? `HSG RELAY | 编辑监管规则 · ${state.ruleId}` : 'HSG RELAY | 新增监管规则';
    c2$('pageError').textContent = '';
    c2$('loading').hidden = false;
    try {
        // 编辑：按编码载入这一条；新增：载入列表，用于给出默认编码并提示编码重复
        const [, fields, rules] = await Promise.all([
            c2LoadDict(), c2Api('/fields'),
            state.ruleId ? c2Api(`/regulatory-rules/${encodeURIComponent(state.ruleId)}`) : c2Api('/regulatory-rules')
        ]);
        state.fields = fields;
        c2$('loading').hidden = true;
        c2$('editor').classList.remove('hidden');
        if (state.ruleId) fill(rules);
        else {
            state.rules = rules;
            fill(null);
        }
    } catch (e) {
        // 多为规则已在别处被删除：页头的「返回监管路由规则」可回到列表重新选择
        c2$('loading').hidden = true;
        c2$('editor').classList.add('hidden');
        c2$('pageError').textContent = `${e.message}。请返回监管路由规则列表重新选择。`;
    }
}

for (const id of ['currency', 'bankCountry', 'enabled', 'effectiveFrom', 'effectiveTo']) c2$(id).addEventListener('change', preview);
c2$('tags').addEventListener('change', preview);
c2$('triggerTree').addEventListener('keydown', e => {
    // 条件值输入框里按回车只是结束输入，不提交表单
    if (e.key === 'Enter' && e.target.tagName === 'INPUT') e.preventDefault();
});
c2$('compile').onclick = compileTrigger;
c2$('reloadLibrary').onclick = refreshLibrary;
c2$('copy').onclick = copyJson;

c2$('ruleForm').onsubmit = async e => {
    e.preventDefault();
    c2$('save').disabled = true;
    c2$('formError').textContent = '';
    c2$('formGood').textContent = '';
    try {
        const inData = request();
        if (!state.view && state.rules.some(r => r.ruleId === inData.ruleId)) throw new Error('规则编码已存在，请编辑现有规则或更换编码');
        if (!inData.requirement.requiredTags.length) throw new Error('请至少选择一个能力标签');
        if (inData.effectiveTo && inData.effectiveTo <= inData.effectiveFrom) throw new Error('失效时间必须晚于生效时间');
        if (inData.trigger.enabled) {
            const problem = treeProblem(state.tree);
            if (problem) throw new Error(problem);
        }
        // 未启用附加触发条件即清除：已保存的条件会随本次保存删除，先确认
        const previous = state.view, creating = !previous;
        if (!inData.trigger.enabled && previous?.triggerCondition
            && !confirm(`保存后将清除规则 ${previous.ruleId} 已配置的附加触发条件（${previous.triggerText}），清除后交易只要落在适用范围内规则即触发。确定保存？`)) return;
        // 服务端把规则和附加触发条件一起校验、一起保存：条件不通过时整条规则（含范围、标签）都不保存
        const saved = await c2Api('/regulatory-rules', {method: 'POST', body: JSON.stringify(inData)});
        c2MarkChanged();
        // 新增成功后地址栏换成编辑地址：刷新、后退再进来都是编辑这条规则
        if (creating) history.replaceState(null, '', `/c2-regulatory-edit.html?rule=${encodeURIComponent(saved.ruleId)}`);
        state.ruleId = saved.ruleId;
        fill(saved);
        c2$('formGood').textContent = `已保存监管规则 ${saved.ruleId}，版本 ${saved.versionNo}。${saved.triggerText ? `附加触发条件：${saved.triggerText}。` : ''}${saved.enabled ? '' : '规则已停用，C2 判定不执行。'}可在「C2 合规限制」页模拟验证。`;
    } catch (e) {
        c2$('formError').textContent = e.message;
    } finally {
        c2$('save').disabled = false;
    }
};

show();
