'use strict';

Object.assign(BAND, {

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
   * 「问题讨论」的板级最新动态（docs/15 §15.11）：`GET /api/issues/activity` 的 `latestUpdatedAt`。
   * **一次页面生命周期只拉一次**（`renderNav` 会跑两遍、红点与页面已读都要问同一件事）。
   */
  issueActivity() {
    if (!this._issueActivity) {
      this._issueActivity = this.api('/api/issues/activity')
        .then((envelope) => (envelope.code === 0 && envelope.data
          ? envelope.data.latestUpdatedAt : null))
        .catch(() => null);
    }
    return this._issueActivity;
  },

  /** 本机读到的「问题讨论」最新动态（设备级，同 `band_seen_release`）。 */
  seenIssueActivity() {
    try {
      return localStorage.getItem('band_seen_issue_activity') || '';
    } catch (e) {
      return '';
    }
  },

  /** 有没有未读：服务器值更新，或本机从没记过（首次使用也算一次未读）。 */
  issueUnread() {
    return this.issueActivity().then((latest) => {
      if (!latest) return false;
      const seen = this.seenIssueActivity();
      return !seen || String(latest) > seen;
    });
  },

  /** 打开 `issues.html` / `issue-detail.html` 后把当前值写回本机，并立刻熄灭导航红点。 */
  markIssueSeen(latest) {
    if (!latest) return;
    try { localStorage.setItem('band_seen_issue_activity', String(latest)); } catch (e) { /* 忽略 */ }
    const dot = document.querySelector('.rail .menu a[href="/issues.html"] .ndot');
    if (dot) dot.hidden = true;
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

  /**
   * 右上角操作区（`docs/14` §14.6.4「右上角操作区（铃铛 + 信封）」）：
   * **铃铛**（原「反馈 + 更新」两个汉字入口**合并成一个**；#227 起点开只剩「更新公告」一项）+
   * **信封**（上任声部长的留言）。
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
    bell.addEventListener('click', () => this.openHeadPanel(bell));
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

  /** 铃铛点开的小面板（`docs/14` §14.6.4）：**只剩「更新公告」一项**（#227 起）。
   *  提问题改走主导航「问题讨论」，红点在导航项上、不在铃铛里。
   *  ⚠️ **层级沿用「个人栏」那一档（遮罩 490 / 面板 500）**，不新造一档。
   *  关闭：点任意处 / `Esc`（与个人栏同一条规矩：**绝不能不让人关**）；再点铃铛 = 收起。 */
  openHeadPanel(bell) {
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
    sign.textContent = (note.authorName || '') + ' · ' + BAND.stamp(note.createdAt, 'day')
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
  }});
