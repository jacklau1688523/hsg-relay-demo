'use strict';
// C5 速度评分规则页签：评分组（草稿）编辑、校验、模拟试算、保存草稿、发布、历史版本回放、审计。
// 页面只编辑「草稿」；已发布版本不可修改，单独分区只读展示。分数和条件是否合法以服务端校验为准。
const score = {view: null, fields: [], catalog: {functions: [], paths: []}, entries: [], seq: 0, publishers: [], dirty: false, busy: false};
const CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$/;
const publishedRule = () => (score.view && score.view.published ? score.view.published.rule : null) || {groups: [], scoreRange: {min: 0, max: 100}};
const scoreRange = () => publishedRule().scoreRange || {min: 0, max: 100};

// ---------- 草稿 ↔ 页面 ----------
function scoreReadDraft() {
    const source = score.view && score.view.draft && score.view.draft.rule ? score.view.draft.rule : publishedRule();
    score.entries = ((source && source.groups) || []).map(g => ({key: ++score.seq, data: JSON.parse(JSON.stringify(g))}));
}
// 页面上的评分组 → 提交给服务端的规则 JSON。priority 就是卡片顺序（从 1 开始）；草稿不带 version
function scoreRuleJson() {
    return {
        ruleCode: 'C5', ruleType: 'SCORE', matchMode: 'FIRST_MATCH', scoreRange: scoreRange(), enabled: true,
        groups: score.entries.map((entry, index) => {
            const g = entry.data;
            return {
                groupCode: String(g.groupCode || '').trim(), name: String(g.name || '').trim(), priority: index + 1,
                condition: entry.editor ? entry.editor.get() : g.condition,
                result: {type: 'SCORE', score: Number(g.result.score)}, enabled: g.enabled !== false
            };
        })
    };
}
// 页面与「已保存的草稿」是否有差别（同一套整理规则：按优先级排序、不带 version、只比较业务字段）
function canonical(value) {
    if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
    if (!value || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
    return '{' + Object.keys(value).filter(k => k !== 'version' && k !== 'priority').sort()
        .map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
}
function scoreDirty() {
    const saved = score.view && score.view.draft ? score.view.draft.rule : null;
    return !saved || canonical(scoreRuleJson()) !== canonical(saved);
}
function scoreMarkDirty() {
    score.dirty = scoreDirty();
    $('scoreState').innerHTML = score.dirty ? '<span class="badge warn">有未保存的修改</span>'
        : score.view && score.view.draftChanged ? '<span class="badge warn">草稿未发布</span>' : '<span class="badge">草稿与已发布一致</span>';
}

// ---------- 渲染 ----------
// 卡片上的标识：与已发布版本里的同一个评分组比较（新增 / 已修改 / 一致）
const scoreTagText = g => {
    const published = publishedRule().groups.find(p => p.groupCode === g.groupCode);
    return !published ? '新增组 · 草稿' : canonical(g) === canonical(published) ? '与已发布一致' : '草稿已修改';
};
const scoreTag = g => `<span data-roletag="1" class="badge${scoreTagText(g) === '与已发布一致' ? '' : ' warn'}">${scoreTagText(g)}</span>`;
function scoreCard(entry, index) {
    const g = entry.data, tag = scoreTag(g);
    const range = scoreRange();
    return `<div class="groupcard${scoreTagText(g) === '草稿已修改' ? ' changed' : ''}${g.enabled === false ? ' off' : ''}" data-key="${entry.key}">
    <div class="ghead">
        <span class="gprio">${index + 1}</span>
        <div class="field"><label>评分组编码</label><input aria-label="评分组编码" value="${esc(g.groupCode)}" maxlength="64" data-g="groupCode" ${scoreTagText(g) !== '新增组 · 草稿' ? 'readonly title="已发布的评分组以编码对应各版本，编码不可改"' : ''}></div>
        <div class="field"><label>评分组名称</label><input aria-label="评分组名称" value="${esc(g.name)}" maxlength="80" data-g="name"></div>
        <div class="field gscore"><label>分数（${range.min}–${range.max}）</label><input aria-label="分数" type="number" min="${range.min}" max="${range.max}" step="1" value="${esc(g.result.score)}" data-g="score"></div>
        <label class="gcheck"><input type="checkbox" data-g="enabled" ${g.enabled !== false ? 'checked' : ''}> 启用</label>
        ${tag}
        <div class="gtools">
            <button type="button" class="btn secondary tiny" data-card="up" ${index === 0 ? 'disabled' : ''} aria-label="上移">↑</button>
            <button type="button" class="btn secondary tiny" data-card="down" ${index === score.entries.length - 1 ? 'disabled' : ''} aria-label="下移">↓</button>
            <button type="button" class="btn secondary tiny" data-card="remove">删除</button>
        </div>
    </div>
    <div class="cond"></div>
    <div class="muted">可读条件：<span data-summary="1"></span></div>
</div>`;
}
// 卡片标识随编辑即时更新：与已发布一致 / 草稿已修改 / 新增组（不需要重绘整张卡片）
const scoreSetTag = entry => {
    entry.el.querySelector('[data-roletag]').outerHTML = scoreTag(entry.data);
    entry.el.classList.toggle('changed', scoreTagText(entry.data) === '草稿已修改');
};
function scoreRenderGroups() {
    $('scoreGroups').innerHTML = score.entries.map(scoreCard).join('') || '<div class="notice warn">草稿里没有评分组。发布前至少需要一个已启用的评分组，否则所有候选都得不到分数。</div>';
    score.entries.forEach((entry, index) => {
        entry.el = $('scoreGroups').children[index];
        entry.setTag = () => scoreSetTag(entry);
        entry.editor = createConditionEditor({
            container: entry.el.querySelector('.cond'), ruleCode: 'C5', fields: score.fields, catalog: score.catalog,
            onChange: () => {
                entry.data.condition = entry.editor.get();          // 条件树的改动随时写回评分组
                entry.el.querySelector('[data-summary]').textContent = entry.editor.summary();
                entry.setTag();
                scoreMarkDirty();
            }
        });
        entry.editor.set(entry.data.condition);
    });
    // 草稿里删掉的已发布评分组单独列出，避免以为「删除」会改动已发布版本
    const removed = publishedRule().groups.filter(p => p.enabled !== false && !score.entries.some(e => e.data.groupCode === p.groupCode));
    if (removed.length) $('scoreGroups').insertAdjacentHTML('beforeend', `<div class="notice warn">草稿中已删除：${removed.map(p => esc(p.name) + '（' + esc(p.groupCode) + '）').join('、')}。只影响草稿；已发布版本不变，发布新版本后才生效。</div>`);
    scoreMarkDirty();
}
function scoreRenderState() {
    const v = score.view;
    if (!v) return;
    const p = v.published;
    $('scoreMeta').textContent = `${v.ruleType} · 首个命中 FIRST_MATCH`;
    $('scoreEffective').textContent = p ? `v${p.version} · 生效自 ${esc(p.effectiveFrom ? p.effectiveFrom.replace('T', ' ') : '—')}${v.publishedAhead ? '（尚未到生效时间）' : ''}` : '尚无已发布版本';
    const rows = (rule, draft) => (rule.groups || []).map(g => `<tr class="${g.enabled === false ? 'muted' : ''}"><td class="num">${g.priority}</td><td><strong>${esc(g.name)}</strong><br><span class="muted">${esc(g.groupCode)}</span></td><td>${esc(scoreText(g.condition))}</td><td class="num">${g.result ? g.result.score : '—'}</td><td>${g.enabled === false ? '停用' : '启用'}</td></tr>`).join('');
    $('scorePublished').innerHTML = p ? `<div class="tablewrap"><table><thead><tr><th>优先级</th><th>评分组</th><th>条件</th><th>分数</th><th>状态</th></tr></thead><tbody>${rows(p.rule)}</tbody></table></div>` : '<div class="notice">还没有已发布的评分规则：C5 对所有候选都标记为「评分规则未发布」，不给分，也不剔除候选。</div>';
    $('scoreHistory').innerHTML = (v.history || []).map(h => `<tr><td>v${h.version}</td><td>${esc(h.effectiveFrom ? h.effectiveFrom.replace('T', ' ') : '—')}</td><td>${esc(h.publishedAt ? h.publishedAt.replace('T', ' ') : '—')}<br>${esc(h.publishedBy || '—')}</td><td>${(h.groups || []).map(g => `${esc(g.name)} <b>${g.score}</b>分${g.enabled ? '' : '（停用）'}`).join('<br>')}</td><td>${esc(h.note || '—')}</td><td><button type="button" class="btn secondary tiny" data-replay="${h.version}">用此版本回放</button></td></tr>`).join('') || '<tr><td colspan="6" class="muted">暂无历史版本</td></tr>';
}
// 只读展示用的条件文本（不依赖编辑器）
function scoreText(node) {
    if (!node) return '—';
    const field = score.fields.find(f => f.fieldCode === node.field);
    if (Array.isArray(node.children)) return `(${node.children.map(scoreText).join(node.logic === 'OR' ? ' 或 ' : ' 且 ')})`;
    if (node.functionCode) return `${node.functionCode}(${Object.keys(node.arguments || {}).map(k => `${k}=${node.arguments[k]}`).join(', ')}) ${node.operator || ''} ${node.value ?? ''}`;
    return `${field ? field.fieldName : node.field || '—'} ${node.operator || ''} ${node.value ?? ''}`;
}
async function scoreLoadAudits() {
    try {
        const audits = await api('/c5/score-rule/audits');
        $('scoreAudits').innerHTML = audits.map(a => `<tr><td>${esc(a.operatedAt.replace('T', ' '))}</td><td>${a.operation === 'PUBLISH' ? '发布' : '保存草稿'}</td><td>${esc(a.operator)}</td><td>${esc(a.version)}</td></tr>`).join('') || '<tr><td colspan="4" class="muted">暂无操作记录</td></tr>';
    } catch (error) {$('scoreAudits').innerHTML = `<tr><td colspan="4" class="err">${esc(error.message)}</td></tr>`;}
}
function scoreApplyView(view) {
    score.view = view;
    scoreReadDraft();
    scoreRenderGroups();
    scoreRenderState();
    if (typeof c5RenderScoreCards === 'function') c5RenderScoreCards(view.published);
    scoreLoadAudits();
}

// ---------- 页面级检查（服务端校验仍为准） ----------
function scorePageErrors() {
    const errors = [], range = scoreRange(), seen = new Set();
    if (!score.entries.length) errors.push('至少需要一个评分组');
    score.entries.forEach((entry, index) => {
        const g = entry.data, label = `第 ${index + 1} 个评分组`;
        if (!CODE.test(String(g.groupCode || '').trim())) errors.push(`${label}：编码需为 2–64 位字母、数字、_ 或 -`);
        if (seen.has(g.groupCode)) errors.push(`${label}：编码 ${g.groupCode} 重复`);
        seen.add(g.groupCode);
        if (!String(g.name || '').trim()) errors.push(`${label}：名称不能为空`);
        const value = Number(g.result.score);
        if (g.result.score === '' || g.result.score === null || !Number.isInteger(value) || value < range.min || value > range.max)
            errors.push(`${label}：分数必须是 ${range.min}–${range.max} 的整数`);
        if (entry.editor && !entry.editor.isValid()) errors.push(`${label}：条件里有未选择的字段、已停用的函数或未填写的必填参数`);
    });
    return errors;
}
function scoreOperator() {
    const who = $('scoreOperator').value.trim();
    if (!who) throw Error('请填写操作人：保存草稿和发布都必须记录操作人');
    return who;
}
function scoreCheckHtml(check) {
    const list = (items, cls) => items.map(x => `<div class="${cls}">• ${esc(x)}</div>`).join('');
    const s = check.summary || {};
    const hits = Object.keys(s.groupHits || {}).map(k => `${k}：${s.groupHits[k]} 种`).join('、');
    return `${check.errors.length ? `<div class="err">${list(check.errors, '')}</div>` : '<div class="good">校验通过：没有阻断发布的错误。</div>'}
        ${check.warnings.length ? `<div class="notice warn"><b>提示（发布前需确认）</b>${list(check.warnings, '')}</div>` : ''}
        <div class="muted">按可枚举的取值组合检查了 ${s.probeCombinations ?? '—'} 种情况；各评分组命中次数：${esc(hits || '—')}。
            ${s.unverifiableGroups && s.unverifiableGroups.length ? `无法穷举验证的评分组：${esc(s.unverifiableGroups.join('、'))}，请用模拟试算确认它们能命中。` : ''}</div>`;
}
function scoreShowCheck(html) {$('scoreCheck').innerHTML = html;}

// ---------- 操作 ----------
async function scoreValidate() {
    const errors = scorePageErrors();
    if (errors.length) {scoreShowCheck(`<div class="err">${errors.map(e => '• ' + esc(e)).join('<br>')}</div>`); return null;}
    const check = await api('/c5/score-rule/validate', {method: 'POST', body: JSON.stringify(scoreRuleJson())});
    scoreShowCheck(scoreCheckHtml(check));
    return check;
}
$('scoreValidate').onclick = async () => {
    try {await scoreValidate();} catch (error) {scoreShowCheck(`<div class="err">${esc(error.message)}</div>`);}
};
$('scoreAdd').onclick = () => {
    if (score.entries.length >= 20) {scoreShowCheck('<div class="err">评分组最多 20 个</div>'); return;}
    let n = score.entries.length + 1, code = `C5-GROUP-${n}`;
    const taken = new Set(score.entries.map(e => e.data.groupCode));
    while (taken.has(code)) code = `C5-GROUP-${++n}`;
    score.entries.push({key: ++score.seq, data: {groupCode: code, name: '新评分组', priority: score.entries.length + 1, condition: null, result: {type: 'SCORE', score: 50}, enabled: true}});
    scoreRenderGroups();
    $('scoreGroups').children[score.entries.length - 1].scrollIntoView({block: 'center', behavior: 'smooth'});
};
$('scoreGroups').addEventListener('click', event => {
    // 只处理卡片自己的按钮（data-card）；条件编辑器内部的按钮用 data-act，由编辑器自己处理，这里不碰
    const button = event.target.closest('[data-card]');
    if (!button) return;
    const card = event.target.closest('[data-key]'), index = score.entries.findIndex(e => String(e.key) === card.dataset.key);
    if (index < 0) return;
    const act = button.dataset.card;
    if (act === 'remove') {
        const name = score.entries[index].data.name;
        if (!confirm(`从草稿删除评分组「${name}」？\n只影响草稿；已发布版本不变。`)) return;
        score.entries.splice(index, 1);
    } else if (act === 'up' && index > 0) score.entries.splice(index - 1, 0, score.entries.splice(index, 1)[0]);
    else if (act === 'down' && index < score.entries.length - 1) score.entries.splice(index + 1, 0, score.entries.splice(index, 1)[0]);
    else return;
    scoreRenderGroups();
});
// 评分组卡片上的输入（编码 / 名称 / 分数 / 启用）：只更新数据不重绘，输入框不会失焦
const scoreEdit = event => {
    const control = event.target.closest('[data-g]');
    if (!control) return;
    const entry = score.entries.find(e => String(e.key) === event.target.closest('[data-key]').dataset.key);
    if (!entry) return;
    const key = control.dataset.g;
    if (key === 'enabled') entry.data.enabled = control.checked;
    else if (key === 'score') entry.data.result.score = control.value === '' ? '' : Number(control.value);
    else entry.data[key] = control.value;
    entry.el.classList.toggle('off', entry.data.enabled === false);
    entry.setTag();
    scoreMarkDirty();
};
$('scoreGroups').addEventListener('input', scoreEdit);
$('scoreGroups').addEventListener('change', scoreEdit);     // 复选框在部分浏览器只触发 change
// 「模拟试算」按钮提交评分页签的试算表单（按钮本身不是 submit 类型）
$('scoreSimulate').onclick = () => $('scoreSimForm').reportValidity() && $('scoreSimForm').requestSubmit();
$('scoreSimForm').onsubmit = event => {
    event.preventDefault();
    const errors = scorePageErrors();
    if (errors.length) {showSimulation('scoreSimResult', errors.map(e => '• ' + esc(e)).join('<br>'), true); return;}
    runSimulation('scoreSimResult', 'scoreSimRun', '/c5/score-rule/simulate', {rule: scoreRuleJson(), input: simRequest('scoreSim', 'scoreSimAt', 'scoreSimRequested')}, $('scoreSimRequested').value);
};
$('scoreSave').onclick = async () => {
    const button = $('scoreSave');
    button.disabled = true;
    try {
        const who = scoreOperator();
        const errors = scorePageErrors();
        if (errors.length) throw Error(errors.join('；'));
        const view = await api('/c5/score-rule/draft', {method: 'PUT', body: JSON.stringify({
            rule: scoreRuleJson(), operator: who, expectedDraftVersion: score.view && score.view.draft ? score.view.draft.version : null})});
        scoreApplyView(view);
        scoreShowCheck(`<div class="good">草稿已保存（操作人 ${esc(who)}）。草稿不影响实际评分，发布后才生效。</div>`);
    } catch (error) {scoreShowCheck(`<div class="err">${esc(error.message)}</div>`);}
    finally {button.disabled = false;}
};
$('scorePublish').onclick = async () => {
    const button = $('scorePublish');
    button.disabled = true;
    try {
        const who = scoreOperator();
        if (!score.view.published && !score.view.draft) throw Error('没有可发布的草稿');
        if (score.dirty) throw Error('页面上的修改还没有保存成草稿：请先「保存草稿」，再发布');
        const check = await scoreValidate();
        if (!check || !check.ok) return;                          // 有错误：结果已显示，不进入发布
        // 发布确认：有提示项时必须勾选确认；生效时间可留空（= 发布当时）
        $('scoreCheck').insertAdjacentHTML('beforeend', `<div class="notice" id="scoreConfirm">
            <b>确认发布为新版本</b>（操作人 ${esc(who)}）。已发布版本不可修改、不可覆盖。
            <div class="field" style="margin-top:8px"><label for="scoreEffectiveFrom">生效时间（HKT，可留空 = 发布当时生效）</label>
                <input id="scoreEffectiveFrom" type="datetime-local" step="60" title="不得早于当前最新版本的生效时间"></div>
            ${check.warnings.length ? `<label class="gcheck"><input type="checkbox" id="scoreAck"> 我已阅读上面的 ${check.warnings.length} 条提示，并用模拟试算确认过覆盖范围</label>` : ''}
            <div class="actions" style="margin-top:10px"><button type="button" class="btn" id="scoreConfirmYes">确认发布</button><button type="button" class="btn secondary" id="scoreConfirmNo">取消</button></div></div>`);
        $('scoreConfirmNo').onclick = () => $('scoreConfirm').remove();
        $('scoreConfirmYes').onclick = async () => {
            const yes = $('scoreConfirmYes');
            yes.disabled = true;
            try {
                if (check.warnings.length && !$('scoreAck').checked) throw Error('请先勾选确认提示项');
                const at = $('scoreEffectiveFrom').value;
                const result = await api('/c5/score-rule/publish', {method: 'POST', body: JSON.stringify({
                    expectedDraftVersion: score.view.draft ? score.view.draft.version : null, operator: who,
                    effectiveFrom: at || null, acknowledgeCoverageGaps: !!check.warnings.length})});
                scoreApplyView(result.view);
                // 发布前的试算结果（按草稿算的）已不代表现状：提示重新试算，避免把旧结果当成发布后的评分
                showSimulation('scoreSimResult', `<div class="notice">评分规则已发布 v${result.published.version}。上面的试算结果已清除，请重新试算查看发布后的评分。</div>`, false);
                scoreShowCheck(`<div class="good">已发布 v${result.published.version}，生效时间 ${esc(String(result.published.effectiveFrom).replace('T', ' '))}（操作人 ${esc(who)}）。</div>`);
            } catch (error) {
                yes.disabled = false;
                $('scoreCheck').insertAdjacentHTML('afterbegin', `<div class="err">${esc(error.message)}</div>`);
            }
        };
    } catch (error) {scoreShowCheck(`<div class="err">${esc(error.message)}</div>`);}
    finally {button.disabled = false;}
};
$('scoreHistory').addEventListener('click', async event => {
    const button = event.target.closest('[data-replay]');
    if (!button) return;
    button.disabled = true;
    const version = button.dataset.replay;
    try {
        const r = await api(`/c5/score-rule/versions/${version}/replay`, {method: 'POST', body: JSON.stringify(simRequest('scoreSim', 'scoreSimAt', 'scoreSimRequested'))});
        showSimulation('scoreSimResult', c5RenderSimulation(r, $('scoreSimRequested').value), false);
        $('scoreSimResult').scrollIntoView({block: 'start', behavior: 'smooth'});
    } catch (error) {showSimulation('scoreSimResult', esc(error.message), true);}
    finally {button.disabled = false;}
});

// ---------- 进入页签 / 初始化 ----------
// 把截单页筋试算交易的取值带到评分页签，方便用同一笔交易对照
function c5ScoreTabShown() {
    const map = {scoreSimProvider: 'simProvider', scoreSimCurrency: 'simCurrency', scoreSimCountry: 'simCountry', scoreSimRegion: 'simRegion', scoreSimBic: 'simBic', scoreSimCharge: 'simCharge', scoreSimAt: 'at', scoreSimRequested: 'requested'};
    Object.keys(map).forEach(target => { $(target).value = $(map[target]).value; });   // 下拉框里没有对应选项时浏览器会保持原选项
}
async function c5ScoreInit(view, providers, currencies) {
    $('scoreSimProvider').innerHTML = providers.map(p => `<option value="${esc(p.code)}">${esc(p.code)} · ${esc(p.name)}</option>`).join('');
    $('scoreSimProvider').value = $('simProvider').value || 'PROV-T1';
    $('scoreSimCurrency').innerHTML = currencies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
    $('scoreSimCurrency').value = $('simCurrency').value || 'USD';
    try {score.fields = await api('/field-metadata');} catch (error) {$('scoreGroups').innerHTML = `<div class="err">字段元数据读取失败：${esc(error.message)}</div>`;}
    // 函数目录：函数库里没有可用于 C5 的函数时为空，条件编辑器只能加字段条件（函数节点入口会给提示）
    try {score.catalog = await api('/function-metadata/available?ruleCode=C5');} catch (error) {score.catalog = {functions: [], paths: []};}
    if (!(score.catalog.functions || []).length)
        $('scoreMeta').insertAdjacentHTML('afterend', '<div class="notice warn">函数库里还没有可用于 C5 的函数：如需在评分组里使用函数条件，请到函数库把函数的「适用规则」勾选 C5。</div>');
    scoreApplyView(view);
}
