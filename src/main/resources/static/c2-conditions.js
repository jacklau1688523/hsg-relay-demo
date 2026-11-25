'use strict';
let c2ConditionTree = null;

let c2CondFields = [], c2CondVersion = null;
const c2CondOperators = {EQ:'等于 =',NE:'不等于 ≠',IN:'属于 IN',GT:'大于 >',GTE:'大于等于 ≥',LT:'小于 <',LTE:'小于等于 ≤'};
const c2CondSplit = value => String(value||'').split(',').map(x=>x.trim()).filter(Boolean);
// 可选字段完全由元数据中心决定：已启用且适用规则含 C2 的字段全部进入下拉，运算符和可选值随字段定义走
function c2CondAvailableFields(){return c2CondFields.filter(f=>f.enabled&&c2CondSplit(f.allowedRules).includes('C2'));}
function c2CondDefaultValue(f){return c2CondSplit(f?.valueOptions)[0]||(f?.dataType==='BOOLEAN'?'true':['DECIMAL','INTEGER'].includes(f?.dataType)?'0':f?.fieldCode==='flowCategory'?'STANDARD':'');}
function c2CondDefaultLeaf(){const f=c2CondAvailableFields()[0];return {field:f?.fieldCode||'',operator:c2CondSplit(f?.allowedOperators)[0]||'EQ',value:c2CondDefaultValue(f)};}
function c2CondInitCondition(json){
 try{c2ConditionTree=json?JSON.parse(json):{logic:'AND',children:[c2CondDefaultLeaf()]};}
 catch(_){c2ConditionTree={logic:'AND',children:[c2CondDefaultLeaf()]};}
 c2CondRenderTree();
 c2$('c2ConditionError').textContent='';c2$('c2ConditionGood').textContent='';
}
function c2CondNode(path){let n=c2ConditionTree;for(const part of path.split('.').filter(Boolean))n=n.children[Number(part)];return n;}
function c2CondRemove(path){const parts=path.split('.'),index=Number(parts.pop()),parent=c2CondNode(parts.join('.'));if(parent.children.length===1){alert('条件组至少保留一项');return;}parent.children.splice(index,1);c2CondRenderTree();}
function c2CondAdd(path,kind){const group=c2CondNode(path);if(group.children.length>=20){alert('每组最多20项');return;}if(kind==='group'&&path.split('.').filter(Boolean).length>=5){alert('最多6层条件组');return;}group.children.push(kind==='group'?{logic:'OR',children:[c2CondDefaultLeaf()]}:c2CondDefaultLeaf());c2CondRenderTree();}
// c2CondShown：上一次重绘时展示给操作员的提示（字段被停用 / 条件值失效），用于判断改值后是否需要重绘
let c2CondNotices=[],c2CondShown=[];
function c2CondHasOptions(node){const f=c2CondAvailableFields().find(x=>x.fieldCode===node.field);return node.operator!=='IN'&&(f?.dataType==='BOOLEAN'||c2CondSplit(f?.valueOptions).length>0);}
function c2CondChange(path,key,value){const node=c2CondNode(path);node[key]=value;if(key==='field'){const f=c2CondAvailableFields().find(x=>x.fieldCode===value);node.operator=c2CondSplit(f?.allowedOperators)[0]||'EQ';node.value=c2CondDefaultValue(f);}if(key==='field'||key==='operator'||key==='logic'||(key==='value'&&c2CondShown.length&&c2CondHasOptions(node)))c2CondRenderTree();}
function c2CondRenderLeaf(node,path){
 const fields=c2CondAvailableFields(),f=fields.find(x=>x.fieldCode===node.field)||fields[0];if(!f)return '<div class="err">请先在元数据中心启用 C2 字段</div>';
 // 已保存条件引用的字段在元数据中心停用或不再适用 C2 时，换成当前首个可用字段，并在表达式区提示
 if(!fields.some(x=>x.fieldCode===node.field)){c2CondNotices.push(`字段 ${node.field||'（空）'} 已在元数据中心停用或不再适用 C2，对应条件已换成当前可用字段，请核对后再保存`);node.field=f.fieldCode;node.operator=c2CondSplit(f.allowedOperators)[0];node.value=c2CondDefaultValue(f);}
 const ops=c2CondSplit(f.allowedOperators);if(!ops.includes(node.operator))node.operator=ops[0];
 const values=node.operator!=='IN'?(f.dataType==='BOOLEAN'?['true','false']:c2CondSplit(f.valueOptions)):[];
 // 还没有取值时（例如刚从 IN 的文本框切到 EQ）取第一个可选值，与下拉实际显示的保持一致
 if(values.length&&!String(node.value??'').trim())node.value=values[0];
 const current=String(node.value??'');
 // 已保存的条件值不在元数据中心当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
 const stale=values.length&&!values.includes(current);
 if(stale)c2CondNotices.push(`字段 ${f.fieldName} 的条件值「${current}」不在元数据中心当前可选值中，请重新选择`);
 const listed=stale?[current,...values]:values;
 const control=values.length?`<select aria-label="条件值" onchange="c2CondChange('${path}','value',this.value)">${listed.map(v=>`<option value="${c2Esc(v)}" ${v===current?'selected':''}>${c2Esc(v)}${stale&&v===current?'（已不在可选值中）':''}</option>`).join('')}</select>`:`<input aria-label="条件值" value="${c2Esc(node.value)}" placeholder="${node.operator==='IN'?'多个值用逗号分隔':'输入条件值'}" oninput="c2CondChange('${path}','value',this.value)" ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''}>`;
 return `<div class="c1-line"><select aria-label="条件字段" onchange="c2CondChange('${path}','field',this.value)">${fields.map(x=>`<option value="${c2Esc(x.fieldCode)}" ${x.fieldCode===node.field?'selected':''}>${c2Esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" onchange="c2CondChange('${path}','operator',this.value)">${ops.map(op=>`<option value="${c2Esc(op)}" ${op===node.operator?'selected':''}>${c2Esc(c2CondOperators[op]||op)}</option>`).join('')}</select>${control}<button type="button" class="btn secondary tiny" onclick="c2CondRemove('${path}')">✕</button></div>`;
}
function c2CondRenderGroup(group,path,depth=0){
 const logic=group.logic==='OR'?'OR':'AND';group.logic=logic;
 const head=`<div class="c1-head"><strong>${depth?'子条件组':'根条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" onchange="c2CondChange('${path}','logic',this.value)"><option value="AND" ${logic==='AND'?'selected':''}>全部满足 AND</option><option value="OR" ${logic==='OR'?'selected':''}>任一满足 OR</option></select>${depth?`<button type="button" class="btn secondary tiny" onclick="c2CondRemove('${path}')">删除子组</button>`:''}</div>`;
 const body=group.children.map((child,i)=>`${i?`<div class="c1-join">${logic}</div>`:''}${child.children?c2CondRenderGroup(child,path?path+'.'+i:String(i),depth+1):c2CondRenderLeaf(child,path?path+'.'+i:String(i))}`).join('');
 return `<div class="c1-group">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" onclick="c2CondAdd('${path}','leaf')">＋ 添加条件</button><button type="button" class="btn secondary tiny" onclick="c2CondAdd('${path}','group')">▧ 添加子条件组</button></div></div>`;
}
function c2CondRenderTree(){
 c2CondNotices=[];
 c2$('c2ConditionTree').innerHTML=c2CondRenderGroup(c2ConditionTree,'');
 c2CondShown=[...new Set(c2CondNotices)];c2CondNotices=[];
 c2$('c2Compiled').textContent=(c2CondShown.length?c2CondShown.join('\n')+'\n':'')+'条件已修改，请重新校验';
}

async function c2CondLoad() {
 try {
  const [config, fields] = await Promise.all([c2Api('/condition'), fetch('/api/field-metadata').then(r=>r.json())]);
  c2CondFields=fields; c2CondVersion=config.versionNo;
  c2CondInitCondition(config.conditionJson);
 } catch(error) { c2$('c2ConditionError').textContent=error.message; }
}
async function c2CondCompileCondition() {
 try {
  const out=await c2Api('/condition/compile',{method:'POST',body:JSON.stringify({tree:c2ConditionTree})});
  c2$('c2Compiled').textContent=out.expression;
  c2$('c2ConditionError').textContent='';
 } catch(error) {
  c2$('c2Compiled').textContent='条件校验失败';
  c2$('c2ConditionError').textContent=error.message;
 }
}
async function c2CondSave() {
 c2$('c2ConditionError').textContent='';
 try {
  const saved=await c2Api('/condition',{method:'PUT',body:JSON.stringify({tree:c2ConditionTree,versionNo:c2CondVersion})});
  c2CondVersion=saved.versionNo;
  c2$('c2ConditionGood').textContent='C2 附加条件已保存 · 版本 '+saved.versionNo;
 } catch(error) { c2$('c2ConditionError').textContent=error.message; }
}
c2CondLoad();
