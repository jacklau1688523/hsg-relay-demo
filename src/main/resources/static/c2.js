'use strict';
const c2$ = id => document.getElementById(id);
const c2Esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const c2Key = str => String(str || '');
const c2Api = async (path, opt = {}) => {
    const res = await fetch('/api/c2' + path, {
        ...opt,
        headers: {'Content-Type': 'application/json', ...(opt.headers || {})}
    });
    const data = await res.json().catch(() => ({error: '服务器未返回 JSON'}));
    if (!res.ok) throw new Error(data.error || data.message || '请求失败：' + res.status);
    return data;
};
const c2State = {providers: [], fields: []};
const c2Restrictions = {rows: []};
const c2RestrictionTypes = {
    BENEFICIARY_COUNTRY: '受益行 / 地址国家', DIRECTOR_UBO_NATIONALITY: '董事 / UBO 国籍',
    BENEFICIARY_REGION: '受益人地区', ORDERING_COUNTRY: '客户 / 发起方国家',
    INTERMEDIARY_COUNTRY: '中间行国家', CURRENCY: '扣账 / 交易币种', PURPOSE: '交易用途'
};
// 规则编辑器为 C2 设计的条件结构：根条件组 8 项、全部满足（AND），每项是若干「字段命中屏蔽 = false」叶子的规则组，叶子之间也是 AND。
// 页面顶部的「C2 校验顺序与配置来源」和模拟结果的「校验过程」共用这一份定义；code 对应接口返回的 groups[].code，叶子字段对应 leaves[].field。
const c2Logic = {AND: '全部满足 AND', SINGLE: '单项判断'};
const c2Groups = [
    {code: 'BENEFICIARY_COUNTRY', title: '受益方国家未被限制', logic: 'AND', reason: 'RESTRICTED_COUNTRY',
        leaves: [['beneficiaryBankCountry', '受益行国家命中屏蔽'], ['beneficiaryAddressCountry', '受益人地址国家命中屏蔽']]},
    {code: 'DIRECTOR_UBO_NATIONALITY', title: '董事 / UBO 国籍未被限制', logic: 'AND', reason: 'RESTRICTED_NATIONALITY',
        leaves: [['directorNationalities', '客户董事国籍命中屏蔽'], ['uboNationalities', '客户 UBO 国籍命中屏蔽']]},
    {code: 'BENEFICIARY_REGION', title: '受益人地区未被限制', logic: 'SINGLE', reason: 'RESTRICTED_REGION',
        leaves: [['beneficiaryRegion', '受益人地区命中屏蔽']]},
    {code: 'ORDERING_COUNTRY', title: '付款 / 发起方国家未被限制', logic: 'AND', reason: 'RESTRICTED_COUNTRY',
        leaves: [['clientCountry', '付款客户国家命中屏蔽'], ['orderingCountry', '发起方国家命中屏蔽']]},
    {code: 'INTERMEDIARY_COUNTRY', title: '中间行国家未被限制', logic: 'SINGLE', reason: 'RESTRICTED_COUNTRY',
        leaves: [['intermediaryCountry', '中间行国家命中屏蔽']]},
    {code: 'CURRENCY', title: '交易币种未被限制', logic: 'AND', reason: 'RESTRICTED_CURRENCY',
        leaves: [['debitCurrency', '扣账币种命中屏蔽'], ['paymentCurrency', '交易币种命中屏蔽']]},
    {code: 'PURPOSE', title: '交易用途未被限制', logic: 'SINGLE', reason: 'RESTRICTED_PURPOSE',
        leaves: [['purpose', '交易用途命中屏蔽']]},
    {code: 'REGULATORY', title: '监管路由要求已满足', logic: 'SINGLE', reason: 'REG_RULE_NOT_MET',
        leaves: [['providerCapabilities', '监管能力要求不满足']]}
];
const c2GlobalGroup = {code:'GLOBAL_CONDITION', title:'全局附加条件已满足', logic:'SINGLE', reason:'C2_CONDITION_NOT_MET', leaves:[['globalCondition','字段库条件不满足']]};
const c2Group = code => c2Groups.find(g => g.code === code) || (code === 'GLOBAL_CONDITION' ? c2GlobalGroup : {title: code, logic: 'SINGLE', leaves: []});

