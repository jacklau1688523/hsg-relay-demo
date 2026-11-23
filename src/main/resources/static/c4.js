'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const state = {revision: 0, rows: [], currencies: [], hkdRates: {}, switches: [], editIndex: null};
const VENUE = {NONE: 'NONE · 无需换汇', TREASURY: 'TREASURY · 资金部换汇', PROVIDER: 'PROVIDER · 合作方换汇'};

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: d});
const money = (v, cur) => `<span class="mono">${esc(fmt(v))}</span> ${esc(cur)}`;
const available = b => Number(b.balance) - Number(b.reserved);
const switchOf = code => state.switches.find(s => s.code === code);
const providerName = code => switchOf(code)?.name || '';
const badge = on => on ? '<span class="badge pass">ON</span>' : '<span class="badge pending">OFF</span>';
const currencyOptions = () => state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
const providerOptions = () => state.switches.map(s => `<option value="${esc(s.code)}">${esc(s.code)} · ${esc(s.name)}</option>`).join('');

/** 重建下拉选项时保留当前选中值；当前值为空或已不存在时取 fallback。 */
function options(el, html, fallback) {
    const v = el.value;
    el.innerHTML = html;
    el.value = [...el.options].some(o => o.value === v) ? v : fallback;
}

function savedSwitchLabel() {
    const s = switchOf($('simProvider').value);
    $('simSwitch').options[0].textContent = `用合作方已保存的开关（当前 ${s ? (s.fundingCheckEnabled ? 'ON' : 'OFF') : '—'}）`;
}

function render() {
    const p = $('filterProvider').value, c = $('filterCurrency').value;
    const rows = state.rows.filter(r => (!p || r.balance.provider === p) && (!c || r.balance.currency === c));
    $('rowCount').textContent = `往来账余额（共 ${rows.length} 行）`;
    $('rows').innerHTML = rows.map(r => {
        const b = r.balance, avail = available(b), sw = switchOf(b.provider);
        return `<tr class="${state.editIndex === r.index ? 'row-selected' : ''}"><td>#${r.index}</td><td><b>${esc(b.provider)}</b><br><span class="muted">${esc(providerName(b.provider))}</span></td><td>${esc(b.currency)}</td><td>${money(b.balance, b.currency)}</td><td>${money(b.reserved, b.currency)}</td><td class="${avail < 0 ? 'neg' : ''}">${money(avail, b.currency)}</td><td>${sw ? badge(sw.fundingCheckEnabled) : '—'}</td><td><button class="btn secondary tiny" data-edit="${r.index}">编辑</button></td></tr>`;
    }).join('') || '<tr><td colspan="8" style="text-align:center">暂无符合条件的余额行。</td></tr>';
    $('rates').textContent = `HSG 当日汇率（1 单位币种折合港币）：${state.currencies.map(c => `${c} ${fmt(state.hkdRates[c], 4)}`).join(' · ')}。PROVIDER 渠道以「交易币种汇率 ÷ 扣账币种汇率」把交易金额折算为扣账账户币种的等价金额。`;
}

function renderSwitches() {
    $('switches').innerHTML = state.switches.map(s => `<tr><td><b>${esc(s.code)}</b><br><span class="muted">${esc(s.name)}</span></td><td>${s.status === 'LIVE' ? '<span class="badge pass">LIVE</span>' : '<span class="badge pending">NOT_LIVE</span>'}</td><td>${badge(s.fundingCheckEnabled)}</td><td>${esc(s.nostroAccount || '未设置')}</td></tr>`).join('') || '<tr><td colspan="4" style="text-align:center">暂无合作方。</td></tr>';
}

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    state.currencies = [...data.currencies].sort();
    state.hkdRates = data.hkdRates;
    state.switches = data.fundingSwitches;
    $('revision').textContent = `配置版本 ${state.revision}`;
    options($('filterProvider'), '<option value="">全部合作方</option>' + [...new Set(data.rows.map(r => r.balance.provider))].map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join(''), '');
    options($('filterCurrency'), '<option value="">全部币种</option>' + state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join(''), '');
    options($('simDebit'), currencyOptions(), 'HKD');
    options($('simPayment'), currencyOptions(), 'USD');
    options($('balCurrency'), currencyOptions(), 'USD');
    options($('simProvider'), providerOptions(), 'PROV-T1');
    options($('balProvider'), providerOptions(), 'PROV-T1');
    savedSwitchLabel();
    render();
    renderSwitches();
}

