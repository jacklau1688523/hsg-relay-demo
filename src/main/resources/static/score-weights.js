'use strict';
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));

const state = {options: null, all: [], versions: [], audits: [], editing: null, publishTarget: null, match: null};
const STATUS = {DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用'};
const OPERATION = {
    SEED: '系统初始化', CREATE_DRAFT: '新建草稿', UPDATE_DRAFT: '修改草稿', DELETE_DRAFT: '删除草稿',
    PUBLISH: '发布', SUPERSEDED: '被新版本接替', DISABLE: '停用', REOPEN: '恢复生效'
};
const HIT = {HIT: '命中', MISS: '未命中', SKIPPED: '已跳过'};
const WCOLOR = ['#1265d7', '#16a85b', '#d08a1e', '#6b4fbb'];

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    let data = null;
    try {
        data = await r.json();
    } catch (e) {
        // 非 JSON 响应：按状态码报错
    }
    if (!r.ok) throw Error((data && (data.error || data.message)) || `HTTP ${r.status}`);
    return data;
}

const dt = v => v ? String(v).replace('T', ' ').slice(0, 16) : '—';   // 到分钟（HKT）
const key = r => `${r.transactionType} + ${r.clientTier}`;
const weights = r => `${r.w1Cost} / ${r.w2Speed} / ${r.w3Preference} / ${r.w4Commercial}`;
const isFallback = r => r.transactionType === 'OTHER' && r.clientTier === 'ALL';
const rowById = id => state.all.find(r => r.id === Number(id));
const draftOf = (type, tier) => state.all.find(r => r.status === 'DRAFT' && r.transactionType === type && r.clientTier === tier);
const hasHistory = (type, tier) => state.all.some(r => r.status !== 'DRAFT' && r.transactionType === type && r.clientTier === tier);
const period = v => v.effectiveFrom ? `${dt(v.effectiveFrom)} ~ ${v.effectiveTo ? dt(v.effectiveTo) : '长期有效'}` : '—';

function fail(e) {
    console.error(e);
    $('pageError').textContent = e.message || String(e);
    $('pageMsg').classList.add('hidden');
}

function message(text) {
    $('pageError').textContent = '';
    $('pageMsg').textContent = text;
    $('pageMsg').classList.remove('hidden');
}

/** 演示环境没有登录：操作人由页面输入，保存草稿 / 发布 / 停用 / 删除都必填并记入审计。 */
function operator() {
    const who = $('operator').value.trim();
    if (!who) throw Error('请先填写操作人（「评分权重配置」页签下方的输入框）：保存草稿、发布、停用、删除都会记入审计');
    return who;
}

// ---------- 页签 ----------

function showTab(name) {
    document.querySelectorAll('.tabs [data-tab]').forEach(b => b.classList.toggle('sel', b.dataset.tab === name));
    [['config', 'tabConfig'], ['match', 'tabMatch'], ['rank', 'tabRank'], ['versions', 'tabVersions']]
        .forEach(([tab, id]) => $(id).classList.toggle('hidden', tab !== name));
    if (name === 'versions') loadVersions().catch(fail);
}

// ---------- 加载与筛选 ----------

async function load() {
    const [options, all] = await Promise.all([api('/routing/score-weights/options'), api('/routing/score-weights')]);
    state.options = options;
    state.all = all;
    fillSelects();
    $('fallbackWarn').classList.toggle('hidden', options.fallbackEffective);
    $('fallbackWarn').innerHTML = '兜底配置 <b>OTHER + ALL</b> 当前没有生效的版本：三级匹配都没有命中的交易类型会明确报错（不会按 0 权重评分）。请为 OTHER + ALL 发布一个版本。';
    render();
}

function fillSelects() {
    const o = state.options;
    const keep = (sel, html) => {
        const current = sel.value;
        sel.innerHTML = html;
        if ([...sel.options].some(x => x.value === current)) sel.value = current;
    };
    keep($('filterType'), `<option value="">全部交易类型</option>` + o.transactionTypes.map(t => `<option value="${t}">${esc(t)}</option>`).join(''));
    keep($('filterTier'), `<option value="">全部层级</option>` + o.clientTiers.map(t => `<option value="${t}">${esc(t)}</option>`).join(''));
    keep($('matchType'), o.transactionTypes.map(t => `<option value="${t}">${esc(t)}</option>`).join(''));
    $('matchTierOptions').innerHTML = o.clientTiers.map(t => `<option value="${esc(t)}"></option>`).join('');
}

