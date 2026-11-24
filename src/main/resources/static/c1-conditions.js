'use strict';
let c1ConditionTree = null;
let c1TargetRuleId = new URLSearchParams(location.search).get('rule');
const c1FieldCodes = new Set(['flowCategory', 'paymentCurrency', 'beneficiaryBankCountry', 'amountUsd']);
const c1Operators = {EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN',GT:'大于 >',GTE:'大于等于 ≥',LT:'小于 <',LTE:'小于等于 ≤'};
function c1Fields(){return fieldDefs.filter(f=>f.enabled&&c1FieldCodes.has(f.fieldCode)&&String(f.allowedRules).split(',').includes('C1'));}
function c1DefaultLeaf(){const f=c1Fields()[0];return {field:f?.fieldCode||'',operator:String(f?.allowedOperators||'EQ').split(',')[0],value:String(f?.valueOptions||'').split(',')[0]||'STANDARD'};}
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
function c1Change(path,key,value){const node=c1Node(path);node[key]=value;if(key==='field'){const f=c1Fields().find(x=>x.fieldCode===value);node.operator=String(f?.allowedOperators||'EQ').split(',')[0];node.value=String(f?.valueOptions||'').split(',')[0]||'';}if(key==='field'||key==='operator'||key==='logic')c1RenderTree();}
function c1RenderLeaf(node,path){
 const fields=c1Fields(),f=fields.find(x=>x.fieldCode===node.field)||fields[0];if(!f)return '<div class="err">请先在元数据中心启用 C1 字段</div>';
 if(!fields.some(x=>x.fieldCode===node.field)){node.field=f.fieldCode;node.operator=String(f.allowedOperators).split(',')[0];node.value=String(f.valueOptions||'').split(',')[0]||'';}
 const ops=String(f.allowedOperators).split(',');if(!ops.includes(node.operator))node.operator=ops[0];
 const values=String(f.valueOptions||'').split(',').map(x=>x.trim()).filter(Boolean);
 const control=values.length&&node.operator!=='IN'?`<select aria-label="条件值" onchange="c1Change('${path}','value',this.value)">${values.map(v=>`<option value="${esc(v)}" ${v===node.value?'selected':''}>${esc(v)}</option>`).join('')}</select>`:`<input aria-label="条件值" value="${esc(node.value)}" placeholder="${node.operator==='IN'?'多个值用逗号分隔':'输入条件值'}" oninput="c1Change('${path}','value',this.value)" ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''}>`;
 return `<div class="c1-line"><select aria-label="条件字段" onchange="c1Change('${path}','field',this.value)">${fields.map(x=>`<option value="${esc(x.fieldCode)}" ${x.fieldCode===node.field?'selected':''}>${esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="c1Change('${path}','operator',this.value)">${ops.map(op=>`<option value="${esc(op)}" ${op===node.operator?'selected':''}>${esc(c1Operators[op]||op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')">✕</button></div>`;
}
function c1RenderGroup(group,path,depth=0){
 const logic=group.logic==='OR'?'OR':'AND';group.logic=logic;
 const head=`<div class="c1-head"><strong>${depth?'子条件组':'根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c1Change('${path}','logic',this.value)"><option value="AND" ${logic==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${logic==='OR'?'selected':''}>任一满足 OR</option></select>${depth?`<button type="button" class="btn secondary tiny" onclick="c1Remove('${path}')">删除子组</button>`:''}</div>`;
 const body=group.children.map((child,i)=>`${i?`<div class="c1-join">${logic}</div>`:''}${child.children?c1RenderGroup(child,path?path+'.'+i:String(i),depth+1):c1RenderLeaf(child,path?path+'.'+i:String(i))}`).join('');
 return `<div class="c1-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c1Add('${path}','group')">▧ 添加子条件组</button></div></div>`;
}
function c1RenderTree(){$('c1ConditionTree').innerHTML=c1RenderGroup(c1ConditionTree,'');$('c1Compiled').textContent='条件已修改，请重新校验';}
async function c1CompileCondition(){try{const out=await api('/appetites/compile',{method:'POST',body:JSON.stringify({tree:c1ConditionTree})});$('c1Compiled').textContent=out.expression;$('c1ConditionError').textContent='';}catch(error){$('c1Compiled').textContent='条件校验失败';$('c1ConditionError').textContent=error.message;}}
async function c1LoadConditionRules(){
 try{
  const selected=c1TargetRuleId||$('c1RuleSelect').value;
  allRules=await api('/appetites');
  $('c1RuleSelect').innerHTML=allRules.map(r=>`<option value="${r.id}">#${r.id} · ${esc(r.providerCode)} · ${esc(r.flowCategory)} · ${esc(r.currency)}/${esc(r.bankCountry)} · ${esc(r.action)}</option>`).join('');
  if(allRules.some(r=>String(r.id)===String(selected)))$('c1RuleSelect').value=String(selected);
  c1TargetRuleId=null;c1SelectRule();
 }catch(error){$('c1ConditionError').textContent=error.message;}
}
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
