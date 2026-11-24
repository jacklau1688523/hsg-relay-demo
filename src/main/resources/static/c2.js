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
    const req = {
        providerCode: c2$('c2SimProvider').value,
        directorUboNationalities: c2$('c2SimNationalities').value.split(',').map(x => x.trim()).filter(Boolean),
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
        let html = `<div class="result ${res.passed ? '' : 'error'}">${res.passed ? '✓ C2 校验通过' : '✕ C2 校验失败'}</div>
 <p class="caps">返回原因码：<b>${c2Esc(res.reasonCode)}</b></p><p class="muted">${c2Esc(res.note)}</p>`;
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

c2Init();
