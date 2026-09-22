/* ============================================================
   band-nav.js —— 深色侧栏的**按键收起/展开**
   决策记录（docs/14 §14.6）：导航 = 深色侧栏；收起方式 = **按键**，
   不用"鼠标靠近自动弹出"（有实测代价：NN/g 测出隐藏导航在桌面端被漏掉的
   几率几乎翻倍；Vivaldi 7.9 上线同类功能后踩了"边缘空隙""与滚动条打架"）。

   ⚠️ 状态类放在 **<html>** 上，不是 <body>：
   本文件在 <head> 里加载，那时 **document.body 还是 null**，
   碰它就会抛 `Cannot read properties of null`，把整个 IIFE 打断，
   `window.bandNavInit` 永远不会被赋值 → 把手点了没反应、侧栏出不来。
   <html>（documentElement）在 <head> 阶段就已存在，所以状态放它身上既不会崩，
   也能在首次绘制前就把状态应用好（不闪）。
   ============================================================ */
'use strict';

(function () {
  const KEY = 'band_nav_collapsed';
  const root = document.documentElement;          // <head> 阶段就存在
  const collapsed = () => root.classList.contains('nav-collapsed');

  function setCollapsed(v) {
    root.classList.toggle('nav-collapsed', v);
    try { localStorage.setItem(KEY, v ? '1' : '0'); } catch (e) { /* 无痕模式忽略 */ }
    const btn = document.getElementById('navToggle');
    if (btn) {
      btn.setAttribute('aria-expanded', String(!v));
      btn.setAttribute('aria-label', v ? '展开导航' : '收起导航');
    }
  }

  // 解析阶段就把上次的选择应用上（此时 <html> 已在，且首次绘制尚未发生）
  try {
    const saved = localStorage.getItem(KEY);
    if (saved !== null) root.classList.toggle('nav-collapsed', saved !== '0');
  } catch (e) { /* 忽略 */ }

  /* 侧栏骨架由 app.js 异步注入，所以这里暴露 initNav 给它调用；
     静态页面（骨架已在 HTML 里）则靠下面的 DOMContentLoaded 自动绑。 */
  function initNav() {
    const btn = document.getElementById('navToggle');
    if (!btn || btn.dataset.bound === '1') return;
    btn.dataset.bound = '1';

    setCollapsed(collapsed());                    // 同步一次 aria
    btn.addEventListener('click', () => setCollapsed(!collapsed()));

    // 把手上的文字 = 当前所在的页
    const cur = document.querySelector('.sidebar .menu a.on');
    const label = btn.querySelector('.txt');
    if (cur && label) label.textContent = cur.textContent.trim();
  }
  window.bandNavInit = initNav;

  document.addEventListener('DOMContentLoaded', () => {
    setCollapsed(collapsed());
    initNav();
  });

  // Esc 收起
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !collapsed()) setCollapsed(true);
  });
})();
