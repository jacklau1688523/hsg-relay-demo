'use strict';
// 合作方管理（/providers.html）与合作方详情（/provider-detail.html）共用：
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

// 上线准备检查清单：编辑面板按表单实时计算，详情页直接用接口返回的 checks
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
