'use strict';

const BAND = {
  token: () => localStorage.getItem('band_token'),
  saveToken: (t) => localStorage.setItem('band_token', t),
  /** 清登录态时**必须连身份缓存一起清**：否则同一个标签页换人登录，跳页首帧会闪出上一个人的名字。 */
  clearToken: () => {
    localStorage.removeItem('band_token');
    try { sessionStorage.removeItem('band_me'); sessionStorage.removeItem('band.navigation.v1'); sessionStorage.removeItem('band.theme.from'); } catch (e) { /* 隐私模式 */ }
    BAND.navigation?.clear();
  },

  /** 统一 fetch 封装：Bearer、401 跳登录、统一响应壳。返回 {code,message,data}。 */
  async api(path, { method = 'GET', body } = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const token = this.token();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    let res;
    try {
      res = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (e) {
      return { code: 10003, message: '无法连接服务器', data: null };
    }
    let envelope;
    try {
      envelope = await res.json();
    } catch (e) {
      envelope = { code: 10003, message: 'HTTP ' + res.status, data: null };
    }
    if (res.status === 401) {
      this.clearToken();
      if (!location.pathname.endsWith('/login.html')) location.href = '/login.html';
    }
    return envelope;
  },

  /** 请求并解包：成功返回 data；code 40002 视为"已签到"成功；其余 toast 并 throw。 */
  async must(path, opts) {
    const envelope = await this.api(path, opts);
    if (envelope.code === 0) return envelope.data;
    if (envelope.code === 40002) {
      this.toast('已签到，无需重复操作', 'success');
      return null;
    }
    this.toast(envelope.message || ('错误码 ' + envelope.code), 'danger');
    throw envelope;
  },

  /**
   * 安全地把一批条目填进 `<select>`：**先清空，再逐个建 `<option>` 用 `textContent` 填名字**。
   * ⚠️ **别改成拼 `innerHTML`**：名字来自服务端（声部名、曲名…），拼字符串会把 `</option>`
   * 之类当场拼坏、甚至执行脚本（存储型 XSS）。**全站下拉都走这里**，别再各处手拼。
   * @param {HTMLSelectElement} select
   * @param {Array<{value: (string|number), label: string}>} items
   * @param {string} [placeholder] 传了就在最前面加一条 `value=""` 的占位项（如"全部声部"）
   */
  fillSelect(select, items, placeholder) {
    select.textContent = '';
    if (placeholder !== undefined) {
      const ph = document.createElement('option');
      ph.value = '';
      ph.textContent = placeholder;
      select.appendChild(ph);
    }
    for (const it of items) {
      const opt = document.createElement('option');
      opt.value = it.value;
      opt.textContent = it.label;
      select.appendChild(opt);
    }
  },

  /**
   * 按钮圆点旋转 → 连续成勾（docs/14 §14.6.11 / #243）。
   * 用法：
   *   const m = BAND.dots(btn, { busy:'正在保存', success:'已保存', error:'保存失败' });
   *   if (!m.start()) return;        // 等待中防重复提交
   *   try { await doIt(); m.success(); } catch (e) { m.fail(); }
   * 成功由真实结果驱动；失败不画勾；减少动态效果时直接落终态。
   * 只接收纯文字按钮（图标槽由本方法插入）；已有内容的按钮返回 null，别硬套。
   */
  dots(button, labels = {}) {
    const el = typeof button === 'string' ? document.querySelector(button) : button;
    if (!el) return null;
    if (el.__bdDots) return el.__bdDots;
    if (el.children.length) return null;

    const COUNT = 28;
    const R = 10.5;
    const SPIN_MS = 820;
    const MORPH_MS = 620;
    const original = (el.textContent || '').trim();
    const L = {
      idle: original,
      busy: labels.busy || '处理中',
      stage2: labels.stage2 || labels.busy || '处理中',
      success: labels.success || '完成',
      error: labels.error || '失败',
    };
    const reduced = () => !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    el.textContent = '';
    el.classList.add('bd-btn');
    const icon = document.createElement('span');
    icon.className = 'bd-icon';
    icon.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.className = 'bd-label';
    label.textContent = L.idle;
    const maxChars = Math.max(...Object.values(L).map((s) => Array.from(s).length));
    label.style.minWidth = (maxChars * 1.25) + 'em';
    el.appendChild(icon);
    el.appendChild(label);

    const dots = [];
    const pos = [];
    for (let i = 0; i < COUNT; i++) {
      const d = document.createElement('span');
      d.className = 'bd-dot';
      icon.appendChild(d);
      dots.push(d);
      pos.push({ x: 0, y: 0, opacity: 1 });
    }

    const ringPoint = (i, angle) => {
      const a = i / COUNT * Math.PI * 2 + angle;
      return { x: Math.cos(a) * R, y: Math.sin(a) * R };
    };
    const checkPoint = (i) => {
      const f = i / (COUNT - 1);
      const short = Math.hypot(7, 7);
      const long = Math.hypot(15, 16);
      const d = f * (short + long);
      return d <= short
        ? { x: -11 + 7 * d / short, y: 7 * d / short }
        : { x: -4 + 15 * (d - short) / long, y: 7 - 16 * (d - short) / long };
    };
    const ringOpacity = (i) => .18 + .82 * Math.pow(i / (COUNT - 1), 1.4);
    const place = (i, p, opacity) => {
      pos[i] = { x: p.x, y: p.y, opacity };
      dots[i].style.transform = 'translate(' + p.x + 'px,' + p.y + 'px)';
      dots[i].style.opacity = String(opacity);
    };
    const renderIdle = () => {
      for (let i = 0; i < COUNT; i++) {
        const p = ringPoint(i, 0);
        place(i, p, ringOpacity(i));
      }
    };

    let phase = 'idle';
    let gen = 0;
    let raf = 0;
    let spinT0 = 0;
    let morphT0 = 0;
    let morphStart = null;
    let successResolve = null;
    let successPromise = null;

    const setPhase = (p) => {
      phase = p;
      el.dataset.bdPhase = p;
    };
    const setBusy = (busy) => {
      if (busy) {
        el.setAttribute('aria-busy', 'true');
        el.setAttribute('aria-disabled', 'true');
      } else {
        el.removeAttribute('aria-busy');
        el.removeAttribute('aria-disabled');
      }
    };
    const stop = () => {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
    };
    const busy = () => phase === 'pending' || phase === 'spinning' || phase === 'morphing';

    const commitSuccess = (myGen) => {
      if (myGen !== gen) return;
      stop();
      setPhase('success');
      label.textContent = L.success;
      for (let i = 0; i < COUNT; i++) place(i, checkPoint(i), 1);
      setBusy(false);
      if (successResolve) { successResolve(); successResolve = null; }
      successPromise = null;
    };
    const applyMorph = (t, myGen) => {
      if (myGen !== gen) return;
      const e = t * t * (3 - 2 * t);
      for (let i = 0; i < COUNT; i++) {
        const a = morphStart[i];
        const b = checkPoint(i);
        place(i, {
          x: a.x + (b.x - a.x) * e,
          y: a.y + (b.y - a.y) * e,
        }, a.opacity + (1 - a.opacity) * e);
      }
    };
    const morphFrame = (myGen) => (now) => {
      if (myGen !== gen) return;
      const t = Math.min(1, Math.max(0, (now - morphT0) / MORPH_MS));
      applyMorph(t, myGen);
      if (t < 1) raf = requestAnimationFrame(morphFrame(myGen));
      else commitSuccess(myGen);
    };
    const spinFrame = (myGen) => (now) => {
      if (myGen !== gen || phase !== 'spinning') return;
      const angle = (now - spinT0) / SPIN_MS * Math.PI * 2;
      for (let i = 0; i < COUNT; i++) place(i, ringPoint(i, angle), ringOpacity(i));
      raf = requestAnimationFrame(spinFrame(myGen));
    };

    const controller = {
      start() {
        if (busy()) return false;
        const myGen = ++gen;
        stop();
        successPromise = null;
        successResolve = null;
        renderIdle();
        label.textContent = L.busy;
        setPhase('pending');
        setBusy(true);
        if (!reduced()) {
          spinT0 = performance.now();
          setPhase('spinning');
          raf = requestAnimationFrame(spinFrame(myGen));
        }
        return true;
      },
      /** 两阶段操作：文件传完、业务记录还没保存时改文案，等待不中断。 */
      stage(text) {
        if (!busy()) return;
        label.textContent = text || L.stage2;
      },
      /** 待机文案会变的按钮（如「上传 N 条」）：空闲时改原文案，等待中不动。 */
      setIdle(text) {
        L.idle = String(text == null ? '' : text);
        if (busy()) return;
        label.textContent = L.idle;
        const maxChars = Math.max(...Object.values(L).map((s) => Array.from(s).length));
        label.style.minWidth = (maxChars * 1.25) + 'em';
      },
      /** 返回 Promise：成勾动画落定后 resolve（reduced 时立即）。用于“等勾播完再关表单”。 */
      success() {
        if (phase === 'success' || !busy()) return Promise.resolve();
        if (successPromise) return successPromise;   // 重复调用复用同一段动画
        const myGen = gen;
        successPromise = new Promise((resolve) => {
          successResolve = resolve;
          if (reduced()) {
            commitSuccess(myGen);
            return;
          }
          stop();
          morphStart = pos.map((p) => ({ ...p }));
          morphT0 = performance.now();
          setPhase('morphing');
          raf = requestAnimationFrame(morphFrame(myGen));
        });
        return successPromise;
      },
      fail() {
        if (!busy()) return;
        gen++;
        stop();
        setPhase('error');
        label.textContent = L.error;
        renderIdle();
        setBusy(false);
        if (successResolve) { successResolve(); successResolve = null; }
        successPromise = null;
      },
      cancel() {
        gen++;
        stop();
        setPhase('idle');
        label.textContent = L.idle;
        renderIdle();
        setBusy(false);
        if (successResolve) { successResolve(); successResolve = null; }
        successPromise = null;
      },
      isBusy: busy,
      phase: () => phase,
    };

    renderIdle();
    setPhase('idle');
    el.__bdDots = controller;
    return controller;
  },

  /** #246：波纹只接显式标记的业务按钮；不触发 click，不改业务或滚动默认动作。 */
  initRipples() {
    if (this._ripplesReady) return;
    this._ripplesReady = true;
    const active = new Map();
    let keyboardButton = null;
    const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const buttonFor = (event) => event.target.closest && event.target.closest('button[data-band-ripple]');
    const valid = (button) => button && !button.disabled && !button.closest('fieldset:disabled') && button.getAttribute('aria-disabled') !== 'true';
    const end = (key, immediately = false) => {
      const item = active.get(key);
      if (!item) return;
      active.delete(key);
      clearTimeout(item.timer);
      const remove = () => { item.grow?.cancel(); item.layer.remove(); };
      if (immediately || reduced() || !item.dot || typeof item.dot.animate !== 'function') { remove(); return; }
      item.dot.animate([{ opacity:.15 },{ opacity:0 }], { duration:170, fill:'forwards' }).finished.then(remove, remove);
    };
    const start = (button, key, x, y) => {
      end(key, true);
      if (!valid(button)) return;
      const rect = button.getBoundingClientRect();
      x = Math.max(0, Math.min(rect.width, x));
      y = Math.max(0, Math.min(rect.height, y));
      const layer = document.createElement('span');
      layer.className = 'band-ripple-layer';
      layer.setAttribute('aria-hidden', 'true');
      const dot = document.createElement('span');
      const staticPress = reduced() || typeof dot.animate !== 'function';
      dot.className = staticPress ? 'band-ripple-static' : 'band-ripple-dot';
      let grow = null;
      if (!staticPress) {
        const radius = Math.hypot(Math.max(x, rect.width-x), Math.max(y, rect.height-y));
        Object.assign(dot.style, { width:radius*2+'px', height:radius*2+'px', left:x-radius+'px', top:y-radius+'px' });
        grow = dot.animate([{ transform:'scale(0)' },{ transform:'scale(1)' }], { duration:430, easing:'cubic-bezier(.2,.7,.3,1)', fill:'forwards' });
      }
      layer.appendChild(dot);
      button.appendChild(layer);
      active.set(key, { button, layer, dot:staticPress ? null : dot, grow, x:rect.left+x, y:rect.top+y, timer:setTimeout(() => end(key), 1800) });
    };
    const clear = () => { for (const key of [...active.keys()]) end(key, true); keyboardButton = null; };
    document.addEventListener('pointerdown', (event) => {
      if (!event.isPrimary || event.button !== 0) return;
      const button = buttonFor(event);
      if (!valid(button)) return;
      const rect = button.getBoundingClientRect();
      start(button, event.pointerId, event.clientX-rect.left, event.clientY-rect.top);
    }, { passive:true });
    document.addEventListener('pointermove', (event) => {
      const item = active.get(event.pointerId);
      if (!item) return;
      const rect = item.button.getBoundingClientRect();
      if (Math.hypot(event.clientX-item.x,event.clientY-item.y)>10 || event.clientX<rect.left || event.clientX>rect.right || event.clientY<rect.top || event.clientY>rect.bottom) end(event.pointerId);
    }, { passive:true });
    document.addEventListener('pointerup', (event) => end(event.pointerId), { passive:true });
    document.addEventListener('pointercancel', (event) => end(event.pointerId), { passive:true });
    document.addEventListener('scroll', clear, { capture:true, passive:true });
    window.addEventListener('blur', clear);
    document.addEventListener('visibilitychange', () => { if (document.hidden) clear(); });
    document.addEventListener('keydown', (event) => {
      if (!['Enter',' '].includes(event.key) || event.repeat) return;
      const button = buttonFor(event);
      if (!valid(button)) return;
      keyboardButton = button;
      const rect = button.getBoundingClientRect();
      start(button, 'keyboard', rect.width/2, rect.height/2);
    });
    document.addEventListener('keyup', (event) => {
      if (!['Enter',' '].includes(event.key)) return;
      end('keyboard');
      setTimeout(() => { keyboardButton = null; }, 0);
    });
    document.addEventListener('focusout', () => end('keyboard'));
    document.addEventListener('click', (event) => {
      const button = buttonFor(event);
      if (event.detail !== 0 || keyboardButton === button || !valid(button)) return;
      const rect = button.getBoundingClientRect();
      start(button, 'assistive', rect.width/2, rect.height/2);
      end('assistive');
    });
    new MutationObserver(() => {
      for (const [key,item] of active) if (!item.button.isConnected) end(key,true);
    }).observe(document.documentElement, { childList:true, subtree:true });
  },

  toast(message, type = 'danger') {
    let host = document.getElementById('toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'toast-host';
      host.className = 'toast-container position-fixed top-0 start-50 translate-middle-x p-3';
      host.style.zIndex = 2000;
      document.body.appendChild(host);
    }
    const el = document.createElement('div');
    el.className = `toast align-items-center text-bg-${type} border-0`;
    el.innerHTML = `<div class="d-flex"><div class="toast-body"></div>
      <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button></div>`;
    el.querySelector('.toast-body').textContent = message;
    host.appendChild(el);
    const toast = new bootstrap.Toast(el, { delay: 3000 });
    toast.show();
    el.addEventListener('hidden.bs.toast', () => el.remove());
  },

  /**
   * 应用内确认层，返回 Promise<boolean>（docs/14 §14.6.4：活动详情页那三处写操作都要先确认，
   * **取消则不发请求**）。用 Bootstrap Modal——项目已自托管 bundle，Esc / 点背景 / 取消都能关，
   * z-index 1055 正好介于个人栏（500）与 toast（2000）之间。
   * 元素懒建一次、之后复用；每次调用重绑处理器（属性赋值 + once，不会攒监听）。
   */
  confirm({ title, body, ok = '确认', cancel = '取消' }) {
    return new Promise((resolve) => {
      let el = document.getElementById('confirm-modal');
      if (!el) {
        el = document.createElement('div');
        el.id = 'confirm-modal';
        el.className = 'modal fade';
        el.tabIndex = -1;
        el.innerHTML = `<div class="modal-dialog modal-sm">
          <div class="modal-content">
            <div class="modal-body">
              <p class="cf-title big my-2"></p>
              <p class="cf-body chip mb-0"></p>
            </div>
            <div class="modal-footer py-1">
              <button class="go go-ghost go-sm" data-bs-dismiss="modal" id="cf-cancel"></button>
              <button class="go go-sm" id="cf-ok"></button>
            </div>
          </div></div>`;
        document.body.appendChild(el);
      }
      el.querySelector('.cf-title').textContent = title || '';
      el.querySelector('.cf-body').textContent = body || '';
      el.querySelector('#cf-ok').textContent = ok;
      el.querySelector('#cf-cancel').textContent = cancel;

      const modal = bootstrap.Modal.getOrCreateInstance(el);
      let decided = false;
      // 取消 / Esc / 点背景都会触发 hidden → 只要没点过"确认"就一律 resolve(false)，
      // 否则调用方的 await 会永远挂着（而且"关不掉"比"发错请求"更糟）
      el.addEventListener('hidden.bs.modal', () => {
        if (!decided) resolve(false);
      }, { once: true });
      el.querySelector('#cf-ok').onclick = () => {
        decided = true;
        modal.hide();
        resolve(true);
      };
      modal.show();
    });
  },

  me: null,

  /**
   * 上一次拿到的身份（先内存、再 `sessionStorage.band_me`）——**只用于首帧抢先画**骨架里的文字与菜单。
   * ⚠️ 不是权威：`renderNav` 拿到 `/api/auth/me` 后会再用服务端结果重填一遍；鉴权始终在服务端。
   */
  cachedMe() {
    if (this.me) {
      return this.me;
    }
    try {
      const raw = sessionStorage.getItem('band_me');
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;   // 隐私模式 / 内容坏了：当作没有缓存，留空即可
    }
  },

  async requireUser(roles) {
    if (!this.token()) location.href = '/login.html';
    if (!this.me) {
      const envelope = await this.api('/api/auth/me');
      if (envelope.code !== 0) {
        // 拿不到身份（后端 500 / 断网 → 10003）就绝不能把黑幕留在屏幕上：这条路径以前直接 return，
        // 黑幕没人摘、"点任意处跳过"的监听也还没挂 → 页面全黑，只能手动刷新。
        this.disarmWelcome();
        this.toast(envelope.message || '加载失败，请刷新重试');
        return null; // 401 已在 api() 里跳转
      }
      this.me = envelope.data;
      // 身份缓存：只为"下一次跳页在**首帧**就画出菜单与姓名"（服务端始终是权威，renderNav 会重填一遍）
      try { sessionStorage.setItem('band_me', JSON.stringify(this.me)); } catch (e) { /* 隐私模式 */ }
    }
    if (roles && !roles.includes(this.me.role)) {
      location.href = '/events.html';
      return null;
    }
    return this.me;
  },

  /** 摘掉欢迎过渡黑幕。docs/14 §14.6.4 的硬要求：**任何提前退出的路径都必须摘**
      （`band-nav.js` 在首帧前就武装了它，摘的人漏一条就是全黑且无路可走）。
      标记不清——`playWelcome` 自己会清，留着才能"下一页重试"。 */
  disarmWelcome() {
    document.documentElement.classList.remove('wel-armed');
  },

  /** `Date` → 本地时区 `YYYY-MM-DD`。⚠️ **别用 `toISOString()`**——那是 UTC，东八区早上 8 点前会算成前一天。 */
  dayKey: (d = new Date()) => {
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  },

  /** 本地时区的今天 `YYYY-MM-DD`（`dayKey()` 的今天版）。 */
  localDayKey() { return this.dayKey(new Date()); },

  /**
   * 把服务端回的 ISO 时间串（`2026-10-06T09:30:00`）截成**人能读**的样子。
   * ⚠️ **只做"去 T + 按需截断"，故意不做时区换算**：库里存的就是本地墙上时间，
   * 拐去 UTC（`toISOString`）会早晚差一天——和 `localDayKey` 同一条坑。
   * @param {string} iso
   * @param {'min'|'sec'|'day'} [mode] 默认 `min`（到分，`2026-10-06 09:30`）；`sec` 到秒；`day` 只到日。
   */
  stamp(iso, mode = 'min') {
    if (!iso) return '';
    const s = String(iso).replace('T', ' ');
    if (mode === 'day') return s.slice(0, 10);
    return mode === 'sec' ? s.slice(0, 19) : s.slice(0, 16);
  },

  kindLabel: (kind) => ({ REHEARSAL: '排练', SECTION_CLASS: '声部课', DRESS_REHEARSAL: '彩排' }[kind] || kind),

  statusLabel: (s) => ({ ACTIVE: '在团', PAUSED: '休团', LEFT: '退团', GRADUATED: '毕业' }[s] || s),
  /** 当前出勤入口的共用判据；休团历史仍展示，不能用此方法清空历史统计。 */
  noAttend: (me) => !me || me.status === 'PAUSED' || !!me.isSystem
    || ['ADMIN', 'CONDUCTOR', 'HONORARY'].includes(me.role),
  roleLabel: (r) => ({ ADMIN: '管理员', LEADER: '团长', CONDUCTOR: '指挥', SECTION_LEADER: '声部长', MEMBER: '团员', HONORARY: '荣誉团员' }[r] || r),
  /** 分母为 0（指挥 / 荣誉团员等不参与考勤的人）显示 —，不能显示 0%（docs/05 §5.4）。 */
  ratePct: (rate, expected) => (expected === 0 ? '—' : Math.round(rate * 1000) / 10 + '%'),

  /** 入团天数（个人栏数字层，docs/14 §14.6.4）。两端都用 UTC 零点算——
      `new Date('2020-09-01')` 是 UTC 午夜，直接减本地今天会差一天。 */
  daysSince: (iso) => {
    const [y, m, d] = iso.split('-').map(Number);
    const now = new Date();
    const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
    return Math.max(0, Math.round((today - Date.UTC(y, m - 1, d)) / 86400000));
  },

  /** 体积显示（附件列表、演出照片都用它）。 */
  sizeText(bytes) {
    if (!bytes) {
      return '';
    }
    return bytes >= 1024 * 1024
      ? (Math.round(bytes / 1024 / 102.4) / 10) + ' MB'
      : Math.round(bytes / 1024) + ' KB';
  },

  /**
   * 上传前压缩图片（docs/07 §7.4 / `docs/08` §8.5 的**硬要求**）：canvas 缩到**长边 1920 / 质量 80%**。
   * 手机直出原图一张好几 MB，不压缩磁盘与每周备份都会被拖垮——"这类问题往往半年后才被发现"。
   * ⚠️ 服务端**仍会按上限校验**（防绕过前端直接打接口传大图），这里只是体验与容量。
   */
  async compressImage(file, maxEdge = 1920, quality = 0.8) {
    if (!file.type.startsWith('image/')) {
      return file;
    }
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size <= 2 * 1024 * 1024) {
      return file;   // 本来就小，不必重编码（重编码反而可能变大）
    }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (!blob) {
      return file;
    }
    const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
    return new File([blob], name, { type: 'image/jpeg' });
  },

  /**
   * 身份行文案：声部 · 乐器 · 角色 · 入团年份（docs/14 §14.6.4）。
   * ⚠️ **个人栏浮层与个人页共用这一个函数**——规格要求两处"同源"，
   * 各写一份必然飘（这两处就飘过一次：个人页少了入团年份）。
   */
  identityTags: (member) => [
    member.sectionName,
    member.instrument,
    BAND.roleLabel(member.role),
    member.joinedAt ? `${member.joinedAt.slice(0, 4)} 年入团` : null,
  ].filter(Boolean).join(' · '),

  /**
   * 「参演过 N 场专场」（`docs/14` §14.6.4「个人页」第 6 条，`交接单 #117`）。
   * ⚠️ **`profile.html` 与 `member-detail.html` 共用这一个函数**（与 `identityTags` 同一条规矩：
   * 两处各写一份必然飘）。调用方只负责给出那个 `<b>` 与 memberId。
   * ⚠️ 拉不到才写 `—`；**服务端回的 0 就照实显示 `0`**（"一场没参演过"是个事实）。
   */
  async mountShowcaseCount(el, memberId) {
    if (!el) {
      return;
    }
    try {
      const d = await this.must('/api/members/' + encodeURIComponent(memberId) + '/showcase-count');
      el.textContent = String(d.count);
    } catch (e) {
      el.textContent = '—';   // toast 已弹；这里只是别让那一格空着
    }
  },

  /**
   * 把全页唯一的 `AudioContext` 弄到 **`running`**。节拍器「开始」在用户手势里调它。
   * <p>
   * ⚠️ **必须在手势里同步建上下文、再 await `resume()`**：iOS / 鸿蒙上上下文可能一出生就是
   * `suspended`，而"建了就算数"是错的 —— 得等 resume 真的有结果、再核一次 state。
   * ⚠️ 失败原因写进 `_audioIssue`，由 `audioIssueText()` 翻成人话。**别把异常吞掉**：
   * 吞掉的结果就是"按钮点了没反应"（鸿蒙百度浏览器实测：上下文永远 suspended、时钟不走、无提示）。
   * @returns {Promise<AudioContext|null>} running 的上下文；拿不到就 `null`
   */
  async ensureAudio() {
    if (!this._ac) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) {
        this._audioIssue = { kind: 'no-ctor' };
        return null;
      }
      try {
        this._ac = new Ctx();
      } catch (e) {
        this._audioIssue = { kind: 'ctor', name: e && e.name, message: e && e.message };
        return null;
      }
    }
    const ac = this._ac;
    if (ac.state !== 'running') {
      try {
        await ac.resume();
      } catch (e) {
        this._audioIssue = { kind: 'resume', name: e && e.name, message: e && e.message };
      }
    }
    if (ac.state !== 'running') {
      this._audioIssue = this._audioIssue || { kind: 'suspended' };
      return null;
    }
    this._audioIssue = null;
    return ac;
  },

  /** `ensureAudio()` 失败原因的中文提示（含百度浏览器的站点声音设置路径）。 */
  audioIssueText() {
    const i = this._audioIssue || { kind: 'suspended' };
    if (i.kind === 'compat') {
      return '兼容模式没能播放点击音（' + (i.name || '被浏览器拦下') + '）。请再点一次「开始」；仍不行请检查媒体音量、静音和蓝牙耳机。';
    }
    const huaweiNext = this.isHarmonyNext();
    if (huaweiNext) {
      if (i.kind === 'no-ctor') {
        return '鸿蒙 NEXT 的浏览器不支持网页音频（Web Audio）。请点「改用兼容模式」继续。';
      }
      if (i.kind === 'ctor' || i.kind === 'tick') {
        const what = i.kind === 'ctor' ? '音频初始化失败' : '音频节点创建失败';
        return '鸿蒙 NEXT ' + what + '（' + (i.name || '未知错误') + '）。请点「改用兼容模式」再试。';
      }
      if (i.kind === 'resume' && i.name && i.name !== 'NotAllowedError') {
        return '鸿蒙 NEXT 音频没能启动（' + i.name + '）。请点「改用兼容模式」再试；也可检查媒体音量、蓝牙和站点声音权限。';
      }
      return '鸿蒙 NEXT 的浏览器没有启动网页音频（被挂起）。请点「改用兼容模式」继续；也可检查媒体音量、蓝牙和站点声音权限。';
    }
    // 官方 App 没有地址栏和站点声音权限，浏览器那套出路在里面不存在
    const inApp = /ForBandApp\//i.test(navigator.userAgent);
    if (i.kind === 'no-ctor') {
      return inApp
        ? '系统 WebView 不支持网页音频（Web Audio），节拍器响不了。请改用「选择本机文件」上传；也可更新系统的「Android System WebView」后重启 App。'
        : '这个浏览器不支持网页音频（Web Audio），节拍器响不了。请换用手机自带浏览器或 Chrome 打开这一页。';
    }
    if (i.kind === 'ctor' || i.kind === 'tick') {
      const what = i.kind === 'ctor' ? '音频初始化失败' : '音频节点创建失败';
      return inApp
        ? what + '（' + (i.name || '未知错误') + '）。请退出 App 重进再试；仍不行请更新系统 WebView。'
        : what + '（' + (i.name || '未知错误') + '）。请换用手机自带浏览器或 Chrome 再试。';
    }
    if (i.kind === 'resume' && i.name && i.name !== 'NotAllowedError') {
      return inApp
        ? '音频没能启动（' + i.name + '）。请检查媒体音量，退出 App 重进后再点「开始」。'
        : '音频没能启动（' + i.name + '）。请换用手机自带浏览器或 Chrome 再试。';
    }
    return inApp
      ? 'App 没能启动音频（被系统挂起）。请先调高媒体音量，退出 App 重进后再点「开始」。'
      : '浏览器没有允许网页发声（音频被挂起）。百度浏览器：点地址栏左侧锁图标 → 网站设置 → 声音 → 允许，刷新后再点「开始」；也可换用手机自带浏览器。';
  },

  /**
   * 节拍器的一声（练习录音页）：**用户主动发起、持续可控的声音** —— `docs/14` §14.6.6 那张表
   * 把它和"确认类声音"分开的正是这一点，也是**第四条留痕**讲的"拍点指示不算 idle 动画"的同一条界线。
   * <p>
   * ⚠️ **不受 `band_sound` 约束**（**显式例外**，不是漏写）：一个开了却不出声的节拍器，看起来就是坏的。
   * ⚠️ **复用唯一的 `BAND._ac`** —— 全页只允许一个 `AudioContext`，这里绝不 `new` 第二个。
   * ⚠️ 包络：指数起音 → **峰值保持 8ms**（#258，`docs/14` §14.6.6）→ 指数衰减（线性到 0 会爆音）。
   *    **重拍高且响、弱拍低**；兼容模式的 WAV 与这里用同一套包络（`tools/gen_metro_wavs.py`）。
   * ⚠️ **保持同步**：离线探针直接把 `_ac` 换成 `OfflineAudioContext` 调这里。正常起拍前会先过
   *    `ensureAudio()`；这里的 resume 只是兜底，且必须接住 rejection（否则控制台多一条未处理异常）。
   * @param {boolean} accent 是不是每小节的第 1 拍
   * @param {number} [when] 排在音频时钟的哪个时刻（lookahead 调度用）；不传＝马上
   * @returns {number|null} 实际排出去的时刻（秒）；拿不到音频就 `null`
   */
  tick(accent, when) {
    try {
      if (!this._ac) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) {
          this._audioIssue = { kind: 'no-ctor' };
          return null;
        }
        try {
          this._ac = new Ctx();
        } catch (e) {
          this._audioIssue = { kind: 'ctor', name: e && e.name, message: e && e.message };
          return null;
        }
      }
      const ac = this._ac;
      if (ac.state === 'suspended') {
        Promise.resolve(ac.resume()).catch(() => {});
      }
      const t = Math.max(when || 0, ac.currentTime);
      const osc = ac.createOscillator();
      const gain = ac.createGain();
      osc.type = 'sine';
      osc.frequency.value = accent ? 1600 : 1000;
      // 起手先给一个极小值再指数爬到峰值：`exponentialRamp` 不接受 0，且这样不会有"咔"的起音
      // #258：峰值**保持到 t+0.012**（8ms 保持段）再衰减 —— 只加这一段、峰值 0.90 / 0.45 不动，
      // 有效响度就上来了；兼容模式的 WAV 由 `tools/gen_metro_wavs.py` 用同一套包络合成。
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(accent ? 0.90 : 0.45, t + 0.004);
      gain.gain.setValueAtTime(accent ? 0.90 : 0.45, t + 0.012);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.06);
      // ⚠️ 别写成 `osc.connect(gain).connect(ac.destination)`：少数内核的 `connect()` 返回值靠不住
      osc.connect(gain);
      gain.connect(ac.destination);
      osc.start(t);
      osc.stop(t + 0.08);
      return t;
    } catch (e) {
      this._audioIssue = { kind: 'tick', name: e && e.name, message: e && e.message };
      return null;
    }
  },

  /** 鸿蒙 NEXT（纯血）：UA 里通常有 OpenHarmony / ArkWeb。别只匹配 HarmonyOS——4.x 也能正常用 Web Audio。 */
  isHarmonyNext() {
    return /OpenHarmony|ArkWeb/i.test(navigator.userAgent || '');
  },

  /** 节拍器音频模式：用户手动选择优先；没选过时，鸿蒙 NEXT 默认 compat，其余平台默认 webaudio。 */
  metroAudioMode() {
    try {
      const m = localStorage.getItem('band_metro_audio_mode');
      if (m === 'compat' || m === 'webaudio') return m;
    } catch (e) { /* 隐私模式 */ }
    return this.isHarmonyNext() ? 'compat' : 'webaudio';
  },

  setMetroAudioMode(mode) {
    if (mode !== 'compat' && mode !== 'webaudio') return;
    try { localStorage.setItem('band_metro_audio_mode', mode); } catch (e) { /* 隐私模式 */ }
  },

  /** 兼容模式音池：预加载两组短 WAV；轮换元素，避免上一声还在响时被 currentTime=0 截断。 */
  warmClickPool() {
    if (this._clickPool) return this._clickPool;
    const make = (url) => Array.from({ length: 4 }, () => {
      const a = new Audio(url);
      a.preload = 'auto';
      a.volume = 1;
      return a;
    });
    // -258：8ms 峰值保持包络（`tools/gen_metro_wavs.py` 生成）。新 URL 避免旧 SW 缓存
    // 继续供旧点击音；旧的两条保留过渡，不再被引用。
    this._clickPool = {
      accent: make('/sounds/metro-accent-258.wav'),
      weak: make('/sounds/metro-weak-258.wav'),
      idx: { accent: 0, weak: 0 },
    };
    return this._clickPool;
  },

  /**
   * 兼容模式的一声：用 `<audio>` 播预生成 WAV，绕开“Web Audio 在跑但不出声”的宿主。
   * ⚠️ 第一次调用必须发生在「开始」按钮的手势里；之后的定时调用依赖这次解锁。
   * @param {boolean} accent 是不是重拍
   * @returns {number|null} 伪时间（秒）；拿不到就 null
   */
  tickCompat(accent) {
    try {
      const pool = this.warmClickPool();
      const key = accent ? 'accent' : 'weak';
      const list = pool[key];
      const el = list[pool.idx[key] % list.length];
      pool.idx[key] = (pool.idx[key] + 1) % list.length;
      try { el.currentTime = 0; } catch (e) { /* 首次还没元数据，play() 会从头开始 */ }
      const p = el.play();
      if (p && typeof p.catch === 'function') {
        p.catch((e) => {
          this._audioIssue = { kind: 'compat', name: e && e.name, message: e && e.message };
        });
      }
      return performance.now() / 1000;
    } catch (e) {
      this._audioIssue = { kind: 'compat', name: e && e.name, message: e && e.message };
      return null;
    }
  },

  /** 签到成功的"确认"声：现场合成，单 AudioContext，指数衰减（§14.6.6）。 */
  chime() {
    if (localStorage.getItem('band_sound') === '0') return;
    try {
      if (!this._ac) {
        this._ac = new (window.AudioContext || window.webkitAudioContext)();
      }
      const ac = this._ac;
      if (ac.state === 'suspended') Promise.resolve(ac.resume()).catch(() => {});
      const t0 = ac.currentTime;
      [[880, 0], [1318.5, 0.09]].forEach(([freq, dt]) => {
        const osc = ac.createOscillator();
        const gain = ac.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0.0001, t0 + dt);
        gain.gain.exponentialRampToValueAtTime(0.2, t0 + dt + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.5);
        osc.connect(gain);
        gain.connect(ac.destination);
        osc.start(t0 + dt);
        osc.stop(t0 + dt + 0.55);
      });
    } catch (e) { /* 音频不可用即静默：印章与文字是视觉等价物 */ }
  }};

