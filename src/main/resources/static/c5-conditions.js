'use strict';
/**
 * 参数化条件树编辑器：一个实例编辑一棵条件树（GROUP / FIELD / FUNCTION），节点结构与服务端 ExpressionService.Node 一致。
 *   GROUP    {type:'GROUP', logic:'AND'|'OR', children:[...]}
 *   FIELD    {type:'FIELD', field, operator, value}
 *   FUNCTION {type:'FUNCTION', functionCode, arguments:{参数:绑定}, operator, value}
 * 字段清单、函数目录、适用规则编码都由调用方传入（C5 评分组用元数据中心里适用规则含 C5 的字段和函数库里可用于 C5 的函数），
 * 这里不写死任何字段或函数。条件树的编译和校验在服务端，本文件只负责编辑和生成可读文本。
 *
 *   const editor = createConditionEditor({container, ruleCode:'C5', fields, catalog, onChange});
 *   editor.get()                      当前的条件树（根条件组）
 *   editor.set(tree)                  载入条件树并重绘
 *   editor.setSources({fields, catalog})  更新字段清单 / 函数目录（规则发布后元数据可能变化）
 *   editor.summary(tree)              可读文本，例如 (delayBusinessDays = 0)
 *   editor.isValid()                  页面级完整性：字段、函数、必填参数都已填写（结构是否合法仍以服务端校验为准）
 *
 * 依赖页面全局的 esc（HTML 转义）。
 */
