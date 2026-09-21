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

  async renderNav(active) {
    const me = await this.requireUser();
    if (!me) return null;
    const items = [
      ['events.html', '活动', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['members.html', '名册', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
      ['stats.html', '考勤统计', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
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
        `<li class="nav-item"><a class="nav-link ${href === active ? 'active' : ''}" href="/${href}">${label}</a></li>`)
      .join('');
    const host = document.getElementById('navbar');
    host.innerHTML = `
      <nav class="navbar navbar-expand-lg bg-body-tertiary border-bottom">
        <div class="container">
          <a class="navbar-brand" href="/events.html">乐团</a>
          <button class="navbar-toggler" data-bs-toggle="collapse" data-bs-target="#navlist">
            <span class="navbar-toggler-icon"></span></button>
          <div class="collapse navbar-collapse" id="navlist">
            <ul class="navbar-nav me-auto">${links}</ul>
            <span class="navbar-text me-3">${me.name} · ${roleNames[me.role] || me.role}</span>
            <button id="logout-btn" class="btn btn-outline-secondary btn-sm">退出</button>
          </div>
        </div>
      </nav>`;
    host.querySelector('#logout-btn').addEventListener('click', async () => {
      await this.api('/api/auth/logout', { method: 'POST' });
      this.clearToken();
      location.href = '/login.html';
    });
    return me;
  },

  kindLabel: (kind) => ({ REHEARSAL: '排练', SECTION_CLASS: '声部课', DRESS_REHEARSAL: '彩排' }[kind] || kind),
  statusLabel: (s) => ({ ACTIVE: '在团', PAUSED: '休团', LEFT: '退团', GRADUATED: '毕业' }[s] || s),
  roleLabel: (r) => ({ ADMIN: '管理员', LEADER: '团长', CONDUCTOR: '指挥', SECTION_LEADER: '声部长', MEMBER: '团员' }[r] || r),
  ratePct: (rate) => Math.round(rate * 1000) / 10 + '%',
};

document.addEventListener('DOMContentLoaded', () => {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = '/vendor/bootstrap.min.css';
  document.head.prepend(link);
  const script = document.createElement('script');
  script.src = '/vendor/bootstrap.bundle.min.js';
  document.head.appendChild(script);
});
