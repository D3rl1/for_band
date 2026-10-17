'use strict';

/** 角色组（`menu` 的角色白名单复用这几个，免得每行抄一串）。**与后端 `@RequireRole` 是两处各写各的**
    ——本表只管"菜单显不显示"，真正的鉴权在服务端（`AuthInterceptor` + `PermissionService`）。 */
const NAV_ROLES = {
  all:      ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER', 'HONORARY'],
  member:   ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER', 'MEMBER'],
  staff:    ['ADMIN', 'LEADER', 'CONDUCTOR', 'SECTION_LEADER'],
  approver: ['ADMIN', 'LEADER', 'SECTION_LEADER'],
  lead:     ['ADMIN', 'LEADER'],
  admin:    ['ADMIN'],
};

Object.assign(BAND, {

  /**
   * 菜单总表（**单一来源**，`docs/14` §14.6.4）：每条只写一次——名字、页面、图标 key、可见角色、
   * **视图**（`view`：日常 / 管理）与**归属组**（`group`）。桌面平铺、手机抽屉分组、
   * 侧栏顶部的**视图开关**、页面标题**全部由它派生**；**没有第二份并列清单**。
   * ⚠️ 增删菜单 / 调分组 = 只改这张表（视图顺序在 `menuViews`，组顺序在 `menuGroups`）。
   * ⚠️ **图标 key 不许两个「不同的页面」共用**（`AGENTS.md` 前端节第 6 条）：活动/活动管理、
   *    名册/团员管理 都是高危对。⚠️ 「更新公告」用 `megaphone` **不能用 `bell`**
   *    （铃铛归右上角合并入口）。⚠️ **同一个页面在两个视图里各挂一次**（演出↔演出管理、名册）不算冲突。
   * ⚠️ **换图标仍要同步三处**：`vendor/icons/` 的文件 + 那目录的 `manifest.json` + 本表的 key；
   *    对应的 `<svg>` 内层写在下面 `ICON_SVG`（由 `design-samples/_gen_icons_js.py` 生成，别手抄）。
   */
  menu: [
    // ---- 日常视图（全员可见，按角色过滤）----
    { view: '日常', group: '活动与演出', href: 'events.html',        title: '活动',    icon: 'calendar-days',     roles: NAV_ROLES.all },
    { view: '日常', group: '活动与演出', href: 'concerts.html',      title: '演出',    icon: 'ticket',            roles: NAV_ROLES.all },
    { view: '日常', group: '活动与演出', href: 'showcase.html',      title: '专场',    icon: 'star',              roles: NAV_ROLES.all },
    { view: '日常', group: '曲目与录音', href: 'pieces.html',        title: '曲谱',    icon: 'music',             roles: NAV_ROLES.all },
    { view: '日常', group: '曲目与录音', href: 'recordings.html',    title: '录音',    icon: 'mic',               roles: NAV_ROLES.all },
    // 2026-10-17：原「我的请假」并入「我的出勤」（一页三段，`docs/14` §14.6.4）——菜单项随之删除，
    // 请假记录仍在同一页。`calendar-minus` 图标暂未使用（manifest 保留，R11 只查"用到的图标"）。
    { view: '日常', group: '考勤与请假', href: 'my-attendance.html', title: '我的出勤', icon: 'calendar-check',    roles: NAV_ROLES.all },
    { view: '日常', group: '名册',      href: 'members.html',       title: '团员列表', icon: 'users',             roles: NAV_ROLES.all },
    // 「我的」三项 2026-10-06 从**个人栏浮层**搬进菜单（用户拍板"进菜单、删浮层"）：
    // 个人资料仅非荣誉可见（荣誉团员在浮层里本就没有"编辑资料"，免得点进去撞 403）。
    { view: '日常', group: '我的',      href: 'profile.html',       title: '个人资料', icon: 'circle-user-round', roles: NAV_ROLES.member },
    { view: '日常', group: '我的',      href: 'sessions.html',      title: '登录设备', icon: 'laptop',            roles: NAV_ROLES.all },
    { view: '日常', group: '我的',      href: 'updates.html',       title: '更新日志', icon: 'newspaper',         roles: NAV_ROLES.all },
    // 「工具」：节拍器本轮只占位。占位页 = 一句"建设中"。
    { view: '日常', group: '工具',      href: 'metronome.html',     title: '节拍器',  icon: 'metronome',         roles: NAV_ROLES.all },
    { view: '日常', group: '工具',      href: 'tuner.html',         title: '调音器',  icon: 'audio-waveform',    roles: NAV_ROLES.all },
    // ---- 管理视图（仅管理角色；见 `canManage`）----
    { view: '管理', group: '活动与演出', href: 'admin-events.html',   title: '活动管理', icon: 'calendar-cog',    roles: NAV_ROLES.staff },
    { view: '管理', group: '活动与演出', href: 'admin-showcase.html', title: '专场策展', icon: 'images',          roles: NAV_ROLES.admin },
    // ⚠️ 「演出管理」与日常「演出」指向**同一页** `concerts.html`（该页对管理角色就地可编辑）。
    //    两个视图互斥切换，所以同一时刻只画得出其中一条，`a.on` 不会双高亮。
    { view: '管理', group: '活动与演出', href: 'concerts.html',       title: '演出管理', icon: 'ticket',          roles: NAV_ROLES.staff },
    { view: '管理', group: '团员管理',   href: 'members.html',        title: '名册',     icon: 'users',           roles: NAV_ROLES.staff },
    { view: '管理', group: '团员管理',   href: 'admin-members.html',  title: '团员编辑', icon: 'user-cog',        roles: NAV_ROLES.admin },
    { view: '管理', group: '考勤与请假', href: 'approve-leave.html',  title: '请假审批', icon: 'clipboard-check', roles: NAV_ROLES.approver },
    { view: '管理', group: '考勤与请假', href: 'stats.html',          title: '考勤统计', icon: 'chart-column',    roles: NAV_ROLES.staff },
    { view: '管理', group: '系统管理',   href: 'admin-feedback.html', title: '反馈管理', icon: 'inbox',           roles: NAV_ROLES.admin },
    { view: '管理', group: '系统管理',   href: 'admin-notes.html',    title: '更新公告', icon: 'megaphone',       roles: NAV_ROLES.admin },
    { view: '管理', group: '系统管理',   href: 'logs.html',           title: '操作日志', icon: 'scroll-text',     roles: NAV_ROLES.lead },
  ],

  /** 视图的顺序（侧栏顶部的开关就是它们）。⚠️「管理」只对**能看见至少一条管理项**的角色出现。 */
  menuViews: ['日常', '管理'],

  /** 每个视图内的组顺序 + 折叠规则：**第 0 组不折叠**，其余可折叠（桌面端组标题一律不画）。 */
  menuGroups: {
    '日常': ['活动与演出', '曲目与录音', '考勤与请假', '名册', '我的', '工具'],
    '管理': ['活动与演出', '团员管理', '考勤与请假', '系统管理'],
  },

  /* 图标内层 markup（`vendor/icons/` 是唯一权威）。⚠️ **本区块由 `design-samples/_gen_icons_js.py`
     从 `vendor/icons/` 现算生成，别手改** —— 手抄一遍就会漂，本项目**刚因为"手抄哈希表少打一个字符"
     翻过车**（`work_log` 2026-09-30）。改图标的正确姿势：换 `vendor/icons/<file>.svg` + 更新那目录的
     `manifest.json` + 改上面 `menu` 的 key，然后重跑那个脚本。 */
  /* ==ICON_SVG:begin== */
  ICON_SVG: {
    'audio-waveform': '<path d="M2 13a2 2 0 0 0 2-2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0 1 2-2" />',
    'bell': '<path d="M10.268 21a2 2 0 0 0 3.464 0" /><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326" />',
    'calendar-cog': '<path d="m15.228 16.852-.923-.383" /><path d="m15.228 19.148-.923.383" /><path d="M16 2v3" /><path d="m16.47 14.305.382.923" /><path d="m16.852 20.772-.383.924" /><path d="m19.148 15.228.383-.923" /><path d="m19.53 21.696-.382-.924" /><path d="m20.773 16.852.924-.383" /><path d="m20.773 19.148.924.383" /><path d="M21 10.5V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h5.5" /><path d="M3 9h18" /><path d="M8 2v3" /><circle cx="18" cy="18" r="3" />',
    'calendar-days': '<path d="M8 2v3" /><path d="M16 2v3" /><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18" /><path d="M8 13h.01" /><path d="M12 13h.01" /><path d="M16 13h.01" /><path d="M8 17h.01" /><path d="M12 17h.01" /><path d="M16 17h.01" />',
    'calendar-check': '<path d="M8 2v3" /><path d="M16 2v3" /><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18" /><path d="m9 15 2 2 4-4" />',
    'calendar-minus': '<path d="M16 18h6" /><path d="M16 2v3" /><path d="M21 14V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h8.3" /><path d="M3 9h18" /><path d="M8 2v3" />',
    'chart-column': '<path d="M3 3v16a2 2 0 0 0 2 2h16" /><path d="M18 17V9" /><path d="M13 17V5" /><path d="M8 17v-3" />',
    'circle-user-round': '<path d="M17.925 20.056a6 6 0 0 0-11.851.001" /><circle cx="12" cy="11" r="4" /><circle cx="12" cy="12" r="10" />',
    'clipboard-check': '<rect width="8" height="4" x="8" y="2" rx="1" ry="1" /><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" /><path d="m9 14 2 2 4-4" />',
    'images': '<path d="m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16" /><path d="M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2" /><circle cx="13" cy="7" r="1" fill="currentColor" /><rect x="8" y="2" width="14" height="14" rx="2" />',
    'inbox': '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />',
    'laptop': '<path d="M18 5a2 2 0 0 1 2 2v8.526a2 2 0 0 0 .212.897l1.068 2.127a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45l1.068-2.127A2 2 0 0 0 4 15.526V7a2 2 0 0 1 2-2z" /><path d="M20.054 15.987H3.946" />',
    'mail': '<path d="m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7" /><rect x="2" y="4" width="20" height="16" rx="2" />',
    'megaphone': '<path d="M11 6a13 13 0 0 0 8.4-2.8A1 1 0 0 1 21 4v12a1 1 0 0 1-1.6.8A13 13 0 0 0 11 14H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z" /><path d="M6 14a12 12 0 0 0 2.4 7.2 2 2 0 0 0 3.2-2.4A8 8 0 0 1 10 14" /><path d="M8 6v8" />',
    'metronome': '<path d="M12 11.4V9.1" /><path d="m12 17 6.59-6.59" /><path d="m15.05 5.7-.218-.691a3 3 0 0 0-5.663 0L4.418 19.695A1 1 0 0 0 5.37 21h13.253a1 1 0 0 0 .951-1.31L18.45 16.2" /><circle cx="20" cy="9" r="2" />',
    'mic': '<path d="M12 19v3" /><path d="M19 10v2a7 7 0 0 1-14 0v-2" /><rect x="9" y="2" width="6" height="13" rx="3" />',
    'music': '<path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" />',
    'newspaper': '<path d="M15 18h-5" /><path d="M18 14h-8" /><path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-4 0v-9a2 2 0 0 1 2-2h2" /><rect width="8" height="4" x="10" y="6" rx="1" />',
    'scroll-text': '<path d="M15 12h-5" /><path d="M15 8h-5" /><path d="M19 17V5a2 2 0 0 0-2-2H4" /><path d="M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3" />',
    'smartphone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2" /><path d="M12 18h.01" />',
    'star': '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z" />',
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

  /** 各折叠组的开关状态（键 = **组名**，如 `'系统管理'`；不再用下标，否则切视图会错位）。
      ⚠️ **真值只在这里**：DOM 上那个 `.open` 类只是它的投影（`mountDrawer` 的委托监听器写回来）。
      从没手动动过 ⇒ 该键不存在。 */
  grpOpen: {},

  /** 当前显示的视图（`menuViews` 之一）与它是**按哪一页**定的。`paintNav` 里只按当前页推断一次
      （`_viewPage` 一变才重推）；用户点视图开关只改 `_view`、不跳页 ⇒ 同页内保留用户的选择。 */
  _view: null,
  _viewPage: null,
  /** 最近一次拿到的身份：视图开关重画菜单时要用（`setView`）。 */
  _me: null,

  /** 不在主导航里的页面取不到菜单项，标题单独给——别把它们塞进 `menu`（那会多出一个菜单项）。
      ⚠️ 2026-10-06：`profile/sessions/updates/showcase` **已进菜单**，从本表移除（见 `menu`）。 */
  offMenuTitles: {
    'piece-detail.html': '曲谱详情',
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
        <div class="vswitch" role="group" aria-label="视图切换"></div>
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
    // 欢迎行（「欢迎回来/生日快乐」+ 姓名职称）**只在概览页 events.html 显示**，其余页面页头只留 h1。
    // ⚠️ 不能按 renderNav 的 active 判 —— event-detail.html 传的 active 也是 'events.html'，会漏到详情页。
    // welcome/wname/wrole/wlead 节点照建、照从骨架返回（paintNav 会无条件写它们），只是不挂进 DOM：
    // 写到游离节点上无害；playWelcome 找不到 .welcome 时经 place()→finish(0) 安全降级。
    if (location.pathname.endsWith('/events.html')) headL.append(welcome, h1);
    else headL.append(h1);
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
    // ⚠️ 2026-10-02：下拉刷新的**提示条 `.mhint` 已整条删除**（用户报"页面顶部的下拉刷新纯占地方"）
    //    ⇒ 手势保留，只是不再有那行字 / 箭头 / 转圈（拉动时正文自己下移就是反馈）。
    const mbody = document.createElement('div');
    mbody.className = 'mbody';
    // 骨架屏（六 + 「界面的活性」8）：只在手机段显示，由 `BAND.alive()` 撤掉（`endLoading`）。
    const skel = document.createElement('div');
    skel.className = 'skel';
    skel.innerHTML = '<div class="sk tall"></div><div class="sk row"></div>' +
                     '<div class="sk row"></div><div class="sk"></div>';
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
    this.mountPullRefresh({ mbody });

    return {
      layout, active,
      menu: layout.querySelector('.menu'),
      vswitch: layout.querySelector('.vswitch'),
      av: layout.querySelector('.me .ic span'),
      mav: mavSpan,                       // 手机标题栏那颗（欢迎过渡在手机端的落点，见 `playWelcome`）
      mhTitle,
      mhead,                              // 手机那条 sticky 标题栏本身（`mountHeadActions` 手机端把两个入口挂它里面）
      mbody, skel,
      meName: layout.querySelector('.me-name'),
      meSec: layout.querySelector('.me-sec'),
      wname, wrole, wlead, h1,
      greet: headR.querySelector('.greet'),
      headR,
    };
  },

  /**
   * 菜单 HTML（`docs/14` §14.6.4 二/四）：**先按当前视图**（`this._view`，由顶部开关决定）过滤，
   * 再按角色过滤。桌面把这些项平铺成一列；手机端在抽屉里按组折叠，**第 0 组不折叠**、其余可折叠，
   * 而**当前项落在哪一组，那一组就必须展开**（硬条款——否则"你在这一页"的高亮块看不见）。
   * ⚠️ 硬条款优先于"手动折叠"：你在某一组里手动收它、而当前页正是该组，这一页会立刻展回来，
   * 这是**有意**的，别当 bug（要改口径得先动规格）。
   * ⚠️ 分组容器与组标题在**桌面端**被 `band.css` §4 摊平（`display: contents` / `none`），
   * 所以这一份 DOM 桌面与抽屉共用，不做两套。
   */
  menuMarkup(me, active) {
    const view = this._view || this.menuViews[0];
    const allowed = this.menu.filter((it) => it.view === view && it.roles.includes(me.role));
    const link = (it) =>
      `<a href="/${it.href}"${it.href === active ? ' class="on"' : ''}>` +
      `<span class="ic" aria-hidden="true">${this.iconSvg(it.icon)}</span><span class="tx">${it.title}</span></a>`;
    const groups = this.menuGroups[view] || [];
    return groups.map((name, gi) => {
      const items = allowed.filter((it) => it.group === name);
      if (!items.length) return '';               // 这个角色在这个视图的这一组里一项都没有 ⇒ 连组标题都不画
      const fold = gi > 0;                        // 第 0 组不可折叠；其余可折叠
      const open = !fold || !!this.grpOpen[name] || items.some((it) => it.href === active);
      return `<div class="dgrp${open ? ' open' : ''}">` +
        (fold ? `<button class="dgrp-h" type="button" data-g="${name}" aria-expanded="${open}">` +
                `${name}<i aria-hidden="true">▸</i></button>` : '') +
        `<div class="dgrp-b">${items.map(link).join('')}</div></div>`;
    }).join('');
  },

  /** 某页面属于哪个视图（取它在 `menu` 里的条目；同一页挂两处时取**第一条** = 日常）。 */
  viewOf(active) {
    const it = this.menu.find((x) => x.href === active);
    return it ? it.view : this.menuViews[0];
  },

  /** 这个角色在这个视图里**至少能看见一条**吗。⚠️ 用来兜底：某页只挂在管理视图（如 `stats.html`）时，
      普通团员直接打开它 → 若照 `viewOf` 落「管理」，他的菜单会是**空的** ⇒ 得退回他能看见的视图。 */
  viewVisible(view, me) {
    return !!me && this.menu.some((it) => it.view === view && it.roles.includes(me.role));
  },

  /** 这个角色第一个能看见的视图（都看不见就退回 `menuViews[0]`）。 */
  firstVisibleView(me) {
    return this.menuViews.find((v) => this.viewVisible(v, me)) || this.menuViews[0];
  },

  /** 这个角色能不能看见「管理」视图（⇒ 才画顶部开关）。 */
  canManage(me) {
    return this.viewVisible('管理', me);
  },

  /** 画侧栏顶部的视图开关（只有能看见管理视图的角色才画）。开关状态只存 `this._view` 一处。 */
  paintViewSwitch(s, me) {
    if (!s.vswitch) return;
    if (!this.canManage(me)) {                 // 看不见管理视图 ⇒ 连开关都不画（空壳用 [hidden] 藏掉）
      s.vswitch.hidden = true;
      s.vswitch.textContent = '';
      return;
    }
    s.vswitch.hidden = false;
    s.vswitch.textContent = '';
    for (const v of this.menuViews) {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.v = v;
      b.textContent = v;
      b.setAttribute('aria-pressed', String(v === this._view));
      if (v === this._view) b.className = 'on';
      s.vswitch.appendChild(b);
    }
  },

  /** 点开关切换视图：只重画菜单与开关，**不跳页**（`_viewPage` 不动 ⇒ 同页内保留用户的选择）。 */
  setView(v) {
    if (!this.skeleton || !this.menuViews.includes(v) || v === this._view) return;
    this._view = v;
    this.paintNav(this.skeleton, this._me, this._viewPage);
  },

  /**
   * ②/③ 幂等填充：菜单（按角色过滤）、头像、姓名·声部、欢迎行职称、页标题、右上角操作区。
   * 传 `null`（还没拿到身份）就把这些**留空**——不编数据；拿到接口结果后再调一次即纠正。
   */
  paintNav(s, me, active) {
    // 视图：按当前页推断**一次**（`_viewPage` 变了才重推）。用户点开关改的是 `_view`、不跳页（见 `setView`），
    // 所以同页内再 paintNav（筛选器 reload）不会把用户选的视图顶掉。
    if (this._viewPage !== active) {
      this._viewPage = active;
      this._view = this.viewOf(active);
    }
    // 拿不到身份时先不判定；拿到身份后若这个视图他**看不到**（例：普通团员直接开 `stats.html`），
    // 退回他能看见的视图——否则菜单会空。只在需要时改，不覆盖用户在开关上做的选择。
    if (me && !this.viewVisible(this._view, me)) {
      this._view = this.firstVisibleView(me);
    }
    this._me = me;
    // 视图开关（只有能看见管理视图的角色才画；没身份时留空——不编数据）
    this.paintViewSwitch(s, me);
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
    const activeItem = this.menu.find((it) => it.href === active);
    const title = activeItem ? activeItem.title
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
    const vs = layout.querySelector('.vswitch');
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
    // 视图开关：委托在容器上（`paintViewSwitch` 每次会重建里面的按钮，逐一绑会随 DOM 一起没）
    if (vs) {
      vs.addEventListener('click', (e) => {
        const b = e.target && e.target.closest ? e.target.closest('button[data-v]') : null;
        if (b) this.setView(b.dataset.v);
      });
    }
    menu.addEventListener('click', (e) => {
      const h = e.target && e.target.closest ? e.target.closest('.dgrp-h') : null;
      if (!h) return;
      e.preventDefault();
      const grp = h.parentElement;
      const open = !grp.classList.contains('open');
      // 状态只存一处（`grpOpen`，键 = **组名**），DOM 上的 `.open` 类是它的投影 —— 别在两处各写一遍
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
      ⚠️ 2026-10-02：**提示条 `.mhint` 已整条删除**（用户嫌占地方）⇒ 这里只剩"推正文 + 松手重新取数"，
      拉动时正文下移就是唯一的反馈；`ptr-load` 那个只服务转圈的类一并撤了。
      ⚠️ 触摸必须走 `touchmove` + `{ passive: false }`，而且**只有确认在下拉才 `preventDefault`**
      （别抢正常滚动）；Pointer Events 做不到这件事。
      ⚠️ 鼠标分支只为**窄窗口下的验收**（鼠标拖不触发原生滚动，没有要压的东西）；
      整段由 `isNarrow()`（**只认窄屏**，`#114`）圈住 —— 见 `allowed()`。 */
  mountPullRefresh(s) {
    if (!s || !s.mbody || !this.refresher()) return;   // 没有刷新器就别给这个手势（空承诺）
    const THRESHOLD = 64, DECAY = .5, CAP = 96;
    const root = document.documentElement;
    const body = s.mbody;
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
      root.classList.remove('ptr-drag');
      body.style.transform = '';
    };
    const end = () => {
      if (!dragging) return;
      dragging = false;
      if (dy < THRESHOLD) { reset(); return; }
      busy = true;
      body.style.transform = 'translateY(' + THRESHOLD + 'px)';
      const done = () => {                 // 收尾：归位（别静默弹回）
        body.style.transform = '';
        busy = false;
        setTimeout(() => { root.classList.remove('ptr-drag'); }, 340);
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
    //    （名册的状态 / 声部、曲谱库的状态、活动的类型、录音的范围 —— **四处全是这个写法**）。
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
  }});