/**
 * 规则组内的叶子条件。AND 连接同组的两个叶子：说明面板的卡片较窄，AND 放在上一行末尾以免折行（joinAfter）；校验过程放在下一个叶子之前。
 * tail 生成每个叶子「等于 false」之后的内容（说明面板不带）。
 */
function c2LeafRows(group, tail, joinAfter = false) {
    const join = n => n > 0 && n < group.leaves.length ? '<span class="c2-join">AND</span>' : '';
    return group.leaves.map(([field, label], n) => `<div class="c2-leaf">${joinAfter ? '' : join(n)}<span>${c2Esc(label)}</span><span class="muted">等于</span><code>false</code>${tail ? tail(field) : ''}${joinAfter ? join(n + 1) : ''}</div>`).join('');
}

/** 「C2 校验顺序与配置来源」：按规则编辑器的条件结构逐组列出叶子条件、不满足时返回的原因码和配置来源。 */
// 规则组默认收起（只显示根条件组和最终判定），点「展开规则组」再看各组明细
function c2RenderStructure() {
    c2$('c2Structure').innerHTML = `<div class="c2-root">根条件组（${c2Groups.length} 项）<span class="badge pending">全部满足 AND</span></div>
 <div class="c2-groups collapsed" id="c2StructureGroups">${c2Groups.map((g, i) => `<div class="c2-group">
 <div class="c2-group-head"><span class="c2-order">${i + 1}</span><b>${c2Esc(g.title)}</b></div>
 <div class="c2-logic">${c2Esc(c2Logic[g.logic])}</div>
 ${c2LeafRows(g, null, true)}
 <div class="c2-group-foot"><span class="muted">不满足 →</span><code>${c2Esc(g.reason)}</code></div>
 <div class="c2-source">${g.code === 'GLOBAL_CONDITION' ? '<a href="#c2ConditionPanel">字段库条件 ↑</a>在本页维护' : g.code === 'REGULATORY'
     ? '<a href="/c2-regulatory.html">监管路由规则 ↗</a>能力标签在合作方编辑页维护'
     : `<a href="/providers.html">合作方管理 ↗</a>${c2Esc(c2RestrictionTypes[g.code])} · 在合作方详情维护`}</div>
</div>`).join('')}</div>
 <div class="c2-final"><b>最终判定</b>　全部字段均为 false → C2 PASS；任意字段为 true → C2 FAIL，记录全部命中原因。</div>`;
    // 「合作方限制 ↓」：滚到下方的限制查询，并按该规则组的限制类型筛选
    c2$('c2Structure').querySelectorAll('a[data-c2-type]').forEach(a => a.onclick = event => {
        event.preventDefault();
        c2$('c2RestrictionType').value = a.dataset.c2Type;
        c2RenderRestrictions();
        c2$('c2RestrictionPanel').scrollIntoView({behavior: 'smooth', block: 'start'});
    });
}

function c2ToggleStructure() {
    const collapsed = c2$('c2StructureGroups').classList.toggle('collapsed');
    c2$('c2StructureToggle').textContent = collapsed ? '展开规则组' : '收起规则组';
}

