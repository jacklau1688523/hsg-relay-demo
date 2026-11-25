'use strict';
let c3ConditionTree = null;

let c3CondFields = [], c3CondVersion = null;
const c3CondOperators = {EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN',GT:'大于 >',GTE:'大于等于 ≥',LT:'小于 <',LTE:'小于等于 ≤'};
const c3CondSplit = value => String(value||'').split(',').map(x=>x.trim()).filter(Boolean);
// 可选字段完全由元数据中心决定：已启用且适用规则含 C3 的字段全部进入下拉，运算符和可选值随字段定义走
function c3CondAvailableFields(){return c3CondFields.filter(f=>f.enabled&&c3CondSplit(f.allowedRules).includes('C3'));}
function c3CondDefaultValue(f){return c3CondSplit(f?.valueOptions)[0]||(f?.dataType==='BOOLEAN'?'true':['DECIMAL','INTEGER'].includes(f?.dataType)?'0':f?.fieldCode==='flowCategory'?'STANDARD':'');}
function c3CondDefaultLeaf(){const f=c3CondAvailableFields()[0];return {field:f?.fieldCode||'',operator:c3CondSplit(f?.allowedOperators)[0]||'EQ',value:c3CondDefaultValue(f)};}
function c3CondInitCondition(json){
 try{c3ConditionTree=json?JSON.parse(json):{logic:'AND',children:[c3CondDefaultLeaf()]};}
 catch(_){c3ConditionTree={logic:'AND',children:[c3CondDefaultLeaf()]};}
 c3CondRenderTree();
 $('c3ConditionError').textContent='';$('c3ConditionGood').textContent='';
}
function c3CondNode(path){let n=c3ConditionTree;for(const part of path.split('.').filter(Boolean))n=n.children[Number(part)];return n;}
function c3CondRemove(path){const parts=path.split('.'),index=Number(parts.pop()),parent=c3CondNode(parts.join('.'));if(parent.children.length===1){alert('条件组至少保留一项');return;}parent.children.splice(index,1);c3CondRenderTree();}
function c3CondAdd(path,kind){const group=c3CondNode(path);if(group.children.length>=20){alert('每组最多20项');return;}if(kind==='group'&&path.split('.').filter(Boolean).length>=5){alert('最多6层条件组');return;}group.children.push(kind==='group'?{logic:'OR',children:[c3CondDefaultLeaf()]}:c3CondDefaultLeaf());c3CondRenderTree();}
// c3CondShown：上一次重绘时展示给操作员的提示（字段被停用 / 条件值失效），用于判断改值后是否需要重绘
let c3CondNotices=[],c3CondShown=[];
function c3CondHasOptions(node){const f=c3CondAvailableFields().find(x=>x.fieldCode===node.field);return node.operator!=='IN'&&(f?.dataType==='BOOLEAN'||c3CondSplit(f?.valueOptions).length>0);}
function c3CondChange(path,key,value){const node=c3CondNode(path);node[key]=value;if(key==='field'){const f=c3CondAvailableFields().find(x=>x.fieldCode===value);node.operator=c3CondSplit(f?.allowedOperators)[0]||'EQ';node.value=c3CondDefaultValue(f);}if(key==='field'||key==='operator'||key==='logic'||(key==='value'&&c3CondShown.length&&c3CondHasOptions(node)))c3CondRenderTree();}
function c3CondRenderLeaf(node,path){
 const fields=c3CondAvailableFields(),f=fields.find(x=>x.fieldCode===node.field)||fields[0];if(!f)return '<div class="err">请先在元数据中心启用 C3 字段</div>';
 // 已保存条件引用的字段在元数据中心停用或不再适用 C3 时，换成当前首个可用字段，并在表达式区提示
 if(!fields.some(x=>x.fieldCode===node.field)){c3CondNotices.push(`字段 ${node.field||'（空）'} 已在元数据中心停用或不再适用 C3，对应条件已换成当前可用字段，请核对后再保存`);node.field=f.fieldCode;node.operator=c3CondSplit(f.allowedOperators)[0];node.value=c3CondDefaultValue(f);}
 const ops=c3CondSplit(f.allowedOperators);if(!ops.includes(node.operator))node.operator=ops[0];
 const values=node.operator!=='IN'?(f.dataType==='BOOLEAN'?['true','false']:c3CondSplit(f.valueOptions)):[];
 // 还没有取值时（例如刚从 IN 的文本框切到 EQ）取第一个可选值，与下拉实际显示的保持一致
 if(values.length&&!String(node.value??'').trim())node.value=values[0];
 const current=String(node.value??'');
 // 已保存的条件值不在元数据中心当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
 const stale=values.length&&!values.includes(current);
 if(stale)c3CondNotices.push(`字段 ${f.fieldName} 的条件值「${current}」不在元数据中心当前可选值中，请重新选择`);
 const listed=stale?[current,...values]:values;
 const control=values.length?`<select aria-label="条件值" onchange="c3CondChange('${path}','value',this.value)">${listed.map(v=>`<option value="${esc(v)}" ${v===current?'selected':''}>${esc(v)}${stale&&v===current?'（已不在可选值中）':''}</option>`).join('')}</select>`:`<input aria-label="条件值" value="${esc(node.value)}" placeholder="${node.operator==='IN'?'多个值用逗号分隔':'输入条件值'}" oninput="c3CondChange('${path}','value',this.value)" ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''}>`;
 return `<div class="c1-line"><select aria-label="条件字段" onchange="c3CondChange('${path}','field',this.value)">${fields.map(x=>`<option value="${esc(x.fieldCode)}" ${x.fieldCode===node.field?'selected':''}>${esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="c3CondChange('${path}','operator',this.value)">${ops.map(op=>`<option value="${esc(op)}" ${op===node.operator?'selected':''}>${esc(c3CondOperators[op]||op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c3CondRemove('${path}')">✕</button></div>`;
}
function c3CondRenderGroup(group,path,depth=0){
 const logic=group.logic==='OR'?'OR':'AND';group.logic=logic;
 const head=`<div class="c1-head"><strong>${depth?'子条件组':'根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c3CondChange('${path}','logic',this.value)"><option value="AND" ${logic==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${logic==='OR'?'selected':''}>任一满足 OR</option></select>${depth?`<button type="button" class="btn secondary tiny" onclick="c3CondRemove('${path}')">删除子组</button>`:''}</div>`;
 const body=group.children.map((child,i)=>`${i?`<div class="c1-join">${logic}</div>`:''}${child.children?c3CondRenderGroup(child,path?path+'.'+i:String(i),depth+1):c3CondRenderLeaf(child,path?path+'.'+i:String(i))}`).join('');
 return `<div class="c1-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c3CondAdd('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c3CondAdd('${path}','group')">▧ 添加子条件组</button></div></div>`;
}
function c3CondRenderTree(){
 c3CondNotices=[];
 $('c3ConditionTree').innerHTML=c3CondRenderGroup(c3ConditionTree,'');
 c3CondShown=[...new Set(c3CondNotices)];c3CondNotices=[];
 $('c3Compiled').textContent=(c3CondShown.length?c3CondShown.join('\n')+'\n':'')+'条件已修改，请重新校验';
}

async function c3CondLoad() {
 try {
  const [config, fields] = await Promise.all([api('/c3/condition'), fetch('/api/field-metadata').then(r=>r.json())]);
  c3CondFields=fields; c3CondVersion=config.versionNo;
  c3CondInitCondition(config.conditionJson);
 } catch(error) { $('c3ConditionError').textContent=error.message; }
}
async function c3CondCompileCondition() {
 try {
  const out=await api('/c3/condition/compile',{method:'POST',body:JSON.stringify({tree:c3ConditionTree})});
  $('c3Compiled').textContent=out.expression;
  $('c3ConditionError').textContent='';
 } catch(error) {
  $('c3Compiled').textContent='条件校验失败';
  $('c3ConditionError').textContent=error.message;
 }
}
async function c3CondSave() {
 $('c3ConditionError').textContent='';
 try {
  const saved=await api('/c3/condition',{method:'PUT',body:JSON.stringify({tree:c3ConditionTree,versionNo:c3CondVersion})});
  c3CondVersion=saved.versionNo;
  $('c3ConditionGood').textContent='C3 附加条件已保存 · 版本 '+saved.versionNo;
 } catch(error) { $('c3ConditionError').textContent=error.message; }
}
c3CondLoad();
