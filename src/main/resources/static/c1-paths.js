'use strict';
// C1 合作方动态规则路径配置（页面逻辑）：页签、路径卡片、草稿 / 校验 / 发布、模拟试算、历史版本与审计。
// 条件树编辑复用 c1-conditions.js（同一个编辑器、同一个「添加函数」选择器）：这里只决定「哪条路径的条件树」挂在编辑器上。
const C1_TABS={flow:'tabFlow',paths:'tabPaths',simulate:'tabSimulate',history:'tabHistory'};
const C1_DEFAULT='DEFAULT';
const C1_PATH_CODE=/^[A-Z][A-Z0-9_]{0,31}$/;
// APPETITE_MATCH 的四个匹配级别（与后端 C1Service.APPETITE_MATCH_LEVELS 一致），由低到高
const C1_LEVEL_NAMES={ALL_ALL:'ALL/ALL',ALL_EXACT:'ALL 币种 + 精确国家',EXACT_ALL:'精确币种 + ALL 国家',EXACT_EXACT:'精确币种 + 精确国家'};
// 路径条件求值时执行器固定代入的字段：页面不提供，也不需要提供
const C1_INJECTED=['clientClassificationStatus','flowCategory','paymentCurrency','beneficiaryBankCountry','amountUsd'];
// 旧版全局准入条件里的两个布尔字段：新配置不能引用（已由 APPETITE_MATCH 取代）
const C1_LEGACY_FIELDS=['appetiteCurrencyEnabled','appetiteCountryEnabled'];
// 原因码的中文说明；路径自己的失败原因码（C1_PATH_xxx_NOT_MATCHED）单独归类
const C1_REASONS={PASS:'通过',C1_CONFIG_MISSING:'没有已发布的路径配置（默认拒绝）',C1_CONFIG_BROKEN:'路径配置损坏或不是有效的路径配置',
  C1_PATH_CONFLICT:'路径冲突：合作方同时绑定在多条启用的路径上',C1_PATH_NOT_FOUND:'没有可用路径（无启用的专用路径，DEFAULT 也未启用）',
  CLIENT_NOT_FOUND:'客户档案不存在',CLIENT_NOT_CLASSIFIED:'客户分类状态不是 COMPLETE',CLIENT_EXCLUDED:'客户在合作方的有效排除记录中',
  CLIENT_EXCLUSION_CHECK_FAILED:'客户排除检查失败（不按「未排除」放行）',APPETITE_NOT_SET:'未命中有效准入规则（默认拒绝）',
  APPETITE_EXCLUDED:'命中的准入规则为禁止',APPETITE_APPROVAL_MISSING:'命中 CASE_BY_CASE 但没有有效个案审批',
  APPETITE_CAP_REACHED:'MTD + 本笔金额超过月度上限',C1_CONDITION_NOT_MATCHED:'旧版全局准入条件不满足（迁移产生的 v1）'};
// 页面状态：view 是服务端一次取齐的数据（草稿、最新已发布、历史、迁移提示）；rule 是页面上正在编辑的草稿（有未保存修改时与 view.draft 不同）
const c1Paths={view:null,rule:null,selected:null,tree:null,dirty:false,tab:'flow',seq:0,shown:null,version:null};

// ---------- 页签 ----------

// 四个页签各自独立显示，只做隐藏 / 显示；路径草稿、试算要素和试算结果都留在页面里，切换不会丢失
function c1ShowTab(name){
  if(!C1_TABS[name])return;
  c1Paths.tab=name;
  document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('sel',b.dataset.tab===name));
  Object.keys(C1_TABS).forEach(tab=>$(C1_TABS[tab]).classList.toggle('hidden',tab!==name));
  if(name==='history')c1LoadHistory();
  if(name==='simulate')c1RenderSimVariables();
}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>c1ShowTab(b.dataset.tab));

// ---------- 读取 ----------

// 打开页面 / 点击「刷新」：路径配置、字段元数据、函数库一起读取；迁移说明单独读取，失败不影响配置
async function c1LoadPathConfig(){
  try{
    const [view]=await Promise.all([api('/c1/paths'),c1LoadEditorSources()]);
    $('c1PageError').textContent='';
    c1ApplyView(view);
    c1LoadMigration();
  }catch(error){
    $('c1PageError').textContent=`路径配置读取失败：${error.message}`;
    c1RenderRequired();
  }
}
const c1Clone=value=>JSON.parse(JSON.stringify(value));
// 时间显示到秒（服务端的时间带微秒）
const c1When=value=>value?String(value).replace('T',' ').slice(0,19):'—';
// 把服务端的草稿接到页面上：选中还存在的路径就保持选中（编辑器不跳走），否则选第一条；页面上没有未保存的修改
function c1ApplyView(view){
  c1Paths.view=view;
  c1Paths.rule=view?.draft?.rule?c1Clone(view.draft.rule):{ruleCode:'C1',branchMode:'FIRST_MATCH',paths:[]};
  if(!c1Paths.rule.paths.some(p=>p.pathCode===c1Paths.selected))c1Paths.selected=c1Paths.rule.paths[0]?.pathCode||null;
  c1Paths.dirty=false;
  c1RenderStatus();c1RenderPathCards();c1RenderEditor();c1RenderSimVersions();c1RenderSimVariables();
  c1RenderPublished(view?.published);c1RenderHistory(view?.history||[]);
  c1SimStale();
}
const c1DraftVersion=()=>c1Paths.view?.draft?.version??null;
const c1SelectedPath=()=>c1Paths.rule.paths.find(p=>p.pathCode===c1Paths.selected)||null;
// 出现过任一已发布版本的路径编码：这些路径不能删除（历史版本和决策记录仍引用它们），只能停用
const c1PublishedCodes=()=>new Set((c1Paths.view?.history||[]).flatMap(h=>(h.paths||[]).map(p=>p.pathCode)));
function c1RenderStatus(){
  const v=c1Paths.view;
  if(!v)return;
  const published=v.published?`已发布 v${v.published.version}`:'尚未发布',draft=v.draft?`草稿 v${v.draft.version}`:'没有草稿';
  const state=c1Paths.dirty?'页面有未保存的修改':v.draftChanged?'草稿与已发布版本不同':'草稿与已发布版本一致';
  $('c1PathStatus').textContent=`${published} · ${draft} · ${state}`;
  $('c1PathMeta').textContent=`${v.ruleCode} · ${v.branchMode}：按候选合作方选路径，没有专用路径的合作方走 ${C1_DEFAULT}`;
}
function c1MarkDirty(){c1Paths.dirty=true;c1RenderStatus();c1SimStale();}
// 程序触发的重绘（选中路径、载入草稿）不算修改：重绘后恢复原来的「未保存修改」状态
function c1WithoutDirty(render){const was=c1Paths.dirty;render();c1Paths.dirty=was;c1RenderStatus();c1SimStale();}

