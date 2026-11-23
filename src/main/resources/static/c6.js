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
        return `<tr class="${state.editIndex === r.index ? 'row-selected' : ''}"><td>#${r.index}</td><td>${esc(x.provider)}<br><span class="muted">${esc(x.currency)}</span></td><td>${dim(x)}</td><td>${esc(fmt(x.perPaymentUsd))}</td><td>${esc(fmt(x.dailyUsd))}</td><td>${on ? '<span class="ok">启用</span>' : '<span class="bad">停用</span>'}</td><td><button class="btn secondary tiny" data-edit="${r.index}">调整限额</button></td></tr>`;
    }).join('') || '<tr><td colspan="7">暂无符合条件的覆盖行。</td></tr>';
}

function rowOptions(selected) {
    return state.rows.map(r => `<option value="${r.index}" ${r.index === selected ? 'selected' : ''}>#${r.index} · ${esc(r.reach.provider)} / ${esc(r.reach.currency)} / ${esc(r.reach.country)} / ${esc(r.reach.region)}</option>`).join('');
}

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    state.today = data.todayUsd;
    state.currencies = [...data.currencies].sort();
    $('revision').textContent = `配置版本 ${state.revision}`;
    $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + [...new Set(data.rows.map(r => r.reach.provider))].map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join('');
    $('filterCurrency').innerHTML = '<option value="">全部币种</option>' + state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    $('simCurrency').innerHTML = state.currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    $('simProvider').innerHTML = state.providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
    $('todayProvider').innerHTML = state.providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
    render();
}

function fillForm(index) {
    const row = state.rows.find(r => r.index === index) || state.rows[0];
    if (!row) return;
    state.editIndex = row.index;
    $('rowSelect').innerHTML = rowOptions(row.index);
    $('perPayment').value = row.reach.perPaymentUsd;
    $('daily').value = row.reach.dailyUsd;
    $('limitError').textContent = '';
    $('limitGood').textContent = '';
    render();
}

function fillToday() {
    const provider = $('todayProvider').value || state.providers[0]?.code;
    $('todayUsd').value = state.today[provider] ?? 0;
    $('todayError').textContent = '';
    $('todayGood').textContent = '';
}

$('filterProvider').onchange = render;
$('filterCurrency').onchange = render;
$('query').onclick = render;
$('rowSelect').onchange = () => fillForm(Number($('rowSelect').value));
$('refresh').onclick = async () => {
    try {
        apply(await api('/c6/limits'));
        fillForm(state.editIndex);
        fillToday();
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
$('limitForm').onsubmit = async e => {
    e.preventDefault();
    $('limitError').textContent = '';
    $('limitGood').textContent = '';
    $('limitSave').disabled = true;
    try {
        const input = {
            revision: state.revision,
            index: state.editIndex,
            perPaymentUsd: Number($('perPayment').value),
            dailyUsd: Number($('daily').value)
        };
        if (!(input.perPaymentUsd >= 0) || !(input.dailyUsd >= 0)) throw Error('两项限额必须为不小于0的数字');
        apply(await api('/c6/limits', {method: 'PUT', body: JSON.stringify(input)}));
        fillForm(input.index);
        $('limitGood').textContent = `已保存，配置版本 ${state.revision}。下一次路由试算即按新限额判定。`;
        $('simResult').className = 'notice';
        $('simResult').textContent = '限额已保存，请重新执行模拟查看最新判定。';
    } catch (err) {
        $('limitError').textContent = err.message;
    } finally {
        $('limitSave').disabled = false;
    }
};
$('limitCancel').onclick = () => fillForm(state.editIndex);
$('todayForm').onsubmit = async e => {
    e.preventDefault();
    $('todayError').textContent = '';
    $('todayGood').textContent = '';
    $('todaySave').disabled = true;
    try {
        const input = {
            revision: state.revision,
            provider: $('todayProvider').value,
            todayUsd: Number($('todayUsd').value)
        };
        if (!(input.todayUsd >= 0)) throw Error('当日累计必须为不小于0的数字');
        apply(await api('/c6/today', {method: 'PUT', body: JSON.stringify(input)}));
        fillToday();
        $('todayGood').textContent = `已保存 ${input.provider} 的当日累计，配置版本 ${state.revision}。`;
        $('simResult').className = 'notice';
        $('simResult').textContent = '当日累计已保存，请重新执行模拟查看最新判定。';
    } catch (err) {
        $('todayError').textContent = err.message;
    } finally {
        $('todaySave').disabled = false;
    }
};
$('todayProvider').onchange = fillToday;
$('todayCancel').onclick = fillToday;

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
        fillForm(data.rows[0]?.index);
        fillToday();
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
