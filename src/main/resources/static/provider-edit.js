'use strict';
// 合作方新增 / 编辑：/provider-edit.html 为新增，/provider-edit.html?code=合作方编码[&tab=restrictions] 为编辑。
// 列表在 /providers.html，只读详情在 /provider-detail.html；共用的接口、字典和展示规则在 provider-common.js（须先引入）。
const state = {tags: [], code: null, version: null, checks: [], request: 0};
const params = new URLSearchParams(location.search);
const code = params.get('code');
const TABS = ['base', 'restrictions'];

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
    ++state.request;
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

async function activate(tab) {
    document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('sel', b.dataset.tab === tab));
    for (const id of TABS) $(id + 'View').classList.toggle('hidden', id !== tab);
    if (tab === 'base') return;
    const container = $('restrictionsView');
    if (!state.code) {
        container.innerHTML = '<div class="notice">保存合作方后可配置合作方限制。关联配置和变更记录在合作方详情页查看。</div>';
        return;
    }
    await loadRestrictions(state.code, ++state.request);
}

// ---- 合作方限制（制裁及监管屏蔽）：按合作方维护，走 C2 接口，保存后回到列表并定位到刚保存的记录 ----
let restrictEditing = null;

function nowLocal() {
    return new Date().toLocaleString('sv-SE', {timeZone: 'Asia/Hong_Kong', hour12: false}).replace(' ', 'T').slice(0, 16);
}

