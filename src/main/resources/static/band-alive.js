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
      // `.evrow` = 活动页精装修的卡片行（docs/14 §14.6.4「活动页」，2026-10-11）——
      // 与表格行同一套「列表逐行入场」；起点/终态类由下面 `.ar` / `.in` 分支统一处理。
      // `.cn-prog li` = 演出详情改成有序列表后的曲目行（docs/14 §14.6.4「演出页」，2026-10-11）——
      // 原来是 `.tb tbody tr` 拿到的，换成 `<ol>` 后要显式补上（参演名单药丸不做逐条入场，横向换行没意义）。
      // `.pc-row` = 曲谱库的卡片行（docs/14 §14.6.4「曲谱页」，2026-10-15）——同 `.evrow`：原来是 `table.tb` 里的行。
      // `.at-gcard / .at-lr / .at-srow / .at-pc` = 考勤与请假两页（docs/14 §14.6.4「我的出勤 / 请假审批」，2026-10-17）——
      // 统计卡、请假记录行、逐场明细行、待审卡共用「逐行入场」；起点 / 终态类由 §16 的 `.at-*.ar` / `.at-*.in` 处理。
      // `.ed-arow` = 活动详情精装后的考勤行（docs/14 §14.6.4「活动详情」，2026-10-18）——与 `.evrow` 同理，
      // 原来是 `table.tb` 的行，换成 `.ed-table` 后要显式补上；起点 / 终态样式在 §17.2。
      const rows = [...root.querySelectorAll('.tb tbody tr, .evrow, .cn-prog li, .pc-row, .at-gcard, .at-lr, .at-srow, .at-pc, .ed-arow')];
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

