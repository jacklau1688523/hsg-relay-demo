'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const state = {revision: 0, rows: [], providers: []};

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: d});
const show = v => (v == null || v === '' || v === 'ALL') ? '全部' : String(v);
const range = (min, max) => min == null && max == null ? '不限' : (min == null ? '≤' : (max == null ? '≥' : `${fmt(min)}–`)) + (max == null ? fmt(min ?? '') : fmt(max));

function render() {
    const p = $('filterProvider').value;
    const rows = state.rows.filter(r => !p || r.rule.provider === p);
    $('rowCount').textContent = `偏好规则（共 ${rows.length} 条）`;
    $('rows').innerHTML = rows.map(r => {
        const x = r.rule;
        return `<tr><td>#${r.index}</td><td>${esc(x.provider)}</td><td>${esc(show(x.currency))}</td><td>${esc(show(x.country))}</td><td>${esc(show(x.region))}</td><td>${esc(range(x.minUsd, x.maxUsd))}</td><td>${esc(show(x.paymentType))}</td><td>${esc(show(x.segment))}</td><td><span class="points-badge">＋${esc(x.points)}</span></td><td><a href="/provider-detail.html?code=${encodeURIComponent(x.provider)}&tab=preferences">合作方详情 ↗</a></td></tr>`;
    }).join('') || '<tr><td colspan="10">暂无符合条件的规则。</td></tr>';
}

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    $('revision').textContent = `配置版本 ${state.revision}`;
    $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + [...new Set(data.rows.map(r => r.rule.provider))].map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join('');
    render();
}

$('filterProvider').onchange = render;
$('query').onclick = render;
$('refresh').onclick = async () => {
    try { apply(await api('/c8/rules')); $('pageError').textContent = ''; }
    catch (e) { $('pageError').textContent = e.message; }
};
$('simForm').onsubmit = async e => {
    e.preventDefault();
    $('simulate').disabled = true;
    try {
        const clean = s => s && s.trim() ? s.trim().toUpperCase() : null;
        const req = {
            paymentCurrency: clean($('simCurrency').value), beneficiaryBankCountry: clean($('simCountry').value),
            beneficiaryRegion: clean($('simRegion').value), amount: Number($('simAmount').value),
            paymentType: $('simType').value, clientSegment: clean($('simSegment').value)
        };
        const r = await api('/c8/simulate', {method: 'POST', body: JSON.stringify(req)});
        const c = r.calculation;
        const condDiv = (cs) => `<div class="cond">${cs.map(x => `<div><b>${esc(x.label)}${x.wildcard ? '<span class="wild"> · 匹配全部</span>' : ''}</b>配置：${esc(x.configured)} ｜ 交易：${esc(x.actual)} <span class="${x.matched ? 'hit' : 'miss'}">${x.matched ? '✓ 命中' : '✗ 不匹配'}</span></div>`).join('')}</div>`;
        const ruleBlocks = c.evaluations.map(ev => `<div class="step"><span class="num">${ev.matched ? '✓' : '✗'}</span><div><b>#${ev.index} → ${esc(ev.rule.provider)} ＋${esc(ev.rule.points)} 分 · ${ev.matched ? '<span class="hit">命中</span>' : '<span class="miss">未命中</span>'}</b>${condDiv(ev.conditions)}</div></div>`).join('');
        const providerScores = c.providers.map(p => `<div class="provider-score"><span>${esc(p.provider)}${p.hitIndexes.length ? ` <span class="muted">命中规则 #${p.hitIndexes.join('、#')}</span>` : ''}</span><span>累计 ${esc(p.rawPoints)} → <span class="pts">${esc(p.score)} 分</span></span></div>`).join('');
        const winner = c.providers.filter(p => p.score > 0).sort((a, b) => b.score - a.score);
        $('simResult').className = '';
        $('simResult').innerHTML = `<div class="score-big"><span>命中规则后各合作方偏好得分</span>${winner.length ? winner.map(w => `<b>${esc(w.provider)} ＋${esc(w.score)} 分</b>`).join('') : '<b>0 分</b>'}<small>合计封顶100；未命中或未配置规则得0分 · 交易美元等价 ${esc(fmt(r.txn.amountUsd))} USD</small></div>${ruleBlocks}<div style="margin-top:10px"><b style="font-size:13px">合作方得分汇总</b>${providerScores || '<div class="muted">尚无任何规则配置。</div>'}</div><div class="notice">${esc(r.note)}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    $('simCurrency').value = 'USD';
    $('simRegion').value = '';
    $('simSegment').value = 'STANDARD';
    $('simType').value = 'TRADE';
    if (b.dataset.sample === 'kg') {
        $('simCountry').value = 'KG';
        $('simAmount').value = 30000;
    } else if (b.dataset.sample === 'million') {
        $('simCountry').value = 'US';
        $('simAmount').value = 1000000;
    } else if (b.dataset.sample === 'gd') {
        $('simCountry').value = 'CN';
        $('simRegion').value = 'CN-GD';
        $('simAmount').value = 50000;
    } else {
        $('simCountry').value = 'DE';
        $('simCurrency').value = 'EUR';
        $('simAmount').value = 5000;
    }
});
(async () => {
    try {
        const [providers, data] = await Promise.all([api('/providers'), api('/c8/rules')]);
        state.providers = providers;
        apply(data);
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
