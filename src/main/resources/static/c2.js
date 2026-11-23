'use strict';
const c2$ = id => document.getElementById(id);
const c2Esc = value => String(value ?? '').replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const c2Key = str=>String(str||'');
const c2Api=async (path,opt={})=>{
  const res=await fetch('/api/c2'+path,{...opt,headers:{'Content-Type':'application/json',...(opt.headers||{})}});
  const data=await res.json().catch(()=>({error:'服务器未返回 JSON'}));
  if(!res.ok)throw new Error(data.error||data.message||'请求失败：'+res.status);
  return data;
};
const c2State={providers:[],fields:[],tags:[],rules:[],tree:null,requiredTags:['CNY_ONSHORE'],
  editingId:null,regVersion:null};

function c2DefaultTree(){return {operator:'AND',children:[
 {field:'paymentCurrency',operator:'EQ',value:'CNY'},
 {field:'beneficiaryBankCountry',operator:'EQ',value:'CN'}
]};}
function c2SetTab(tab){
 document.querySelectorAll('[data-c2tab]').forEach(el=>el.classList.toggle('sel',el.dataset.c2tab===tab));
 c2$('c2Regulatory').classList.toggle('hidden',tab!=='regulatory');
}
function c2Options(values,selected){return values.map(v=>`<option value="${c2Esc(v)}" ${selected===v?'selected':''}>${c2Esc(v)}</option>`).join('');}
function c2Field(code){return c2State.fields.find(f=>f.fieldCode===code)||c2State.fields[0];}
function c2Node(path){let n=c2State.tree;if(path==='')return n;for(const part of path.split('.'))n=n.children[Number(part)];return n;}
function c2Parent(path){const parts=path.split('.');const index=Number(parts.pop());return {parent:c2Node(parts.join('.')),index};}
function c2UpdateGroup(path,logic){c2Node(path).operator=logic;c2RenderTree();}
function c2UpdateField(path,field){const n=c2Node(path);const f=c2Field(field);n.field=field;n.operator='EQ';n.value=f.options[0];c2RenderTree();}
function c2UpdateOperator(path,op){const n=c2Node(path);n.operator=op;
 if(op==='IN')n.value=Array.isArray(n.value)?n.value:[n.value];
 else n.value=Array.isArray(n.value)?(n.value[0]||c2Field(n.field).options[0]):n.value;
 c2RenderTree();}
function c2UpdateValue(path,el){const n=c2Node(path);n.value=n.operator==='IN'?Array.from(el.selectedOptions).map(o=>o.value):el.value;
 if(n.operator==='IN')c2RenderTree();}
function c2AddCondition(path){const group=c2Node(path);if(group.children.length>=20)return alert('每个条件组最多20项');
 group.children.push({field:'paymentCurrency',operator:'EQ',value:'CNY'});c2RenderTree();}
function c2AddGroup(path){const group=c2Node(path);if(path.split('.').filter(Boolean).length>=5)return alert('条件组最多6层');
 if(group.children.length>=20)return alert('每个条件组最多20项');
 group.children.push({operator:'OR',children:[{field:'paymentCurrency',operator:'EQ',value:'CNY'}]});c2RenderTree();}