async function loadRestrictions(code, token, selectId, goodMsg) {
    const box = $('restrictionsView');
    box.innerHTML = '<p class="muted">正在加载…</p>';
    try {
        const all = await api('/c2/restrictions');
        if (token !== state.request || code !== state.code) return;
        const rows = all.filter(r => r.providerCode === code);
        restrictEditing = null;
        box.innerHTML = `<div class="panel"><div class="rulebar"><div class="ptitle" style="margin:0">${esc(code)} · 合作方限制（制裁及监管屏蔽）· ${rows.length} 条</div><button class="btn" onclick="restrictNew()">＋ 新增限制</button></div>
  <div class="tablewrap"><table><thead><tr><th>ID</th><th>限制类型</th><th>屏蔽值</th><th>生效时间</th><th>失效时间</th><th>版本</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(r => `<tr><td>#${r.id}</td><td title="${esc(r.restrictionType)}">${esc(restrictTypes[r.restrictionType]?.[0] || r.restrictionType)}</td><td>${esc(r.blockedValues)}</td><td>${esc((r.effectiveFrom || '').replace('T', ' ').slice(0, 16))}</td><td>${esc((r.effectiveTo || '—').replace('T', ' ').slice(0, 16))}</td><td>v${r.versionNo}</td><td>${r.enabled ? '启用' : '停用'}</td><td><a onclick="restrictEditRow(${r.id})">编辑</a><a onclick="restrictToggle(${r.id})">${r.enabled ? '停用' : '启用'}</a></td></tr>`).join('') || '<tr><td colspan="8">该合作方暂无限制记录。</td></tr>'}</tbody></table></div>
  <div class="notice">合作方限制按合作方独立配置，C2 校验实时读取：受益行国家/地址国家、客户国家/发起方国家、扣账币种/交易币种分别采用 OR 匹配；董事及 UBO 国籍遍历全部值；中间行为空时不触发该项。</div>${goodMsg ? `<div class="good">${esc(goodMsg)}</div>` : ''}</div>
  <div class="panel" id="restrictFormPanel"></div>`;
        if (selectId != null) restrictFill(selectId, all); else restrictForm(null);
    } catch (e) {
        if (token === state.request) box.innerHTML = `<p class="err">${esc(e.message)}</p>`;
    }
}

function restrictNew() {
    restrictForm(null);
}

async function restrictEditRow(id) {
    try {
        const all = await api('/c2/restrictions');
        restrictFill(id, all);
    } catch (e) {
        alert(e.message);
    }
}

function restrictFill(id, all) {
    const r = all.find(x => x.id === id);
    if (r) restrictForm(r);
}

function restrictForm(r) {
    const f = $('restrictFormPanel');
    if (!f) return;
    restrictEditing = r ? {id: r.id, versionNo: r.versionNo} : null;
    f.innerHTML = `<h2 style="margin:0 0 12px">${r ? '编辑合作方限制 · #' + r.id : '新增合作方限制 · ' + esc(state.code)}</h2>
  <div class="layout2">
   <div class="field"><label>限制类型 *</label><select id="rType">${Object.entries(restrictTypes).map(([v, [label, reason]]) => `<option value="${v}" ${r && r.restrictionType === v ? 'selected' : ''}>${esc(label)}（${reason}）</option>`).join('')}</select></div>
   <div class="field"><label>屏蔽编码（逗号分隔） *</label><input id="rValues" value="${r ? esc(r.blockedValues) : ''}" placeholder="KG,RU"></div>
   <div class="field"><label>启用状态</label><select id="rEnabled"><option value="true" ${!r || r.enabled ? 'selected' : ''}>启用</option><option value="false" ${r && !r.enabled ? 'selected' : ''}>停用</option></select></div>
   <div class="field"><label>生效时间（HKT） *</label><input type="datetime-local" id="rFrom" value="${r ? (r.effectiveFrom || '').slice(0, 16) : nowLocal()}"></div>
   <div class="field"><label>失效时间（HKT）</label><input type="datetime-local" id="rTo" value="${r && (r.effectiveTo || '').slice(0, 16) || ''}"></div>
   <div class="field"><label>备注</label><input id="rRemark" value="${r ? esc(r.remark || '') : ''}"></div>
  </div>
  <div class="err" id="rError"></div><div class="good" id="rGood"></div>
  <div class="actions" style="margin-top:12px"><button class="btn" onclick="restrictSave()">保存合作方限制</button></div>`;
}

async function restrictSave() {
    if (!$('rValues').value.trim()) {
        $('rError').textContent = '屏蔽编码不能为空';
        return;
    }
    $('rError').textContent = '';
    $('rGood').textContent = '';
    try {
        const req = {
            id: restrictEditing?.id ?? null, versionNo: restrictEditing?.versionNo ?? null, providerCode: state.code,
            restrictionType: $('rType').value, blockedValues: $('rValues').value, enabled: $('rEnabled').value === 'true',
            effectiveFrom: $('rFrom').value || null, effectiveTo: $('rTo').value || null, remark: $('rRemark').value || null
        };
        const saved = await api('/c2/restrictions', {method: 'POST', body: JSON.stringify(req)});
        markChanged();
        await loadRestrictions(state.code, ++state.request, saved.id, `保存成功 · #${saved.id} · v${saved.versionNo}`);
    } catch (e) {
        $('rError').textContent = e.message;
    }
}

async function restrictToggle(id) {
    if (!confirm('确定切换限制记录 #' + id + '？')) return;
    try {
        await api(`/c2/restrictions/${id}/toggle`, {method: 'POST'});
        markChanged();
        await loadRestrictions(state.code, ++state.request);
    } catch (e) {
        alert(e.message);
    }
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
        const tab = params.get('tab');
        await activate(view && TABS.includes(tab) ? tab : 'base');
    } catch (e) {
        $('loading').hidden = true;
        $('editor').classList.add('hidden');
        $('pageError').textContent = e.message;
    }
}

document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => activate(b.dataset.tab));
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
        await activate('base');
        $('formGood').textContent = `已保存 ${saved.provider.code}，版本 ${saved.provider.version}。状态、资金开关、通道及优先级在下次路由试算生效。`;
    } catch (e) {
        $('formError').textContent = e.message;
    } finally {
        $('save').disabled = false;
    }
};

show();
