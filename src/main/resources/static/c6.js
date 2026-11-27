'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const state = {revision: 0, rows: [], currencies: [], today: {}, providers: []};

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: d});
const code = (c, ok) => `<span class="mono" style="color:${ok ? '#0a8745' : '#bf3b1f'}">${esc(c)}</span>`;
const dim = r => `${esc(r.country)} · ${esc(r.region)} · ${r.bics && r.bics.length ? `LIST(${r.bics.length})` : 'ALL'}`;

function render() {
    const p = $('filterProvider').value, c = $('filterCurrency').value;
    const rows = state.rows.filter(r => (!p || r.reach.provider === p) && (!c || r.reach.currency === c));
    $('rowCount').textContent = `覆盖行限额（共 ${rows.length} 行）`;
    $('rows').innerHTML = rows.map(r => {
        const x = r.reach;
        const on = x.enabled !== false;
        return `<tr><td>#${r.index}</td><td>${esc(x.provider)}<br><span class="muted">${esc(x.currency)}</span></td><td>${dim(x)}</td><td>${esc(fmt(x.perPaymentUsd))}</td><td>${esc(fmt(x.dailyUsd))}</td><td>${on ? '<span class="ok">启用</span>' : '<span class="bad">停用</span>'}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(x.provider)}&tab=limits">合作方详情 ↗</a></td></tr>`;
    }).join('') || '<tr><td colspan="7">暂无符合条件的覆盖行。</td></tr>';
    const providers=state.providers.filter(item=>!p||item.code===p);
    $('todayRows').innerHTML=providers.map(item=>{const configured=Object.prototype.hasOwnProperty.call(state.today,item.code);return `<tr><td>${esc(item.code)} · ${esc(item.name)}</td><td>${esc(fmt(state.today[item.code]??0))}</td><td>${configured?'演示快照':'未配置，按 0 计算'}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(item.code)}&tab=limits">合作方详情 ↗</a></td></tr>`;}).join('')||'<tr><td colspan="4">暂无合作方。</td></tr>';
}

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    state.today = data.todayUsd;
    state.currencies = [...data.currencies].sort();
    $('revision').textContent = `配置版本 ${state.revision}`;
    $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + state.providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
    $('filterCurrency').innerHTML = '<option value="">全部币种</option>' + state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    $('simCurrency').innerHTML = state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    $('simProvider').innerHTML = state.providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
    render();
}

$('filterProvider').onchange = render;
$('filterCurrency').onchange = render;
$('query').onclick = render;
$('refresh').onclick = async () => {
    try { apply(await api('/c6/limits')); $('pageError').textContent = ''; }
    catch (e) { $('pageError').textContent = e.message; }
};

function step(n, title, value, detail) {
    return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;
}

$('simForm').onsubmit = async e => {
    e.preventDefault();
    $('simulate').disabled = true;
    try {
        const req = {
            providerCode: $('simProvider').value,
            paymentCurrency: $('simCurrency').value,
            amount: Number($('simAmount').value),
            beneficiaryBankCountry: $('simCountry').value.trim().toUpperCase(),
            beneficiaryRegion: $('simRegion').value.trim().toUpperCase() || null,
            beneficiaryBankBic: $('simBic').value.trim().toUpperCase(),
            chargeOption: $('simCharge').value,
            todayUsd: $('simToday').value === '' ? null : Number($('simToday').value)
        };
        const r = await api('/c6/simulate', {method: 'POST', body: JSON.stringify(req)});
        if (r.status === 'SKIPPED') { $('simResult').className = ''; $('simResult').innerHTML = '<div class="verdict"><span>C6 结果</span><b>已跳过</b><small>校验开关已关闭，不执行交易限额过滤。</small></div>'; return; }
        const fail = !r.status || r.status !== 'PASS';
        const c = r.calculation;
        let body = '';
        if (r.status === 'NO_REACH') {
            body = `<div class="verdict fail"><span>C6 结果</span><b>NO_REACH（C3 未覆盖）</b><small>限额不适用；该候选已被 C3 剔除</small></div>${step(1, 'C3 匹配覆盖行', '未命中任何覆盖行', '币种 / 国家 / 地区 / 银行范围 / 扣费方式需同时匹配。')}${step(2, '限额判定', '不执行', '无覆盖行时 C6 不返回原因码，详情见 C3 页面。')}`;
        } else {
            const perOk = `${fmt(c.amountUsd)} ≤ ${fmt(c.perPaymentUsd)} ${code('LIMIT_PER_PAYMENT', c.perPaymentPassed)}`;
            const dayOk = `${fmt(c.todayUsd)} ＋ ${fmt(c.amountUsd)} ＝ ${fmt(c.todayAfterUsd)} ≤ ${fmt(c.dailyUsd)} ${code('LIMIT_DAILY', c.dailyPassed)}`;
            body = `<div class="verdict ${fail ? 'fail' : ''}"><span>C6 结果</span><b>${fail ? esc(c.reasons.join('、')) : 'PASS'}</b><small>${fail ? '该候选将被剔除，原因码随候选列表返回 S2/S4 界面' : '两项限额全部通过，候选保留进入打分排序'}</small></div>
   ${step(1, 'C3 选中覆盖行', `#${esc(r.selectedIndex)} · ${esc(r.selectedReach.provider)} / ${esc(r.selectedReach.currency)} / ${dim(r.selectedReach)}`, '多条命中时取最精确行：指定地区＞ALL、指定国家＞ALL、LIST＞ALL。')}
   ${step(2, '本笔美元等价金额', `${fmt(c.amountUsd)} USD`, '交易金额 × HSG 中间汇率 ÷ USD 汇率；当日累计不含本笔自身。')}
   ${step(3, '校验 1 · 单笔限额', perOk, '本笔美元等价金额 ≤ 覆盖行单笔最大限额。')}
   ${step(4, '校验 2 · 当日累计', dayOk, '当日累计＋本笔 ≤ 每日最大限额；两项校验均执行并留痕。')}`;
        }
        $('simResult').className = '';
        $('simResult').innerHTML = body + `<div class="notice">${esc(r.note)} · 配置版本 ${r.revision}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    $('simProvider').value = 'PROV-T1';
    $('simCurrency').value = 'USD';
    $('simCountry').value = 'US';
    $('simRegion').value = '';
    $('simBic').value = 'CHASUS33';
    $('simCharge').value = 'SHA';
    $('simToday').value = '';
    if (b.dataset.sample === 'pass') {
        $('simAmount').value = 10000;
    } else if (b.dataset.sample === 'per') {
        $('simAmount').value = 3000000;
    } else if (b.dataset.sample === 'daily') {
        $('simProvider').value = 'PROV-W';
        $('simAmount').value = 100000;
        $('simToday').value = 9950000;
    } else {
        $('simProvider').value = 'PROV-GD';
        $('simAmount').value = 1000;
        $('simCountry').value = 'CN';
        $('simRegion').value = 'CN-ZJ';
        $('simBic').value = 'ICBKCNBJBJA';
    }
});
(async () => {
    try {
        const [providers, data] = await Promise.all([api('/providers'), api('/c6/limits')]);
        state.providers = providers;
        apply(data);
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
