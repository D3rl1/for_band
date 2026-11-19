/* ============================================================
   band-nav.js —— 导航的前置状态处理（docs/14 §14.6.4，2026-09-23 定稿）
   导航本身是**纯 CSS**：常驻 92px 图标轨 + 悬停/Tab 展开到 320px，
   DOM 由 app.js 的 renderNav 注入。本文件只剩一件事：
   清掉旧「按键收起」方案残留的 localStorage 键（band_nav_collapsed）。
   旧值若不清，老用户的首访表现会被它带偏；removeItem 一次即可。

   决策变更留痕：旧方案"默认收起 + 常驻把手按键展开"（当时明确拒绝
   悬停自动弹出，引 NN/g 隐藏导航漏检率翻倍、Vivaldi 7.9 的"边缘空隙/
   与滚动条打架"）被 2026-09-23 定稿推翻，改为悬停展开。当年那两个坑
   属于"浮层式侧栏"；现在的"预留位"方案不遮正文、不与滚动条打架，
   且配了两条缓解：@media (hover:hover) and (pointer:fine) 才自动展开、
   :focus-within 保证键盘可达。
   老规矩仍然有效：状态类放 <html> 不放 <body>——本文件在 <head> 加载，
   那时 document.body 还是 null，碰它会抛错打断整个脚本。
   ============================================================ */
'use strict';

(function () {
  try { localStorage.removeItem('band_nav_collapsed'); } catch (e) { /* 无痕模式忽略 */ }

  // 欢迎过渡的开关必须**首帧前**就位：本文件在 <head> 里跑，此时什么都还没画。
  // 有 band_welcome 标记 → 给 <html> 加 wel-armed，黑幕从第一帧就是黑的（不闪白）；
  // 没有 → 黑幕保持 display:none，直接打开目标页也不会闪黑（docs/14 §14.6.4）。
  try {
    if (sessionStorage.getItem('band_welcome') === '1') {
      document.documentElement.classList.add('wel-armed');
      // 登录那一次**不参与**跨页过渡（band.css: html.no-xvt → view-transition-name: none）：
      // 这一页首帧就是全黑幕，再叠一层文档级过渡必然与欢迎过渡互相盖住。
      // 本文件在 <head> 里同步跑，正好赶在这次过渡建立之前。
      document.documentElement.classList.add('no-xvt');
    }
  } catch (e) { /* 隐私模式：跳过过渡 */ }

  // 点过菜单项后"保持展开"（docs/14 §14.6.4）：类同样必须**首帧前**挂上，
  // 否则会先看到收起态再"拉开"一次。
  // 标记是**给下一页用一次**的意图（上一页在菜单项 mousedown 时写下一个时间戳）——
  // 读到就**立刻消费掉**：这样"从正文里点的普通链接"跳过来不会又展开，只有"点过菜单项"那一次会。
  // 用与 CSS 同一个门控圈住：手机/窄屏根本没有展开/收起这回事，别把类挂上去。
  try {
    const fine = window.matchMedia
      && window.matchMedia('(hover: hover) and (pointer: fine)').matches;
    const armedAt = Number(sessionStorage.getItem('band_nav_keep')) || 0;
    if (fine && Date.now() - armedAt < 5000) {
      document.documentElement.classList.add('rail-keep');
    }
    if (armedAt) {
      sessionStorage.removeItem('band_nav_keep');   // 消费掉：旧页不清，只有新页清
    }
  } catch (e) { /* 隐私模式：跳过 */ }

  // 配色 / 夜间（docs/14 §14.6.2b / §14.6.2c，2026-11-04）：**必须在首绘前**把
  // data-theme 与已算好的主题令牌打到 <html>——本文件在 <head> 里同步跑，正好赶在
  // 第一次绘制之前 ⇒ 全站换色跨页不闪。
  //
  // 主题配置 v2 的令牌缓存与配置**写在同一个键**里（`band.theme.settings.v2`）：
  // 一次写入、一次校验，不会出现"配置已换、缓存还是旧配色"的半套状态。
  // 其他页面不需要加载 87 KB 的颜色引擎，只把缓存里的 CSS 变量同步写上去。
  //
  // 旧键 `band.palette` 只在**没有 v2 配置**时才读（迁移前的老用户）；
  // 一旦应用过新主题，v2 就是唯一真值，旧键保留不删，但不再参与渲染。
  // 只认白名单里的值；缺失 / 非法一律退回 :root 的默认（墨·青 / 浅色）。
  try {
    const dark = localStorage.getItem('band.theme') === 'dark';
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';

    const raw = localStorage.getItem('band.theme.settings.v2');
    if (raw) {
      // v2 存在就**不走旧配色**：即使内容损坏，也回默认，而不是悄悄退回另一套旧偏好。
      const cfg = JSON.parse(raw);
      // 迁移（rev < 2）：旧引擎把「纯墨」的种子色度强制拉到 32，缓存里是蓝色主色。
      // 本文件没有颜色引擎、重算不了，于是退回 CSS 的 legacy ink 调色板（就是原来的纯墨），
      // 用户刷新即恢复墨色，不必回主题页重新「应用」。用户下次应用后会写入 rev 2，走新引擎。
      const staleInk = cfg && cfg.version === 2 && !(cfg.rev >= 2) && cfg.preset === 'ink';
      const map = cfg && cfg.version === 2 && !staleInk && cfg.tokens && cfg.tokens[dark ? 'dark' : 'light'];
      if (staleInk) {
        document.documentElement.dataset.palette = 'ink';
      } else if (map && typeof map === 'object') {
        const style = document.documentElement.style;
        Object.keys(map).forEach(function (k) {
          if (typeof map[k] !== 'string') return;
          // ⚠️ 夜间 hero **不读缓存**：旧配置里 --hero-1/2 是固定 neutral tone（曾等于页面底
          // 的 tone 6 ⇒ 活动页大卡与背景同色）。夜间改由 band.css §1b 的 accent 掺色公式重算，
          // 这样**已应用过旧主题的用户不用重新点「应用」**也能拿到分离的卡面。
          if (dark && (k === '--hero-1' || k === '--hero-2')) return;
          // 只许写令牌变量；缓存被手改成别的属性名也不生效。
          if (/^--(md-sys-color-|bg$|surface$|surface-2$|line$|line-strong$|ink$|ink-2$|ink-3$|accent$|on-accent$|accent-ink$|accent-text$|accent-weak$|accent-line$|accent-dk$|danger$|danger-weak$|hero-1$|hero-2$|edge$|shellmid$|on-shell$|sh-[123]$)/.test(k)) {
            style.setProperty(k, map[k]);
          }
        });
      }
    } else {
      const pal = localStorage.getItem('band.palette');
      if (pal && ['jade', 'teal', 'violet', 'olive', 'clay', 'ink'].indexOf(pal) >= 0) {
        document.documentElement.dataset.palette = pal;
      }
    }
  } catch (e) { /* 隐私模式 / 配置损坏：跳过，退回默认配色 */ }
})();
