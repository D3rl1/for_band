'use strict';
// Preview renderer only. This file does not choose, save or grant a frame.
(() => {
  const instances = new WeakMap();
  const cache = new Map();
  const plans = [
    { x:.42, y:.38, end:.46, sway:.045, duration:11.6, phase:.14, scale:.76, angle:-.6 },
    { x:.55, y:.25, end:.66, sway:.035, duration:13.1, phase:.68, scale:.65, angle:.5 },
    { x:.37, y:.43, end:.40, sway:.04, duration:10.4, phase:.43, scale:.73, angle:-.3 },
    { x:.62, y:.28, end:.73, sway:.027, duration:14.2, phase:.83, scale:.61, angle:.8 },
    { x:.48, y:.35, end:.55, sway:.047, duration:12.7, phase:.30, scale:.69, angle:-.9 },
    { x:.30, y:.44, end:.36, sway:.024, duration:15, phase:.94, scale:.55, angle:.2 }
  ];
  function load(url) {
    const key = String(url);
    if (cache.has(key)) return cache.get(key);
    const promise = new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error('Cannot load autumn frame asset: ' + key));
      image.src = key;
    });
    cache.set(key, promise);
    promise.catch(() => cache.delete(key));
    return promise;
  }
  function mount(root, options = {}) {
    if (instances.has(root)) return instances.get(root);
    if (!root.querySelector('.band-autumn-wrap__avatar')) throw new Error('Avatar child is required');
    const assetBase = new URL(options.assetBase || './assets/', document.baseURI);
    const canvas = document.createElement('canvas');
    canvas.className = 'band-autumn-wrap__art';
    canvas.setAttribute('aria-hidden', 'true');
    root.append(canvas);
    const ctx = canvas.getContext('2d');
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    let resources, disposed = false, paused = false, visible = true, time = 0, previous = null, raf = 0;
    function paint() {
      if (!resources || disposed) return;
      const [base, still, ...leaves] = resources;
      ctx.setTransform(canvas.width / 1254, 0, 0, canvas.height / 1254, 0, 0);
      ctx.clearRect(0, 0, 1254, 1254);
      const staticMode = reduced.matches || options.staticOnly === true;
      ctx.drawImage(staticMode ? still : base, 0, 0, 1254, 1254);
      if (staticMode) return;
      plans.forEach((plan, index) => {
        const leaf = leaves[index % leaves.length];
        const p = (time / plan.duration + plan.phase) % 1;
        const swing = Math.sin(p * Math.PI * 3.2 + index * 1.5);
        const x = (plan.x + (plan.end - plan.x) * p + swing * plan.sway * Math.sin(Math.PI * p)) * 1254;
        const y = (plan.y + (.887 - plan.y) * (1 - Math.pow(1 - p, 1.22))) * 1254;
        ctx.save(); ctx.translate(x, y);
        ctx.rotate(plan.angle + swing * .52 + p * Math.PI * (index % 2 ? .5 : -.6));
        ctx.scale(plan.scale * (.42 + .58 * Math.abs(Math.cos(p * Math.PI * 2.2 + index))), plan.scale);
        ctx.globalAlpha = Math.min(1, p / .065) * Math.min(1, (1 - p) / .09);
        ctx.drawImage(leaf, -leaf.naturalWidth / 2, -leaf.naturalHeight / 2);
        ctx.restore();
      });
    }
    function active() { return !disposed && resources && visible && !document.hidden && !paused && !reduced.matches && !options.staticOnly; }
    function tick(now) {
      raf = 0;
      if (!root.isConnected || !active()) { previous = null; return; }
      if (previous !== null) time += Math.min((now - previous) / 1000, .08);
      previous = now; paint(); raf = requestAnimationFrame(tick);
    }
    function update() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0; previous = null; paint();
      if (active()) raf = requestAnimationFrame(tick);
    }
    function resize() {
      const edge = Math.max(1, Math.round(canvas.getBoundingClientRect().width * Math.min(devicePixelRatio || 1, 2)));
      if (canvas.width !== edge || canvas.height !== edge) { canvas.width = edge; canvas.height = edge; }
      paint();
    }
    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(root);
    const intersection = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; update(); });
    intersection.observe(root);
    reduced.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);
    const controller = {
      ready: null,
      setPaused(value) { paused = Boolean(value); update(); },
      destroy() {
        disposed = true;
        if (raf) cancelAnimationFrame(raf);
        resizeObserver.disconnect(); intersection.disconnect();
        reduced.removeEventListener('change', update);
        document.removeEventListener('visibilitychange', update);
        canvas.remove(); instances.delete(root);
      }
    };
    instances.set(root, controller);
    controller.ready = Promise.all(['tree.webp', 'still.webp', 'leaf-1.webp', 'leaf-2.webp', 'leaf-3.webp', 'leaf-4.webp', 'leaf-5.webp'].map(file => load(new URL(file, assetBase))))
      .then(images => { if (disposed) return; resources = images; resize(); update(); })
      .catch(error => { if (!disposed) controller.destroy(); throw error; });
    return controller;
  }
  window.BandAutumnFrame = Object.freeze({ mount });
})();
