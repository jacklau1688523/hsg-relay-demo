'use strict';
let c1ConditionTree = null;
let c1ActiveRule = null;
// 函数库数据（GET /function-metadata/available?ruleCode=C1）：受控取值路径、系统必检清单、可添加到条件的函数。
// 打开页面和打开「添加函数」时读取；读取失败不影响字段条件的配置，函数条件显示为不可用并说明原因。
let c1FunctionCatalog = null, c1FunctionCatalogError = '', c1FunctionTarget = null, c1FunctionDrafts = {}, c1FunctionResults = {};
const c1ConfiguredRuleId = new URLSearchParams(location.search).get('rule');
const c1Operators = {EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN',GT:'大于 >',GTE:'大于等于 ≥',LT:'小于 <',LTE:'小于等于 ≤'};
const c1Sources = {CANDIDATE:'候选合作方',TRANSACTION:'本笔交易',CONTEXT:'规则上下文'};
const c1Split = value => String(value||'').split(',').map(x=>x.trim()).filter(Boolean);
// 节点类型与服务端一致：有子条件的是条件组 GROUP；带 functionCode 的是函数条件 FUNCTION；其余是字段条件 FIELD。
// 旧版保存的 JSON 不带 type，按同样的规则识别。
const c1IsGroup = node => Array.isArray(node.children)&&node.children.length>0;
const c1IsFunction = node => !c1IsGroup(node)&&(node.type==='FUNCTION'||!!node.functionCode);
// 可选字段完全由元数据中心决定：已启用且适用规则含 C1 的字段全部进入下拉，运算符和可选值随字段定义走
function c1Fields(){return fieldDefs.filter(f=>f.enabled&&c1Split(f.allowedRules).includes('C1'));}
function c1DefaultValue(f){return c1Split(f?.valueOptions)[0]||(f?.dataType==='BOOLEAN'?'true':['DECIMAL','INTEGER'].includes(f?.dataType)?'0':f?.fieldCode==='flowCategory'?'STANDARD':'');}
function c1DefaultLeaf(){const f=c1Fields()[0];return {type:'FIELD',field:f?.fieldCode||'',operator:c1Split(f?.allowedOperators)[0]||'EQ',value:c1DefaultValue(f)};}
const c1Function = code => c1FunctionCatalog?.functions.find(f=>f.functionCode===code)||null;
function c1FunctionName(code){
  if(!c1FunctionCatalog)return code||'（未指定函数）';
  return c1FunctionCatalog.functions.find(f=>f.functionCode===code)?.functionName||c1FunctionCatalog.required.find(r=>r.functionCode===code)?.functionName||code||'（未指定函数）';
}
// 字段下拉里的名称：绑定了函数的字段标注取值来源，操作员能看出这个条件不需要请求提供取值
function c1FieldLabel(f){return f.valueSource==='FUNCTION'&&f.functionCode?`${f.fieldName}（ƒ ${c1FunctionName(f.functionCode)}）`:f.fieldName;}
const c1Show = value => value===undefined||value===null?'—':typeof value==='object'?JSON.stringify(value):String(value);
// 读取已保存的条件树：补上节点类型（旧 JSON 不带 type），函数条件的参数补成对象，页面内部统一使用同一种形态
function c1Normalize(node){
  if(c1IsGroup(node)){node.type='GROUP';node.children.forEach(c1Normalize);}
  else if(c1IsFunction(node)){node.type='FUNCTION';node.arguments={...(node.arguments||{})};}
  else node.type='FIELD';
  return node;
}
function c1InitCondition(json){
  let saved=null;
  try{saved=json?JSON.parse(json):null;}catch(_){saved=null;}
  c1ConditionTree=c1Normalize(saved||{logic:'AND',children:[c1DefaultLeaf()]});
  c1RenderTree();
  $('c1ConditionError').textContent='';$('c1ConditionGood').textContent='';
}
function c1Node(path){let n=c1ConditionTree;for(const part of path.split('.').filter(Boolean))n=n.children[Number(part)];return n;}
function c1Remove(path){
  const parts=path.split('.'),index=Number(parts.pop()),parent=c1Node(parts.join('.'));
  if(parent.children.length===1){alert('条件组至少保留一项');return;}
  const node=parent.children[index];
  // 系统必检函数从条件树删除后，C1 执行器每次判定仍会执行这项检查；删除前说明清楚，避免误以为是在取消检查
  const fn=c1IsFunction(node)?c1Function(node.functionCode):null;
  if(fn?.usagePolicy==='REQUIRED'&&!confirm(`${fn.functionName} 是系统必检函数：从条件树删除后，C1 执行器每次判定仍会执行这项检查并返回对应原因码（${fn.requiredNote||''}）。\n这里只是不再把它的返回值作为可选条件参与比较。确定删除吗？`))return;
  parent.children.splice(index,1);c1RenderTree();
}
function c1Add(path,kind){
  const group=c1Node(path);
  if(group.children.length>=20){alert('每组最多20项');return;}
  if(kind==='function'){c1OpenFunctionPicker(path);return;}
  if(kind==='group'&&path.split('.').filter(Boolean).length>=5){alert('最多6层条件组');return;}
  group.children.push(kind==='group'?{type:'GROUP',logic:'OR',children:[c1DefaultLeaf()]}:c1DefaultLeaf());c1RenderTree();
}
// c1Shown：上一次重绘时展示给操作员的提示（字段被停用 / 条件值失效），用于判断改值后是否需要重绘
let c1Notices=[],c1Shown=[];
function c1HasOptions(node){const f=c1Fields().find(x=>x.fieldCode===node.field);return node.operator!=='IN'&&(f?.dataType==='BOOLEAN'||c1Split(f?.valueOptions).length>0);}
function c1Change(path,key,value){const node=c1Node(path);node[key]=value;if(key==='field'){const f=c1Fields().find(x=>x.fieldCode===value);node.operator=c1Split(f?.allowedOperators)[0]||'EQ';node.value=c1DefaultValue(f);}if(key==='field'||key==='operator'||key==='logic'||(key==='value'&&c1Shown.length&&c1HasOptions(node)))c1RenderTree();}
function c1SetArgument(path,code,value){const node=c1Node(path);node.arguments={...(node.arguments||{}),[code]:value};}
function c1RemoveArgument(path,code){const node=c1Node(path);delete node.arguments[code];c1RenderTree();}
function c1RenderLeaf(node,path){
  const fields=c1Fields(),f=fields.find(x=>x.fieldCode===node.field)||fields[0];if(!f)return '<div class="err">请先在元数据中心启用 C1 字段</div>';
  // 已保存条件引用的字段在元数据中心停用或不再适用 C1 时，换成当前首个可用字段，并在表达式区提示
  if(!fields.some(x=>x.fieldCode===node.field)){c1Notices.push(`字段 ${node.field||'（空）'} 已在元数据中心停用或不再适用 C1，对应条件已换成当前可用字段，请核对后再保存`);node.field=f.fieldCode;node.operator=c1Split(f.allowedOperators)[0];node.value=c1DefaultValue(f);}
  const ops=c1Split(f.allowedOperators);if(!ops.includes(node.operator))node.operator=ops[0];
  const values=node.operator!=='IN'?(f.dataType==='BOOLEAN'?['true','false']:c1Split(f.valueOptions)):[];
  // 还没有取值时（例如刚从 IN 的文本框切到 EQ）取第一个可选值，与下拉实际显示的保持一致
  if(values.length&&!String(node.value??'').trim())node.value=values[0];
  const current=String(node.value??'');
  // 已保存的条件值不在元数据中心当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
  const stale=values.length&&!values.includes(current);
  if(stale)c1Notices.push(`字段 ${f.fieldName} 的条件值「${current}」不在元数据中心当前可选值中，请重新选择`);
  const listed=stale?[current,...values]:values;
  const control=values.length?`<select aria-label="条件值" onchange="c1Change('${path}','value',this.value)">${listed.map(v=>`<option value="${esc(v)}" ${v===current?'selected':''}>${esc(v)}${stale&&v===current?'（已不在可选值中）':''}</option>`).join('')}</select>`:`<input aria-label="条件值" value="${esc(node.value)}" placeholder="${node.operator==='IN'?'多个值用逗号分隔':'输入条件值'}" oninput="c1Change('${path}','value',this.value)" ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''}>`;
  return `<div class="c1-line"><select aria-label="条件字段" onchange="c1Change('${path}','field',this.value)">${fields.map(x=>`<option value="${esc(x.fieldCode)}" ${x.fieldCode===node.field?'selected':''}>${esc(c1FieldLabel(x))}</option>`).join('')}</select><select aria-label="比较方式" onchange="c1Change('${path}','operator',this.value)">${ops.map(op=>`<option value="${esc(op)}" ${op===node.operator?'selected':''}>${esc(c1Operators[op]||op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')" aria-label="删除条件">✕</button></div>`;
}

// ---------- 函数条件 ----------

const c1UsageBadge = policy => policy==='REQUIRED'?'<span class="c1-badge">系统必检</span>':'<span class="c1-badge optional">可选</span>';
// 比较方式下拉；action 为选项变化时执行的脚本
function c1OperatorControl(fn,operator,action){
  return `<select aria-label="比较方式" onchange="${action}">${fn.operators.map(op=>`<option value="${esc(op)}" ${op===operator?'selected':''}>${esc(c1Operators[op]||op)}</option>`).join('')}</select>`;
}
// 期望值控件随函数返回类型走：布尔用下拉，数字 / 日期 / 文本用输入框；action 为取值变化时执行的脚本
function c1ValueControl(fn,value,action){
  const current=value===undefined||value===null?'':String(value);
  if(fn.returnType==='BOOLEAN')return `<select aria-label="期望值" onchange="${action}"><option value="true" ${current==='true'?'selected':''}>true</option><option value="false" ${current==='false'?'selected':''}>false</option></select>`;
  const numeric=['INTEGER','DECIMAL'].includes(fn.returnType);
  return `<input aria-label="期望值" type="${numeric?'number':'text'}" step="any" value="${esc(current)}" placeholder="${fn.returnType==='DATE'?'2026-11-25T10:15:30':numeric?'数值':'输入期望值'}" oninput="${action}">`;
}
// 每个参数一个绑定输入框：下拉候选是受控取值路径（$candidate.providerCode），也可以填字面量；合法性由服务端校验。
// handler(code) 返回输入框的事件属性；remover(code) 用于移除不在函数参数中的多余绑定（选择器里没有多余绑定，不传）。
function c1ArgumentControls(fn,bindings,handler,remover){
  const parameters=fn.parameters||[], known=parameters.map(p=>p.code);
  const extra=remover?Object.keys(bindings||{}).filter(code=>!known.includes(code)):[];
  return parameters.map(p=>{
    const hint=[p.type,c1Sources[p.source]||p.source||''].filter(Boolean).join(' · ')+(p.defaultBinding?` · 默认 ${p.defaultBinding}`:'');
    return `<div class="c1-arg"><label>${esc(p.code)}${p.required?' <span class="c1-required-mark">*</span>':''} <span class="muted">${esc(hint)}</span></label><input list="c1PathOptions" aria-label="参数 ${esc(p.code)}" value="${esc(bindings?.[p.code]??p.defaultBinding??'')}" placeholder="${p.required?'必填：选择受控路径或填写字面量':'可留空'}" ${handler(p.code)}></div>`;
  }).join('')+extra.map(code=>`<div class="c1-arg"><label>${esc(code)} <span class="err" style="padding:0">不在函数参数中</span></label><div style="display:flex;gap:6px"><input aria-label="参数 ${esc(code)}" value="${esc(bindings[code]??'')}" ${handler(code)}><button type="button" class="btn secondary tiny" onclick="${remover(code)}" aria-label="移除参数">✕</button></div></div>`).join('');
}
// 函数不可用（已停用 / 不再适用 C1 / 后端实现未注册 / 函数库数据未加载）：保留节点原样展示并提示，不悄悄换成别的函数
function c1RenderStaleFunction(node,path){
  const code=node.functionCode||'';
  c1Notices.push(c1FunctionCatalog
    ?`函数 ${code||'（空）'} 在函数库中已停用、不再适用 C1 或后端实现未注册，引用它的条件无法执行，请删除后按需重新添加`
    :'函数库数据未加载，函数条件暂不能编辑；请点击「↻ 刷新规则 / 字段库」重试');
  return `<div class="c1-fn stale"><div class="c1-line"><span class="c1-fnname">ƒ <b>${esc(c1FunctionName(code))}</b> <span class="muted">${esc(code)}</span><span class="badge fail">不可用</span></span><span>${esc(c1Operators[node.operator]||node.operator||'')} ${esc(String(node.value??''))}</span><button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')" aria-label="删除函数条件">✕</button></div></div>`;
}
function c1RenderFunction(node,path){
  const fn=c1Function(node.functionCode);
  if(!fn)return c1RenderStaleFunction(node,path);
  const operators=fn.operators.length?fn.operators:['EQ','NE'];
  if(!operators.includes(node.operator))node.operator=operators[0];
  return `<div class="c1-fn"><div class="c1-line"><span class="c1-fnname">ƒ <b>${esc(fn.functionName)}</b> <span class="muted">${esc(fn.functionCode)}</span>${c1UsageBadge(fn.usagePolicy)}</span>${c1OperatorControl({operators},node.operator,`c1Change('${path}','operator',this.value)`)}${c1ValueControl(fn,node.value,`c1Change('${path}','value',this.value)`)}<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')" aria-label="删除函数条件">✕</button></div><div class="c1-args">${c1ArgumentControls(fn,node.arguments,code=>`oninput="c1SetArgument('${path}','${esc(code)}',this.value)"`,code=>`c1RemoveArgument('${path}','${esc(code)}')`)}</div><div class="muted">返回 ${esc(fn.returnType)} · 数据来源 ${esc(fn.dataSource||'—')}</div></div>`;
}

// ---------- 条件树 ----------

function c1RenderGroup(group,path,depth=0){
  const logic=group.logic==='OR'?'OR':'AND';group.logic=logic;
  const head=`<div class="c1-head"><strong>${depth?'子条件组':'根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c1Change('${path}','logic',this.value)"><option value="AND" ${logic==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${logic==='OR'?'selected':''}>任一满足 OR</option></select>${depth?`<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')">删除子组</button>`:''}</div>`;
  // 嵌套的条件组排在前面，字段 / 函数条件排在后面，保持根条件组与子条件组的显示顺序稳定
  const body=group.children.map((child,i)=>`${i?`<div class="c1-join">${logic}</div>`:''}${c1IsGroup(child)?c1RenderGroup(child,path?path+'.'+i:String(i),depth+1):c1IsFunction(child)?c1RenderFunction(child,path?path+'.'+i:String(i)):c1RenderLeaf(child,path?path+'.'+i:String(i))}`).join('');
  return `<div class="c1-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','group')">▧ 添加子条件组</button><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','function')">ƒ 添加函数</button></div></div>`;
}
function c1RenderTree(){
  c1Notices=[];
  $('c1ConditionTree').innerHTML=c1RenderGroup(c1ConditionTree,'');
  c1Shown=[...new Set(c1Notices)];c1Notices=[];
  $('c1Compiled').textContent=(c1Shown.length?c1Shown.join('\n')+'\n':'')+'条件已修改，请重新校验';
  c1RenderRequired();
}
// 系统必检清单：只读展示 C1 执行器在代码里强制执行的检查，不提供删除
function c1RenderRequired(){
  const box=$('c1RequiredList');
  if(!box)return;
  if(!c1FunctionCatalog){box.innerHTML=`<div class="muted">${esc(c1FunctionCatalogError?'函数库数据未加载：'+c1FunctionCatalogError:'')}</div>`;return;}
  const addable=new Set(c1FunctionCatalog.functions.map(f=>f.functionCode));
  box.innerHTML=`<div class="tablewrap"><table class="c1-table"><thead><tr><th>函数</th><th>C1 执行器强制落实的检查</th><th>添加到条件树</th></tr></thead><tbody>${c1FunctionCatalog.required.map(r=>`<tr><td><b>${esc(r.functionName)}</b><br><span class="muted">${esc(r.functionCode)}</span></td><td>${esc(r.note)}</td><td>${addable.has(r.functionCode)?'<span class="badge pass">可以</span>':'<span class="muted">当前不可添加（已停用或由执行器内部落实）</span>'}</td></tr>`).join('')}</tbody></table></div>`;
}
// 受控取值路径下拉候选（参数绑定输入框共用）
function c1RenderPathOptions(){
  $('c1PathOptions').innerHTML=(c1FunctionCatalog?.paths||[]).map(p=>`<option value="$${esc(p.path)}">${esc(p.label)}（${esc(p.type)}）</option>`).join('');
}
async function c1LoadFunctionCatalog(){
  try{c1FunctionCatalog=await api('/function-metadata/available?ruleCode=C1');c1FunctionCatalogError='';}
  catch(error){c1FunctionCatalog=null;c1FunctionCatalogError=error.message;}
  c1RenderPathOptions();
}
async function c1CompileCondition(){try{const out=await api('/appetites/compile',{method:'POST',body:JSON.stringify({tree:c1ConditionTree})});$('c1Compiled').textContent=out.expression;$('c1ConditionError').textContent='';}catch(error){$('c1Compiled').textContent='条件校验失败';$('c1ConditionError').textContent=error.message;}}
async function c1LoadConditionRules(){
  try{
   // 左侧配置对象只由合作方详情的规则入口（rule 参数）决定；右侧模拟输入不更换配置对象
   await Promise.all([api('/appetites'),api('/field-metadata')]).then(([rules,defs])=>{allRules=rules;fieldDefs=defs;});
   const wanted=c1ActiveRule?.id||c1ConfiguredRuleId;
   c1ActiveRule=allRules.find(r=>String(r.id)===String(wanted))||allRules.slice().sort((a,b)=>a.id-b.id)[0]||null;
   await c1LoadFunctionCatalog();
   c1InitCondition(c1ActiveRule?.conditionJson||null);
   $('c1SaveButton').disabled=!c1ActiveRule;
   $('c1SaveButton').textContent=c1ActiveRule?`保存条件至规则 #${c1ActiveRule.id}`:'保存条件配置';
   $('c1SaveButton').title=c1ActiveRule?'': '请先在合作方详情新增准入规则';
  }catch(error){$('c1ConditionError').textContent=error.message;}
}
async function c1SaveCondition(){
  const id=c1ActiveRule?.id;if(!id)return;
  $('c1ConditionError').textContent='';$('c1ConditionGood').textContent='';
  try{
   const saved=await api('/appetites/'+encodeURIComponent(id)+'/condition',{method:'PUT',body:JSON.stringify({enabled:true,tree:c1ConditionTree})});
   const index=allRules.findIndex(r=>String(r.id)===String(id));if(index>=0)allRules[index]=saved;
   c1ActiveRule=saved;
   $('c1ConditionGood').textContent=`条件配置已保存 · 规则 #${id} · 版本 ${saved.versionNo}`;
  }catch(error){$('c1ConditionError').textContent=error.message;}
}

// ---------- 「添加函数」选择器 ----------

// 新加入条件的默认形态：参数取函数库给出的默认绑定，运算符取第一个可用项，期望值取该返回类型的默认值
function c1FunctionDraft(fn){
  return {operator:fn.operators[0],value:fn.defaultValue===undefined||fn.defaultValue===null?'':String(fn.defaultValue),arguments:{...(fn.defaultArguments||{})}};
}
// 每个函数一份草稿（参数绑定 / 比较方式 / 期望值），打开选择器时按默认形态初始化
function c1InitFunctionDrafts(){c1FunctionCatalog?.functions.forEach(fn=>{c1FunctionDrafts[fn.functionCode]||=c1FunctionDraft(fn);});}
function c1OpenFunctionPicker(path){
  const group=c1Node(path);
  if(group.children.length>=20){alert('每组最多20项');return;}
  c1FunctionTarget=path;c1FunctionDrafts={};c1FunctionResults={};
  $('c1FunctionPicker').classList.add('show');
  c1RenderFunctionSample();
  // 先用页面已加载的函数库数据立刻渲染；再读取一次最新数据（元数据中心停用 / 启用函数后不用刷新页面），有变化才重绘
  c1InitFunctionDrafts();c1RenderFunctionPicker();
  const shown=JSON.stringify(c1FunctionCatalog);
  c1LoadFunctionCatalog().then(()=>{if(JSON.stringify(c1FunctionCatalog)!==shown){c1InitFunctionDrafts();c1RenderFunctionPicker();c1RenderTree();}});
}
function c1CloseFunctionPicker(){$('c1FunctionPicker').classList.remove('show');c1FunctionTarget=null;}
function c1Draft(code,key,value){if(c1FunctionDrafts[code])c1FunctionDrafts[code][key]=value;}
function c1DraftArgument(code,argument,value){c1FunctionDrafts[code].arguments={...c1FunctionDrafts[code].arguments,[argument]:value};}
function c1FunctionCard(fn){
  const code=fn.functionCode, draft=c1FunctionDrafts[code];
  return `<div class="c1-card" data-function="${esc(code)}">
    <div class="rowbetween"><h3>ƒ ${esc(fn.functionName)} <span class="muted">${esc(code)}</span></h3>${c1UsageBadge(fn.usagePolicy)}</div>
    <div class="muted">返回类型 ${esc(fn.returnType)} · 数据来源 ${esc(fn.dataSource||'—')}${fn.category?` · ${esc(fn.category)}`:''}</div>
    ${fn.description?`<p class="c1-desc">${esc(fn.description)}</p>`:''}
    ${fn.usagePolicy==='REQUIRED'?`<div class="notice">系统必检：${esc(fn.requiredNote||'')} C1 执行器每次判定都会执行这项检查，与是否加入条件树无关；加入后只是把它的返回值作为一个可选条件参与比较。</div>`:''}
    <div class="c1-args">${c1ArgumentControls(fn,draft.arguments,argument=>`oninput="c1DraftArgument('${esc(code)}','${esc(argument)}',this.value)"`)}</div>
    <div class="c1-args"><div class="c1-arg"><label>比较方式</label>${c1OperatorControl(fn,draft.operator,`c1Draft('${esc(code)}','operator',this.value)`)}</div><div class="c1-arg"><label>期望值</label>${c1ValueControl(fn,draft.value,`c1Draft('${esc(code)}','value',this.value)`)}</div></div>
    <div class="actions"><button type="button" class="btn tiny" onclick="c1AddFunction('${esc(code)}')">＋ 添加到条件</button><button type="button" class="btn secondary tiny" onclick="c1SimulateFunction('${esc(code)}')">▶ 用右侧模拟数据试算</button></div>
    <div class="c1-sim" id="c1Sim-${esc(code)}">${c1SimulationState(code)}</div>
  </div>`;
}
function c1RenderFunctionPicker(){
  if(!$('c1FunctionPicker').classList.contains('show'))return;
  const box=$('c1FunctionCards');
  if(!c1FunctionCatalog){box.innerHTML=`<div class="notice warn">函数库数据未加载${c1FunctionCatalogError?'：'+esc(c1FunctionCatalogError):''}。<button type="button" class="btn secondary tiny" onclick="c1OpenFunctionPicker(c1FunctionTarget||'')">重新加载</button></div>`;return;}
  if(!c1FunctionCatalog.functions.length){box.innerHTML='<div class="notice">函数库里没有可用于 C1 条件的函数：函数须已启用、适用规则含 C1，且后端实现已注册。请在元数据中心核对。</div>';return;}
  // 参数输入框的候选路径直接引用页面上的 #c1PathOptions，不在弹层里再放一份（避免重复 ID）
  box.innerHTML=c1FunctionCatalog.functions.map(c1FunctionCard).join('');
}
// 试算用右侧模拟面板的要素（客户、合作方、币种、国家、金额、首个流转类别）构造一次上下文
function c1FunctionSample(){
  return {providerCode:$('simProvider').value,clientId:$('simClient').value,paymentCurrency:$('simCurrency').value,beneficiaryBankCountry:$('simCountry').value,amountUsd:$('simAmount').value,flowCategory:c1Split($('simFlows').value)[0]||''};
}
// 选择器打开期间右侧面板被弹层遮住：把试算会取到的数据显示在选择器顶部，改数据需先关闭窗口
function c1RenderFunctionSample(){
  const s=c1FunctionSample();
  $('c1FunctionSample').textContent=`试算数据取自右侧「C1 规则模拟校验」面板当前填写的要素：客户 ${s.clientId||'—'} · 合作方 ${s.providerCode||'—'} · ${s.paymentCurrency||'—'} / ${s.beneficiaryBankCountry||'—'} / ${s.amountUsd||'—'} USD。要换数据请先关闭本窗口。`;
}
// 试算结果保存在选择器的状态里，卡片按状态渲染：函数库重载重绘卡片时，已显示的结果不会丢
async function c1SimulateFunction(code){
  const draft=c1FunctionDrafts[code], results=c1FunctionResults;
  if(!draft)return;
  const show=()=>{const box=$('c1Sim-'+code);if(box)box.innerHTML=c1SimulationState(code);};
  results[code]={pending:true};show();
  try{
    const result=await api('/function-metadata/'+encodeURIComponent(code)+'/simulate',{method:'POST',body:JSON.stringify({arguments:draft.arguments,operator:draft.operator,value:draft.value,sample:c1FunctionSample()})});
    results[code]={result};
  }catch(error){results[code]={error:error.message};}
  // 选择器已关闭或重新打开时（状态对象已被替换）丢弃这次的结果
  if(c1FunctionResults===results)show();
}
// 试算结果框的内容：进行中 / 参数不合法 / 执行结果
function c1SimulationState(code){
  const r=c1FunctionResults[code];
  return !r?'':r.pending?'<div class="notice">试算中…</div>':r.error?`<div class="err">参数不合法，未执行：${esc(r.error)}</div>`:c1SimulationHtml(r.result);
}
// 试算结果：条件结果、表达式、每个参数的实际入参与所用绑定、返回值、命中记录、数据来源、耗时、异常。
// 卡片较窄，逐行展示（标签 + 内容），不用宽表
function c1SimulationHtml(r){
  const names=Object.keys(r.arguments||{});
  const row=(label,value)=>`<div class="c1-kvrow"><span class="muted">${label}</span> <div>${value}</div></div>`;
  return `<div class="c1-simbox ${r.error?'bad':r.matched?'hit':''}">
    ${row('条件结果',r.matched===null||r.matched===undefined?'无法判断（函数执行失败）':r.matched?'✓ 命中 · 条件成立':'✕ 未命中 · 条件不成立')}
    ${row('表达式',`<span class="code" style="padding:6px 8px">${esc(r.expression)}</span>`)}
    ${names.length?names.map(k=>row(esc(k),`<b>${esc(c1Show(r.arguments[k]))}</b> <span class="muted">绑定 ${esc(r.bindings?.[k]||'—')}</span>`)).join(''):row('入参','（无参数）')}
    ${row('返回值',`<b>${esc(c1Show(r.value))}</b>`)}
    ${row('命中记录',r.recordId?'#'+esc(r.recordId):'—')}
    ${row('数据来源',`${esc(r.dataSource||'—')} · <b>耗时</b> ${esc(r.durationMs)} ms`)}
    ${r.error?`<div class="err">异常：${esc(r.error)}</div>`:''}
  </div>`;
}
function c1AddFunction(code){
  const draft=c1FunctionDrafts[code];
  if(!draft||c1FunctionTarget===null)return;
  c1Node(c1FunctionTarget).children.push({type:'FUNCTION',functionCode:code,arguments:{...draft.arguments},operator:draft.operator,value:draft.value});
  c1CloseFunctionPicker();c1RenderTree();
}
// C1 模拟校验结果里的函数调用记录：系统必检、条件节点、函数绑定字段都在这里，含入参、返回值、命中记录、数据来源、耗时、是否复用、异常。
// 右侧面板较窄，每次调用用一个紧凑的条目展示（与「校验过程」的条目一致），不用宽表；data-* 为原始值
function c1FunctionCallsHtml(calls){
  if(!calls?.length)return '';
  const rows=calls.map(c=>{
    const args=Object.entries(c.arguments||{}).map(([k,v])=>`<div>${esc(k)}=${esc(c1Show(v))}</div>`).join('');
    const row=(label,value)=>`<div class="c1-kvrow"><span class="muted">${label}</span> <div>${value}</div></div>`;
    return `<div class="trace c1-call${c.error?' bad':''}" data-function="${esc(c.functionCode)}" data-value="${esc(c1Show(c.value))}" data-record="${esc(c.recordId??'')}" data-cached="${c.cached?'true':'false'}">${c.cached?'<span class="c1-badge">复用</span>':'<span class="badge pass">执行</span>'}<b style="margin-left:8px">${esc(c.functionCode)}</b>
      ${row('入参',args||'—')}
      ${row('返回值',`<b>${esc(c1Show(c.value))}</b>`)}
      ${row('命中记录',c.recordId?'#'+esc(c.recordId):'—')}
      ${row('数据来源',esc(c.dataSource||'—'))}
      ${row('耗时',c.cached?'—（复用本次判定内同一查询的结果）':esc(c.durationMs)+' ms')}
      ${c.error?`<div class="err">异常：${esc(c.error)}</div>`:''}</div>`;
  });
  return `<h2 style="margin:15px 0 4px">函数调用</h2>${rows.join('')}`;
}
