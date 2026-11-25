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

const options = (values, selected) => values.map(v => `<option value="${esc(v)}" ${v === selected ? 'selected' : ''}>${esc(v)}</option>`).join('');
const fmt = (v, d = 2) => v == null || v === '' ? '—' : Number(v).toLocaleString('en-US', {maximumFractionDigits: d});
const OUTCOME = {NO_TARGET: '无月度目标', TARGET_MET: '已达标', ON_PACE: '进度正常', BEHIND_PACE: '进度落后'};
const METRIC = {AMOUNT: '月累计金额 USD', COUNT: '月累计笔数'};
const badge = s => `<span class="score-badge score-${s}">${s} 分</span>`;

function dayInfo() {
    const c = state.rows[0]?.calculation;
    if (c && c.daysInMonth) return c;
    const now = new Date();
    return {dayOfMonth: now.getDate(), daysInMonth: new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()};
}

function paceCell(item, timePercent) {
    if (!item || item.outcome === 'NO_TARGET') return '<span class="pace-note">未设月度目标</span>';
    const pct = Math.min(120, Number(item.targetPercent ?? 0));
    return `<div class="pacebar"><div class="fill" style="width:${Math.min(100, pct)}%"></div><div class="mark" style="left:${Math.min(100, Number(timePercent))}%"></div></div><span class="pace-note">MTD ${esc(fmt(item.targetPercent, 1))}% ｜ 基准 ${esc(fmt(timePercent, 1))}%</span>`;
}

function render() {
    const p = $('filterProvider').value, o = $('filterOutcome').value;
    const rows = state.rows.filter(r => (!p || r.provider === p) && (!o || r.calculation.outcome === o));
    $('rowCount').textContent = `合作方履约进度（共 ${rows.length} 家）`;
    $('rows').innerHTML = rows.map(r => `<tr><td>${esc(r.provider)}<br><span class="muted">${esc(r.name)} · ${r.status === 'LIVE' ? '上线' : '未上线'}</span></td><td class="agreements">${r.agreements.length ? r.agreements.map(a => `${esc(a.metric)} · 目标 ${esc(fmt(a.monthlyTarget))}`).join('<br>') : '<span class="muted">无协议</span>'}</td><td>${esc(fmt(r.monthUsd))} USD<br><span class="muted">${esc(fmt(r.monthCount, 0))} 笔</span></td><td>第 ${r.calculation.dayOfMonth} / ${r.calculation.daysInMonth} 天<br><span class="muted">${esc(fmt(r.calculation.timePercent, 1))}%</span></td><td>${r.calculation.items.length ? r.calculation.items.map(i => paceCell(i, r.calculation.timePercent)).join('<hr style="border:0;border-top:1px dashed #e3eaf3;margin:6px 0">') : '<span class="pace-note">—</span>'}</td><td>${r.calculation.items.map(i => `${badge(i.score)}<span class="outcome">${esc(OUTCOME[i.outcome] || i.outcome)}</span>`).join('') || '<span class="pace-note">—</span>'}<b style="display:block;margin-top:4px">综合 ${badge(r.calculation.score)}</b></td><td><a href="/provider-detail.html?code=${encodeURIComponent(r.provider)}&tab=agreements">合作方详情 ↗</a></td></tr>`).join('') || '<tr><td colspan="7">暂无符合条件的合作方。</td></tr>';
}

function apply(data) {
    state.rows = data.rows;
    state.revision = data.revision;
    $('revision').textContent = `配置版本 ${state.revision}`;
    render();
}

$('filterProvider').onchange = render;
$('filterOutcome').onchange = render;
$('query').onclick = render;
$('refresh').onclick = async () => {
    try { apply(await api('/c9/agreements')); $('pageError').textContent = ''; }
    catch (e) { $('pageError').textContent = e.message; }
};

function step(n, title, value, detail) {
    return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;
}