function render() {
    const type = $('filterType').value, tier = $('filterTier').value, status = $('filterStatus').value;
    const rows = state.all.filter(r => (!type || r.transactionType === type) && (!tier || r.clientTier === tier) && (!status || r.status === status));
    $('rowCount').textContent = `权重配置（共 ${rows.length} 行）`;
    let prev = null;
    $('rows').innerHTML = rows.map(r => {
        const k = key(r), first = k !== prev;
        prev = k;
        const who = r.publishedAt ? r.publishedBy : r.createdBy, at = r.publishedAt || r.createdAt;
        return `<tr class="${first ? 'group-first ' : ''}${r.status === 'DRAFT' ? 'draftRow' : ''}">
            <td>${first ? `<b>${esc(r.transactionType)}</b>` : ''}</td>
            <td>${first ? `<b>${esc(r.clientTier)}</b>${isFallback(r) ? '<br><span class="muted">兜底配置</span>' : ''}` : ''}</td>
            <td title="${esc(r.note)}">v${r.versionNo}</td>
            <td class="num">${r.w1Cost}</td><td class="num">${r.w2Speed}</td><td class="num">${r.w3Preference}</td><td class="num">${r.w4Commercial}</td>
            <td class="num"><b>${r.weightSum}</b></td>
            <td><span class="badge st-${esc(r.status)}">${STATUS[r.status] || esc(r.status)}</span></td>
            <td>${currentLabel(r)}</td>
            <td>${period(r)}</td>
            <td>${esc(who || '—')}<br><span class="muted">${dt(at)}</span></td>
            <td style="white-space:nowrap">${actions(r)}</td>
        </tr>`;
    }).join('') || `<tr><td colspan="13" class="muted" style="text-align:center;padding:26px">没有符合条件的权重配置</td></tr>`;
}

/** 「当前」列：只看服务端给出的状态、有效时间段和是否在决策时间生效，不在浏览器里比较时间。 */
function currentLabel(r) {
    if (r.status === 'DRAFT') return '<span class="muted">未生效</span>';
    if (r.status === 'DISABLED') return '<span class="muted">已停用</span>';
    if (r.effectiveTo) return '<span class="muted">已结束</span>';
    return r.effectiveNow ? '<span class="badge st-PUBLISHED">生效中</span>' : '<span class="badge st-DRAFT">待生效</span>';
}

function actions(r) {
    const b = (act, label, cls, title) =>
        `<button type="button" class="btn ${cls} tiny" data-act="${act}" data-id="${r.id}" style="margin:2px 4px 2px 0"${title ? ` title="${esc(title)}"` : ''}>${label}</button>`;
    if (r.status === 'DRAFT') return b('edit', '编辑', 'secondary') + b('publish', '发布', '') + b('delete', '删除', 'secondary');
    if (r.effectiveTo) return b('newVersion', '新建新版本', 'secondary', '已结束的版本是历史记录，不能修改');
    return b('newVersion', '新建新版本', 'secondary', '已发布的版本不能修改：新建草稿，发布后接替当前版本')
        + (isFallback(r) ? '<span class="muted" title="OTHER + ALL 是兜底配置，必须保留">不可停用</span>' : b('disable', '停用', 'secondary'));
}

async function act(id, name) {
    const r = rowById(id);
    if (!r) return;
    if (name === 'edit') return openDrawer({draft: r});
    if (name === 'publish') return openPublish(r);
    if (name === 'newVersion') {
        // 已有草稿：直接编辑它（同一组只允许一份草稿）
        const draft = draftOf(r.transactionType, r.clientTier);
        return openDrawer(draft ? {draft} : {type: r.transactionType, tier: r.clientTier, from: r});
    }
    const who = operator();
    if (name === 'delete') {
        if (!confirm(`确认删除草稿 ${key(r)} v${r.versionNo}？删除后不可恢复，已发布的版本不受影响。`)) return;
        await api(`/routing/score-weights/drafts/${r.id}?operator=${encodeURIComponent(who)}`, {method: 'DELETE'});
        message(`草稿已删除：${key(r)} v${r.versionNo}`);
    } else if (name === 'disable') {
        const scheduled = !r.effectiveNow;
        const text = scheduled
            ? `确认停用 ${key(r)} v${r.versionNo}？\n这个版本还没有到生效时间：停用后它从未生效，在它的生效时间结束的上一版本恢复为长期有效。`
            : `确认停用 ${key(r)} v${r.versionNo}？\n停用后这个版本在当前时间结束并保留为历史记录；之后的决策按 P1 → P2 → P3 的顺序使用其它生效的配置。`;
        if (!confirm(text)) return;
        await api(`/routing/score-weights/${r.id}/disable`, {method: 'POST', body: JSON.stringify({operator: who, expectedVersion: r.rowVersion})});
        message(`已停用 ${key(r)} v${r.versionNo}`);
    }
    await load();
}

