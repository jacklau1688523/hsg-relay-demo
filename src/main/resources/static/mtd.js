'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const FLOWS = ['STANDARD', 'PSP', 'WEB3'], MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
// 香港时间（HKT 固定 UTC+8）：把 UTC 整体后移 8 小时再取 ISO 字符串，即得到香港的日历日期与时间
const hkNow = () => new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 19);
const CURRENT_MONTH = hkNow().slice(0, 7);
const iso = v => {
    const s = String(v ?? '').slice(0, 19);
    return s.length === 16 ? s + ':00' : s;
};
const state = {rows: [], providers: [], rules: [], editKey: null};

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: d});
const providerName = code => state.providers.find(p => p.code === code)?.name || '';
const keyOf = (p, f, m) => `${p}|${f}|${m}`;
const sorted = rows => rows.slice().sort((a, b) => b.monthKey.localeCompare(a.monthKey) || a.providerCode.localeCompare(b.providerCode) || a.flowCategory.localeCompare(b.flowCategory));

function activeRule(r, now) {
    return r.enabled && !!r.effectiveFrom && iso(r.effectiveFrom) <= now && (!r.effectiveTo || now < iso(r.effectiveTo));
}

// C1 实际命中的规则还取决于币种 / 受益行国家；这里取该合作方＋流转类别下所有生效 ACCEPT 规则的月度上限（升序）
function caps(provider, flow) {
    const now = hkNow();
    return [...new Set(state.rules.filter(r => r.providerCode === provider && r.flowCategory === flow && r.action === 'ACCEPT' && r.monthlyCapUsd != null && activeRule(r, now)).map(r => Number(r.monthlyCapUsd)))].sort((x, y) => x - y);
}

const hasRule = (provider, flow) => {
    const now = hkNow();
    return state.rules.some(r => r.providerCode === provider && r.flowCategory === flow && activeRule(r, now));
};

function capCell(cap, ruled) {
    if (cap.length > 1) return `${fmt(cap[0])} ~ ${fmt(cap[cap.length - 1])}`;
    if (cap.length === 1) return fmt(cap[0]);
    return `<span class="muted">${ruled ? '未设上限' : '未配置准入规则'}</span>`;
}

function render() {
    const p = $('filterProvider').value, f = $('filterFlow').value;
    const rows = sorted(state.rows).filter(r => (!p || r.providerCode === p) && (!f || r.flowCategory === f));
    $('rowCount').textContent = `MTD 月累计使用情况（共 ${rows.length} 条，当月 ${rows.filter(r => r.monthKey === CURRENT_MONTH).length} 条）`;
    $('rows').innerHTML = rows.map(r => {
        const current = r.monthKey === CURRENT_MONTH, cap = caps(r.providerCode, r.flowCategory);
        const remaining = cap.length ? cap[0] - Number(r.amountUsd) : null;
        const key = keyOf(r.providerCode, r.flowCategory, r.monthKey);
        return `<tr class="${state.editKey === key ? 'row-selected' : ''}"><td>${esc(r.providerCode)}<br><span class="muted">${esc(providerName(r.providerCode))}</span></td><td>${esc(r.flowCategory)}</td><td>${esc(r.monthKey)} ${current ? '<span class="badge pass">当月 · C1 读取</span>' : '<span class="muted">不参与校验</span>'}</td><td class="mono">${esc(fmt(r.amountUsd))}</td><td class="mono">${capCell(cap, hasRule(r.providerCode, r.flowCategory))}</td><td class="mono ${remaining != null && remaining < 0 ? 'neg' : ''}">${remaining == null ? '—' : esc(fmt(remaining))}</td><td><button class="btn secondary tiny" data-edit="${esc(key)}">调整</button></td></tr>`;
    }).join('') || '<tr><td colspan="7">暂无符合条件的 MTD 记录，可在下方「调整 MTD 月累计」中新增。</td></tr>';
}

const formKey = () => keyOf($('mtdProvider').value, $('mtdFlow').value, $('mtdMonth').value);
const existingRow = () => state.rows.find(r => keyOf(r.providerCode, r.flowCategory, r.monthKey) === formKey()) || null;

