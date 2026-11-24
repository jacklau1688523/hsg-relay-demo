'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const state = {revision: 0, rows: [], currencies: [], rates: {}, providers: []};

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: d
});
const VENUE = {NONE: '无需换汇', TREASURY: 'TREASURY · 资金部', PROVIDER: 'PROVIDER · 合作方换汇'};

function render() {
    const p = $('filterProvider').value, c = $('filterCurrency').value;
    const rows = state.rows.filter(r => (!p || r.fee.provider === p) && (!c || r.fee.currency === c));
    $('rowCount').textContent = `手续费配置行（共 ${rows.length} 行）`;
    $('rows').innerHTML = rows.map(r => {
        const f = r.fee;
        return `<tr><td>#${r.index}</td><td>${esc(f.provider)}<br><span class="muted">${esc(f.currency)}</span></td><td>${esc(f.charge)}</td><td>${esc(fmt(f.fixed))} ${esc(f.feeCurrency)}</td><td>${esc(f.rate)}</td><td>${esc(fmt(f.minimum))} / ${f.maximum == null ? '不封顶' : esc(fmt(f.maximum))}</td><td>${esc(fmt(f.cable))}</td><td>${esc(fmt(f.intermediary))}</td><td>${esc(f.feeCurrency)}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(f.provider)}&tab=fees">合作方详情 ↗</a></td></tr>`;
    }).join('') || '<tr><td colspan="10">暂无符合条件的手续费行。</td></tr>';
    $('rates').innerHTML = Object.entries(state.rates).map(([k, v]) => `<span>${esc(k)}→HKD ${esc(v)}</span>`).join('');
}

const providerOptions = sel => state.providers.map(p => `<option value="${esc(p.code)}" ${p.code === sel ? 'selected' : ''}>${esc(p.code)} · ${esc(p.name)}</option>`).join('');
const currencyOptions = sel => state.currencies.map(c => `<option value="${esc(c)}" ${c === sel ? 'selected' : ''}>${esc(c)}</option>`).join('');

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    state.currencies = [...data.currencies].sort();
    state.rates = data.hkdRates;
    $('revision').textContent = `配置版本 ${state.revision}`;
    $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + [...new Set(data.rows.map(r => r.fee.provider))].map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join('');
    $('filterCurrency').innerHTML = '<option value="">全部币种</option>' + state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    for (const id of ['simDebit', 'simPayment']) $(id).innerHTML = currencyOptions($(id).value || 'USD');
    $('simProvider').innerHTML = providerOptions($('simProvider').value);
    render();
}

$('filterProvider').onchange = render;
$('filterCurrency').onchange = render;
$('query').onclick = render;
$('refresh').onclick = async () => {
    try { apply(await api('/c7/fees')); $('pageError').textContent = ''; }
    catch (e) { $('pageError').textContent = e.message; }
};

function step(n, title, value, detail) {
    return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;
}