// ---------- 条件树 ----------

const c1TreeEmpty=tree=>!tree||(c1IsGroup(tree)&&!(tree.children||[]).length);
function c1TreeCount(node){
  if(!node)return 0;
  return c1IsGroup(node)?(node.children||[]).reduce((n,c)=>n+c1TreeCount(c),0):1;
}
// 只读展示用的条件文本（不依赖编辑器），与服务端 C1PathConfigService.describe 一致
function c1DescribeTree(node){
  if(!node)return '';
  if(c1IsGroup(node))return `(${(node.children||[]).map(c1DescribeTree).join(node.logic==='OR'?' OR ':' AND ')})`;
  const show=v=>v===undefined||v===null?'':typeof v==='object'?JSON.stringify(v):String(v);
  const args=Object.keys(node.arguments||{}).map(k=>`${k}=${show(node.arguments[k])}`).join(', ');
  return `${node.functionCode?`${node.functionCode}(${args})`:node.field||''}${node.operator?` ${node.operator} ${show(node.value)||'（空）'}`:''}`;
}
// 编辑器每次重绘后：把编辑器里的条件树写回当前路径（根条件组为空 = 这条路径没有条件树）
function c1SyncCondition(){
  const path=c1SelectedPath();
  if(!path||!c1Paths.tree)return;
  path.conditionAst=c1TreeEmpty(c1Paths.tree)?null:c1Paths.tree;
  c1Paths.dirty=true;c1RenderStatus();c1SimStale();
  c1RenderEditorHint();c1UpdateCardCondition(path.pathCode);
}
c1OnTreeRendered=c1SyncCondition;
// 不重绘的修改（改条件值、改参数绑定）也要写回草稿并标记「有未保存修改」
c1OnTreeChanged=c1SyncCondition;
c1AllowEmptyRoot=true;
// 旧版布尔字段不进入路径条件的字段下拉：新配置不能引用它们（已由 APPETITE_MATCH 取代）；回放 v1 时由执行器注入取值，不需要在这里配置
c1HiddenFields=C1_LEGACY_FIELDS;
// 路径的显示名：名称留空或与编码相同（新增路径默认拿编码当名称）时只显示编码
const c1PathLabel=(code,name)=>name&&name!==code?`${code} · ${name}`:String(code??'');
// 把所选路径的条件树挂到编辑器上：路径已有条件树就用它本身（编辑器里的修改直接改到页面草稿上）；
// 根节点不是条件组时包一层 AND；还没有条件树就挂一个空的根条件组，加了条件才写回路径
function c1RenderEditor(){
  const path=c1SelectedPath(),area=$('c1ConditionArea');
  $('c1EditorPath').textContent=path?c1PathLabel(path.pathCode,path.pathName):'—';
  area.classList.toggle('hidden',!path);
  $('c1ConditionError').textContent='';$('c1ConditionGood').textContent='';
  if(!path){c1Paths.tree=null;c1ConditionTree=null;$('c1ConditionTree').innerHTML='';return;}
  let tree=path.conditionAst;
  if(tree&&!c1TreeEmpty(tree)&&!c1IsGroup(tree)){tree={type:'GROUP',logic:'AND',children:[tree]};path.conditionAst=tree;}
  c1Paths.tree=tree&&!c1TreeEmpty(tree)?c1Normalize(tree):{type:'GROUP',logic:'AND',children:[]};
  c1WithoutDirty(()=>{c1ConditionTree=c1Paths.tree;c1RenderTree();});
  c1RenderEditorHint();
}
function c1RenderEditorHint(){
  const path=c1SelectedPath();
  $('c1EditorHint').textContent=!path?'还没有路径：点击「＋ 新增路径」。'
    :c1TreeEmpty(path.conditionAst)?`路径 ${path.pathCode} 还没有条件树：草稿可以保存；启用中的路径发布时必须有条件树（空条件树不等于通过），否则请停用它。`
    :'这条路径的条件树挂在下面的编辑器里（字段库 + 函数库）。修改先留在页面上，保存草稿后才写入草稿；根条件组可以删空，子条件组至少保留一项。';
}
function c1SelectPath(code,scroll){
  if(!c1Paths.rule.paths.some(p=>p.pathCode===code))return;
  c1Paths.selected=code;
  c1RenderEditor();c1RenderPathCards();
  if(scroll)$('c1PathEditor').scrollIntoView({behavior:'smooth',block:'start'});
}

// ---------- 路径卡片 ----------

