'use strict';

const BAND = {
  token: () => localStorage.getItem('band_token'),
  saveToken: (t) => localStorage.setItem('band_token', t),
  /** 清登录态时**必须连身份缓存一起清**：否则同一个标签页换人登录，跳页首帧会闪出上一个人的名字。 */
  clearToken: () => {
    localStorage.removeItem('band_token');
    try { sessionStorage.removeItem('band_me'); } catch (e) { /* 隐私模式 */ }
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
    if (i.kind === 'no-ctor') {
      return '这个浏览器不支持网页音频（Web Audio），节拍器响不了。请换用手机自带浏览器或 Chrome 打开这一页。';
    }
    if (i.kind === 'ctor') {
      return '音频初始化失败（' + (i.name || '未知错误') + '）。请换用手机自带浏览器或 Chrome 再试。';
    }
    if (i.kind === 'tick') {
      return '音频节点创建失败（' + (i.name || '未知错误') + '）。请换用手机自带浏览器或 Chrome 再试。';
    }
    if (i.kind === 'resume' && i.name && i.name !== 'NotAllowedError') {
      return '音频没能启动（' + i.name + '）。请换用手机自带浏览器或 Chrome 再试。';
    }
    return '浏览器没有允许网页发声（音频被挂起）。百度浏览器：点地址栏左侧锁图标 → 网站设置 → 声音 → 允许，刷新后再点「开始」；也可换用手机自带浏览器。';
  },

  /**
   * 节拍器的一声（练习录音页）：**用户主动发起、持续可控的声音** —— `docs/14` §14.6.6 那张表
   * 把它和"确认类声音"分开的正是这一点，也是**第四条留痕**讲的"拍点指示不算 idle 动画"的同一条界线。
   * <p>
   * ⚠️ **不受 `band_sound` 约束**（**显式例外**，不是漏写）：一个开了却不出声的节拍器，看起来就是坏的。
   * ⚠️ **复用唯一的 `BAND._ac`** —— 全页只允许一个 `AudioContext`，这里绝不 `new` 第二个。
   * ⚠️ 包络与 `chime()` 同一路子：**指数衰减**（线性到 0 会爆音）。**重拍高且响、弱拍低**。
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
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(accent ? 0.34 : 0.17, t + 0.004);
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
