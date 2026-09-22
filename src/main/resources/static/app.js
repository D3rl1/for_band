'use strict';

const BAND = {
  token: () => localStorage.getItem('band_token'),
  saveToken: (t) => localStorage.setItem('band_token', t),
  clearToken: () => localStorage.removeItem('band_token'),

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

  me: null,

  async requireUser(roles) {
    if (!this.token()) location.href = '/login.html';
    if (!this.me) {
      const envelope = await this.api('/api/auth/me');
      if (envelope.code !== 0) return null; // 401 已在 api() 里跳转
      this.me = envelope.data;
    }
    if (roles && !roles.includes(this.me.role)) {
      location.href = '/events.html';
      return null;
    }
    return this.me;
  },

  /** 深色侧栏骨架（docs/14 §14.6.4）：把手常驻，菜单按角色渲染；把页面既有内容挪进 .main。 */
  async renderNav(active) {
    const me = await this.requireUser();
    if (!me) return null;
    const items = [
      ['events.html', '活动', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['members.html', '名册', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['my-leave.html', '我的请假', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['stats.html', '考勤统计', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['approve-leave.html', '请假审批', ['ADMIN', 'LEADER', 'SECTION_LEADER']],
      ['admin-events.html', '活动管理', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER']],
      ['admin-members.html', '团员管理', ['ADMIN']],
      ['admin-sections.html', '声部管理', ['ADMIN']],
      ['logs.html', '操作日志', ['ADMIN', 'LEADER']],
    ];
    const roleNames = {
      ADMIN: '管理员', LEADER: '团长', CONDUCTOR: '指挥',
      SECTION_LEADER: '声部长', MEMBER: '团员',
    };
    const links = items
      .filter(([, , roles]) => roles.includes(me.role))
      .map(([href, label]) =>
        `<a href="/${href}" class="${href === active ? 'on' : ''}">${label}</a>`)
      .join('');
    const soundOn = localStorage.getItem('band_sound') !== '0';

    const host = document.getElementById('navbar');
    const layout = document.createElement('div');
    layout.className = 'layout';
    layout.innerHTML = `
      <button class="nav-toggle" id="navToggle" type="button" aria-expanded="false">
        <span class="txt">概览</span></button>
      <aside class="sidebar">
        <div class="brand">民 乐 管 弦 乐 团</div>
        <nav class="menu">${links}</nav>
        <div class="foot">${me.name} · ${roleNames[me.role] || me.role}<br>
          <a href="#" id="sound-toggle">声音 ${soundOn ? '开' : '关'}</a> ·
          <a href="#" id="logout-link">退出</a></div>
      </aside>`;
    const main = document.createElement('main');
    main.className = 'main';
    const head = document.createElement('div');
    head.className = 'head';
    const activeLabel = items.find(([href]) => href === active);
    head.innerHTML = `<h1></h1><span class="greet"></span>`;
    head.querySelector('h1').textContent = activeLabel ? activeLabel[1] : me.name;
    head.querySelector('.greet').textContent =
      `${me.name} · ${roleNames[me.role] || me.role}${me.sectionName ? ' · ' + me.sectionName : ''}`;
    main.appendChild(head);
    layout.appendChild(main);

    // 把 #navbar 之后的页面内容全部挪进 .main（toast-host 留在 body 上）
    const moved = [];
    let node = host.nextSibling;
    while (node) {
      const next = node.nextSibling;
      if (node.nodeType === Node.ELEMENT_NODE && node.id !== 'toast-host') moved.push(node);
      node = next;
    }
    moved.forEach((n) => main.appendChild(n));
    host.replaceWith(layout);

    layout.querySelector('#logout-link').addEventListener('click', async (e) => {
      e.preventDefault();
      await this.api('/api/auth/logout', { method: 'POST' });
      this.clearToken();
      location.href = '/login.html';
    });
    layout.querySelector('#sound-toggle').addEventListener('click', (e) => {
      e.preventDefault();
      const nowOn = localStorage.getItem('band_sound') !== '0';
      localStorage.setItem('band_sound', nowOn ? '0' : '1');
      e.target.textContent = '声音 ' + (nowOn ? '关' : '开');
    });

    if (typeof window.bandNavInit === 'function') window.bandNavInit();
    return me;
  },

  /** 签到成功的"确认"声：现场合成，单 AudioContext，指数衰减（§14.6.6）。 */
  chime() {
    if (localStorage.getItem('band_sound') === '0') return;
    try {
      if (!this._ac) {
        this._ac = new (window.AudioContext || window.webkitAudioContext)();
      }
      const ac = this._ac;
      if (ac.state === 'suspended') ac.resume();
      const t0 = ac.currentTime;
      [[880, 0], [1318.5, 0.09]].forEach(([freq, dt]) => {
        const osc = ac.createOscillator();
        const gain = ac.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0.0001, t0 + dt);
        gain.gain.exponentialRampToValueAtTime(0.2, t0 + dt + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.5);
        osc.connect(gain).connect(ac.destination);
        osc.start(t0 + dt);
        osc.stop(t0 + dt + 0.55);
      });
    } catch (e) { /* 音频不可用即静默：印章与文字是视觉等价物 */ }
  },

  kindLabel: (kind) => ({ REHEARSAL: '排练', SECTION_CLASS: '声部课', DRESS_REHEARSAL: '彩排' }[kind] || kind),
  statusLabel: (s) => ({ ACTIVE: '在团', PAUSED: '休团', LEFT: '退团', GRADUATED: '毕业' }[s] || s),
  roleLabel: (r) => ({ ADMIN: '管理员', LEADER: '团长', CONDUCTOR: '指挥', SECTION_LEADER: '声部长', MEMBER: '团员' }[r] || r),
  ratePct: (rate) => Math.round(rate * 1000) / 10 + '%',
};