function c2RenderRestrictions() {
    const provider=c2$('c2RestrictionProvider').value, type=c2$('c2RestrictionType').value, status=c2$('c2RestrictionStatus').value;
    const rows=c2Restrictions.rows.filter(r=>(!provider||r.providerCode===provider)&&(!type||r.restrictionType===type)&&(!status||String(r.enabled)===status));
    c2$('c2RestrictionRows').innerHTML=rows.map(r=>`<tr><td>#${c2Esc(r.id)}</td><td>${c2Esc(r.providerCode)}</td><td>${c2Esc(c2RestrictionTypes[r.restrictionType]||r.restrictionType)}</td><td>${c2Esc(r.blockedValues)}</td><td>${c2Esc((r.effectiveFrom||'').replace('T',' ').slice(0,16))}</td><td>${c2Esc((r.effectiveTo||'—').replace('T',' ').slice(0,16))}</td><td>v${c2Esc(r.versionNo)}</td><td><span class="badge ${r.enabled?'pass':'pending'}">${r.enabled?'启用':'停用'}</span></td><td>${c2Esc(r.remark||'—')}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(r.providerCode)}&tab=restrictions">合作方详情 ↗</a></td></tr>`).join('')||'<tr><td colspan="10">暂无符合条件的合作方限制。</td></tr>';
}
async function c2LoadRestrictions() {
    c2$('c2RestrictionError').textContent='';
    try {
        const [providers, rows]=await Promise.all([fetch('/api/providers').then(r=>r.json()),c2Api('/restrictions')]);
        const selectedProvider=c2$('c2RestrictionProvider').value,selectedType=c2$('c2RestrictionType').value;
        c2Restrictions.rows=rows;
        c2$('c2RestrictionProvider').innerHTML='<option value="">全部合作方</option>'+providers.map(p=>`<option value="${c2Esc(p.code)}">${c2Esc(p.code)} · ${c2Esc(p.name)}</option>`).join('');
        c2$('c2RestrictionType').innerHTML='<option value="">全部类型</option>'+Object.entries(c2RestrictionTypes).map(([key,label])=>`<option value="${c2Esc(key)}">${c2Esc(label)}</option>`).join('');
        c2$('c2RestrictionProvider').value=selectedProvider;c2$('c2RestrictionType').value=selectedType;
        c2RenderRestrictions();
    } catch(e) { c2$('c2RestrictionError').textContent=e.message; }
}
for(const id of ['c2RestrictionProvider','c2RestrictionType','c2RestrictionStatus'])c2$(id).onchange=c2RenderRestrictions;
c2$('c2RestrictionRefresh').onclick=c2LoadRestrictions;
// 模拟面板有专门输入项的九个交易要素；字段库里其余已启用的 C2 字段由 c2RenderVariableInputs 自动生成取值控件
const c2FixedFields = ['paymentCurrency', 'debitCurrency', 'beneficiaryBankCountry', 'beneficiaryAddressCountry', 'beneficiaryRegion', 'orderingCountry', 'clientCountry', 'intermediaryCountry', 'purpose'];

function c2Field(code) {
    return c2State.fields.find(f => f.fieldCode === code) || c2State.fields[0];
}

async function c2SyncSimCapabilities() {
    try {
        const tags = await c2Api('/providers/' + encodeURIComponent(c2$('c2SimProvider').value) + '/capabilities');
        c2$('c2SimTags').textContent = tags.join(', ') || '无';
    } catch (e) {
        c2$('c2SimTags').textContent = e.message;
    }
}