function c2RemoveNode(path){const {parent,index}=c2Parent(path);if(parent.children.length<=1)return alert('条件组至少保留1项');parent.children.splice(index,1);c2RenderTree();}
function c2RenderCondition(n,path){
 const f=c2Field(n.field);
 const fieldOptions=c2State.fields.map(x=>`<option value="${c2Esc(x.fieldCode)}" ${x.fieldCode===n.field?'selected':''}>${c2Esc(x.fieldName)}</option>`).join('');
 const opOptions=f.operators.map(op=>`<option value="${op}" ${n.operator===op?'selected':''}>${{EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN'}[op]||op}</option>`).join('');
 const values=n.operator==='IN'?(Array.isArray(n.value)?n.value:[n.value]):[n.value];
 const options=f.options.map(v=>`<option value="${c2Esc(v)}" ${values.includes(v)?'selected':''}>${c2Esc(v)}</option>`).join('');
 const valueSelect=n.operator==='IN'
   ?`<select multiple size="4" title="按住 Cmd/Ctrl 可以多选" onchange="c2UpdateValue('${path}',this)">${options}</select>`
   :`<select onchange="c2UpdateValue('${path}',this)">${options}</select>`;
 return `<div class="condition-row">
  <select aria-label="字段" onchange="c2UpdateField('${path}',this.value)">${fieldOptions}</select>
  <select aria-label="比较运算符" onchange="c2UpdateOperator('${path}',this.value)">${opOptions}</select>
  ${valueSelect}
  <button class="btn secondary tiny" title="删除条件" onclick="c2RemoveNode('${path}')">✕</button>
 </div>`;
}
function c2RenderGroup(group,path,depth=0){
 const op=group.operator==='OR'?'OR':'AND';
 const title=depth===0?'根条件组':'子条件组 '+depth;
 const groupHead=`<div class="group-head"><span><b>${title}</b> <small class="muted">（${group.children.length} 项）</small></span>
  <div class="inlinefilter"><select class="logic-select" title="本组条件关系" onchange="c2UpdateGroup('${path}',this.value)">
   <option value="AND" ${op==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${op==='OR'?'selected':''}>任意满足 OR</option></select>
  ${depth>0?`<button class="btn secondary tiny" onclick="c2RemoveNode('${path}')">删除子组</button>`:''}</div></div>`;
 const children=group.children.map((child,i)=>{
  const childPath=path?path+'.'+i:String(i);
  const prefix=i>0?`<div class="condition-join">${op}</div>`:'';
  return prefix+(child.children?c2RenderGroup(child,childPath,depth+1):c2RenderCondition(child,childPath));
 }).join('');
 return `<div class="condition-group">${groupHead}${children}
  <div class="actionrow"><button class="btn secondary tiny" onclick="c2AddCondition('${path}')">＋ 添加条件</button><button class="btn secondary tiny" onclick="c2AddGroup('${path}')">▧ 添加子条件组</button></div></div>`;
}
function c2RenderTree(){c2$('c2ConditionTree').innerHTML=c2RenderGroup(c2State.tree,'');}
function c2RenderRequiredTags(){c2$('c2RequiredTags').innerHTML=c2State.tags.map(t=>
 `<label class="tagcheck"><input type="checkbox" value="${c2Esc(t.tagCode)}" ${c2State.requiredTags.includes(t.tagCode)?'checked':''} onchange="c2UpdateRequiredTag(this)"><span>${c2Esc(t.tagCode)}</span><span class="muted">${c2Esc(t.tagName)}</span></label>`).join('');}
function c2UpdateRequiredTag(el){const tag=el.value;if(el.checked){if(!c2State.requiredTags.includes(tag))c2State.requiredTags.push(tag);}else c2State.requiredTags=c2State.requiredTags.filter(x=>x!==tag);}
function c2DateNow(){return new Date().toLocaleString('sv-SE',{timeZone:'Asia/Hong_Kong',hour12:false}).replace(' ','T').slice(0,16);}
function c2NewRegulatory(){
 c2State.editingId=null;c2State.regVersion=null;c2State.tree=c2DefaultTree();c2State.requiredTags=['CNY_ONSHORE'];
 c2$('c2FormTitle').textContent='新增监管路由规则';c2$('c2RuleId').value='REG-002';c2$('c2RuleId').disabled=false;
 c2$('c2RuleName').value='CNY 境内清算能力要求';c2$('c2From').value=c2DateNow();c2$('c2To').value='';
 c2$('c2Enabled').value='true';c2$('c2Remark').value='';c2$('c2Compiled').textContent='点击「校验并生成表达式」';
 c2$('c2RegError').textContent='';c2$('c2RegGood').textContent='';c2RenderTree();c2RenderRequiredTags();
 c2SetTab('regulatory');c2$('c2RegForm').scrollIntoView({behavior:'smooth',block:'start'});
}
function c2EditRegulatory(id){
 const r=c2State.rules.find(x=>x.ruleId===id);if(!r)return;
 c2State.editingId=r.ruleId;c2State.regVersion=r.versionNo;c2State.tree=JSON.parse(JSON.stringify(r.condition));
 c2State.requiredTags=[...r.requirement.requiredTags];
 c2$('c2FormTitle').textContent='编辑监管规则 · '+id;
 c2$('c2RuleId').value=id;c2$('c2RuleId').disabled=true;c2$('c2RuleName').value=r.ruleName;
 c2$('c2From').value=r.effectiveFrom?.slice(0,16)||'';c2$('c2To').value=r.effectiveTo?.slice(0,16)||'';
 c2$('c2Enabled').value=String(r.enabled);c2$('c2Remark').value=r.remark||'';
 c2$('c2RegError').textContent='';c2$('c2RegGood').textContent='';c2$('c2Compiled').textContent='点击「校验并生成表达式」';
 c2RenderTree();c2RenderRequiredTags();c2SetTab('regulatory');c2$('c2RegForm').scrollIntoView({behavior:'smooth',block:'start'});
}
function c2RegulatoryRequest(){return {
 ruleId:c2$('c2RuleId').value.trim(),ruleName:c2$('c2RuleName').value.trim(),ruleCode:'C2',scope:'GLOBAL',
 condition:JSON.parse(JSON.stringify(c2State.tree)),
 requirement:{type:'PROVIDER_CAPABILITY',requiredTags:[...c2State.requiredTags]},
 onFailure:{reasonCode:'REG_RULE_NOT_MET'},enabled:c2$('c2Enabled').value==='true',
 versionNo:c2State.regVersion,effectiveFrom:c2$('c2From').value||null,effectiveTo:c2$('c2To').value||null,
 remark:c2$('c2Remark').value.trim()||null
};}
async function c2CopyJson(){try{await navigator.clipboard.writeText(JSON.stringify(c2RegulatoryRequest(),null,2));c2$('c2RegGood').textContent='已复制到剪贴板';}catch(e){c2$('c2RegError').textContent='浏览器禁止复制，请使用开发者工具查看';}}
async function c2Compile(){c2$('c2RegError').textContent='';try{const resp=await c2Api('/regulatory-rules/compile',{method:'POST',body:JSON.stringify(c2State.tree)});
 c2$('c2Compiled').textContent=resp.expression;c2$('c2RegGood').textContent='表达式通过后端字段白名单与类型校验';}
 catch(e){c2$('c2RegError').textContent=e.message;c2$('c2Compiled').textContent='表达式未编译';}}