// 列表顺序与服务端一致：按（优先级，编码）排序，DEFAULT 排在最后。优先级只决定这个顺序，不参与选路
function c1SortedPaths(){
  return [...c1Paths.rule.paths].sort((a,b)=>(a.pathCode===C1_DEFAULT)-(b.pathCode===C1_DEFAULT)
    ||(Number(a.priority)||0)-(Number(b.priority)||0)||String(a.pathCode).localeCompare(String(b.pathCode)));
}
// 每个合作方 → 绑定了它的启用中的专用路径（页面提前标出冲突；发布前服务端还会再检查）
function c1Bindings(){
  const bound=new Map();
  c1Paths.rule.paths.filter(p=>p.pathCode!==C1_DEFAULT&&p.enabled!==false)
    .forEach(p=>(p.providerCodes||[]).forEach(code=>bound.set(code,[...(bound.get(code)||[]),p.pathCode])));
  return bound;
}
function c1ConditionHtml(path){
  return path.conditionAst&&!c1TreeEmpty(path.conditionAst)?esc(c1DescribeTree(path.conditionAst)):'<span class="badge pending">没有条件树</span>';
}
function c1UpdateCardCondition(code){
  const box=document.querySelector(`#c1PathCards [data-path="${CSS.escape(code)}"] [data-cond]`);
  const path=c1Paths.rule.paths.find(p=>p.pathCode===code);
  if(box&&path)box.innerHTML=`<span class="caps">条件树（${c1TreeCount(path.conditionAst)} 项）</span>${c1ConditionHtml(path)}`;
}
function c1PathCard(path){
  const code=path.pathCode,isDefault=code===C1_DEFAULT,on=path.enabled!==false;
  const bound=c1Bindings(),conflict=(path.providerCodes||[]).filter(p=>(bound.get(p)||[]).length>1);
  const providerBoxes=providers.map(p=>`<label title="${esc(p.name)}"><input type="checkbox" data-key="provider" value="${esc(p.code)}" ${(path.providerCodes||[]).includes(p.code)?'checked':''} oninput="c1CardInput(event)" onchange="c1CardInput(event)"> ${esc(p.code)}</label>`).join('')
    ||'<span class="muted">没有合作方</span>';
  return `<div class="pathcard ${code===c1Paths.selected?'sel':''} ${on?'':'off'}" data-path="${esc(code)}">
    <div class="p1-head"><span class="c1-badge">${isDefault?'DEFAULT 路径':'专用路径'}</span><b>${esc(code)}</b>${conflict.length?`<span class="badge fail" title="合作方 ${esc(conflict.join('、'))} 同时绑定在多条启用的路径上，发布前必须解决">冲突</span>`:''}
      <label class="p1-switch"><input type="checkbox" data-key="enabled" ${on?'checked':''} onchange="c1CardInput(event)"> ${on?'启用':'停用'}</label></div>
    <div class="p1-grid">
      <div class="field"><label>路径名称</label><input data-key="name" value="${esc(path.pathName||'')}" maxlength="80" placeholder="如：广东地区合作方" oninput="c1CardInput(event)"></div>
      <div class="field"><label>优先级（只排序，不参与选路）</label><input type="number" data-key="priority" min="0" step="1" value="${esc(path.priority??0)}" oninput="c1CardInput(event)" onchange="c1CardInput(event)"></div>
      <div class="field span2"><label>失败原因码（留空 = C1_PATH_${esc(String(code).toUpperCase())}_NOT_MATCHED）</label><input data-key="reason" value="${esc(path.failureReasonCode||'')}" maxlength="64" placeholder="C1_PATH_${esc(String(code).toUpperCase())}_NOT_MATCHED" spellcheck="false" oninput="c1CardInput(event)"></div>
    </div>
    <div class="p1-box"><span class="caps">绑定合作方（${isDefault?'不绑定':(path.providerCodes||[]).length}）</span><div class="p1-prov">${isDefault?'<span class="muted">DEFAULT 不绑定合作方：没有启用专用路径的合作方走它，同样要过自己的条件树。</span>':providerBoxes}</div></div>
    <div class="p1-box p1-cond" data-cond><span class="caps">条件树（${c1TreeCount(path.conditionAst)} 项）</span>${c1ConditionHtml(path)}</div>
    ${isDefault&&on?`<div class="muted">当前会走 DEFAULT 的合作方：${esc(providers.map(p=>p.code).filter(c=>!(bound.get(c)||[]).length).join('、')||'无')}</div>`:''}
    <div class="actions">
      <button type="button" class="btn secondary tiny" data-act="edit">✎ 编辑条件</button>
      <button type="button" class="btn secondary tiny" data-act="simulate">▶ 试算</button>
      <button type="button" class="btn secondary tiny" data-act="delete" ${c1PublishedCodes().has(code)?'disabled title="已发布过，不能删除；不需要时请停用"':''}>✕ 删除</button>
    </div>
  </div>`;
}
function c1RenderPathCards(){
  const box=$('c1PathCards'),paths=c1SortedPaths();
  box.innerHTML=paths.length?paths.map(c1PathCard).join(''):'<div class="notice">还没有路径：点击「＋ 新增路径」。发布前必须有一条 DEFAULT 路径（不绑定合作方）。</div>';
}
// 卡片上的修改直接改到页面草稿上；优先级 / 启用 / 绑定合作方变化后重绘卡片（顺序、冲突标记、DEFAULT 覆盖范围随之更新）
function c1CardInput(event){
  const el=event.target,path=c1Paths.rule.paths.find(p=>p.pathCode===el.closest('[data-path]')?.dataset.path);
  if(!path||!el.dataset.key)return;
  switch(el.dataset.key){
    case 'name':path.pathName=el.value;break;
    case 'reason':path.failureReasonCode=el.value;break;
    case 'priority':path.priority=el.value;break;
    case 'enabled':path.enabled=el.checked;break;
    case 'provider':path.providerCodes=el.checked?[...new Set([...(path.providerCodes||[]),el.value])].sort():(path.providerCodes||[]).filter(c=>c!==el.value);break;
  }
  c1MarkDirty();
  if(event.type==='change'&&['priority','enabled','provider'].includes(el.dataset.key))c1RenderPathCards();
}
// 卡片上的按钮：编辑条件 / 试算 / 删除（路径编码放在 data-path 里，不拼进脚本）
$('c1PathCards').addEventListener('click',event=>{
  const button=event.target.closest('[data-act]');
  if(!button||button.disabled)return;
  const code=button.closest('[data-path]').dataset.path;
  if(button.dataset.act==='edit')c1SelectPath(code,true);
  else if(button.dataset.act==='simulate')c1SimulatePath(code);
  else c1DeletePath(code);
});
function c1AddPath(){
  const code=(prompt('新路径编码：大写字母开头，仅大写字母、数字、下划线，最多 32 位（如 P3）。编码发布后不能再改。','')||'').trim().toUpperCase();
  if(!code)return;
  if(!C1_PATH_CODE.test(code)){alert('路径编码须以大写字母开头，仅含大写字母、数字、下划线，最多 32 位');return;}
  if(c1Paths.rule.paths.some(p=>p.pathCode===code)){alert(`路径 ${code} 已存在`);return;}
  const priority=Math.max(0,...c1Paths.rule.paths.filter(p=>p.pathCode!==C1_DEFAULT).map(p=>Number(p.priority)||0))+1;
  // 名称默认用编码（名称不能为空，改个更贴切的名字即可）；编码发布后不能再改
  c1Paths.rule.paths.push({pathCode:code,pathName:code,providerCodes:[],priority,enabled:true,conditionAst:null,failureReasonCode:''});
  c1MarkDirty();
  c1SelectPath(code,true);
}
function c1DeletePath(code){
  const index=c1Paths.rule.paths.findIndex(p=>p.pathCode===code);
  if(index<0)return;
  if(c1PublishedCodes().has(code)){alert(`路径 ${code} 已发布过，不能删除（历史版本和决策记录仍引用它）；不需要时请停用。`);return;}
  if(!confirm(`删除路径 ${code}？只影响页面上的草稿，保存草稿后生效。`))return;
  c1Paths.rule.paths.splice(index,1);
  if(c1Paths.selected===code)c1Paths.selected=c1Paths.rule.paths[0]?.pathCode||null;
  c1MarkDirty();c1RenderPathCards();c1RenderEditor();
}

