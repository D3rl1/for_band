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
  }});