// 表单任一字段变化后：同步标题、提示、删除按钮、表格选中行和 curl 示例
function syncForm() {
    const row = existingRow(), cap = caps($('mtdProvider').value, $('mtdFlow').value), amount = $('mtdAmount').value;
    state.editKey = row ? formKey() : null;
    $('formTitle').textContent = `${row ? '调整' : '新增'} MTD · ${$('mtdProvider').value} / ${$('mtdFlow').value} / ${$('mtdMonth').value}`;
    // 当月且设有上限时，按输入框里的金额预览保存后的剩余额度（表格里显示的是已保存的值）
    const preview = cap.length && $('mtdMonth').value === CURRENT_MONTH && amount !== '' && Number(amount) >= 0 ? cap[0] - Number(amount) : null;
    $('formHint').textContent = [
        row ? `记录已存在（id ${row.id}，当前 ${fmt(row.amountUsd)} USD），保存将覆盖为新的月累计金额。` : '该合作方＋流转类别＋月份尚无记录，保存将新增一行。',
        cap.length ? `生效 ACCEPT 规则的月度上限：${cap.map(v => fmt(v)).join(' / ')} USD。` : '该组合没有设置月度上限的生效 ACCEPT 规则，MTD 不影响 C1 结果。',
        preview == null ? '' : `按当前输入保存后，当月剩余额度为 ${fmt(preview)} USD${preview < 0 ? '（已超出上限）' : ''}。`,
        $('mtdMonth').value === CURRENT_MONTH ? '' : '注意：非当月记录不参与 C1 校验。'
    ].join('');
    $('mtdDelete').disabled = !row;
    render();
    renderCurl(row);
}

function renderCurl(row) {
    const amount = $('mtdAmount').value;
    const body = JSON.stringify({
        providerCode: $('mtdProvider').value,
        flowCategory: $('mtdFlow').value,
        monthKey: $('mtdMonth').value,
        amountUsd: amount === '' ? 0 : Number(amount)
    });
    $('curlPut').textContent = `curl -X PUT http://${location.host}/api/usage \\\n  -H 'Content-Type: application/json' \\\n  -d '${body}'`;
    $('curlDelete').textContent = row ? `curl -X DELETE http://${location.host}/api/usage/${row.id}` : `# 记录保存后才有 id\ncurl -X DELETE http://${location.host}/api/usage/{id}`;
    $('curlGet').textContent = `curl http://${location.host}/api/usage`;
}

function fillForm(provider, flow, month) {
    if (state.providers.some(p => p.code === provider)) $('mtdProvider').value = provider;
    $('mtdFlow').value = FLOWS.includes(flow) ? flow : 'STANDARD';
    $('mtdMonth').value = MONTH_PATTERN.test(month || '') ? month : CURRENT_MONTH;
    $('mtdAmount').value = existingRow()?.amountUsd ?? '';
    $('mtdError').textContent = '';
    $('mtdGood').textContent = '';
    syncForm();
}

async function load() {
    const [providers, rules, rows] = await Promise.all([api('/providers'), api('/appetites'), api('/usage')]);
    const first = state.providers.length === 0;
    state.providers = providers;
    state.rules = rules;
    state.rows = rows;
    if (first) {
        const options = providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
        $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + options;
        $('mtdProvider').innerHTML = options;
    }
    $('hkMonth').textContent = `香港当月 ${CURRENT_MONTH}`;
    render();
}

const monthInput = $('mtdMonth');
if (monthInput.type !== 'month') { // 不支持 type=month 的浏览器退化为文本框，提交前按同一格式校验
    monthInput.type = 'text';
    monthInput.placeholder = 'YYYY-MM';
}