// ---------- 新增 / 编辑草稿 ----------

function openDrawer(target) {
    const o = state.options, draft = target.draft;
    $('drawerError').textContent = '';
    $('editType').innerHTML = o.transactionTypes.map(t => `<option value="${t}">${esc(t)}</option>`).join('');
    $('tierOptions').innerHTML = o.clientTiers.map(t => `<option value="${esc(t)}"></option>`).join('');
    state.editing = draft ? {id: draft.id, rowVersion: draft.rowVersion} : null;
    // 草稿的「交易类型 + 客户层级」不能修改
    $('editType').disabled = $('editTier').disabled = !!draft;
    $('editType').value = draft ? draft.transactionType : (target.type || o.transactionTypes[0]);
    $('editTier').value = draft ? draft.clientTier : (target.tier || 'ALL');
    const start = draft || target.from || effectiveOf($('editType').value, $('editTier').value);
    ['w1', 'w2', 'w3', 'w4'].forEach((id, i) => $(id).value = start ? [start.w1Cost, start.w2Speed, start.w3Preference, start.w4Commercial][i] : '');
    $('editNote').value = (draft && draft.note) || '';
    $('drawerTitle').textContent = draft ? `编辑草稿 v${draft.versionNo}` : '新增权重配置';
    $('drawerSub').textContent = '保存为草稿，发布后才参与路由评分；权重为 0–100 的整数，合计必须等于 100';
    $('drawer').classList.add('show');
    keyNote();
    recalc();
}

/** 某个「交易类型 + 客户层级」当前生效的版本（与匹配顺序无关，只是给新增草稿一个起始值）。 */
function effectiveOf(type, tier) {
    return state.all.find(r => r.status === 'PUBLISHED' && r.effectiveNow && r.transactionType === type && r.clientTier === tier) || null;
}

function closeDrawer() {
    $('drawer').classList.remove('show');
    state.editing = null;
}

/** 所选「交易类型 + 客户层级」的现有版本提示：是否兜底配置、当前生效的权重、是否已有草稿。 */
function keyNote() {
    const type = $('editType').value, tier = $('editTier').value.trim().toUpperCase();
    const lines = [];
    if (type === 'OTHER' && tier === 'ALL') lines.push('<b>兜底配置</b>：P1、P2 都没有命中时使用，必须保留，不能停用。');
    const live = effectiveOf(type, tier);
    if (live) lines.push(`当前生效：<b>v${live.versionNo}</b>，权重 ${weights(live)}。新版本发布后在生效时间起接替它，v${live.versionNo} 作为历史记录保留。`);
    const pending = state.all.find(r => r.id !== (state.editing || {}).id && r.status === 'PUBLISHED' && !r.effectiveTo && !r.effectiveNow
        && r.transactionType === type && r.clientTier === tier);
    if (pending) lines.push(`<span style="color:#9e6c28">已有待生效版本 v${pending.versionNo}（${dt(pending.effectiveFrom)} 起）：发布前需要先停用它。</span>`);
    const draft = state.all.find(r => r.id !== (state.editing || {}).id && r.status === 'DRAFT' && r.transactionType === type && r.clientTier === tier);
    if (draft) lines.push(`<span style="color:#9e6c28">这个配置组已有草稿 v${draft.versionNo}：请编辑那份草稿，不能同时保留两份。</span>`);
    $('keyNote').innerHTML = lines.join('<br>') || '这个「交易类型 + 客户层级」还没有任何配置。';
}

function inputWeights() {
    return ['w1', 'w2', 'w3', 'w4'].map(id => $(id).value.trim());
}

/** 权重输入的合法性：每一项都是 0–100 的整数。返回缺失或越界的说明。 */
function weightProblem(v) {
    if (v === '') return '未填写';
    if (!/^\d+$/.test(v)) return '必须是整数';
    if (Number(v) > 100) return '不能大于 100';
    return null;
}

