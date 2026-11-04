/* Dorftafel — interaktive Fortschritts- und Feature-Graphen + Pixel-Banner.
   Erwartet window.DT_PAGE = {type:'releases'|'villager'|'portfolio', name, releases?, all?} */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  function el(tag, attrs, parent, text) {
    var e = document.createElement(tag);
    if (attrs) for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }
  function sv(tag, attrs, parent) {
    var e = document.createElementNS(NS, tag);
    if (attrs) for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function fmtDate(d) { var p = d.split('-'); return p[2] + '.' + p[1] + '.' + p[0]; }

  /* ---------- Pixel-Dorf-Banner ---------- */
  function banner() {
    var host = document.querySelector('.section-nav') || document.querySelector('.topbar');
    if (!host || document.querySelector('.dt-banner')) return;
    var W = 192, H = 30, seed = 11;
    function rnd() { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; }
    var box = el('div', { class: 'dt-banner', 'aria-hidden': 'true' });
    host.parentNode.insertBefore(box, host.nextSibling);
    var s = sv('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'xMidYMax slice', 'shape-rendering': 'crispEdges' }, box);
    function R(x, y, w, h, c, cls) { var r = sv('rect', { x: x, y: y, width: w, height: h, fill: c }, s); if (cls) r.setAttribute('class', cls); return r; }
    for (var i = 0; i < 46; i++) R(Math.floor(rnd() * W), Math.floor(rnd() * 14), 1, 1, '#fff4d4', 'dt-star dt-s' + (i % 3));
    R(160, 4, 6, 6, 'var(--accent)'); R(161, 5, 4, 4, '#fff4d4');
    var x = -2;
    while (x < W) {
      var w = 11 + Math.floor(rnd() * 8), h = 8 + Math.floor(rnd() * 7), top = H - 3 - h;
      R(x, top, w, h, '#120d08');
      for (var r = 0; r < 5; r++) R(x - 1 + r, top - r - 1, w + 2 - 2 * r, 1, '#0d0906');
      R(x + Math.floor(w / 2) - 1, top - 6, 2, 3, '#120d08');
      var cols = Math.max(1, Math.floor((w - 2) / 4));
      for (var c = 0; c < cols; c++) if (rnd() > .35) R(x + 2 + c * 4, top + 3 + (rnd() > .6 ? 4 : 0), 2, 3, 'var(--accent)', 'dt-win');
      x += w + 2 + Math.floor(rnd() * 5);
      if (rnd() > .7) { R(x, H - 8, 1, 5, '#0d0906'); R(x - 1, H - 10, 3, 3, 'var(--accent)', 'dt-win'); x += 5; }
    }
    var tx = 118; R(tx, 5, 10, 22, '#0d0906'); R(tx - 1, 4, 12, 2, '#0d0906'); R(tx + 3, 8, 4, 4, '#ffb347', 'dt-win'); R(tx + 4, 9, 2, 2, '#fff4d4');
    R(0, H - 3, W, 3, '#0a0705'); for (var g = 0; g < W; g += 6) R(g, H - 3, 3, 1, '#1b130b');
  }

  /* ---------- Rahmen für alle Graphen ---------- */
  function section() {
    var main = document.querySelector('main') || document.body;
    var foot = main.querySelector('.closing') || main.querySelector('.legal-note');
    var sec = el('section', { class: 'content-section dt-section', id: 'fortschritt' });
    var head = el('div', { class: 'section-heading' }, sec);
    el('p', { class: 'eyebrow' }, head, 'Fortschritt');
    var h2 = el('h2', null, head);
    var body = el('div', { class: 'dt-body' }, sec);
    if (foot) foot.parentNode.insertBefore(sec, foot); else main.appendChild(sec);
    var nav = document.querySelector('.section-nav');
    if (nav && !nav.querySelector('a[href="#fortschritt"]')) el('a', { href: '#fortschritt' }, nav, 'Fortschritt');
    return { sec: sec, head: head, h2: h2, body: body };
  }
  function stat(parent, items) {
    var row = el('div', { class: 'dt-stats' }, parent);
    items.forEach(function (it) {
      var d = el('div', null, row);
      el('b', null, d, it[0]); el('span', null, d, it[1]);
    });
    return row;
  }
  function detailCard(parent) {
    var wrap = el('div', { class: 'dt-detail-wrap' }, parent);
    var card = el('div', { class: 'dt-detail', 'aria-live': 'polite' }, wrap);
    return card;
  }

  /* ---------- Release-Diagramm (Git-Tags) ---------- */
  function releaseChart(parent, rels, label) {
    var mode = 'per', sel = rels.length - 1;
    var total = rels.reduce(function (a, r) { return a + r.n; }, 0);
    stat(parent, [[rels.length, 'Releases'], [total, 'Commits'], [fmtDate(rels[0].d) + ' – ' + fmtDate(rels[rels.length - 1].d), 'Zeitraum']]);
    var bar = el('div', { class: 'dt-tools' }, parent);
    var g = el('div', { class: 'dt-seg', role: 'group', 'aria-label': 'Darstellung' }, bar);
    var b1 = el('button', { type: 'button', 'aria-pressed': 'true' }, g, 'Pro Release');
    var b2 = el('button', { type: 'button', 'aria-pressed': 'false' }, g, 'Gesamt');
    var lay = el('div', { class: 'dt-grid' }, parent);
    var chart = el('div', { class: 'dt-chart' }, lay);
    var card = detailCard(lay);
    function cum() { var a = 0; return rels.map(function (r) { a += r.n; return a; }); }
    function draw() {
      chart.innerHTML = '';
      var vals = mode === 'per' ? rels.map(function (r) { return r.n; }) : cum();
      var max = Math.max.apply(null, vals), per = Math.max(1, Math.ceil(max / 14));
      var cw = 26, gap = 6, W = rels.length * (cw + gap) + 8, H = 14 * 14 + 46;
      var s = sv('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'group', 'aria-label': 'Releases von ' + label, 'shape-rendering': 'crispEdges' }, chart);
      for (var y = 0; y <= 14; y += 2) sv('line', { x1: 0, x2: W, y1: H - 30 - y * 14, y2: H - 30 - y * 14, stroke: 'var(--line)', 'stroke-width': 1, 'stroke-dasharray': '2 4' }, s);
      rels.forEach(function (r, i) {
        var n = Math.max(1, Math.round(vals[i] / per)), x = 6 + i * (cw + gap);
        var g2 = sv('g', { class: 'dt-col' + (i === sel ? ' sel' : ''), tabindex: 0, role: 'button', 'aria-label': 'Version ' + r.v + ', ' + r.n + ' Commits', 'data-i': i }, s);
        sv('rect', { x: x - 2, y: 0, width: cw + 4, height: H, fill: 'transparent' }, g2);
        for (var k = 0; k < n; k++) {
          var yy = H - 30 - (k + 1) * 14;
          sv('rect', { x: x, y: yy, width: cw, height: 12, fill: 'var(--pb)' }, g2);
          sv('rect', { x: x, y: yy, width: cw, height: 3, fill: 'var(--pb-l)' }, g2);
          sv('rect', { x: x + cw - 3, y: yy, width: 3, height: 12, fill: 'var(--pb-d)' }, g2);
        }
        var showLbl = rels.length <= 12 || i % 3 === 0 || i === sel || i === rels.length - 1;
        if (showLbl) { var t = sv('text', { x: x + cw / 2, y: H - 10, 'text-anchor': 'middle', class: 'dt-lbl' }, g2); t.textContent = r.v; }
        g2.addEventListener('click', function () { sel = i; draw(); show(); });
        g2.addEventListener('keydown', function (e) {
          if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { e.preventDefault(); sel = Math.max(0, Math.min(rels.length - 1, i + (e.key === 'ArrowRight' ? 1 : -1))); draw(); show(); var c = chart.querySelector('[data-i="' + sel + '"]'); if (c) c.focus(); }
          else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); sel = i; draw(); show(); }
        });
      });
      var note = el('p', { class: 'dt-note' }, chart, 'Ein Block steht für ' + per + (per === 1 ? ' Commit' : ' Commits') + '. Auswahl per Klick oder Pfeiltasten.');
    }
    function show() {
      var r = rels[sel], c = cum()[sel];
      card.innerHTML = '<p class="k">Version ' + esc(r.v) + '</p><h3>' + fmtDate(r.d) + '</h3><p><b>' + r.n + '</b> ' + (r.n === 1 ? 'Commit' : 'Commits') + ' in diesem Release · <b>' + c + '</b> insgesamt</p>' +
        (r.s.length ? '<ul>' + r.s.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '') +
        '<p class="dt-nav"><button type="button" data-d="-1">← früher</button><button type="button" data-d="1">später →</button></p>';
      card.querySelectorAll('[data-d]').forEach(function (b) { b.onclick = function () { sel = Math.max(0, Math.min(rels.length - 1, sel + (+b.dataset.d))); draw(); show(); }; });
    }
    b1.onclick = function () { mode = 'per'; b1.setAttribute('aria-pressed', 'true'); b2.setAttribute('aria-pressed', 'false'); draw(); };
    b2.onclick = function () { mode = 'cum'; b2.setAttribute('aria-pressed', 'true'); b1.setAttribute('aria-pressed', 'false'); draw(); };
    draw(); show();
  }

  /* ---------- Portfolio: Bahnen je Projekt ---------- */
  function lanes(parent, all) {
    var names = Object.keys(all), items = [], sel = null;
    var dMin = '9999', dMax = '0000';
    names.forEach(function (k) { all[k].rels.forEach(function (r) { items.push({ k: k, r: r }); if (r.d < dMin) dMin = r.d; if (r.d > dMax) dMax = r.d; }); });
    var days = Math.max(1, Math.round((Date.parse(dMax) - Date.parse(dMin)) / 864e5));
    var tot = items.length, commits = items.reduce(function (a, i) { return a + i.r.n; }, 0);
    stat(parent, [[tot, 'Releases'], [names.length, 'Projekte'], [commits, 'Commits'], [days + 1, 'Tage Entwicklung']]);
    var lay = el('div', { class: 'dt-grid' }, parent);
    var chart = el('div', { class: 'dt-chart' }, lay);
    var card = detailCard(lay);
    var W = 720, H = 70 * names.length + 40, L = 110;
    var s = sv('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'group', 'aria-label': 'Releases aller Projekte', 'shape-rendering': 'crispEdges' }, chart);
    for (var d = 0; d <= days; d += (days > 10 ? 2 : 1)) {
      var x = L + (W - L - 20) * d / days;
      sv('line', { x1: x, x2: x, y1: 10, y2: H - 24, stroke: 'var(--line)', 'stroke-dasharray': '2 4' }, s);
      var dt = new Date(Date.parse(dMin) + d * 864e5), t = sv('text', { x: x, y: H - 6, 'text-anchor': 'middle', class: 'dt-lbl' }, s);
      t.textContent = ('0' + dt.getUTCDate()).slice(-2) + '.' + ('0' + (dt.getUTCMonth() + 1)).slice(-2) + '.';
    }
    names.forEach(function (k, li) {
      var y = 40 + li * 70, t = sv('text', { x: 4, y: y + 4, class: 'dt-lane' }, s); t.textContent = all[k].name;
      sv('line', { x1: L, x2: W - 14, y1: y, y2: y, stroke: 'var(--w2)', 'stroke-width': 4 }, s);
    });
    var nodes = [];
    items.forEach(function (it, idx) {
      var li = names.indexOf(it.k), dd = Math.round((Date.parse(it.r.d) - Date.parse(dMin)) / 864e5);
      var same = items.filter(function (o, j) { return j < idx && o.k === it.k && o.r.d === it.r.d; }).length;
      var x = L + (W - L - 20) * dd / days + same * 14, y = 40 + li * 70, sz = Math.min(24, 8 + it.r.n * 1.6);
      var g = sv('g', { class: 'dt-node', tabindex: 0, role: 'button', 'aria-label': all[it.k].name + ' ' + it.r.v }, s);
      sv('rect', { x: x - sz / 2 - 3, y: y - sz / 2 - 3, width: sz + 6, height: sz + 6, fill: 'none', stroke: 'var(--accent)', 'stroke-width': 3, class: 'ring' }, g);
      sv('rect', { x: x - sz / 2, y: y - sz / 2, width: sz, height: sz, fill: all[it.k].color, stroke: '#1d130a', 'stroke-width': 2 }, g);
      g.addEventListener('click', function () { pick(idx); }); g.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(idx); } });
      nodes.push(g);
    });
    function pick(i) {
      sel = i; nodes.forEach(function (n, j) { n.classList.toggle('sel', j === i); });
      var it = items[i];
      card.innerHTML = '<p class="k">' + esc(all[it.k].name) + '</p><h3>Version ' + esc(it.r.v) + '</h3><p>' + fmtDate(it.r.d) + ' · <b>' + it.r.n + '</b> Commits</p>' +
        (it.r.s.length ? '<ul>' + it.r.s.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '') +
        (all[it.k].href ? '<p><a class="text-link" href="' + all[it.k].href + '">Zur Projektseite</a></p>' : '');
    }
    var last = 0; items.forEach(function (it, i) { if (it.r.d >= items[last].r.d) last = i; }); pick(last);
  }

  /* ---------- Villagers: 62 Features ---------- */
  function villager(parent) {
    var V = window.DT_V; if (!V) return;
    var D = V.D, F = V.F, L = V.L, by = {}, rel = {}, filt = 'all', q = '', focusD = null, sel = null, view = 'ring';
    F.forEach(function (f) { by[f[0]] = f; });
    L.forEach(function (l) { (rel[l[0]] = rel[l[0]] || []).push(l[1]); (rel[l[1]] = rel[l[1]] || []).push(l[0]); });
    function dk(id) { return id[0] === 'R' ? 'A' : id[0]; }
    var dn = {}; D.forEach(function (d) { dn[d[0]] = d[1]; });
    var byD = {}; D.forEach(function (d) { byD[d[0]] = F.filter(function (f) { return dk(f[0]) === d[0]; }); });
    var nDone = F.filter(function (f) { return f[2] === 'd'; }).length;

    var top = el('div', { class: 'dt-vtop' }, parent);
    var ringBox = el('div', { class: 'dt-ring' }, top);
    var rs = sv('svg', { viewBox: '-60 -60 120 120', role: 'img', 'aria-label': nDone + ' von ' + F.length + ' Anforderungen umgesetzt', 'shape-rendering': 'crispEdges' }, ringBox);
    F.forEach(function (f, i) {
      var a = (i / F.length) * Math.PI * 2 - Math.PI / 2, x = 46 * Math.cos(a), y = 46 * Math.sin(a), done = i < nDone;
      var srt = F.slice().sort(function (p, q2) { return (p[2] === 'd' ? 0 : 1) - (q2[2] === 'd' ? 0 : 1); })[i][2] === 'd';
      sv('rect', { x: x - 3.4, y: y - 3.4, width: 6.8, height: 6.8, fill: srt ? 'var(--ok)' : 'var(--w2)', stroke: '#1d130a', 'stroke-width': 1 }, rs);
    });
    var t1 = sv('text', { x: 0, y: 6, 'text-anchor': 'middle', class: 'dt-big' }, rs); t1.textContent = nDone + '/' + F.length;
    var t2 = sv('text', { x: 0, y: 22, 'text-anchor': 'middle', class: 'dt-sm' }, rs); t2.textContent = 'UMGESETZT';
    var bars = el('div', { class: 'dt-bars' }, top);
    D.forEach(function (d) {
      var it = byD[d[0]], n = it.filter(function (f) { return f[2] === 'd'; }).length;
      var b = el('button', { type: 'button', class: 'dt-bar', 'aria-pressed': 'false' }, bars);
      b.innerHTML = '<span class="n">' + esc(d[1]) + '</span><span class="c">' + n + '/' + it.length + '</span><span class="m"></span>';
      var m = b.querySelector('.m');
      it.slice().sort(function (a, c) { return (a[2] === 'd' ? 0 : 1) - (c[2] === 'd' ? 0 : 1); }).forEach(function (f) { el('i', { class: f[2] === 'd' ? 'd' : 'w' }, m); });
      b.onclick = function () { focusD = focusD === d[0] ? null : d[0]; bars.querySelectorAll('.dt-bar').forEach(function (x) { x.setAttribute('aria-pressed', 'false'); }); if (focusD) b.setAttribute('aria-pressed', 'true'); render(); };
    });

    var tools = el('div', { class: 'dt-tools' }, parent);
    var g1 = el('div', { class: 'dt-seg', role: 'group', 'aria-label': 'Status' }, tools), fb = {};
    [['all', 'Alle'], ['d', 'Umgesetzt'], ['p', 'Teilweise']].forEach(function (x) { var b = el('button', { type: 'button', 'aria-pressed': x[0] === 'all' ? 'true' : 'false' }, g1, x[1]); fb[x[0]] = b; b.onclick = function () { filt = x[0]; for (var k in fb) fb[k].setAttribute('aria-pressed', k === filt); render(); }; });
    var g2 = el('div', { class: 'dt-seg', role: 'group', 'aria-label': 'Ansicht' }, tools), vb = {};
    [['ring', 'Ring'], ['list', 'Liste']].forEach(function (x) { var b = el('button', { type: 'button', 'aria-pressed': x[0] === 'ring' ? 'true' : 'false' }, g2, x[1]); vb[x[0]] = b; b.onclick = function () { view = x[0]; for (var k in vb) vb[k].setAttribute('aria-pressed', k === view); build(); }; });
    var inp = el('input', { type: 'search', placeholder: 'Feature suchen: Mine, Karawane, J04', 'aria-label': 'Features suchen' }, tools);
    inp.oninput = function () { q = inp.value.toLowerCase(); render(); };

    var lay = el('div', { class: 'dt-grid' }, parent);
    var chart = el('div', { class: 'dt-chart' }, lay);
    var card = detailCard(lay);
    var nodes = {}, chords = [];

    function ok(f) {
      var s = filt === 'all' || filt === f[2], t = !q || (f[0] + ' ' + f[1] + ' ' + f[3]).toLowerCase().indexOf(q) > -1, d = !focusD || dk(f[0]) === focusD;
      return s && t && d;
    }
    function block(g, done, x, y) {
      var gg = sv('g', { transform: 'translate(' + x + ' ' + y + ') scale(1.55) translate(' + (-x) + ' ' + (-y) + ')' }, g);
      var c = done ? 'var(--ok)' : 'var(--w2)';
      sv('rect', { class: 'halo', x: x - 12, y: y - 12, width: 24, height: 24, fill: 'none', stroke: 'var(--accent)', 'stroke-width': 2 }, gg);
      sv('rect', { x: x - 8, y: y - 8, width: 16, height: 16, fill: '#1d130a' }, gg);
      sv('rect', { x: x - 6, y: y - 6, width: 12, height: 12, fill: c }, gg);
      sv('rect', { x: x - 6, y: y - 6, width: 12, height: 2, fill: done ? 'var(--ok-l)' : 'var(--w1)' }, gg);
      sv('rect', { x: x + 3, y: y - 6, width: 3, height: 12, fill: done ? 'var(--ok-d)' : 'var(--w3)' }, gg);
      if (!done) sv('path', { d: 'M' + (x - 6) + ' ' + (y + 6) + ' L' + (x + 6) + ' ' + (y - 6), stroke: '#1d130a', 'stroke-width': 1.6 }, gg);
    }
    function build() {
      chart.innerHTML = ''; nodes = {}; chords = [];
      if (view === 'ring') {
        var s = sv('svg', { viewBox: '-330 -330 660 660', role: 'group', 'aria-label': 'Feature-Graph', 'shape-rendering': 'crispEdges' }, chart);
        var per = (360 - 8 * D.length) / F.length, a0 = 4, ang = {}, RN = 250;
        D.forEach(function (d) {
          var it = byD[d[0]], st = a0; it.forEach(function (f, i) { ang[f[0]] = a0 + (i + .5) * per; }); a0 += it.length * per;
          var mid = (st + a0) / 2, rad = mid * Math.PI / 180, lx = 300 * Math.sin(rad), ly = -300 * Math.cos(rad);
          var t = sv('text', { x: lx, y: ly, 'text-anchor': 'middle', class: 'dt-dist' }, s); t.textContent = d[1].split(' ')[0].toUpperCase();
          a0 += 8;
        });
        sv('circle', { r: RN, fill: 'none', stroke: '#0e0a06', 'stroke-opacity': .85, 'stroke-width': 40 }, s);
        sv('circle', { r: RN, fill: 'none', stroke: '#d9bb82', 'stroke-opacity': .2, 'stroke-dasharray': '8 10' }, s);
        function P(r, a) { var t = a * Math.PI / 180; return [r * Math.sin(t), -r * Math.cos(t)]; }
        var gc = sv('g', {}, s);
        L.forEach(function (l) {
          var a = P(RN - 10, ang[l[0]]), b = P(RN - 10, ang[l[1]]);
          chords.push({ a: l[0], b: l[1], el: sv('path', { d: 'M' + a[0] + ' ' + a[1] + ' C' + a[0] * .2 + ' ' + a[1] * .2 + ' ' + b[0] * .2 + ' ' + b[1] * .2 + ' ' + b[0] + ' ' + b[1], class: 'dt-thread' }, gc) });
        });
        var cg = sv('g', { 'text-anchor': 'middle' }, s); cg.setAttribute('class', 'dt-center'); nodes._c = cg;
        F.forEach(function (f) {
          var p = P(RN, ang[f[0]]), g = sv('g', { class: 'dt-nd', tabindex: 0, role: 'button', 'aria-label': f[0] + ' ' + f[1] + ', ' + (f[2] === 'd' ? 'umgesetzt' : 'teilweise') }, s);
          block(g, f[2] === 'd', p[0], p[1]);
          var rt = ang[f[0]] < 180, tp = P(RN + 30, ang[f[0]]);
          var t = sv('text', { x: tp[0], y: tp[1] + 4, 'text-anchor': rt ? 'start' : 'end', class: 'dt-nl' }, g); t.textContent = f[0];
          g.addEventListener('click', function () { pick(f[0]); }); g.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(f[0]); } });
          g.addEventListener('mouseenter', function () { hov = f[0]; render(); }); g.addEventListener('mouseleave', function () { hov = null; render(); });
          nodes[f[0]] = { g: g };
        });
      } else {
        var wrap = el('div', { class: 'dt-list' }, chart);
        D.forEach(function (d) {
          var sec = el('div', { class: 'dt-lcol' }, wrap); el('h4', null, sec, d[1]);
          byD[d[0]].slice().sort(function (a, c) { return (a[2] === 'd' ? 0 : 1) - (c[2] === 'd' ? 0 : 1); }).forEach(function (f) {
            var b = el('button', { type: 'button', class: 'dt-li ' + (f[2] === 'd' ? 'd' : 'w') }, sec);
            b.innerHTML = '<i></i><b>' + f[0] + '</b><span>' + esc(f[1]) + '</span>';
            b.onclick = function () { pick(f[0]); };
            nodes[f[0]] = { g: b };
          });
        });
      }
      render();
    }
    var hov = null;
    function render() {
      var act = hov || sel, r = act ? (rel[act] || []) : [];
      F.forEach(function (f) {
        var n = nodes[f[0]]; if (!n) return;
        var inRel = !act || f[0] === act || r.indexOf(f[0]) > -1, faded = !(ok(f) && inRel);
        n.g.classList.toggle('faded', faded); n.g.classList.toggle('sel', f[0] === sel);
      });
      chords.forEach(function (c) { var on = act && (c.a === act || c.b === act); c.el.classList.toggle('on', !!on); });
      var cg = nodes._c; if (cg) {
        cg.innerHTML = '';
        function tx(y, cls, s) { var t = sv('text', { y: y, class: cls }, cg); t.textContent = s; }
        if (act) { var f = by[act]; tx(-8, 'dt-cid', act); tx(20, 'dt-cnm', f[1].length > 22 ? f[1].slice(0, 21) + '…' : f[1]); tx(44, 'dt-csb', f[2] === 'd' ? 'UMGESETZT' : 'BAUSTELLE'); }
        else { tx(14, 'dt-cbg', nDone + '/' + F.length); tx(40, 'dt-csb', 'UMGESETZT'); }
      }
    }
    function pick(id) {
      sel = id; var f = by[id], r = rel[id] || [], done = f[2] === 'd';
      card.innerHTML = '<p class="k">' + id + ' · ' + esc(dn[dk(id)]) + '</p><h3>' + esc(f[1]) + '</h3><span class="dt-tag ' + (done ? 'd' : 'w') + '">' + (done ? 'Umgesetzt' : 'Teilweise, Baustelle') + '</span>' +
        '<p>' + esc(f[3]) + '</p>' +
        '<p class="dt-open"><b>' + (done ? 'Hinweis' : 'Offen') + ':</b> ' + esc(done ? ((V.HINT[id]) || 'Natürliche CTOV-Abnahme noch offen.') : ((V.GAP[id]) || 'Natürliche CTOV-Abnahme noch offen.')) + '</p>' +
        '<p class="k">Hängt zusammen mit</p><p class="dt-rels">' + (r.length ? r.map(function (k) { return '<button type="button" data-id="' + k + '">' + k + ' ' + esc(by[k][1]) + '</button>'; }).join('') : 'keine abgeleitete Verbindung') + '</p>';
      card.querySelectorAll('[data-id]').forEach(function (b) { b.onclick = function () { pick(b.dataset.id); }; });
      render();
    }
    build();
    el('p', { class: 'dt-note' }, parent, 'Die Verbindungen sind aus dem Spec abgeleitet und keine Code-Abhängigkeiten. Alle bisherigen Prüfungen (501 Checks) liefen auf synthetischen Dev-Server-Fixtures; natürliche CTOV-, Client- und Performance-Abnahme stehen noch aus. Stand 04.11.2026.');
    card.innerHTML = '<p class="k">Auswahl</p><h3>Feature antippen</h3><p>Ein Klick auf einen Block zeigt Beschreibung, Status, offene Punkte und die verbundenen Features. Die Balken oben filtern nach Bezirk.</p>';
    render();
  }

  /* ---------- Feature-Explorer aus den vorhandenen Seitenabschnitten ---------- */
  function explorer(parent) {
    var feats = [];
    document.querySelectorAll('.feature-row').forEach(function (r) {
      var h = r.querySelector('h3'), p = r.querySelector('p'); if (h) feats.push({ n: h.textContent.trim(), d: p ? p.textContent.trim() : '', st: 'f', row: r });
    });
    var sc = document.querySelectorAll('.status-columns > div');
    sc.forEach(function (col, ci) { col.querySelectorAll('li').forEach(function (li) { feats.push({ n: li.textContent.trim().slice(0, 60), d: li.textContent.trim(), st: ci === 0 ? 'd' : 'w', row: col }); }); });
    if (!feats.length) return;
    var hasStatus = feats.some(function (f) { return f.st !== 'f'; }), sel = 0, filt = 'all';
    var box = el('div', { class: 'dt-expl' }, parent);
    el('h3', { class: 'dt-h3' }, box, 'Funktionen im Überblick');
    var tools = el('div', { class: 'dt-tools' }, box);
    if (hasStatus) {
      var g = el('div', { class: 'dt-seg', role: 'group', 'aria-label': 'Status' }, tools), btns = {};
      [['all', 'Alle'], ['d', 'Verfügbar'], ['w', 'Geplant']].forEach(function (x) { var b = el('button', { type: 'button', 'aria-pressed': x[0] === 'all' ? 'true' : 'false' }, g, x[1]); btns[x[0]] = b; b.onclick = function () { filt = x[0]; for (var k in btns) btns[k].setAttribute('aria-pressed', k === filt); draw(); }; });
    }
    var lay = el('div', { class: 'dt-grid' }, box), grid = el('div', { class: 'dt-blocks' }, lay), card = detailCard(lay);
    function draw() {
      grid.innerHTML = '';
      feats.forEach(function (f, i) {
        if (filt !== 'all' && f.st !== filt) return;
        var b = el('button', { type: 'button', class: 'dt-blk ' + f.st + (i === sel ? ' sel' : '') }, grid);
        b.innerHTML = '<i></i><span>' + esc(f.n) + '</span>';
        b.onclick = function () { sel = i; draw(); show(); };
      });
    }
    function show() {
      var f = feats[sel];
      card.innerHTML = '<p class="k">' + (f.st === 'd' ? 'Verfügbar' : f.st === 'w' ? 'Geplant' : 'Funktion') + '</p><h3>' + esc(f.n) + '</h3><p>' + esc(f.d) + '</p><p><button type="button" class="dt-go">Zum Abschnitt springen</button></p>';
      card.querySelector('.dt-go').onclick = function () { f.row.scrollIntoView({ behavior: 'smooth', block: 'center' }); f.row.classList.add('dt-flash'); setTimeout(function () { f.row.classList.remove('dt-flash'); }, 1600); };
    }
    draw(); show();
  }

  function init() {
    banner();
    var P = window.DT_PAGE; if (!P) return;
    var s = section();
    if (P.type === 'villager') { s.h2.textContent = 'Wie weit ist das Dorf?'; villager(s.body); }
    else if (P.type === 'portfolio') { s.h2.textContent = 'Was in den Projekten passiert.'; lanes(s.body, P.all); }
    else if (P.type === 'releases') { s.h2.textContent = P.name + ': Release für Release.'; releaseChart(s.body, P.releases, P.name); explorer(s.body); }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
