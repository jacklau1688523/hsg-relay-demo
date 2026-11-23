'use strict';
// 合作方管理：列表与筛选。新增 / 编辑在独立页面 /provider-edit.html，只读详情在 /provider-detail.html；
// 共用的接口、字典和展示规则在 provider-common.js（须先引入）。
const state = {views: []};

function render() {
    const views = state.views.filter(({provider: p}) => (!$('search').value || [p.code, p.name, p.bic].some(v => String(v || '').toLowerCase().includes($('search').value.trim().toLowerCase())))
        && (!$('filterType').value || type(p) === $('filterType').value) && (!$('filterStatus').value || p.status === $('filterStatus').value));
    $('rows').innerHTML = views.map(v => {
        const p = v.provider, code = encodeURIComponent(p.code);
        return `<tr><td><b>${esc(p.code)}</b><br>${esc(p.name)}</td><td>${esc(types[type(p)] || p.providerType)}</td><td><span class="badge ${p.status === 'LIVE' ? 'pass' : 'pending'}">${esc(p.status)}</span></td><td>${esc(p.channel)}<br>${esc(p.bic || '未设置')}</td><td>${p.fundingCheckEnabled ? 'ON' : 'OFF'}</td><td>${v.capabilityTags.map(esc).join('<br>') || '—'}</td><td>${esc(p.priority)}</td><td>${esc(p.relationshipOwner || '未设置')}<br>${esc(p.appetiteReviewDate || '未设置')}<br><span class="${v.reviewStatus === 'OVERDUE' ? 'err' : v.reviewStatus === 'DUE_SOON' ? 'pending' : 'muted'}">${esc(reviewLabel(v.reviewStatus))}</span></td><td><span class="badge ${v.ready ? 'pass' : 'pending'}">${v.ready ? '已齐备' : '待完善'}</span></td><td class="nowrap"><a class="btn secondary tiny" href="/provider-detail.html?code=${code}">详情</a><a class="btn secondary tiny" href="/provider-edit.html?code=${code}">编辑</a></td></tr>`;
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
}

$('refresh').onclick = () => reload().catch(e => $('pageError').textContent = e.message);
$('query').onclick = render;
$('search').oninput = render;
$('filterType').onchange = render;
$('filterStatus').onchange = render;
reloadIfChangedSinceLoad();

(async () => {
    try {
        $('filterType').innerHTML = '<option value="">全部类型</option>' + options(types);
        await reload();
    } catch (e) {
        $('pageError').textContent = e.message;
    }
})();