async function c2Simulate() {
    const keys = {
        paymentCurrency: 'c2SimPaymentCurrency',
        debitCurrency: 'c2SimDebitCurrency',
        beneficiaryBankCountry: 'c2SimBeneficiaryBankCountry',
        beneficiaryAddressCountry: 'c2SimBeneficiaryAddressCountry',
        beneficiaryRegion: 'c2SimBeneficiaryRegion',
        orderingCountry: 'c2SimOrderingCountry',
        clientCountry: 'c2SimClientCountry',
        intermediaryCountry: 'c2SimIntermediaryCountry',
        purpose: 'c2SimPurpose'
    };
    // 董事、UBO 国籍分别提交，校验过程才能给出「客户董事国籍命中屏蔽」「客户 UBO 国籍命中屏蔽」各自的结果；合并字段照旧提交
    const directors = c2$('c2SimDirectorNationalities').value.split(',').map(x => x.trim()).filter(Boolean);
    const ubos = c2$('c2SimUboNationalities').value.split(',').map(x => x.trim()).filter(Boolean);
    const req = {
        providerCode: c2$('c2SimProvider').value,
        directorUboNationalities: [...directors, ...ubos],
        directorNationalities: directors,
        uboNationalities: ubos,
        variables: {}
    };
    for (const [key, id] of Object.entries(keys)) req[key] = c2$(id).value || null;
    // 附加触发条件字段的取值：选「（不提供）」或留空的不提交，该字段按「本次没有取值」处理
    for (const field of c2State.fields.filter(f => !c2FixedFields.includes(f.fieldCode))) {
        const value = c2$('c2Var_' + field.fieldCode)?.value.trim();
        if (value) req.variables[field.fieldCode] = value;
    }
    c2$('c2SimResult').innerHTML = '<div class="notice">模拟计算中...</div>';
    try {
        const res = await c2Api('/simulate', {method: 'POST', body: JSON.stringify(req)});
        let html = `<div class="result ${res.passed ? '' : 'error'}">${res.reasonCode === 'SKIPPED' ? '↷ C2 已跳过' : res.passed ? '✓ C2 校验通过' : '✕ C2 校验失败'}</div>
 <p class="caps">返回原因码：<b>${c2Esc(res.reasonCode)}</b></p><p class="muted">${c2Esc(res.note)}</p>${c2RenderChecks(res)}`;
        if (res.hits.length) {
            html += '<h2>命中限制（按 C2 序号排序）</h2>';
            html += res.hits.map(h => `<div class="trace"><span class="badge fail">${c2Esc(h.reasonCode)}</span>
 <div style="margin-top:5px"><b>${c2Esc(h.ruleId)}</b> · ${c2Esc(h.field)}</div><div class="muted">${c2Esc(h.detail)}</div></div>`).join('');
        }
        if (res.regulatoryChecks.length) {
            html += '<h2 style="margin:15px 0 8px">监管规则执行过程</h2>';
            html += res.regulatoryChecks.map(r => {
                // 附加条件编译出了表达式却未触发、也没有缺值 / 失效说明：说明范围已命中，是附加条件本身未命中
                const miss = r.triggerExpression && !r.triggered && !r.note
                    ? '交易落在规则范围内，但附加触发条件未命中'
                    : `条件未命中（本次交易币种 / 受益行国家：${c2Esc(req.paymentCurrency)} / ${c2Esc(req.beneficiaryBankCountry)}）`;
                return `<div class="trace"><b>${c2Esc(r.ruleId)}</b>
 <span class="badge ${r.triggered ? 'pending' : 'pass'}">${r.triggered ? '触发' : '未触发'}</span>
 <div class="muted">${r.triggered ? '缺失标签：' + (r.missingTags.join(', ') || '无') : miss}</div>
 <div class="code" style="margin-top:6px">${c2Esc(r.condition)}</div>${r.triggerCondition ? `<div class="code" style="margin-top:4px">附加触发条件：${c2Esc(r.triggerCondition)}${r.triggerExpression ? `\n表达式：${c2Esc(r.triggerExpression)}` : ''}</div>` : ''}${r.note ? `<div class="c2-note">${c2Esc(r.note)}</div>` : ''}${r.expression ? `<div class="muted" style="margin-top:4px">旧版条件树，按原表达式执行：${c2Esc(r.expression)}</div>` : ''}</div>`;
            }).join('');
        }
        c2$('c2SimResult').innerHTML = html;
    } catch (e) {
        c2$('c2SimResult').innerHTML = `<div class="result error">${c2Esc(e.message)}</div>`;
    }
}

// 校验过程里每个叶子旁注明的本次取值来自模拟表单；接口响应只返回是否命中，不含取值
const c2LeafInputs = {
    beneficiaryBankCountry: 'c2SimBeneficiaryBankCountry', beneficiaryAddressCountry: 'c2SimBeneficiaryAddressCountry',
    beneficiaryRegion: 'c2SimBeneficiaryRegion', clientCountry: 'c2SimClientCountry', orderingCountry: 'c2SimOrderingCountry',
    intermediaryCountry: 'c2SimIntermediaryCountry', debitCurrency: 'c2SimDebitCurrency', paymentCurrency: 'c2SimPaymentCurrency',
    purpose: 'c2SimPurpose', directorNationalities: 'c2SimDirectorNationalities', uboNationalities: 'c2SimUboNationalities'
};

