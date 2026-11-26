'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[ch]));
const rules = Array.from({length: 10}, (_, i) => `C${i + 1}`);
const opNames = {
    EQ: '等于 =',
    NE: '不等于 ≠',
    GT: '大于 >',
    GTE: '大于等于 ≥',
    LT: '小于 <',
    LTE: '小于等于 ≤',
    IN: '属于 IN',
    CONTAINS: '包含 CONTAINS',
    NOT_CONTAINS: '不包含 NOT_CONTAINS'
};
const allowedByType = {
    STRING: ['EQ', 'NE', 'IN'],
    ENUM: ['EQ', 'NE', 'IN'],
    DECIMAL: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE'],
    INTEGER: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE'],
    BOOLEAN: ['EQ', 'NE'],
    // 集合字段：条件值是要判断的单个元素
    STRING_SET: ['CONTAINS', 'NOT_CONTAINS']
};
const split = value => String(value || '').split(',').map(x => x.trim()).filter(Boolean);
let fields = [], editingCode = null, conditions = [], activeTab = 'catalog';
// 字段绑定函数用的函数库数据（GET /function-metadata/available?ruleCode=C1）：可用于 C1 条件的函数与受控取值路径
let functionCatalog = null, functionCatalogError = '', editFunctionCode = '', editBindings = {}, rulesBeforeLock = null;
const sourceNames = {CANDIDATE: '候选合作方', TRANSACTION: '本笔交易', CONTEXT: '规则上下文', FIELD: '元数据字段', ANY: '任意来源（显式绑定）'};
// 字段解析器清单（GET /field-metadata/resolvers）：代码里已注册的 FieldValueResolver，数据解析字段只能从中选择
let fieldResolvers = [], fieldResolversError = '';
const isSetType = type => type === 'STRING_SET';

async function api(path, opt = {}) {
    const response = await fetch('/api' + path, {
        ...opt,
        headers: {'Content-Type': 'application/json', ...(opt.headers || {})}
    });
    const body = await response.json();
    if (!response.ok) throw Error(body.error || body.message || response.statusText);
    return body;
}

const selectedField = code => fields.find(f => f.fieldCode === code);
const availableFields = () => fields.filter(f => f.enabled && split(f.allowedRules).includes($('exprRule').value));

function showTab(tab) {
    activeTab = tab;
    document.querySelectorAll('[data-tab]').forEach(button => button.classList.toggle('sel', button.dataset.tab === tab));
    $('catalogTab').classList.toggle('hidden', tab !== 'catalog');
    $('functionsTab').classList.toggle('hidden', tab !== 'functions');
    $('expressionTab').classList.toggle('hidden', tab !== 'expression');
    if (tab === 'expression') renderConditions();
    if (tab === 'functions' && typeof refreshFunctions === 'function') refreshFunctions().catch(error => alert(error.message));
}

// 取值来源：请求输入（默认）、函数计算（标注绑定的函数）或数据解析（标注字段解析器）；没有配置过取值来源的旧字段按请求输入
const sourceCell = f => f.valueSource === 'FUNCTION'
    ? `<span class="meta-tag">ƒ 函数计算</span><br><span class="meta-code mono">${esc(f.functionCode || '—')}</span>`
    : f.valueSource === 'DATA_RESOLVER'
        ? `<span class="meta-tag">⟐ 数据解析</span><br><span class="meta-code mono">${esc(f.resolverCode || '—')}</span>`
        : '<span class="muted">请求输入</span>';
async function loadResolvers() {
    try {
        fieldResolvers = await api('/field-metadata/resolvers');
        fieldResolversError = '';
    } catch (error) {
        fieldResolvers = [];
        fieldResolversError = error.message;
    }
    // 编辑器已打开时按刚读到的清单刷新解析器下拉
    if ($('editorBackdrop').classList.contains('show') && $('editSource').value === 'DATA_RESOLVER') renderResolverOptions();
}

