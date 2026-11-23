'use strict';
// 合作方管理列表（/providers.html）、新增 / 编辑（/provider-edit.html）与详情（/provider-detail.html）三个页面共用：
// 接口封装、字典，以及上线准备检查 / 准入复核的展示规则。须在页面脚本之前引入。
const $ = id => document.getElementById(id), esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));

async function api(path, options = {}) {
    const r = await fetch('/api' + path, {...options, headers: {'Content-Type': 'application/json'}});
    const data = await r.json();
    if (!r.ok) throw Error(data.error || data.message || `HTTP ${r.status}`);
    return data;
}

const types = {
    TIER1_BANK: '一级银行',
    OTHER_BANK: '其他银行',
    HEAD_OFFICE: '总行',
    FINTECH: '金融科技',
    NETWORK: '支付网络'
};
// 旧演示数据的 PAYMENT_NETWORK 统一按 NETWORK 展示
const type = p => p.providerType === 'PAYMENT_NETWORK' ? 'NETWORK' : p.providerType;

// 下拉选项：值 · 文案
function options(all, selected) {
    return Object.entries(all).map(([v, label]) => `<option value="${esc(v)}" ${v === selected ? 'selected' : ''}>${esc(v)} · ${esc(label)}</option>`).join('');
}

function reviewLabel(status) {
    return {
        NOT_SET: '未设置复核日期',
        OVERDUE: '复核已逾期',
        DUE_SOON: '30天内到期',
        CURRENT: '复核日期正常'
    }[status] || status;
}

const restrictTypes = {
    BENEFICIARY_COUNTRY: ['受益行 / 地址国家', 'RESTRICTED_COUNTRY'],
    DIRECTOR_UBO_NATIONALITY: ['董事 / UBO 国籍', 'RESTRICTED_NATIONALITY'],
    BENEFICIARY_REGION: ['受益人地区', 'RESTRICTED_REGION'],
    ORDERING_COUNTRY: ['客户 / 发起方国家', 'RESTRICTED_COUNTRY'],
    INTERMEDIARY_COUNTRY: ['中间行国家', 'RESTRICTED_COUNTRY'],
    CURRENCY: ['扣账 / 交易币种', 'RESTRICTED_CURRENCY'],
    PURPOSE: ['交易用途', 'RESTRICTED_PURPOSE']
};

// 上线准备检查清单：编辑页按表单实时计算，详情页直接用接口返回的 checks
function renderReadiness(checks) {
    $('readiness').innerHTML = checks.map(c => `<div class="checkrow"><b class="${c.passed ? 'good' : 'err'}">${c.passed ? '✓' : '○'} ${esc(c.label)}</b><small>${esc(c.detail)}</small></div>`).join('');
}

// 准入复核提示：按香港日期计算，30 天内为临期，已过期为逾期
function reviewText(date, owner) {
    let text = '未设置准入复核日期。';
    if (date) {
        const today = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Hong_Kong'}).format(new Date());
        const days = Math.round((Date.parse(date + 'T00:00:00Z') - Date.parse(today + 'T00:00:00Z')) / 86400000);
        text = days < 0 ? `复核已逾期 ${-days} 天。` : days <= 30 ? `准入复核将在 ${days} 天后到期。` : `下次准入复核：${date}。`;
    }
    const name = String(owner ?? '').trim();
    return text + (name ? ` 负责人：${name}` : ' 请设置对接负责人。');
}

// ---- 浏览器「后退」时的数据新鲜度 ----
// 列表 / 详情页从往返缓存（bfcache）恢复时脚本不会重新执行，页面还是离开时的旧数据。
// 编辑页每次写入成功后记下时间（markChanged）；列表 / 详情页恢复时若发现期间有过写入就整页刷新。
// 正常打开的页面数据本就是新的，不受影响，列表页的筛选条件也能保留。
const CHANGED_AT_KEY = 'providerChangedAt';

function markChanged() {
    try {
        sessionStorage.setItem(CHANGED_AT_KEY, String(Date.now()));
    } catch {
        // 存储不可用（如隐私模式限制）时只是失去「后退」自动刷新
    }
}

function reloadIfChangedSinceLoad() {
    const loadedAt = Date.now();
    window.addEventListener('pageshow', e => {
        let changedAt = 0;
        try {
            changedAt = Number(sessionStorage.getItem(CHANGED_AT_KEY)) || 0;
        } catch {
            // 同上
        }
        if (e.persisted && changedAt > loadedAt) location.reload();
    });
}
