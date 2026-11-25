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
    IN: '属于 IN'
};
const allowedByType = {
    STRING: ['EQ', 'NE', 'IN'],
    ENUM: ['EQ', 'NE', 'IN'],
    DECIMAL: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE'],
    INTEGER: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE'],
    BOOLEAN: ['EQ', 'NE']
};
const split = value => String(value || '').split(',').map(x => x.trim()).filter(Boolean);
let fields = [], editingCode = null, conditions = [], activeTab = 'catalog';

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

function renderCatalog() {
    const enabled = fields.filter(f => f.enabled).length;
    $('metaStats').innerHTML = `<div class="meta-stat"><strong>${fields.length}</strong><span>字段总数</span></div><div class="meta-stat"><strong>${enabled}</strong><span>启用字段</span></div><div class="meta-stat"><strong>${fields.filter(f => f.enabled && split(f.allowedRules).includes('C2')).length}</strong><span>C2 适用字段</span></div>`;
    const query = $('fieldSearch').value.trim().toLowerCase(), rule = $('ruleFilter').value,
        status = $('statusFilter').value;
    const shown = fields.filter(f => (!rule || split(f.allowedRules).includes(rule)) && (!status || (status === 'on') === f.enabled) && (!query || [f.fieldName, f.fieldCode, f.dataType, f.valueOptions].join(' ').toLowerCase().includes(query)));
    $('fieldRows').innerHTML = shown.map(f => `<tr><td><strong>${esc(f.fieldName)}</strong><br><span class="meta-code mono">${esc(f.fieldCode)}</span></td><td><span class="meta-tag">${esc(f.dataType)}</span></td><td><div class="meta-tags">${split(f.allowedRules).map(r => `<span class="meta-tag">${esc(r)}</span>`).join('')}</div></td><td>${split(f.allowedOperators).map(op => esc(opNames[op] || op)).join(' · ')}</td><td class="mono">${esc(f.valueOptions || '—')}</td><td>${f.enabled ? '<span class="pass badge">启用</span>' : '<span class="pending badge">停用</span>'}</td><td><div class="actions"><button type="button" class="btn secondary tiny" data-edit="${esc(f.fieldCode)}">编辑</button><button type="button" class="btn secondary tiny" data-toggle="${esc(f.fieldCode)}">${f.enabled ? '停用' : '启用'}</button><button type="button" class="btn secondary tiny" data-delete="${esc(f.fieldCode)}">删除</button></div></td></tr>`).join('') || '<tr><td colspan="7" class="meta-empty">没有匹配的字段</td></tr>';
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
    const payload = {
        fieldCode: $('editCode').value.trim(),
        fieldName: $('editName').value.trim(),
        dataType: $('editType').value,
        allowedOperators: checked('editOperators').join(','),
        allowedRules: checked('editRules').join(','),
        valueOptions: $('editOptions').disabled ? '' : $('editOptions').value.trim(),
        enabled: $('editEnabled').checked
    };
    if (!payload.allowedRules || !payload.allowedOperators) {
        $('editError').textContent = '请至少选择一项适用规则和一项运算符';
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
    $('conditionNodes').innerHTML = available.length ? conditions.map((c, i) => {
        const f = selectedField(c.field), operators = split(f.allowedOperators), values = split(f.valueOptions);
        if (!operators.includes(c.operator)) c.operator = operators[0];
        const valueControl = f.dataType === 'BOOLEAN' ? `<select class="meta-value" data-index="${i}" data-part="value"><option value="true" ${c.value === 'true' ? 'selected' : ''}>true</option><option value="false" ${c.value === 'false' ? 'selected' : ''}>false</option></select>` : values.length && c.operator !== 'IN' ? `<select class="meta-value" data-index="${i}" data-part="value">${values.map(v => `<option value="${esc(v)}" ${c.value === v ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select>` : `<input class="meta-value" data-index="${i}" data-part="value" value="${esc(c.value)}" placeholder="${c.operator === 'IN' ? '逗号分隔多个值' : '请输入比较值'}" ${['DECIMAL', 'INTEGER'].includes(f.dataType) ? 'type="number" step="any"' : ''}>`;
        return `<div class="meta-cond"><select aria-label="字段 ${i + 1}" data-index="${i}" data-part="field">${available.map(x => `<option value="${esc(x.fieldCode)}" ${x.fieldCode === c.field ? 'selected' : ''}>${esc(x.fieldName)} · ${esc(x.fieldCode)}</option>`).join('')}</select><select aria-label="运算符 ${i + 1}" data-index="${i}" data-part="operator">${operators.map(op => `<option value="${esc(op)}" ${op === c.operator ? 'selected' : ''}>${esc(opNames[op] || op)}</option>`).join('')}</select>${valueControl}<button type="button" class="btn secondary tiny" data-remove="${i}" ${conditions.length === 1 ? 'disabled' : ''}>✕</button><span class="meta-cond-hint">${esc(f.dataType)}${values.length ? ' · 可选值：' + esc(values.join(', ')) : ''}</span></div>`;
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
$('editType').onchange = () => renderOperators();
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
    $('fieldRows').innerHTML = `<tr><td colspan="7" class="meta-empty">字段加载失败：${esc(error.message)}</td></tr>`;
});