function renderCatalog() {
    const enabled = fields.filter(f => f.enabled).length;
    $('metaStats').innerHTML = `<div class="meta-stat"><strong>${fields.length}</strong><span>字段总数</span></div><div class="meta-stat"><strong>${enabled}</strong><span>启用字段</span></div><div class="meta-stat"><strong>${fields.filter(f => f.enabled && split(f.allowedRules).includes('C2')).length}</strong><span>C2 适用字段</span></div>`;
    const query = $('fieldSearch').value.trim().toLowerCase(), rule = $('ruleFilter').value,
        status = $('statusFilter').value;
    const shown = fields.filter(f => (!rule || split(f.allowedRules).includes(rule)) && (!status || (status === 'on') === f.enabled) && (!query || [f.fieldName, f.fieldCode, f.dataType, f.valueOptions, f.functionCode].join(' ').toLowerCase().includes(query)));
    $('fieldRows').innerHTML = shown.map(f => `<tr><td><strong>${esc(f.fieldName)}</strong><br><span class="meta-code mono">${esc(f.fieldCode)}</span></td><td><span class="meta-tag">${esc(f.dataType)}</span></td><td><div class="meta-tags">${split(f.allowedRules).map(r => `<span class="meta-tag">${esc(r)}</span>`).join('')}</div></td><td>${split(f.allowedOperators).map(op => esc(opNames[op] || op)).join(' · ')}</td><td>${sourceCell(f)}</td><td class="mono">${esc(f.valueOptions || '—')}</td><td>${f.enabled ? '<span class="pass badge">启用</span>' : '<span class="pending badge">停用</span>'}</td><td><div class="actions"><button type="button" class="btn secondary tiny" data-edit="${esc(f.fieldCode)}">编辑</button><button type="button" class="btn secondary tiny" data-toggle="${esc(f.fieldCode)}">${f.enabled ? '停用' : '启用'}</button><button type="button" class="btn secondary tiny" data-delete="${esc(f.fieldCode)}">删除</button></div></td></tr>`).join('') || '<tr><td colspan="8" class="meta-empty">没有匹配的字段</td></tr>';
}

function renderChecks(container, options, selected) {
    $(container).innerHTML = options.map(([value, label]) => `<label><input type="checkbox" value="${esc(value)}" ${selected.includes(value) ? 'checked' : ''}>${esc(label)}</label>`).join('');
}

function checked(container) {
    return Array.from($(container).querySelectorAll('input:checked')).map(input => input.value);
}

function renderOperators(selected = []) {
    const list = allowedByType[$('editType').value];
    renderChecks('editOperators', list.map(op => [op, opNames[op]]), selected.length ? selected.filter(op => list.includes(op)) : list.slice(0, 2));
    $('editOptions').disabled = !['STRING', 'ENUM'].includes($('editType').value);
}

// ---------- 字段 ↔ 函数绑定（取值来源 = FUNCTION） ----------