/* ============================================================
   外观控件：主题设置入口 + 深浅（docs/14 §14.6.2b / §14.6.2c / #239 / #244）
   ------------------------------------------------------------
   ⚠️ **配色弹层已于 2026-11-04 改道**：原来的 6 色小弹层整体删除，配色按钮变成
   指向 `theme.html` 的**真实链接**（所有原入口同步改道，不保留第二套入口）。
   ⚠️ 全部封在 `BAND.appearance` 里：本文件是 classic script，旧 8 页各自的内联
   `const PAL_NAME / store / setPalette…` 已删；将来若有人再摊成顶层 const，
   会与本文件/其它脚本**重复声明**（SyntaxError）。
   ⚠️ `band-nav.js` 已在 <head> 把 data-theme 与主题令牌打到 <html>（首帧不闪）；
   这里只接线控件、把按钮状态同步回来，切换时才写 localStorage。
   ⚠️ 主题令牌存在 `band.theme.settings.v2` 的 `tokens` 字段里（一次写入、一次校验）；
   本文件不加载颜色引擎，只按当前深浅把对应那套令牌写回 `<html>`。
   ⚠️ 沉浸页 `showcase.html`（我的专场）**明确不加**控件（用户 2026-10-29 拍板）。
   ⚠️ 「墨洇」只在 `html.theme-xvt` 期间生效（band.css §1b）；连续点击用**代际令牌**
   保证**最后一代**过渡结束时才摘类——旧回调提前摘会让后一次退回默认交叉淡化并叠出白闪。
   ============================================================ */
