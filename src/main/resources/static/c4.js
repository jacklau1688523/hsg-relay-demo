'use strict';
const $=id=>document.getElementById(id),esc=value=>String(value??'—').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const state={currencies:[],switches:[],balanceRows:[],balanceRevision:0};
const VENUE={NONE:'NONE · 无需换汇',TREASURY:'TREASURY · 资金部换汇',PROVIDER:'PROVIDER · 合作方换汇'};
async function api(path,options={}){const response=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json'}});const body=await response.json();if(!response.ok)throw Error(body.error||body.message||response.statusText);return body;}
const fmt=(v,d=2)=>v==null||v===''?'—':Number(v).toLocaleString('en-US',{maximumFractionDigits:d});
const switchOf=code=>state.switches.find(x=>x.code===code);
function savedSwitchLabel(){const item=switchOf($('simProvider').value);$('simSwitch').options[0].textContent=`用合作方已保存的开关（当前 ${item?(item.fundingCheckEnabled?'ON':'OFF'):'—'}）`;}
function renderBalanceQuery(){
 const provider=$('balanceFilterProvider').value,currency=$('balanceFilterCurrency').value;
 const rows=state.balanceRows.filter(row=>(!provider||row.balance.provider===provider)&&(!currency||row.balance.currency===currency));
 $('balanceRowCount').textContent=`往来账余额与占用查询（${rows.length} 行）`;
 $('balanceRevision').textContent=`配置版本 ${state.balanceRevision}`;
 $('balanceRows').innerHTML=rows.map(({balance:b})=>{const sw=switchOf(b.provider),available=Number(b.balance)-Number(b.reserved);return `<tr><td><b>${esc(b.provider)}</b><br><span class="muted">${esc(sw?.name||'')}</span></td><td>${esc(b.currency)}</td><td>${esc(fmt(b.balance))}</td><td>${esc(fmt(b.reserved))}</td><td class="${available<0?'neg':''}">${esc(fmt(available))}</td><td>${sw?(sw.fundingCheckEnabled?'ON':'OFF'):'—'}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(b.provider)}&tab=balances">合作方详情 ↗</a></td></tr>`;}).join('')||'<tr><td colspan="7">暂无符合条件的余额行。</td></tr>';
}
function setBalanceOptions(select,values){const current=select.value;select.innerHTML='<option value="">全部</option>'+values.map(v=>`<option value="${esc(v)}">${esc(v)}</option>`).join('');select.value=values.includes(current)?current:'';}
async function refreshBalanceQuery(){const data=await api('/c4/balances');state.balanceRows=data.rows;state.balanceRevision=data.revision;state.switches=data.fundingSwitches;setBalanceOptions($('balanceFilterProvider'),[...new Set(data.rows.map(row=>row.balance.provider))].sort());setBalanceOptions($('balanceFilterCurrency'),[...new Set(data.rows.map(row=>row.balance.currency))].sort());renderBalanceQuery();savedSwitchLabel();}
$('balanceFilterProvider').onchange=renderBalanceQuery;
$('balanceFilterCurrency').onchange=renderBalanceQuery;
$('balanceQuery').onclick=renderBalanceQuery;
$('balanceRefresh').onclick=async()=>{try{await refreshBalanceQuery();$('pageError').textContent='';}catch(error){$('pageError').textContent=error.message;}};
$('simProvider').onchange = savedSwitchLabel;

function step(n, title, value, detail) {
    return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;
}

function venueBlock(v) {
    const c = v.calculation, on = c.enabled, ok = c.passed, provider = c.fxVenue === 'PROVIDER',
        acct = c.accountCurrency, avail = Number(c.available), enough = avail >= Number(c.required);
    const step3 = c.accountFound
        ? `${fmt(c.balance)} − ${fmt(c.reserved)} ＝ <b class="${avail < 0 ? 'neg' : ''}">${fmt(c.available)}</b> ${esc(acct)}`
        : `未配置 ${esc(acct)} 余额行，可用头寸按 <b class="neg">0</b> 计`;
    const compare = `可用头寸 ${fmt(c.available)} ${esc(acct)} ${enough ? '≥' : '&lt;'} 本笔等价金额 ${fmt(c.required)} ${esc(acct)}`;
    const step4 = on
        ? `${compare} → ${ok ? '<span class="ok">充足，PASS</span>' : '<span class="bad">不足，FUNDING_SHORTFALL</span>'}`
        : `${compare}（数值上${enough ? '充足' : '不足'}），但开关 OFF 不参与判定 → <span class="ok">PASS</span>`;
    return `<div class="channel ${ok ? '' : 'closed'}"><header><h3>${esc(VENUE[c.fxVenue] || c.fxVenue)}</h3><span class="${ok ? 'ok' : 'bad'}">${ok ? 'PASS' : 'FUNDING_SHORTFALL'}</span></header>
  ${step(1, '合作方资金校验开关', on ? '<b>ON</b> · 继续校验头寸' : '<b>OFF</b> · 直接通过', on ? '开关由合作方管理维护（BRD 9.1）；开启时逐笔校验往来账可用头寸。' : '后补头寸的合作方（如 Wise）设为 OFF：资金由资金部在合作方出款前补足，逻辑在 RELAY 模块之外；以下数值仅供参考，不参与判定。')}
  ${step(2, '账户币种判定', `外汇渠道 <span class="mono">${esc(c.fxVenue)}</span> → 校验 <b>${esc(acct)}</b> 往来账（交易币种 ${esc(c.paymentCurrency)} · 扣账币种 ${esc(c.debitCurrency)}）`, provider ? 'PROVIDER 渠道取扣账账户币种。' : 'NONE / TREASURY 渠道取交易币种。')}
  ${step(3, '可用头寸 ＝ 账户余额 − 未记账占用', step3, '实时读取 HSG：我行在合作方的往来账 nostro 余额，扣除尚未记账的已路由交易占用。')}
  ${step(4, '本笔等价金额与判定', step4, provider ? `等价金额＝交易金额 ${fmt(c.amount)} ${esc(c.paymentCurrency)} × HSG 当日汇率 ${fmt(c.rate, 6)}（1 ${esc(c.paymentCurrency)} ＝ ${fmt(c.rate, 6)} ${esc(acct)}）≈ ${fmt(c.required)} ${esc(acct)}。` : `账户币种即交易币种：等价金额＝交易金额 ${fmt(c.amount)} ${esc(acct)}，无需换算。`)}
  </div>`;
}

$('simForm').onsubmit = async e => {
    e.preventDefault();
    $('simulate').disabled = true;
    try {
        const req = {
            providerCode: $('simProvider').value,
            debitCurrency: $('simDebit').value,
            paymentCurrency: $('simPayment').value,
            amount: Number($('simAmount').value),
            fundingCheckEnabled: $('simSwitch').value === '' ? null : $('simSwitch').value === 'true'
        };
        const r = await api('/c4/simulate', {method: 'POST', body: JSON.stringify(req)});
        let verdict;
        if (r.status === 'NO_FX_VENUE') verdict = '<div class="verdict fail"><span>C4 结果</span><b>NO_FX_VENUE</b><small>没有任何外汇渠道配置该货币对窗口：全流程路由不生成候选，C4 不适用，返回 REFER NO_FX_VENUE</small></div>';
        else if (r.status !== 'PASS') verdict = '<div class="verdict fail"><span>C4 结果</span><b>FUNDING_SHORTFALL</b><small>全部渠道可用头寸不足，该合作方候选被剔除；全流程中全部候选仅因 C4 失败时返回 REFER FUNDING_SHORTFALL——运营审核环节，资金部确认补头寸后，运营可人工选用该合作方（示例 6b）</small></div>';
        else verdict = `<div class="verdict"><span>C4 结果</span><b>PASS</b><small>${r.fundingCheckEnabled ? '至少一个渠道可用头寸充足，候选保留进入 C6 / C10 过滤与后续打分' : '资金校验开关 OFF：直接通过，账户与头寸数值仅供参考（后补头寸的合作方，资金由资金部在出款前补足）'}</small></div>`;
        $('simResult').className = '';
        $('simResult').innerHTML = verdict + r.venues.map(venueBlock).join('') + `<div class="notice">${esc(r.note)} · 配置版本 ${r.revision}</div>`;
    } catch (err) {
        $('simResult').className = 'err';
        $('simResult').textContent = err.message;
    } finally {
        $('simulate').disabled = false;
    }
};
document.querySelectorAll('[data-sample]').forEach(b => b.onclick = () => {
    const set = (provider, debit, payment, amount, sw) => {
        $('simProvider').value = provider;
        savedSwitchLabel();
        $('simDebit').value = debit;
        $('simPayment').value = payment;
        $('simAmount').value = amount;
        $('simSwitch').value = sw;
    };
    const s = b.dataset.sample;
    if (s === 'pass') set('PROV-T1', 'USD', 'USD', 10000, '');
    else if (s === 'short') set('PROV-T1', 'USD', 'USD', 60000000, '');
    else if (s === 'treasury') set('PROV-T1', 'HKD', 'USD', 10000, '');
    else if (s === 'provider') set('PROV-W', 'USD', 'AUD', 78000000, 'true');
    else if (s === 'off') set('PROV-W', 'USD', 'AUD', 78000000, 'false');
    else set('PROV-T1', 'HKD', 'EUR', 10000, '');
});

(async()=>{try{const data=await api('/c4/balances');state.currencies=[...data.currencies].sort();state.switches=data.fundingSwitches;for(const id of ['simDebit','simPayment'])$(id).innerHTML=state.currencies.map(currency=>`<option value="${esc(currency)}">${esc(currency)}</option>`).join('');$('simProvider').innerHTML=state.switches.map(p=>`<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');$('simProvider').value='PROV-T1';$('simDebit').value='HKD';$('simPayment').value='USD';state.balanceRows=data.rows;state.balanceRevision=data.revision;setBalanceOptions($('balanceFilterProvider'),[...new Set(data.rows.map(row=>row.balance.provider))].sort());setBalanceOptions($('balanceFilterCurrency'),[...new Set(data.rows.map(row=>row.balance.currency))].sort());renderBalanceQuery();savedSwitchLabel();}catch(error){$('pageError').textContent=error.message;}})();