function fillForm(index) {
    const row = typeof index === 'number' ? state.rows.find(r => r.index === index) : null;
    state.editIndex = row ? row.index : null;
    const b = row ? row.balance : {provider: $('balProvider').value, currency: 'USD', balance: 0, reserved: 0};
    options($('balProvider'), providerOptions(), b.provider);
    options($('balCurrency'), currencyOptions(), b.currency);
    $('balBalance').value = b.balance;
    $('balReserved').value = b.reserved;
    $('balanceDelete').disabled = !row;
    $('formTitle').textContent = row ? `编辑往来账余额 · #${row.index} ${b.provider} / ${b.currency}` : '新增往来账余额';
    $('balanceError').textContent = '';
    $('balanceGood').textContent = '';
    render();
}

$('filterProvider').onchange = render;
$('filterCurrency').onchange = render;
$('query').onclick = render;
$('new').onclick = () => {
    fillForm();
    $('formPanel').scrollIntoView({behavior: 'smooth'});
};
$('refresh').onclick = async () => {
    try {
        apply(await api('/c4/balances'));
        fillForm(state.editIndex);
        $('pageError').textContent = '';
    } catch (e) {
        $('pageError').textContent = e.message;
    }
};
$('rows').onclick = e => {
    const b = e.target.closest('[data-edit]');
    if (b) {
        fillForm(Number(b.dataset.edit));
        $('formPanel').scrollIntoView({behavior: 'smooth'});
    }
};
$('balanceCancel').onclick = () => fillForm();

function staleSim(text) {
    $('simResult').className = 'notice';
    $('simResult').textContent = text;
}

$('balanceForm').onsubmit = async e => {
    e.preventDefault();
    $('balanceError').textContent = '';
    $('balanceGood').textContent = '';
    $('balanceSave').disabled = true;
    try {
        const balance = {
            provider: $('balProvider').value,
            currency: $('balCurrency').value,
            balance: Number($('balBalance').value),
            reserved: Number($('balReserved').value || 0)
        };
        if (!(balance.balance >= 0) || !(balance.reserved >= 0)) throw Error('账户余额与未记账占用必须为不小于0的数字');
        apply(await api('/c4/balances', {
            method: 'PUT',
            body: JSON.stringify({revision: state.revision, index: state.editIndex, balance})
        }));
        const hit = state.rows.find(r => r.balance.provider === balance.provider && r.balance.currency === balance.currency);
        fillForm(hit ? hit.index : null);
        $('balanceGood').textContent = `已保存，配置版本 ${state.revision}。下一次路由试算即按新余额与占用判定 C4。`;
        staleSim('余额已保存，请重新执行模拟查看最新判定。');
    } catch (err) {
        $('balanceError').textContent = err.message;
    } finally {
        $('balanceSave').disabled = false;
    }
};
$('balanceDelete').onclick = async () => {
    if (state.editIndex == null) return;
    const row = state.rows.find(r => r.index === state.editIndex);
    if (!row) return;
    if (!confirm(`确定删除 ${row.balance.provider} / ${row.balance.currency} 的往来账余额行？删除后该币种可用头寸按 0 计。`)) return;
    $('balanceError').textContent = '';
    $('balanceGood').textContent = '';
    try {
        apply(await api('/c4/balances/delete', {
            method: 'POST',
            body: JSON.stringify({revision: state.revision, index: state.editIndex})
        }));
        fillForm();
        $('balanceGood').textContent = '余额行已删除。';
        staleSim('余额行已删除，请重新执行模拟查看最新判定。');
    } catch (err) {
        $('balanceError').textContent = err.message;
    }
};
$('simProvider').onchange = savedSwitchLabel;

function step(n, title, value, detail) {
    return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;
}