Object.assign(BAND, {
  appearance: {
    SETTINGS_KEY: 'band.theme.settings.v2',
    THEME_KEY: 'band.theme',
    ICON_SUN: '<path d="M12 5v2M12 17v2M5 12H3M21 12h-2M6.3 6.3 4.9 4.9M19.1 19.1l-1.4-1.4M17.7 6.3l1.4-1.4M4.9 19.1l1.4-1.4"/><circle cx="12" cy="12" r="3.6"/>',
    ICON_MOON: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.4 6.4 0 0 0 10.5 10.5Z"/>',
    /** 31 个 M3 语义角色（与 theme.html 的 ROLES 同序；改这里要同步两边）。 */
    ROLE_VARS: [
      'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer',
      'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
      'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
      'error', 'onError', 'errorContainer', 'onErrorContainer',
      'surface', 'onSurface', 'onSurfaceVariant',
      'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
      'surfaceContainerHigh', 'surfaceContainerHighest', 'surfaceDim', 'surfaceBright',
      'outline', 'outlineVariant',
      'inverseSurface', 'inverseOnSurface', 'inversePrimary', 'shadow',
    ],
    /** 应用语义令牌（band.css :root 的直接值名字）。别名 --silk/--wood/… 自动跟随。 */
    APP_VARS: [
      '--bg', '--surface', '--surface-2', '--line', '--line-strong',
      '--ink', '--ink-2', '--ink-3',
      '--accent', '--on-accent', '--accent-ink', '--accent-text', '--accent-weak', '--accent-line', '--accent-dk',
      '--danger', '--danger-weak', '--hero-1', '--hero-2', '--edge',
      '--shellmid', '--on-shell',
      '--sh-1', '--sh-2', '--sh-3',
    ],
    _wired: false,
    _gen: 0,

    _get(key, dflt) {
      try { return localStorage.getItem(key) ?? dflt; } catch (e) { return dflt; }
    },
    _set(key, value) {
      try { localStorage.setItem(key, value); } catch (e) { /* 隐私模式：本页仍生效 */ }
    },

    /** 当前深浅（与 `band-nav.js` 首帧写的一致）。 */
    currentTheme() {
      return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
    },

    /** 读 v2 配置；坏了 / 版本不对一律当没有（绝不抛错打断页面）。 */
    readSettings() {
      try {
        const raw = localStorage.getItem(this.SETTINGS_KEY);
        if (!raw) return null;
        const cfg = JSON.parse(raw);
        return cfg && cfg.version === 2 ? cfg : null;
      } catch (e) { return null; }
    },

    /** 令牌键全集：31 个角色 + 应用令牌。 */
    _tokenKeys() {
      return this.ROLE_VARS.map((r) => '--md-sys-color-' + r.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()))
        .concat(this.APP_VARS);
    },

    /** 把已应用主题的指定深浅那套令牌写到 `<html>`；没有缓存就清掉内联覆盖、退回默认。 */
    applyStoredTokens(theme) {
      const cfg = this.readSettings();
      // 迁移（rev < 2）：旧「纯墨」缓存是蓝色主色；无引擎可重算 ⇒ 退回 legacy ink 调色板。
      // 与 `band-nav.js` 同一口径：用户不需要回主题页重新应用。
      const staleInk = cfg && !(cfg.rev >= 2) && cfg.preset === 'ink';
      if (staleInk) document.documentElement.dataset.palette = 'ink';
      const map = cfg && !staleInk && cfg.tokens && cfg.tokens[theme];
      const style = document.documentElement.style;
      this._tokenKeys().forEach((k) => {
        // ⚠️ 夜间 hero 与 `band-nav.js` 同一口径：**不读缓存**。旧配置把 --hero-1/2 存成
        // 固定 neutral tone（旧版 tone 6 = 夜间 surface ⇒ 活动页大卡与页底同色），
        // 这里清掉内联值，让 band.css §1b 的 accent 掺色公式生效，旧用户无需重选主题。
        const skipHero = theme === 'dark' && (k === '--hero-1' || k === '--hero-2');
        if (map && typeof map[k] === 'string' && !skipHero) style.setProperty(k, map[k]);
        else style.removeProperty(k);
      });
      style.colorScheme = theme === 'dark' ? 'dark' : 'light';
    },

    /** 旧 8 页有静态 `.ev-appbar` ⇒ 只接线；其余标准导航页就地注入一份（不含「今天」chip）。 */
    mount(host) {
      if (!host || document.body.classList.contains('page-showcase')) return;
      const isTheme = location.pathname.endsWith('/theme.html');
      if (!document.getElementById('night-btn')) {
        const bar = document.createElement('div');
        bar.className = 'ev-appbar';
        // 主题页自己不再放「进主题页」的入口；只注入日夜按钮。
        bar.innerHTML = isTheme
          ? `<button class="ticon" id="night-btn" type="button" aria-pressed="false" aria-label="切换到深色" title="深浅色">
               <svg id="night-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">${this.ICON_MOON}</svg>
             </button>`
          : `<a class="ticon" id="theme-btn" href="/theme.html" aria-label="主题设置" title="主题设置">
               <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="8.5"/><circle cx="9.5" cy="10" r="1.1" fill="currentColor"/><circle cx="14.5" cy="10" r="1.1" fill="currentColor"/><circle cx="12" cy="14.4" r="1.1" fill="currentColor"/></svg>
             </a>
             <button class="ticon" id="night-btn" type="button" aria-pressed="false" aria-label="切换到深色" title="深浅色">
               <svg id="night-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">${this.ICON_MOON}</svg>
             </button>`;
        host.parentNode.insertBefore(bar, host.nextSibling);
      }
      this.wire();
      this.setTheme(this.currentTheme());
    },

    wire() {
      if (this._wired) return;
      const night = document.getElementById('night-btn');
      if (!night) return;
      this._wired = true;
      night.addEventListener('click', () => this.toggleNight());
      const btn = document.getElementById('theme-btn');
      if (btn) {
        // 真实链接：中键 / 新标签都能用；`from` 让主题页知道该回哪儿。
        const href = this.themeHref();
        if (href && btn.tagName === 'A') btn.setAttribute('href', href);
        const remember = () => this.rememberSource();
        btn.addEventListener('click', remember);
        btn.addEventListener('auxclick', remember);
      }
    },

    /** 来源 / 视图状态由 #248 的共用导航记录，保持真实链接支持新标签。 */
    rememberSource() {
      BAND.navigation?.save();
    },

    /** 主题入口的真实 href；主题页自己返回 null（不给自己再挂一个入口）。 */
    themeHref() {
      if (location.pathname.endsWith('/theme.html')) return null;
      return '/theme.html?from=' + encodeURIComponent(location.pathname + location.search);
    },

    setTheme(t) {
      const on = t === 'dark';
      document.documentElement.dataset.theme = on ? 'dark' : 'light';
      this.applyStoredTokens(on ? 'dark' : 'light');
      const btn = document.getElementById('night-btn');
      if (btn) {
        btn.setAttribute('aria-pressed', String(on));
        btn.setAttribute('aria-label', on ? '切换到浅色' : '切换到深色');
      }
      const ic = document.getElementById('night-ic');
      if (ic) ic.innerHTML = on ? this.ICON_SUN : this.ICON_MOON;
      this._set(this.THEME_KEY, document.documentElement.dataset.theme);
    },

    toggleNight() {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
      const btn = document.getElementById('night-btn');
      if (!document.startViewTransition || reduce || !btn) { this.setTheme(next); return; }
      const r = btn.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const cr = Math.hypot(Math.max(cx, innerWidth - cx), Math.max(cy, innerHeight - cy)) * 1.05;
      const style = document.documentElement.style;
      style.setProperty('--cx', cx + 'px');
      style.setProperty('--cy', cy + 'px');
      style.setProperty('--cr', cr + 'px');
      const cls = document.documentElement.classList;
      const gen = ++this._gen;
      cls.add('theme-xvt');
      try {
        const t = document.startViewTransition(() => this.setTheme(next));
        // 只有**最新一代**的过渡结束才摘门控类；被连点替换掉的旧过渡不能提前摘（#234）。
        const done = () => { if (gen === this._gen) cls.remove('theme-xvt'); };
        if (t && t.finished) t.finished.then(done, done);
        else done();
      } catch (e) {
        cls.remove('theme-xvt');
        this.setTheme(next);
      }
    },
  },
});