async function c2SaveRegulatory(){c2$('c2RegError').textContent='';c2$('c2RegGood').textContent='';try{
 const saved=await c2Api('/regulatory-rules',{method:'POST',body:JSON.stringify(c2RegulatoryRequest())});
 c2$('c2RegGood').textContent='保存成功 · 规则 '+saved.ruleId+' · 版本 '+saved.versionNo;
 await c2LoadRegulatory();c2EditRegulatory(saved.ruleId);
 c2$('c2RegGood').textContent='保存成功 · 规则 '+saved.ruleId+' · 版本 '+saved.versionNo;
}catch(e){c2$('c2RegError').textContent=e.message;}}
async function c2ToggleRegulatory(id){if(!confirm('确定切换监管规则 '+id+' 的启用状态？'))return;
 try{await c2Api('/regulatory-rules/'+encodeURIComponent(id)+'/toggle',{method:'POST'});await c2LoadRegulatory();if(c2State.editingId===id)c2EditRegulatory(id);}catch(e){alert(e.message);}}
async function c2LoadRegulatory(){c2State.rules=await c2Api('/regulatory-rules');
 c2$('c2RegRows').innerHTML=c2State.rules.map(r=>`<tr>
 <td>${c2Esc(r.ruleId)}</td><td>${c2Esc(r.ruleName)}</td><td>GLOBAL</td>
 <td>${r.requirement.requiredTags.map(c2Esc).join(', ')}</td><td>v${r.versionNo}</td>
 <td>${r.enabled?'<span class="dot"></span>启用':'停用'}</td>
 <td><a onclick="c2EditRegulatory('${c2Esc(r.ruleId)}')">编辑</a><a onclick="c2ToggleRegulatory('${c2Esc(r.ruleId)}')">${r.enabled?'停用':'启用'}</a></td>
 </tr>`).join('')||'<tr><td colspan="7">暂无监管规则</td></tr>';
}
async function c2SyncSimCapabilities(){try{const tags=await c2Api('/providers/'+encodeURIComponent(c2$('c2SimProvider').value)+'/capabilities');
 c2$('c2SimTags').textContent=tags.join(', ')||'无';}catch(e){c2$('c2SimTags').textContent=e.message;}}