/** 实时合计条：合计不等于 100 或有非法值时不能保存（服务端和数据库还会各校验一次）。 */
function recalc() {
    const values = inputWeights(), nums = values.map(v => weightProblem(v) ? 0 : Number(v));
    const sum = nums.reduce((a, b) => a + b, 0), problems = values.map(weightProblem);
    $('sumbar').innerHTML = nums.map((n, i) => `<span style="width:${n}%;background:${WCOLOR[i]}"></span>`).join('');
    const ok = sum === 100 && !problems.some(Boolean);
    $('sumnum').textContent = `合计 ${sum} / 100`;
    $('sumnum').className = `sumnum ${ok ? 'ok' : 'bad'}`;
    $('sumhint').textContent = ok ? '合计等于 100，可以保存草稿'
        : problems.some(Boolean) ? 'W1–W4 每一项都必须是 0–100 的整数：' + ['W1 成本', 'W2 速度', 'W3 偏好', 'W4 履约'].filter((_, i) => problems[i]).join('、') + ' 不符合'
            : sum < 100 ? `还差 ${100 - sum} 才到 100` : `超出 100 共 ${sum - 100}`;
    $('drawerSave').disabled = !ok;
    // 示例：用和「权重匹配模拟」相同的四个得分，按当前输入的权重示意总分（页面计算）
    const total = (90 * nums[0] + 100 * nums[1] + 80 * nums[2] + 50 * nums[3]) / 100;
    $('drawerExample').innerHTML = `示例：C7 = 90、C5 = 100、C8 = 80、C9 = 50 时，(90×W1 + 100×W2 + 80×W3 + 50×W4) ÷ 100 = <b>${total.toFixed(2)}</b>（页面示意计算，实际评分以服务端为准）`;
}

async function saveDraft() {
    try {
        const body = {
            transactionType: $('editType').value, clientTier: $('editTier').value.trim().toUpperCase(),
            w1Cost: Number($('w1').value), w2Speed: Number($('w2').value), w3Preference: Number($('w3').value), w4Commercial: Number($('w4').value),
            note: $('editNote').value.trim(), operator: operator()
        };
        if (state.editing) Object.assign(body, {id: state.editing.id, expectedVersion: state.editing.rowVersion});
        const saved = await api('/routing/score-weights/drafts', {method: 'POST', body: JSON.stringify(body)});
        closeDrawer();
        await load();
        message(`草稿已保存：${key(saved)} v${saved.versionNo}（权重 ${weights(saved)}）。发布后才参与路由评分。`);
    } catch (e) {
        $('drawerError').textContent = e.message || String(e);
    }
}

// ---------- 发布 ----------

function openPublish(r) {
    state.publishTarget = r;
    const history = hasHistory(r.transactionType, r.clientTier);
    const live = state.all.find(x => x.id !== r.id && x.status === 'PUBLISHED' && !x.effectiveTo && x.effectiveNow
        && x.transactionType === r.transactionType && x.clientTier === r.clientTier);
    const pending = state.all.find(x => x.id !== r.id && x.status === 'PUBLISHED' && !x.effectiveTo && !x.effectiveNow
        && x.transactionType === r.transactionType && x.clientTier === r.clientTier);
    $('publishBody').innerHTML = `发布 <b>${key(r)} v${r.versionNo}</b>：权重 ${weights(r)}，合计 ${r.weightSum}。
        <br>已发布的版本不可修改、不可覆盖；发布后立即刷新内存快照，到达生效时间的决策开始使用。
        ${history ? '<br>这个配置组已有发布过的版本：生效时间不能早于当前时间，也不能与已发布版本的有效时间段重叠。'
            : '<br>这是全新的「交易类型 + 客户层级」：可以指定过去的生效时间，历史决策回放时也会用上这个版本。'}
        ${live ? `<br>当前生效的 v${live.versionNo}（${weights(live)}）将在生效时间结束，作为历史记录保留。` : ''}
        ${pending ? `<br><span style="color:#c63a4b">已有待生效版本 v${pending.versionNo}（${dt(pending.effectiveFrom)} 起）：请先停用它，再发布这个草稿。</span>` : ''}`;
    $('publishFrom').value = '';
    $('publishError').textContent = '';
    $('publishBack').classList.add('show');
}