BAND.initRipples();

/* #251：原生二值开关的共用滑块弹簧。只画运动，不派发业务事件或保存偏好。 */
(function () {
  const selector = '.band-switch input[type="checkbox"][role="switch"]';
  const controls = new WeakMap();
  const mounted = new Set();
  const moving = new Set();
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  let frame = 0, previousTime = 0;

  function stopFrameIfIdle() {
    if (moving.size) return;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    previousTime = 0;
  }

  function tick(time) {
    frame = 0;
    const dt = previousTime ? Math.min(.032, Math.max(.001, (time - previousTime) / 1000)) : 1 / 60;
    previousTime = time;
    for (const control of moving) control.step(dt);
    if (moving.size) frame = requestAnimationFrame(tick);
    else previousTime = 0;
  }

  BAND.switch = function (input) {
    if (!(input instanceof HTMLInputElement) || !input.matches(selector)) return null;
    if (controls.has(input)) return controls.get(input).api;
    let x = input.checked ? 1 : 0, velocity = 0, target = x, revision = 0;
    let distance = 14, low = 0, high = 1, disposed = false;
    const locked = () => input.disabled || input.readOnly || input.getAttribute('aria-readonly') === 'true';

    function measure() {
      const style = getComputedStyle(input);
      distance = parseFloat(style.getPropertyValue('--band-switch-travel')) || 14;
      const inset = parseFloat(style.getPropertyValue('--band-switch-inset')) || 3;
      const thumb = parseFloat(style.getPropertyValue('--band-switch-thumb')) || 16;
      low = -inset / distance;
      high = Math.max(1, (input.clientWidth - inset - thumb) / distance);
    }

    function paint() { input.style.setProperty('--band-switch-x', (x * distance) + 'px'); }

    function settle() {
      target = input.checked ? 1 : 0;
      x = target;
      velocity = 0;
      moving.delete(control);
      input.style.removeProperty('--band-switch-x');
      stopFrameIfIdle();
    }

    function sync({ animate = false, force = false } = {}) {
      if (disposed) return;
      const next = input.checked ? 1 : 0;
      if (reduce.matches || locked() || force || !input.isConnected || !input.getClientRects().length) {
        settle();
        return;
      }
      // 页面的同值重绘 / 保存回显不掐断当前运动。
      if (next === target) return;
      if (!animate) { revision++; settle(); return; }
      measure();
      target = next;
      paint();
      moving.add(control);
      if (!frame) frame = requestAnimationFrame(tick);
    }

    function onChange() { revision++; sync({ animate: true }); }
    function onClick(event) {
      if (input.readOnly || input.getAttribute('aria-readonly') === 'true') {
        event.preventDefault();
        queueMicrotask(settle); // 原生 click 的取消会先还原 checked。
      }
    }
    function onKey(event) {
      if (event.key === ' ' && locked()) event.preventDefault();
    }
    const observer = new MutationObserver(() => { if (locked()) settle(); });
    const control = {
      input,
      step(dt) {
        if (!input.isConnected) { control.api.destroy(); return; }
        if (reduce.matches || locked() || !input.getClientRects().length) { settle(); return; }
        // 与点验样张相同的弹簧 / 阻尼；细分积分确保慢帧与反向仍连续。
        const steps = Math.ceil(dt / (1 / 120)), h = dt / steps;
        for (let i = 0; i < steps; i++) {
          velocity += (-300 * (x - target) - 15 * velocity) * h;
          x += velocity * h;
          if (x < low || x > high) {
            x = Math.max(low, Math.min(high, x));
            velocity = 0;
          }
        }
        if (Math.abs(x - target) * distance < .008 && Math.abs(velocity) * distance < .04) {
          x = target;
          velocity = 0;
          moving.delete(control);
        }
        paint();
        if (!moving.has(control)) input.style.removeProperty('--band-switch-x');
      },
      api: {
        get revision() { return revision; },
        sync,
        setChecked(value, options = {}) {
          if (disposed || (options.revision !== undefined && options.revision !== revision)) return false;
          input.checked = !!value;
          sync(options);
          return true;
        },
        destroy() {
          if (disposed) return;
          disposed = true;
          moving.delete(control);
          mounted.delete(control);
          controls.delete(input);
          observer.disconnect();
          input.removeEventListener('change', onChange, true);
          input.removeEventListener('click', onClick, true);
          input.removeEventListener('keydown', onKey, true);
          input.style.removeProperty('--band-switch-x');
          stopFrameIfIdle();
        },
        settle,
      },
    };
    controls.set(input, control);
    mounted.add(control);
    observer.observe(input, { attributes: true, attributeFilter: ['disabled', 'readonly', 'aria-readonly'] });
    input.addEventListener('change', onChange, true);
    input.addEventListener('click', onClick, true);
    input.addEventListener('keydown', onKey, true);
    measure();
    return control.api;
  };

  BAND.switches = {
    mount(root = document) {
      if (root.matches?.(selector)) BAND.switch(root);
      root.querySelectorAll?.(selector).forEach(input => BAND.switch(input));
    },
  };
  const settleAll = () => { for (const control of mounted) control.api.settle(); };
  const onReduce = () => { if (reduce.matches) settleAll(); };
  if (reduce.addEventListener) reduce.addEventListener('change', onReduce);
  else reduce.addListener(onReduce);
  document.addEventListener('reset', event => queueMicrotask(() => {
    for (const control of mounted) if (control.input.form === event.target) control.api.settle();
  }), true);
  document.addEventListener('visibilitychange', () => { if (document.hidden) settleAll(); });
  window.addEventListener('pagehide', settleAll);
  function bootSwitches() {
    BAND.switches.mount();
    new MutationObserver(records => {
      for (const control of mounted) if (!control.input.isConnected) control.api.destroy();
      for (const record of records) for (const node of record.addedNodes) BAND.switches.mount(node);
    }).observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bootSwitches, { once: true });
  else bootSwitches();
})();

