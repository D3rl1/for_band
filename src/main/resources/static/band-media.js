'use strict';

Object.assign(BAND, {

  /**
   * 预置头像清单在本地的两份表示：`avatarIndex`（`id → 清单条目`，给 `paintAvatar` 同步查）
   * 与 `avatarList`（清单原文，给「个人页」渲染选择网格）。条目可带可选字段
   * `stillFile`（减少动态效果时的静帧）与 `animated`（#253：分组唯一依据）。
   * `avatarIndex === null` = 还没就绪。权威永远是 `vendor/avatars/manifest.json` 那一份
   * （docs/14 §14.6.4：**别在别处再抄一遍**），这里只是把它搬进内存一次。
   */
  avatarIndex: null,
  avatarList: [],
  avatarCall: null,
  /**
   * 本人当前有效的限定头像资格（#266）：`Map<avatarId, batch|null>`。
   * `null` = 还没就绪；请求失败按空 Map 处理——**失败即隐藏**：宁可让限定项不出现，
   * 也不能把没资格选的项摆出来（保存时后端还会再拦一次）。
   */
  avatarGrants: null,
  avatarGrantsCall: null,
  /** 已画过的头像（元素 → member）：`prefers-reduced-motion` 变了要**就地重画**，不能等下次刷新。 */
  paintedAvatars: new Map(),
  avatarMedia: null,

  /**
   * 头像框清单（docs/14 §14.6.4「秋日树框」）。与头像清单一样，权威只有
   * `vendor/avatar-frames/manifest.json` 那一份；`frameIndex === null` = 还没就绪。
   * 每条至少带 `{id, name, dir, file}`，`dir` 下的 `assets/` 是运行素材。
   */
  frameIndex: null,
  frameList: [],
  frameCall: null,
  /** 当前挂着的框实例（元素 → controller）。重画 / 清空时必须先 destroy，否则 RAF 与 observer 会泄漏。 */
  paintedFrames: new Map(),
  frameImages: new Map(),

  /** 收下清单：同时建索引与原文，并**写穿会话缓存**（隐私模式下 `sessionStorage` 会抛，忽略即可）。 */
  adoptAvatars(list) {
    this.avatarList = list;
    this.avatarIndex = new Map(list.map((item) => [item.id, item]));
    try {
      sessionStorage.setItem('band_avatars', JSON.stringify(list));
    } catch (e) { /* 隐私模式：忽略 */ }
    this.repaintAvatars();   // 新清单可能带来 stillFile / 新条目：已画的头像按最新元数据重取文件
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
          .map((item) => {
            // 只搬清单里显式写了的可选字段；旧条目不补默认值（省略 = 静态），别按后缀 / id 猜分组
            // #260：description 是可选纯文本说明，定稿后随素材写进同一份清单；
            // 空 / 非字符串按“无说明”兼容（前端只存合法字符串，渲染时再判空）。
            const out = { id: item.id, name: item.name || item.id, file: item.file };
            if (typeof item.description === 'string') out.description = item.description;
            if (item.stillFile) out.stillFile = item.stillFile;
            if (item.animated === true) out.animated = true;
            // #266：`scope: "grant"` = 限定头像，必须有发放资格才能选。其余写法一律当公共头像
            // （限定是例外，不能靠"忘了写字段"把公共头像误关起来）。
            if (item.scope === 'grant') out.grantOnly = true;
            return out;
          });
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

  /**
   * 取本人有效的限定头像 id 列表（#266）。与 {@link #avatars} 一样**永不 reject**：
   * 拿不到就按空集 ⇒ 限定项不显示（失败即隐藏），公共头像与页面其它功能照常。
   * 同一页里多处调用只发一次请求；登录 / 登出由页面整页跳转，不在这里跨账号复用。
   */
  myAvatarGrants() {
    if (this.avatarGrantsCall) {
      return this.avatarGrantsCall;
    }
    this.avatarGrantsCall = fetch('/api/me/avatar-grants')
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        const list = body && Array.isArray(body.data) ? body.data : [];
        // 每条 {avatarId, batch}；batch 供选中区显示「纪念限定 · 批次名」，可空。
        this.avatarGrants = new Map(list
          .filter((g) => g && typeof g.avatarId === 'string')
          .map((g) => [g.avatarId, typeof g.batch === 'string' && g.batch ? g.batch : null]));
        return this.avatarGrants;
      })
      .catch((e) => {
        console.warn('限定头像资格读取失败，本次不显示限定项', e);
        this.avatarGrants = new Map();
        return this.avatarGrants;
      });
    return this.avatarGrantsCall;
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

  /** 收下框清单：建索引 + 写穿会话缓存，并重画已挂过的头像（框清单可能晚于头像到达）。 */
  adoptFrames(list) {
    this.frameList = list;
    this.frameIndex = new Map(list.map((item) => [item.id, item]));
    try {
      sessionStorage.setItem('band_avatar_frames', JSON.stringify(list));
    } catch (e) { /* 隐私模式：忽略 */ }
    this.repaintAvatars();
  },

  /** 取头像框清单（**永不 reject**：拿不到就当空清单，头像照常显示，只是不挂框）。 */
  frames() {
    if (this.frameCall) {
      return this.frameCall;
    }
    this.frameCall = fetch('/vendor/avatar-frames/manifest.json')
      .then((res) => res.json())
      .then((data) => {
        const list = ((data && data.frames) || [])
          .filter((item) => item && item.id && item.dir)
          .map((item) => {
            const out = {
              id: item.id,
              name: item.name || item.id,
              dir: item.dir,
              file: item.file || 'still.webp',
              animated: item.animated === true,
            };
            // 与头像同一口径（#260）：description 可选纯文本，只搬显式写了的那一条；
            // 空 / 非字符串按“无说明”兼容，渲染时再判空。
            if (typeof item.description === 'string') out.description = item.description;
            return out;
          });
        this.adoptFrames(list);
        return this.frameIndex;
      })
      .catch((e) => {
        console.warn('头像框清单读取失败，本次不挂框', e);
        this.frameIndex = new Map();
        return this.frameIndex;
      });
    return this.frameCall;
  },

  /** 页面首帧同步读会话缓存（与头像缓存同一策略）。 */
  loadCachedFrames() {
    if (this.frameIndex) {
      return;
    }
    try {
      const raw = sessionStorage.getItem('band_avatar_frames');
      if (raw) {
        this.adoptFrames(JSON.parse(raw) || []);
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
    this.cleanupFrames();   // 列表筛选 / 重绘会整块换 DOM：先回收已离屏的框实例，别让 RAF / observer 越积越多
    const avatar = member && member.avatar;
    const item = avatar && this.avatarIndex ? this.avatarIndex.get(avatar) : undefined;
    const file = item ? this.avatarFile(item) : undefined;
    const frameId = member && member.avatarFrame;
    const frame = frameId && this.frameIndex ? this.frameIndex.get(frameId) : undefined;

    // 框是独立装饰层：先拆旧的（含 RAF / observer），再重建头像内容。
    this.destroyAvatarFrame(el);
    el.textContent = '';
    el.classList.add('band-avatar-host');

    const inner = document.createElement('span');
    inner.className = 'band-avatar-inner';
    el.appendChild(inner);

    if (file) {
      this.watchAvatarMotion();
      const img = document.createElement('img');
      img.className = 'av-img';
      img.alt = '';
      img.src = '/vendor/avatars/' + encodeURIComponent(file);
      inner.appendChild(img);
    } else {
      // ① 没选头像 ② 清单外的旧 id ③ 清单还没就绪 / 读不到 ④ 清单里 file 为空 —— 四种都回退姓名首字
      inner.textContent = Array.from((member && member.name) || '?')[0];
    }

    // 有头像或有框都要记住：框清单晚到时还要重画（只是首字 + 框也算“画过”）。
    if (file || frameId) this.paintedAvatars.set(el, member);
    else this.paintedAvatars.delete(el);

    if (frame) {
      this.mountAvatarFrame(el, frame);
    } else if (frameId && !this.frameIndex) {
      // 清单还没到：先画头像，框清单到达后 adoptFrames() 会重画这一颗。
      this.frames();
    }
  },

  /** 清空一个头像容器（含框实例）。侧栏退出登录等直接 `textContent=''` 的地方改用这个，避免框的 RAF 泄漏。 */
  clearAvatar(el) {
    if (!el) return;
    this.destroyAvatarFrame(el);
    this.paintedAvatars.delete(el);
    el.textContent = '';
  },

  destroyAvatarFrame(el) {
    const controller = this.paintedFrames.get(el);
    if (controller) {
      controller.destroy();
      this.paintedFrames.delete(el);
    }
  },

  /** 回收已经不在文档里的框实例（列表整体重绘后旧节点不会自己 destroy）。 */
  cleanupFrames() {
    for (const [el, controller] of this.paintedFrames) {
      if (!el.isConnected) {
        controller.destroy();
        this.paintedFrames.delete(el);
      }
    }
  },

  /** 头像框运行素材的图片缓存：同一个文件只加载一次，多个头像共用。 */
  frameImage(url) {
    const key = String(url);
    if (this.frameImages.has(key)) return this.frameImages.get(key);
    const promise = new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error('头像框素材加载失败：' + key));
      img.src = key;
    });
    this.frameImages.set(key, promise);
    promise.catch(() => this.frameImages.delete(key));
    return promise;
  },

  /**
   * 挂一个头像框（docs/14 §14.6.4「秋日树框」）。
   * - 框是 `el` 的**兄弟层**：头像内容在 `.band-avatar-inner` 里被裁成圆，树 / 落叶画在 canvas 上、
   *   允许溢出到容器外（`pointer-events:none`，不挡点击）。
   * - 小头像（< 56px，名册 / 考勤等）只画静态树、不跑 RAF；大一点的头像按偏好播六条落叶。
   * - `prefers-reduced-motion`、离屏、页面隐藏、素材失败都只影响装饰，原头像保持。
   */
  mountAvatarFrame(host, item) {
    if (this.paintedFrames.has(host)) return this.paintedFrames.get(host);
    const canvas = document.createElement('canvas');
    canvas.className = 'band-avatar-frame';
    canvas.setAttribute('aria-hidden', 'true');
    host.appendChild(canvas);
    const ctx = canvas.getContext('2d');
    const base = '/vendor/avatar-frames/' + encodeURIComponent(item.dir) + '/assets/';
    const reduced = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    const plans = [
      { x: .42, y: .38, end: .46, sway: .045, duration: 11.6, phase: .14, scale: .76, angle: -.6 },
      { x: .55, y: .25, end: .66, sway: .035, duration: 13.1, phase: .68, scale: .65, angle: .5 },
      { x: .37, y: .43, end: .40, sway: .04, duration: 10.4, phase: .43, scale: .73, angle: -.3 },
      { x: .62, y: .28, end: .73, sway: .027, duration: 14.2, phase: .83, scale: .61, angle: .8 },
      { x: .48, y: .35, end: .55, sway: .047, duration: 12.7, phase: .30, scale: .69, angle: -.9 },
      { x: .30, y: .44, end: .36, sway: .024, duration: 15, phase: .94, scale: .55, angle: .2 },
    ];
    const state = { tree: null, still: null, leaves: [] };
    let disposed = false, paused = false, visible = true, raf = 0, previous = null, time = 0;

    const compact = () => Math.max(0, host.getBoundingClientRect().width) < 56;

    function paint() {
      if (disposed || !state.tree || !canvas.width) return;
      const edge = canvas.width;
      ctx.setTransform(edge / 1254, 0, 0, edge / 1254, 0, 0);
      ctx.clearRect(0, 0, 1254, 1254);
      // 小头像只留静态树：一排树各自飘叶子既糊又费电，也符合“小尺寸简化框”的既定边界。
      if (compact()) {
        ctx.drawImage(state.tree, 0, 0, 1254, 1254);
        return;
      }
      if (reduced && reduced.matches) {
        if (state.still) ctx.drawImage(state.still, 0, 0, 1254, 1254);
        else ctx.drawImage(state.tree, 0, 0, 1254, 1254);
        return;
      }
      ctx.drawImage(state.tree, 0, 0, 1254, 1254);
      if (!state.still || state.leaves.length < 5) return;
      plans.forEach((plan, index) => {
        const leaf = state.leaves[index % state.leaves.length];
        const p = (time / plan.duration + plan.phase) % 1;
        const swing = Math.sin(p * Math.PI * 3.2 + index * 1.5);
        const x = (plan.x + (plan.end - plan.x) * p + swing * plan.sway * Math.sin(Math.PI * p)) * 1254;
        const y = (plan.y + (.887 - plan.y) * (1 - Math.pow(1 - p, 1.22))) * 1254;
        ctx.save();
        ctx.translate(x, y);
        ctx.rotate(plan.angle + swing * .52 + p * Math.PI * (index % 2 ? .5 : -.6));
        ctx.scale(plan.scale * (.42 + .58 * Math.abs(Math.cos(p * Math.PI * 2.2 + index))), plan.scale);
        ctx.globalAlpha = Math.min(1, p / .065) * Math.min(1, (1 - p) / .09);
        ctx.drawImage(leaf, -leaf.naturalWidth / 2, -leaf.naturalHeight / 2);
        ctx.restore();
      });
    }

    function active() {
      return !disposed && visible && !document.hidden && !paused && !compact()
        && item.animated === true && !!state.still && state.leaves.length >= 5
        && !(reduced && reduced.matches);
    }

    function tick(now) {
      raf = 0;
      if (!host.isConnected || !active()) { previous = null; return; }
      if (previous !== null) time += Math.min((now - previous) / 1000, .08);
      previous = now;
      paint();
      raf = requestAnimationFrame(tick);
    }

    function update() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0; previous = null;
      paint();
      if (active()) raf = requestAnimationFrame(tick);
    }

    function resize() {
      const width = Math.max(1, Math.round(host.getBoundingClientRect().width * Math.min(devicePixelRatio || 1, 2)));
      if (canvas.width !== width || canvas.height !== width) {
        canvas.width = width;
        canvas.height = width;
      }
      paint();
    }

    const load = (file) => this.frameImage(base + file);
    const controller = {
      ready: null,
      setPaused(value) { paused = Boolean(value); update(); },
      destroy() {
        disposed = true;
        if (raf) cancelAnimationFrame(raf);
        if (resizeObserver) resizeObserver.disconnect();
        if (intersection) intersection.disconnect();
        if (reduced && reduced.removeEventListener) reduced.removeEventListener('change', update);
        document.removeEventListener('visibilitychange', update);
        canvas.remove();
        BAND.paintedFrames.delete(host);
      },
    };
    const resizeObserver = window.ResizeObserver ? new ResizeObserver(() => { resize(); if (!compact()) ensureFull(); }) : null;
    const intersection = window.IntersectionObserver
      ? new IntersectionObserver((entries) => { visible = entries[0].isIntersecting; update(); })
      : null;
    if (resizeObserver) resizeObserver.observe(host);
    if (intersection) intersection.observe(host);
    if (reduced && reduced.addEventListener) reduced.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);

    const ensureFull = () => {
      if (state.still && state.leaves.length >= 5) return Promise.resolve();
      return Promise.all([load('still.webp'), load('leaf-1.webp'), load('leaf-2.webp'),
        load('leaf-3.webp'), load('leaf-4.webp'), load('leaf-5.webp')])
        .then(([still, ...leaves]) => {
          if (disposed) return;
          state.still = still;
          state.leaves = leaves;
          update();
        })
        .catch(() => { /* 素材失败只撤装饰：保留已经画出的树 / 头像 */ });
    };

    this.paintedFrames.set(host, controller);
    controller.ready = load('tree.webp')
      .then((tree) => {
        if (disposed) return;
        state.tree = tree;
        resize();
        if (!compact()) return ensureFull();
      })
      .catch(() => {
        // 树都加载失败：整个装饰撤掉，原头像保持不变。
        if (!disposed) controller.destroy();
      });
    return controller;
  },

  /** 是否减少动态效果。拿不到 `matchMedia`（很老的浏览器 / 无 DOM 的测试）就当 false。 */
  prefersReduced() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  },

  /** 条目实际该用的文件：动态条目在 reduce 下取静帧 `stillFile`，其余一律 `file`。 */
  avatarFile(item) {
    if (!item || !item.file) return null;
    return this.prefersReduced() && item.stillFile ? item.stillFile : item.file;
  },

  /** 偏好切换时把已画过的头像就地重画（**不重置任何表单草稿**）；监听只挂一次。 */
  watchAvatarMotion() {
    if (this.avatarMedia || !window.matchMedia) return;
    this.avatarMedia = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => this.repaintAvatars();
    if (this.avatarMedia.addEventListener) this.avatarMedia.addEventListener('change', onChange);
    else if (this.avatarMedia.addListener) this.avatarMedia.addListener(onChange);
  },

  repaintAvatars() {
    this.cleanupFrames();
    for (const [el, member] of this.paintedAvatars) {
      if (!el.isConnected) { this.paintedAvatars.delete(el); continue; }
      this.paintAvatar(el, member);
    }
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

  /**
   * 取文件的**签名 URL**（手机端"打开 / 下载"谱子用）：`{ view, download, expiresAt }`。
   * ⚠️ **必须带 `Authorization` 头**——这条要鉴权（荣誉团员在这里就 403）；只有拿到的 `view` 那条
   * 才免头（票据即凭证）。返回的是**相对 URL**（`/api/files/{id}/view?t=…`），同源，直接给 `<a href>` 即可。
   * ⭐ 票据**按天分桶**（后端 `FileTicketService`）⇒ 同一天内 URL 一字不变 ⇒ 浏览器缓存生效，**反复打开不重复下载**。
   */
  async fileTicket(fileId) {
    const res = await fetch('/api/files/' + fileId + '/ticket', {
      headers: { Authorization: 'Bearer ' + this.token() },
    });
    const envelope = await res.json().catch(() => ({ code: 10003, message: 'HTTP ' + res.status }));
    if (envelope.code !== 0) {
      this.toast(envelope.message || ('错误码 ' + envelope.code));
      throw envelope;
    }
    return envelope.data;
  },

  /** 浏览器**不能内联渲染**的类型（PDF / PPT / Word…）—— 判据是"不是图片、也不是音频"。
      类型未知（空串）也算：宁可给签名链接，也别再退回会白屏的 `iframe`（见 `previewFile`）。 */
  nonInline(type) {
    return !/^(image|audio)\//.test(type || '');
  },

  /** 打开附件（谱子 / 音频；`piece-detail.html` 的「查看 / 试听」）。
      ⚠️ **手机与桌面走两条路**（`docs/08` §8.5，`#113`）——
      起因：手机上报"点了毫无反应"。根因 = 原来那句 `window.open(await fileBlobUrl(id))`：
      **`window.open` 在 `await` 之后才调用** ⇒ 那一刻**已经脱离用户手势** ⇒
      手机浏览器 / 弹窗拦截器**直接把它拦掉**（返回 `null`），而代码**不校验返回值** ⇒ **静默失败**。
      ⭐ **手机走页面内预览层**（完全绕开弹窗拦截，与公告 / 生日是同一族形态）；
      桌面仍开新标签，但**先把窗同步开出来**（还在手势里）再填内容，且**校验 `null`**。
      ⚠️ 这个 bug **在桌面上复现不出来**（桌面拦截宽松）——桌面测通 ≠ 修好，必须真机验。
      `name` / `type` 由调用方（`AttachmentView`）给：手机端要拿 `type` 判"能不能内联渲染"。 */
  async openFile(fileId, name, type) {
    if (this.isMobile()) {
      return this.previewFile(fileId, name, type);
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

  /**
   * 页面内全屏预览层（手机端走它，`#113`）：
   * · **图片 / 音频** → 仍用 blob（`<img>` / `<audio>`，手机上本来就是好的）；
   * · **PDF 及其它不可内联渲染的类型** → **不再塞 `iframe`**，改给两个**真链接**（「打开」/「下载」），
   *   指向后端**签名 URL**（{@link fileTicket}）。
   * ⚠️ 为什么 PDF 必须换掉 blob（**2026-10-07 用户实报**）：安卓系浏览器（夸克 / 小米 / 微信）
   *   **没有内置 PDF 渲染器**，`<iframe src=blob:>` 一片空白、"在新标签打开"退路**点了也没反应**。
   *   签名 URL 是**普通同源 URL**，交给浏览器自己的下载器 / 查看器 —— 夸克、小米都可用。
   *   `#113` 的 blob 方案**从没在真机验过**，这次才暴露。
   * **完全绕开弹窗拦截**。关闭：关闭按钮 / `Esc` / 点遮罩；`prefers-reduced-motion` 不播入场。 */
  async previewFile(fileId, name, type) {
    let blob = null;
    if (!type) {                          // 老调用点 / 探针不带类型 ⇒ 先取 blob 探类型（此情形多一次下载）
      try { blob = await this.fileBlob(fileId); } catch (e) { return; }
      type = blob.type || '';
    }
    let url = null;
    let ticket = null;
    if (this.nonInline(type)) {
      try { ticket = await this.fileTicket(fileId); } catch (e) { return; }   // toast 已弹
    } else {
      if (!blob) {
        try { blob = await this.fileBlob(fileId); } catch (e) { return; }
      }
      url = URL.createObjectURL(blob);
    }
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
    let media = null;
    if (type.startsWith('image/')) {
      media = document.createElement('img');
      media.className = 'fview-img';
      media.alt = '';
      media.src = url;
      parts.push(media);
    } else if (type.startsWith('audio/')) {
      media = document.createElement('audio');
      media.className = 'fview-audio';
      media.controls = true;
      media.src = url;
      parts.push(media);
    } else {
      // PDF / PPT / Word / 类型未知：给**真链接** —— 浏览器自己去开 / 去存，不经过 blob。
      // 「打开」开新标签（真链接、落在用户手势里，不会被拦）；「下载」带 `download` + 服务端 attachment 双保险。
      if (name) {
        const title = document.createElement('div');
        title.className = 'fview-name';
        title.textContent = name;
        parts.push(title);
      }
      const openA = document.createElement('a');
      openA.className = 'go go-sm fview-dl';
      openA.href = ticket.view;
      openA.target = '_blank';
      openA.rel = 'noopener';
      openA.textContent = '打开';
      const dlA = document.createElement('a');
      dlA.className = 'go go-sm go-ghost fview-dl';
      dlA.href = ticket.download;
      dlA.download = name || '';
      dlA.textContent = '下载';
      const links = document.createElement('div');
      links.className = 'fview-links';
      links.append(openA, dlA);
      parts.push(links);
    }
    card.append(...parts);
    mask.appendChild(card);

    let closed = false;
    const shut = () => {
      if (closed) return;
      closed = true;
      document.removeEventListener('keydown', onKey);
      if (media && media.tagName === 'AUDIO') {   // 关掉预览层就把声音停掉，别让它在后台响
        try { media.pause(); } catch (e) { /* 无所谓 */ }
      }
      if (url) URL.revokeObjectURL(url);
      mask.remove();
    };
    function onKey(e) { if (e.key === 'Escape') shut(); }
    cls.addEventListener('click', shut);
    mask.addEventListener('click', (e) => { if (e.target === mask) shut(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(mask);
    cls.focus();
  }});
