'use strict';
const $=id=>document.getElementById(id),esc=value=>String(value??'—').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const state={revision:0,rows:[],tab:'score'};   // tab = 当前显示的页签（速度评分规则为默认）
async function api(path,options={}){const response=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json'}});const body=await response.json();if(!response.ok)throw Error(body.error||body.message||response.statusText);return body;}
const options=(values,selected)=>values.map(value=>`<option value="${esc(value)}" ${value===selected?'selected':''}>${esc(value)}</option>`).join('');
function render(){const rows=state.rows.filter(({reach:r})=>(!$('filterProvider').value||r.provider===$('filterProvider').value)&&(!$('filterCurrency').value||r.currency===$('filterCurrency').value));$('rowCount').textContent=`覆盖行起息配置（共 ${rows.length} 条）`;$('rows').innerHTML=rows.map(({index,reach:r})=>`<tr><td>#${index+1}<br>${esc(r.provider)}</td><td>${esc(r.currency)} / ${esc(r.country)}<br>${esc(r.region)} · ${r.bics.length?'LIST':'ALL'}</td><td>${esc(r.cutoff)}<br>${esc(r.zone)}</td><td>+${r.offsetDays}</td><td>${r.holidays.length?`<details><summary>${r.holidays.length} 个节假日</summary>${r.holidays.map(day=>`<div>${esc(day)}</div>`).join('')}</details>`:'仅周末'}</td><td>${r.enabled===false?'覆盖已停用':'覆盖启用'}</td><td><a href="/provider-detail.html?code=${encodeURIComponent(r.provider)}&tab=schedules">合作方详情 ↗</a></td></tr>`).join('')||'<tr><td colspan="7">暂无覆盖行，请先在合作方详情的“业务覆盖”页签新增。</td></tr>';}
function apply(data){state.rows=data.rows;state.revision=data.revision;$('revision').textContent=`覆盖配置版本 ${state.revision}`;render();}
// 页签切换：四个页签各自独立显示，只做隐藏 / 显示，评分草稿、试算输入和试算结果都留在页面里，切换不会丢失。
// 只有从「截单与工作日日历」回到「速度评分规则」时，才把截单页签的试算交易带过去（同一笔交易对照）；其余页签之间切换不动表单。
const C5_TABS={score:'tabScore',history:'tabHistory',audits:'tabAudits',schedule:'tabSchedule'};
function c5ShowTab(name){
    if(!C5_TABS[name])return;
    const from=state.tab;state.tab=name;
    document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('sel',b.dataset.tab===name));
    Object.keys(C5_TABS).forEach(tab=>$(C5_TABS[tab]).classList.toggle('hidden',tab!==name));
    if(name==='score'&&from==='schedule'&&typeof c5ScoreTabShown==='function')c5ScoreTabShown();
    if(name==='history'&&typeof c5HistoryTabShown==='function')c5HistoryTabShown();
}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>c5ShowTab(b.dataset.tab));
// 评分卡：按当前生效的已发布版本渲染；没有已发布版本时明确提示，不展示写死的分数
function c5RenderScoreCards(published){
    const box=$('scoreCards');
    if(!published||!published.rule){box.innerHTML='<div class="scorecard draft"><span>速度评分规则</span><b style="font-size:17px">尚未发布</b><span>没有生效的评分版本，C5 不给出分数。请在「速度评分规则」页签发布。</span></div>';return;}
    // 全部是已发布的数据：一律实线卡片（虚线只用于「尚未发布」的占位），概览信息占满一行放在最上面
    const groups=(published.rule.groups||[]).filter(g=>g.enabled!==false);
    box.innerHTML=`<div class="scorecard wide" style="padding:10px 17px"><span>当前生效：已发布 v${published.version} · ${esc(published.rule.matchMode)} · 分数范围 ${published.rule.scoreRange.min}–${published.rule.scoreRange.max} · 首个命中的评分组给出分数，0 分也不剔除候选</span></div>`
        +groups.map(g=>`<div class="scorecard"><span>${esc(g.name)}</span><b>${g.result.score} 分</b><span>优先级 ${g.priority} · ${esc(g.groupCode)}</span></div>`).join('');
}
$('filterProvider').onchange=render;$('filterCurrency').onchange=render;$('query').onclick=render;
$('refresh').onclick=async()=>{try{apply(await api('/c5/schedules'));$('pageError').textContent='';}catch(error){$('pageError').textContent=error.message;}};
const step=(n,title,value,detail)=>`<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${esc(value)}</div><small>${esc(detail)}</small></div></div>`;
const C5_STATUS={SCORED:'已评分',NO_REACH:'C3 未匹配覆盖行',NOT_PUBLISHED:'评分规则未发布',NOT_MATCHED:'没有评分组命中',RULE_DISABLED:'评分规则已停用',ERROR:'评分出错'};
const rollReason={BEFORE_CUTOFF:'当前为工作日且严格早于截单，按今日计算。',AT_OR_AFTER_CUTOFF:'已到或超过截单，顺延至下一工作日。',NOT_BUSINESS_DAY:'当前为周末或节假日，顺延至下一工作日。'};
// 试算交易表单的取值。合作方等控件 ID = 前缀 + 名称（sim / scoreSim）；时间和请求日两个控件的 ID 没有前缀，显式传入
const simRequest=(prefix,atId,requestedId)=>({providerCode:$(prefix+'Provider').value,paymentCurrency:$(prefix+'Currency').value,beneficiaryBankCountry:$(prefix+'Country').value.trim().toUpperCase(),beneficiaryRegion:$(prefix+'Region').value.trim().toUpperCase()||null,beneficiaryBankBic:$(prefix+'Bic').value.trim().toUpperCase(),chargeOption:$(prefix+'Charge').value,requestedValueDate:$(requestedId).value||null,at:$(atId).value?$(atId).value+'+08:00':null});
// 两个页签共用的试算结果渲染。分数只来自评分规则结果：不是 SCORED 就显示「未评分」和原因，不用 0 或 100 代替。
// compact：速度评分规则页签右侧的窄栏，用「概览键值 + 评分组逐组结果」，全部取自接口返回和本次上送的试算交易（input）；
// 不传 compact 则按分步说明展示（截单与工作日日历页签，与改造前一致）。
const yesNo=v=>v===true?'是':v===false?'否':'—';
const kv=(label,value,note)=>`<dt>${label}</dt><dd>${value===null||value===undefined||value===''?'—':esc(value)}${note?` <span class="muted">${esc(note)}</span>`:''}</dd>`;
function c5RenderSimulation(r,requested,compact,input){
    if(!r.calculation)return `<div class="notice warn"><b>未评分 · ${esc(C5_STATUS[r.scoreStatus]||r.scoreStatus||'—')}</b><br>${esc(r.note)}</div>`;
    const c=r.calculation,reach=r.selectedReach,scored=r.scoreStatus==='SCORED'&&r.score!=null;
    const source=r.draft?'当前页面草稿（未发布）':r.replay?`历史版本 v${r.scoreRuleVersion}（回放）`:`已发布版本 v${r.scoreRuleVersion}`;
    const scoreLine=scored?`${r.score} 分`:'未评分';
    const verdict=g=>g.matched===true?'<span class="good">命中</span>':g.matched===false?'未命中':'未判断';
    const head=`<div class="score-result ${scored&&r.score===0?'zero':''}${compact?' compact':''}"><span>C5 速度评分 · ${esc(source)}</span><b>${esc(scoreLine)}</b><small>${scored?`命中评分组 ${esc(r.matchedScoreGroup)} · ${r.scoreRuleVersion==null?'草稿（无版本号）':'规则版本 v'+r.scoreRuleVersion}`:`${esc(C5_STATUS[r.scoreStatus]||'')} · ${esc(r.scoreMessage||'')}`}${compact?' · ':'<br>'}仅参与打分 · 不剔除候选</small></div>`;
    const reachLine=`命中覆盖行 #${r.selectedIndex+1} · ${esc(reach.provider)} / ${esc(reach.currency)} · 覆盖配置版本 ${r.revision} · C3 条件版本 ${r.c3ConditionVersion??'—'}`;
    if(compact)return head
    +`<div class="kvsec"><div class="kvtitle">交易与覆盖行</div><dl class="kv">${kv('合作方',input?input.providerCode:reach.provider)}${kv('交易币种',input?input.paymentCurrency:reach.currency)}${kv('C3 匹配覆盖行 ID',`#${r.selectedIndex+1}`,reachLine)}</dl></div>`
    +`<div class="kvsec"><div class="kvtitle">工作日与截单</div><dl class="kv">${kv('评估时间',c.evaluatedAt.replace('T',' '),`截单 ${reach.cutoff} ${reach.zone}`)}${kv('当前是否工作日',yesNo(c.currentBusinessDay))}${kv('是否早于截单时间',yesNo(c.beforeCutoff))}${kv('工作日偏移数',`+${reach.offsetDays} 个工作日`)}${kv('起算方式',rollReason[c.rollReason]||'—')}</dl></div>`
    +`<div class="kvsec"><div class="kvtitle">起息日</div><dl class="kv">${kv('请求起息日',c.requestedValueDate,r.requestedValueDateDefaulted?'未填写，默认香港当日':'')}${kv('最早可实现起息日',c.earliestValueDate,`基准工作日 ${c.baseBusinessDate} + ${reach.offsetDays}`)}${kv('候选起息日',c.candidateValueDate)}${kv('延迟工作日数',`${c.delayBusinessDays} 个工作日`)}</dl></div>`
    +`<div class="kvsec"><div class="kvtitle">速度评分</div><dl class="kv">${kv('命中评分组',scored?r.matchedScoreGroup:'—',scored?'':C5_STATUS[r.scoreStatus]||'')}${kv('最终 C5 得分',scoreLine,r.draft?'草稿试算，不影响实际评分':r.replay?'历史回放，不改变当前生效版本':'当前生效版本')}</dl>`
    +((r.groupResults||[]).length?`<div class="kvtitle" style="margin-top:10px">评分组逐组判断（${r.draft?'当前页面草稿':'规则 v'+r.scoreRuleVersion}）</div>${r.groupResults.map(g=>`<div class="gout${g.matched===true?' hit':''}"><span class="gprio">${g.priority}</span><div><b>${esc(g.name)}</b> <span class="muted">${esc(g.groupCode)}</span> · ${g.score??'—'} 分 · ${verdict(g)}${g.expression?`<br><span class="muted">${esc(g.expression)}</span>`:''}${g.note&&!['命中','未命中'].includes(g.note)?`<br><span class="muted">${esc(g.note)}</span>`:''}</div></div>`).join('')}`:'')
    +`</div><div class="notice">${esc(r.note)}</div>`;
    const groups=(r.groupResults||[]).map(g=>`<tr class="${g.matched?'row-selected':''}"><td>${g.priority}</td><td>${esc(g.name)}<br><span class="muted">${esc(g.groupCode)}</span></td><td>${esc(g.expression||'')}</td><td>${g.score??'—'}</td><td>${verdict(g)}</td><td class="muted">${esc(g.note||'')}</td></tr>`).join('');
    return head
    +`<div class="notice">${reachLine}</div>`
    +step(1,'当前时间与截单',c.evaluatedAt.replace('T',' '),`截单 ${reach.cutoff} ${reach.zone}。${rollReason[c.rollReason]||''}`)
    +step(2,'计算基准工作日',c.baseBusinessDate,'已按周末和本覆盖行节假日顺延。')
    +step(3,'最早可实现起息日',c.earliestValueDate,`基准工作日 + ${reach.offsetDays} 个工作日。`)
    +step(4,'客户请求起息日',c.requestedValueDate,requested?'使用上送的请求日期。':'请求日期未填写，默认香港当日（已记录默认标记）。')
    +step(5,'候选起息日',c.candidateValueDate,'max（请求起息日，最早可实现起息日），随路由返回给网银。')
    +step(6,'延迟工作日与速度分',`${c.delayBusinessDays} 个工作日 → ${scoreLine}`,'最早日不晚于请求日为 0 延迟；分数由速度评分规则按优先级首个命中的评分组给出。')
    +`${(r.groupResults||[]).length?`<div class="tablewrap score-table"><table><thead><tr><th>优先级</th><th>评分组</th><th>条件</th><th>分数</th><th>结果</th><th>说明</th></tr></thead><tbody>${groups}</tbody></table></div>`:''}`
    +`<div class="notice">${esc(r.note)}</div>`;
}
function showSimulation(containerId,html,isError){const box=$(containerId);box.className=isError?'err':'';box.innerHTML=html;}
// 试算：每个结果区只认最后一次请求——旧请求晚返回时直接丢弃，不会覆盖新结果；请求期间按钮禁用并显示加载状态。
// options.compact / input：结果按窄栏概览展示；options.after：结果显示后的回调（评分页签用它记录「这份结果是按哪份输入和规则算的」）。
const simulationSeq={};
async function runSimulation(containerId,button,path,body,requested,options){
    const control=$(button),seq=simulationSeq[containerId]=(simulationSeq[containerId]||0)+1;let result,ok=false;
    control.disabled=true;
    showSimulation(containerId,'<div class="notice">计算中，请稍候…</div>',false);
    try{
        result=await api(path,{method:'POST',body:JSON.stringify(body)});
        if(seq!==simulationSeq[containerId])return;
        ok=true;
        showSimulation(containerId,c5RenderSimulation(result,requested,options&&options.compact,options&&options.input),false);
    }catch(error){
        if(seq!==simulationSeq[containerId])return;
        showSimulation(containerId,esc(error.message),true);
    }finally{if(seq===simulationSeq[containerId])control.disabled=false;}
    if(ok&&options&&options.after)options.after(result);
}
$('simForm').onsubmit=e=>{e.preventDefault();runSimulation('simResult','simulate','/c5/simulate',simRequest('sim','at','requested'),$('requested').value);};
document.querySelectorAll('[data-sample]').forEach(b=>b.onclick=()=>{const sample=b.dataset.sample;$('simProvider').value='PROV-T1';$('simCurrency').value='USD';$('simCountry').value='US';$('simRegion').value='';$('simBic').value='CHASUS33';$('simCharge').value='SHA';$('at').value=sample==='cutoff'?'2026-11-20T17:00':sample==='weekend'?'2026-11-21T10:00':'2026-11-20T10:00';$('requested').value=sample==='future'?'2026-12-01':'2026-11-20';});

(async()=>{try{const [providers,data,scoreView]=await Promise.all([api('/providers'),api('/c5/schedules'),api('/c5/score-rule')]);$('filterProvider').innerHTML='<option value="">全部合作方</option>'+options(providers.map(p=>p.code));$('filterCurrency').innerHTML='<option value="">全部币种</option>'+options(data.currencies);$('simProvider').innerHTML=providers.map(p=>`<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');$('simProvider').value='PROV-T1';$('simCurrency').innerHTML=options(data.currencies,'USD');apply(data);c5RenderScoreCards(scoreView.published);if(typeof c5ScoreInit==='function')c5ScoreInit(scoreView,providers,data.currencies||[]);}catch(error){$('pageError').textContent=error.message;}})();