/* ============================================================
   PWA 外壳（docs/19 / #219）：SW 注册、更新提示、离线提示。
   ⚠️ 只在这里注册一次（band-core.js 被除 index.html 外的所有根页面引用）。
   ⚠️ 更新提示必须用户点「刷新」才生效：install 不 skipWaiting，这里也绝不自动 reload。
   ⚠️ 提示条追加到 body 末尾（不放 .mbody）：否则会被手机端下拉刷新的 transform 拖走。
   ============================================================ */
(function () {
  if (!('serviceWorker' in navigator)) return;

  const state = {
    registration: null,
    updateAvailable: false,
    dismissed: false,
    offline: navigator.onLine === false,
    refreshRequested: false,
    reloaded: false,
    bar: null,
  };

  function isControlled() {
    return !!navigator.serviceWorker.controller;
  }

  function ensureBar() {
    if (state.bar) return state.bar;
    if (!document.body) return null;
    const bar = document.createElement('div');
    bar.id = 'pwa-bar';
    bar.className = 'pwa-bar';
    bar.hidden = true;
    bar.setAttribute('role', 'region');
    bar.setAttribute('aria-label', '应用提示');
    bar.setAttribute('aria-live', 'polite');
    document.body.appendChild(bar);
    state.bar = bar;
    return bar;
  }

  function sync() {
    const bar = ensureBar();
    if (!bar) return;

    // 更新提示优先；同一时刻只显示一条
    if (state.updateAvailable && !state.dismissed) {
      bar.hidden = false;
      bar.className = 'pwa-bar pwa-bar-update';
      bar.textContent = '';

      const text = document.createElement('span');
      text.className = 'pwa-bar-tx';
      text.textContent = '新版本已就绪';

      const acts = document.createElement('span');
      acts.className = 'pwa-bar-acts';

      const refresh = document.createElement('button');
      refresh.type = 'button';
      refresh.className = 'go go-sm';
      refresh.textContent = '刷新';
      refresh.addEventListener('click', requestRefresh);

      const later = document.createElement('button');
      later.type = 'button';
      later.className = 'go go-ghost go-sm';
      later.textContent = '稍后';
      later.addEventListener('click', () => {
        state.dismissed = true;
        sync();
      });

      acts.append(refresh, later);
      bar.append(text, acts);
      return;
    }

    if (state.offline) {
      bar.hidden = false;
      bar.className = 'pwa-bar pwa-bar-offline';
      bar.textContent = '当前离线 · 数据需联网';
      return;
    }

    bar.hidden = true;
    bar.textContent = '';
    bar.className = 'pwa-bar';
  }

  function requestRefresh() {
    if (state.refreshRequested) return;
    state.refreshRequested = true;
    const waiting = state.registration && state.registration.waiting;
    if (waiting) waiting.postMessage({ type: 'SKIP_WAITING' });
    // 用户已明确同意刷新：3 秒没等到 controllerchange 也不能卡住
    setTimeout(() => {
      if (!state.reloaded) {
        state.reloaded = true;
        location.reload();
      }
    }, 3000);
  }

  function showUpdate() {
    // 首次安装没有 controller：绝不出现「新版本」
    if (!isControlled()) return;
    state.updateAvailable = true;
    sync();
  }

  navigator.serviceWorker.addEventListener('controllerchange', () => {
    // 只有用户点过「刷新」才允许因切换而重载
    if (!state.refreshRequested || state.reloaded) return;
    state.reloaded = true;
    location.reload();
  });

  function watch(registration) {
    state.registration = registration;

    if (registration.waiting && isControlled()) showUpdate();

    registration.addEventListener('updatefound', () => {
      const worker = registration.installing;
      if (!worker) return;
      worker.addEventListener('statechange', () => {
        if (worker.state === 'installed' && isControlled()) showUpdate();
      });
    });
  }

  function boot() {
    ensureBar();
    sync();

    navigator.serviceWorker.register('/service-worker.js', { updateViaCache: 'none' })
      .then(watch)
      .catch((e) => console.warn('[PWA] service worker 注册失败', e));

    window.addEventListener('offline', () => {
      state.offline = true;
      sync();
    });
    window.addEventListener('online', () => {
      state.offline = false;
      sync();   // 只隐藏提示条：不自动刷新、不自动重请求数据
    });
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && state.registration) {
        state.registration.update().catch(() => {});
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
