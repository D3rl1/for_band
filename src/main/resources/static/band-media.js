'use strict';

Object.assign(BAND, {

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