function c2LeafNote(leaf, res) {
    if (leaf.field === 'globalCondition') return leaf.hit ? '附加条件未满足或配置失效' : '附加条件已满足或尚未配置';
    if (leaf.field === 'providerCapabilities') {
        const unmet = res.regulatoryChecks.filter(c => leaf.ruleIds.includes(c.ruleId));
        return unmet.length ? unmet.map(c => `${c.ruleId} 缺少能力标签：${c2Esc(c.missingTags.join(', '))}`).join('；')
            : '合作方能力标签：' + c2Esc(res.providerCapabilities.join(', ') || '无');
    }
    if (leaf.hit) return '命中限制记录：' + c2Esc(leaf.ruleIds.join('、'));
    const value = c2$(c2LeafInputs[leaf.field]).value.trim();
    return value ? '本次取值：' + c2Esc(value) : '本次未填写';
}

/** 「校验过程」：接口按规则组返回判断结果（groups），逐组给出 PASS / FAIL，以及每个叶子本次的实际取值（期望均为 false）。 */
function c2RenderChecks(res) {
    const groups = res.groups || [];
    if (!groups.length) return '';
    const failed = groups.filter(g => !g.passed);
    const rows = groups.map(g => {
        const meta = c2Group(g.code);
        const actual = field => {
            const leaf = g.leaves.find(x => x.field === field) || {};
            return `<span class="c2-join">→</span><code class="actual ${leaf.hit ? 'hit' : ''}">实际 ${leaf.hit ? 'true' : 'false'}</code><span class="muted">${c2LeafNote(leaf, res)}</span>`;
        };
        return `<div class="c2-check ${g.passed ? '' : 'fail'}">
 <div class="c2-check-head"><span class="badge ${g.passed ? 'pass' : 'fail'}">${g.passed ? 'PASS' : 'FAIL'}</span><b>${g.order}. ${c2Esc(meta.title)}</b>${g.passed ? '' : `<span class="muted">不满足 →</span><code>${c2Esc(g.reasonCode)}</code>`}<span class="c2-logic">${c2Esc(c2Logic[meta.logic])}</span></div>
 ${c2LeafRows(meta, actual)}
</div>`;
    }).join('');
    const reasons = [...new Set(res.hits.map(h => h.reasonCode))].join('、');
    return `<h2 style="margin:15px 0 4px">校验过程</h2><div class="muted" style="margin-bottom:8px">根条件组（${groups.length} 项）· 全部满足 AND</div>${rows}
 <div class="c2-final" style="margin:10px 0 0"><b>最终判定</b>　${res.passed ? '全部字段均为 false → C2 PASS'
        : `第 ${failed.map(g => g.order).join('、')} 项存在为 true 的字段 → C2 FAIL，记录全部命中原因：${c2Esc(reasons)}`}</div>`;
}

function c2FillSelect(id, fieldCode, defaultValue, optional = false) {
    const field = c2Field(fieldCode);
    const options = (optional ? '<option value="">— 不填写 —</option>' : '') + field.options.map(v => `<option value="${c2Esc(v)}" ${v === defaultValue ? 'selected' : ''}>${c2Esc(v)}</option>`).join('');
    c2$(id).innerHTML = options;
    c2$(id).value = defaultValue || '';
}

/** 监管规则可按任意 ISO 国家配置；受益行国家在字段库可选值之外补齐其余 ISO 代码，这类规则才能在这里模拟。 */
function c2AddIsoCountries(id, countries) {
    const select = c2$(id), known = new Set(Array.from(select.options, option => option.value.toUpperCase()));
    select.insertAdjacentHTML('beforeend', countries.filter(x => x.code !== 'ALL' && !known.has(x.code))
        .map(x => `<option value="${c2Esc(x.code)}">${c2Esc(x.name ? `${x.code} · ${x.name}` : x.code)}</option>`).join(''));
}