$('filterProvider').onchange = render;
$('filterFlow').onchange = render;
$('query').onclick = render;
['mtdProvider', 'mtdFlow', 'mtdMonth', 'mtdAmount'].forEach(id => $(id).oninput = syncForm);
$('mtdCancel').onclick = () => fillForm($('mtdProvider').value, $('mtdFlow').value, $('mtdMonth').value);
$('refresh').onclick = async () => {
    try {
        await load();
        syncForm();
        $('pageError').textContent = '';
    } catch (e) {
        $('pageError').textContent = e.message;
    }
};
$('rows').onclick = e => {
    const b = e.target.closest('[data-edit]');
    if (!b) return;
    const [p, f, m] = b.dataset.edit.split('|');
    fillForm(p, f, m);
    $('formPanel').scrollIntoView({behavior: 'smooth', block: 'start'});
};
$('mtdForm').onsubmit = async e => {
    e.preventDefault();
    $('mtdError').textContent = '';
    $('mtdGood').textContent = '';
    const input = {
        providerCode: $('mtdProvider').value,
        flowCategory: $('mtdFlow').value,
        monthKey: $('mtdMonth').value,
        amountUsd: $('mtdAmount').value === '' ? null : Number($('mtdAmount').value)
    };
    if (!MONTH_PATTERN.test(input.monthKey)) {
        $('mtdError').textContent = '月份格式应为 YYYY-MM';
        return;
    }
    if (!(input.amountUsd >= 0)) {
        $('mtdError').textContent = '月累计金额必须为不小于0的数字';
        return;
    }
    $('mtdSave').disabled = true;
    try {
        const existed = !!existingRow();
        const saved = await api('/usage', {method: 'PUT', body: JSON.stringify(input)});
        state.rows = await api('/usage');
        fillForm(saved.providerCode, saved.flowCategory, saved.monthKey);
        $('mtdGood').textContent = `已${existed ? '覆盖' : '新增'} ${saved.providerCode} / ${saved.flowCategory} / ${saved.monthKey} 的 MTD = ${fmt(saved.amountUsd)} USD（id ${saved.id}）。到 C1「规则模拟校验」即可看到效果。`;
    } catch (err) {
        $('mtdError').textContent = err.message;
    } finally {
        $('mtdSave').disabled = false;
    }
};
$('mtdDelete').onclick = async () => {
    const row = existingRow();
    if (!row || !confirm(`确定删除 ${row.providerCode} / ${row.flowCategory} / ${row.monthKey} 的 MTD 记录？删除后该组合按 0 参与校验。`)) return;
    $('mtdError').textContent = '';
    $('mtdGood').textContent = '';
    $('mtdDelete').disabled = true;
    try {
        await api(`/usage/${row.id}`, {method: 'DELETE'});
        state.rows = await api('/usage');
        fillForm(row.providerCode, row.flowCategory, row.monthKey);
        $('mtdGood').textContent = `已删除 ${row.providerCode} / ${row.flowCategory} / ${row.monthKey} 的 MTD 记录（id ${row.id}）。`;
    } catch (err) {
        $('mtdError').textContent = err.message;
    } finally {
        $('mtdDelete').disabled = !existingRow();
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    $('mtdError').textContent = '';
    $('mtdGood').textContent = '';
    if (b.dataset.sample === 'zero') {
        $('mtdAmount').value = 0;
    } else {
        const cap = caps($('mtdProvider').value, $('mtdFlow').value);
        if (!cap.length) {
            $('mtdError').textContent = `${$('mtdProvider').value} / ${$('mtdFlow').value} 没有设置月度上限的生效 ACCEPT 规则，无法演示超限；可改选 PROV-T1 / STANDARD。`;
            return;
        }
        // 只有当月记录参与 C1 校验：上限 − 100,000，再模拟一笔 150,000 即 APPETITE_CAP_REACHED
        $('mtdMonth').value = CURRENT_MONTH;
        $('mtdAmount').value = Math.max(0, cap[0] - 100000);
    }
    syncForm();
});
(async () => {
    const q = new URLSearchParams(location.search);
    try {
        await load();
        // 支持从 C1「MTD 使用情况」页签带参数跳转：/mtd.html?provider=..&flow=..&month=..
        fillForm(q.get('provider') || sorted(state.rows)[0]?.providerCode || state.providers[0]?.code, q.get('flow'), q.get('month'));
        if (q.has('provider')) $('formPanel').scrollIntoView({behavior: 'smooth', block: 'start'});
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
