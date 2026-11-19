/* ============================================================
   theme.js —— 主题设置页（docs/14 §14.6.2c / #244）
   ------------------------------------------------------------
   颜色引擎：@material/material-color-utilities@0.4.0，2021 规范。
   种子色 + 深浅 + 对比度（0 / 0.5 / 1）→ SchemeTonalSpot
   → 31 个语义角色 → --md-sys-color-*；应用令牌再从角色派生。
   ⚠️ 本页不用 filter: contrast()，不统一加减亮度，组件里不写死色值。
   ⚠️ 种子色 / 对比度是**草稿**：只更新预览；点「应用到全站」才写配置。
      日夜沿用原按钮，即时生效，不进草稿、不被「取消草稿」撤销。
   ⚠️ 本页不暴露调试钩子；验收走真实 DOM / localStorage / 最终计算样式。
   ============================================================ */
'use strict';

(function () {
  const MCU = window.MCU;
  // ⚠️ 不写 `const BAND = window.BAND`：band-core.js 顶层是 `const BAND`，
  // 它是**全局词法绑定**、不是 window 属性。在 IIFE 里再声明一次会遮蔽成 undefined。
  const SETTINGS_KEY = 'band.theme.settings.v2';

  const PRESETS = {
    jade:   { name: '墨·青', seed: '#1F6F5C' },
    teal:   { name: '墨·松', seed: '#1C6E77' },
    violet: { name: '墨·紫', seed: '#6A4A9C' },
    olive:  { name: '墨·缃', seed: '#5F6A2C' },
    clay:   { name: '墨·赭', seed: '#A45A34' },
    ink:    { name: '纯墨', seed: '#1C1F21' },
  };

  const ROLES = [
    'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer',
    'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
    'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
    'error', 'onError', 'errorContainer', 'onErrorContainer',
    'surface', 'onSurface', 'onSurfaceVariant',
    'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
    'surfaceContainerHigh', 'surfaceContainerHighest', 'surfaceDim', 'surfaceBright',
    'outline', 'outlineVariant',
    'inverseSurface', 'inverseOnSurface', 'inversePrimary', 'shadow',
  ];

  const LEVELS = { standard: 0, medium: 0.5, high: 1 };
  const LEVEL_NAMES = { standard: '标准', medium: '中对比度', high: '高对比度' };

  const $ = (s) => document.querySelector(s);
  const preview = $('#theme-preview');
  const sticky = $('#theme-sticky');
  const stickyLevel = $('#theme-sticky-level');
  const noticeEl = $('#theme-notice');
  const seedsEl = $('#theme-seeds');
  const colorEl = $('#theme-color');
  const hexEl = $('#theme-hex');
  const hexErr = $('#theme-hex-err');
  const segEl = $('#theme-seg');
  const draftText = $('#theme-draft-text');
  const draftDot = $('#theme-dot');
  const applyBtn = $('#theme-apply');
  const resetBtn = $('#theme-reset');
  const discardBtn = $('#theme-discard');
  const rampEl = $('#theme-ramp');

  let theme = document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
  let applied = null;
  let draft = null;
  let storageOK = probeStorage();
  let allowLeave = false;
  const notices = [];

  /* ---------------- 小工具 ---------------- */
  function lsGet(key) {
    try { return localStorage.getItem(key); } catch (e) { storageOK = false; return null; }
  }
  function lsSet(key, value) {
    try { localStorage.setItem(key, value); return true; } catch (e) { storageOK = false; return false; }
  }
  function probeStorage() {
    try {
      const k = '__theme_probe__';
      localStorage.setItem(k, '1');
      localStorage.removeItem(k);
      return true;
    } catch (e) { return false; }
  }
  function hexNormalize(v) {
    let s = String(v == null ? '' : v).trim();
    if (!s) return null;
    if (s[0] !== '#') s = '#' + s;
    if (/^#[0-9a-f]{3}$/i.test(s)) {
      s = '#' + s.slice(1).split('').map((c) => c + c).join('');
    }
    return /^#[0-9a-f]{6}$/i.test(s) ? s.toUpperCase() : null;
  }
  function normalizeContrast(v) {
    if (v === 0 || v === '0') return 'standard';
    if (v === 0.5 || v === '0.5') return 'medium';
    if (v === 1 || v === '1') return 'high';
    return LEVELS[v] === undefined ? 'standard' : v;
  }
  function kebab(name) {
    return name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
  }
  function argbHex(argb) {
    return MCU.hexFromArgb(argb).toUpperCase();
  }
  function levelOf(state) {
    return LEVELS[state.contrast] === undefined ? 0 : LEVELS[state.contrast];
  }
  function summary(state) {
    const name = state.preset === 'custom' || !PRESETS[state.preset]
      ? ('自定义 ' + state.seed.toUpperCase())
      : PRESETS[state.preset].name;
    return name + ' · ' + LEVEL_NAMES[normalizeContrast(state.contrast)];
  }
  function sameState(a, b) {
    return a.seed.toUpperCase() === b.seed.toUpperCase() && a.contrast === b.contrast;
  }
  function isDirty() {
    return !sameState(draft, applied);
  }

  /* ---------------- 颜色引擎 ---------------- */
  function buildScheme(state, mode) {
    const dark = mode === 'dark';
    const seed = hexNormalize(state.seed) || PRESETS.jade.seed;
    const sourceHct = MCU.Hct.fromInt(MCU.argbFromHex(seed));
    // 0.4.0 的 SchemeTonalSpot 默认规范就是 2021；显式写出，换库版本时不会静默改规范。
    const scheme = new MCU.SchemeTonalSpot(sourceHct, dark, levelOf(state), '2021');
    // ⚠️ 「纯墨」这类**低色度种子**不能直接交给 TonalSpot：2021 规范会把主色调色板的
    // 色度强制拉到 32，于是 #1C1F21（HCT 色度 4.3）被算成明显的蓝/青色主色
    // （实测浅色 primary #186584、夜间 #8DCFF2）——名字叫「纯墨」，配色却不是墨。
    // 色度低于 8 时改用低色度调色板：主/次保留种子一点点点色度，第三色与中性归零。
    if (sourceHct.chroma < 8) {
      const byChroma = (chroma) => MCU.TonalPalette.fromHueAndChroma(sourceHct.hue, chroma);
      scheme.primaryPalette = byChroma(sourceHct.chroma);
      scheme.secondaryPalette = byChroma(sourceHct.chroma);
      scheme.tertiaryPalette = byChroma(0);
      scheme.neutralPalette = byChroma(0);
      scheme.neutralVariantPalette = byChroma(2);
    }
    const neutral = scheme.neutralPalette;
    const neutralTone = (t) => argbHex(neutral.tone(t));

    const roles = {};
    ROLES.forEach((role) => { roles[role] = argbHex(scheme[role]); });
    if (sourceHct.chroma < 8) {
      // 「纯墨」的主色取墨色本身（浅色近黑 / 夜间近白），而不是 M3 固定 tone 40 的中灰：
      // 旧配色里 ink 的 accent 就是 #1C1F21，用户对「纯墨」的预期是墨，不是灰。
      roles.primary = neutralTone(dark ? 85 : 12);
      roles.onPrimary = neutralTone(dark ? 12 : 100);
      roles.inversePrimary = neutralTone(dark ? 12 : 85);
    }
    const railAccent = dark ? roles.primary : roles.inversePrimary;

    // 应用语义令牌：**只从角色派生**；M3 没有「常暗侧栏」角色，用中性色板固定 tone。
    const tokens = {};
    ROLES.forEach((role) => { tokens['--md-sys-color-' + kebab(role)] = roles[role]; });
    tokens['--bg'] = roles.surface;
    tokens['--surface'] = roles.surfaceContainerLowest;
    tokens['--surface-2'] = roles.surfaceContainer;
    tokens['--line'] = roles.outlineVariant;
    tokens['--line-strong'] = roles.outline;
    tokens['--ink'] = roles.onSurface;
    tokens['--ink-2'] = roles.onSurfaceVariant;
    tokens['--ink-3'] = roles.outline;
    tokens['--accent'] = roles.primary;
    tokens['--on-accent'] = roles.onPrimary;
    tokens['--accent-ink'] = roles.onPrimaryContainer;
    // #257：普通表面上的强调文字用 primary（与 surface 有保证对比度）；
    // onPrimaryContainer 只留给 primaryContainer 容器内部，不能外溢。
    tokens['--accent-text'] = roles.primary;
    tokens['--accent-weak'] = roles.primaryContainer;
    tokens['--accent-line'] = roles.outlineVariant;
    tokens['--accent-dk'] = railAccent;
    tokens['--danger'] = roles.error;
    tokens['--danger-weak'] = roles.errorContainer;
    // ⚠️ 夜间 hero 不能用固定 neutral tone：M3 夜间 `surface` 就是 tone 6，
    // 旧写法 tone 6/11 会把 --hero-1 算成**与 --bg 完全同色**（实测 1.01 亮度比）
    // ⇒ 活动页「下一场」大卡整个融进页面底。夜间改成与 band.css §1b 同一口径的
    // accent 掺色公式（14% / 24%），浅色仍用中性色阶。
    tokens['--hero-1'] = dark ? `color-mix(in srgb, var(--accent-dk) 14%, ${neutralTone(4)})` : neutralTone(12);
    tokens['--hero-2'] = dark ? `color-mix(in srgb, var(--accent-dk) 24%, ${neutralTone(4)})` : neutralTone(18);
    tokens['--edge'] = roles.outline;
    tokens['--shellmid'] = neutralTone(78);
    tokens['--on-shell'] = neutralTone(92);
    tokens['--sh-1'] = '0 1px 2px color-mix(in srgb, var(--md-sys-color-shadow) 5%, transparent)';
    tokens['--sh-2'] = '0 10px 30px -18px color-mix(in srgb, var(--md-sys-color-shadow) 28%, transparent)';
    tokens['--sh-3'] = '0 24px 60px -30px color-mix(in srgb, var(--md-sys-color-shadow) 34%, transparent)';

    // 种子色阶：固定 tone 的色板，三档对比度下不变。
    const ramp = [20, 35, 50, 65, 80, 92].map((t) => argbHex(scheme.primaryPalette.tone(t)));

    return { roles: roles, tokens: tokens, ramp: ramp };
  }

  function setVars(el, scheme) {
    Object.keys(scheme.tokens).forEach((k) => el.style.setProperty(k, scheme.tokens[k]));
  }

  function setRootScheme(scheme) {
    setVars(document.documentElement, scheme);
    document.documentElement.style.colorScheme = theme === 'dark' ? 'dark' : 'light';
  }

  /* ---------------- 配置读写 / 迁移 ---------------- */
  function validTokens(t) {
    if (!t || typeof t !== 'object') return false;
    return ['light', 'dark'].every((mode) => {
      const map = t[mode];
      if (!map || typeof map !== 'object') return false;
      return ROLES.every((r) => typeof map['--md-sys-color-' + kebab(r)] === 'string')
        && typeof map['--bg'] === 'string'
        && typeof map['--accent'] === 'string';
    });
  }

  function defaultState() {
    return { seed: PRESETS.jade.seed, preset: 'jade', contrast: 'standard' };
  }

  function loadApplied() {
    const raw = lsGet(SETTINGS_KEY);
    if (raw) {
      let cfg = null;
      try { cfg = JSON.parse(raw); } catch (e) { cfg = null; }
      const seed = cfg && cfg.version === 2 ? hexNormalize(cfg.seed) : null;
      if (!seed) {
        notices.push('已保存的主题配置损坏，已回落到默认墨·青。原有日夜偏好不受影响。');
        return { state: defaultState(), repair: true };
      }
      const preset = PRESETS[cfg.preset] ? cfg.preset : 'custom';
      const state = { seed: seed, preset: preset, contrast: normalizeContrast(cfg.contrast) };
      return { state: state, repair: !validTokens(cfg.tokens) };
    }

    // 没有 v2 配置：读旧键迁移（不删除旧键，降级时仍可用）。
    const old = lsGet('band.palette');
    if (PRESETS[old]) {
      notices.push('已从旧版配色「' + PRESETS[old].name + '」读取种子色；应用到全站后会写入新的主题配置，同时保留旧键。');
      return {
        state: { seed: PRESETS[old].seed, preset: old, contrast: 'standard' },
        migrated: true,
        repair: true,
      };
    }
    return { state: defaultState(), repair: false };
  }

  function payloadFor(state) {
    const light = buildScheme(state, 'light');
    const dark = buildScheme(state, 'dark');
    return {
      version: 2,
      // rev 2：修复夜间 hero 与「纯墨」令牌的派生口径。无引擎的页面靠它识别旧缓存
      // （rev 缺失 = 旧配置），做一次性降级，不必让用户重新点「应用」。
      rev: 2,
      seed: state.seed,
      preset: state.preset,
      contrast: state.contrast,
      // 派生缓存与配置写在同一个键：一次写入、一次校验，跨页首帧不必再跑引擎。
      tokens: { light: light.tokens, dark: dark.tokens },
    };
  }

  function persist(state) {
    const ok = lsSet(SETTINGS_KEY, JSON.stringify(payloadFor(state)));
    // 旧键只作为降级兼容：自定义种子不覆盖它，免得老版本显示成"上一个预置色"。
    if (state.preset !== 'custom' && PRESETS[state.preset]) lsSet('band.palette', state.preset);
    return ok;
  }

  /* ---------------- 预览 / 控件 ---------------- */
  function renderPreview() {
    const scheme = buildScheme(draft, theme);
    setVars(preview, scheme);
    setVars(sticky, scheme);
    stickyLevel.textContent = LEVEL_NAMES[normalizeContrast(draft.contrast)];
    sticky.setAttribute('aria-label', '查看完整主题预览，当前' + LEVEL_NAMES[normalizeContrast(draft.contrast)]);
    scheduleSticky();
  }

  function renderDraftLine() {
    const dirty = isDirty();
    draftDot.classList.toggle('dirty', dirty);
    draftText.textContent = dirty
      ? '未应用：草稿 ' + summary(draft) + '；已应用 ' + summary(applied) + '。'
      : '当前已应用：' + summary(applied) + '。';
  }

  function renderRamp() {
    const ramp = buildScheme(draft, theme).ramp;
    rampEl.textContent = '';
    ramp.forEach((hex) => {
      const band = document.createElement('span');
      band.className = 'theme-ramp-band';
      band.style.backgroundColor = hex;
      rampEl.appendChild(band);
    });
  }

  function renderControls() {
    seedsEl.querySelectorAll('.theme-seed').forEach((b) => {
      b.setAttribute('aria-pressed', String(draft.preset === b.dataset.p));
    });
    colorEl.value = draft.seed.toLowerCase();
    hexEl.value = draft.seed.toUpperCase();
    hexErr.hidden = true;
    document.querySelectorAll('input[name="contrast"]').forEach((r) => {
      r.checked = r.value === normalizeContrast(draft.contrast);
    });
    renderDraftLine();
    renderRamp();
  }

  function renderAll() {
    setRootScheme(buildScheme(applied, theme));
    renderPreview();
    renderControls();
  }

  function updateDraft(next) {
    draft = {
      seed: next.seed !== undefined ? next.seed : draft.seed,
      preset: next.preset !== undefined ? next.preset : draft.preset,
      contrast: next.contrast !== undefined ? normalizeContrast(next.contrast) : draft.contrast,
    };
    renderPreview();
    renderControls();
  }

  /* ---------------- 操作 ---------------- */
  function toast(message) {
    if (BAND && BAND.toast) BAND.toast(message, 'success');
  }

  function applyDraft() {
    applied = { seed: draft.seed, preset: draft.preset, contrast: draft.contrast };
    draft = { seed: applied.seed, preset: applied.preset, contrast: applied.contrast };
    const ok = persist(applied);
    setRootScheme(buildScheme(applied, theme));
    renderAll();
    toast(ok
      ? '已应用到全站'
      : '已在本次会话生效，但偏好未能写入本机存储');
    if (!ok) {
      notices.push('本机存储不可用：偏好不会被记住，本次会话内仍可正常使用。');
      showNotices();
    }
  }

  function resetDraft() {
    draft = defaultState();
    renderPreview();
    renderControls();
    toast('已恢复默认草稿；仍需点「应用到全站」才会写入偏好');
  }

  function discardDraft() {
    draft = { seed: applied.seed, preset: applied.preset, contrast: applied.contrast };
    renderPreview();
    renderControls();
    toast('已取消草稿；保留上次已应用的颜色方案');
  }

  function showNotices() {
    if (!notices.length) return;
    noticeEl.textContent = notices.join('\n');
    noticeEl.hidden = false;
  }

  /* ---------------- 返回来源（#248） ---------------- */
  function askDiscard() {
    if (BAND && BAND.confirm) {
      return BAND.confirm({
        title: '还有未应用的配色草稿',
        body: '返回会丢弃草稿；已应用的颜色和日夜状态不受影响。',
        ok: '放弃草稿并返回',
        cancel: '留在本页',
      });
    }
    return Promise.resolve(window.confirm('还有未应用的配色草稿，返回会丢弃。继续？'));
  }

  function mountBack() {
    BAND.navigation.mountBack({
      id: 'theme-back', isDirty,
      canLeave: () => isDirty() ? askDiscard() : true,
      didLeave: () => {
        allowLeave = true;
        draft = { seed: applied.seed, preset: applied.preset, contrast: applied.contrast };
      },
    });
  }

  /* ---------------- 手机端实时预览条 ---------------- */
  const STICKY_MQ = window.matchMedia('(max-width: 620px)');
  let stickyFrame = 0;
  let stickyDismissed = false;

  function previewVisibleEnough() {
    const r = preview.getBoundingClientRect();
    const visible = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    // 阈值按预览自身高度算，不写死滚动距离。
    return visible >= Math.min(120, r.height * 0.35);
  }

  function overlapsContrast() {
    const bar = sticky.getBoundingClientRect();
    if (bar.height === 0) return false;
    const seg = segEl.getBoundingClientRect();
    return seg.bottom > bar.top && seg.top < window.innerHeight;
  }

  function updateSticky() {
    const inView = previewVisibleEnough();
    if (stickyDismissed && inView) stickyDismissed = false;
    const show = STICKY_MQ.matches && !inView && !stickyDismissed && !overlapsContrast();
    sticky.dataset.show = show ? '1' : '0';
  }

  function scheduleSticky() {
    if (stickyFrame) return;
    stickyFrame = requestAnimationFrame(() => {
      stickyFrame = 0;
      updateSticky();
    });
  }

  /* ---------------- 初始化 ---------------- */
  function buildSeeds() {
    Object.keys(PRESETS).forEach((key) => {
      const p = PRESETS[key];
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'theme-seed';
      b.dataset.p = key;
      b.style.setProperty('--seed', p.seed);
      b.setAttribute('aria-pressed', 'false');
      b.setAttribute('aria-label', '预置种子色：' + p.name);
      const sw = document.createElement('span');
      sw.className = 'theme-seed-sw';
      sw.setAttribute('aria-hidden', 'true');
      const name = document.createElement('span');
      name.className = 'theme-seed-name';
      name.textContent = p.name;
      b.append(sw, name);
      b.addEventListener('click', () => {
        updateDraft({ seed: p.seed, preset: key, contrast: draft.contrast });
      });
      seedsEl.appendChild(b);
    });
  }

  function wire() {
    colorEl.addEventListener('input', () => {
      const hex = hexNormalize(colorEl.value);
      if (hex) updateDraft({ seed: hex, preset: 'custom' });
    });

    hexEl.addEventListener('input', () => {
      const hex = hexNormalize(hexEl.value);
      if (hex) {
        hexErr.hidden = true;
        updateDraft({ seed: hex, preset: 'custom' });
        colorEl.value = hex.toLowerCase();
      } else {
        // 无效 HEX 不覆盖已生效主题，也不改草稿。
        hexErr.hidden = false;
      }
    });
    hexEl.addEventListener('blur', () => {
      const hex = hexNormalize(hexEl.value);
      if (hex) hexEl.value = hex;
    });

    segEl.addEventListener('change', (e) => {
      const r = e.target.closest('input[name="contrast"]');
      if (r && r.checked) updateDraft({ contrast: r.value });
    });

    applyBtn.addEventListener('click', applyDraft);
    resetBtn.addEventListener('click', resetDraft);
    discardBtn.addEventListener('click', discardDraft);

    sticky.addEventListener('click', () => {
      const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      // 点一下先把自己收掉：平滑滚动途中也不该再浮在预览上。
      stickyDismissed = true;
      sticky.dataset.show = '0';
      preview.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
      preview.focus({ preventScroll: true });
      scheduleSticky();
    });

    window.addEventListener('scroll', scheduleSticky, { passive: true });
    window.addEventListener('resize', scheduleSticky);
    if (STICKY_MQ.addEventListener) {
      STICKY_MQ.addEventListener('change', scheduleSticky);
    } else if (STICKY_MQ.addListener) {
      STICKY_MQ.addListener(scheduleSticky);
    }

    // 日夜由 band-core 切换（保留墨洇、写 band.theme）；本页只跟着重画预览。
    const observer = new MutationObserver(() => {
      const next = BAND.appearance.currentTheme();
      if (next !== theme) {
        theme = next;
        renderPreview();
        renderControls();
      }
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    window.addEventListener('beforeunload', (e) => {
      if (isDirty() && !allowLeave) {
        e.preventDefault();
        e.returnValue = '';
      }
    });
  }

  async function init() {
    const me = await BAND.renderNav('theme.html');
    if (!me) return;

    if (!MCU) {
      notices.push('颜色引擎未能加载：本页暂时无法生成新配色，已应用的主题不受影响。');
      showNotices();
      return;
    }

    const loaded = loadApplied();
    applied = loaded.state;
    draft = { seed: applied.seed, preset: applied.preset, contrast: applied.contrast };

    if (!storageOK) {
      notices.push('本机存储不可用：本页仍可预览与应用，但偏好不会被记住。');
    }
    // 只修 v2 的派生缓存；从旧键迁移来的选择要等用户点「应用到全站」才写 v2。
    if (loaded.repair && !loaded.migrated && storageOK) persist(applied);

    theme = BAND.appearance.currentTheme();
    mountBack();
    buildSeeds();
    wire();
    renderAll();
    showNotices();

    // 修正：缓存缺失/损坏时 persist 已经写入，但如果存储不可用，本页仍用重算的令牌渲染。
    scheduleSticky();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