/** 模拟面板为字段库里其余已启用的 C2 字段（九个固定交易要素之外）自动生成取值控件；保留仍有效的已填取值。 */
function c2RenderVariableInputs() {
    const extra = c2State.fields.filter(f => !c2FixedFields.includes(f.fieldCode));
    const kept = Object.fromEntries(extra.map(f => [f.fieldCode, c2$('c2Var_' + f.fieldCode)?.value.trim() || '']));
    c2$('c2SimVariables').innerHTML = extra.length ? '<div class="c2-subhead">附加触发条件字段（按字段库生成）</div><div class="layout2">' + extra.map(f => {
        const id = 'c2Var_' + f.fieldCode, label = `${c2Esc(f.fieldName)} <span class="muted">${c2Esc(f.fieldCode)}</span>`;
        const blank = '<option value="">（不提供）</option>';
        const control = f.dataType === 'BOOLEAN'
            ? `<select id="${id}" aria-label="${c2Esc(f.fieldName)}">${blank}<option value="true">true</option><option value="false">false</option></select>`
            : ['DECIMAL', 'INTEGER'].includes(f.dataType)
                ? `<input id="${id}" type="number" step="any" placeholder="（不提供）" aria-label="${c2Esc(f.fieldName)}">`
                : f.options.length
                    ? `<select id="${id}" aria-label="${c2Esc(f.fieldName)}">${blank}${f.options.map(v => `<option value="${c2Esc(v)}">${c2Esc(v)}</option>`).join('')}</select>`
                    : `<input id="${id}" placeholder="（不提供）" aria-label="${c2Esc(f.fieldName)}">`;
        return `<div class="field"><label>${label}</label>${control}</div>`;
    }).join('') + '</div>' : '';
    // 重绘后恢复之前填的取值；该值已不是可选项时不恢复
    for (const f of extra) {
        const input = c2$('c2Var_' + f.fieldCode), value = kept[f.fieldCode];
        if (value && (input.tagName !== 'SELECT' || Array.from(input.options, o => o.value).includes(value))) input.value = value;
    }
}

async function c2Init() {
    try {
        const [providers, fields, options] = await Promise.all([fetch('/api/providers').then(r => r.json()), c2Api('/fields'), c2Api('/regulatory-rules/options').catch(() => null)]);
        c2State.providers = providers;
        c2State.fields = fields;
        if (!fields.length) throw new Error('C2 字段库为空');
        c2$('c2SimProvider').innerHTML = providers.map(p => `<option value="${c2Esc(p.code)}">${c2Esc(p.code)} · ${c2Esc(p.name)}</option>`).join('');
        c2$('c2SimProvider').value = 'PROV-HO';
        c2FillSelect('c2SimPaymentCurrency', 'paymentCurrency', 'CNY');
        c2FillSelect('c2SimDebitCurrency', 'debitCurrency', 'CNY', true);
        c2FillSelect('c2SimBeneficiaryBankCountry', 'beneficiaryBankCountry', 'CN');
        c2AddIsoCountries('c2SimBeneficiaryBankCountry', options?.countries || []);
        c2FillSelect('c2SimBeneficiaryAddressCountry', 'beneficiaryAddressCountry', 'CN', true);
        c2FillSelect('c2SimBeneficiaryRegion', 'beneficiaryRegion', '', true);
        c2FillSelect('c2SimOrderingCountry', 'orderingCountry', 'HK', true);
        c2FillSelect('c2SimClientCountry', 'clientCountry', '', true);
        c2FillSelect('c2SimIntermediaryCountry', 'intermediaryCountry', '', true);
        c2FillSelect('c2SimPurpose', 'purpose', '', true);
        c2RenderVariableInputs();
        await c2SyncSimCapabilities();
    } catch (e) {
        c2$('c2SimResult').innerHTML = `<div class="result error">页面初始化失败：${c2Esc(e.message)}</div>`;
    }
}

c2RenderStructure();
c2$('c2StructureToggle').onclick = c2ToggleStructure;
c2Init();
c2LoadRestrictions();
