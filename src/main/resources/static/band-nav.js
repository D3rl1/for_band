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

  // 配色 / 夜间（docs/14 §14.6.2b，2026-10-11）：**必须在首绘前**把 data-palette / data-theme
  // 打到 <html>——本文件在 <head> 里同步跑，正好赶在第一次绘制之前 ⇒ 全站换色跨页不闪。
  // 值存 localStorage（活动页的外观控件写入 `band.palette` / `band.theme`）。
  // 只认白名单里的值；缺失/非法一律退回默认（墨·青 / 浅色）——属性不设即走 :root 的默认。
  try {
    const pal = localStorage.getItem('band.palette');
    if (pal && ['jade', 'teal', 'violet', 'olive', 'clay', 'ink'].indexOf(pal) >= 0) {
      document.documentElement.dataset.palette = pal;
    }
    if (localStorage.getItem('band.theme') === 'dark') {
      document.documentElement.dataset.theme = 'dark';
    }
  } catch (e) { /* 隐私模式：跳过，退回默认配色 */ }
})();