async function confirmPublish() {
    const button = $('publishYes'), target = state.publishTarget;
    if (!target) return;
    button.disabled = true;
    try {
        const who = operator();
        const body = {operator: who, expectedVersion: target.rowVersion};
        // datetime-local 的值形如 2026-11-26T17:00，直接作为 HKT 的 LocalDateTime 提交
        if ($('publishFrom').value) body.effectiveFrom = $('publishFrom').value;
        const published = await api(`/routing/score-weights/drafts/${target.id}/publish`, {method: 'POST', body: JSON.stringify(body)});
        $('publishBack').classList.remove('show');
        await load();
        message(`已发布 ${key(published)} v${published.versionNo}：${published.effectiveNow ? '立即生效' : `将在 ${dt(published.effectiveFrom)} 生效`}（权重 ${weights(published)}）。`);
    } catch (e) {
        $('publishError').textContent = e.message || String(e);
    } finally {
        button.disabled = false;
    }
}

// ---------- 权重匹配模拟 ----------

async function runMatch() {
    const type = $('matchType').value, tier = $('matchTier').value.trim().toUpperCase();
    if (!type) throw Error('请选择交易类型');
    if (!tier) throw Error('请填写客户层级（例如 ALL 或字段库 clientSegment 里维护的层级）');
    const body = {transactionType: type, clientTier: tier};
    if ($('matchAt').value) body.at = $('matchAt').value;
    state.match = await api('/routing/score-weights/match', {method: 'POST', body: JSON.stringify(body)});
    renderMatch();
}

function renderMatch() {
    const t = state.match, hit = !!t.matched;     // 没有命中时 matched 为空、error 说明原因
    $('matchSteps').innerHTML = `
        <div class="notice ${hit ? '' : 'warn'}">输入：<b>${esc(t.transactionType)} + ${esc(t.clientTier)}</b>，决策时间 ${t.at ? dt(t.at) : '当前时间'}
            → ${hit ? `命中 <b>${esc(t.matched.level)}</b>：${esc(t.matched.transactionType)} + ${esc(t.matched.clientTier)} v${t.matched.versionNo}`
                : `<b>没有命中</b>：${esc(t.error || '三级都没有在决策时间生效的配置')}`}</div>`
        + t.steps.map((s, i) => `<div class="mstep ${s.hit === 'HIT' ? '' : 'off'}">
            <div class="num">${esc(s.level)}</div>
            <div><b>${esc(s.transactionType)} + ${esc(s.clientTier)}</b> &nbsp;<span class="badge st-${s.hit === 'HIT' ? 'PUBLISHED' : 'DISABLED'}">${HIT[s.hit] || esc(s.hit)}</span>
                <small>${esc(s.note)}</small></div>
        </div>`).join('');
    renderExample();
}

/** 用命中的权重按四个得分试算；没有命中时明确说明，不按 0 权重计算。 */
function renderExample() {
    const t = state.match;
    if (!t) return;
    if (!t.matched) {
        $('exResult').className = 'notice warn';
        $('exResult').innerHTML = `<b>无法试算</b>：${esc(t.error)}`;
        return;
    }
    const ids = ['exC7', 'exC5', 'exC8', 'exC9'], names = ['C7 成本', 'C5 速度', 'C8 偏好', 'C9 履约'];
    const scores = ids.map(id => Number($(id).value));
    const bad = ids.map((id, i) => $(id).value.trim() === '' || !Number.isFinite(scores[i]) || scores[i] < 0 || scores[i] > 100 ? names[i] : null).filter(Boolean);
    $('exResult').className = 'notice';
    if (bad.length) {
        $('exResult').innerHTML = `得分缺失或超出 0–100：${bad.join('、')}。得分缺失不按 0 分计算，请补全后再试算。`;
        return;
    }
    const w = t.matched.weights, ws = [w.cost, w.speed, w.preference, w.commercial];
    const terms = scores.map((v, i) => `${v}×${ws[i]}`).join(' + ');
    $('exResult').innerHTML = `<b>${esc(t.matched.transactionType)} + ${esc(t.matched.clientTier)}（v${t.matched.versionNo}，${esc(t.matched.level)}）</b>：权重 ${w.cost} / ${w.speed} / ${w.preference} / ${w.commercial}
        <div style="margin-top:8px">${ws.map((x, i) => `<div class="wline"><span style="min-width:84px">W${i + 1} ${names[i]}</span><span class="bar"><i style="width:${x}%;background:${WCOLOR[i]}"></i></span><b style="min-width:28px;text-align:right">${x}</b></div>`).join('')}</div>
        <div style="margin-top:8px">(${terms}) ÷ 100 = <b>${(scores.reduce((a, v, i) => a + v * ws[i], 0) / 100).toFixed(2)}</b>（页面示意计算，保留两位小数；实际评分以服务端 BigDecimal 结果为准）</div>`;
}

