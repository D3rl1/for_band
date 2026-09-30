'use strict';

/**
 * 「界面的活性」（`docs/14` §14.6.4 那四样 + §14.6.6 **第七条留痕**）。
 * 定档样张 `design-samples/alive-mock.html` 是用户拍板过的真值；这里只搬算法、不带样张的档位开关与重播按钮。
 *
 * ⚠️ **三条硬约束**：
 * ① **起点必须先被浏览器真的画过一帧**：**同步**写起点 → **双** `requestAnimationFrame` → 再改终态。
 *    少这一步的症状是"看着没响应 / 没有生长"（浏览器把两步合成一步）。本项目第二次踩，第一次是专页背景交叉淡入。
 * ② **只在"首次出现"播一次**：`played` 记的是**常驻容器**（不是里面的数字元素——那些节点会被重渲染换掉），
 *    播过就什么都不做 ⇒ 筛选 / 翻页 / 重展开导致的重渲染**天然直接给终值**。
 * ③ **启动间隔与并发峰值由队列自己记**（`window.__alive`）：Windows 上窗口被遮挡时 Chromium 会把 rAF 饿死
 *    （实测帧间隔 400–1100ms），探针逐帧采样量出来全是帧量化的假象。
 */
const ALIVE = (() => {
  const MAX_RUN = 5;      // 同一时刻在滚的上限（第 6 个排队等前面的滚完）
  const STAGGER = 40;     // 相邻启动的错开间隔（ms）
  const DUR = 700;        // 单个数字的时长（easeOutCubic）
  const ROW_STAGGER = 40; // 列表前 5 行的错开间隔

  /* 给探针读的钩子：`starts`/`peak` 是规格点名要的两个量（不许逐帧采样）；
     `running`/`queued` 让探针能等"队列真的排空"，而不是猜一个时间——
     ⚠️ 队列在两波之间会停顿几百毫秒（rAF 被遮挡时更久），猜时间会把中间值当成终值。 */
  const stats = { starts: [], peak: 0, running: 0, queued: 0 };
  window.__alive = stats;

  const played = new WeakSet();
  /* ⚠️ 键用**元素**、不用 `el.id`：拆出来的 `<span>` 没有 id，用 id 会让它们全挤在一个键上。 */
  const running = new Map();
  const queue = [];
  let runningCount = 0;
  let lastStart = -1e9;
  let pumpTimer = null;

  const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const fmt = (v, dec, suf) => v.toFixed(dec) + suf;

  /**
   * 从元素**原文**反解 `to` / 小数位 / 后缀 —— 终值不自己算，所以精度天然与 `ratePct` 等
   * 现成格式化函数的输出一致（规格要求"不许自己再 `Math.round` 一遍"）。
   * 非数字（`—`、日期、分页文字、字节量级）返回 null ⇒ **不数**。
   */
  function specOf(el) {
    const raw = el.dataset.final;
    if (raw === undefined) return null;
    const suf = raw.endsWith('%') ? '%' : '';
    const num = suf ? raw.slice(0, -1) : raw;
    if (!/^-?\d+(\.\d+)?$/.test(num)) return null;
    return { to: Number(num), dec: num.includes('.') ? num.split('.')[1].length : 0, suf };
  }

  function settle(el) {
    el.textContent = el.dataset.final;   // ⚠️ 逐字还原原文，不自算终值
    running.delete(el);
    runningCount--;
    stats.running = runningCount;
    pump();
  }

  function start(el) {
    const spec = specOf(el);
    if (!spec) { settle(el); return; }
    const t0 = performance.now();
    const tick = (now) => {
      /* ⚠️ **两头都夹**：rAF 回调收到的 `now` 可能早于上面那句 `performance.now()` ⇒ `p` 变负
         ⇒ `1-(1-p)³` 也变负 ⇒ 第一帧滚出一个负数（样张实测滚出过 `-6%`）。 */
      const p = Math.max(0, Math.min(1, (now - t0) / DUR));
      const txt = fmt(spec.to * (1 - Math.pow(1 - p, 3)), spec.dec, spec.suf);   // easeOutCubic
      if (el.textContent !== txt) el.textContent = txt;   // 只在真的变了才写，少触发布局
      if (p < 1) running.set(el, requestAnimationFrame(tick));
      else settle(el);
    };
    running.set(el, requestAnimationFrame(tick));
  }

  /** 拉队列：**错开 STAGGER 启动、同一时刻最多 MAX_RUN 个在滚**；谁滚完谁让位。 */
  function pump() {
    if (pumpTimer) { clearTimeout(pumpTimer); pumpTimer = null; }
    stats.queued = queue.length;
    while (queue.length && runningCount < MAX_RUN) {
      const wait = lastStart + STAGGER - performance.now();
      if (wait > 0) {
        pumpTimer = setTimeout(pump, Math.ceil(wait));
        return;
      }
      lastStart = performance.now();
      runningCount++;
      stats.running = runningCount;
      const el = queue.shift();
      stats.queued = queue.length;
      stats.starts.push({ t: Math.round(performance.now()) });
      stats.peak = Math.max(stats.peak, runningCount);
      start(el);
    }
    stats.queued = queue.length;
    stats.running = runningCount;
  }

  function play(...roots) {
    for (const root of roots) {
      if (!root || played.has(root)) continue;
      const numbers = [...root.querySelectorAll('[data-count]')];
      const dots = [...root.querySelectorAll('.penta .pv')];
      const lit = dots.map((d) => d.classList.contains('on'));   // ⚠️ 要在摘掉之前记下"哪些该亮"
      const bars = [...root.querySelectorAll('.attbar b')];
      const rows = [...root.querySelectorAll('.tb tbody tr')];
      // 空态（还没数据）：**不占位**，将来有数据时仍能播
      if (!numbers.length && !dots.length && !bars.length && !rows.length) continue;
      played.add(root);

      let done = false;
      const finalize = () => {
        if (done) return;
        done = true;
        numbers.forEach((el) => { if (specOf(el)) queue.push(el); });
        pump();
        dots.forEach((d, i) => { if (lit[i]) d.classList.add('on'); });
        bars.forEach((b) => b.classList.add('done'));
        rows.forEach((tr, i) => {
          if (reduced()) tr.classList.add('in');
          else setTimeout(() => tr.classList.add('in'), i < 5 ? i * ROW_STAGGER : 0);
        });
      };

      if (reduced()) { finalize(); continue; }   // 降级是"减少不是删掉"：全部直接给终态，功能一样不缺

      // ① **同步**写到"起点"（必须在首绘之前，双 rAF 之后才改终态）
      numbers.forEach((el) => {
        if (el.dataset.final === undefined) el.dataset.final = el.textContent;
        const spec = specOf(el);
        if (spec) el.textContent = fmt(0, spec.dec, spec.suf);
      });
      dots.forEach((d) => d.classList.remove('on'));   // 标记里自带 `.on`，不在首绘前摘掉就"一进来就是满的"
      rows.forEach((tr) => tr.classList.add('ar'));    // ⚠️ 起点类下到**行**：`tbody` 级会连后来插入的行一起藏住
      // ②③ 双 rAF（首次绘制那一帧把起点真的画出来）→ 改终态
      requestAnimationFrame(() => requestAnimationFrame(finalize));
      setTimeout(finalize, 900);   // 兜底：万一两次 rAF 之间抛错，点阵 / 条不会永远停在起点
    }
  }

  return { play };
})();

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

  /**
   * 「上任声部长的留言」读到哪一条了（`docs/03` §3.5）——**设备级**，记的是那条的 **id**
   * （与生日通告记"日期"不同：留言是**一条一条**的，"读过这一条"才够准）。
   * ⚠️ 读完之后内容**不会消失**，只是**不再弹**（它常驻在右上角那个「信封」里）。
   */
  seenSectionNote() {
    try {
      return Number(localStorage.getItem('band_seen_section_note')) || 0;
    } catch (e) {
      return 0;   // 隐私模式：当作没读过 → 会再弹一次，可接受
    }
  },

  markSectionNoteSeen(id) {
    try { localStorage.setItem('band_seen_section_note', String(id)); } catch (e) { /* 忽略 */ }
  },

  /** 本地日期 `YYYY-MM-DD`。⚠️ **别用 `toISOString()`**——那是 UTC，跨日会错一天。 */
  localDayKey() {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  },

  /**
   * 右上角操作区（`docs/14` §14.6.4「右上角操作区（铃铛 + 信封）」）：
   * **铃铛**（原「反馈 + 更新」两个汉字入口**合并成一个**，点开小面板两项）+ **信封**（上任声部长的留言）。
   * ⚠️ 2026-09-30（`交接单 #118`）用户原话：「**其它两个合并在一起呗，放个单色铃铛**，
   * 然后声部长留言就放信封图标」。**这不是把已有的挪个位置，是把两个入口收成一个。**
   * ⚠️ **都不带文字**（用户原话「单独摆个字在这也不好看」）⇒ **`aria-label` 是它们唯一的可读名**，
   * 别只写 `aria-hidden`；`.head-r` 的 `align-items` 也跟着从 `baseline` 改成 `center`（`band.css`）。
   * ⚠️ **两个宿主**（2026-09-28 改；用户真机反馈"手机上点不动、位置也乱"）：
   *   桌面挂 `s.headR`（右上角操作区，原样）；**手机挂 `s.mhead`（那条 sticky 标题栏）**。
   *   为什么必须换宿主：手机端 `.head{display:contents}` 之后 `.head-r` 掉成**正文里的通栏块**
   *   （位置乱），而且它**没有任何 z-index**、会被 `sticky; z-index:20` 且不透明的标题栏盖住（点不动）
   *   —— 挪进标题栏两件事一起解决。⚠️ **只判一次**（`isMobile()` 与手机段媒体查询条件逐字相同），
   *   不监听 resize —— 照 `mountRailKeep` 的既有先例。
   */
  mountHeadActions(s, me) {
    const host = this.isMobile() ? s.mhead : s.headR;
    const bell = document.createElement('button');
    bell.type = 'button';
    bell.className = 'hact hact-bell';
    bell.setAttribute('aria-label', '消息');
    bell.setAttribute('aria-haspopup', 'true');
    bell.setAttribute('aria-expanded', 'false');
    bell.innerHTML = '<span class="hact-ic" aria-hidden="true">' + this.iconSvg('bell') + '</span>'
      + '<span class="hact-dot" hidden></span>';
    bell.addEventListener('click', () => this.openHeadPanel(bell, me));
    host.appendChild(bell);
    if (me.everSectionLeader) {
      // ⭐ **条件渲染**（`docs/03` §3.5）：只有**历任与现任声部长**看得到信封——
      //   普通团员那个位置**只有铃铛**（**不是留个灰的**）。
      const mail = document.createElement('button');
      mail.type = 'button';
      mail.className = 'hact hact-mail';
      mail.setAttribute('aria-label', '上任声部长的留言');
      mail.innerHTML = '<span class="hact-ic" aria-hidden="true">' + this.iconSvg('mail') + '</span>'
        + '<span class="hact-dot" hidden></span>';
      mail.addEventListener('click', () => this.openSectionNotes());
      host.appendChild(mail);
    }
  },

  /** 「有没有未读公告」的唯一判定（与弹层同一套：本机没记录过也算"有未读"）。
   *  抽成 Promise 是因为**两个消费者**（铃铛上的红点、小面板里那行的红点）都要问同一件事。 */
  releaseUnread() {
    return this.api('/api/release-notes/latest').then((envelope) =>
      !!(envelope.code === 0 && envelope.data && envelope.data.id > this.seenRelease()))
      .catch(() => false);   // 拉不到就不显示红点，别打扰
  },

  /** 未读公告的小红点。单独一个方法是为了**只拉一次**——`paintNav` 会跑两遍（缓存 + 权威）。
   *  ⚠️⚠️ **选择器必须收窄到 `.hact-bell`**（2026-09-30，`交接单 #118`）：合并之后
   *  **铃铛与信封各有一颗红点**，而这里原来是 `document.querySelector('.hact-dot')`（**单数**）
   *  ⇒ 不收窄就会**把公告的红点画到信封上**（这就是"合并"带来的新坑）。 */
  refreshReleaseDot() {
    const dot = document.querySelector('.hact-bell .hact-dot');
    if (!dot) {
      return;
    }
    this.releaseUnread().then((unread) => { dot.hidden = !unread; });
  },

  /** 信封上的未读点：有"我刚上任、还没读过"的那一条才亮（`docs/03` §3.5）。
   *  ⚠️ 只拉一次（见 `sectionNotePending`）。 */
  refreshSectionNoteDot() {
    const dot = document.querySelector('.hact-mail .hact-dot');
    if (!dot) {
      return;
    }
    this.sectionNotePending().then((note) => {
      if (note && note.id > this.seenSectionNote()) {
        dot.hidden = false;
      }
    });
  },

  /** 「我刚上任、还没读过上一任的留言」那一条；没有就是 `null`。
   *  **一次页面生命周期只拉一次**（`paintNav` 会跑两遍、弹层与红点都要问同一件事），
   *  连 `null` 也缓存 —— 所以要重拉得先把它清掉。 */
  sectionNotePending() {
    if (!this._pendingNote) {
      this._pendingNote = this.api('/api/me/section-note-pending')
        .then((envelope) => (envelope.code === 0 && envelope.data ? envelope.data : null))
        .catch(() => null);
    }
    return this._pendingNote;
  },

  /** 「今天过生日的人」（`docs/14` §14.6.4「生日通告」/「欢迎行」，`#115`）。
   *  ⚠️ **一次页面生命周期只拉一次**：生日通告浮层与欢迎行那条文案问的是同一件事，
   *  而 `paintNav` 会跑两遍（缓存 + 权威）⇒ 两个消费者共用一个 promise。
   *  ⚠️ 与 `sectionNotePending` 同规矩：**连空数组也缓存**（"今天没人过生日"是确定答案，
   *  别每调一次就打一次接口）。`id` 是 `#115` 给接口加的字段——没有它前端答不出"我在不在名单里"。 */
  birthdaysToday() {
    if (!this._birthdays) {
      this._birthdays = this.api('/api/birthdays/today')
        .then((envelope) => (envelope.code === 0 && Array.isArray(envelope.data) ? envelope.data : []))
        .catch(() => []);           // 接口挂了就当作"今天没人过生日"：欢迎行退回平常那句，不报错
    }
    return this._birthdays;
  },

  /** 铃铛点开的小面板（`docs/14` §14.6.4）：两项，**按角色条件渲染**——
   *  「更新公告」（进 `updates.html`，未读时这一行带小红点）+「提反馈」（开反馈浮层）。
   *  ⚠️ **荣誉团员只渲染第一行**（提反馈是"动作"、不是只读 ⇒ 防腐条款不给，`docs/02` §2.3）；
   *  仍保留"点开面板"这一个交互，不为此另开分支。
   *  ⚠️ **层级沿用「个人栏」那一档（遮罩 490 / 面板 500）**，不新造一档；
   *  「提反馈」开的 bootstrap modal 默认 1055，天然在其上。
   *  关闭：点任意处 / `Esc`（与反馈浮层、个人栏同一条规矩：**绝不能不让人关**）；再点铃铛 = 收起。 */
  openHeadPanel(bell, me) {
    if (this.closeHeadPanel()) {
      return;                                  // 已经开着 ⇒ 这一下是"收起"
    }
    const back = document.createElement('div');
    back.className = 'hpanel-back';
    const panel = document.createElement('div');
    panel.className = 'hpanel';
    panel.setAttribute('role', 'menu');
    panel.setAttribute('aria-label', '消息');
    const up = document.createElement('a');
    up.className = 'hpanel-row';
    up.href = '/updates.html';
    up.innerHTML = '<span>更新公告</span><span class="hpanel-dot" hidden></span>';
    panel.appendChild(up);
    if (me.role !== 'HONORARY') {
      const fb = document.createElement('button');
      fb.type = 'button';
      fb.className = 'hpanel-row';
      fb.textContent = '提反馈';
      fb.addEventListener('click', () => { this.closeHeadPanel(); this.openFeedback(); });
      panel.appendChild(fb);
    }
    document.body.append(back, panel);
    bell.setAttribute('aria-expanded', 'true');
    const rowDot = panel.querySelector('.hpanel-dot');
    this.releaseUnread().then((unread) => { rowDot.hidden = !unread; });
    back.addEventListener('click', () => this.closeHeadPanel());
    this._panelKey = (e) => { if (e.key === 'Escape') this.closeHeadPanel(); };
    document.addEventListener('keydown', this._panelKey);
  },

  /** 收起小面板。**返回"刚才是不是开着"**——`openHeadPanel` 用它实现"再点一下收起"。 */
  closeHeadPanel() {
    const panel = document.querySelector('.hpanel');
    if (!panel) {
      return false;
    }
    const back = document.querySelector('.hpanel-back');
    if (back) back.remove();
    panel.remove();
    document.removeEventListener('keydown', this._panelKey);
    const bell = document.querySelector('.hact-bell');
    if (bell) bell.setAttribute('aria-expanded', 'false');
    return true;
  },

  // ---------- 上任声部长的留言（`#111`，docs/03 §3.5 / docs/14 §14.6.4） ----------

  /**
   * 「上任声部长的留言」浮层：**读 + 写同一处**。
   * <p>
   * 美术 = 「**一封压在谱架上的信**」：丝白底 `--silk`（⚠️ 铁律③：**绝不能做成宣纸黄**）、
   * 楷体、落款**右对齐**、朱砂印章、**宽行距** + 段首空两格。⚠️ **不做竖排**（手机上可读性差）、
   * **不画边框与阴影**（`§14.6.3`：需要边框就是需要更多间距，靠底色与留白分层）、
   * **只做一次「展开」**（`opacity` + 轻微 `translateY`，≤400ms；**不做循环、不加纸纹**）。
   * <p>
   * 权限全由**服务端**判：读不到（403）会 toast 并**直接把浮层收掉**（不留空壳）；
   * **作者能改自己那条**、**谁都删不了**（页面上根本没有删除入口）。
   * 关闭：关闭按钮 / `Esc` / 点遮罩（与公告弹层同一套）。
   */
  async openSectionNotes() {
    if (document.querySelector('.letter')) {
      return;                                   // 已经开着，别叠第二层
    }
    const me = this.me || {};
    const sectionId = me.sectionId;
    if (!sectionId) {
      this.toast('你现在不在任何声部，看不了留言');
      return;
    }
    const reduced = window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const mask = document.createElement('div');
    mask.className = 'letter' + (reduced ? '' : ' letter-in');
    mask.setAttribute('role', 'dialog');
    mask.setAttribute('aria-modal', 'true');
    mask.setAttribute('aria-label', '上任声部长的留言');
    const card = document.createElement('div');
    card.className = 'letter-card';
    const cls = document.createElement('button');
    cls.type = 'button';
    cls.className = 'go go-ghost go-sm letter-cls';
    cls.textContent = '关闭';
    const list = document.createElement('div');
    list.className = 'letter-list';
    card.append(cls, list);
    mask.appendChild(card);

    const shut = () => {
      document.removeEventListener('keydown', onKey);
      mask.remove();
    };
    function onKey(e) { if (e.key === 'Escape') shut(); }
    cls.addEventListener('click', shut);
    mask.addEventListener('click', (e) => { if (e.target === mask) shut(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(mask);
    cls.focus();

    try {
      await this.drawSectionNotes(list, sectionId);
    } catch (e) {
      shut();     // 读不了（403 / 网络）：toast 已弹，**别留一个空壳浮层**
    }
  },

  /** 画留言列表：**按 `created_at` 正序**（历届叠着看，越早的越在上面）。写完 / 改完都重走一遍。 */
  async drawSectionNotes(list, sectionId) {
    const d = await this.must('/api/sections/' + sectionId + '/notes?size=100');
    const me = this.me || {};
    const redraw = () => this.drawSectionNotes(list, sectionId);
    list.textContent = '';
    const rows = d.list || [];
    if (!rows.length) {
      const empty = document.createElement('p');
      empty.className = 'letter-empty';
      empty.textContent = '还没有留言。';
      list.appendChild(empty);
    }
    for (const n of rows) {
      list.appendChild(this.letterItem(n, me, sectionId, redraw));
    }
    // ⭐ **现任声部长多一个「写」**（服务端还会再判一次；这一层只是不给没权限的人看）
    if (me.role === 'SECTION_LEADER') {
      list.appendChild(this.letterComposer(sectionId, redraw));
    }
  },

  /** 一封「信」：正文 + 落款（署名 · 日期，右对齐）+ 朱砂印章；**我写的那条**多一个「改」。 */
  letterItem(note, me, sectionId, redraw) {
    const el = document.createElement('article');
    el.className = 'letter-item';
    const body = document.createElement('p');
    body.className = 'letter-body';
    body.textContent = note.body;              // 纯文本 + 保留换行（**绝不解析 HTML**，SECURITY.md C3）
    const foot = document.createElement('div');
    foot.className = 'letter-foot';
    const seal = document.createElement('span');
    seal.className = 'letter-seal';
    seal.setAttribute('aria-hidden', 'true');
    seal.textContent = (me.sectionName || '声部').replace(/声部$/, '') || '印';
    const sign = document.createElement('span');
    sign.className = 'letter-sign';
    sign.textContent = (note.authorName || '') + ' · ' + String(note.createdAt || '').slice(0, 10)
      + (note.updatedAt && note.updatedAt !== note.createdAt ? ' · 改过' : '');
    foot.append(seal, sign);
    el.append(body, foot);
    if (note.mine) {
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'go go-ghost go-sm letter-act';
      edit.textContent = '改';
      edit.addEventListener('click', () => {
        const box = this.letterEditor(note.body, '保存', async (text) => {
          await this.must('/api/sections/' + sectionId + '/notes/' + note.id,
            { method: 'PUT', body: { body: text } });
          this.toast('改好了', 'success');
          await redraw();
        }, redraw);
        el.replaceWith(box);
        box.querySelector('.letter-ta').focus();
      });
      el.appendChild(edit);
    }
    return el;
  },

  /** 「写一条」——**只有现任声部长**看得到。 */
  letterComposer(sectionId, redraw) {
    return this.letterEditor('', '留一条', async (text) => {
      await this.must('/api/sections/' + sectionId + '/notes', { method: 'POST', body: { body: text } });
      this.toast('留好了', 'success');
      await redraw();
    }, null);   // 写没有"取消"（还没写呢，取消无从谈起）
  },

  /** 「写」与「改」共用的那一块（textarea + 保存 [+ 取消]）。`onCancel` 传 `null` 就不画取消。 */
  letterEditor(initial, saveLabel, onSave, onCancel) {
    const box = document.createElement('div');
    box.className = 'letter-edit';
    const ta = document.createElement('textarea');
    ta.className = 'letter-ta';
    ta.rows = 5;
    ta.value = initial || '';
    const row = document.createElement('div');
    row.className = 'letter-acts';
    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'go go-sm';
    save.textContent = saveLabel;
    row.appendChild(save);
    if (onCancel) {
      const cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.className = 'go go-ghost go-sm';
      cancel.textContent = '取消';
      cancel.addEventListener('click', onCancel);
      row.appendChild(cancel);
    }
    box.append(ta, row);
    save.addEventListener('click', async () => {
      const text = ta.value.trim();
      if (!text) {
        this.toast('留言不能为空');   // 服务端也会拦（`@NotBlank`），这里只是不用等一个来回
        return;
      }
      save.disabled = true;           // 防连点发两次
      try {
        await onSave(text);
      } finally {
        save.disabled = false;
      }
    });
    return box;
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
        // 红点跟着消失：刚刚已经看过了。⚠️ **只清铃铛那颗**（`#118` 之后信封也有一颗，
        // 而这条说的是"公告已读"——清错就是把信封的未读状态抹掉了）。
        const bellDot = document.querySelector('.hact-bell .hact-dot');
        if (bellDot) bellDot.hidden = true;
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
    return this.birthdaysToday().then((list) => {
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
   * 「新部长上任」那一次额外弹一遍（`docs/14` §14.6.4 ③）：**上号后首次进入**弹一次。
   * ⚠️ **它和信封里那个浮层是同一份内容** —— 读完之后内容**不会消失**，只是**不再弹**（常驻在信封里）。
   * 去重键 `localStorage.band_seen_section_note = '<noteId>'`（做法仿生日通告 `band_seen_birthday`）。
   * <p>
   * ⚠️ 形态**照抄 `announce()` / `announceBirthday()`**（同一个 `.ann` 浮层与 `ann-rise`、同一套关闭行为、
   * z-index 120）—— 规格明写"别另造一套弹层"。
   * ⚠️ **位置排在最后**：欢迎过渡 → 更新公告 → 生日通告 → **本弹层** → 才加载 oneko
   * （oneko 的 z-index 是最大整数，**必须等所有弹层都关掉**）。
   * ⚠️ 规格里写死的是「**四者顺序**」，本弹层是**第五个** —— 已记 `交接单` §三 移交事项。
   * @param fromLogin 只有登录成功那一次为 true
   */
  announceSectionNote(fromLogin) {
    if (!fromLogin) {
      return Promise.resolve();   // 平时浏览页面不弹
    }
    return this.sectionNotePending().then((note) => {
      if (!note || note.id <= this.seenSectionNote()) {
        return undefined;         // 没有 / 已经读过这一条：整个环节跳过，不弹空层、不留空帧
      }
      return new Promise((resolve) => {
        const reduced = window.matchMedia
          && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        const mask = document.createElement('div');
        mask.className = 'ann' + (reduced ? '' : ' ann-in');
        const card = document.createElement('div');
        card.className = 'ann-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-label', '上任声部长的留言');
        const title = document.createElement('p');
        title.className = 'ann-title';
        title.textContent = '上任声部长给你留了一条';
        const body = document.createElement('p');
        body.className = 'ann-body';
        body.textContent = note.body;   // 纯文本，不解析 HTML（与公告同规矩）
        const acts = document.createElement('div');
        acts.className = 'ann-acts';
        const more = document.createElement('button');
        more.type = 'button';
        more.className = 'ann-link';
        more.textContent = '去信封里看';
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
          this.markSectionNoteSeen(note.id);
          const dot = document.querySelector('.hact-mail .hact-dot');
          if (dot) dot.hidden = true;        // 信封上的未读点跟着灭（刚读过了）
          document.removeEventListener('keydown', onKey);
          mask.remove();
          resolve();
        };
        function onKey(e) { if (e.key === 'Escape') close(); }
        ok.addEventListener('click', close);
        more.addEventListener('click', () => { close(); this.openSectionNotes(); });
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
   * 取文件内容为 `Blob`。**绝不能用裸 `<a href="/api/files/{id}">`**——
   * 裸链接不带 `Authorization` 头，永远 401（导出 CSV 早就踩过同一个坑，见 admin-members.html）。
   * 失败**已 toast**（403 与其它分开说）并**抛出去**，由调用方决定怎么收场。
   */
  async fileBlob(fileId) {
    const res = await fetch('/api/files/' + fileId, {
      headers: { Authorization: 'Bearer ' + this.token() },
    });
    if (!res.ok) {
      this.toast(res.status === 403 ? '没有权限看这个文件' : '打不开这个文件（HTTP ' + res.status + '）');
      throw new Error('HTTP ' + res.status);
    }
    return res.blob();
  },

  /** 取文件内容为 `blob:` URL（`fileBlob` 的薄包装）。用完记得 `URL.revokeObjectURL`。 */
  async fileBlobUrl(fileId) {
    return URL.createObjectURL(await this.fileBlob(fileId));
  },

  /** 打开附件（谱子 / 音频；`piece-detail.html` 的「查看 / 试听」）。
      ⚠️ **手机与桌面走两条路**（`docs/08` §8.5，`#113`）——
      起因：手机上报"点了毫无反应"。根因 = 原来那句 `window.open(await fileBlobUrl(id))`：
      **`window.open` 在 `await` 之后才调用** ⇒ 那一刻**已经脱离用户手势** ⇒
      手机浏览器 / 弹窗拦截器**直接把它拦掉**（返回 `null`），而代码**不校验返回值** ⇒ **静默失败**。
      ⭐ **手机走页面内预览层**（完全绕开弹窗拦截，与公告 / 生日是同一族形态）；
      桌面仍开新标签，但**先把窗同步开出来**（还在手势里）再填内容，且**校验 `null`**。
      ⚠️ 这个 bug **在桌面上复现不出来**（桌面拦截宽松）——桌面测通 ≠ 修好，必须真机验。 */
  async openFile(fileId) {
    if (this.isMobile()) {
      return this.previewFile(fileId);
    }
    // ⚠️ **同步**开窗：这一行必须在任何 `await` **之前**，否则又脱离了用户手势（就是原来那个 bug）。
    const w = window.open('', '_blank');
    if (!w) {
      this.toast('浏览器拦了新窗口——请允许本站的弹窗后重试');
      return;
    }
    try {
      w.location = await this.fileBlobUrl(fileId);
    } catch (e) {
      try { w.close(); } catch (err) { /* 关不掉就算了，里面只是个空白页 */ }
    }
  },

  /** 页面内全屏预览层（手机端走它，`#113`）：图片 `<img>` / PDF `<iframe>` / 音频 `<audio>`，
      其它类型给一个下载链接兜底。**完全绕开弹窗拦截**。
      关闭：关闭按钮 / `Esc` / 点遮罩（与「更新公告弹层」同一套）；`prefers-reduced-motion` 不播入场。 */
  async previewFile(fileId) {
    let blob;
    try {
      blob = await this.fileBlob(fileId);
    } catch (e) {
      return;   // toast 已弹
    }
    const url = URL.createObjectURL(blob);
    const type = blob.type || '';
    const reduced = window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const mask = document.createElement('div');
    mask.className = 'fview' + (reduced ? '' : ' fview-in');
    mask.setAttribute('role', 'dialog');
    mask.setAttribute('aria-modal', 'true');
    mask.setAttribute('aria-label', '附件预览');
    const card = document.createElement('div');
    card.className = 'fview-card';
    const cls = document.createElement('button');
    cls.className = 'go go-ghost go-sm fview-cls';
    cls.type = 'button';
    cls.textContent = '关闭';
    const parts = [cls];
    let media;
    if (type.startsWith('image/')) {
      media = document.createElement('img');
      media.className = 'fview-img';
      media.alt = '';
      media.src = url;
    } else if (type === 'application/pdf') {
      media = document.createElement('iframe');
      media.className = 'fview-frame';
      media.title = 'PDF 预览';
      media.src = url;
      // ⚠️ **iOS Safari 上 blob 的 PDF 经常是一片空白**（`docs/08` §8.5 提过这条）⇒
      //    给一个"在新标签打开"当退路。它是**直接点击**打开的（落在手势里），不会被拦。
      const alt = document.createElement('a');
      alt.className = 'fview-alt';
      alt.href = url;
      alt.target = '_blank';
      alt.rel = 'noopener';
      alt.textContent = '上面是空白？点这里在新标签打开';
      parts.push(media, alt);
    } else if (type.startsWith('audio/')) {
      media = document.createElement('audio');
      media.className = 'fview-audio';
      media.controls = true;
      media.src = url;
    } else {
      // 白名单外的类型（PPT / Word 之类）：给一个下载链接，**别静默**
      media = document.createElement('a');
      media.className = 'fview-dl';
      media.href = url;
      media.download = '';
      media.textContent = '下载这个文件';
    }
    if (parts.length === 1) parts.push(media);
    card.append(...parts);
    mask.appendChild(card);

    let closed = false;
    const shut = () => {
      if (closed) return;
      closed = true;
      document.removeEventListener('keydown', onKey);
      if (media.tagName === 'AUDIO') {            // 关掉预览层就把声音停掉，别让它在后台响
        try { media.pause(); } catch (e) { /* 无所谓 */ }
      }
      URL.revokeObjectURL(url);
      mask.remove();
    };
    function onKey(e) { if (e.key === 'Escape') shut(); }
    cls.addEventListener('click', shut);
    mask.addEventListener('click', (e) => { if (e.target === mask) shut(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(mask);
    cls.focus();
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
    // ⚠️⚠️ **本函数会被同一个页面第二次调用**：页面上改筛选器走的就是 `change → load() → renderNav()`
    //    （名册的状态 / 声部、曲目库的状态、活动的类型、录音的范围 —— **四处全是这个写法**）。
    //    第二次**必须复用已搭好的骨架**：`mountSkeleton` 里 `#navbar` 已经被 `replaceWith(layout)` 换掉，
    //    再搭一次就是 `document.getElementById('navbar')` 返回 null → `.nextSibling` 抛错，
    //    表现是"**筛选器完全没反应**"（列表一动不动，只在控制台留一条异常）。
    //    复用时只重画导航（`paintNav` 是幂等的：`menu.innerHTML = …` 整块重写），
    //    **一次性副作用（欢迎过渡 / 更新公告 / 生日通告 / oneko / 个人栏）不再跑**——
    //    否则欢迎动画会重播、猫会叠一只、个人栏会插第二份。
    if (this.skeleton) {
      this.paintNav(this.skeleton, this.cachedMe(), active);
      const [me, , bds] = await Promise.all([this.requireUser(), this.avatars(), this.birthdaysToday()]);
      if (!me) return null;
      this._bd = bds || [];
      this.paintNav(this.skeleton, me, active);
      return me;
    }
    const skeleton = this.mountSkeleton(active);       // ① 同步：布局一次到位
    this.skeleton = skeleton;                          // 存起来给上面那条"第二次进来"的分支复用
    this.paintNav(skeleton, this.cachedMe(), active);  // ② 先用缓存填（没有就留空，不编数据）
    // 身份、头像清单、今天的寿星**并行**取（三份都是很小的清单）；avatars()/birthdaysToday() 自带 catch，
    // 不会把这条链拖垮。⚠️ **寿星也走这条并行**（`#115`）：`paintNav` 的第二次填充在 `playWelcome()`
    // 之前，所以**欢迎过渡那一屏的文案一开始就是对的**，不会先"欢迎回来"再跳成"生日快乐"
    // （登录那一眼正好是最显眼的时刻）。
    const [me, , bds] = await Promise.all([this.requireUser(), this.avatars(), this.birthdaysToday()]);
    if (!me) return null;                              // 401 已在 api() 里跳转；其他错误已 toast + 摘黑幕
    this._bd = bds || [];
    this.paintNav(skeleton, me, active);               // ③ 再用服务端**权威**数据填一遍

    this.mountProfile(skeleton.layout, me);
    // ⚠️ `mountRailKeep` **不在这里**了：2026-09-25 起它随骨架**同步绑**（在 `mountSkeleton` 里），
    // 否则那几十~几百毫秒没监听，指针甩到正文会卡在展开态（详见它的方法注释）。
    this.refreshReleaseDot();
    this.refreshSectionNoteDot();   // 信封上的未读点（有"我刚上任、还没读过"的那条才亮）
    // ⚠️ **三者顺序写死**（docs/15 §15.5）：欢迎过渡 → 更新公告 → **关掉公告才加载 oneko**。
    // 猫的 z-index 是最大整数，不等公告关掉就会从公告上跑过去（当年"从欢迎黑幕上跑过去"是同款坑）。
    // fromLogin **必须在 playWelcome 之前读**——它一进去就把 band_welcome 标记删了。
    let fromLogin = false;
    try { fromLogin = sessionStorage.getItem('band_welcome') === '1'; } catch (e) { /* 隐私模式 */ }
    this.playWelcome()
      .then(() => this.announce(fromLogin))
      .then(() => this.announceBirthday(fromLogin))   // 生日通告**排在公告之后**（docs/15 §15.5 的四者顺序）
      // ⚠️ **第五个**：新部长上任那一次（`#111`）。规格写死的是"四者顺序"，
      //    这一层必须排在 oneko **之前**——猫的 z-index 是最大整数，等不到所有弹层关掉就会从上面跑过去。
      .then(() => this.announceSectionNote(fromLogin))
      .then(() => this.loadCat());
    return me;
  },

  /** 图标列与页面标题表（docs/14 §14.6.4）：菜单项 [href, 名字, **图标 key**, 角色白名单]。
      ⚠️ 第 3 位从 2026-09-30 起是 **`vendor/icons/` 里的图标 key**（不再是汉字占位）——
      那正是 `docs/14` §14.6.4「DOM 契约」预留的"将来换真图标只改这一列"。
      ⇒ **一一对应、不许两个页面共用一个 key**（`AGENTS.md` 前端节第 6 条）：
      活动/活动管理、名册/团员管理、我的请假/请假审批 都是高危对。
      ⚠️ 「更新公告」用 `megaphone` **不能用 `bell`**——铃铛已经给了右上角那个合并入口。
      ⚠️ **换图标要同步三处**：`vendor/icons/` 的文件 + 那目录的 `manifest.json` + 本表；
      对应的 `<svg>` 内层写在下面 `ICON_SVG`（**由脚本生成，别手抄**）。 */
  navItems: [
    ['events.html', '活动', 'calendar-days', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['members.html', '名册', 'users', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['my-leave.html', '我的请假', 'calendar-minus', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER']],
    // 曲目 / 演出 / 录音：**所有角色都能看**（含荣誉团员——曲目附件另由文件接口按角色挡；
    // 荣誉团员在录音页只看得到"自己发过的"、上传区不渲染），增删改都在服务层收口
    ['pieces.html', '曲目', 'music', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['concerts.html', '演出', 'ticket', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['recordings.html', '录音', 'mic', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['stats.html', '考勤统计', 'chart-column', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY']],
    ['approve-leave.html', '请假审批', 'clipboard-check', ['ADMIN', 'LEADER', 'SECTION_LEADER']],
    ['admin-events.html', '活动管理', 'calendar-cog', ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER']],
    ['admin-showcase.html', '专场策展', 'images', ['ADMIN']],
    ['admin-members.html', '团员管理', 'user-cog', ['ADMIN']],
    ['admin-feedback.html', '反馈管理', 'inbox', ['ADMIN']],
    ['admin-notes.html', '更新公告', 'megaphone', ['ADMIN']],
    ['logs.html', '操作日志', 'scroll-text', ['ADMIN', 'LEADER']],
  ],

  /* 图标内层 markup（`vendor/icons/` 是唯一权威）。⚠️ **本区块由 `design-samples/_gen_icons_js.py`
     从 `vendor/icons/` 现算生成，别手改** —— 手抄一遍就会漂，本项目**刚因为"手抄哈希表少打一个字符"
     翻过车**（`work_log` 2026-09-30）。改图标的正确姿势：换 `vendor/icons/<file>.svg` + 更新那目录的
     `manifest.json` + 改上面 `navItems` 的 key，然后重跑那个脚本。 */
  /* ==ICON_SVG:begin== */
  ICON_SVG: {
    'bell': '<path d="M10.268 21a2 2 0 0 0 3.464 0" /><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326" />',
    'calendar-cog': '<path d="m15.228 16.852-.923-.383" /><path d="m15.228 19.148-.923.383" /><path d="M16 2v3" /><path d="m16.47 14.305.382.923" /><path d="m16.852 20.772-.383.924" /><path d="m19.148 15.228.383-.923" /><path d="m19.53 21.696-.382-.924" /><path d="m20.773 16.852.924-.383" /><path d="m20.773 19.148.924.383" /><path d="M21 10.5V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h5.5" /><path d="M3 9h18" /><path d="M8 2v3" /><circle cx="18" cy="18" r="3" />',
    'calendar-days': '<path d="M8 2v3" /><path d="M16 2v3" /><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18" /><path d="M8 13h.01" /><path d="M12 13h.01" /><path d="M16 13h.01" /><path d="M8 17h.01" /><path d="M12 17h.01" /><path d="M16 17h.01" />',
    'calendar-minus': '<path d="M16 18h6" /><path d="M16 2v3" /><path d="M21 14V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h8.3" /><path d="M3 9h18" /><path d="M8 2v3" />',
    'chart-column': '<path d="M3 3v16a2 2 0 0 0 2 2h16" /><path d="M18 17V9" /><path d="M13 17V5" /><path d="M8 17v-3" />',
    'circle-user-round': '<path d="M17.925 20.056a6 6 0 0 0-11.851.001" /><circle cx="12" cy="11" r="4" /><circle cx="12" cy="12" r="10" />',
    'clipboard-check': '<rect width="8" height="4" x="8" y="2" rx="1" ry="1" /><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" /><path d="m9 14 2 2 4-4" />',
    'images': '<path d="m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16" /><path d="M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2" /><circle cx="13" cy="7" r="1" fill="currentColor" /><rect x="8" y="2" width="14" height="14" rx="2" />',
    'inbox': '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />',
    'laptop': '<path d="M18 5a2 2 0 0 1 2 2v8.526a2 2 0 0 0 .212.897l1.068 2.127a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45l1.068-2.127A2 2 0 0 0 4 15.526V7a2 2 0 0 1 2-2z" /><path d="M20.054 15.987H3.946" />',
    'mail': '<path d="m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7" /><rect x="2" y="4" width="20" height="16" rx="2" />',
    'megaphone': '<path d="M11 6a13 13 0 0 0 8.4-2.8A1 1 0 0 1 21 4v12a1 1 0 0 1-1.6.8A13 13 0 0 0 11 14H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z" /><path d="M6 14a12 12 0 0 0 2.4 7.2 2 2 0 0 0 3.2-2.4A8 8 0 0 1 10 14" /><path d="M8 6v8" />',
    'mic': '<path d="M12 19v3" /><path d="M19 10v2a7 7 0 0 1-14 0v-2" /><rect x="9" y="2" width="6" height="13" rx="3" />',
    'music': '<path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" />',
    'newspaper': '<path d="M15 18h-5" /><path d="M18 14h-8" /><path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-4 0v-9a2 2 0 0 1 2-2h2" /><rect width="8" height="4" x="10" y="6" rx="1" />',
    'scroll-text': '<path d="M15 12h-5" /><path d="M15 8h-5" /><path d="M19 17V5a2 2 0 0 0-2-2H4" /><path d="M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3" />',
    'smartphone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2" /><path d="M12 18h.01" />',
    'ticket': '<path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z" /><path d="M13 5v2" /><path d="M13 17v2" /><path d="M13 11v2" />',
    'user-cog': '<path d="M10 15H6a4 4 0 0 0-4 4v2" /><path d="m14.305 16.53.923-.382" /><path d="m15.228 13.852-.923-.383" /><path d="m16.852 12.228-.383-.923" /><path d="m16.852 17.772-.383.924" /><path d="m19.148 12.228.383-.923" /><path d="m19.53 18.696-.382-.924" /><path d="m20.772 13.852.924-.383" /><path d="m20.772 16.148.924.383" /><circle cx="18" cy="15" r="3" /><circle cx="9" cy="7" r="4" />',
    'users': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><path d="M16 3.128a4 4 0 0 1 0 7.744" /><path d="M22 21v-2a4 4 0 0 0-3-3.87" /><circle cx="9" cy="7" r="4" />',
  },
  /* ==ICON_SVG:end== */

  /** 把图标 key 包成可直接塞进 `innerHTML` 的 `<svg>`。**wrapper 属性只在这里写一次**
      （照 `vendor/icons/manifest.json` 的 `svg_attrs`），`ICON_SVG` 里只存内层 markup。
      颜色走 `stroke="currentColor"` ⇒ **继承所在元素的 `color`**（侧栏收起态 `--shellmid`、
      hover / 当前项 `--silk` / `--wood`），**不新造色阶**。
      ⚠️ 查不到的 key **给空串**：宁可这个位置空着，也不要抛错把整个菜单拼不出来。 */
  iconSvg(key) {
    const inner = this.ICON_SVG[key];
    if (!inner) return '';
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
  },

  /** 抽屉里的分组归属表（`docs/14` §14.6.4 二，2026-09-28 定）。⚠️ **只影响手机端的抽屉**：
      桌面仍是那一列 14 项平铺（`.dgrp` 在桌面端是 `display: contents`、组标题不画）。
      ⚠️ 这里**只写 `href`**，名字与图标仍从 `navItems` 取（单一来源）；`navItems` 本身不改。
      某角色在某组里一项都看不到时，那一组连标题都不画（团员就没有「管理」组）。 */
  navGroups: [
    ['日常', ['events.html', 'members.html', 'my-leave.html', 'pieces.html', 'concerts.html',
              'recordings.html', 'stats.html']],
    ['管理', ['approve-leave.html', 'admin-events.html', 'admin-showcase.html', 'admin-members.html',
              'admin-feedback.html', 'admin-notes.html', 'logs.html']],
  ],

  /** 各折叠组的开关状态（键 = 组下标）。⚠️ **真值只在这里**：DOM 上那个 `.open` 类只是它的投影
      （`mountDrawer` 的委托监听器写回来）。从没手动动过 ⇒ 该键不存在。 */
  grpOpen: {},

  /** 不在主导航里的页面取不到菜单项，标题单独给——别把它们塞进 `navItems`（那会多出一个菜单项）。 */
  offMenuTitles: {
    'sessions.html': '登录设备',
    'profile.html': '个人资料',
    'updates.html': '更新日志',
    'piece-detail.html': '曲目详情',
    'concert-detail.html': '演出详情',
    'member-detail.html': '团员主页',
    // 「我们的专场」的入口是 concerts.html 顶部那个链接，**不占菜单项**（docs/17 §17.5）
    'showcase.html': '我们的专场',
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
      </aside></div></div>
      <div class="scrim"></div>`;

    const main = document.createElement('main');
    main.className = 'main';
    const head = document.createElement('div');
    head.className = 'head';
    // 细标题栏（docs/14 §14.6.4「手机端（≤720px）」）：**只在窄屏 / 触屏出现**（CSS 里 `display:none` 兜底）。
    // 左侧那颗头像是「开抽屉」的开关（2026-09-28 用户定的，原方案是 `☰`）——它同时是**欢迎过渡的
    // FLIP 落点之一**（`playWelcome` 的 `targets()` 在触屏 / 窄屏下取的就是它），所以它必须一直可见。
    // ⚠️ 内容走 `paintAvatar`（清单校验 + 首字回退都在它内部），别写第二套；拿到身份之前留空、不编数据。
    const mhead = document.createElement('div');
    mhead.className = 'mhead';
    const mav = document.createElement('button');
    mav.className = 'mav-toggle';
    mav.type = 'button';
    mav.setAttribute('aria-label', '打开菜单');
    mav.setAttribute('aria-expanded', 'false');
    const mavSpan = document.createElement('span');
    mav.appendChild(mavSpan);
    const mhTitle = document.createElement('span');
    mhTitle.className = 'mh-title';
    mhead.append(mav, mhTitle);
    const headL = document.createElement('div');
    headL.className = 'head-l';
    // 欢迎行（docs/14 §14.6.4）：姓名朱砂、后面跟职称，不用标点；它是欢迎过渡的落点之一
    const welcome = document.createElement('p');
    welcome.className = 'welcome';
    // ⚠️ 开头那四个字**必须是具名节点**：生日当天要换成「生日快乐，」（`docs/14` §14.6.4
    //    「欢迎行」/ `#115`）。写成 `welcome.append('欢迎回来，')` 就再也改不动它了。
    const wlead = document.createTextNode('欢迎回来，');
    welcome.append(wlead);
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
    head.append(mhead, headL, headR);   // ⚠️ `.mhead` 在桌面端 `display:none` ⇒ 桌面那套布局与从前一模一样
    main.appendChild(head);
    // 正文**再包一层**（`docs/14` §14.6.4 六）：手机端下拉刷新的 `transform` 只能打在这一层上——
    // 打在主区容器上会建 containing block，把 `showcase.html` 那些 `position:fixed` 层与 `#wel`
    // 的参照系一起搞乱。骨架屏（C 批）也插在这里。**`.head` 留在包裹层外面**，否则标题栏会跟着位移。
    const mbody = document.createElement('div');
    mbody.className = 'mbody';
    // 下拉刷新的提示条（`docs/14` §14.6.4 六）：挂在正文包裹层**内部**、常态 `translateY(-100%)`
    // ⇒ 正文被推下去时它"自然露出来"，不用再写一条会与主位移对不齐的跟随动画。
    // ⚠️ **只在 `isNarrow()` 时挂进 DOM**（`#114`，2026-09-30）：它的样式全在 `band.css` §10·b 的
    //    `@media (max-width: 720px)` 里 ⇒ 宽屏 / 触屏平板上它**没有任何规则命中**，
    //    会退化成"没样式的裸 div **把『↓ 下拉刷新』直接显示出来**"（这正是用户报的电脑端那个障）。
    //    干脆不创建 —— 于是宽屏上 `document.querySelector('.mhint')` 是 `null`、而不是"存在但被藏起来"。
    //    `hint` 变量照旧传下去（`mountPullRefresh` 会拿到），只是非窄屏下它是个游离节点、永不显示。
    const hint = document.createElement('div');
    hint.className = 'mhint';
    const hArrow = document.createElement('span');
    hArrow.className = 'arrow';
    hArrow.textContent = '↓';
    const hSpin = document.createElement('span');
    hSpin.className = 'spin';
    const hintTx = document.createElement('span');
    hintTx.className = 'mh-tx';
    hintTx.textContent = '下拉刷新';
    hint.append(hArrow, hSpin, hintTx);
    // 骨架屏（六 + 「界面的活性」8）：只在手机段显示，由 `BAND.alive()` 撤掉（`endLoading`）。
    const skel = document.createElement('div');
    skel.className = 'skel';
    skel.innerHTML = '<div class="sk tall"></div><div class="sk row"></div>' +
                     '<div class="sk row"></div><div class="sk"></div>';
    if (this.isNarrow()) mbody.append(hint);
    mbody.append(skel);
    main.appendChild(mbody);
    layout.appendChild(main);
    // ⚠️⚠️ **骨架屏只对"有列表 / 数字那类内容"的页面开**（`ALIVE.play` 认得的那几样，静态标记里就有）。
    // 沉浸页（`showcase.html`）与纯表单页**没有"纯白等"这回事**，给它们盖骨架反而像卡住了 ——
    // 而且它会顺手把正文藏掉（`.mbody.loading > *:not(.skel)`），**跨批回归时真被探针抓到过**
    // （专页探针量到中轴 `x = 0.00`：整页内容还在骨架的 3 秒窗口里）。
    const host0 = document.getElementById('navbar');
    if (host0 && host0.parentNode.querySelector('table.tb, [data-count], .penta, .attbar')) {
      mbody.classList.add('loading');
      // ⚠️ **兜底**：个别页面**不调** `BAND.alive()`——少了它，骨架会把正文**一直**盖住
      // （`.mbody.loading` 会把非骨架子元素一起藏掉）。正常路径是 `alive()` 里的 `endLoading()`。
      setTimeout(() => this.endLoading(mbody), 3000);
    }

    // 把 #navbar 之后的页面内容全部挪进 `.mbody`（toast-host 留在 body 上）
    const host = document.getElementById('navbar');
    const moved = [];
    let node = host.nextSibling;
    while (node) {
      const next = node.nextSibling;
      if (node.nodeType === Node.ELEMENT_NODE && node.id !== 'toast-host') moved.push(node);
      node = next;
    }
    moved.forEach((n) => mbody.appendChild(n));
    host.replaceWith(layout);   // ← 这一步之后，布局就定了；接口还没回来也不影响首帧

    // 底栏现在只剩品牌一行，**没有监听器要绑**了——「退出」与「声音」都搬去了个人栏
    //（`#p-logout` / `#p-sound`，见 `mountProfile`）。
    // ⚠️ 收起判定**必须在这里同步绑**（不能等 `await requireUser()` 回来），详见 `mountRailKeep`。
    this.mountRailKeep(layout);
    // 抽屉的开关同理：`mountSkeleton` 一个页面只跑**一次**（第二次 `renderNav()` 只走 `paintNav()`、
    // 不重建 DOM，`app.js` 上面那段注释）⇒ 这里同步绑，别挪进 `paintNav`。
    this.mountDrawer(layout);
    this.mountPullRefresh({ mbody, hint, hintTx, hArrow });

    return {
      layout, active,
      menu: layout.querySelector('.menu'),
      av: layout.querySelector('.me .ic span'),
      mav: mavSpan,                       // 手机标题栏那颗（欢迎过渡在手机端的落点，见 `playWelcome`）
      mhTitle,
      mhead,                              // 手机那条 sticky 标题栏本身（`mountHeadActions` 手机端把两个入口挂它里面）
      mbody, skel, hint, hintTx, hArrow,
      meName: layout.querySelector('.me-name'),
      meSec: layout.querySelector('.me-sec'),
      wname, wrole, wlead, h1,
      greet: headR.querySelector('.greet'),
      headR,
    };
  },

  /**
   * 菜单 HTML（`docs/14` §14.6.4 二/四）：桌面仍是 14 项平铺；手机端在抽屉里分「日常 / 管理」两组，
   * **「管理」默认折叠**，而**当前项落在哪一组，那一组就必须展开**（硬条款——否则"你在这一页"的
   * 高亮块看不见）。⚠️ 硬条款优先于"手动折叠"：你在管理组的某一页里手动收它，这一页会立刻展回来，
   * 这是**有意**的，别当 bug（要改口径得先动规格）。
   * ⚠️ 分组容器与组标题在**桌面端**被 `band.css` §4 摊平（`display: contents` / `none`），
   * 所以这一份 DOM 桌面与抽屉共用，不做两套。
   */
  menuMarkup(me, active) {
    const allowed = this.navItems.filter(([, , , roles]) => roles.includes(me.role));
    const byHref = new Map(allowed.map((it) => [it[0], it]));
    const link = ([href, label, icon]) =>
      `<a href="/${href}"${href === active ? ' class="on"' : ''}>` +
      `<span class="ic" aria-hidden="true">${this.iconSvg(icon)}</span><span class="tx">${label}</span></a>`;
    return this.navGroups.map(([name, hrefs], gi) => {
      const items = hrefs.map((h) => byHref.get(h)).filter(Boolean);
      if (!items.length) return '';               // 这个角色在这一组里一项都没有 ⇒ 连组标题都不画
      const fold = gi > 0;                        // 第 0 组（日常）不可折叠；其余可折叠
      const open = !fold || !!this.grpOpen[gi] || items.some((it) => it[0] === active);
      return `<div class="dgrp${open ? ' open' : ''}">` +
        (fold ? `<button class="dgrp-h" type="button" data-g="${gi}" aria-expanded="${open}">` +
                `${name}<i aria-hidden="true">▸</i></button>` : '') +
        `<div class="dgrp-b">${items.map(link).join('')}</div></div>`;
    }).join('');
  },

  /**
   * ②/③ 幂等填充：菜单（按角色过滤）、头像、姓名·声部、欢迎行职称、页标题、右上角操作区。
   * 传 `null`（还没拿到身份）就把这些**留空**——不编数据；拿到接口结果后再调一次即纠正。
   */
  paintNav(s, me, active) {
    // 菜单：只有知道角色才画得出（别的角色看到的是另一套）
    s.menu.innerHTML = !me ? '' : this.menuMarkup(me, active);
    s.av.textContent = '';
    s.mav.textContent = '';                 // 手机标题栏那颗同理：拿到身份之前留空、不编数据
    if (!me) {
      s.meName.textContent = '';
      s.meSec.textContent = '';
      s.wlead.nodeValue = '欢迎回来，';
      s.wname.textContent = '';
      s.wrole.textContent = '';
      s.greet.textContent = '';
    } else {
      this.paintAvatar(s.av, me);   // 有头像用头像、否则姓名首字；一律 textContent，防注入
      this.paintAvatar(s.mav, me);  // 标题栏那颗走**同一个入口**（清单校验 + 首字回退都在它内部）
      s.meName.textContent = me.name;
      s.meSec.textContent = me.sectionName || '';
      // 生日当天的欢迎行（`docs/14` §14.6.4「欢迎行」，2026-09-30 `#115`）：
      // **我在今天的名单里** ⇒ 文案换「生日快乐，」、职称**用全角括号**包起来；
      // **别人看到的仍是「欢迎回来，{姓名}{职称}」**（名单是"谁过生日"，我只跟自己比 `me.id`）。
      // ⚠️ 姓名**仍走楷体 + 墨色**（`band.css` 的 `.wname`，**绝不改朱砂**）、那道手划笔划照旧；
      //    与「生日通告」浮层**并存**（浮层 = 全团谁生日，这里 = 我自己生日）。
      const mine = (this._bd || []).some((b) => b.id === me.id);
      const role = this.roleLabel(me.role) || me.role;
      s.wlead.nodeValue = mine ? '生日快乐，' : '欢迎回来，';
      s.wname.textContent = me.name;
      s.wrole.textContent = mine ? `（${role}）` : role;
      s.greet.textContent = me.sectionName || '';
    }
    const activeLabel = this.navItems.find(([href]) => href === active);
    const title = activeLabel ? activeLabel[1]
      : (this.offMenuTitles[active] || (me ? me.name : ''));
    s.h1.textContent = title;
    // 手机端细标题栏右侧那颗「页面名」= **菜单表里的栏目名**（2026-09-28 用户定），
    // 与 `h1` 同一个字符串。⚠️ 详情页会把 `h1` 改写成具体演出名，但**手机端 h1 不显示**（`band.css` §10），
    // 所以标题栏照样是栏目名 —— 这是选定的口径，不是漏同步。
    s.mhTitle.textContent = title;
    // 右上角操作区要重建（它按角色决定画不画「反馈」）：先清掉上一轮的，再画。
    // ⚠️ **两个宿主都要扫**（手机在 `.mhead`、桌面在 `.head-r`）—— 只扫一个的话，
    //    一旦宿主跟着视口形态变过，另一处就会留下上一轮的残份（`mountHeadActions` 里选宿主）。
    s.headR.querySelectorAll('.hact').forEach((el) => el.remove());
    s.mhead.querySelectorAll('.hact').forEach((el) => el.remove());
    if (me) {
      this.mountHeadActions(s, me);
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
  /** 「窄屏 或 触屏」= 手机端那套形态（抽屉 / 标题栏 / 密度 / 卡片行）的门控。
      ⚠️⚠️ **必须与 `band.css` §10 那条 `@media (max-width: 720px), (pointer: coarse)` 同源**，
      一处改了另一处要跟着改。⚠️ **只写 `(pointer: coarse)` 不够**：**≤720px 的桌面窗口**
      （精确指针）在 CSS 上也走抽屉，而欢迎过渡若照桌面那一支去量**侧栏**那颗头像，
      量到的是抽屉里那颗、`x ≈ -320`（**在屏幕外**）⇒ 头像会被飞出屏幕。
      （`docs/14` §14.6.4 给的是 `(hover: none), (pointer: coarse)`，漏了窄屏这一半——
      已记 `交接单` §三 移交事项，规格侧改口径前以这条代码为准。） */
  isMobile() {
    return !!(window.matchMedia
      && window.matchMedia('(max-width: 720px), (pointer: coarse)').matches);
  },

  /** **只认"窄屏"**（`max-width: 720px`）——**下拉刷新专用**的门控（`docs/14` §14.6.4 九·b，`#114`）。
      为什么与 `isMobile()` 分开：`isMobile()` 那条是「窄屏 **或** 触屏」，**抽屉需要它**
      （触屏平板走抽屉是有意的，见 `band.css` §10 的说明）；而"下拉刷新是手机的姿势"——
      在触屏笔记本 / 触屏平板上**没有意义**。用户报障原话：「电脑端上显示的下拉刷新改掉。」
      ⚠️ **必须与 `band.css` §10·b 那条 `@media (max-width: 720px)` 同源**，只改一处会得到
      "CSS 不显示、手势仍被接管"的诡异状态。 */
  isNarrow() {
    return !!(window.matchMedia && window.matchMedia('(max-width: 720px)').matches);
  },

  /** 手机端抽屉（`docs/14` §14.6.4「手机端（≤720px）」一）：**标题栏那颗头像**是开关，
      点遮罩（抽屉右边那一条）或 Esc 收回。
      ⚠️ 必须由 `mountSkeleton` **同步**调用：`renderNav()` 第二次只走 `paintNav()`、不重建 DOM。
      ⚠️ 组标题的折叠走**事件委托**（绑在 `.menu` 上）——`paintNav` 会把菜单 `innerHTML` 整块重写，
      绑在按钮上的监听器会随 DOM 一起没（与 `mountRailKeep` 同一个理由）。 */
  mountDrawer(layout) {
    const root = document.documentElement;
    const btn = layout.querySelector('.mav-toggle');
    const scrim = layout.querySelector('.scrim');
    const menu = layout.querySelector('.menu');
    if (!btn || !scrim || !menu) return;
    const setOpen = (open) => {
      root.classList.toggle('drawer-open', open);
      btn.setAttribute('aria-expanded', String(open));
    };
    btn.addEventListener('click', () => setOpen(!root.classList.contains('drawer-open')));
    scrim.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && root.classList.contains('drawer-open')) setOpen(false);
    });
    menu.addEventListener('click', (e) => {
      const h = e.target && e.target.closest ? e.target.closest('.dgrp-h') : null;
      if (!h) return;
      e.preventDefault();
      const grp = h.parentElement;
      const open = !grp.classList.contains('open');
      // 状态只存一处（`grpOpen`），DOM 上的 `.open` 类是它的投影 —— 别在两处各写一遍
      this.grpOpen[h.dataset.g] = open;
      grp.classList.toggle('open', open);
      h.setAttribute('aria-expanded', String(open));
    });
  },

  /** 手机端的「一行一人」卡片行（`docs/14` §14.6.4 八）：把 `thead th` 的文字抄进每格的
      `data-label`（CSS 用它画"标签 · 值"），并标出**哪一行末格里有操作**（按钮 / 链接）。
      调用点：`BAND.alive(...)` 里、`ALIVE.play` **之前** —— 那是各页数据渲染完的**唯一汇合点**，
      而且**每次重渲染都会走到**（名册状态 / 声部、曲目状态、活动类型、录音范围那四处筛选器
      走的都是 `load() → alive()`）。⚠️ 幂等（只写 `data-label` 与类名）。
      ⚠️ 桌面端**什么都不做**（卡片行是 `@media (max-width:720px), (pointer:coarse)` 里的版式）。
      ⚠️ **类名的来由**（2026-09-28 改）：原来叫 `.swipable` / `.sw-act`，因为那时操作区藏在左滑后面；
      **左滑已取消**（用户真机反馈"右滑展不出"）⇒ 操作区改成**常显**，名字也改诚实：
      行标 **`.has-act`**、操作格标 **`.act`** —— `.act` 顺带与**桌面那套已有的列约定**同名
      （`band.css` §8 `.tb .act { text-align: right; white-space: nowrap }`，6 个页面的末格本来就写着 `class="act"`，
      同名叠加是幂等的）。⚠️ `tr.has-act` 只是给 CSS 一个"这行下面要接一条按钮带"的钩子。 */
  shapeRows(root) {
    if (!root || !this.isMobile()) return;
    const tables = root.matches && root.matches('table.tb')
      ? [root] : [...root.querySelectorAll('table.tb')];
    for (const tb of tables) {
      // ⚠️⚠️ **两行式（`.tb.t2`，`docs/14` §14.6.4 八，2026-09-30 `#105`）不走这一套**：
      //    那种版式**不画"标签 · 值"**（格子自己带 `.t-head` / `.t-meta` / `.t-st` / `.t-ph`），
      //    抄上去的 `data-label` 没人看；而且它的**末格已经不是操作格**了
      //    （名册末格是 `.t-meta`、团员管理末格是后加的 `.t-pick`）⇒ 按"末格"判 `has-act` 必然判错。
      //    ⚠️ 规格原话是"`data-label` 仍由 `shapeRows()` 抄…**对不上就以样张为准改 `shapeRows()`**"，
      //    这里就是照样张改的那一处（已记 `交接单` §三）。
      //    两行式里操作格**在标记里就叫 `.act`**（各页自己写死），所以按类找、不猜位置。
      const twoLine = tb.classList.contains('t2');
      const labels = twoLine ? [] : [...tb.querySelectorAll('thead th')].map((th) => th.textContent.trim());
      for (const tr of tb.querySelectorAll('tbody tr')) {
        // ⚠️ **小节标题行要跳过**（名册的「历届成员」，`docs/03` §3.4，2026-09-30 `#109`）：
        //    它不是一条数据；给它抄 `data-label` 会把它变成"只有标签没有值"的一行卡片。
        if (tr.classList.contains('sec-h')) {
          continue;
        }
        const tds = [...tr.children].filter((c) => c.tagName === 'TD');
        if (!twoLine) {
          tds.forEach((td, i) => { td.dataset.label = labels[i] || ''; });
        }
        const cell = twoLine ? tr.querySelector('td.act') : tds[tds.length - 1];
        const acts = cell && (twoLine || tds.length > 1)
          ? cell.querySelector('button, a, input, select') : null;
        if (acts) {
          if (!twoLine) cell.classList.add('act');
          tr.classList.add('has-act');
        } else {
          if (!twoLine && cell) cell.classList.remove('act');
          tr.classList.remove('has-act');
        }
      }
    }
  },

  // ⚠️ **2026-09-28 删掉了整个 `mountRowSwipe()`**（原来是"手机端表格行左滑露出操作按钮"）：
  //    用户真机反馈**右滑展不出**「编辑」那些按钮 ⇒ 改成**操作直接显示**在卡片底部（纯 CSS，见 `band.css` §10），
  //    手势这一整块（含 2026-09-28 为"让开系统返回手势"加的左缘保护区与方向意图判定）**不再需要**，一并删除。
  //    ⭐ 副作用是好的：本页从此**没有任何横向手势**，也就不会再跟浏览器/系统的边缘返回抢那一下
  //    （那种手势是系统级的、网页本来就关不掉——删掉才是根治）。
  //    留给将来的：若哪天要恢复"滑动露出"，**别照抄 `pointerdown` + `window.pointermove` 那套**，
  //    先读 `band.css` §10 与 `docs/14` §14.6.4 八 当时撤销的理由（卡片下方已有常显按钮，滑动与点按会互相打架）。

  /** 撤骨架屏（手机端，`docs/14` §14.6.4 六 + 「界面的活性」8）：**必须在 `BAND.alive(...)`
      跑之前撤掉**——那一批"只播一次"的入场快照会把骨架一起拍进去，等入了场就已经是终态。
      幂等（重复调用只去掉一个类）。 */
  endLoading(mbody) {
    if (mbody) mbody.classList.remove('loading');
  },

  /** 下拉刷新的"刷什么"（规格只写了形态、没写刷什么 ⇒ 见 `交接单` §三 移交事项）：
      各页可以 `BAND.onRefresh(fn)` 自己登记；没登记的退回**各页那个全局 `load()`**
      ——本仓 22 个页面的加载函数都是 `function load()` 的**函数声明**（⇒ 挂在 window 上可取）。
      两条都没有的页面（沉浸式的 `showcase.html` 这类）**不启用**下拉手势，免得给一个空承诺。 */
  onRefresh(fn) { this._refresh = fn; },
  refresher() {
    if (typeof this._refresh === 'function') return this._refresh;
    if (typeof window.load === 'function') return window.load;
    return null;
  },

  /** 手机端下拉刷新（`docs/14` §14.6.4 六 + 「界面的活性」6）：**参数写死，别自己调**——
      触发阈值 **64**、位移衰减 **×0.5**、位移封顶 **96**。
      ⚠️⚠️ `transform` **只许打在 `.mbody`** 上（打在 `.main` 上会建 containing block，
      `showcase.html` 那些 `position: fixed` 层与 `#wel` 的参照系会一起乱）。
      ⚠️ 触摸必须走 `touchmove` + `{ passive: false }`，而且**只有确认在下拉才 `preventDefault`**
      （别抢正常滚动）；Pointer Events 做不到这件事。
      ⚠️ 鼠标分支只为**窄窗口下的验收**（鼠标拖不触发原生滚动，没有要压的东西）；
      整段由 `isNarrow()`（**只认窄屏**，`#114`）圈住 —— 见 `allowed()`。 */
  mountPullRefresh(s) {
    if (!s || !s.mbody || !this.refresher()) return;   // 没有刷新器就别给这个手势（空承诺）
    const THRESHOLD = 64, DECAY = .5, CAP = 96;
    const root = document.documentElement;
    const body = s.mbody, hint = s.hint, tx = s.hintTx;
    let startY = 0, dy = 0, dragging = false, busy = false;
    const atTop = () => (document.scrollingElement.scrollTop || 0) <= 0;
    // ⚠️ **抽屉开着时不许接管**（2026-09-28 补）：`band.css` 里 `html.drawer-open` 已经锁了
    // `<html>/<body>` 的滚动，但这段手势是**用 transform 推 `.mbody`**、根本不走滚动
    // ⇒ 不拦的话，"锁住主页面"就成了半句话（正文照样被拖着走）。
    const allowed = () => this.isNarrow() && !busy
      && !root.classList.contains('drawer-open') && atTop();
    const paint = () => {
      root.classList.toggle('ptr-drag', dy > 0);
      body.style.transform = dy ? 'translateY(' + dy + 'px)' : '';
      hint.style.setProperty('--mh-rot', Math.min(dy / 1.4, 180) + 'deg');
      tx.textContent = dy >= THRESHOLD ? '松手刷新' : '下拉刷新';
    };
    const begin = (y) => { if (!allowed()) return; startY = y; dy = 0; dragging = true; };
    const move = (y) => {
      if (!dragging) return;
      const raw = y - startY;
      if (raw <= 0) { if (dy !== 0) { dy = 0; paint(); } return; }
      dy = Math.min(raw * DECAY, CAP);     // 橡皮筋：衰减 + 封顶，**只动 transform**
      paint();
    };
    const reset = () => {
      root.classList.remove('ptr-drag', 'ptr-load');
      body.style.transform = '';
      tx.textContent = '下拉刷新';
    };
    const end = () => {
      if (!dragging) return;
      dragging = false;
      if (dy < THRESHOLD) { reset(); return; }
      busy = true;
      root.classList.add('ptr-load');
      body.style.transform = 'translateY(' + THRESHOLD + 'px)';
      tx.textContent = '正在刷新…';
      const done = () => {                 // 收尾：给一句「已是最新」再归位（别静默弹回）
        root.classList.remove('ptr-load');
        tx.textContent = '已是最新';
        body.style.transform = '';
        busy = false;
        setTimeout(() => { root.classList.remove('ptr-drag'); tx.textContent = '下拉刷新'; }, 340);
      };
      let p;
      try { p = Promise.resolve(this.refresher()()); } catch (e) { p = Promise.resolve(); }
      Promise.resolve(p).catch(() => {}).then(() => setTimeout(done, 400));
    };
    document.addEventListener('touchstart', (e) => begin(e.touches[0].clientY), { passive: true });
    document.addEventListener('touchmove', (e) => {
      if (!dragging) return;
      if (e.touches[0].clientY - startY > 0) e.preventDefault();
      move(e.touches[0].clientY);
    }, { passive: false });
    document.addEventListener('touchend', end);
    document.addEventListener('touchcancel', () => { if (dragging) { dragging = false; reset(); } });
    // 鼠标分支（窄窗口下验收用）：只在窄屏生效（`isNarrow()` 圈着，见 `allowed()`）
    document.addEventListener('pointerdown', (e) => {
      if (e.pointerType === 'mouse' && e.button === 0) begin(e.clientY);
    });
    window.addEventListener('pointermove', (e) => {
      if (e.pointerType === 'mouse') move(e.clientY);
    });
    window.addEventListener('pointerup', (e) => {
      if (e.pointerType === 'mouse') end();
    });
  },

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
    // 手机端的第二层要能**回到抽屉**：给一个「←」（`band.css` 里 `display:none`，只在手机段出现）。
    // ⚠️ 样张（`mobile-mock.html` 的 `.p2head .back`）里有它，而规格正文只写了"Esc / 点面板外关闭"——
    //    手机上既没有 Esc、第二层又把 320px 占满，没有它就等于"进去出不来"（已记 `交接单` §三）。
    const back = document.createElement('button');
    back.className = 'p-back';
    back.type = 'button';
    back.setAttribute('aria-label', '返回菜单');
    back.textContent = '←';
    panel.prepend(back);
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
    back.addEventListener('click', () => setOpen(false));
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
          : `<div class="p-num"><b data-count>${this.ratePct(k.rehearsal.rate, k.rehearsal.expected)}</b><span>排练出勤 · 请假 ${k.rehearsal.leave}</span></div>
             <div class="p-num"><b data-count>${this.ratePct(k.sectionClass.rate, k.sectionClass.expected)}</b><span>声部课出勤 · 请假 ${k.sectionClass.leave}</span></div>
             <div class="p-num"><b data-count>${k.rehearsal.present + k.sectionClass.present}</b><span>参加场次</span></div>
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
    /* 「界面的活性」：这三个数播一次（上面标了 `data-count`）。
       ⚠️ 「入团天数」属"日期与时间"、录音条数初值是 `—`，按规格 ③ **都不数**，所以没标。
       ⚠️ 键用**常驻容器 `nums`**（面板每次展开都重建 innerHTML，用里面的 `<b>` 当键会每次都重播）。 */
    this.alive(nums);
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

      // ⚠️ **2026-09-28 删掉了原先这里的一段"等字体就绪再量矩形"**（原代码是
      //    `await Promise.race([document.fonts.load('600 33px BandName'), 350ms 上限])`）。
      //    它存在的**唯一**理由是：姓名那时用的是**下载来的子集字体**，字形没落地时量到的是
      //    回退黑体的字宽 ⇒ FLIP 落点会偏（且只在无缓存的首访那一次明显）。
      //    现在姓名改用**系统楷体**（见 `band.css` 的 `.wname` 与那里的留痕）——系统字体**即时就绪**，
      //    没有"等"可言，留着就只是白等（还可能白等满 350ms）⇒ 整段删除。
      //    ⭐ 落点仍然是**运行时量出来的**（换宽度 / 手机端自动对上，绝不写死坐标），这点没变。

      const av = wel.querySelector('.wel-av');
      const tx = wel.querySelector('.wel-tx');
      const wname = tx.querySelector('.wname');
      const me = this.me;
      this.paintAvatar(av, me);   // 落点那份也要跟着头像走（第三处，最容易漏）
      // ⚠️ 生日当天（`#115`）**遮罩里那行也要一起换**：规格要求遮罩里那行与页面上那行**逐字一致**
      //    （同号 33px、飞行时 `scale ≈ 1`——它们本来就是"同一行在飞"）。只换页面那行的话，
      //    飞过去的一瞬间会从「欢迎回来」跳成「生日快乐」，而那正好是用户最看得见的一刻。
      //    ⚠️ 这行的开头几个字是**各页 HTML 里写死的文本节点**（24 个页面都有），所以只能改节点值。
      const mine = (this._bd || []).some((b) => b.id === me.id);
      const role = this.roleLabel(me.role) || me.role;
      tx.childNodes[0].nodeValue = mine ? '生日快乐，' : '欢迎回来，';
      wname.textContent = me.name;
      tx.append(mine ? `（${role}）` : role);   // 名字与职称之间不用标点（docs/14 §14.6.4）

      const timers = [];
      const after = (ms, fn) => timers.push(setTimeout(fn, ms));
      const clearTimers = () => { timers.forEach(clearTimeout); timers.length = 0; };
      let playing = true;

      // 落点：**运行时量出来**（换宽度、手机端都会自动对上，绝不写死坐标）
      // ⚠️ 头像那颗**按门控分支**（2026-09-28 连带修的真问题）：手机端侧栏是**默认关着的抽屉**，
      //    抽屉里那颗头像在**屏幕之外**（`x ≈ -320`），照它飞等于把头像飞出屏幕——而现有守卫
      //    只在"元素不存在"时早退，**抓不到"元素存在、只是在屏外"**。
      //    ⇒ 手机 / 触屏改取**标题栏那颗**（一直可见，本来就是"开抽屉"的开关，落点/开关/身份合成一个元素）。
      // ⚠️ 欢迎行两套形态**一样**（`.welcome` 在 `.head` 里、手机端也一直可见）：别把它藏了。
      const targets = () => ({
        av: document.querySelector(this.isMobile() ? '.mhead .mav-toggle span' : '.rail .me .ic span'),
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
          av.style.transition = tx.style.transition = 'opacity 240ms linear';   // ⑥-1 先淡掉头像/文字 0.24s
          av.style.opacity = tx.style.opacity = '0';
          after(240, () => {
            wel.style.transition = 'opacity 400ms linear';                     // ⑥-2 再淡黑底 0.40s
            wel.style.opacity = '0';
            after(400, () => {
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
      // ⚠️ 下面这几个毫秒值 = `docs/14` §14.6.4「欢迎过渡」**六步表**那一行（2026-09-30 整体延长）：
      //    用户原话「登录界面登录到主页面的**过场动画太短了**，我觉得可以延长一下」⇒ 合计约 **2.7s**
      //    （原来是 0.24/0.19/0.27/0.62/0.16/0.26，合计约 1.8s）。
      //    ⚠️⚠️ **延长的是"每一段"，不是只把位移拉长**——只拉位移会变成"停得太短、飞得太久"的**慢动作**。
      //    ⇒ **六个一起改**，改完对着那张表逐段核一遍。⚠️ 这条的来历有点特别：规格只在表里改了值、
      //    **没开交接单条目、也没挂 🚧**，所以按"交接单剩余项"分批时**整条漏掉了**（见 `交接单` §三）。
      requestAnimationFrame(() => {
        av.style.transition = tx.style.transition = 'opacity 380ms linear';   // ④-1 头像淡入 0.38s
        av.style.opacity = '1';
        after(300, () => { tx.style.opacity = '1'; });                        // ④-2 +0.30s 欢迎行淡入
        // ⚠️⚠️ 这个数是**前三段累加**得来的，不是规格表里那个"0.50"：那张六步表是**累加**口径
        //    （0.38 头像淡入 → +0.30 欢迎行淡入 → **停 0.50** → 飞 0.92 → 0.24 → 0.40，合计 2.74s）。
        //    照"0.50"直接写会变成"从 t=0 起 500ms 就飞" ⇒ 整条只有 2.08s（旧值 1.81s，只差 270ms，
        //    而规格要的是差 900ms）——**是探针量墙钟把这个错误当场抓出来的**（反例验算里旧值 1999ms，
        //    我的第一版 2192ms，两个窗口挨着，一眼看出没做够）。
        after(1180, () => {                           // ⑤ 停一下，再飞（= 0.38 + 0.30 + 0.50）
          av.style.transition = tx.style.transition =
            'transform 920ms cubic-bezier(.44, 0, .2, 1), opacity 200ms linear';   // ⑤ 飞行 0.92s
          place();
          finish(940);
        });
      });
    });
  },

  /**
   * 猫可以**晚一点**来（`docs/14` §14.6.4）：`showcase.html` 的入场编排（约 6s）与全屏看图期间
   * 必须把它按住——猫的 `z-index` 是 `2147483647`、`position:fixed`，**抬层级打不过它**。
   * `catHold = true` 期间 `loadCat()` 只记一笔不加载；`releaseCat()` 把它放出来（幂等）。
   * ⚠️ 用的人要在**每一条**出口都调 `releaseCat()`（收尾 / 跳过 / 降级），漏一条那条路上就永远没有猫。
   */
  catHold: false,
  catPending: false,
  releaseCat() {
    this.catHold = false;
    if (!this.catPending) return;
    this.catPending = false;
    this.loadCat();
  },

  /** 萌宠 oneko（docs/14 §14.6.4「页面萌宠」）：登录后所有页、仅桌面。
     matchMedia 不满足时**连脚本都不插**（不是加载了再藏）——触屏没有光标可追。 */
  loadCat() {
    if (this.catHold) { this.catPending = true; return; }
    if (!window.matchMedia
        || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    if (document.getElementById('oneko')) return;
    const s = document.createElement('script');
    s.src = '/vendor/oneko/oneko.js';
    s.dataset.cat = '/vendor/oneko/oneko.gif';
    document.body.appendChild(s);
  },

  /**
   * 节拍器的一声（练习录音页）：**用户主动发起、持续可控的声音** —— `docs/14` §14.6.6 那张表
   * 把它和"确认类声音"分开的正是这一点，也是**第四条留痕**讲的"拍点指示不算 idle 动画"的同一条界线。
   * <p>
   * ⚠️ **不受 `band_sound` 约束**（**显式例外**，不是漏写）：一个开了却不出声的节拍器，看起来就是坏的。
   * ⚠️ **复用唯一的 `BAND._ac`** —— 全页只允许一个 `AudioContext`，这里绝不 `new` 第二个。
   * ⚠️ 包络与 `chime()` 同一路子：**指数衰减**（线性到 0 会爆音）。**重拍高且响、弱拍低**。
   * @param {boolean} accent 是不是每小节的第 1 拍
   * @param {number} [when] 排在音频时钟的哪个时刻（lookahead 调度用）；不传＝马上
   * @returns {number|null} 实际排出去的时刻（秒）；拿不到音频就 `null`
   */
  tick(accent, when) {
    try {
      if (!this._ac) {
        this._ac = new (window.AudioContext || window.webkitAudioContext)();
      }
      const ac = this._ac;
      if (ac.state === 'suspended') ac.resume();
      const t = Math.max(when || 0, ac.currentTime);
      const osc = ac.createOscillator();
      const gain = ac.createGain();
      osc.type = 'sine';
      osc.frequency.value = accent ? 1600 : 1000;
      // 起手先给一个极小值再指数爬到峰值：`exponentialRamp` 不接受 0，且这样不会有"咔"的起音
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(accent ? 0.34 : 0.17, t + 0.004);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.06);
      osc.connect(gain).connect(ac.destination);
      osc.start(t);
      osc.stop(t + 0.08);
      return t;
    } catch (e) { return null; }
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

  /**
   * 「界面的活性」的唯一入口（`docs/14` §14.6.4；算法与取值见 `ALIVE` 那段注释）。
   * 参数是**常驻容器**（重渲染不重建的那些节点）；数据写完**之后**才调用 ——
   * 早调会快照到 `—` / 空点阵，而"播过"的标志一置位就**再也不播**了。
   */
  alive(...roots) {
    // 手机端的卡片行要在**入场动画之前**把 `data-label` / 可左滑标记铺好（`shapeRows` 幂等）——
    // `ALIVE.play` 用 WeakSet 记过"这个 root 播过了"，**重渲染时不会再走**，所以列形不能挂在它里面。
    roots.forEach((root) => this.shapeRows(root));
    // 骨架屏必须在入场之前撤（`docs/14` §14.6.4 六 / 「界面的活性」8）——这是"数据到了"的信号。
    if (this.skeleton) this.endLoading(this.skeleton.mbody);
    ALIVE.play(...roots);
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
