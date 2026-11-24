'use strict';
// 监管路由规则列表（/c2-regulatory.html）与新增 / 编辑（/c2-regulatory-edit.html）两个页面共用：
// 接口封装、下拉字典、时间与有效期的展示规则，以及「后退」时的数据新鲜度处理。须在页面脚本之前引入。
const c2$ = id => document.getElementById(id);
const c2Esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));

async function c2Api(path, opt = {}) {
    const res = await fetch('/api/c2' + path, {
        ...opt,
        headers: {'Content-Type': 'application/json', ...(opt.headers || {})}
    });
    const data = await res.json().catch(() => ({error: '服务器未返回 JSON'}));
    if (!res.ok) throw new Error(data.error || data.message || '请求失败：' + res.status);
    return data;
}

// ---- 下拉字典：币种（字段库「交易币种」可选值）、受益行国家（ALL + ISO 3166-1）、能力标签 ----
const c2Dict = {currencies: [], countries: [], tags: []};

async function c2LoadDict() {
    const options = await c2Api('/regulatory-rules/options');
    Object.assign(c2Dict, {currencies: options.currencies || [], countries: options.countries || [], tags: options.tags || []});
}

function c2CountryLabel(code) {
    const country = c2Dict.countries.find(x => x.code === code);
    return country && country.name ? `${code} · ${country.name}` : code;
}

function c2TagName(code) {
    return c2Dict.tags.find(t => t.tagCode === String(code))?.tagName || String(code);
}

/** 能力标签按字典顺序展示；字典里已经没有的标签排在后面 */
function c2SortedTags(codes) {
    const order = c2Dict.tags.map(t => t.tagCode);
    return codes.map(String).slice().sort((a, b) => {
        const x = order.includes(a) ? order.indexOf(a) : order.length, y = order.includes(b) ? order.indexOf(b) : order.length;
        return x - y || a.localeCompare(b);
    });
}

// ---- 时间：页面按香港时间（HKT）录入和展示 ----
// 接口返回 ISO 形态（2026-01-01T00:00:00）；datetime-local 控件的取值是 2026-01-01T00:00，直接作为入参提交。
const c2Minute = iso => String(iso || '').slice(0, 16);
const c2ShowTime = iso => c2Minute(iso).replace('T', ' ');

/** 当前香港时间，取到分钟，形态同 datetime-local 的取值。 */
function c2NowHkt() {
    return new Date().toLocaleString('sv-SE', {timeZone: 'Asia/Hong_Kong', hourCycle: 'h23'}).replace(' ', 'T').slice(0, 16);
}

/**
 * 有效期状态，与引擎一致（启用、已到生效时间、且未到失效时间的规则才执行）：
 * PENDING 未到生效时间 / EXPIRED 已过失效时间 / ACTIVE 在有效期内。
 */
function c2Validity(rule, now = c2NowHkt()) {
    if (rule.effectiveFrom && c2Minute(rule.effectiveFrom) > now) return 'PENDING';
    if (rule.effectiveTo && c2Minute(rule.effectiveTo) <= now) return 'EXPIRED';
    return 'ACTIVE';
}

// ---- 浏览器「后退」时的数据新鲜度（同合作方管理） ----
// 列表页从往返缓存（bfcache）恢复时脚本不会重新执行，页面还是离开时的旧数据。
// 编辑页每次保存成功后、列表页启停 / 删除成功后记下时间；列表页恢复时若发现期间有过写入就整页刷新。
const C2_CHANGED_AT_KEY = 'c2RegulatoryChangedAt';

function c2MarkChanged() {
    try {
        sessionStorage.setItem(C2_CHANGED_AT_KEY, String(Date.now()));
    } catch {
        // 存储不可用（如隐私模式限制）时只是失去「后退」自动刷新
    }
}

// 返回 markReloaded()：列表页原地重新读取数据后调用，之后的「后退」恢复不再因这次读取之前的写入而整页刷新
function c2ReloadIfChangedSinceLoad() {
    let loadedAt = Date.now();
    window.addEventListener('pageshow', e => {
        let changedAt = 0;
        try {
            changedAt = Number(sessionStorage.getItem(C2_CHANGED_AT_KEY)) || 0;
        } catch {
            // 同上
        }
        if (e.persisted && changedAt > loadedAt) location.reload();
    });
    return () => {
        loadedAt = Date.now();
    };
}