// 函数库数据只用于字段编辑器的函数绑定：读取失败不影响字段管理，在绑定函数时提示并允许重试
async function loadFunctionCatalog() {
    try {
        functionCatalog = await api('/function-metadata/available?ruleCode=C1');
        functionCatalogError = '';
    } catch (error) {
        functionCatalog = null;
        functionCatalogError = error.message;
    }
    $('metaPathOptions').innerHTML = (functionCatalog?.paths || []).map(p => `<option value="$${esc(p.path)}">${esc(p.label)}（${esc(p.type)}）</option>`).join('');
    // 编辑器已打开时按刚读到的函数库数据刷新函数下拉
    if ($('editorBackdrop').classList.contains('show') && $('editSource').value === 'FUNCTION') renderFunctionOptions();
}
// 绑定函数的返回类型须与字段类型一致（枚举字段对应 STRING）
const expectedReturnType = () => $('editType').value === 'ENUM' ? 'STRING' : $('editType').value;
const bindingFunctions = () => (functionCatalog?.functions || []).filter(fn => fn.returnType === expectedReturnType());
const parseBindings = text => {
    try {
        return text ? JSON.parse(text) || {} : {};
    } catch (_) {
        return {};
    }
};
// 函数计算、数据解析的字段目前只能适用于 C1（与保存接口的校验一致）：取值来源选这两种时只保留 C1，其余规则不可勾选
function lockRulesToC1() {
    const bound = ['FUNCTION', 'DATA_RESOLVER'].includes($('editSource').value);
    $('editFunctionBlock').classList.toggle('hidden', $('editSource').value !== 'FUNCTION');
    $('editResolverBlock').classList.toggle('hidden', $('editSource').value !== 'DATA_RESOLVER');
    const inputs = Array.from(document.querySelectorAll('#editRules input'));
    if (bound) {
        // 记住切换前的适用规则，改回「请求输入」时恢复
        if (checked('editRules').length > 1 || !checked('editRules').includes('C1')) rulesBeforeLock = checked('editRules');
        inputs.forEach(input => {
            input.disabled = input.value !== 'C1';
            input.checked = input.value === 'C1';
        });
    } else {
        inputs.forEach(input => {
            input.disabled = false;
        });
        if (rulesBeforeLock) {
            inputs.forEach(input => {
                input.checked = rulesBeforeLock.includes(input.value);
            });
            rulesBeforeLock = null;
        }
    }
}
// 解析器下拉：只列后端已注册的字段解析器。已保存的解析器如果当前未注册，原样列出并标注，不悄悄换成别的
let editResolverCode = '';
function renderResolverOptions() {
    const select = $('editResolver'), known = fieldResolvers.includes(editResolverCode);
    const retained = !known && fieldResolvers.length && editResolverCode ? [editResolverCode] : [];
    select.innerHTML = (fieldResolvers.length ? '' : `<option value="">${fieldResolversError ? '解析器清单加载失败：' + esc(fieldResolversError) : '没有已注册的字段解析器'}</option>`)
        + [...fieldResolvers, ...retained].map(code => `<option value="${esc(code)}">${esc(code)}${retained.includes(code) ? '（当前未注册，保存会被拦下）' : ''}</option>`).join('');
    select.disabled = !fieldResolvers.length;
    if (fieldResolvers.length && !known && !retained.length) editResolverCode = fieldResolvers[0];
    select.value = editResolverCode;
    $('editResolverRetry').classList.toggle('hidden', !!fieldResolvers.length);
    $('editResolverHint').textContent = isSetType($('editType').value)
        ? `元素类型 elementType：STRING。集合在规则执行中由解析器读取，读取失败时报错，不按空集合处理。`
        : '解析器的返回值类型须与字段的数据类型一致。';
}
// 函数下拉：只列返回类型与字段类型一致的函数。已保存的绑定如果当前不可用（停用、不再适用 C1、实现未注册），
// 原样列出并标注，不悄悄换成别的函数；换了函数时参数绑定重新取新函数的默认值
function renderFunctionOptions() {
    const list = bindingFunctions(), select = $('editFunction'), before = editFunctionCode;
    const known = list.some(fn => fn.functionCode === editFunctionCode);
    const retained = !known && functionCatalog && editFunctionCode ? [{functionCode: editFunctionCode, functionName: editFunctionCode, unavailable: true}] : [];
    select.innerHTML = (functionCatalog ? '' : '<option value="">函数库数据未加载</option>') + [...list, ...retained].map(fn => `<option value="${esc(fn.functionCode)}">${esc(fn.functionName)} · ${esc(fn.functionCode)}${fn.unavailable ? '（当前不可用）' : fn.usagePolicy === 'REQUIRED' ? ' · 系统必检' : ' · 可选'}</option>`).join('');
    select.disabled = !functionCatalog || !(list.length || retained.length);
    // 函数库数据未加载时先保留已保存的函数编码，加载完成后按上面的规则重新确定
    if (functionCatalog && !known && !retained.length) editFunctionCode = list[0]?.functionCode || '';
    select.value = editFunctionCode;
    if (before !== editFunctionCode) {
        editBindings = {};
        renderBindingInputs();
    }
    renderFunctionHint();
}
function renderFunctionHint() {
    const fn = bindingFunctions().find(x => x.functionCode === editFunctionCode);
    const any = functionCatalog?.functions.find(x => x.functionCode === editFunctionCode);
    $('editFunctionRetry').classList.toggle('hidden', !!functionCatalog);
    $('editFunctionHint').innerHTML = !functionCatalog
        ? `函数库数据未加载${functionCatalogError ? '：' + esc(functionCatalogError) : ''}。`
        : fn ? `返回 ${esc(fn.returnType)} · 数据来源 ${esc(fn.dataSource || '—')}${fn.usagePolicy === 'REQUIRED' ? ' · 系统必检' : ' · 可选'}。参数可引用受控取值路径（$ 开头，有候选）或填写字面量，保存时由服务端校验。`
        : any ? `函数 ${esc(any.functionCode)} 返回类型为 ${esc(any.returnType)}，与字段类型 ${esc($('editType').value)} 不一致，不能绑定。`
        : editFunctionCode ? `函数 ${esc(editFunctionCode)} 当前不可用（已停用、不再适用 C1 或实现未注册），保存会被拦下：请改选其他函数，或把取值来源改为「请求输入」。`
        : `函数库里没有返回类型为 ${esc(expectedReturnType())} 的可用函数，不能绑定；请先到「函数库」页登记并启用。`;
}
// 每个参数一个绑定输入框：候选为受控取值路径（如 $candidate.providerCode），也可填字面量；已保存的绑定优先，否则带出函数库的默认绑定
function renderBindingInputs() {
    const fn = bindingFunctions().find(x => x.functionCode === editFunctionCode);
    $('editBindings').innerHTML = (fn?.parameters || []).map(p => `<div class="meta-binding"><label>参数 ${esc(p.code)}${p.required ? ' *' : ''} <span class="muted">${esc([p.type, sourceNames[p.source] || p.source].filter(Boolean).join(' · '))}</span></label><input data-binding="${esc(p.code)}" list="metaPathOptions" value="${esc(editBindings[p.code] ?? p.defaultBinding ?? '')}" placeholder="${p.required ? '必填：选择受控路径或填写字面量' : '可留空'}"></div>`).join('');
}

