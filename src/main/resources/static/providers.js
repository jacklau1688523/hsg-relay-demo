'use strict';
// 合作方管理：列表、筛选和删除。基础信息在 /provider-edit.html 编辑，配置信息在合作方详情的各页签维护。
// 共用的接口、字典和展示规则在 provider-common.js（须先引入）。
const state = {views: [], deleting: false};
// 确认框里逐项列出将被一并删除的配置：接口字段 · 名称 · 量词
const IMPACT_LABELS = [
    ['capabilities', '能力标签', '项'],
    ['appetites', 'C1 准入', '行'],
    ['restrictions', 'C2 合作方限制', '行'],
    ['usages', 'MTD 模拟数据', '行'],
    ['reaches', 'C3 业务覆盖', '行'],
    ['balances', 'C4 往来账余额', '行'],
    ['fees', 'C7 手续费', '行'],
    ['fxWindows', 'C10 外汇窗口', '行'],
    ['preferences', 'C8 偏好规则', '行'],
    ['agreements', 'C9 履约协议', '行']
];

function render() {
    const views = state.views.filter(({provider: p}) => (!$('search').value || [p.code, p.name, p.bic].some(v => String(v || '').toLowerCase().includes($('search').value.trim().toLowerCase())))
        && (!$('filterType').value || type(p) === $('filterType').value) && (!$('filterStatus').value || p.status === $('filterStatus').value));
    $('rows').innerHTML = views.map(v => {
        const p = v.provider, code = encodeURIComponent(p.code);
        return `<tr><td><b>${esc(p.code)}</b><br>${esc(p.name)}</td><td>${esc(types[type(p)] || p.providerType)}</td><td><span class="badge ${p.status === 'LIVE' ? 'pass' : 'pending'}">${esc(p.status)}</span></td><td>${esc(p.channel)}<br>${esc(p.bic || '未设置')}</td><td>${p.fundingCheckEnabled ? 'ON' : 'OFF'}</td><td>${v.capabilityTags.map(esc).join('<br>') || '—'}</td><td>${esc(p.priority)}</td><td>${esc(p.relationshipOwner || '未设置')}<br>${esc(p.appetiteReviewDate || '未设置')}<br><span class="${v.reviewStatus === 'OVERDUE' ? 'err' : v.reviewStatus === 'DUE_SOON' ? 'pending' : 'muted'}">${esc(reviewLabel(v.reviewStatus))}</span></td><td><span class="badge ${v.ready ? 'pass' : 'pending'}">${v.ready ? '已齐备' : '待完善'}</span></td><td class="nowrap"><a class="btn secondary tiny" href="/provider-detail.html?code=${code}">详情</a><a class="btn secondary tiny" href="/provider-edit.html?code=${code}">编辑基础信息</a><a class="btn secondary tiny" href="/provider-detail.html?code=${code}&amp;tab=appetites">编辑配置信息</a><button type="button" class="btn secondary tiny" data-delete="${code}">删除</button></td></tr>`;
    }).join('') || '<tr><td colspan="10">暂无符合条件的合作方</td></tr>';
    const metrics = [
        ['合作方总数', state.views.length],
        ['已上线 LIVE', state.views.filter(v => v.provider.status === 'LIVE').length],
        ['未上线 NOT_LIVE', state.views.filter(v => v.provider.status === 'NOT_LIVE').length],
        ['临期 / 逾期复核', state.views.filter(v => ['DUE_SOON', 'OVERDUE'].includes(v.reviewStatus)).length]
    ];
    $('metrics').innerHTML = metrics.map(([title, count]) => `<div class="metric"><span>${title}</span><b>${count}</b></div>`).join('');
}

async function reload() {
    state.views = await api('/provider-management');
    render();
    $('pageError').textContent = '';
    $('pageNote').textContent = '';
    markReloaded();
}

// 删除前先读取删除影响，把将被一并移除的配置逐项写进确认框；版本号取列表加载时的值，过期时服务端拒绝
async function deleteProvider(view) {
    const p = view.provider, path = `/provider-management/${encodeURIComponent(p.code)}`;
    try {
        const impact = await api(`${path}/impact`);
        const removed = IMPACT_LABELS.filter(([key]) => impact[key] > 0).map(([key, label, unit]) => `${label} ${impact[key]} ${unit}`);
        const cascade = removed.length ? `将一并删除：${removed.join('、')}。` : '';
        if (!confirm(`确定删除合作方 ${p.code}（${p.name}）？删除后不可恢复。${cascade}`)) return;
        await api(`${path}?version=${p.version}`, {method: 'DELETE'});
        markChanged();
        await reload();
        $('pageNote').textContent = `已删除合作方 ${p.code}。`;
    } catch (e) {
        // 多为列表已过期（版本变化）或该合作方已被删除：重新读取后保留原因
        await reload().catch(() => {
        });
        $('pageError').textContent = e.message;
    }
}

$('refresh').onclick = () => reload().catch(e => $('pageError').textContent = e.message);
$('query').onclick = render;
$('search').oninput = render;
$('filterType').onchange = render;
$('filterStatus').onchange = render;
$('rows').onclick = async e => {
    const button = e.target.closest('[data-delete]');
    // 确认框关闭后请求仍在进行时列表行还未刷新：忽略重复点击
    if (!button || state.deleting) return;
    state.deleting = true;
    try {
        const view = state.views.find(v => v.provider.code === decodeURIComponent(button.dataset.delete));
        if (view) await deleteProvider(view);
    } finally {
        state.deleting = false;
    }
};
const markReloaded = reloadIfChangedSinceLoad();

(async () => {
    try {
        $('filterType').innerHTML = '<option value="">全部类型</option>' + options(types);
        await reload();
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
