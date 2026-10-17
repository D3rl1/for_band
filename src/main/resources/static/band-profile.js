'use strict';

Object.assign(BAND, {

  mountProfile(layout, me) {
    const meBtn = layout.querySelector('.me');
    // 2026-10-06：**「编辑资料」「登录设备」已搬进侧栏菜单**（日常「我的」组）⇒ 浮层里删掉这两项
    //（`updates.html` 本就不在浮层），避免逐字重复。这里只剩账号动作：改密码 / 声音 / 退出。
    // 「声音 开/关」2026-09-25 从侧栏底栏搬到这里（docs/14 §14.6.4「底部只留品牌一行」）。
    const soundOn = localStorage.getItem('band_sound') !== '0';
    const acts = ['<a href="#" id="p-pw">改密码</a>',
                  `<a href="#" id="p-sound">声音 ${soundOn ? '开' : '关'}</a>`,
                  '<a href="#" id="p-logout">退出</a>'].join(' · ');
    const panel = document.createElement('aside');
    panel.className = 'profile';
    panel.id = 'profile';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', '个人栏');
    panel.innerHTML = `
      <div class="p-id">
        <span class="p-av"></span>
        <div class="p-who">
          <div class="p-name"></div>
          <div class="p-tags"></div>
        </div>
      </div>
      <div class="p-nums"></div>
      <div class="p-flow">
        <div class="p-flow-head">
          <span class="lab">最近</span>
          <a href="/my-attendance.html">看全部</a>
        </div>
        <div class="p-flow-list"></div>
      </div>
      <div class="p-acts">${acts}</div>
      <form class="p-pw-form" id="p-pw-form" hidden>
        <input type="password" id="p-old" placeholder="原密码" autocomplete="current-password" required>
        <input type="password" id="p-new" placeholder="新密码（至少 8 位）" autocomplete="new-password" minlength="8" required>
        <button class="go go-sm" type="submit">改密码</button>
      </form>`;
    const backdrop = document.createElement('div');
    backdrop.className = 'profile-backdrop';
    // 手机端的第二层要能**回到抽屉**：给一个「←」（`band.css` 里 `display:none`，只在手机段出现）。
    // ⚠️ 样张（`mobile-mock.html` 的 `.p2head .back`）里有它，而规格正文只写了"Esc / 点面板外关闭"——
    //    手机上既没有 Esc、第二层又把 320px 占满，没有它就等于"进去出不来"（已记 `交接单` §三）。
    const back = document.createElement('button');
    back.className = 'p-back';
    back.type = 'button';
    back.setAttribute('aria-label', '返回菜单');
    back.textContent = '←';
    panel.prepend(back);
    document.body.append(backdrop, panel);

    // 用户数据一律 textContent（防注入）
    this.paintAvatar(panel.querySelector('.p-av'), me);
    panel.querySelector('.p-name').textContent = me.name;
    panel.querySelector('.p-tags').textContent = this.identityTags(me);

    const setOpen = (open) => {
      panel.classList.toggle('open', open);
      backdrop.classList.toggle('open', open);
      meBtn.setAttribute('aria-expanded', String(open));
      if (open) this.loadProfileData(panel, me);
    };
    meBtn.addEventListener('click', (e) => {
      e.preventDefault();
      setOpen(!panel.classList.contains('open'));
    });
    backdrop.addEventListener('click', () => setOpen(false));
    back.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') setOpen(false);
    });

    panel.querySelector('#p-logout').addEventListener('click', async (e) => {
      e.preventDefault();
      await this.api('/api/auth/logout', { method: 'POST' });
      this.clearToken();
      location.href = '/login.html';
    });
    panel.querySelector('#p-pw').addEventListener('click', (e) => {
      e.preventDefault();
      panel.querySelector('#p-pw-form').hidden = !panel.querySelector('#p-pw-form').hidden;
    });
    // 「声音 开/关」：**监听器在这边重绑**（原来绑在 `mountSkeleton` 建的那个节点上，
    // 2026-09-25 那个节点已随底部去重删掉——留着就是一个指向空节点的悬空监听器）。
    // ⚠️ 文案按**当时**的 localStorage 重算：`mountSkeleton` 里那份是首帧时读的，可能已经过期。
    // 用 `currentTarget` 而不是 `target`：文案挂在这个锚点自身，但这样写对子节点也安全。
    panel.querySelector('#p-sound').addEventListener('click', (e) => {
      e.preventDefault();
      const nowOn = localStorage.getItem('band_sound') !== '0';
      localStorage.setItem('band_sound', nowOn ? '0' : '1');
      e.currentTarget.textContent = '声音 ' + (nowOn ? '关' : '开');
    });
    panel.querySelector('#p-pw-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      await this.must('/api/auth/password', {
        method: 'POST',
        body: {
          oldPassword: panel.querySelector('#p-old').value,
          newPassword: panel.querySelector('#p-new').value,
        },
      });
      // 改密码成功 → 全部 token 失效（含当前），回登录页重新登
      this.toast('密码已改，请用新密码重新登录', 'success');
      setTimeout(() => location.href = '/login.html', 900);
    });
  },

  /** 数字与最近流水按需拉取（每次展开刷新一次；失败静默——面板其余部分照常用）。 */
  async loadProfileData(panel, me) {
    const nums = panel.querySelector('.p-nums');
    const flow = panel.querySelector('.p-flow-list');
    try {
      const s = await this.api(`/api/members/${me.id}/attendance-summary`);
      if (s.code === 0) {
        const k = s.data;
        // 不参与考勤的人（指挥 / 荣誉团员，docs/13 不变式 14）分母天然为 0：照常显示数字，
        // 出勤率那一格显示「—」。空状态那句预告是留给"还没签到过的新团员"的，别用在他们身上。
        const noAttend = ['CONDUCTOR', 'HONORARY'].includes(me.role);
        const empty = !noAttend && k.rehearsal.expected === 0 && k.sectionClass.expected === 0;
        nums.innerHTML = empty
          ? '<p class="p-empty">完成第一次签到后，这里会出现你的五声点阵</p>'
          : `<div class="p-num"><b data-count>${this.ratePct(k.rehearsal.rate, k.rehearsal.expected)}</b><span>排练出勤 · 请假 ${k.rehearsal.leave}</span></div>
             <div class="p-num"><b data-count>${this.ratePct(k.sectionClass.rate, k.sectionClass.expected)}</b><span>声部课出勤 · 请假 ${k.sectionClass.leave}</span></div>
             <div class="p-num"><b data-count>${k.rehearsal.present + k.sectionClass.present}</b><span>参加场次</span></div>
             <div class="p-num"><b>${me.joinedAt ? this.daysSince(me.joinedAt) : '—'}</b><span>入团天数</span></div>
             <div class="p-num"><b id="p-rec-count">—</b><span>录音条数</span></div>`;
      }
    } catch (e) { /* 面板数字失败不阻断 */ }
    try {
      // 录音条数（规格标的"期 5 并入"）：用**现成接口**的 total，不为它新增接口；
      // 可见性由服务端收口，所以这里天然只会数到"我听得见的"那些
      const rec = await this.api(`/api/recordings?memberId=${me.id}&size=1`);
      const slot = document.getElementById('p-rec-count');
      if (slot) {
        slot.textContent = rec.code === 0 ? rec.data.total : '—';
      }
    } catch (e) { /* 数字失败不阻断 */ }
    try {
      const r = await this.api('/api/me/recent-activity?limit=5');
      if (r.code === 0) {
        flow.innerHTML = '';
        if (!r.data.length) {
          const p = document.createElement('p');
          p.className = 'p-empty';
          p.textContent = '还没有签到或请假记录';
          flow.appendChild(p);
          return;
        }
        for (const it of r.data) {
          const row = document.createElement('div');
          row.className = 'p-flow-item';
          const when = it.time ? it.time.slice(5, 10) : '';
          row.textContent = `${when}　${it.title || ''}　${it.note || ''}`;
          flow.appendChild(row);
        }
      }
    } catch (e) { /* 流水失败不阻断 */ }
    /* 「界面的活性」：这三个数播一次（上面标了 `data-count`）。
       ⚠️ 「入团天数」属"日期与时间"、录音条数初值是 `—`，按规格 ③ **都不数**，所以没标。
       ⚠️ 键用**常驻容器 `nums`**（面板每次展开都重建 innerHTML，用里面的 `<b>` 当键会每次都重播）。 */
    this.alive(nums);
  }});