function venueBlock(v) {
    const c = v.calculation, on = c.enabled, ok = c.passed, provider = c.fxVenue === 'PROVIDER',
        acct = c.accountCurrency, avail = Number(c.available), enough = avail >= Number(c.required);
    const step3 = c.accountFound
        ? `${fmt(c.balance)} − ${fmt(c.reserved)} ＝ <b class="${avail < 0 ? 'neg' : ''}">${fmt(c.available)}</b> ${esc(acct)}`
        : `未配置 ${esc(acct)} 余额行，可用头寸按 <b class="neg">0</b> 计`;
    const compare = `可用头寸 ${fmt(c.available)} ${esc(acct)} ${enough ? '≥' : '&lt;'} 本笔等价金额 ${fmt(c.required)} ${esc(acct)}`;
    const step4 = on
        ? `${compare} → ${ok ? '<span class="ok">充足，PASS</span>' : '<span class="bad">不足，FUNDING_SHORTFALL</span>'}`
        : `${compare}（数值上${enough ? '充足' : '不足'}），但开关 OFF 不参与判定 → <span class="ok">PASS</span>`;
    return `<div class="channel ${ok ? '' : 'closed'}"><header><h3>${esc(VENUE[c.fxVenue] || c.fxVenue)}</h3><span class="${ok ? 'ok' : 'bad'}">${ok ? 'PASS' : 'FUNDING_SHORTFALL'}</span></header>
  ${step(1, '合作方资金校验开关', on ? '<b>ON</b> · 继续校验头寸' : '<b>OFF</b> · 直接通过', on ? '开关由合作方管理维护（BRD 9.1）；开启时逐笔校验往来账可用头寸。' : '后补头寸的合作方（如 Wise）设为 OFF：资金由资金部在合作方出款前补足，逻辑在 RELAY 模块之外；以下数值仅供参考，不参与判定。')}
  ${step(2, '账户币种判定', `外汇渠道 <span class="mono">${esc(c.fxVenue)}</span> → 校验 <b>${esc(acct)}</b> 往来账（交易币种 ${esc(c.paymentCurrency)} · 扣账币种 ${esc(c.debitCurrency)}）`, provider ? 'PROVIDER 渠道取扣账账户币种。' : 'NONE / TREASURY 渠道取交易币种。')}
  ${step(3, '可用头寸 ＝ 账户余额 − 未记账占用', step3, '实时读取 HSG：我行在合作方的往来账 nostro 余额，扣除尚未记账的已路由交易占用。')}
  ${step(4, '本笔等价金额与判定', step4, provider ? `等价金额＝交易金额 ${fmt(c.amount)} ${esc(c.paymentCurrency)} × HSG 当日汇率 ${fmt(c.rate, 6)}（1 ${esc(c.paymentCurrency)} ＝ ${fmt(c.rate, 6)} ${esc(acct)}）≈ ${fmt(c.required)} ${esc(acct)}。` : `账户币种即交易币种：等价金额＝交易金额 ${fmt(c.amount)} ${esc(acct)}，无需换算。`)}
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
            fundingCheckEnabled: $('simSwitch').value === '' ? null : $('simSwitch').value === 'true'
        };
        const r = await api('/c4/simulate', {method: 'POST', body: JSON.stringify(req)});
        let verdict;
        if (r.status === 'NO_FX_VENUE') verdict = '<div class="verdict fail"><span>C4 结果</span><b>NO_FX_VENUE</b><small>没有任何外汇渠道配置该货币对窗口：全流程路由不生成候选，C4 不适用，返回 REFER NO_FX_VENUE</small></div>';
        else if (r.status !== 'PASS') verdict = '<div class="verdict fail"><span>C4 结果</span><b>FUNDING_SHORTFALL</b><small>全部渠道可用头寸不足，该合作方候选被剔除；全流程中全部候选仅因 C4 失败时返回 REFER FUNDING_SHORTFALL——运营审核环节，资金部确认补头寸后，运营可人工选用该合作方（示例 6b）</small></div>';
        else verdict = `<div class="verdict"><span>C4 结果</span><b>PASS</b><small>${r.fundingCheckEnabled ? '至少一个渠道可用头寸充足，候选保留进入 C6 / C10 过滤与后续打分' : '资金校验开关 OFF：直接通过，账户与头寸数值仅供参考（后补头寸的合作方，资金由资金部在出款前补足）'}</small></div>`;
        $('simResult').className = '';
        $('simResult').innerHTML = verdict + r.venues.map(venueBlock).join('') + `<div class="notice">${esc(r.note)} · 配置版本 ${r.revision}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    const set = (provider, debit, payment, amount, sw) => {
        $('simProvider').value = provider;
        savedSwitchLabel();
        $('simDebit').value = debit;
        $('simPayment').value = payment;
        $('simAmount').value = amount;
        $('simSwitch').value = sw;
    };
    const s = b.dataset.sample;
    if (s === 'pass') set('PROV-T1', 'USD', 'USD', 10000, '');
    else if (s === 'short') set('PROV-T1', 'USD', 'USD', 60000000, '');
    else if (s === 'treasury') set('PROV-T1', 'HKD', 'USD', 10000, '');
    else if (s === 'provider') set('PROV-W', 'USD', 'AUD', 78000000, 'true');
    else if (s === 'off') set('PROV-W', 'USD', 'AUD', 78000000, 'false');
    else set('PROV-T1', 'HKD', 'EUR', 10000, '');
});
(async () => {
    try {
        apply(await api('/c4/balances'));
        fillForm();
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
