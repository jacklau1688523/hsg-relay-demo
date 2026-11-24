'use strict';
let c1ConditionTree = null;
let c1TargetRuleId = new URLSearchParams(location.search).get('rule');
// 模拟面板固定采集的交易要素；字段库里其余 C1 字段的取值由模拟面板按字段定义动态生成
const c1SimFixedFields = new Set(['flowCategory', 'paymentCurrency', 'beneficiaryBankCountry', 'amountUsd']);
const c1Operators = {EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN',GT:'大于 >',GTE:'大于等于 ≥',LT:'小于 <',LTE:'小于等于 ≤'};
const c1Split = value => String(value||'').split(',').map(x=>x.trim()).filter(Boolean);
// 可选字段完全由元数据中心决定：已启用且适用规则含 C1 的字段全部进入下拉，运算符和可选值随字段定义走
function c1Fields(){return fieldDefs.filter(f=>f.enabled&&c1Split(f.allowedRules).includes('C1'));}
function c1DefaultValue(f){return c1Split(f?.valueOptions)[0]||(f?.dataType==='BOOLEAN'?'true':['DECIMAL','INTEGER'].includes(f?.dataType)?'0':f?.fieldCode==='flowCategory'?'STANDARD':'');}
function c1DefaultLeaf(){const f=c1Fields()[0];return {field:f?.fieldCode||'',operator:c1Split(f?.allowedOperators)[0]||'EQ',value:c1DefaultValue(f)};}
function c1InitCondition(json){
 try{c1ConditionTree=json?JSON.parse(json):{logic:'AND',children:[c1DefaultLeaf()]};}
 catch(_){c1ConditionTree={logic:'AND',children:[c1DefaultLeaf()]};}
 $('c1UseCondition').checked=!!json;$('c1UseCondition').disabled=!c1Fields().length;c1ToggleCondition();
 $('c1ConditionError').textContent='';$('c1ConditionGood').textContent='';
}
function c1ToggleCondition(){$('c1ConditionArea').style.opacity=$('c1UseCondition').checked?'1':'.72';c1RenderTree();}
function c1Node(path){let n=c1ConditionTree;for(const part of path.split('.').filter(Boolean))n=n.children[Number(part)];return n;}
function c1Remove(path){const parts=path.split('.'),index=Number(parts.pop()),parent=c1Node(parts.join('.'));if(parent.children.length===1){alert('条件组至少保留一项');return;}parent.children.splice(index,1);c1RenderTree();}
function c1Add(path,kind){const group=c1Node(path);if(group.children.length>=20){alert('每组最多20项');return;}if(kind==='group'&&path.split('.').filter(Boolean).length>=5){alert('最多6层条件组');return;}group.children.push(kind==='group'?{logic:'OR',children:[c1DefaultLeaf()]}:c1DefaultLeaf());c1RenderTree();}
// c1Shown：上一次重绘时展示给操作员的提示（字段被停用 / 条件值失效），用于判断改值后是否需要重绘
let c1Notices=[],c1Shown=[];
function c1HasOptions(node){const f=c1Fields().find(x=>x.fieldCode===node.field);return node.operator!=='IN'&&(f?.dataType==='BOOLEAN'||c1Split(f?.valueOptions).length>0);}
function c1Change(path,key,value){const node=c1Node(path);node[key]=value;if(key==='field'){const f=c1Fields().find(x=>x.fieldCode===value);node.operator=c1Split(f?.allowedOperators)[0]||'EQ';node.value=c1DefaultValue(f);}if(key==='field'||key==='operator'||key==='logic'||(key==='value'&&c1Shown.length&&c1HasOptions(node)))c1RenderTree();}
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
 return `<div class="c1-line"><select aria-label="条件字段" onchange="c1Change('${path}','field',this.value)">${fields.map(x=>`<option value="${esc(x.fieldCode)}" ${x.fieldCode===node.field?'selected':''}>${esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="c1Change('${path}','operator',this.value)">${ops.map(op=>`<option value="${esc(op)}" ${op===node.operator?'selected':''}>${esc(c1Operators[op]||op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')">✕</button></div>`;
}
function c1RenderGroup(group,path,depth=0){
 const logic=group.logic==='OR'?'OR':'AND';group.logic=logic;
 const head=`<div class="c1-head"><strong>${depth?'子条件组':'根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c1Change('${path}','logic',this.value)"><option value="AND" ${logic==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${logic==='OR'?'selected':''}>任一满足 OR</option></select>${depth?`<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')">删除子组</button>`:''}</div>`;
 const body=group.children.map((child,i)=>`${i?`<div class="c1-join">${logic}</div>`:''}${child.children?c1RenderGroup(child,path?path+'.'+i:String(i),depth+1):c1RenderLeaf(child,path?path+'.'+i:String(i))}`).join('');
 return `<div class="c1-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','group')">▧ 添加子条件组</button></div></div>`;
}
function c1RenderTree(){
 c1Notices=[];
 $('c1ConditionTree').innerHTML=c1RenderGroup(c1ConditionTree,'');
 c1Shown=[...new Set(c1Notices)];c1Notices=[];
 $('c1Compiled').textContent=(c1Shown.length?c1Shown.join('\n')+'\n':'')+'条件已修改，请重新校验';
}
async function c1CompileCondition(){try{const out=await api('/appetites/compile',{method:'POST',body:JSON.stringify({tree:c1ConditionTree})});$('c1Compiled').textContent=out.expression;$('c1ConditionError').textContent='';}catch(error){$('c1Compiled').textContent='条件校验失败';$('c1ConditionError').textContent=error.message;}}
async function c1LoadConditionRules(){
 try{
  const selected=c1TargetRuleId||$('c1RuleSelect').value;
  // 规则与字段库每次都从服务端重新读取：在元数据中心增删字段后点「刷新」即可生效
  [allRules,fieldDefs]=await Promise.all([api('/appetites'),api('/field-metadata')]);
  $('c1RuleSelect').innerHTML=allRules.map(r=>`<option value="${r.id}">#${r.id} · ${esc(r.providerCode)} · ${esc(r.flowCategory)} · ${esc(r.currency)}/${esc(r.bankCountry)} · ${esc(r.action)}</option>`).join('');
  if(allRules.some(r=>String(r.id)===String(selected)))$('c1RuleSelect').value=String(selected);
  c1TargetRuleId=null;c1SelectRule();c1RenderSimFields();
 }catch(error){$('c1ConditionError').textContent=error.message;}
}
// 模拟面板：除固定交易要素外，字段库里启用的 C1 字段按字段定义生成取值控件（枚举下拉 / 数字 / 文本）
function c1SimFields(){return c1Fields().filter(f=>!c1SimFixedFields.has(f.fieldCode));}
function c1RenderSimFields(){
 const list=c1SimFields(),blank='<option value="">（不提供）</option>';
 $('simVariables').innerHTML=list.length?'<div class="muted" style="margin:14px 0 4px">附加条件字段 · 取自元数据中心 C1 字段库。选「不提供」或留空的字段不参与本次模拟，引用它的规则按不命中处理。</div>'+list.map(f=>{
  const attr=`data-field="${esc(f.fieldCode)}" aria-label="${esc(f.fieldName)}"`,values=c1Split(f.valueOptions);
  const control=f.dataType==='BOOLEAN'?`<select ${attr}>${blank}<option selected>true</option><option>false</option></select>`:values.length?`<select ${attr}>${blank}${values.map((v,i)=>`<option ${i?'':'selected'}>${esc(v)}</option>`).join('')}</select>`:`<input ${attr} ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''} placeholder="请输入模拟取值">`;
  return `<div class="field section"><label>${esc(f.fieldName)}</label>${control}</div>`;
 }).join(''):'';
}
// 下拉选「不提供」或文本留空的字段不提交：规则引用了没有取值的字段时，后端按不命中处理并在校验过程中说明
function c1SimVariables(){const out={};c1SimFields().forEach(f=>{const v=$('simVariables').querySelector(`[data-field="${f.fieldCode}"]`)?.value.trim();if(v)out[f.fieldCode]=v;});return out;}
function c1EditCondition(id){c1TargetRuleId=id;c1LoadConditionRules();}
function c1SelectRule(){
 const rule=allRules.find(r=>String(r.id)===$('c1RuleSelect').value);
 if(!rule){$('c1RuleScope').textContent='请先新增准入规则，再配置触发条件。';$('c1ConditionArea').classList.add('hidden');return;}
 $('c1RuleScope').textContent=`当前规则 #${rule.id}：${rule.providerCode} / ${rule.flowCategory} / ${rule.currency} / ${rule.bankCountry} / ${rule.action}。附加条件不命中时，此规则不参与匹配。`;
 $('c1ConditionArea').classList.remove('hidden');c1InitCondition(rule.conditionJson);
}
async function c1SaveCondition(){
 const id=$('c1RuleSelect').value;if(!id)return;
 $('c1ConditionError').textContent='';$('c1ConditionGood').textContent='';
 try{
  const saved=await api('/appetites/'+encodeURIComponent(id)+'/condition',{method:'PUT',body:JSON.stringify({enabled:$('c1UseCondition').checked,tree:c1ConditionTree})});
  const index=allRules.findIndex(r=>String(r.id)===id);if(index>=0)allRules[index]=saved;
  $('c1ConditionGood').textContent=`条件配置已保存 · 规则 #${id} · 版本 ${saved.versionNo}`;
 }catch(error){$('c1ConditionError').textContent=error.message;}
}
