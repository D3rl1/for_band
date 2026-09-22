/* ============================================================
   band-nav.js —— 深色侧栏的**按键收起/展开**
   决策记录（docs/14 §14.6）：导航形态 = 深色侧栏；收起方式 = **按键**，
   不用"鼠标靠近自动弹出"（那种做法有实测代价：NN/g 测出隐藏导航在桌面端
   被漏掉的几率几乎翻倍；Vivaldi 7.9 上线同类功能后踩了"边缘空隙"和
   "与滚动条打架"两个坑）。

   所以：**常驻把手**（任何时候看得见、点得到、Tab 得到）+ 点击切换 + 记住选择。
   ============================================================ */
'use strict';

(function () {
  const KEY = 'band_nav_collapsed';
  const body = document.body;

  function applySaved() {
    // 解析时立即把上次的选择打到 body 上，避免侧栏闪一下（app.js 异步注入骨架前就生效）
    let saved = null;
    try { saved = localStorage.getItem(KEY); } catch (e) { /* 无痕模式忽略 */ }
    body.classList.toggle('nav-collapsed', saved !== '0');
  }
  applySaved();

  const collapsed = () => body.classList.contains('nav-collapsed');

  // 侧栏骨架可能是 app.js 异步注入的：暴露 initNav 供其调用；静态页面自动绑定
  function initNav() {
    const btn = document.getElementById('navToggle');
    if (!btn || btn.dataset.bound === '1') return;
    btn.dataset.bound = '1';

    function setCollapsed(v) {
      body.classList.toggle('nav-collapsed', v);
      try { localStorage.setItem(KEY, v ? '1' : '0'); } catch (e) { /* 无痕模式忽略 */ }
      btn.setAttribute('aria-expanded', String(!v));
      btn.setAttribute('aria-label', v ? '展开导航' : '收起导航');
    }
    setCollapsed(collapsed());

    btn.addEventListener('click', () => setCollapsed(!collapsed()));

    // 把手上的文字 = 当前所在的页
    const cur = document.querySelector('.sidebar .menu a.on');
    const label = btn.querySelector('.txt');
    if (cur && label) label.textContent = cur.textContent.trim();
  }
  window.bandNavInit = initNav;

  // Esc 收起
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !collapsed()) {
      body.classList.add('nav-collapsed');
      try { localStorage.setItem(KEY, '1'); } catch (e2) { /* 忽略 */ }
    }
  });
})();
