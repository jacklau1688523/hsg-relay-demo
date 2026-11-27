(function () {
    const host = document.querySelector('[data-check-switch]');
    if (!host) return;
    const code = host.dataset.checkSwitch;
    host.innerHTML = `<div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <label style="display:flex;align-items:center;gap:9px;font-weight:700;cursor:pointer">
            <input id="stageCheckEnabled" type="checkbox" checked style="width:auto">启用 ${code} 校验
        </label><span id="stageCheckStatus" class="muted">正在读取配置…</span>
    </div><div class="muted" style="margin-top:6px">关闭后，独立模拟与综合路由均跳过 ${code} 过滤；其他校验项保持各自设置。</div>`;
    const checkbox = host.querySelector('#stageCheckEnabled');
    const status = host.querySelector('#stageCheckStatus');
    const url = '/api/check-switches/' + code;
    checkbox.disabled = true;
    fetch(url).then(async response => {
        if (!response.ok) throw new Error('读取校验开关失败');
        return response.json();
    }).then(state => {
        checkbox.checked = state.enabled;
        status.textContent = state.enabled ? '当前开启' : '当前关闭';
        checkbox.disabled = false;
    }).catch(error => { status.textContent = error.message; });
    checkbox.addEventListener('change', async () => {
        const desired = checkbox.checked;
        checkbox.disabled = true;
        status.textContent = '保存中…';
        try {
            const response = await fetch(url, {
                method: 'PUT', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({enabled: desired})
            });
            if (!response.ok) throw new Error('保存校验开关失败');
            const saved = await response.json();
            checkbox.checked = saved.enabled;
            status.textContent = saved.enabled ? '已开启，模拟与路由将执行校验' : '已关闭，模拟与路由将跳过校验';
        } catch (error) {
            checkbox.checked = !desired;
            status.textContent = error.message;
        } finally { checkbox.disabled = false; }
    });
})();