function createConditionEditor(options) {
    const container = typeof options.container === 'string' ? document.getElementById(options.container) : options.container;
    if (!container) throw new Error('条件编辑器缺少容器');
    const ruleCode = options.ruleCode || '';
    const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
    const MAX_CHILDREN = 20, MAX_DEPTH = 5;
    let fields = options.fields || [];
    let catalog = options.catalog || {functions: [], paths: []};
    let root = null;
    // 载入的根条件本身不是条件组（单个字段 / 函数条件）：编辑时包一层条件组，读回时还原，不编辑就不会产生差别
    let bareLeaf = false;

    const split = value => String(value || '').split(',').map(x => x.trim()).filter(Boolean);
    const functions = () => (catalog && catalog.functions) || [];
    const fieldDef = code => fields.find(f => f.fieldCode === code);
    // 可用字段完全由元数据中心决定：已启用且适用规则包含本规则
    const availableFields = () => fields.filter(f => f.enabled !== false && split(f.allowedRules).includes(ruleCode));
    const operatorsOf = field => split(field && field.allowedOperators);
    const fieldOptions = field => (field && field.dataType !== 'BOOLEAN' ? split(field.valueOptions) : []);
    const defaultValue = field => !field ? '' : field.dataType === 'BOOLEAN' ? 'true'
        : ['DECIMAL', 'INTEGER'].includes(field.dataType) ? '0' : split(field.valueOptions)[0] || '';
    const defaultLeaf = () => {
        const field = availableFields()[0];
        return {type: 'FIELD', field: field ? field.fieldCode : '', operator: operatorsOf(field)[0] || 'EQ', value: defaultValue(field)};
    };
    const defaultFunction = code => {
        const fn = functions().find(x => x.functionCode === code) || functions()[0];
        if (!fn) return null;
        // 每个参数的绑定：函数库登记的默认绑定（受控取值路径，如 $candidate.providerCode）；没有默认绑定的留空由操作员填写
        const args = {};
        (fn.parameters || []).forEach(p => {
            const bound = (fn.defaultArguments || {})[p.code];
            args[p.code] = bound === undefined || bound === null ? (p.defaultBinding || '') : bound;
        });
        return {
            type: 'FUNCTION', functionCode: fn.functionCode, arguments: args,
            operator: (fn.operators || ['EQ'])[0], value: fn.defaultValue === undefined || fn.defaultValue === null ? '' : String(fn.defaultValue)
        };
    };
    const isGroup = node => !!node && (node.type === 'GROUP' || Array.isArray(node.children));
    const isFunction = node => !!node && !isGroup(node) && (node.type === 'FUNCTION' || !!node.functionCode);

    // ---------- 读写节点 ----------
    function node(path) {
        let current = root;
        for (const part of String(path || '').split('.').filter(Boolean)) current = current.children[Number(part)];
        return current;
    }
    const parentPath = path => String(path || '').split('.').filter(Boolean).slice(0, -1).join('.');
    const depthOf = path => String(path || '').split('.').filter(Boolean).length;

    function normalize(node) {
        if (isGroup(node)) {
            node.type = 'GROUP';
            node.logic = node.logic === 'OR' ? 'OR' : 'AND';
            node.children = (node.children || []).map(normalize);
            return node;
        }
        if (isFunction(node)) {
            node.type = 'FUNCTION';
            node.arguments = {...(node.arguments || {})};
            return node;
        }
        node.type = 'FIELD';
        return node;
    }
    function add(path, kind) {
        const group = node(path);
        if (group.children.length >= MAX_CHILDREN) { note(`每个条件组最多 ${MAX_CHILDREN} 项`); return; }
        if (kind === 'group' && depthOf(path) >= MAX_DEPTH) { note(`条件组最多嵌套 ${MAX_DEPTH} 层`); return; }
        if (kind === 'function' && !functions().length) { note('函数库里没有可用于本规则的函数。请先在函数库把函数的「适用规则」勾选 ' + ruleCode); return; }
        group.children.push(kind === 'group' ? {type: 'GROUP', logic: 'OR', children: [defaultLeaf()]}
            : kind === 'function' ? defaultFunction() : defaultLeaf());
        render();
    }
    function remove(path) {
        const parent = node(parentPath(path));
        if (parent.children.length === 1) { note('条件组至少保留一项；要删除请删除整个评分组'); return; }
        parent.children.splice(Number(String(path).split('.').pop()), 1);
        render();
    }
    function note(message) {
        const target = container.querySelector('[data-note]');
        if (target) target.textContent = message;
    }

    // ---------- 取值控件 ----------
    function valueControl(node, path, values, control) {
        const current = String(node.value ?? '');
        // 已保存的条件值不在当前可选值里时原样列出并标注，由操作员重新选择，不悄悄换成别的值
        const stale = values.length > 0 && !values.includes(current);
        if (stale) note(`条件值「${current}」不在当前可选值中，请重新选择`);
        const listed = stale ? [current, ...values] : values;
        if (listed.length) return `<select aria-label="条件值" data-key="value" data-path="${path}">${listed.map(v => `<option value="${esc(v)}" ${v === current ? 'selected' : ''}>${esc(v)}${stale && v === current ? '（已不在可选值中）' : ''}</option>`).join('')}</select>`;
        if (control === 'boolean') return `<select aria-label="条件值" data-key="value" data-path="${path}"><option value="true" ${current === 'true' ? 'selected' : ''}>true</option><option value="false" ${current === 'false' ? 'selected' : ''}>false</option></select>`;
        if (control === 'number') return `<input type="number" step="1" aria-label="条件值" data-key="value" data-path="${path}" data-live="1" value="${esc(current)}">`;
        if (control === 'date') return `<input type="date" aria-label="条件值" data-key="value" data-path="${path}" data-live="1" value="${esc(current)}">`;
        return `<input aria-label="条件值" data-key="value" data-path="${path}" data-live="1" value="${esc(current)}">`;
    }
    function argumentControl(fn, node, path, parameter) {
        const value = String((node.arguments || {})[parameter.code] ?? '');
        const hint = parameter.defaultBinding ? `默认 ${parameter.defaultBinding}` : (parameter.required ? '必填' : '可选');
        // 候选：本规则的受控取值路径（$candidate.providerCode 这样的写法），也可以直接填字面量
        const listId = `${container.id}-paths-${parameter.code}`.replace(/[^A-Za-z0-9_-]/g, '');
        return `<div class="c5e-arg"><label>${esc(parameter.code)} <span class="muted">${esc(parameter.type)}${parameter.required ? ' *' : ''} · ${esc(hint)}</span></label>`
            + `<input aria-label="参数 ${esc(parameter.code)}" list="${listId}" value="${esc(value)}" data-key="argument" data-code="${esc(parameter.code)}" data-path="${path}" data-live="1" ${parameter.required && !value.trim() ? 'aria-invalid="true"' : ''}>`
            + `<datalist id="${listId}">${(catalog.paths || []).map(p => `<option value="${esc('$' + p.path)}">${esc(p.label || '')}</option>`).join('')}</datalist></div>`;
    }

    // ---------- 绘制 ----------
    function renderLeaf(node, path) {
        const list = availableFields();
        let field = fieldDef(node.field);
        // 已保存条件引用的字段在元数据中心停用或不再适用本规则时，换成当前首个可用字段并提示，不悄悄保留无效字段
        if (!field || !list.includes(field)) {
            field = list[0];
            if (field) note(`字段 ${node.field || '（空）'} 已停用或不再适用于 ${ruleCode}，对应条件已换成「${field.fieldName}」，请核对`);
            node.field = field ? field.fieldCode : '';
            node.operator = operatorsOf(field)[0] || 'EQ';
            node.value = defaultValue(field);
        }
        if (!field) return '<div class="err">请先在元数据中心启用适用规则含 ' + esc(ruleCode) + ' 的字段</div>';
        const operators = operatorsOf(field);
        if (!operators.includes(node.operator)) node.operator = operators[0] || 'EQ';
        const values = node.operator !== 'IN' ? fieldOptions(field) : [];
        if (node.operator === 'IN') return `<div class="c5e-line"><select aria-label="条件字段" data-key="field" data-path="${path}">${list.map(x => `<option value="${esc(x.fieldCode)}" ${x.fieldCode === field.fieldCode ? 'selected' : ''}>${esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" data-key="operator" data-path="${path}">${operators.map(op => `<option value="${esc(op)}" ${op === node.operator ? 'selected' : ''}>${esc(op)}</option>`).join('')}</select><input aria-label="条件值" placeholder="用逗号分隔多个值" data-key="value" data-path="${path}" data-live="1" value="${esc(node.value)}"><button type="button" class="btn secondary tiny" data-act="remove" data-path="${path}" aria-label="删除条件">✕</button></div>`;
        return `<div class="c5e-line"><select aria-label="条件字段" data-key="field" data-path="${path}">${list.map(x => `<option value="${esc(x.fieldCode)}" ${x.fieldCode === field.fieldCode ? 'selected' : ''}>${esc(x.fieldName)}</option>`).join('')}</select><select aria-label="比较方式" data-key="operator" data-path="${path}">${operators.map(op => `<option value="${esc(op)}" ${op === node.operator ? 'selected' : ''}>${esc(op)}</option>`).join('')}</select>${valueControl(node, path, values, field.dataType === 'BOOLEAN' ? 'boolean' : field.dataType === 'DATE' ? 'date' : ['INTEGER', 'DECIMAL'].includes(field.dataType) ? 'number' : 'text')}<button type="button" class="btn secondary tiny" data-act="remove" data-path="${path}" aria-label="删除条件">✕</button></div>`;
    }
    function renderFunction(node, path) {
        const list = functions();
        // 函数在函数库里被停用或不再适用本规则时提示，由操作员换函数或删除该条件；这里不替操作员改条件
        const fn = list.find(x => x.functionCode === node.functionCode);
        if (!fn) return `<div class="c5e-line"><span class="err">函数 ${esc(node.functionCode || '（空）')} 已停用或不再适用于 ${esc(ruleCode)}，请更换或删除这条条件</span><button type="button" class="btn secondary tiny" data-act="remove" data-path="${path}" aria-label="删除条件">✕</button></div>`;
        const operators = fn.operators && fn.operators.length ? fn.operators : ['EQ', 'NE'];
        if (!operators.includes(node.operator)) node.operator = operators[0];
        const control = fn.returnType === 'BOOLEAN' ? 'boolean' : ['INTEGER', 'DECIMAL'].includes(fn.returnType) ? 'number' : 'text';
        return `<div class="c5e-fn"><div class="c5e-line"><span class="c5e-fnname">ƒ</span><select aria-label="函数" data-key="functionCode" data-path="${path}">${list.map(x => `<option value="${esc(x.functionCode)}" ${x.functionCode === fn.functionCode ? 'selected' : ''}>${esc(x.functionName)}（${esc(x.functionCode)}）</option>`).join('')}</select><select aria-label="比较方式" data-key="operator" data-path="${path}">${operators.map(op => `<option value="${esc(op)}" ${op === node.operator ? 'selected' : ''}>${esc(op)}</option>`).join('')}</select>${valueControl(node, path, [], control)}<button type="button" class="btn secondary tiny" data-act="remove" data-path="${path}" aria-label="删除条件">✕</button></div>`
            + `<div class="c5e-args">${(fn.parameters || []).map(p => argumentControl(fn, node, path, p)).join('') || '<div class="muted">该函数没有参数</div>'}</div>`
            + `<div class="muted">返回 ${esc(fn.returnType)} · 来源 ${esc(fn.dataSource || '—')}${fn.usagePolicy === 'REQUIRED' ? ' · 系统必检' : ''}</div></div>`;
    }
    function renderGroup(group, path, depth) {
        const logic = group.logic === 'OR' ? 'OR' : 'AND';
        group.logic = logic;
        const head = `<div class="c5e-head"><strong>${depth ? '子条件组' : '条件组'}（${group.children.length} 项）</strong><select aria-label="条件组关系" data-key="logic" data-path="${path}"><option value="AND" ${logic === 'AND' ? 'selected' : ''}>全部满足 AND</option><option value="OR" ${logic === 'OR' ? 'selected' : ''}>任一满足 OR</option></select>${depth ? `<button type="button" class="btn secondary tiny" data-act="remove" data-path="${path}" aria-label="删除子条件组">✕ 删除子条件组</button>` : ''}</div>`;
        const body = group.children.map((child, index) => {
            const childPath = path ? `${path}.${index}` : String(index);
            return `${index ? `<div class="c5e-join">${logic}</div>` : ''}${isGroup(child) ? renderGroup(child, childPath, depth + 1) : isFunction(child) ? renderFunction(child, childPath) : renderLeaf(child, childPath)}`;
        }).join('');
        return `<div class="c5e-group${depth ? ' c5e-nested' : ''}">${head}${body}<div class="actions"><button type="button" class="btn secondary tiny" data-act="add" data-kind="leaf" data-path="${path}">＋ 添加条件</button><button type="button" class="btn secondary tiny" data-act="add" data-kind="function" data-path="${path}" ${functions().length ? '' : `title="函数库里没有可用于 ${esc(ruleCode)} 的函数"`}>＋ 添加函数</button><button type="button" class="btn secondary tiny" data-act="add" data-kind="group" data-path="${path}">▧ 添加子条件组</button></div></div>`;
    }
    function render() {
        if (!root) root = {type: 'GROUP', logic: 'AND', children: [defaultLeaf()]};
        container.innerHTML = `<div class="c5e-note muted" data-note role="status"></div>` + renderGroup(root, '', 0);
        onChange();
    }

    // ---------- 事件（委托到容器，重绘后不用重新绑定） ----------
    container.addEventListener('click', event => {
        const button = event.target.closest('[data-act]');
        if (!button || !container.contains(button)) return;
        if (button.dataset.act === 'remove') remove(button.dataset.path);
        else if (button.dataset.act === 'add') add(button.dataset.path, button.dataset.kind);
    });
    container.addEventListener('change', event => {
        const control = event.target.closest('[data-key]');
        if (!control || !container.contains(control)) return;
        const target = node(control.dataset.path), key = control.dataset.key;
        if (!target) return;
        target[key] = control.value;
        if (key === 'field') {                                   // 换字段：运算符和条件值随字段定义走
            const field = fieldDef(control.value);
            target.operator = operatorsOf(field)[0] || 'EQ';
            target.value = defaultValue(field);
        }
        if (key === 'functionCode') {                            // 换函数：参数绑定、运算符、条件值都按新函数重建
            const fresh = defaultFunction(control.value);
            Object.keys(target).forEach(k => delete target[k]);
            Object.assign(target, fresh);
        }
        if (key === 'operator' && !fieldOptions(fieldDef(target.field)).length && fieldDef(target.field) && fieldDef(target.field).dataType !== 'BOOLEAN' && fieldDef(target.field).dataType !== 'DATE' && !['INTEGER', 'DECIMAL'].includes(fieldDef(target.field).dataType) && !isGroup(target) && !isFunction(target)) {
            // 从 IN 切回其它比较方式时没有预设值，保留已填内容
        }
        if (['field', 'functionCode', 'operator', 'logic'].includes(key)) render();   // 控件种类会变化，需要重绘
        else onChange();
    });
    // 文本 / 数字 / 日期输入和函数参数：只更新数据不重绘，输入框不会失焦
    container.addEventListener('input', event => {
        const control = event.target.closest('[data-live]');
        if (!control || !container.contains(control)) return;
        const target = node(control.dataset.path);
        if (!target) return;
        if (control.dataset.key === 'argument') {
            target.arguments = {...(target.arguments || {}), [control.dataset.code]: control.value};
            control.removeAttribute('aria-invalid');
        } else target.value = control.value;
        onChange();
    });

    // ---------- 可读文本 ----------
    const show = value => value === true ? 'true' : value === false ? 'false' : value === undefined || value === null || value === '' ? '（未填）' : String(value);
    function summarize(node) {
        if (!node) return '（空）';
        if (isGroup(node)) return `(${node.children.map(summarize).join(node.logic === 'OR' ? ' OR ' : ' AND ')})`;
        if (isFunction(node)) {
            const fn = functions().find(x => x.functionCode === node.functionCode);
            return `${fn ? fn.functionName : node.functionCode || '（未选择函数）'}(${Object.keys(node.arguments || {}).map(code => `${code}=${show(node.arguments[code])}`).join(', ')}) ${node.operator || ''} ${show(node.value)}`;
        }
        const field = fieldDef(node.field);
        const value = field && field.dataType === 'BOOLEAN' ? show(node.value) : show(node.value);
        return `${field ? field.fieldName : node.field || '（未选择字段）'} ${node.operator || ''} ${value}`;
    }

    // ---------- 对外 ----------
    return {
        get() {
            if (!root) return null;
            const copy = JSON.parse(JSON.stringify(root));
            return bareLeaf && copy.logic === 'AND' && copy.children.length === 1 ? copy.children[0] : copy;
        },
        set(tree) {
            const source = tree ? JSON.parse(JSON.stringify(tree)) : defaultLeaf();
            bareLeaf = !isGroup(source);
            root = normalize(bareLeaf ? {type: 'GROUP', logic: 'AND', children: [source]} : source);
            if (!Array.isArray(root.children) || !root.children.length) root.children = [defaultLeaf()];   // 空的条件组至少给一项
            render();
        },
        setSources(sources) {
            if (sources.fields) fields = sources.fields;
            if (sources.catalog) catalog = sources.catalog;
            if (root) render();
        },
        summary(tree) { return summarize(tree || root); },
        // 页面级完整性：每个叶子都有字段，函数节点的必填参数都已填写
        isValid() {
            let valid = true;
            (function walk(node) {
                if (!node) return;
                if (isGroup(node)) { (node.children || []).forEach(walk); return; }
                if (isFunction(node)) {
                    const fn = functions().find(x => x.functionCode === node.functionCode);
                    if (!fn) valid = false;
                    (fn ? fn.parameters || [] : []).forEach(p => {
                        if (p.required && !String((node.arguments || {})[p.code] ?? '').trim()) valid = false;
                    });
                    return;
                }
                if (!availableFields().some(f => f.fieldCode === node.field)) valid = false;
            })(root);
            return valid;
        },
        get fields() { return fields; },
        get catalog() { return catalog; }
    };
}