// ---------- 草稿 JSON 与校验结果 ----------

// 页面草稿 → 提交的配置 JSON。根条件组为空的路径上送 null（= 没有条件树）；优先级不是整数时原样上送，由服务端报错，页面不悄悄改成 0
function c1PathRuleJson(){
  return {ruleCode:'C1',branchMode:'FIRST_MATCH',paths:c1Paths.rule.paths.map(path=>({
    pathCode:String(path.pathCode??'').trim(),pathName:String(path.pathName??''),
    priority:String(path.priority??'').trim()===''?0:Number(path.priority),
    enabled:path.enabled!==false,providerCodes:[...(path.providerCodes||[])],
    conditionAst:c1TreeEmpty(path.conditionAst)?null:path.conditionAst,
    failureReasonCode:String(path.failureReasonCode??'').trim()||null}))};
}
function c1Operator(){
  const who=$('c1Operator').value.trim();
  if(!who)throw Error('请先填写操作人（保存草稿 / 发布必填）');
  return who;
}
const c1ShowCheck=html=>{$('c1PathCheckResult').innerHTML=html;};
// 校验结果：errors 任一项不满足都不能发布；warnings 不阻止发布，但要读
function c1RenderCheck(check,title){
  const list=items=>items.map(x=>`<li>${esc(x)}</li>`).join(''),errors=check.errors||[],warnings=check.warnings||[];
  return `${errors.length?`<div class="err"><b>${title}未通过（${errors.length} 项）：</b><ul>${list(errors)}</ul></div>`
    :`<div class="good"><b>${title}通过</b>${warnings.length?'，有以下提示':''}</div>`}`
    +(warnings.length?`<div class="notice warn"><b>提示（${warnings.length} 项，不阻止发布，但要读）：</b><ul>${list(warnings)}</ul></div>`:'');
}
// 每条非空条件树的可读表达式（服务端编译后返回）
function c1RenderCompiled(paths){
  $('c1Compiled').textContent=(paths||[]).length?(paths||[]).map(p=>`${p.pathName&&p.pathName!==p.pathCode?`${p.pathCode}（${p.pathName}）`:p.pathCode}：${p.expression||'（没有条件树）'}`).join('\n'):'没有可编译的条件树';
}
async function c1CheckPathDraft(){
  const button=$('c1PathCheck');button.disabled=true;
  try{
    const check=await api('/c1/paths/check',{method:'POST',body:JSON.stringify({rule:c1PathRuleJson()})});
    c1ShowCheck(c1RenderCheck(check,'校验'));c1RenderCompiled(check.paths);
  }catch(error){c1ShowCheck(`<div class="err">${esc(error.message)}</div>`);}
  finally{button.disabled=false;}
}
async function c1CompilePaths(){
  const button=$('c1PathCompile');button.disabled=true;
  try{
    const check=await api('/c1/paths/compile',{method:'POST',body:JSON.stringify({rule:c1PathRuleJson()})});
    c1RenderCompiled(check.paths);
    $('c1ConditionError').textContent=check.errors?.length?check.errors.join('\n'):'';
    $('c1ConditionGood').textContent=check.errors?.length?'':'条件树可以编译（发布前仍会做规则级与发布前检查，请用「校验」查看）';
  }catch(error){$('c1Compiled').textContent='条件校验失败';$('c1ConditionError').textContent=error.message;$('c1ConditionGood').textContent='';}
  finally{button.disabled=false;}
}
async function c1SavePathDraft(){
  const button=$('c1PathSave');button.disabled=true;
  try{
    const who=c1Operator();
    const view=await api('/c1/paths/draft',{method:'PUT',body:JSON.stringify({rule:c1PathRuleJson(),expectedDraftVersion:c1DraftVersion(),operator:who})});
    c1ApplyView(view);
    c1ShowCheck(`<div class="good">草稿已保存（草稿 v${view.draft.version}，操作人 ${esc(who)}）。草稿不参与判定，发布后才生效。</div>`);
  }catch(error){c1ShowCheck(`<div class="err">${esc(error.message)}</div>`);}
  finally{button.disabled=false;}
}
// 发布的是已保存的草稿：页面有未保存的修改时先要求保存；发布前把全部检查跑一遍，有错误不进入发布，有提示项须勾选确认
async function c1PublishPaths(){
  const button=$('c1PathPublish');button.disabled=true;
  try{
    // 先看发布对象（页面草稿是否已保存），再看操作人：内容问题比操作人更该先知道
    if(c1Paths.dirty)throw Error('页面上的修改还没有保存成草稿：请先「保存草稿」，再发布');
    if(!c1Paths.view?.draft)throw Error('没有可发布的草稿：请先「保存草稿」');
    const who=c1Operator();
    const check=await api('/c1/paths/check',{method:'POST',body:JSON.stringify({rule:c1PathRuleJson()})});
    if(!check.ok){c1ShowCheck(c1RenderCheck(check,'发布前校验'));return;}
    c1ShowCheck(c1RenderCheck(check,'发布前校验')+`<div class="notice" id="c1Confirm"><b>确认发布为新版本</b>（操作人 ${esc(who)}）。已发布版本不可修改、不可覆盖，C1 立即改读新版本。
      ${check.warnings.length?'<label class="gcheck"><input type="checkbox" id="c1Ack"> 我已阅读上面的提示，并用模拟试算确认过路径的覆盖范围</label>':''}
      <div class="actions" style="margin-top:10px"><button type="button" class="btn" id="c1ConfirmYes">确认发布</button><button type="button" class="btn secondary" id="c1ConfirmNo">取消</button></div></div>`);
    $('c1ConfirmNo').onclick=()=>$('c1Confirm').remove();
    $('c1ConfirmYes').onclick=async()=>{
      const yes=$('c1ConfirmYes');yes.disabled=true;
      try{
        if(check.warnings.length&&!$('c1Ack').checked)throw Error('请先勾选确认提示项');
        const published=await api('/c1/paths/publish',{method:'POST',body:JSON.stringify({expectedDraftVersion:c1DraftVersion(),operator:who,note:$('c1Note').value.trim()||null})});
        $('c1Note').value='';
        // 发布前的试算结果（按草稿或旧版本算的）已不代表现状：清掉，提示重新试算
        c1Paths.shown=null;$('simResult').innerHTML=`<div class="notice">路径配置已发布 v${published.version}。发布前的试算结果已清除，请在「模拟试算」重新试算。</div>`;
        await c1LoadPathConfig();
        c1ShowCheck(`<div class="good">已发布 v${published.version}（操作人 ${esc(who)}${published.note?`，说明：${esc(published.note)}`:''}）。C1 从现在起按 v${published.version} 判定。</div>`);
      }catch(error){yes.disabled=false;$('c1Confirm').insertAdjacentHTML('afterbegin',`<div class="err">${esc(error.message)}</div>`);}
    };
  }catch(error){c1ShowCheck(`<div class="err">${esc(error.message)}</div>`);}
  finally{button.disabled=false;}
}

