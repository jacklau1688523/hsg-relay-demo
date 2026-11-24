'use strict';
// 合作方新增 / 编辑：/provider-edit.html 为新增，/provider-edit.html?code=合作方编码 为编辑。
// 列表在 /providers.html，准入、MTD、客户排除、审批和合作方限制在 /provider-detail.html 维护；共用接口与字典在 provider-common.js。
const state = {tags: [], code: null, version: null, checks: []};
const params = new URLSearchParams(location.search);
const code = params.get('code');

// 新增时尚无接口返回的 checks：六项都按未通过展示，其中往来账号 / 总账编码 / BIC 随表单实时重算
function baseChecks() {
    const labels = ['业务覆盖', '手续费', '业务准入', 'HSG 往来账号', 'HSG 总账编码', 'SWIFT BIC'];
    const details = ['至少一条启用覆盖行', '至少一条手续费配置', '至少一条当前生效准入行', '状态为 LIVE 时必填', '状态为 LIVE 时必填', 'SWIFT 通道需8或11位 BIC'];
    return ['reach', 'fee', 'appetite', 'nostro', 'gl', 'bic'].map((key, i) => ({key, label: labels[i], passed: false, detail: details[i]}));
}

// 上线准备检查：编辑时以接口返回的 checks 为底，按表单当前值重算往来账号 / 总账编码 / BIC；同时同步必填项和准入复核提示
function readiness() {
    const checks = state.checks.map(c => ({...c}));
    for (const c of checks) {
        if (c.key === 'nostro') c.passed = !!$('nostroAccount').value.trim();
        if (c.key === 'gl') c.passed = !!$('glCode').value.trim();
        if (c.key === 'bic') c.passed = $('channel').value !== 'SWIFT' || /^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$/.test($('bic').value.trim());
    }
    renderReadiness(checks);
    $('bic').required = $('channel').value === 'SWIFT';
    $('nostroAccount').required = $('status').value === 'LIVE';
    $('glCode').required = $('status').value === 'LIVE';
    $('reviewInfo').textContent = reviewText($('appetiteReviewDate').value, $('relationshipOwner').value);
}

// 把接口返回的 View 填入表单；传空时是空白的新增表单
function fill(v = null) {
    const p = v?.provider || {code: '', name: '', providerType: 'OTHER_BANK', status: 'NOT_LIVE', channel: 'SWIFT', fundingCheckEnabled: true, priority: 99};
    state.code = v ? p.code : null;
    state.version = v ? p.version : null;
    state.checks = v ? v.checks : baseChecks();
    for (const key of ['code', 'name', 'status', 'channel', 'bic', 'nostroAccount', 'glCode', 'relationshipOwner', 'appetiteReviewDate']) $(key).value = p[key] ?? '';
    $('code').readOnly = !!v;
    $('providerType').value = type(p);
    $('priority').value = Math.min(99, Math.max(1, p.priority || 99));
    $('fundingCheckEnabled').value = String(p.fundingCheckEnabled);
    $('tags').innerHTML = state.tags.map(t => `<label class="tagcheck"><input name="tag" type="checkbox" value="${esc(t.tagCode)}" ${v?.capabilityTags.includes(t.tagCode) ? 'checked' : ''}>${esc(t.tagCode)} · ${esc(t.tagName)}</label>`).join('');
    $('formTitle').textContent = v ? '编辑合作方' : '新增合作方';
    $('version').textContent = v ? `合作方编码 ${p.code} · 数据版本 ${p.version}` : '编码保存后不可修改';
    document.title = v ? `HSG RELAY | 编辑合作方 · ${p.code}` : 'HSG RELAY | 新增合作方';
    $('detailLink').style.display = $('simulate').style.display = v ? '' : 'none';
    if (v) {
        $('detailLink').href = `/provider-detail.html?code=${encodeURIComponent(p.code)}`;
        $('simulate').href = `/routing.html?includeProvider=${encodeURIComponent(p.code)}`;
    }
    $('formError').textContent = '';
    $('formGood').textContent = '';
    const legacy = v && (p.priority > 99 || p.providerType === 'PAYMENT_NETWORK');
    $('legacyHint').style.display = legacy ? 'block' : 'none';
    $('legacyHint').textContent = '旧演示数据的支付网络类型和超范围优先级已在表单中按文档规范显示；保存后更新为 NETWORK / 1–99。';
    readiness();
}

// ---- 页面 ----
async function show() {
    const editing = !!code;
    $('formTitle').textContent = editing ? '编辑合作方' : '新增合作方';
    $('version').textContent = editing ? `合作方编码 ${code}` : '编码保存后不可修改';
    document.title = editing ? `HSG RELAY | 编辑合作方 · ${code}` : 'HSG RELAY | 新增合作方';
    $('pageError').textContent = '';
    $('loading').hidden = false;
    try {
        const [tags, view] = await Promise.all([
            api('/c2/capability-tags'),
            code ? api(`/provider-management/${encodeURIComponent(code)}`) : null
        ]);
        state.tags = tags;
        $('providerType').innerHTML = options(types, 'OTHER_BANK');
        $('loading').hidden = true;
        $('editor').classList.remove('hidden');
        fill(view);
    } catch (e) {
        $('loading').hidden = true;
        $('editor').classList.add('hidden');
        $('pageError').textContent = e.message;
    }
}

for (const key of ['nostroAccount', 'glCode', 'channel', 'status', 'bic', 'relationshipOwner', 'appetiteReviewDate']) $(key).addEventListener('input', readiness);

$('providerForm').onsubmit = async e => {
    e.preventDefault();
    $('save').disabled = true;
    $('formError').textContent = '';
    $('formGood').textContent = '';
    try {
        const inData = {};
        for (const key of ['code', 'name', 'providerType', 'status', 'channel', 'bic', 'nostroAccount', 'glCode', 'relationshipOwner', 'appetiteReviewDate']) inData[key] = $(key).value.trim() || null;
        inData.priority = Number($('priority').value);
        inData.fundingCheckEnabled = $('fundingCheckEnabled').value === 'true';
        inData.capabilityTags = [...document.querySelectorAll('[name="tag"]:checked')].map(c => c.value);
        inData.version = state.version;
        const creating = !state.code;
        const saved = await api(creating ? '/provider-management' : `/provider-management/${encodeURIComponent(state.code)}`, {method: creating ? 'POST' : 'PUT', body: JSON.stringify(inData)});
        markChanged();
        // 新增成功后地址栏换成编辑地址：刷新、后退再进来都是编辑这家合作方
        if (creating) history.replaceState(null, '', `/provider-edit.html?code=${encodeURIComponent(saved.provider.code)}`);
        fill(saved);
        $('formGood').textContent = `已保存 ${saved.provider.code}，版本 ${saved.provider.version}。状态、资金开关、通道及优先级在下次路由试算生效。`;
    } catch (e) {
        $('formError').textContent = e.message;
    } finally {
        $('save').disabled = false;
    }
};

show();