function openEditor(code) {
    editingCode = code || null;
    const f = selectedField(code);
    $('editorTitle').textContent = f ? '编辑字段 · ' + f.fieldName : '新增字段';
    $('editCode').value = f?.fieldCode || '';
    $('editCode').readOnly = !!f;
    $('editName').value = f?.fieldName || '';
    $('editType').value = f?.dataType || 'STRING';
    $('editOptions').value = f?.valueOptions || '';
    $('editEnabled').checked = f?.enabled ?? true;
    renderChecks('editRules', rules.map(r => [r, r]), split(f?.allowedRules || 'C2'));
    renderOperators(split(f?.allowedOperators || ''));
    // 取值来源：没有配置过的旧字段按请求输入；集合字段只能由字段解析器取数
    $('editSource').value = isSetType($('editType').value) ? 'DATA_RESOLVER' : ['FUNCTION', 'DATA_RESOLVER'].includes(f?.valueSource) ? f.valueSource : 'REQUEST';
    editFunctionCode = f?.functionCode || '';
    editBindings = parseBindings(f?.argumentBindings);
    editResolverCode = f?.resolverCode || '';
    $('editScope').value = f?.executionScope || 'CANDIDATE';
    $('editCache').value = f?.cachePolicy || 'REQUEST';
    rulesBeforeLock = null;
    lockRulesToC1();
    renderResolverOptions();
    renderFunctionOptions();
    renderBindingInputs();
    $('editError').textContent = '';
    $('editorBackdrop').classList.add('show');
    $('editName').focus();
}

function closeEditor() {
    $('editorBackdrop').classList.remove('show');
    editingCode = null;
}