function venueBlock(v, r) {
    const c = v.calculation;
    let n = 1;
    const clamp = {MIN: ' → 低于最低，按最低托底', MAX: ' → 超过最高，按最高封顶', NONE: ''}[c.clamp] || '';
    const windowLine = v.fxVenue === 'NONE' ? '无需换汇' : `窗口 ${v.window ? v.window.days.join('/') : '—'} ${v.window ? v.window.start + '–' + v.window.end : ''}${v.window && v.window.maxUsd != null ? ` · 单笔上限 ${fmt(v.window.maxUsd)} USD` : ''} ${v.windowOpen ? '<span class="ok">开放</span>' : '<span class="bad">关闭/超限</span>'}`;
    return `<div class="venue ${v.windowOpen ? '' : 'closed'}"><header><h3>${esc(VENUE[v.fxVenue] || v.fxVenue)}</h3><span class="total">HKD ${esc(fmt(c.totalHkd))}</span></header>
 ${step(n++, '外汇渠道与窗口', windowLine, esc(v.windowNote))}
 ${step(n++, '合作方手续费', `(${fmt(c.fee.fixed)} ＋ ${esc(c.fee.rate)} × ${fmt(c.amountInFeeCurrency)}) ${esc(c.fee.feeCurrency)} ＝ <b>${fmt(c.commission)} ${esc(c.fee.feeCurrency)}</b>${esc(clamp)}`, '固定费用＋百分比费率×交易金额；低于最低取最低、高于最高取最高。')}
 ${step(n++, '电报费 / 预估中间行费用', `${fmt(c.fee.cable)} ＋ ${fmt(c.fee.intermediary)} ${esc(c.fee.feeCurrency)} ＝ ${fmt(Number(c.fee.cable) + Number(c.fee.intermediary))} ${esc(c.fee.feeCurrency)}`, '两项均取自手续费配置行。')}
 ${step(n++, '换汇点差成本', `${v.fxVenue === 'NONE' ? '0（无换汇）' : `${fmt(r.amountHkd)} HKD × ${esc(v.spreadBps)}bps ÷10000 ＝ <b>${fmt(c.fxCostHkd)} HKD</b>`}`, '点差取评估时间可用窗口；非工作时段窗口含加点。')}
 ${step(n++, '折算港币合计', `手续费小计 ${fmt(c.feeSubtotalFeeCurrency)} × ${esc(state.rates[c.fee.feeCurrency] || 1)} ＋ 换汇 ${fmt(c.fxCostHkd)} ＝ <b>HKD ${fmt(c.totalHkd)}</b>`, '全部项目按 HSG 中间汇率折算港币，保留2位小数。')}
 </div>`;
}

$('simForm').onsubmit = async e => {
    e.preventDefault();
    $('simulate').disabled = true;
    try {
        const req = {
            providerCode: $('simProvider').value,
            debitCurrency: $('simDebit').value,
            paymentCurrency: $('simPayment').value,
            amount: Number($('simAmount').value),
            chargeOption: $('simCharge').value,
            at: $('at').value ? $('at').value + '+08:00' : null
        };
        const r = await api('/c7/simulate', {method: 'POST', body: JSON.stringify(req)});
        const head = `<div class="notice">交易金额折算：${fmt(r.amountHkd)} HKD / ${fmt(r.amountUsd)} USD 等价 · 配置版本 ${r.revision}</div>`;
        $('simResult').className = '';
        $('simResult').innerHTML = (r.venues.length ? r.venues.map(v => venueBlock(v, r)).join('') : `<div class="venue closed"><header><h3>无可用外汇渠道</h3></header>该货币对没有任何窗口配置，全流程路由返回 REFER <b>NO_FX_VENUE</b>。</div>`) + `<div class="notice">${esc(r.note)}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    const at = s => {
        $('at').value = s.slice(0, 16);
    };
    if (b.dataset.sample === 'none') {
        $('simProvider').value = 'PROV-W';
        $('simDebit').value = 'USD';
        $('simPayment').value = 'USD';
        $('simAmount').value = 10000;
        at('2026-11-20T10:00:00+08:00');
    } else if (b.dataset.sample === 'treasury') {
        $('simProvider').value = 'PROV-T1';
        $('simDebit').value = 'HKD';
        $('simPayment').value = 'USD';
        $('simAmount').value = 20000;
        at('2026-11-23T09:05:00+08:00');
    } else if (b.dataset.sample === 'weekend') {
        $('simProvider').value = 'PROV-W';
        $('simDebit').value = 'HKD';
        $('simPayment').value = 'AUD';
        $('simAmount').value = 20000;
        at('2026-11-21T14:00:00+08:00');
    } else {
        $('simProvider').value = 'PROV-T1';
        $('simDebit').value = 'HKD';
        $('simPayment').value = 'USD';
        $('simAmount').value = 20000;
        at('2026-11-21T10:00:00+08:00');
    }
});
(async () => {
    try {
        const [providers, data] = await Promise.all([api('/providers'), api('/c7/fees')]);
        state.providers = providers;
        apply(data);
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