const OUTCOME_DETAIL = {
    NO_TARGET: '合作方无月度目标（或目标为0）→ 得0分。',
    TARGET_MET: 'MTD 已 ≥ 月度目标 → 得0分，协议已履行。',
    BEHIND_PACE: 'MTD ＜ 目标 ×（当月日期 ÷ 当月总天数）→ 进度落后，得100分。',
    ON_PACE: 'MTD ≥ 进度基准且未达标 → 进度正常，得50分。'
};
$('simForm').onsubmit = async e => {
    e.preventDefault();
    $('simulate').disabled = true;
    try {
        const req = {
            providerCode: $('simProvider').value, metric: $('simMetric').value || null,
            monthlyTarget: $('simTarget').value === '' ? null : Number($('simTarget').value),
            monthUsd: $('simUsd').value === '' ? null : Number($('simUsd').value),
            monthCount: $('simCount').value === '' ? null : Number($('simCount').value),
            at: $('at').value ? $('at').value + '+08:00' : null
        };
        const r = await api('/c9/simulate', {method: 'POST', body: JSON.stringify(req)});
        const c = r.calculation;
        const itemBlocks = c.items.length ? (() => {
            let n = 2;
            return c.items.map(i => {
                const targetLine = `${esc(METRIC[i.metric] || i.metric)} · 月度目标 ${esc(fmt(i.monthlyTarget))}`;
                const paceLine = i.outcome === 'NO_TARGET' ? '未设置进度基准' : `基准＝${esc(fmt(i.monthlyTarget))} × (${c.dayOfMonth} ÷ ${c.daysInMonth}) ＝ ${esc(fmt(i.expectedPace))}`;
                const mtdLine = `${esc(METRIC[i.metric] || i.metric)} · MTD ${esc(fmt(i.mtd))}${i.metric === 'COUNT' ? ' 笔' : ' USD'}`;
                return `${step(n++, '读取商业协议', targetLine, '来源：商业协议配置（BRD 9.3）。')}${step(n++, '读取 MTD 月累计', mtdLine, '来源：MTD 月累计金额 / 交易笔数统计。')}${step(n++, '计算当月进度基准', paceLine, `当月第 ${c.dayOfMonth} 天 / 共 ${c.daysInMonth} 天，时间进度 ${esc(fmt(c.timePercent, 1))}%。`)}${step(n++, '对比判定', `${badge(i.score)} <span class="outcome">${esc(OUTCOME[i.outcome] || i.outcome)}</span>`, OUTCOME_DETAIL[i.outcome])}`;
            }).join('');
        })() : step(2, '读取商业协议', '该合作方暂无协议', '无月度目标 → 直接得0分。');
        $('simResult').className = '';
        $('simResult').innerHTML = `<div class="score-result ${c.outcome === 'BEHIND_PACE' ? 'behind' : ''}"><span>C9 履约评分${c.items.length > 1 ? '（多协议取最高分）' : ''}</span><b>${c.score} 分</b><small>${esc(OUTCOME[c.outcome] || c.outcome)} · 仅参与打分，不剔除候选</small></div><div class="notice">${esc(r.providerCode)} · 配置版本 ${r.revision} · MTD ${esc(fmt(r.monthUsd))} USD / ${esc(fmt(r.monthCount, 0))} 笔</div>${step(1, '评估时间与当月进度', (c.evaluatedAt + '').replace('T', ' ').replace(/\[.*/, ''), `香港自然月第 ${c.dayOfMonth} / ${c.daysInMonth} 天，时间进度 ${esc(fmt(c.timePercent, 1))}%。`)}${itemBlocks}<div class="notice">${esc(r.note)}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    const {dayOfMonth: d, daysInMonth: n} = dayInfo(), target = 5000000, frac = d / n;
    $('simProvider').value = state.providers[0]?.code || 'PROV-T1';
    $('simMetric').value = 'AMOUNT';
    $('simCount').value = '';
    $('at').value = '';
    if (b.dataset.sample === 'none') {
        $('simTarget').value = 0;
        $('simUsd').value = 0;
    } else if (b.dataset.sample === 'met') {
        $('simTarget').value = target;
        $('simUsd').value = target;
    } else if (b.dataset.sample === 'normal') {
        $('simTarget').value = target;
        $('simUsd').value = Math.round(target * Math.min(frac + 0.1, 0.99));
    } else {
        $('simTarget').value = target;
        $('simUsd').value = Math.round(target * frac * 0.5);
    }
});
(async () => {
    try {
        const [providers, data] = await Promise.all([api('/providers'), api('/c9/agreements')]);
        state.providers = providers;
        $('filterProvider').innerHTML = '<option value="">全部合作方</option>' + options(providers.map(p => p.code));
        $('simProvider').innerHTML = providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
        apply(data);
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
