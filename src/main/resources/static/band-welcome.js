'use strict';

Object.assign(BAND, {

  /** 欢迎过渡（docs/14 §14.6.4）：登录成功那次，黑幕上头像与欢迎行飞往页面真实位置。
     黑幕的显示由 band-nav.js 首帧前加的 wel-armed 类控制；这里读掉并清掉标记，
     落点全部运行时测量（getBoundingClientRect），手机端顶栏形态也自动对上。 */
  playWelcome() {
    // ⚠️ 执行器写成 **async** 只为下面那一句 `await document.fonts.load(...)`（字体就绪再量矩形）：
    //    它抛出的异常全被 try/catch 吞掉，所以不会变成"没人接的 rejection"。
    return new Promise(async (resolve) => {
      const wel = document.getElementById('wel');
      const root = document.documentElement;
      let hadFlag = false;
      try { hadFlag = sessionStorage.getItem('band_welcome') === '1'; } catch (e) { /* 忽略 */ }
      try { sessionStorage.removeItem('band_welcome'); } catch (e) { /* 忽略 */ }
      const disarm = () => this.disarmWelcome();
      const reduced = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      // 目标页没有黑幕 / 没有标记 / 用户要求减少动效 → 直接放行，绝不卡在黑屏上
      if (!wel || !hadFlag || !root.classList.contains('wel-armed') || reduced) {
        disarm();
        resolve();
        return;
      }

      // ⚠️ **2026-09-28 删掉了原先这里的一段"等字体就绪再量矩形"**（原代码是
      //    `await Promise.race([document.fonts.load('600 33px BandName'), 350ms 上限])`）。
      //    它存在的**唯一**理由是：姓名那时用的是**下载来的子集字体**，字形没落地时量到的是
      //    回退黑体的字宽 ⇒ FLIP 落点会偏（且只在无缓存的首访那一次明显）。
      //    现在姓名改用**系统楷体**（见 `band.css` 的 `.wname` 与那里的留痕）——系统字体**即时就绪**，
      //    没有"等"可言，留着就只是白等（还可能白等满 350ms）⇒ 整段删除。
      //    ⭐ 落点仍然是**运行时量出来的**（换宽度 / 手机端自动对上，绝不写死坐标），这点没变。

      const av = wel.querySelector('.wel-av');
      const tx = wel.querySelector('.wel-tx');
      const wname = tx.querySelector('.wname');
      const me = this.me;
      this.paintAvatar(av, me);   // 落点那份也要跟着头像走（第三处，最容易漏）
      // ⚠️ 生日当天（`#115`）**遮罩里那行也要一起换**：规格要求遮罩里那行与页面上那行**逐字一致**
      //    （同号 33px、飞行时 `scale ≈ 1`——它们本来就是"同一行在飞"）。只换页面那行的话，
      //    飞过去的一瞬间会从「欢迎回来」跳成「生日快乐」，而那正好是用户最看得见的一刻。
      //    ⚠️ 这行的开头几个字是**各页 HTML 里写死的文本节点**（24 个页面都有），所以只能改节点值。
      const mine = (this._bd || []).some((b) => b.id === me.id);
      const role = this.roleLabel(me.role) || me.role;
      tx.childNodes[0].nodeValue = mine ? '生日快乐，' : '欢迎回来，';
      wname.textContent = me.name;
      tx.append(mine ? `（${role}）` : role);   // 名字与职称之间不用标点（docs/14 §14.6.4）

      const timers = [];
      const after = (ms, fn) => timers.push(setTimeout(fn, ms));
      const clearTimers = () => { timers.forEach(clearTimeout); timers.length = 0; };
      let playing = true;

      // 落点：**运行时量出来**（换宽度、手机端都会自动对上，绝不写死坐标）
      // ⚠️ 头像那颗**按门控分支**（2026-09-28 连带修的真问题）：手机端侧栏是**默认关着的抽屉**，
      //    抽屉里那颗头像在**屏幕之外**（`x ≈ -320`），照它飞等于把头像飞出屏幕——而现有守卫
      //    只在"元素不存在"时早退，**抓不到"元素存在、只是在屏外"**。
      //    ⇒ 手机 / 触屏改取**标题栏那颗**（一直可见，本来就是"开抽屉"的开关，落点/开关/身份合成一个元素）。
      // ⚠️ 欢迎行两套形态**一样**（`.welcome` 在 `.head` 里、手机端也一直可见）：别把它藏了。
      const targets = () => ({
        av: document.querySelector(this.isMobile() ? '.mhead .mav-toggle span' : '.rail .me .ic span'),
        tx: document.querySelector('.welcome'),
      });
      const place = () => {
        const t = targets();
        if (!t.av || !t.tx) { finish(0); return; }
        const ra = t.av.getBoundingClientRect(), ca = av.getBoundingClientRect();
        const rt = t.tx.getBoundingClientRect(), ct = tx.getBoundingClientRect();
        // 头像是圆：以中心对齐 + 按宽度缩放；文字以左上角对齐 + 按宽度缩放
        av.style.transform = 'translate(' + (ra.left + ra.width / 2 - ca.left - ca.width / 2) + 'px,'
          + (ra.top + ra.height / 2 - ca.top - ca.height / 2) + 'px)'
          + ' scale(' + (ra.width / ca.width) + ')';
        tx.style.transform = 'translate(' + (rt.left - ct.left) + 'px,' + (rt.top - ct.top) + 'px)'
          + ' scale(' + (rt.width / ct.width) + ')';
      };
      const finish = (delay) => {
        after(delay, () => {
          // 先让遮罩里的头像/文字淡掉，**再**淡黑底：否则会和页面自己那一份重叠一瞬（双影）
          av.style.transition = tx.style.transition = 'opacity 240ms linear';   // ⑥-1 先淡掉头像/文字 0.24s
          av.style.opacity = tx.style.opacity = '0';
          after(240, () => {
            wel.style.transition = 'opacity 400ms linear';                     // ⑥-2 再淡黑底 0.40s
            wel.style.opacity = '0';
            after(400, () => {
              disarm();
              playing = false;
              document.removeEventListener('keydown', onKey);
              // 收尾：给欢迎行加 `.draw-on` ⇒ 姓名下面那道笔划"**划出**"（docs/14 §14.6.4；`交接单` `#53`）。
              // ⚠️ **只在登录那一次**：本函数只在带 `band_welcome` 标记时才走到这里，
              //    其它页面加载走的是上面那条早退分支 ⇒ 笔划静态可见、**不重播**（每页都播就退化成纯装饰）。
              // ⚠️ reduced-motion 下根本到不了这里（早退分支已含 reduced）⇒ 笔划全程不动；
              //    `band.css` 的 reduced-motion 块里还有一条 `animation: none` 兜底。
              const w = document.querySelector('.welcome');
              if (w) w.classList.add('draw-on');
              resolve();
            });
          });
        });
      };
      const reset = () => {
        av.style.transition = tx.style.transition = 'none';
        av.style.transform = tx.style.transform = 'none';
        av.style.opacity = tx.style.opacity = '0';
        wel.style.transition = 'none';
        wel.style.opacity = '1';
      };

      const skip = () => {
        if (!playing) return;
        clearTimers();
        av.style.transition = tx.style.transition = 'none';
        tx.style.opacity = '1';
        place();
        finish(60);
      };
      function onKey() { skip(); }
      wel.addEventListener('click', skip);
      document.addEventListener('keydown', onKey);   // 点任意处 / 任意键可跳过

      reset();
      // ⚠️ 下面这几个毫秒值 = `docs/14` §14.6.4「欢迎过渡」**六步表**那一行（2026-09-30 整体延长）：
      //    用户原话「登录界面登录到主页面的**过场动画太短了**，我觉得可以延长一下」⇒ 合计约 **2.7s**
      //    （原来是 0.24/0.19/0.27/0.62/0.16/0.26，合计约 1.8s）。
      //    ⚠️⚠️ **延长的是"每一段"，不是只把位移拉长**——只拉位移会变成"停得太短、飞得太久"的**慢动作**。
      //    ⇒ **六个一起改**，改完对着那张表逐段核一遍。⚠️ 这条的来历有点特别：规格只在表里改了值、
      //    **没开交接单条目、也没挂 🚧**，所以按"交接单剩余项"分批时**整条漏掉了**（见 `交接单` §三）。
      requestAnimationFrame(() => {
        av.style.transition = tx.style.transition = 'opacity 380ms linear';   // ④-1 头像淡入 0.38s
        av.style.opacity = '1';
        after(300, () => { tx.style.opacity = '1'; });                        // ④-2 +0.30s 欢迎行淡入
        // ⚠️⚠️ 这个数是**前三段累加**得来的，不是规格表里那个"0.50"：那张六步表是**累加**口径
        //    （0.38 头像淡入 → +0.30 欢迎行淡入 → **停 0.50** → 飞 0.92 → 0.24 → 0.40，合计 2.74s）。
        //    照"0.50"直接写会变成"从 t=0 起 500ms 就飞" ⇒ 整条只有 2.08s（旧值 1.81s，只差 270ms，
        //    而规格要的是差 900ms）——**是探针量墙钟把这个错误当场抓出来的**（反例验算里旧值 1999ms，
        //    我的第一版 2192ms，两个窗口挨着，一眼看出没做够）。
        after(1180, () => {                           // ⑤ 停一下，再飞（= 0.38 + 0.30 + 0.50）
          av.style.transition = tx.style.transition =
            'transform 920ms cubic-bezier(.44, 0, .2, 1), opacity 200ms linear';   // ⑤ 飞行 0.92s
          place();
          finish(940);
        });
      });
    });
  },

  /**
   * 猫可以**晚一点**来（`docs/14` §14.6.4）：`showcase.html` 的入场编排（约 6s）与全屏看图期间
   * 必须把它按住——猫的 `z-index` 是 `2147483647`、`position:fixed`，**抬层级打不过它**。
   * `catHold = true` 期间 `loadCat()` 只记一笔不加载；`releaseCat()` 把它放出来（幂等）。
   * ⚠️ 用的人要在**每一条**出口都调 `releaseCat()`（收尾 / 跳过 / 降级），漏一条那条路上就永远没有猫。
   */
  catHold: false,
  catPending: false,
  releaseCat() {
    this.catHold = false;
    if (!this.catPending) return;
    this.catPending = false;
    this.loadCat();
  },

  /** 萌宠 oneko（docs/14 §14.6.4「页面萌宠」）：登录后所有页、仅桌面。
     matchMedia 不满足时**连脚本都不插**（不是加载了再藏）——触屏没有光标可追。 */
  loadCat() {
    if (this.catHold) { this.catPending = true; return; }
    if (!window.matchMedia
        || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    if (document.getElementById('oneko')) return;
    const s = document.createElement('script');
    s.src = '/vendor/oneko/oneko.js';
    s.dataset.cat = '/vendor/oneko/oneko.gif';
    document.body.appendChild(s);
  }});
