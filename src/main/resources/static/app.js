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

  /**
   * 预置头像清单在本地的两份表示：`avatarIndex`（`id → file`，给 `paintAvatar` 同步查）
   * 与 `avatarList`（清单原文 `[{id,name,file}]`，给「个人页」渲染选择网格）。
   * `avatarIndex === null` = 还没就绪。权威永远是 `vendor/avatars/manifest.json` 那一份
   * （docs/14 §14.6.4：**别在别处再抄一遍**），这里只是把它搬进内存一次。
   */
  avatarIndex: null,
  avatarList: [],
  avatarCall: null,

  /** 收下清单：同时建索引与原文，并**写穿会话缓存**（隐私模式下 `sessionStorage` 会抛，忽略即可）。 */
  adoptAvatars(list) {
    this.avatarList = list;
    this.avatarIndex = new Map(list.map((item) => [item.id, item.file]));
    try {
      sessionStorage.setItem('band_avatars', JSON.stringify(list));
    } catch (e) { /* 隐私模式：忽略 */ }
  },

  /**
   * 取预置头像清单（**永不 reject**：拿不到就当空清单，界面全部回退姓名首字——
   * 比抛出去拖垮整条 `renderNav` 安全得多）。同一页里多处调用只发一次请求。
   */
  avatars() {
    if (this.avatarCall) {
      return this.avatarCall;
    }
    this.avatarCall = fetch('/vendor/avatars/manifest.json')
      .then((res) => res.json())
      .then((data) => {
        // 只收齐了 id 与 file 的条目：缺 file 的条目前端paint不出来（会回退首字），
        // 那种写法属于清单自身出错——由 ProfileTests 里那条"每条 file 都真的在磁盘上"的断言兜住
        const list = ((data && data.avatars) || [])
          .filter((item) => item && item.id && item.file)
          .map((item) => ({ id: item.id, name: item.name || item.id, file: item.file }));
        this.adoptAvatars(list);
        return this.avatarIndex;
      })
      .catch((e) => {
        // 清单没入库 / 网络坏了：留痕但不打扰用户（头像全部回退姓名首字，页面照常可用）
        console.warn('预置头像清单读取失败，头像回退姓名首字', e);
        this.avatarIndex = new Map();
        return this.avatarIndex;
      });
    return this.avatarCall;
  },

  /** 每页开画前**同步**读一次会话缓存，让"本会话后续跳页"的首帧就能画对头像（不用等请求回来）。 */
  loadCachedAvatars() {
    if (this.avatarIndex) {
      return;
    }
    try {
      const raw = sessionStorage.getItem('band_avatars');
      if (raw) {
        this.adoptAvatars(JSON.parse(raw) || []);
      }
    } catch (e) { /* 隐私模式 / 内容坏了：当作没有 */ }
  },

  /**
   * 画头像（**唯一入口**）：**有 `avatar` 且 `id ∈ 预置清单` 才画图，否则回退姓名首字**
   * （docs/14 §14.6.4「名单里的头像」；清单校验收在这里，所以**哪里调它哪里就有保护**）。
   * ⚠️ 所有调用点（侧栏头像行 / 个人栏 / **欢迎过渡的落点** / 名册 / 考勤明细）都**保留外层容器**、
   * 只换里面的内容——过渡是 FLIP，**靠量那个容器的坐标**，把容器本身换成 `<img>` 就会飞到空元素上。
   * <p>
   * ⚠️⚠️ 判据是"**id 在不在清单**"，**不是"图加载成不成功"**——所以这里**故意不给 `<img>` 挂 `onerror` 回退**：
   * 清单里 `file` 写错时必须**看得见坏图**（`交接单.md` `#37` 的验收就是这么要求的）。
   * 给 `<img>` 加兜底会把这类错误静默吃掉，与那条验收直接冲突。
   */
  paintAvatar(el, member) {
    const avatar = member && member.avatar;
    const file = avatar && this.avatarIndex ? this.avatarIndex.get(avatar) : undefined;
    el.textContent = '';
    if (file) {
      const img = document.createElement('img');
      img.className = 'av-img';
      img.alt = '';
      img.src = '/vendor/avatars/' + encodeURIComponent(file);
      el.appendChild(img);
      return;
    }
    // ① 没选头像 ② 清单外的旧 id ③ 清单还没就绪 / 读不到 ④ 清单里 file 为空 —— 四种都回退姓名首字
    el.textContent = Array.from((member && member.name) || '?')[0];
  },

  /** 公告的"已读到哪一版"（**设备级**，不是账号级，docs/15 §15.5）。 */
  seenRelease() {
    try {
      return Number(localStorage.getItem('band_seen_release')) || 0;
    } catch (e) {
      return 0;   // 隐私模式：当作没记录过 → 每次都会弹，可接受
    }
  },

  markReleaseSeen(id) {
    try { localStorage.setItem('band_seen_release', String(id)); } catch (e) { /* 忽略 */ }
  },

  /**
   * 生日通告"今天弹过没"（docs/14 §14.6.4「生日通告」）：**设备级、记的是日期**，
   * 值与今天相等就不再弹。代价与公告同一口径：清浏览器数据 / 换设备会再弹一次（已知、可接受）。
   */
  seenBirthday() {
    try {
      return localStorage.getItem('band_seen_birthday') || '';
    } catch (e) {
      return '';   // 隐私模式：当作没记录过 → 会再弹，可接受
    }
  },

  markBirthdaySeen(day) {
    try { localStorage.setItem('band_seen_birthday', day); } catch (e) { /* 忽略 */ }
  },

  /** 本地日期 `YYYY-MM-DD`。⚠️ **别用 `toISOString()`**——那是 UTC，跨日会错一天。 */
  localDayKey() {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  },

  /**
   * 右上角操作区（docs/14 §14.6.4）：**反馈**（打开浮层）+ **更新**（进 `updates.html`，有未读公告带红点）。
   * §14：**荣誉团员看得到「更新」、看不到「反馈」**（对"新功能默认不给荣誉团员"的有意例外 —— 公告是纯只读信息）。
   */
  mountHeadActions(headR, me) {
    if (me.role !== 'HONORARY') {
      const fb = document.createElement('button');
      fb.type = 'button';
      fb.className = 'hact';
      fb.innerHTML = '<span class="hact-ic" aria-hidden="true">馈</span><span class="hact-tx">反馈</span>';
      fb.addEventListener('click', () => this.openFeedback());
      headR.appendChild(fb);
    }
    const up = document.createElement('a');
    up.className = 'hact';
    up.href = '/updates.html';
    up.innerHTML = '<span class="hact-ic" aria-hidden="true">更</span><span class="hact-tx">更新</span>';
    const dot = document.createElement('span');
    dot.className = 'hact-dot';
    dot.hidden = true;
    up.appendChild(dot);
    headR.appendChild(up);
  },

  /** 未读公告的小红点（与弹层同一套判定：本机没记录过也算"有未读"）。
   *  单独一个方法是为了**只拉一次**——`paintNav` 会跑两遍（缓存 + 权威），别拉两次接口。 */
  refreshReleaseDot() {
    const dot = document.querySelector('.hact-dot');
    if (!dot) {
      return;
    }
    this.api('/api/release-notes/latest').then((envelope) => {
      if (envelope.code === 0 && envelope.data && envelope.data.id > this.seenRelease()) {
        dot.hidden = false;
      }
    }).catch(() => { /* 拉不到就不显示红点，别打扰 */ });
  },

  /** 反馈浮层（docs/15 §15.4）：多行文本框 + 提交。**页面路径由前端自动带**，设备由服务端取。 */
  openFeedback() {
    let el = document.getElementById('feedback-modal');
    if (!el) {
      el = document.createElement('div');
      el.id = 'feedback-modal';
      el.className = 'modal fade';
      el.tabIndex = -1;
      el.innerHTML = `<div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-body">
            <p class="big my-2">反馈</p>
            <p class="chip">遇到 bug 或者哪里别扭，直接写在这里。</p>
            <textarea class="w-100" id="fb-text" rows="5" maxlength="1000"
                      placeholder="比如：在活动详情页点了签到没反应"></textarea>
          </div>
          <div class="modal-footer py-1">
            <button class="go go-ghost go-sm" data-bs-dismiss="modal">取消</button>
            <button class="go go-sm" id="fb-send">提交</button>
          </div>
        </div></div>`;
      document.body.appendChild(el);
      el.querySelector('#fb-send').addEventListener('click', async () => {
        const box = el.querySelector('#fb-text');
        const content = box.value.trim();
        if (!content) {
          this.toast('正文不能为空');
          return;
        }
        try {
          await this.must('/api/feedback', {
            method: 'POST',
            body: { content, pagePath: location.pathname + location.search },
          });
        } catch (err) {
          return;   // 失败：toast 已弹，浮层留着让人改（别把写好的内容弄丢）
        }
        box.value = '';
        bootstrap.Modal.getInstance(el).hide();
        this.toast('已收到，谢谢', 'success');
      });
    }
    bootstrap.Modal.getOrCreateInstance(el).show();
  },

  /**
   * 更新公告弹层（docs/15 §15.5）：**登录成功那一次**、欢迎过渡播完之后弹**最新一条**。
   * 口径：每个版本一次（`latest.id` **大于**本机记过的 id 才弹；**本机无记录也要弹**）；
   * **没有公告就不弹、不占位、不出空层**；纯文本 + 保留换行；点任意处 / Esc 都能关；
   * `prefers-reduced-motion` 不播入场动画。⚠️ **必须等它关掉才加载 oneko**（三者顺序，别让猫从公告上跑过去）。
   * @param fromLogin 只有登录成功那一次为 true
   */
  async announce(fromLogin) {
    if (!fromLogin) {
      return;   // 平时浏览页面不弹
    }
    const envelope = await this.api('/api/release-notes/latest');
    const note = envelope.code === 0 ? envelope.data : null;
    if (!note || note.id <= this.seenRelease()) {
      return;   // 没有公告 / 已读过这一版
    }
    return new Promise((resolve) => {
      const reduced = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      const mask = document.createElement('div');
      mask.className = 'ann' + (reduced ? '' : ' ann-in');
      const card = document.createElement('div');
      card.className = 'ann-card';
      card.setAttribute('role', 'dialog');
      card.setAttribute('aria-label', '本次更新');
      const title = document.createElement('p');
      title.className = 'ann-title';
      title.textContent = note.title;
      const body = document.createElement('p');
      body.className = 'ann-body';
      body.textContent = note.content;   // 纯文本 + 保留换行（CSS white-space），绝不解析 HTML
      const acts = document.createElement('div');
      acts.className = 'ann-acts';
      const more = document.createElement('a');
      more.href = '/updates.html';
      more.textContent = '查看全部更新';
      const ok = document.createElement('button');
      ok.className = 'go go-sm';
      ok.type = 'button';
      ok.textContent = '知道了';
      acts.append(more, ok);
      card.append(title, body, acts);
      mask.appendChild(card);

      let closed = false;
      const close = () => {
        if (closed) return;
        closed = true;
        this.markReleaseSeen(note.id);
        // 红点跟着消失：刚刚已经看过了
        document.querySelectorAll('.hact-dot').forEach((d) => { d.hidden = true; });
        document.removeEventListener('keydown', onKey);
        mask.remove();
        resolve();
      };
      function onKey(e) { if (e.key === 'Escape') close(); }
      ok.addEventListener('click', close);
      more.addEventListener('click', close);            // 去看历史也算已读
      mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
      document.addEventListener('keydown', onKey);
      document.body.appendChild(mask);
      ok.focus();
    });
  },

  /**
   * 生日通告（docs/14 §14.6.4「生日通告」）：登录那一次，今天有在团团员过生日就弹一个居中小浮层。
   * **顺序**：欢迎过渡 → 更新公告 → **生日通告** → 才加载 oneko（docs/15 §15.5 的四者顺序）。
   * <p>
   * ⚠️ 这套是**照抄上面那个 `announce()` 的**（同一个 `.ann` 浮层类与 `ann-rise` 动画、同一套关闭行为、
   * z-index 120）——规格明写"**不要另造一套弹层**，那会重演'两个浮层争 z-index'的坑"。
   * **故意不抽成通用函数**：`announce()` 的 `close()` 里带着两个**公告专有**的副作用
   * （`markReleaseSeen` + 清右上角红点），抽通用会牵动既有公告的行为。
   * <p>
   * 没有寿星 → **不弹、不占位、不出空层**（直接 resolve，让链子继续走到 oneko）。
   * @param fromLogin 只有登录成功那一次为 true
   */
  announceBirthday(fromLogin) {
    if (!fromLogin) {
      return Promise.resolve();   // 平时浏览页面不弹
    }
    const today = this.localDayKey();
    if (this.seenBirthday() === today) {
      return Promise.resolve();   // 今天已经弹过了（设备级去重）
    }
    return this.api('/api/birthdays/today').then((envelope) => {
      const list = envelope.code === 0 && Array.isArray(envelope.data) ? envelope.data : [];
      if (!list.length) {
        return;   // 今天没人过生日：整个环节跳过
      }
      // 文案（docs/14 §14.6.4 逐字）：一位**带职称**，多位只列名字。
      // 职称直接用 identityTags——接口不下发 joinedAt，所以它正好拼出规格要的「声部 · 乐器 · 角色」。
      const text = list.length === 1
        ? `今天是 ${list[0].name}（${this.identityTags(list[0])}）的生日，大家祝他/她生日快乐吧`
        : `今天是 ${list.map((m) => m.name).join('、')} 的生日，大家祝他们生日快乐吧`;
      return new Promise((resolve) => {
        const reduced = window.matchMedia
          && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        const mask = document.createElement('div');
        mask.className = 'ann' + (reduced ? '' : ' ann-in');
        const card = document.createElement('div');
        card.className = 'ann-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-label', '生日通告');
        const title = document.createElement('p');
        title.className = 'ann-title';
        title.textContent = '生日快乐';
        const body = document.createElement('p');
        body.className = 'ann-body';
        body.textContent = text;   // 纯文本，不解析 HTML（与公告同规矩）
        const acts = document.createElement('div');
        acts.className = 'ann-acts';
        const ok = document.createElement('button');
        ok.className = 'go go-sm';
        ok.type = 'button';
        ok.textContent = '知道了';
        acts.appendChild(ok);      // ⚠️ 生日没有"查看全部"那个入口，.ann-acts 里只有它一个
        card.append(title, body, acts);
        mask.appendChild(card);

        let closed = false;
        const close = () => {
          if (closed) return;
          closed = true;
          this.markBirthdaySeen(today);   // 记**日期**：今天剩下的登录都不再弹
          document.removeEventListener('keydown', onKey);
          mask.remove();
          resolve();
        };
        function onKey(e) { if (e.key === 'Escape') close(); }
        ok.addEventListener('click', close);
        mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
        document.addEventListener('keydown', onKey);
        document.body.appendChild(mask);
        ok.focus();
      });
    });
  },

  /**
   * 上传一个文件到统一文件接口（docs/08 §8.5）：**multipart，不能走 `api()` 的 JSON 封装**。
   * 返回 `{id, originalName, contentType, sizeBytes}`，拿 `id` 去挂到曲目附件 / 演出照片 / 录音上。
   */
  async uploadFile(file) {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/api/files', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + this.token() },
      body: form,
    });
    const envelope = await res.json().catch(() => ({ code: 10003, message: 'HTTP ' + res.status }));
    if (envelope.code !== 0) {
      this.toast(envelope.message || ('错误码 ' + envelope.code));
      throw envelope;
    }
    return envelope.data;
  },

  /**
   * 取文件内容为 `blob:` URL。**绝不能用裸 `<a href="/api/files/{id}">`**——
   * 裸链接不带 `Authorization` 头，永远 401（导出 CSV 早就踩过同一个坑，见 admin-members.html）。
   * 用完记得 `URL.revokeObjectURL`。
   */
  async fileBlobUrl(fileId) {
    const res = await fetch('/api/files/' + fileId, {
      headers: { Authorization: 'Bearer ' + this.token() },
    });
    if (!res.ok) {
      this.toast(res.status === 403 ? '没有权限看这个文件' : '打不开这个文件（HTTP ' + res.status + '）');
      throw new Error('HTTP ' + res.status);
    }
    return URL.createObjectURL(await res.blob());
  },

  /** 新窗口打开（谱子/图片）；音频由调用方拿去当 `<audio>` 的 src。 */
  async openFile(fileId) {
    try {
      window.open(await this.fileBlobUrl(fileId), '_blank');
    } catch (e) { /* toast 已弹 */ }
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

  /** 体积显示（附件列表、演出照片都用它）。 */
  sizeText(bytes) {
    if (!bytes) {
      return '';
    }
    return bytes >= 1024 * 1024
      ? (Math.round(bytes / 1024 / 102.4) / 10) + ' MB'
      : Math.round(bytes / 1024) + ' KB';
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

  /** 摘掉欢迎过渡黑幕。docs/14 §14.6.4 的硬要求：**任何提前退出的路径都必须摘**
      （`band-nav.js` 在首帧前就武装了它，摘的人漏一条就是全黑且无路可走）。
      标记不清——`playWelcome` 自己会清，留着才能"下一页重试"。 */
  disarmWelcome() {
    document.documentElement.classList.remove('wel-armed');
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

  /**
   * 导航（docs/14 §14.6.4）：常驻图标轨 + 悬停/Tab 展开到预留位。
   * DOM 由这里注入（只渲染一份）；菜单按角色渲染；把页面既有内容挪进 `.main`。
   *
   * ⚠️ **首帧就该是最终布局**：骨架（`mountSkeleton`）与内容搬迁是**同步**做的，
   * 依赖身份的文字/菜单随后才填（`paintNav`：先用缓存、拿到接口结果再填一遍）。
   * 以前是"先 await `/api/auth/me`、回来才搭骨架"，于是首帧画的是**裸内容顶在左上角**，
   * 接口回来后内容被搬进 `.main` → **右移 ≈354px、下移 ≈146px**，左侧同时冒出 92px 的柱子——
   * 用户看到的就是"切页闪一下"。
   */
  async renderNav(active) {
    this.loadCachedAvatars();                          // ⓪ 同步：本会话缓存过的头像清单先就位，首帧才画得对
    const skeleton = this.mountSkeleton(active);       // ① 同步：布局一次到位
    this.paintNav(skeleton, this.cachedMe(), active);  // ② 先用缓存填（没有就留空，不编数据）
    // 身份与头像清单**并行**取（清单很小）；avatars() 自带 catch，不会把这条链拖垮
    const [me] = await Promise.all([this.requireUser(), this.avatars()]);
    if (!me) return null;                              // 401 已在 api() 里跳转；其他错误已 toast + 摘黑幕
    this.paintNav(skeleton, me, active);               // ③ 再用服务端**权威**数据填一遍

    this.mountProfile(skeleton.layout, me);
    // ⚠️ `mountRailKeep` **不在这里**了：2026-09-25 起它随骨架**同步绑**（在 `mountSkeleton` 里），
    // 否则那几十~几百毫秒没监听，指针甩到正文会卡在展开态（详见它的方法注释）。
    this.refreshReleaseDot();
    // ⚠️ **三者顺序写死**（docs/15 §15.5）：欢迎过渡 → 更新公告 → **关掉公告才加载 oneko**。
    // 猫的 z-index 是最大整数，不等公告关掉就会从公告上跑过去（当年"从欢迎黑幕上跑过去"是同款坑）。
    // fromLogin **必须在 playWelcome 之前读**——它一进去就把 band_welcome 标记删了。
    let fromLogin = false;
    try { fromLogin = sessionStorage.getItem('band_welcome') === '1'; } catch (e) { /* 隐私模式 */ }
    this.playWelcome()
      .then(() => this.announce(fromLogin))
      .then(() => this.announceBirthday(fromLogin))   // 生日通告**排在公告之后**（docs/15 §15.5 的四者顺序）
      .then(() => this.loadCat());
    return me;
  },

  /** 图标列与页面标题表（docs/14 §14.6.4）：菜单项 [href, 名字, 图标字, 角色白名单]。 */
  navItems: [
    ['events.html', '活动', '活', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['members.html', '名册', '册', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['my-leave.html', '我的请假', '假', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
    // 曲目 / 演出 / 录音：**所有角色都能看**（含荣誉团员——曲目附件另由文件接口按角色挡；
    // 荣誉团员在录音页只看得到"自己发过的"、上传区不渲染），增删改都在服务层收口
    ['pieces.html', '曲目', '曲', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['concerts.html', '演出', '演', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['recordings.html', '录音', '录', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['stats.html', '考勤统计', '计', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['approve-leave.html', '请假审批', '批', ['ADMIN', 'LEADER', 'SECTION_LEADER']],
    ['admin-events.html', '活动管理', '理', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER']],
    ['admin-members.html', '团员管理', '员', ['ADMIN']],
    ['admin-feedback.html', '反馈管理', '馈', ['ADMIN']],
    ['admin-notes.html', '更新公告', '告', ['ADMIN']],
    ['logs.html', '操作日志', '志', ['ADMIN', 'LEADER']],
  ],

  /** 不在主导航里的页面取不到菜单项，标题单独给——别把它们塞进 `navItems`（那会多出一个菜单项）。 */
  offMenuTitles: {
    'sessions.html': '登录设备',
    'profile.html': '个人资料',
    'updates.html': '更新日志',
    'piece-detail.html': '曲目详情',
    'concert-detail.html': '演出详情',
    'member-detail.html': '团员主页',
  },

  /**
   * ① 同步搭骨架：`.layout` / `.nav-slot` / `.railwrap` / `.rail` / `.main` / `.head`，
   * 并把 `#navbar` 之后的页面内容**搬进 `.main`**。**完全不依赖 `me`**——所以能在首帧前跑完。
   * 底栏的「退出」也在这里绑（它与身份无关）。
   * ⚠️ 「声音 开/关」2026-09-25 已**搬去个人栏**（`mountProfile`）——底栏那 4 项里
   * 「登录设备」「退出」与个人栏入口行**逐字重复**，见 `docs/14` §14.6.4「底部只留品牌一行」。
   * 收起判定（`#46` 的几何判断）也在这里同步绑——见 `mountRailKeep` 的说明。
   */
  mountSkeleton(active) {
    const layout = document.createElement('div');
    layout.className = 'layout';
    layout.innerHTML = `
      <div class="nav-slot"><span class="hit" aria-hidden="true"></span><div class="railwrap"><aside class="rail">
        <button class="me" type="button" aria-expanded="false" aria-controls="profile">
          <span class="ic"><span></span></span>
          <span class="tx"><span class="me-name"></span> <em class="me-sec"></em></span>
        </button>
        <nav class="menu"></nav>
        <div class="foot">
          <div class="brand">山水民族管弦乐团</div>
        </div>
      </aside></div></div>`;

    const main = document.createElement('main');
    main.className = 'main';
    const head = document.createElement('div');
    head.className = 'head';
    const headL = document.createElement('div');
    headL.className = 'head-l';
    // 欢迎行（docs/14 §14.6.4）：姓名朱砂、后面跟职称，不用标点；它是欢迎过渡的落点之一
    const welcome = document.createElement('p');
    welcome.className = 'welcome';
    welcome.append('欢迎回来，');
    const wname = document.createElement('span');
    wname.className = 'wname';
    const wrole = document.createElement('span');
    welcome.append(wname, wrole);
    const h1 = document.createElement('h1');
    headL.append(welcome, h1);
    // 右侧：声部 + 两个次级入口（反馈 / 更新）。**必须包一层**——.head 是 space-between 的 flex，
    // 三个裸子项会被均匀推开（docs/14 §14.6.4「右上角操作区」）。
    const headR = document.createElement('div');
    headR.className = 'head-r';
    headR.innerHTML = '<span class="greet"></span>';
    head.append(headL, headR);
    main.appendChild(head);
    layout.appendChild(main);

    // 把 #navbar 之后的页面内容全部挪进 .main（toast-host 留在 body 上）
    const host = document.getElementById('navbar');
    const moved = [];
    let node = host.nextSibling;
    while (node) {
      const next = node.nextSibling;
      if (node.nodeType === Node.ELEMENT_NODE && node.id !== 'toast-host') moved.push(node);
      node = next;
    }
    moved.forEach((n) => main.appendChild(n));
    host.replaceWith(layout);   // ← 这一步之后，布局就定了；接口还没回来也不影响首帧

    // 底栏现在只剩品牌一行，**没有监听器要绑**了——「退出」与「声音」都搬去了个人栏
    //（`#p-logout` / `#p-sound`，见 `mountProfile`）。
    // ⚠️ 收起判定**必须在这里同步绑**（不能等 `await requireUser()` 回来），详见 `mountRailKeep`。
    this.mountRailKeep(layout);

    return {
      layout, active,
      menu: layout.querySelector('.menu'),
      av: layout.querySelector('.me .ic span'),
      meName: layout.querySelector('.me-name'),
      meSec: layout.querySelector('.me-sec'),
      wname, wrole, h1,
      greet: headR.querySelector('.greet'),
      headR,
    };
  },

  /**
   * ②/③ 幂等填充：菜单（按角色过滤）、头像、姓名·声部、欢迎行职称、页标题、右上角操作区。
   * 传 `null`（还没拿到身份）就把这些**留空**——不编数据；拿到接口结果后再调一次即纠正。
   */
  paintNav(s, me, active) {
    // 菜单：只有知道角色才画得出（别的角色看到的是另一套）
    s.menu.innerHTML = !me ? '' : this.navItems
      .filter(([, , , roles]) => roles.includes(me.role))
      .map(([href, label, icon]) =>
        `<a href="/${href}"${href === active ? ' class="on"' : ''}>` +
        `<span class="ic" aria-hidden="true">${icon}</span><span class="tx">${label}</span></a>`)
      .join('');
    s.av.textContent = '';
    if (!me) {
      s.meName.textContent = '';
      s.meSec.textContent = '';
      s.wname.textContent = '';
      s.wrole.textContent = '';
      s.greet.textContent = '';
    } else {
      this.paintAvatar(s.av, me);   // 有头像用头像、否则姓名首字；一律 textContent，防注入
      s.meName.textContent = me.name;
      s.meSec.textContent = me.sectionName || '';
      s.wname.textContent = me.name;
      s.wrole.textContent = this.roleLabel(me.role) || me.role;
      s.greet.textContent = me.sectionName || '';
    }
    const activeLabel = this.navItems.find(([href]) => href === active);
    s.h1.textContent = activeLabel ? activeLabel[1]
      : (this.offMenuTitles[active] || (me ? me.name : ''));
    // 右上角操作区要重建（它按角色决定画不画「反馈」）：先清掉上一轮的，再画
    s.headR.querySelectorAll('.hact').forEach((el) => el.remove());
    if (me) {
      this.mountHeadActions(s.headR, me);
    }
  },

  /** 个人栏（docs/14 §14.6.4）：点头像弹出浮层面板（非整页）；桌面从侧栏侧滑出，
     手机（≤720px）从底部升起。Esc/点外关闭；触发按钮带 aria-expanded。
     四层：身份行 / 数字（排练、声部课分开算，出勤率与请假次数成对）/ 最近流水 / 入口。
     不做：编辑资料（账号操作全归管理员）、大封面、排行榜。 */
  /**
   * 点过菜单项 → 新页面**仍然展开**，直到指针越过「命中区」右缘才收起（docs/14 §14.6.4）。
   *
   * 语义（2026-09-23 修 bug 后重写；2026-09-25 又修两处，见下）：
   * 1. 标记 `band_nav_keep` 是"**给下一页用一次**"的意图：菜单项 `mousedown` 时写下**时间戳**，
   *    下一页在 `<head>` 里（`band-nav.js`）读走并**立刻消费掉**（`removeItem`），之后不再有人碰它。
   *    ⚠️ **本页绝不能清这个标记**：以前 `release()` 顺手 `removeItem`，于是"点完菜单项 → 跳页"
   *    那几十毫秒里鼠标一动就把意图**撤回了**，新页首帧便是收起的——症状就是"有时展开、有时点完就缩回去"。
   * 2. 记标记的时机是菜单项上的 **`mousedown`**——不能用 `click`（**键盘 Enter 激活链接也触发 click**），
   *    也不能"悬停侧栏就记"（那样在正文里点任何按钮跳页也会展开侧栏）。
   *
   * ⚠️ **2026-09-25 改了两处（都是 bug 修复，旧写法与旧理由保留在上面）**：
   * (a) **收起判定从"命中测试"改成"几何判断"**：原来是一条**一次性** `pointermove`，
   *     里面用 `wrap.contains(e.target)`。问题是**全屏遮罩**（欢迎黑幕 / 更新公告，`position:fixed; inset:0`）
   *     一盖，`e.target` 就不再是侧栏里的东西了，指针动一下就被判成"已移出" → 误收。
   *     现在量**命中区**的右缘：`clientX > hit.right + 8` 才收（`+8` 是滞回带）。
   *     ⚠️ **绝不能用 `.railwrap` 的 `getBoundingClientRect()` 当阈值**——它的右缘就是那个
   *     92 ↔ 320 的**动画值**，阈值每帧都在动，又是一种抖。
   * (b) **绑定时机提前到 `mountSkeleton`（同步）**：原来跑在 `await requireUser()` 之后，
   *     那几十~几百毫秒里**根本没有监听**，指针这时甩到正文就会**卡在展开态**。
   *     提前之后**必须事件委托**——`paintNav` 会把菜单 `innerHTML` 重写两遍（首访无缓存时三遍），
   *     逐项绑的 `mousedown` 会跟着被销毁。所以挂在 `.menu` 上（它由骨架创建、**永不重建**）。
   */
  mountRailKeep(layout) {
    if (!window.matchMedia
        || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) {
      return;   // 手机端没有展开/收起这回事，行为与之前完全一致
    }
    const root = document.documentElement;
    const menu = layout.querySelector('.menu');
    const hit = layout.querySelector('.hit');   // 骨架里就有（现在不在 DOM 上也没关系，几何运行时才算）
    if (!menu || !hit) {
      return;
    }
    const keep = () => {
      // 时间戳而不是 '1'：旧页不清标记，万一 mousedown 了却没跳转（按下后拖走 = 无 click），
      // 这枚标记就该自己过期，而不是留到很久以后的某次跳页才被用掉
      try { sessionStorage.setItem('band_nav_keep', String(Date.now())); } catch (e) { /* 隐私模式 */ }
      root.classList.add('rail-keep');
    };
    const release = () => root.classList.remove('rail-keep');   // 只摘类：**不碰标记**
    // 事件委托：`e.target` 落在某个菜单链接里才算"点过菜单项"
    menu.addEventListener('mousedown', (e) => {
      if (!e.target || !e.target.closest || !e.target.closest('a')) return;
      keep();
    });
    // 常驻监听（不再"只在已展开时才绑"）：提前绑的目的就是别让"还没监听"的窗口存在
    document.addEventListener('pointermove', (e) => {
      if (!root.classList.contains('rail-keep')) return;
      if (e.clientX > hit.getBoundingClientRect().right + 8) release();
    });
  },

  mountProfile(layout, me) {
    const meBtn = layout.querySelector('.me');
    // 荣誉团员只读（docs/02 §2.3）：入口对他们**不显示**（docs/14 §14.6.4），
    // 不然点进去只会撞 403。后端 `MeProfileService.update()` 才是真闸门。
    // 底部去重（docs/14 §14.6.4「底部只留品牌一行」，2026-09-24）：
    // 「声音 开/关」从侧栏底栏**搬到这里**；底栏原来那 4 项里「登录设备」「退出」与本行**逐字重复**、
    // 已删掉。声音与身份无关，所以荣誉团员那一支也要有它（所以放在 `concat` 里，不在条件分支里）。
    const soundOn = localStorage.getItem('band_sound') !== '0';
    const acts = (me.role === 'HONORARY' ? [] : ['<a href="/profile.html">编辑资料</a>'])
      .concat(['<a href="#" id="p-pw">改密码</a>',
               `<a href="#" id="p-sound">声音 ${soundOn ? '开' : '关'}</a>`,
               '<a href="/sessions.html">登录设备</a>',
               '<a href="#" id="p-logout">退出</a>'])
      .join(' · ');
    const panel = document.createElement('aside');
    panel.className = 'profile';
    panel.id = 'profile';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', '个人栏');
    panel.innerHTML = `
      <div class="p-id">
        <span class="p-av"></span>
        <div class="p-who">
          <div class="p-name"></div>
          <div class="p-tags"></div>
        </div>
      </div>
      <div class="p-nums"></div>
      <div class="p-flow">
        <div class="p-flow-head">
          <span class="lab">最近</span>
          <a href="/my-leave.html">看全部</a>
        </div>
        <div class="p-flow-list"></div>
      </div>
      <div class="p-acts">${acts}</div>
      <form class="p-pw-form" id="p-pw-form" hidden>
        <input type="password" id="p-old" placeholder="原密码" autocomplete="current-password" required>
        <input type="password" id="p-new" placeholder="新密码（至少 8 位）" autocomplete="new-password" minlength="8" required>
        <button class="go go-sm" type="submit">改密码</button>
      </form>`;
    const backdrop = document.createElement('div');
    backdrop.className = 'profile-backdrop';
    document.body.append(backdrop, panel);

    // 用户数据一律 textContent（防注入）
    this.paintAvatar(panel.querySelector('.p-av'), me);
    panel.querySelector('.p-name').textContent = me.name;
    panel.querySelector('.p-tags').textContent = this.identityTags(me);

    const setOpen = (open) => {
      panel.classList.toggle('open', open);
      backdrop.classList.toggle('open', open);
      meBtn.setAttribute('aria-expanded', String(open));
      if (open) this.loadProfileData(panel, me);
    };
    meBtn.addEventListener('click', (e) => {
      e.preventDefault();
      setOpen(!panel.classList.contains('open'));
    });
    backdrop.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') setOpen(false);
    });

    panel.querySelector('#p-logout').addEventListener('click', async (e) => {
      e.preventDefault();
      await this.api('/api/auth/logout', { method: 'POST' });
      this.clearToken();
      location.href = '/login.html';
    });
    panel.querySelector('#p-pw').addEventListener('click', (e) => {
      e.preventDefault();
      panel.querySelector('#p-pw-form').hidden = !panel.querySelector('#p-pw-form').hidden;
    });
    // 「声音 开/关」：**监听器在这边重绑**（原来绑在 `mountSkeleton` 建的那个节点上，
    // 2026-09-25 那个节点已随底部去重删掉——留着就是一个指向空节点的悬空监听器）。
    // ⚠️ 文案按**当时**的 localStorage 重算：`mountSkeleton` 里那份是首帧时读的，可能已经过期。
    // 用 `currentTarget` 而不是 `target`：文案挂在这个锚点自身，但这样写对子节点也安全。
    panel.querySelector('#p-sound').addEventListener('click', (e) => {
      e.preventDefault();
      const nowOn = localStorage.getItem('band_sound') !== '0';
      localStorage.setItem('band_sound', nowOn ? '0' : '1');
      e.currentTarget.textContent = '声音 ' + (nowOn ? '关' : '开');
    });
    panel.querySelector('#p-pw-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      await this.must('/api/auth/password', {
        method: 'POST',
        body: {
          oldPassword: panel.querySelector('#p-old').value,
          newPassword: panel.querySelector('#p-new').value,
        },
      });
      // 改密码成功 → 全部 token 失效（含当前），回登录页重新登
      this.toast('密码已改，请用新密码重新登录', 'success');
      setTimeout(() => location.href = '/login.html', 900);
    });
  },

  /** 数字与最近流水按需拉取（每次展开刷新一次；失败静默——面板其余部分照常用）。 */
  async loadProfileData(panel, me) {
    const nums = panel.querySelector('.p-nums');
    const flow = panel.querySelector('.p-flow-list');
    try {
      const s = await this.api(`/api/members/${me.id}/attendance-summary`);
      if (s.code === 0) {
        const k = s.data;
        // 不参与考勤的人（指挥 / 荣誉团员，docs/13 不变式 14）分母天然为 0：照常显示数字，
        // 出勤率那一格显示「—」。空状态那句预告是留给"还没签到过的新团员"的，别用在他们身上。
        const noAttend = ['CONDUCTOR', 'HONORARY'].includes(me.role);
        const empty = !noAttend && k.rehearsal.expected === 0 && k.sectionClass.expected === 0;
        nums.innerHTML = empty
          ? '<p class="p-empty">完成第一次签到后，这里会出现你的五声点阵</p>'
          : `<div class="p-num"><b>${this.ratePct(k.rehearsal.rate, k.rehearsal.expected)}</b><span>排练出勤 · 请假 ${k.rehearsal.leave}</span></div>
             <div class="p-num"><b>${this.ratePct(k.sectionClass.rate, k.sectionClass.expected)}</b><span>声部课出勤 · 请假 ${k.sectionClass.leave}</span></div>
             <div class="p-num"><b>${k.rehearsal.present + k.sectionClass.present}</b><span>参加场次</span></div>
             <div class="p-num"><b>${me.joinedAt ? this.daysSince(me.joinedAt) : '—'}</b><span>入团天数</span></div>
             <div class="p-num"><b id="p-rec-count">—</b><span>录音条数</span></div>`;
      }
    } catch (e) { /* 面板数字失败不阻断 */ }
    try {
      // 录音条数（规格标的"期 5 并入"）：用**现成接口**的 total，不为它新增接口；
      // 可见性由服务端收口，所以这里天然只会数到"我听得见的"那些
      const rec = await this.api(`/api/recordings?memberId=${me.id}&size=1`);
      const slot = document.getElementById('p-rec-count');
      if (slot) {
        slot.textContent = rec.code === 0 ? rec.data.total : '—';
      }
    } catch (e) { /* 数字失败不阻断 */ }
    try {
      const r = await this.api('/api/me/recent-activity?limit=5');
      if (r.code === 0) {
        flow.innerHTML = '';
        if (!r.data.length) {
          const p = document.createElement('p');
          p.className = 'p-empty';
          p.textContent = '还没有签到或请假记录';
          flow.appendChild(p);
          return;
        }
        for (const it of r.data) {
          const row = document.createElement('div');
          row.className = 'p-flow-item';
          const when = it.time ? it.time.slice(5, 10) : '';
          row.textContent = `${when}　${it.title || ''}　${it.note || ''}`;
          flow.appendChild(row);
        }
      }
    } catch (e) { /* 流水失败不阻断 */ }
  },

  /** 欢迎过渡（docs/14 §14.6.4）：登录成功那次，黑幕上头像与欢迎行飞往页面真实位置。
     黑幕的显示由 band-nav.js 首帧前加的 wel-armed 类控制；这里读掉并清掉标记，
     落点全部运行时测量（getBoundingClientRect），手机端顶栏形态也自动对上。 */
  playWelcome() {
    // ⚠️ 执行器写成 **async** 只为下面那一句 `await document.fonts.load(...)`（字体就绪再量矩形）：
    //    它抛出的异常全被 try/catch 吞掉，所以不会变成"没人接的 rejection"。
    return new Promise(async (resolve) => {
      const wel = document.getElementById('wel');
      const root = document.documentElement;
      let hadFlag = false;
      try { hadFlag = sessionStorage.getItem('band_welcome') === '1'; } catch (e) { /* 忽略 */ }
      try { sessionStorage.removeItem('band_welcome'); } catch (e) { /* 忽略 */ }
      const disarm = () => this.disarmWelcome();
      const reduced = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      // 目标页没有黑幕 / 没有标记 / 用户要求减少动效 → 直接放行，绝不卡在黑屏上
      if (!wel || !hadFlag || !root.classList.contains('wel-armed') || reduced) {
        disarm();
        resolve();
        return;
      }

      // ⭐ **字体就绪之后才量矩形**（docs/14 §14.6.5 与 `交接单.md` `#48` ④）：
      // 子集字体还没落地时量到的是**回退黑体**的字宽 ⇒ FLIP 落点会偏，
      // 而且只在"首次访问、字形没进缓存"那一次明显（之后字形已缓存，量得就对了）。
      // ⚠️ 落点必须在**挂 skip 监听之前**：否则用户在等待期间点屏触发 skip() → place()，
      //    量的还是未就绪的字面。
      // ⚠️ 异常一律吞掉：老浏览器没有 `document.fonts`、或字体加载失败，都必须**继续往下走**——
      //    **绝不许把黑幕留在屏幕上**（docs/14:1467）。
      try {
        if (document.fonts && document.fonts.load) {
          // ⚠️ **给这个等待封顶 350ms**：字体正常情况下是本地的 27.7 KB、几毫秒就就绪；
          // 但万一它慢或被卡住（机器内存紧张时磁盘/换页会拖），**绝不能把用户晾在黑幕上干等**——
          // 超时就用回退字面量继续跑，落点最多差一点，比"黑屏等着"强得多。
          await Promise.race([
            document.fonts.load('600 33px BandName'),
            new Promise((r) => setTimeout(r, 350)),
          ]);
        }
      } catch (e) { /* 跳过，不阻断欢迎过渡 */ }

      const av = wel.querySelector('.wel-av');
      const tx = wel.querySelector('.wel-tx');
      const wname = tx.querySelector('.wname');
      const me = this.me;
      this.paintAvatar(av, me);   // 落点那份也要跟着头像走（第三处，最容易漏）
      wname.textContent = me.name;
      tx.append(this.roleLabel(me.role));   // 名字与职称之间不用标点（docs/14 §14.6.4）

      const timers = [];
      const after = (ms, fn) => timers.push(setTimeout(fn, ms));
      const clearTimers = () => { timers.forEach(clearTimeout); timers.length = 0; };
      let playing = true;

      // 落点：**运行时量出来**（换宽度、手机端都会自动对上，绝不写死坐标）
      const targets = () => ({
        av: document.querySelector('.rail .me .ic span'),
        tx: document.querySelector('.welcome'),
      });
      const place = () => {
        const t = targets();
        if (!t.av || !t.tx) { finish(0); return; }
        const ra = t.av.getBoundingClientRect(), ca = av.getBoundingClientRect();
        const rt = t.tx.getBoundingClientRect(), ct = tx.getBoundingClientRect();
        // 头像是圆：以中心对齐 + 按宽度缩放；文字以左上角对齐 + 按宽度缩放
        av.style.transform = 'translate(' + (ra.left + ra.width / 2 - ca.left - ca.width / 2) + 'px,'
          + (ra.top + ra.height / 2 - ca.top - ca.height / 2) + 'px)'
          + ' scale(' + (ra.width / ca.width) + ')';
        tx.style.transform = 'translate(' + (rt.left - ct.left) + 'px,' + (rt.top - ct.top) + 'px)'
          + ' scale(' + (rt.width / ct.width) + ')';
      };
      const finish = (delay) => {
        after(delay, () => {
          // 先让遮罩里的头像/文字淡掉，**再**淡黑底：否则会和页面自己那一份重叠一瞬（双影）
          av.style.transition = tx.style.transition = 'opacity 160ms linear';
          av.style.opacity = tx.style.opacity = '0';
          after(170, () => {
            wel.style.transition = 'opacity 260ms linear';
            wel.style.opacity = '0';
            after(280, () => {
              disarm();
              playing = false;
              document.removeEventListener('keydown', onKey);
              // 收尾：给欢迎行加 `.draw-on` ⇒ 姓名下面那道笔划"**划出**"（docs/14 §14.6.4；`交接单` `#53`）。
              // ⚠️ **只在登录那一次**：本函数只在带 `band_welcome` 标记时才走到这里，
              //    其它页面加载走的是上面那条早退分支 ⇒ 笔划静态可见、**不重播**（每页都播就退化成纯装饰）。
              // ⚠️ reduced-motion 下根本到不了这里（早退分支已含 reduced）⇒ 笔划全程不动；
              //    `band.css` 的 reduced-motion 块里还有一条 `animation: none` 兜底。
              const w = document.querySelector('.welcome');
              if (w) w.classList.add('draw-on');
              resolve();
            });
          });
        });
      };
      const reset = () => {
        av.style.transition = tx.style.transition = 'none';
        av.style.transform = tx.style.transform = 'none';
        av.style.opacity = tx.style.opacity = '0';
        wel.style.transition = 'none';
        wel.style.opacity = '1';
      };

      const skip = () => {
        if (!playing) return;
        clearTimers();
        av.style.transition = tx.style.transition = 'none';
        tx.style.opacity = '1';
        place();
        finish(60);
      };
      function onKey() { skip(); }
      wel.addEventListener('click', skip);
      document.addEventListener('keydown', onKey);   // 点任意处 / 任意键可跳过

      reset();
      requestAnimationFrame(() => {
        av.style.transition = tx.style.transition = 'opacity 240ms linear';
        av.style.opacity = '1';
        after(190, () => { tx.style.opacity = '1'; });
        after(700, () => {                            // 停一下，再飞
          av.style.transition = tx.style.transition =
            'transform 620ms cubic-bezier(.44, 0, .2, 1), opacity 200ms linear';
          place();
          finish(660);
        });
      });
    });
  },

  /** 萌宠 oneko（docs/14 §14.6.4「页面萌宠」）：登录后所有页、仅桌面。
     matchMedia 不满足时**连脚本都不插**（不是加载了再藏）——触屏没有光标可追。 */
  loadCat() {
    if (!window.matchMedia
        || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    if (document.getElementById('oneko')) return;
    const s = document.createElement('script');
    s.src = '/vendor/oneko/oneko.js';
    s.dataset.cat = '/vendor/oneko/oneko.gif';
    document.body.appendChild(s);
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
};