// ---------- 模拟试算 ----------

function c1SimulationBody(){
  return {clientId:$('simClient').value,providerCode:$('simProvider').value,paymentCurrency:$('simCurrency').value,
    beneficiaryBankCountry:$('simCountry').value,amountUsd:$('simAmount').value,variables:c1SimulationVariables()};
}
// 试算可以提供取值的 C1 字段：字段库里已启用、适用 C1、取值来源为请求输入，且不是执行器固定代入的字段
function c1SimulationFields(){
  return c1Fields().filter(f=>!C1_INJECTED.includes(f.fieldCode)&&!C1_LEGACY_FIELDS.includes(f.fieldCode)&&!['FUNCTION','DATA_RESOLVER'].includes(f.valueSource));
}
function c1RenderSimVariables(){
  const box=$('simVariables'),fields=c1SimulationFields(),current=c1SimulationVariables();
  if(!fields.length){box.innerHTML='';return;}
  // 有可选值 → 下拉（首项「不提供」，默认选中第一个可选值）；数字 → 数字输入框；其它 → 文本框。选「不提供」或留空的字段不提交
  box.innerHTML='<div class="caps" style="grid-column:1/-1">路径条件可能用到的其它 C1 字段（字段库 · 请求输入）：不提供的字段，条件里引用它时按「没有取值、条件不满足」处理</div>'
    +fields.map(f=>{
      const values=f.dataType==='BOOLEAN'?['true','false']:c1Split(f.valueOptions),label=`${esc(f.fieldName)} <span class="muted">${esc(f.fieldCode)}</span>`;
      if(values.length)return `<div class="field"><label>${label}</label><select data-variable="${esc(f.fieldCode)}"><option value="">（不提供）</option>${values.map(v=>`<option value="${esc(v)}" ${(current[f.fieldCode]??values[0])===v?'selected':''}>${esc(v)}</option>`).join('')}</select></div>`;
      return `<div class="field"><label>${label}</label><input data-variable="${esc(f.fieldCode)}" value="${esc(current[f.fieldCode]??'')}" placeholder="不提供则留空" ${['DECIMAL','INTEGER'].includes(f.dataType)?'type="number" step="any"':''}></div>`;
    }).join('');
}
function c1SimulationVariables(){
  const out={};
  document.querySelectorAll('#simVariables [data-variable]').forEach(el=>{if(String(el.value).trim())out[el.dataset.variable]=el.value;});
  return out;
}
// 版本下拉：最新已发布版本，或回放某个历史版本（与决策记录里的 configVersion 对应）
function c1RenderSimVersions(){
  const history=c1Paths.view?.history||[],published=c1Paths.view?.published,current=$('simVersion').value;
  $('simVersion').innerHTML=`<option value="">${published?`最新已发布版本 v${published.version}`:'最新已发布版本（尚未发布）'}</option>`
    +history.map(h=>`<option value="${h.version}">回放 v${h.version}（${esc(c1When(h.publishedAt))}）</option>`).join('');
  if([...$('simVersion').options].some(o=>o.value===current))$('simVersion').value=current;
}
// 每个结果只认最后一次请求：旧请求晚返回时直接丢弃。出结果后配置或要素再有修改，结果标记为过期（不悄悄当成当前配置的结果）
function c1SimKey(draft){
  return JSON.stringify([$('simClient').value,$('simProvider').value,$('simCurrency').value,$('simCountry').value,$('simAmount').value,
    c1SimulationVariables(),draft?c1PathRuleJson():$('simVersion').value]);
}
function c1SimStale(){
  $('simStale').classList.toggle('hidden',!c1Paths.shown||c1Paths.shown.key===c1SimKey(c1Paths.shown.draft));
}
async function c1RunSimulation(draft,button){
  const seq=++c1Paths.seq,key=c1SimKey(draft);
  // 是否显式指定了回放版本：发请求前记下，结果里的「数据来源」才和过程里「0. 路径配置」的说法一致
  const explicit=!draft&&!!$('simVersion').value;
  button.disabled=true;$('simStale').classList.add('hidden');
  $('simResult').innerHTML='<div class="notice">计算中，请稍候…</div>';
  try{
    // 按草稿试算：用页面上现在的配置，不必先保存；按已发布版本校验：与 C1–C10 Check 读同一份版本，configVersion 指定时按该版本回放
    const result=draft?await api('/c1/paths/simulate',{method:'POST',body:JSON.stringify({rule:c1PathRuleJson(),simulation:c1SimulationBody()})})
      :await api('/c1/simulate',{method:'POST',body:JSON.stringify({...c1SimulationBody(),configVersion:$('simVersion').value?Number($('simVersion').value):null})});
    if(seq!==c1Paths.seq)return;
    c1Paths.shown={key,draft};
    $('simResult').innerHTML=c1RenderSimulation(result,{draft,explicit});
  }catch(error){
    if(seq!==c1Paths.seq)return;
    c1Paths.shown=null;
    $('simResult').innerHTML=`<div class="result error">${esc(error.message)}</div>`;
  }finally{if(seq===c1Paths.seq)button.disabled=false;}
  c1SimStale();
}
// 卡片上的「试算」：用当前页面草稿试算这条路径。专用路径取它绑定的第一个合作方；DEFAULT 取第一个没有专用路径的合作方
function c1SimulatePath(code){
  const path=c1Paths.rule.paths.find(p=>p.pathCode===code);
  if(!path)return;
  const bound=c1Bindings(),target=code!==C1_DEFAULT?(path.providerCodes||[])[0]:providers.map(p=>p.code).find(c=>!(bound.get(c)||[]).length);
  if(target)$('simProvider').value=target;
  c1ShowTab('simulate');
  c1RunSimulation(true,$('simRunDraft'));
}
const C1_STEP_PASS={PASS:'pass',FAIL:'fail',SKIP:'pending'};
const c1Badge=status=>`<span class="badge ${C1_STEP_PASS[status]||'pending'}">${esc(status)}</span>`;
function c1ReasonName(code){
  if(C1_REASONS[code])return C1_REASONS[code];
  return String(code||'').startsWith('C1_PATH_')?'路径条件不满足（这条路径自己的失败原因码）':'';
}
// 试算结果：命中路径 / 版本 / 路径条件 / 分类 / 排除 / 每个流转类别的准入匹配（匹配级别、规则、动作）/ 审批 / MTD / 最终原因，以及逐步过程和函数调用
function c1RenderSimulation(result,options={}){
  const draft=!!options.draft,steps=result.steps||[],flows=c1Split($('simFlows').value).length?c1Split($('simFlows').value):['STANDARD'];
  const latest=c1Paths.view?.published?.version;
  // 显式指定了版本就说明是回放（指定的恰好是最新版本时两句都写），与服务端过程里「0. 路径配置」的说法一致
  const versionNote=result.configVersion==null?'':options.explicit?(latest!=null&&result.configVersion===latest?'（按指定版本回放，即当前最新版本）':'（按指定版本回放）'):'（最新）';
  const source=draft?'当前页面草稿（未发布）':`已发布版本 v${result.configVersion??'—'}${versionNote}`;
  const find=name=>steps.find(s=>s.step===name);
  const pathSteps=flows.map(flow=>find('2. 路径条件 '+flow)).filter(Boolean);
  // 配置损坏（没有条件树、无法编译）不是「条件不满足」：摘要里分开写，不把配置问题说成路径条件的结论
  const broken=result.reasonCode==='C1_CONFIG_BROKEN';
  const treeWord=status=>status==='PASS'?'条件满足':broken?'配置损坏':'条件不满足';
  const treeResult=!result.pathCode?'未选中路径':!pathSteps.length?'未执行到':pathSteps.every(s=>s.status==='PASS')?'✓ 条件满足'
    :broken?'✕ 配置损坏（没有条件树或无法编译）':'✕ 条件不满足';
  const exclusion=find('3. 客户排除');
  const exclusionText=result.exclusionRecordId?`✕ 已排除（有效排除记录 #${esc(result.exclusionRecordId)}）`:!exclusion?'未执行到':exclusion.status==='PASS'?'✓ 不在有效排除名单中':'✕ 排除检查失败';
  const status=result.classificationStatus,classStep=find('3. 客户分类');
  // 分类状态在「1. 客户档案」就已读到，但「3. 客户分类」这一步没执行到时不能显示成通过
  const classification=!status?'未执行到':!classStep?`未执行到（档案分类状态 ${esc(status)}）`:classStep.status==='PASS'?`✓ ${esc(status)}`:`✕ ${esc(status)}（仅 COMPLETE 可继续）`;
  const pathText=result.pathCode?`${result.defaultPathHit?'<span class="badge pending">命中 DEFAULT 路径</span> ':''}<b>${esc(c1PathLabel(result.pathCode,result.pathName))}</b>`:'—';
  const kv=(label,value,note)=>`<div class="kv"><span class="caps">${label}</span><div>${value}</div>${note?`<div class="muted">${note}</div>`:''}</div>`;
  const summary=`<div class="simsummary">
    ${kv('合作方 providerCode',`<b>${esc(result.providerCode||$('simProvider').value)}</b>`)}
    ${kv('命中路径 pathCode',pathText,result.pathCode?(result.defaultPathHit?'没有任何启用的专用路径绑定该合作方':'该合作方由这条启用的专用路径负责'):'路径选择未通过，见校验过程')}
    ${kv('路径优先级',result.pathPriority??'—','只决定列表顺序，不参与选路')}
    ${kv('规则版本',draft?'草稿（无版本号）':`v${esc(result.configVersion??'—')}`,esc(source))}
    ${kv('路径条件树（每个流转类别）',treeResult,pathSteps.map(s=>`${esc(s.step.slice('2. 路径条件 '.length))}：${treeWord(s.status)}`).join(' · '))}
    ${kv('客户分类',classification)}
    ${kv('客户排除',exclusionText)}
    ${kv('最终原因',`<b>${esc(result.reasonCode)}</b>`,esc(c1ReasonName(result.reasonCode)))}
  </div>`;
  // 准入匹配：每个流转类别一行（命中规则所在的匹配级别、规则编号与范围、动作），后面跟这一类别的审批与 MTD 结果；没有执行到的类别标明「未执行到」
  const decisions=result.appetiteDecisions||[];
  const cell=s=>!s?'<span class="muted">未执行到</span>':`${c1Badge(s.status)} <span class="muted">${esc(s.detail)}</span>`;
  const appetiteRows=flows.map(flow=>{
    const d=decisions.find(x=>x.flowCategory===flow);
    return `<tr><td><b>${esc(flow)}</b></td><td>${!d?'<span class="muted">未执行到</span>':d.matchLevel?`${esc(C1_LEVEL_NAMES[d.matchLevel]||d.matchLevel)}<br><span class="muted">${esc(d.matchLevel)}</span>`:'<span class="badge fail">无匹配</span>'}</td>
      <td>${d?.ruleId?`#${esc(d.ruleId)}<br><span class="muted">${esc(d.currency||'')}/${esc(d.bankCountry||'')}</span>`:'—'}</td>
      <td>${d?.action?`<span class="badge ${esc(d.action)}">${esc(d.action)}</span>`:'—'}</td><td>${cell(find('4. 个案审批 '+flow))}</td><td>${cell(find('4. MTD '+flow))}</td></tr>`;
  }).join('');
  const appetite=`<div class="tablewrap"><table class="simtable"><thead><tr><th>流转类别</th><th>匹配级别</th><th>命中准入规则</th><th>动作</th><th>个案审批</th><th>MTD</th></tr></thead><tbody>${appetiteRows}</tbody></table></div>`
    +(decisions.length?'':'<div class="notice warn">未执行到准入匹配：判定在路径选择、路径条件、客户分类或客户排除阶段已经结束。</div>');
  const missing=result.reasonCode==='APPETITE_NOT_SET'?`<div class="notice" style="margin-top:12px">${esc(result.providerCode||'')} / ${esc(flows.join('、'))} / ${esc($('simCurrency').value)} / ${esc($('simCountry').value)}：未命中有效准入规则（也可能是规则的附加条件未命中）。请到 <a href="/provider-detail.html?code=${encodeURIComponent(result.providerCode||'')}&tab=appetites">合作方详情</a> 配置。</div>`:'';
  const trace=steps.map(x=>`<div class="trace">${c1Badge(x.status)}<b style="margin-left:8px">${esc(x.step)}</b><div class="muted" style="margin-top:4px">${esc(x.detail)}</div></div>`).join('');
  return `<div class="result ${result.passed?'':'error'}">${result.passed?'✓ C1 校验通过':'✕ C1 校验失败'}</div>
    <p class="caps">原因码：${esc(result.reasonCode)}${result.matchedRuleId?` · 命中准入规则 #${esc(result.matchedRuleId)} · 动作 ${esc(result.matchedRuleAction||'—')}`:''}</p>
    <div class="notice">数据来源：${esc(source)} · 只读试算，不写任何数据、不占用 MTD${draft?'；草稿试算不代表线上判定（线上只认已发布版本）':''}</div>${missing}
    <h2 style="margin:15px 0 8px">判定摘要</h2>${summary}
    <h2 style="margin:15px 0 8px">准入规则匹配（APPETITE_MATCH 四级匹配，全部流转类别）</h2>${appetite}
    <h2 style="margin:15px 0 4px">校验过程</h2>${trace}${c1FunctionCallsHtml(result.functionCalls)}`;
}

// ---------- 历史版本与审计 ----------

// 已发布版本的路径清单（只读）：历史摘要带服务端描述好的条件文本，版本明细带条件树（页面上描述）
function c1PathTable(paths){
  return `<div class="tablewrap"><table class="c1-table"><thead><tr><th>路径</th><th>名称</th><th>绑定合作方</th><th>优先级</th><th>状态</th><th>失败原因码</th><th>条件树</th></tr></thead><tbody>${(paths||[]).map(p=>`<tr class="${p.enabled===false?'muted':''}">
    <td><b>${esc(p.pathCode)}</b>${p.version!=null?`<br><span class="muted">v${esc(p.version)}</span>`:''}</td><td>${esc(p.pathName||'—')}</td>
    <td>${p.pathCode===C1_DEFAULT?'<span class="muted">不绑定（兜底）</span>':(p.providerCodes||[]).map(esc).join('、')||'<span class="muted">未绑定</span>'}</td>
    <td>${esc(p.priority??0)}</td><td>${p.enabled===false?'停用':'启用'}</td><td><span class="code" style="padding:2px 6px">${esc(p.failureReasonCode||'—')}</span></td>
    <td>${p.condition?esc(p.condition):p.conditionAst?esc(c1DescribeTree(p.conditionAst)):'<span class="muted">没有条件树</span>'}</td></tr>`).join('')}</tbody></table></div>`;
}
function c1RenderPublished(published){
  $('c1Published').innerHTML=published
    ?`<div class="notice">v${published.version} · ${esc(published.publishedBy||'—')} 发布于 ${esc(c1When(published.publishedAt))}${published.note?` · ${esc(published.note)}`:''} · C1 判定只读这一版（或按 configVersion 回放）</div>${c1PathTable(published.rule?.paths)}`
    :'<div class="notice warn">还没有已发布的路径配置：C1 对所有候选合作方返回 C1_CONFIG_MISSING（默认拒绝）。请在「合作方路径配置」保存草稿并发布。</div>';
}
function c1RenderHistory(history){
  $('c1History').innerHTML=(history||[]).map(h=>`<tr><td>v${h.version}</td><td>${esc(c1When(h.publishedAt))}<br>${esc(h.publishedBy||'—')}</td>
    <td>${(h.paths||[]).map(p=>{const marks=[p.enabled===false?'<span class="badge fail">停用</span>':'',p.pathCode!==C1_DEFAULT&&!(p.providerCodes||[]).length?'<span class="muted">（未绑定）</span>':''].filter(Boolean).join(' ');return `<b>${esc(p.pathCode)}</b>${marks?' '+marks:''}`;}).join('、')||'<span class="muted">（空）</span>'}</td>
    <td>${esc(h.note||'—')}</td><td><a data-view="${h.version}">查看明细</a><a data-replay="${h.version}">用此版本试算</a></td></tr>`).join('')
    ||'<tr><td colspan="5" class="muted">暂无历史版本</td></tr>';
}
async function c1ShowVersion(version){
  const box=$('c1VersionDetail');
  try{
    const detail=await api(`/c1/paths/versions/${version}`);
    c1Paths.version=version;
    $('c1VersionTitle').textContent=`v${detail.version} · ${esc(detail.publishedBy||'—')} · ${esc(c1When(detail.publishedAt))}`;
    box.innerHTML=c1PathTable(detail.rule?.paths);
  }catch(error){$('c1VersionTitle').textContent=`v${version}`;box.innerHTML=`<div class="err">${esc(error.message)}</div>`;}
}
// 审计记录的变更摘要：按路径编码比较发布前后的配置（新增 / 修改 / 移除）
function c1Diff(beforeJson,afterJson,operation){
  const read=(text,side)=>{try{return JSON.parse(text);}catch(_){return null;}};
  const before=read(beforeJson),after=read(afterJson);
  const legacy=node=>node&&typeof node==='object'&&node.children?'旧版全局准入条件 → DEFAULT 路径':'';
  if(!before)return `<span class="muted">${operation==='PUBLISH'?'首次发布（此前没有已发布版本）':after?.paths?'（首次写入）':'—'}</span>`;
  if(!before.paths)return `<span class="muted">${esc(legacy(before)||'—')}</span>`;
  if(!after?.paths)return '<span class="muted">—</span>';
  const key=p=>JSON.stringify({name:p.pathName,providers:[...(p.providerCodes||[])].sort(),priority:p.priority,enabled:p.enabled!==false,reason:p.failureReasonCode,tree:p.conditionAst});
  const b=new Map(before.paths.map(p=>[p.pathCode,key(p)])),a=new Map(after.paths.map(p=>[p.pathCode,key(p)]));
  const parts=[];
  after.paths.forEach(p=>{if(!b.has(p.pathCode))parts.push(`<span class="badge pass">+ ${esc(p.pathCode)}</span>`);else if(b.get(p.pathCode)!==key(p))parts.push(`<span class="badge pending">~ ${esc(p.pathCode)}</span>`);});
  before.paths.forEach(p=>{if(!a.has(p.pathCode))parts.push(`<span class="badge fail">− ${esc(p.pathCode)}</span>`);});
  return parts.join(' ')||'<span class="muted">内容未变</span>';
}
const C1_OPERATIONS={MIGRATE:'系统迁移',SAVE_DRAFT:'保存草稿',PUBLISH:'发布'};
function c1RenderAudits(audits){
  $('c1Audits').innerHTML=(audits||[]).map(a=>`<tr><td>${esc(c1When(a.operatedAt))}</td><td>${esc(C1_OPERATIONS[a.operation]||a.operation)}</td><td>${esc(a.operatorName||'—')}</td><td>${c1Diff(a.beforeJson,a.afterJson,a.operation)}</td></tr>`).join('')
    ||'<tr><td colspan="4" class="muted">暂无操作记录</td></tr>';
}
// 进入「历史版本」页签时重新读取：版本列表（新 → 旧）与审计记录
async function c1LoadHistory(){
  try{
    const [history,audits]=await Promise.all([api('/c1/paths/history'),api('/c1/paths/audits')]);
    c1RenderHistory(history);c1RenderAudits(audits);c1RenderPublished(c1Paths.view?.published);
    if(!history.some(h=>h.version===c1Paths.version))c1Paths.version=history[0]?.version||null;
    if(c1Paths.version)c1ShowVersion(c1Paths.version);
    else{$('c1VersionTitle').textContent='';$('c1VersionDetail').innerHTML='<div class="muted">还没有已发布版本。</div>';}
  }catch(error){$('c1History').innerHTML=`<tr><td colspan="5" class="err">${esc(error.message)}</td></tr>`;}
}
$('c1History').addEventListener('click',event=>{
  const button=event.target.closest('[data-view],[data-replay]');
  if(!button)return;
  const version=Number(button.dataset.replay||button.dataset.view);
  if(button.dataset.view){c1ShowVersion(version);return;}
  // 回放：用指定的已发布版本按「模拟试算」页签的要素重新判定，只用于核对，不改变当前生效版本，也不碰页面上的草稿
  $('simVersion').value=String(version);
  c1ShowTab('simulate');
  c1RunSimulation(false,$('simRunPublished'));
});

// ---------- 迁移说明 ----------

async function c1LoadMigration(){
  try{
    const m=await api('/c1/paths/migration'),hints=m.hints||[];
    let legacy='';
    if(m.legacyConditionJson){try{legacy=c1DescribeTree(c1Normalize(JSON.parse(m.legacyConditionJson)));}catch(_){legacy='（旧版条件无法读取）';}}
    const panel=`<div class="notice ${m.pendingReview?'warn':''}"><b>${m.migrated?'已迁移':'尚未迁移'}</b>：${m.legacyConfigured?`旧版全局准入条件（版本 ${esc(m.legacyVersionNo??'—')}）已原样转为 v1 的 DEFAULT 路径（只读，已发生的决策按它回放）；草稿是去掉旧版布尔条件后的版本，确认后发布。`:'旧版没有配置全局准入条件：路径配置从空白草稿开始，发布前 C1 按 C1_CONFIG_MISSING 拒绝全部候选合作方。'}</div>`
      +(hints.length?`<ul class="hintlist">${hints.map(h=>`<li><span class="c1-badge">${esc(h.code)}</span> ${esc(h.message)}</li>`).join('')}</ul>`:'<div class="muted">没有待确认的迁移提示（第一次发布后提示自动清空）。</div>')
      +(legacy?`<details style="margin-top:8px"><summary class="muted">旧版全局准入条件（只读，不再参与新版本的判定）</summary><div class="code" style="margin-top:6px">${esc(legacy)}</div></details>`:'');
    $('c1MigrationHints').innerHTML=panel;
    // 待确认的迁移提示在「合作方路径配置」页签（人工核对、发布的地方）再显示一份；第一次发布后随提示一起消失
    const mirror=$('c1MigrationHintsPaths');
    mirror.innerHTML=m.pendingReview&&hints.length?panel:'';
    mirror.classList.toggle('hidden',!(m.pendingReview&&hints.length));
  }catch(error){$('c1MigrationHints').innerHTML=`<div class="err">${esc(error.message)}</div>`;}
}

// ---------- 事件 ----------

$('c1PathAdd').onclick=c1AddPath;
$('c1PathRefresh').onclick=()=>{
  if(c1Paths.dirty&&!confirm('页面上的修改还没有保存，刷新会丢弃这些修改。确定刷新？'))return;
  c1LoadPathConfig();
};
$('c1PathCheck').onclick=c1CheckPathDraft;
$('c1PathCompile').onclick=c1CompilePaths;
$('c1PathSave').onclick=c1SavePathDraft;
$('c1PathPublish').onclick=c1PublishPaths;
$('simRunPublished').onclick=()=>c1RunSimulation(false,$('simRunPublished'));
$('simRunDraft').onclick=()=>c1RunSimulation(true,$('simRunDraft'));
$('tabSimulate').addEventListener('input',c1SimStale);
$('tabSimulate').addEventListener('change',c1SimStale);
