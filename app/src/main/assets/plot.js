/* LocalMath plotter — no dependencies, works offline.
 * plotGraph(canvas, G, C)
 *   G = { curves: [{ f: x => y, color, vx }], points: [{x, y, label}],
 *         shade: [a, b] | null, intervals: [{a, b, ca, cb}], x0, x1 }
 *   C = { text, muted, grid, axis, band }
 */
function pw(a, b) {
  if (a < 0 && !Number.isInteger(b)) {
    var inv = 1 / b;
    if (Math.abs(inv - Math.round(inv)) < 1e-9 && Math.round(inv) % 2 !== 0) return -Math.pow(-a, b);
    return NaN;
  }
  return Math.pow(a, b);
}
function ln(x) { return x > 0 ? Math.log(x) : NaN; }
function lg(x) { return x > 0 ? Math.log(x) / Math.LN10 : NaN; }

function plotGraph(canvas, G, C, buttons) {
  var ctx = canvas.getContext('2d');
  var W = 0, H = 0, dpr = 1;
  var view = { x0: G.x0, x1: G.x1, y0: -10, y1: 10 };

  function safe(f, x) { try { var y = f(x); return (typeof y === 'number') ? y : NaN; } catch (e) { return NaN; } }

  function autoY() {
    var ys = [], n = 240, i, c, k;
    for (k = 0; k < G.curves.length; k++) {
      c = G.curves[k];
      if (c.vx !== null && c.vx !== undefined) continue;
      for (i = 0; i <= n; i++) {
        var y = safe(c.f, view.x0 + (view.x1 - view.x0) * i / n);
        if (isFinite(y)) ys.push(y);
      }
    }
    for (k = 0; k < G.points.length; k++) ys.push(G.points[k].y);
    if (!ys.length) { view.y0 = -10; view.y1 = 10; return; }
    ys.sort(function (a, b) { return a - b; });
    var lo = ys[Math.floor((ys.length - 1) * 0.04)], hi = ys[Math.ceil((ys.length - 1) * 0.96)];
    for (k = 0; k < G.points.length; k++) { lo = Math.min(lo, G.points[k].y); hi = Math.max(hi, G.points[k].y); }
    var span = hi - lo;
    if (lo > 0 && lo < span * 0.6) lo = 0;
    if (hi < 0 && -hi < span * 0.6) hi = 0;
    if (hi - lo < 1e-9) { lo -= 1; hi += 1; }
    var pad = (hi - lo) * 0.12;
    view.y0 = lo - pad; view.y1 = hi + pad;
  }

  function sx(x) { return (x - view.x0) / (view.x1 - view.x0) * W; }
  function sy(y) { return H - (y - view.y0) / (view.y1 - view.y0) * H; }
  function wx(px) { return view.x0 + px / W * (view.x1 - view.x0); }
  function wy(py) { return view.y0 + (H - py) / H * (view.y1 - view.y0); }

  function niceStep(range, count) {
    var raw = range / count, p = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10)), m = raw / p;
    return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
  }
  function fmt(v, step) {
    if (Math.abs(v) < step * 1e-6) return '0';
    var d = Math.max(0, -Math.floor(Math.log(step) / Math.LN10));
    var s = v.toFixed(Math.min(d, 6));
    return s.replace('-', '\u2212');
  }

  function resize() {
    dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth || 300, h = canvas.clientHeight || 240;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    W = canvas.width; H = canvas.height;
    draw();
  }

  function draw() {
    if (!W || !H) return;
    ctx.clearRect(0, 0, W, H);
    var font = Math.round(11 * dpr) + 'px sans-serif';
    ctx.font = font;
    ctx.lineWidth = 1 * dpr;

    // grid
    var xs = niceStep(view.x1 - view.x0, 6), ys = niceStep(view.y1 - view.y0, 5), v;
    ctx.strokeStyle = C.grid;
    ctx.beginPath();
    for (v = Math.ceil(view.x0 / xs) * xs; v <= view.x1; v += xs) { ctx.moveTo(sx(v), 0); ctx.lineTo(sx(v), H); }
    for (v = Math.ceil(view.y0 / ys) * ys; v <= view.y1; v += ys) { ctx.moveTo(0, sy(v)); ctx.lineTo(W, sy(v)); }
    ctx.stroke();

    // axes
    var ax = Math.min(Math.max(sy(0), 0), H), ay = Math.min(Math.max(sx(0), 0), W);
    ctx.strokeStyle = C.axis; ctx.lineWidth = 1.4 * dpr;
    ctx.beginPath(); ctx.moveTo(0, ax); ctx.lineTo(W, ax); ctx.moveTo(ay, 0); ctx.lineTo(ay, H); ctx.stroke();

    // tick labels
    ctx.fillStyle = C.muted;
    ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    var lx = Math.min(ax + 3 * dpr, H - 14 * dpr);
    for (v = Math.ceil(view.x0 / xs) * xs; v <= view.x1; v += xs) {
      if (Math.abs(v) < xs * 1e-6) continue;
      ctx.fillText(fmt(v, xs), sx(v), lx);
    }
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    var ly = Math.min(Math.max(ay + 4 * dpr, 2 * dpr), W - 40 * dpr);
    for (v = Math.ceil(view.y0 / ys) * ys; v <= view.y1; v += ys) {
      if (Math.abs(v) < ys * 1e-6) continue;
      ctx.fillText(fmt(v, ys), ly, sy(v));
    }

    // shaded area (definite integrals)
    if (G.shade && G.curves.length) {
      var f = G.curves[0].f, a = G.shade[0], b = G.shade[1], lo = Math.min(a, b), hi = Math.max(a, b), n = 300, i;
      ctx.fillStyle = G.curves[0].color; ctx.globalAlpha = 0.22;
      ctx.beginPath(); ctx.moveTo(sx(lo), sy(0));
      for (i = 0; i <= n; i++) {
        var x = lo + (hi - lo) * i / n, y = safe(f, x);
        if (isFinite(y)) ctx.lineTo(sx(x), Math.max(-H, Math.min(2 * H, sy(y))));
      }
      ctx.lineTo(sx(hi), sy(0)); ctx.closePath(); ctx.fill(); ctx.globalAlpha = 1;
    }

    // curves
    var k;
    for (k = 0; k < G.curves.length; k++) {
      var c = G.curves[k];
      ctx.strokeStyle = c.color; ctx.lineWidth = 2.4 * dpr;
      ctx.beginPath();
      if (c.vx !== null && c.vx !== undefined) {
        ctx.moveTo(sx(c.vx), 0); ctx.lineTo(sx(c.vx), H);
      } else {
        var down = false, prev = 0, step = Math.max(1, Math.round(dpr)), px;
        for (px = 0; px <= W; px += step) {
          var yy = safe(c.f, wx(px)), py = sy(yy);
          if (!isFinite(py) || Math.abs(py) > 1e6) { down = false; continue; }
          if (down && Math.abs(py - prev) > H * 1.2) down = false;   // asymptote: lift the pen
          if (down) ctx.lineTo(px, py); else ctx.moveTo(px, py);
          down = true; prev = py;
        }
      }
      ctx.stroke();
    }

    // solution intervals on the x-axis (inequalities)
    if (G.intervals && G.intervals.length) {
      var band = Math.min(Math.max(sy(0), 8 * dpr), H - 8 * dpr);
      ctx.strokeStyle = C.band; ctx.fillStyle = C.band; ctx.lineWidth = 5 * dpr; ctx.lineCap = 'round';
      for (k = 0; k < G.intervals.length; k++) {
        var iv = G.intervals[k];
        var p0 = isFinite(iv.a) ? sx(iv.a) : -10, p1 = isFinite(iv.b) ? sx(iv.b) : W + 10;
        ctx.beginPath(); ctx.moveTo(p0, band); ctx.lineTo(p1, band); ctx.stroke();
      }
      ctx.lineWidth = 2 * dpr; ctx.lineCap = 'butt';
      for (k = 0; k < G.intervals.length; k++) {
        var e = G.intervals[k], ends = [[e.a, e.ca], [e.b, e.cb]], j;
        for (j = 0; j < 2; j++) {
          if (!isFinite(ends[j][0])) continue;
          ctx.beginPath(); ctx.arc(sx(ends[j][0]), band, 5 * dpr, 0, 2 * Math.PI);
          if (ends[j][1]) { ctx.fillStyle = C.band; ctx.fill(); }
          else { ctx.fillStyle = C.bg; ctx.fill(); ctx.strokeStyle = C.band; ctx.stroke(); }
        }
      }
    }

    // points (labels move below / further up when they would overlap)
    ctx.textAlign = 'left'; ctx.textBaseline = 'bottom';
    var boxes = [];
    for (k = 0; k < G.points.length; k++) {
      var p = G.points[k], qx = sx(p.x), qy = sy(p.y);
      if (qx < -20 || qx > W + 20 || qy < -20 || qy > H + 20) continue;
      ctx.fillStyle = C.text;
      ctx.beginPath(); ctx.arc(qx, qy, 4.5 * dpr, 0, 2 * Math.PI); ctx.fill();
      var tw = ctx.measureText(p.label).width, th = 13 * dpr;
      var tx = Math.max(2 * dpr, Math.min(qx + 6 * dpr, W - tw - 2 * dpr));
      var tries = [qy - 5 * dpr, qy + 5 * dpr + th, qy - 5 * dpr - th, qy + 5 * dpr + 2 * th], t, ty = tries[0];
      for (t = 0; t < tries.length; t++) {
        var cand = Math.max(th + 2 * dpr, Math.min(H - 2 * dpr, tries[t])), clash = false, b;
        for (b = 0; b < boxes.length; b++) {
          var o = boxes[b];
          if (tx < o.x + o.w && tx + tw > o.x && cand - th < o.y && cand > o.y - th) { clash = true; break; }
        }
        ty = cand;
        if (!clash) break;
      }
      boxes.push({ x: tx, y: ty, w: tw });
      ctx.fillText(p.label, tx, ty);
    }
  }

  // ---------- touch: drag to pan, pinch to zoom, double-tap to reset ----------
  var ptrs = {}, lastTap = 0, pinch = null;
  function count() { return Object.keys(ptrs).length; }
  function pos(e) { var r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left) * dpr, y: (e.clientY - r.top) * dpr }; }

  function zoom(factor, cx, cy) {
    var mx = wx(cx), my = wy(cy);
    view.x0 = mx + (view.x0 - mx) * factor; view.x1 = mx + (view.x1 - mx) * factor;
    view.y0 = my + (view.y0 - my) * factor; view.y1 = my + (view.y1 - my) * factor;
    draw();
  }
  function reset() { view.x0 = G.x0; view.x1 = G.x1; autoY(); draw(); }

  canvas.addEventListener('pointerdown', function (e) {
    canvas.setPointerCapture && canvas.setPointerCapture(e.pointerId);
    ptrs[e.pointerId] = pos(e);
    if (count() === 1) {
      var now = Date.now();
      if (now - lastTap < 300) reset();
      lastTap = now;
    }
    pinch = null;
  });
  canvas.addEventListener('pointermove', function (e) {
    if (!(e.pointerId in ptrs)) return;
    var p = pos(e), old = ptrs[e.pointerId];
    if (count() === 1) {
      var dx = (p.x - old.x) / W * (view.x1 - view.x0), dy = (p.y - old.y) / H * (view.y1 - view.y0);
      view.x0 -= dx; view.x1 -= dx; view.y0 += dy; view.y1 += dy;
      ptrs[e.pointerId] = p; draw();
    } else if (count() === 2) {
      ptrs[e.pointerId] = p;
      var ids = Object.keys(ptrs), a = ptrs[ids[0]], b = ptrs[ids[1]];
      var d = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinch && d > 0) zoom(pinch / d, (a.x + b.x) / 2, (a.y + b.y) / 2);
      pinch = d;
    }
  });
  function up(e) { delete ptrs[e.pointerId]; pinch = null; }
  canvas.addEventListener('pointerup', up);
  canvas.addEventListener('pointercancel', up);

  if (buttons) {
    buttons.zin && buttons.zin.addEventListener('click', function () { zoom(1 / 1.5, W / 2, H / 2); });
    buttons.zout && buttons.zout.addEventListener('click', function () { zoom(1.5, W / 2, H / 2); });
    buttons.reset && buttons.reset.addEventListener('click', reset);
  }
  window.addEventListener('resize', resize);

  autoY();
  resize();
  return { draw: draw, view: view, reset: reset, zoom: zoom };
}