async function saveField(event) {
    event.preventDefault();
    $('editError').textContent = '';
    const source = $('editSource').value, bound = source === 'FUNCTION', resolved = source === 'DATA_RESOLVER';
    const payload = {
        fieldCode: $('editCode').value.trim(),
        fieldName: $('editName').value.trim(),
        dataType: $('editType').value,
        allowedOperators: checked('editOperators').join(','),
        allowedRules: checked('editRules').join(','),
        valueOptions: $('editOptions').disabled ? '' : $('editOptions').value.trim(),
        enabled: $('editEnabled').checked,
        // 取值来源随每次保存显式提交：REQUEST 请求输入 / FUNCTION 函数计算（须指定函数）/ DATA_RESOLVER 数据解析（须指定解析器）
        valueSource: source,
        functionCode: bound ? $('editFunction').value : null,
        // 数据解析字段：字段解析器、元素类型（集合目前只支持 STRING）、执行作用域、缓存策略
        resolverCode: resolved ? $('editResolver').value : null,
        elementType: resolved && isSetType(payload.dataType) ? 'STRING' : null,
        executionScope: resolved ? $('editScope').value : null,
        cachePolicy: resolved ? $('editCache').value : null,
        // 参数绑定取输入框里填写的值（输入框默认带出函数库的默认绑定）
        argumentBindings: bound ? Object.fromEntries(Array.from($('editBindings').querySelectorAll('[data-binding]'))
            .filter(input => input.value.trim()).map(input => [input.dataset.binding, input.value.trim()])) : null
    };
    if (!payload.allowedRules || !payload.allowedOperators) {
        $('editError').textContent = '请至少选择一项适用规则和一项运算符';
        return;
    }
    if (bound && !payload.functionCode) {
        $('editError').textContent = '请选择字段要绑定的业务函数';
        return;
    }
    if (resolved && !payload.resolverCode) {
        $('editError').textContent = '请选择字段解析器（resolverCode）';
        return;
    }
    $('saveField').disabled = true;
    try {
        await api(editingCode ? '/field-metadata/' + encodeURIComponent(editingCode) : '/field-metadata', {
            method: editingCode ? 'PUT' : 'POST',
            body: JSON.stringify(payload)
        });
        await refresh();
        closeEditor();
    } catch (error) {
        $('editError').textContent = error.message;
    } finally {
        $('saveField').disabled = false;
    }
}

async function toggleField(code) {
    const field = selectedField(code);
    if (!field) return;
    if (!confirm(`确定${field.enabled ? '停用' : '启用'}字段「${field.fieldName}」？`)) return;
    try {
        await api('/field-metadata/' + encodeURIComponent(code) + '/toggle', {method: 'POST'});
        await refresh();
    } catch (error) {
        alert(error.message);
    }
}

async function deleteField(code) {
    const field = selectedField(code);
    if (!field || !confirm(`确定删除字段「${field.fieldName}」（${code}）？已被规则引用的字段无法删除。`)) return;
    try {
        await api('/field-metadata/' + encodeURIComponent(code), {method: 'DELETE'});
        await refresh();
    } catch (error) { alert(error.message); }
}

function defaultCondition() {
    const f = availableFields()[0];
    return f ? {
        field: f.fieldCode,
        operator: split(f.allowedOperators)[0],
        value: split(f.valueOptions)[0] || (['DECIMAL', 'INTEGER'].includes(f.dataType) ? '0' : f.dataType === 'BOOLEAN' ? 'true' : '')
    } : {field: '', operator: 'EQ', value: ''};
}

