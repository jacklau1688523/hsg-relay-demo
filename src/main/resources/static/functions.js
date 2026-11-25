'use strict';
let businessFunctions = [], editingFunctionCode = null, functionHandlers = [];
const fnSplit = value => String(value || '').split(',').map(x => x.trim()).filter(Boolean);
const fnParams = () => Array.from($('fnParams').querySelectorAll('.function-param')).map(row => ({
    code: row.querySelector('[data-fn-param="code"]').value.trim(),
    type: row.querySelector('[data-fn-param="type"]').value,
    source: row.querySelector('[data-fn-param="source"]').value
}));

function functionPayload() {
    return {
        functionCode: $('fnCode').value.trim(), functionName: $('fnName').value.trim(),
        category: $('fnCategory').value.trim(), returnType: $('fnReturnType').value,
        allowedRules: Array.from($('fnRules').querySelectorAll('input:checked')).map(x => x.value).join(','),
        executionScope: $('fnScope').value, cacheStrategy: $('fnCache').value,
        handlerCode: $('fnHandler').value, dataSource: $('fnDataSource').value.trim(),
        parametersJson: JSON.stringify(fnParams()), description: $('fnDescription').value.trim(),
        enabled: $('fnEnabled').checked
    };
}
function previewFunction() { $('fnPreview').textContent = JSON.stringify(functionPayload(), null, 2); }
function addFunctionParam(param = {}) {
    const row = document.createElement('div');
    row.className = 'function-param';
    row.innerHTML = `<input data-fn-param="code" placeholder="参数编码" maxlength="64" value="${esc(param.code || '')}" aria-label="参数编码"><select data-fn-param="type" aria-label="参数类型">${['STRING','INTEGER','DECIMAL','BOOLEAN','DATE'].map(t => `<option ${t === param.type ? 'selected' : ''}>${t}</option>`).join('')}</select><select data-fn-param="source" aria-label="参数来源">${['CANDIDATE','TRANSACTION','CONTEXT'].map(t => `<option ${t === param.source ? 'selected' : ''}>${t}</option>`).join('')}</select><button type="button" class="btn secondary tiny" data-remove-fn-param aria-label="删除参数">✕</button>`;
    $('fnParams').append(row);
    previewFunction();
}
function renderFunctionList() {
    const enabled = businessFunctions.filter(f => f.enabled);
    $('functionStats').innerHTML = [[businessFunctions.length,'函数总数'],[enabled.length,'启用函数'],[enabled.filter(f => fnSplit(f.allowedRules).includes('C1')).length,'C1 可用函数'],[enabled.filter(f => fnSplit(f.allowedRules).includes('C2')).length,'C2 可用函数']].map(([count,label]) => `<div class="meta-stat"><strong>${count}</strong><span>${label}</span></div>`).join('');
    const query = $('functionSearch').value.trim().toLowerCase(), rule = $('functionRuleFilter').value, status = $('functionStatusFilter').value;
    const shown = businessFunctions.filter(f => (!rule || fnSplit(f.allowedRules).includes(rule)) && (!status || (status === 'on') === f.enabled) && (!query || [f.functionCode,f.functionName,f.handlerCode,f.category,f.dataSource].join(' ').toLowerCase().includes(query)));
    $('functionRows').innerHTML = shown.map(f => `<tr><td><span class="function-name">${esc(f.functionName)}</span><br><span class="function-code">${esc(f.functionCode)}</span></td><td>${esc(f.category)}</td><td><span class="meta-tag">${esc(f.returnType)}</span></td><td><div class="meta-tags">${fnSplit(f.allowedRules).map(r => `<span class="meta-tag">${esc(r)}</span>`).join('')}</div></td><td>${esc(f.executionScope)}</td><td class="mono">${esc(f.handlerCode)}</td><td class="mono">${esc(f.dataSource || '—')}</td><td>${f.enabled ? '<span class="pass badge">启用</span>' : '<span class="pending badge">停用</span>'}</td><td><div class="actions"><button type="button" class="btn secondary tiny" data-fn-edit="${esc(f.functionCode)}">编辑</button><button type="button" class="btn secondary tiny" data-fn-toggle="${esc(f.functionCode)}">${f.enabled ? '停用' : '启用'}</button><button type="button" class="btn secondary tiny" data-fn-delete="${esc(f.functionCode)}">删除</button></div></td></tr>`).join('') || '<tr><td colspan="9" class="meta-empty">没有匹配的函数</td></tr>';
}
async function refreshFunctions() {
    const [list, handlers] = await Promise.all([api('/function-metadata'), api('/function-metadata/handlers')]);
    businessFunctions = list;
    functionHandlers = handlers;
    renderFunctionList();
}
function openFunctionEditor(code) {
    editingFunctionCode = code || null;
    const f = businessFunctions.find(x => x.functionCode === code);
    $('functionEditorTitle').textContent = f ? '编辑业务函数 · ' + f.functionName : '新增业务函数';
    $('fnCode').value = f?.functionCode || '';
    $('fnCode').readOnly = !!f;
    $('fnName').value = f?.functionName || '';
    $('fnCategory').value = f?.category || '客户与准入';
    $('fnReturnType').value = f?.returnType || 'BOOLEAN';
    $('fnRules').innerHTML = rules.map(r => `<label><input type="checkbox" value="${r}" ${fnSplit(f?.allowedRules || 'C1').includes(r) ? 'checked' : ''}>${r}</label>`).join('');
    $('fnScope').value = f?.executionScope || 'CANDIDATE';
    $('fnCache').value = f?.cacheStrategy || 'REQUEST';
    $('fnHandler').innerHTML = functionHandlers.map(h => `<option value="${esc(h)}">${esc(h)}</option>`).join('');
    $('fnHandler').value = f?.handlerCode || functionHandlers[0] || '';
    $('fnDataSource').value = f?.dataSource || '';
    $('fnDescription').value = f?.description || '';
    $('fnEnabled').checked = f?.enabled ?? true;
    $('fnParams').innerHTML = '';
    let params = [];
    try { params = JSON.parse(f?.parametersJson || '[]'); } catch (_) {}
    params.forEach(addFunctionParam);
    $('fnError').textContent = '';
    previewFunction();
    $('functionBackdrop').classList.add('show');
    $('fnName').focus();
}
function closeFunctionEditor() { $('functionBackdrop').classList.remove('show'); editingFunctionCode = null; }
async function saveFunction(event) {
    event.preventDefault();
    const payload = functionPayload();
    if (!payload.allowedRules) { $('fnError').textContent = '请至少选择一项适用规则'; return; }
    $('fnError').textContent = '';
    $('saveFunction').disabled = true;
    try {
        await api(editingFunctionCode ? '/function-metadata/' + encodeURIComponent(editingFunctionCode) : '/function-metadata', {method: editingFunctionCode ? 'PUT' : 'POST', body: JSON.stringify(payload)});
        await refreshFunctions();
        closeFunctionEditor();
    } catch (error) { $('fnError').textContent = error.message; }
    finally { $('saveFunction').disabled = false; }
}
$('functionRuleFilter').innerHTML += rules.map(r => `<option value="${r}">${r}</option>`).join('');
$('functionSearch').oninput = renderFunctionList;
$('functionRuleFilter').onchange = renderFunctionList;
$('functionStatusFilter').onchange = renderFunctionList;
$('newFunction').onclick = async () => { if (!functionHandlers.length) await refreshFunctions(); openFunctionEditor(); };
$('closeFunctionEditor').onclick = closeFunctionEditor;
$('cancelFunctionEditor').onclick = closeFunctionEditor;
$('functionBackdrop').onclick = event => { if (event.target.id === 'functionBackdrop') closeFunctionEditor(); };
$('functionForm').onsubmit = saveFunction;
$('functionForm').oninput = previewFunction;
$('functionForm').onchange = previewFunction;
$('fnAddParam').onclick = () => addFunctionParam();
$('fnParams').onclick = event => { const button = event.target.closest('[data-remove-fn-param]'); if (button) { button.closest('.function-param').remove(); previewFunction(); } };
$('functionRows').onclick = async event => {
    const edit = event.target.closest('[data-fn-edit]'), toggle = event.target.closest('[data-fn-toggle]'), remove = event.target.closest('[data-fn-delete]');
    if (edit) openFunctionEditor(edit.dataset.fnEdit);
    if (toggle) {
        const f = businessFunctions.find(x => x.functionCode === toggle.dataset.fnToggle);
        if (f && confirm(`确定${f.enabled ? '停用' : '启用'}函数「${f.functionName}」？`)) { try { await api('/function-metadata/' + encodeURIComponent(f.functionCode) + '/toggle', {method:'POST'}); await refreshFunctions(); } catch (error) { alert(error.message); } }
    }
    if (remove) {
        const f = businessFunctions.find(x => x.functionCode === remove.dataset.fnDelete);
        if (f && confirm(`确定删除函数「${f.functionName}」？`)) { try { await api('/function-metadata/' + encodeURIComponent(f.functionCode), {method:'DELETE'}); await refreshFunctions(); } catch (error) { alert(error.message); } }
    }
};