async function c2Simulate(){
 const keys={paymentCurrency:'c2SimPaymentCurrency',debitCurrency:'c2SimDebitCurrency',beneficiaryBankCountry:'c2SimBeneficiaryBankCountry',
 beneficiaryAddressCountry:'c2SimBeneficiaryAddressCountry',beneficiaryRegion:'c2SimBeneficiaryRegion',orderingCountry:'c2SimOrderingCountry',
 clientCountry:'c2SimClientCountry',intermediaryCountry:'c2SimIntermediaryCountry',purpose:'c2SimPurpose'};
 const req={providerCode:c2$('c2SimProvider').value,directorUboNationalities:c2$('c2SimNationalities').value.split(',').map(x=>x.trim()).filter(Boolean)};
 for(const [key,id] of Object.entries(keys))req[key]=c2$(id).value||null;
 c2$('c2SimResult').innerHTML='<div class="notice">模拟计算中...</div>';
 try{const res=await c2Api('/simulate',{method:'POST',body:JSON.stringify(req)});
 let html=`<div class="result ${res.passed?'':'error'}">${res.passed?'✓ C2 校验通过':'✕ C2 校验失败'}</div>
 <p class="caps">返回原因码：<b>${c2Esc(res.reasonCode)}</b></p><p class="muted">${c2Esc(res.note)}</p>`;
 if(res.hits.length){html+='<h2>命中限制（按 C2 序号排序）</h2>';
 html+=res.hits.map(h=>`<div class="trace"><span class="badge fail">${c2Esc(h.reasonCode)}</span>
 <div style="margin-top:5px"><b>${c2Esc(h.ruleId)}</b> · ${c2Esc(h.field)}</div><div class="muted">${c2Esc(h.detail)}</div></div>`).join('');}
 if(res.regulatoryChecks.length){html+='<h2 style="margin:15px 0 8px">监管规则执行过程</h2>';
 html+=res.regulatoryChecks.map(r=>`<div class="trace"><b>${c2Esc(r.ruleId)}</b>
 <span class="badge ${r.triggered?'pending':'pass'}">${r.triggered?'触发':'未触发'}</span>
 <div class="muted">${r.triggered?'缺失标签：'+(r.missingTags.join(', ')||'无'):'条件未命中'}</div>
 <div class="code" style="margin-top:6px">${c2Esc(r.expression)}</div></div>`).join('');}
 c2$('c2SimResult').innerHTML=html;
 }catch(e){c2$('c2SimResult').innerHTML=`<div class="result error">${c2Esc(e.message)}</div>`;}
}
function c2FillSelect(id,fieldCode,defaultValue,optional=false){const field=c2Field(fieldCode);
 const options=(optional?'<option value="">— 不填写 —</option>':'')+field.options.map(v=>`<option value="${c2Esc(v)}" ${v===defaultValue?'selected':''}>${c2Esc(v)}</option>`).join('');
 c2$(id).innerHTML=options;c2$(id).value=defaultValue||'';}
async function c2Init(){try{
 const responses=await Promise.all([fetch('/api/providers').then(r=>r.json()),c2Api('/fields'),c2Api('/capability-tags')]);
 [c2State.providers,c2State.fields,c2State.tags]=responses;
 if(!c2State.fields.length)throw new Error('C2 字段库为空');
 for(const id of ['c2SimProvider']){
  c2$(id).innerHTML=c2State.providers.map(p=>`<option value="${c2Esc(p.code)}">${c2Esc(p.code)} · ${c2Esc(p.name)}</option>`).join('');
 }
 c2$('c2SimProvider').value='PROV-HO';
 c2FillSelect('c2SimPaymentCurrency','paymentCurrency','CNY');
 c2FillSelect('c2SimDebitCurrency','debitCurrency','CNY',true);
 c2FillSelect('c2SimBeneficiaryBankCountry','beneficiaryBankCountry','CN');
 c2FillSelect('c2SimBeneficiaryAddressCountry','beneficiaryAddressCountry','CN',true);
 c2FillSelect('c2SimBeneficiaryRegion','beneficiaryRegion','',true);
 c2FillSelect('c2SimOrderingCountry','orderingCountry','HK',true);
 c2FillSelect('c2SimClientCountry','clientCountry','',true);
 c2FillSelect('c2SimIntermediaryCountry','intermediaryCountry','',true);
 c2FillSelect('c2SimPurpose','purpose','',true);
 await Promise.all([c2LoadRegulatory(),c2SyncSimCapabilities()]);
 if(c2State.rules.length)c2EditRegulatory(c2State.rules[0].ruleId);else c2NewRegulatory();
 c2SetTab('regulatory');
 }catch(e){c2$('c2RegRows').innerHTML=`<tr><td colspan="7" style="color:#c43">页面初始化失败：${c2Esc(e.message)}</td></tr>`;}}
c2Init();