function renderConditions() {
    const available = availableFields();
    $('exprLibrary').textContent = `${$('exprRule').value} · ${available.length} 个已启用字段可用于表达式`;
    if (!conditions.length && available.length) conditions = [defaultCondition()];
    conditions = conditions.map(c => available.some(f => f.fieldCode === c.field) ? c : defaultCondition());
    // 由函数计算取值的字段在名称后标注 ƒ，由字段解析器取数的字段标注 ⟐：它们只能在规则执行中取值，不能在这里手工试算
    const bound = f => f.valueSource === 'FUNCTION' ? ' ƒ' : f.valueSource === 'DATA_RESOLVER' ? ' ⟐' : '';
    $('conditionNodes').innerHTML = available.length ? conditions.map((c, i) => {
        const f = selectedField(c.field), operators = split(f.allowedOperators), values = split(f.valueOptions);
        if (!operators.includes(c.operator)) c.operator = operators[0];
        const valueControl = f.dataType === 'BOOLEAN' ? `<select class="meta-value" data-index="${i}" data-part="value"><option value="true" ${c.value === 'true' ? 'selected' : ''}>true</option><option value="false" ${c.value === 'false' ? 'selected' : ''}>false</option></select>` : values.length && c.operator !== 'IN' ? `<select class="meta-value" data-index="${i}" data-part="value">${values.map(v => `<option value="${esc(v)}" ${c.value === v ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select>` : `<input class="meta-value" data-index="${i}" data-part="value" value="${esc(c.value)}" placeholder="${c.operator === 'IN' ? '逗号分隔多个值' : isSetType(f.dataType) ? '输入要判断的单个元素' : '请输入比较值'}" ${['DECIMAL', 'INTEGER'].includes(f.dataType) ? 'type="number" step="any"' : ''}>`;
        return `<div class="meta-cond"><select aria-label="字段 ${i + 1}" data-index="${i}" data-part="field">${available.map(x => `<option value="${esc(x.fieldCode)}" ${x.fieldCode === c.field ? 'selected' : ''}>${esc(x.fieldName)}${bound(x)} · ${esc(x.fieldCode)}</option>`).join('')}</select><select aria-label="运算符 ${i + 1}" data-index="${i}" data-part="operator">${operators.map(op => `<option value="${esc(op)}" ${op === c.operator ? 'selected' : ''}>${esc(opNames[op] || op)}</option>`).join('')}</select>${valueControl}<button type="button" class="btn secondary tiny" data-remove="${i}" ${conditions.length === 1 ? 'disabled' : ''}>✕</button><span class="meta-cond-hint">${esc(f.dataType)}${f.valueSource === 'FUNCTION' ? ' · 由函数计算取值' : f.valueSource === 'DATA_RESOLVER' ? ' · 由字段解析器取数' : ''}${values.length ? ' · 可选值：' + esc(values.join(', ')) : ''}</span></div>`;
    }).join('') : '<div class="meta-empty">当前规则没有可用字段。请先在字段库中启用并配置适用规则。</div>';
    $('addCondition').disabled = !available.length || conditions.length >= 20;
    $('runExpression').disabled = !available.length;
}

function changeCondition(index, part, value) {
    const c = conditions[index];
    if (!c) return;
    c[part] = value;
    if (part === 'field') {
        const f = selectedField(value);
        c.operator = split(f.allowedOperators)[0];
        c.value = split(f.valueOptions)[0] || (['DECIMAL', 'INTEGER'].includes(f.dataType) ? '0' : f.dataType === 'BOOLEAN' ? 'true' : '');
        renderConditions();
    } else if (part === 'operator') {
        renderConditions();
    }
}

async function runExpression() {
    try {
        if (!conditions.length) throw Error('请先添加条件');
        // 函数计算、数据解析的字段只能在规则执行中取值（如 C1 页的模拟校验），这里没有调用函数 / 字段解析器所需的上下文
        const computed = conditions.filter(c => ['FUNCTION', 'DATA_RESOLVER'].includes(selectedField(c.field)?.valueSource));
        if (computed.length) throw Error(`字段 ${computed.map(c => '「' + selectedField(c.field).fieldName + '」').join('、')} 由业务函数或字段解析器取值，只能在规则执行（如 C1 页的模拟校验）中求值，不能在这里手工试算。`);
        const variables = JSON.parse($('exprVars').value);
        const tree = {
            logic: $('groupLogic').value,
            children: conditions.map(c => ({field: c.field, operator: c.operator, value: c.value}))
        };
        const result = await api('/expression/preview', {
            method: 'POST',
            body: JSON.stringify({tree, variables, ruleCode: $('exprRule').value})
        });
        $('exprResult').innerHTML = `<div class="meta-result ${result.matched ? 'ok' : 'no'}">${result.matched ? 'TRUE · 条件命中' : 'FALSE · 未命中'}</div><div class="meta-codeblock">${esc(result.expression)}</div>`;
    } catch (error) {
        $('exprResult').innerHTML = `<div class="meta-result no">${esc(error.message)}</div>`;
    }
}

async function refresh() {
    // 函数库数据、解析器清单与字段列表并行读取；读取失败不影响字段管理，只在绑定函数 / 选择解析器时提示
    loadFunctionCatalog();
    loadResolvers();
    fields = await api('/field-metadata');
    renderCatalog();
    renderConditions();
    if (activeTab === 'functions' && typeof refreshFunctions === 'function') await refreshFunctions();
}

$('ruleFilter').innerHTML += rules.map(r => `<option value="${r}">${r}</option>`).join('');
$('exprRule').innerHTML = rules.map(r => `<option value="${r}" ${r === 'C2' ? 'selected' : ''}>${r} · 适用字段</option>`).join('');
document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => showTab(button.dataset.tab));
$('fieldSearch').oninput = renderCatalog;
$('ruleFilter').onchange = renderCatalog;
$('statusFilter').onchange = renderCatalog;
$('fieldRows').onclick = event => {
    const edit = event.target.closest('[data-edit]'), toggle = event.target.closest('[data-toggle]'), remove = event.target.closest('[data-delete]');
    if (edit) openEditor(edit.dataset.edit);
    if (toggle) toggleField(toggle.dataset.toggle);
    if (remove) deleteField(remove.dataset.delete);
};
$('newField').onclick = () => openEditor();
// 数据类型决定可绑定的函数（返回类型须一致）：类型变化后重新确定函数，函数变了参数绑定随之重取默认值。
// 集合类型（STRING_SET）只能由字段解析器取数：选它时取值来源自动切到「数据解析」；改回其它类型时回到「请求输入」
$('editType').onchange = () => {
    if (isSetType($('editType').value)) $('editSource').value = 'DATA_RESOLVER';
    else if ($('editSource').value === 'DATA_RESOLVER') $('editSource').value = 'REQUEST';
    renderOperators();
    renderResolverOptions();
    lockRulesToC1();
    renderFunctionOptions();
};
$('editSource').onchange = () => {
    lockRulesToC1();
    renderResolverOptions();
    renderFunctionOptions();
};
$('editResolver').onchange = () => {
    editResolverCode = $('editResolver').value;
};
$('editResolverRetry').onclick = loadResolvers;
$('editFunction').onchange = () => {
    editFunctionCode = $('editFunction').value;
    editBindings = {};
    renderBindingInputs();
    renderFunctionHint();
};
$('editFunctionRetry').onclick = loadFunctionCatalog;
$('fieldForm').onsubmit = saveField;
$('closeEditor').onclick = closeEditor;
$('cancelEditor').onclick = closeEditor;
$('editorBackdrop').onclick = event => {
    if (event.target.id === 'editorBackdrop') closeEditor();
};
$('exprRule').onchange = () => {
    conditions = [];
    renderConditions();
};
$('conditionNodes').onchange = event => {
    const el = event.target.closest('[data-part]');
    if (el) changeCondition(Number(el.dataset.index), el.dataset.part, el.value);
};
$('conditionNodes').oninput = event => {
    const el = event.target.closest('input[data-part="value"]');
    if (el) changeCondition(Number(el.dataset.index), 'value', el.value);
};
$('conditionNodes').onclick = event => {
    const el = event.target.closest('[data-remove]');
    if (el) {
        conditions.splice(Number(el.dataset.remove), 1);
        renderConditions();
    }
};
$('addCondition').onclick = () => {
    if (conditions.length >= 20) return;
    conditions.push(defaultCondition());
    renderConditions();
};
$('runExpression').onclick = runExpression;
$('refresh').onclick = async () => {
    try {
        await refresh();
    } catch (error) {
        alert(error.message);
    }
};
document.querySelectorAll('[data-sample]').forEach(button => button.onclick = () => {
    const sample = button.dataset.sample;
    $('exprRule').value = sample === 'usdkg' ? 'C2' : 'C8';
    $('groupLogic').value = sample === 'or' ? 'OR' : 'AND';
    conditions = sample === 'usdkg' ? [{
        field: 'paymentCurrency',
        operator: 'EQ',
        value: 'USD'
    }, {field: 'beneficiaryBankCountry', operator: 'EQ', value: 'KG'}] : sample === 'million' ? [{
        field: 'amountUsd',
        operator: 'GTE',
        value: '1000000'
    }] : [{field: 'paymentCurrency', operator: 'EQ', value: 'USD'}, {
        field: 'paymentCurrency',
        operator: 'EQ',
        value: 'HKD'
    }];
    renderConditions();
});
refresh().catch(error => {
    $('fieldRows').innerHTML = `<tr><td colspan="8" class="meta-empty">字段加载失败：${esc(error.message)}</td></tr>`;
});