// ---------- 配置版本记录 ----------

async function loadVersions() {
    const [versions, audits] = await Promise.all([api('/routing/score-weights/versions'), api('/routing/score-weights/audits')]);
    state.versions = versions;
    state.audits = audits;
    $('versionCount').textContent = `配置版本记录（共 ${versions.length} 个已发布 / 已停用的版本，不含草稿）`;
    $('versionRows').innerHTML = versions.map(r => `<tr>
        <td><b>${esc(r.transactionType)}</b></td><td>${esc(r.clientTier)}</td><td>v${r.versionNo}</td>
        <td class="num">${r.w1Cost}</td><td class="num">${r.w2Speed}</td><td class="num">${r.w3Preference}</td><td class="num">${r.w4Commercial}</td>
        <td><span class="badge st-${esc(r.status)}">${STATUS[r.status] || esc(r.status)}</span></td>
        <td>${period(r)}</td><td>${esc(r.publishedBy || '—')}</td><td>${dt(r.publishedAt)}</td><td>${esc(r.note)}</td>
    </tr>`).join('') || '<tr><td colspan="12" class="muted" style="text-align:center;padding:26px">还没有发布过的版本</td></tr>';
    $('auditRows').innerHTML = audits.map(a => `<tr>
        <td>${dt(a.operatedAt)}</td><td>${OPERATION[a.operation] || esc(a.operation)}<br><span class="muted">${esc(a.operation)}</span></td>
        <td>${esc(a.operator)}</td><td>${esc(a.transactionType)}</td><td>${esc(a.clientTier)}</td><td>${a.versionNo == null ? '—' : 'v' + a.versionNo}</td>
    </tr>`).join('') || '<tr><td colspan="6" class="muted" style="text-align:center;padding:26px">暂无操作记录</td></tr>';
}

// ---------- 事件 ----------

function init() {
    document.querySelectorAll('.tabs [data-tab]').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));
    $('filterForm').addEventListener('submit', e => {
        e.preventDefault();
        $('pageError').textContent = '';
        render();
    });
    $('filterStatus').addEventListener('change', () => $('filterForm').requestSubmit());
    $('refresh').addEventListener('click', () => load().catch(fail));
    $('newWeight').addEventListener('click', () => openDrawer({type: $('filterType').value, tier: $('filterTier').value}));
    $('rows').addEventListener('click', e => {
        const b = e.target.closest('[data-act]');
        if (b) act(b.dataset.id, b.dataset.act).catch(fail);
    });
    // 抽屉
    $('drawerClose').addEventListener('click', closeDrawer);
    $('drawerCancel').addEventListener('click', closeDrawer);
    $('drawer').addEventListener('click', e => {
        if (e.target === $('drawer')) closeDrawer();
    });
    $('drawerSave').addEventListener('click', saveDraft);
    ['w1', 'w2', 'w3', 'w4'].forEach(id => $(id).addEventListener('input', recalc));
    $('editType').addEventListener('change', keyNote);
    $('editTier').addEventListener('input', keyNote);
    // 发布
    $('publishNo').addEventListener('click', () => $('publishBack').classList.remove('show'));
    $('publishYes').addEventListener('click', confirmPublish);
    $('publishBack').addEventListener('click', e => {
        if (e.target === $('publishBack')) $('publishBack').classList.remove('show');
    });
    document.addEventListener('keydown', e => {
        if (e.key !== 'Escape') return;
        $('publishBack').classList.remove('show');
        closeDrawer();
    });
    // 匹配模拟
    $('matchForm').addEventListener('submit', e => {
        e.preventDefault();
        runMatch().catch(e => {
            $('matchSteps').innerHTML = '';
            fail(e);
        });
    });
    ['exC7', 'exC5', 'exC8', 'exC9'].forEach(id => $(id).addEventListener('input', () => {
        if (state.match) renderExample();
    }));
    $('versionsRefresh').addEventListener('click', () => loadVersions().catch(fail));
    load().catch(fail);
}

document.addEventListener('DOMContentLoaded', init);
