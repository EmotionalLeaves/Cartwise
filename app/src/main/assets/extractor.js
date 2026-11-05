/*
 * Cartwise page reader. Bundled with the app only (never fetched remotely),
 * so the safety guard below cannot be changed from the selectors file.
 * Reads the page you are viewing and returns JSON through CartwiseBridge.
 */
(function () {
  if (window.__cartwise) return;

  // Hard rule 2: the app never presses anything that places an order or pays.
  var FORBIDDEN = /(place\s*(your\s*)?order|placeorder|buy[\s_-]*now|buynow|1-click|one[\s-]?click|turbo|submit[\s_-]?order|pay\s*now|payment|proceed|checkout)/i;

  function arr(s) { return s == null ? [] : (typeof s === 'string' ? [s] : s); }
  function qa(root, sels) {
    sels = arr(sels);
    for (var i = 0; i < sels.length; i++) {
      try {
        var r = (root || document).querySelectorAll(sels[i]);
        if (r && r.length) return Array.prototype.slice.call(r);
      } catch (e) {}
    }
    return [];
  }
  function qall(root, sels) {
    sels = arr(sels); var out = [];
    for (var i = 0; i < sels.length; i++) {
      try {
        var r = (root || document).querySelectorAll(sels[i]);
        for (var j = 0; j < r.length; j++) if (out.indexOf(r[j]) < 0) out.push(r[j]);
      } catch (e) {}
    }
    return out;
  }
  function q(root, sels) { var r = qa(root, sels); return r.length ? r[0] : null; }
  function txt(el) { return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function qt(root, sels) { return txt(q(root, sels)); }
  function at(el, a) { return el ? (el.getAttribute(a) || '') : ''; }
  function abs(h) { try { return new URL(h, location.href).href; } catch (e) { return h || ''; } }
  function asinOf(h) {
    var m = /\/(?:dp|gp\/product|gp\/aw\/d|product-reviews)\/([A-Z0-9]{10})/.exec(h || '');
    return m ? m[1] : '';
  }
  function imgOf(el) {
    if (!el) return '';
    var s = at(el, 'data-old-hires');
    if (!s) {
      var dyn = at(el, 'data-a-dynamic-image');
      if (dyn) {
        try {
          var o = JSON.parse(dyn), best = '', bw = -1;
          for (var k in o) { var w = (o[k] && o[k][0]) || 0; if (w >= bw) { bw = w; best = k; } }
          s = best;
        } catch (e) {}
      }
    }
    if (!s) {
      var ss = at(el, 'srcset');
      if (ss) { var p = ss.split(','); s = p[p.length - 1].trim().split(' ')[0]; }
    }
    if (!s) s = at(el, 'src') || at(el, 'data-src');
    if (s && s.indexOf('data:') === 0) s = at(el, 'data-src') || '';
    return s ? abs(s) : '';
  }
  function ratingOf(root, sels) {
    var el = q(root, sels); if (!el) return '';
    var t = at(el, 'aria-label') || at(el, 'title') || txt(el);
    if (!/out of/.test(t)) { var alt = el.querySelector ? el.querySelector('.a-icon-alt') : null; if (alt) t = txt(alt); }
    return t;
  }
  function priceIn(text) { var m = /\$\s?\d[\d,]*(?:\.\d{2})?/.exec(text || ''); return m ? m[0] : ''; }
  function unitIn(text) { var m = /\(\s*\$\s?[\d,]*\.?\d+\s*\/\s*[^)]{1,24}\)/.exec(text || ''); return m ? m[0] : ''; }
  function deliveryIn(text) {
    var m = /(?:FREE\s+)?(?:delivery|Get it|Arrives|shipping)[^.|]{0,70}/i.exec(text || '');
    return m ? m[0] : '';
  }
  function cardFor(cfg, a) {
    var T = cfg.tiles || {}; var sels = arr(T.card); var card = null;
    for (var i = 0; i < sels.length && !card; i++) {
      try { var c = a.closest(sels[i]); if (c && txt(c).length < 1500) card = c; } catch (e) {}
    }
    if (!card) card = (a.parentElement && a.parentElement.parentElement) || a;
    return card;
  }
  function tiles(cfg, root) {
    root = root || document; var T = cfg.tiles || {};
    var map = {}, order = [];
    var links = root.querySelectorAll('a[href*="/dp/"],a[href*="/gp/product/"],a[href*="/gp/aw/d/"]');
    for (var i = 0; i < links.length && i < 600; i++) {
      var a = links[i]; var asin = asinOf(at(a, 'href')); if (!asin) continue;
      var card = cardFor(cfg, a);
      var t = map[asin];
      if (!t) { t = map[asin] = { asin: asin, url: abs('/dp/' + asin), title: '', image: '', price: '', rating: '', reviews: '', unitPrice: '' }; order.push(asin); }
      var im = a.querySelector('img') || card.querySelector('img');
      if (!t.image && im) t.image = imgOf(im);
      var ct = txt(card);
      if (!t.title) {
        var cand = at(im, 'alt');
        var lt = txt(a);
        if (lt.length > cand.length && lt.length < 300 && !/^\$/.test(lt) && !/out of 5/.test(lt)) cand = lt;
        var tt = q(card, T.title);
        if (tt && txt(tt).length > cand.length && txt(tt).length < 300 && !/^\$/.test(txt(tt))) cand = txt(tt);
        t.title = cand;
      }
      if (!t.price) t.price = qt(card, '.a-price .a-offscreen') || priceIn(ct);
      if (!t.unitPrice) t.unitPrice = unitIn(ct);
      if (!t.rating) {
        var r = card.querySelector('[aria-label*="out of 5"], [title*="out of 5"], .a-icon-alt');
        if (r) t.rating = at(r, 'aria-label') || at(r, 'title') || txt(r);
      }
      if (!t.reviews) {
        var rv = card.querySelector('[aria-label$="ratings"], [aria-label$="rating"]');
        if (rv) t.reviews = at(rv, 'aria-label');
        else {
          var rm = /\(([\d.,]+[KkMm]?\+?)\)/.exec(ct);
          if (rm) t.reviews = rm[1];
        }
      }
    }
    return order.map(function (k) { return map[k]; }).filter(function (t) { return t.title && t.title.length > 3; });
  }

  var EX = {};

  EX.search = function (cfg) {
    var S = cfg.search || {}; var out = []; var seen = {};
    qa(document, S.card).forEach(function (c) {
      var asin = at(c, 'data-asin'); if (!asin || seen[asin]) return; seen[asin] = 1;
      var a = q(c, S.link); var im = q(c, S.image); var ct = txt(c);
      var title = qt(c, S.title) || at(im, 'alt');
      var sp = !!q(c, S.sponsored);
      if (!sp) {
        var els = c.querySelectorAll('span,a');
        for (var i = 0; i < els.length && i < 400; i++) {
          if (els[i].childElementCount === 0 && txt(els[i]) === 'Sponsored') { sp = true; break; }
        }
      }
      var rv = q(c, S.reviews);
      out.push({
        asin: asin,
        title: title,
        url: a ? abs(at(a, 'href')) : abs('/dp/' + asin),
        price: qt(c, S.price) || priceIn(ct),
        unitPrice: unitIn(ct),
        rating: ratingOf(c, S.rating),
        reviews: rv ? (at(rv, 'aria-label') || txt(rv)) : '',
        image: imgOf(im),
        delivery: qt(c, S.delivery) || deliveryIn(ct),
        prime: !!q(c, S.prime),
        sponsored: sp,
        brand: qt(c, S.brand)
      });
    });
    return { items: out, hasNext: !!q(document, S.next) };
  };

  EX.product = function (cfg) {
    var P = cfg.product || {};
    var asin = asinOf(location.href) || at(document.querySelector('input#ASIN, input[name="ASIN"]'), 'value');
    var pbt = qt(document, P.priceBlock);
    var imgs = [];
    qall(document, P.images).forEach(function (e) { var s = imgOf(e); if (s && imgs.indexOf(s) < 0) imgs.push(s); });
    var hist = [];
    qa(document, P.histogramRow).forEach(function (r) {
      var t = at(r, 'aria-label') + ' ' + txt(r); var m = /(\d{1,3})\s*%/.exec(t); if (m) hist.push(parseInt(m[1], 10));
    });
    var details = [];
    qa(document, P.detailRows).forEach(function (r) {
      var th = r.querySelector('th'), td = r.querySelector('td'); var k = '', v = '';
      if (th && td) { k = txt(th); v = txt(td); }
      else {
        var b = r.querySelector('.a-text-bold');
        if (b) { k = txt(b); v = txt(r).replace(k, ''); }
        else {
          var tds = r.querySelectorAll('td');
          if (tds.length >= 2) { k = txt(tds[0]); v = txt(tds[1]); }
          else { var t = txt(r); var i = t.indexOf(':'); if (i > 0) { k = t.slice(0, i); v = t.slice(i + 1); } }
        }
      }
      k = k.replace(/[\s:\u200e\u200f]+$/, '').replace(/^[\s\u200e\u200f]+/, '');
      v = v.replace(/^[\s:\u200e\u200f]+/, '').trim();
      if (k && v && k.length < 60 && v.length < 300) details.push([k, v]);
    });
    var reviews = [];
    qa(document, P.review).slice(0, 12).forEach(function (r) {
      reviews.push({
        title: qt(r, P.reviewTitle).replace(/^\d(\.\d)? out of 5 stars\s*/, ''),
        body: qt(r, P.reviewBody).slice(0, 1500),
        rating: ratingOf(r, P.reviewRating),
        author: qt(r, P.reviewAuthor),
        date: qt(r, P.reviewDate)
      });
    });
    var variants = []; var seenV = {};
    var dimSel = arr(P.variantDim).join(',');
    qall(document, P.variantOption).forEach(function (o) {
      var link = o.querySelector('a');
      var va = at(o, 'data-asin') || at(o, 'data-defaultasin') || asinOf(at(o, 'data-dp-url')) || asinOf(at(link, 'href'));
      var lab = at(o, 'title').replace(/^Click to select\s*/i, '') || at(o.querySelector('img'), 'alt') || at(o, 'aria-label') || txt(o);
      lab = lab.replace(/\s+/g, ' ').trim(); if (!lab) return;
      var dimEl = null; try { dimEl = dimSel ? o.closest(dimSel) : null; } catch (e) {}
      var dim = dimEl ? qt(dimEl, P.variantDimTitle) : '';
      dim = dim.replace(/:.*$/, '').trim();
      var key = dim + '|' + lab; if (seenV[key]) return; seenV[key] = 1;
      var cls = String(o.className || '') + ' ' + at(o, 'aria-checked') + ' ' + at(o, 'aria-selected') + ' ' + at(o, 'data-initiallyselected');
      variants.push({ dim: dim, label: lab.slice(0, 80), asin: va, selected: /selected|true/i.test(cls), available: !/unavailable|disabled/i.test(cls) });
    });
    var rel = []; var seenR = {}; seenR[asin] = 1;
    qall(document, P.related).forEach(function (root) {
      tiles(cfg, root).forEach(function (t) { if (!seenR[t.asin]) { seenR[t.asin] = 1; rel.push(t); } });
    });
    return {
      asin: asin,
      title: qt(document, P.title),
      brand: qt(document, P.brand),
      price: qt(document, P.price) || priceIn(pbt),
      listPrice: qt(document, P.listPrice),
      unitPrice: unitIn(pbt),
      images: imgs.slice(0, 8),
      rating: ratingOf(document, P.rating),
      reviewCount: qt(document, P.reviewCount),
      histogram: hist.slice(0, 5),
      delivery: qt(document, P.delivery),
      soldBy: qt(document, P.soldBy),
      shipsFrom: qt(document, P.shipsFrom),
      returns: qt(document, P.returns),
      availability: qt(document, P.availability),
      bullets: qa(document, P.bullets).map(txt).filter(function (s) { return s.length > 1; }).slice(0, 15),
      details: details.slice(0, 40),
      reviews: reviews,
      breadcrumbs: qa(document, P.breadcrumbs).map(txt).filter(function (s) { return s.length > 0; }),
      variants: variants.slice(0, 60),
      related: rel.slice(0, 30),
      canAddToCart: !!q(document, P.addToCart),
      cartCount: qt(document, (cfg.common || {}).cartCount)
    };
  };

  EX.tiles = function (cfg) { return { items: tiles(cfg, document) }; };

  EX.orders = function (cfg) {
    var O = cfg.orders || {}; var out = [];
    qa(document, O.card).forEach(function (c) {
      var ct = txt(c);
      var dm = /(?:Order placed|ORDER PLACED)\s*([A-Z][a-z]+\.? \d{1,2}, \d{4})/.exec(ct) || /([A-Z][a-z]+ \d{1,2}, \d{4})/.exec(ct);
      var id = /\b\d{3}-\d{7}-\d{7}\b/.exec(ct);
      var returned = /Return (complete|started|received|in progress|requested|approved)|Refund(ed| issued| complete)|Item returned|\bReturned\b/i.test(ct);
      out.push({
        date: dm ? dm[1] : '',
        id: id ? id[0] : '',
        returned: returned,
        status: qt(c, O.status),
        items: tiles(cfg, c).map(function (t) { return { asin: t.asin, title: t.title, image: t.image, price: t.price }; })
      });
    });
    return { orders: out, hasNext: !!q(document, O.next) };
  };

  EX.lists = function (cfg) {
    var L = cfg.lists || {}; var lists = []; var seen = {};
    qall(document, L.listLink).forEach(function (a) {
      var m = /\/hz\/wishlist\/ls\/([A-Z0-9]+)/.exec(at(a, 'href'));
      if (!m || seen[m[1]]) return; seen[m[1]] = 1;
      var name = txt(a) || at(a, 'title');
      if (name) lists.push({ id: m[1], name: name.slice(0, 60), url: abs('/hz/wishlist/ls/' + m[1]) });
    });
    var items = []; var seenI = {};
    qa(document, L.item).forEach(function (li) {
      var a = q(li, L.itemTitle); var asin = asinOf(at(a, 'href'));
      if (!asin) { var pm = /ASIN:([A-Z0-9]{10})/.exec(at(li, 'data-reposition-action-params')); if (pm) asin = pm[1]; }
      if (!asin) { var l2 = li.querySelector('a[href*="/dp/"]'); if (l2) asin = asinOf(at(l2, 'href')); }
      if (!asin || seenI[asin]) return; seenI[asin] = 1;
      var price = at(li, 'data-price');
      if (price && price !== '-Infinity' && !isNaN(parseFloat(price))) price = '$' + parseFloat(price).toFixed(2);
      else price = qt(li, L.itemPrice) || priceIn(txt(li));
      var im = li.querySelector('img');
      items.push({ asin: asin, title: (a ? (at(a, 'title') || txt(a)) : '') || at(im, 'alt'), image: imgOf(im), price: price, url: abs('/dp/' + asin) });
    });
    if (!items.length) items = tiles(cfg, document);
    return { lists: lists, items: items, name: qt(document, L.listName) };
  };

  EX.cartcount = function (cfg) {
    var C = cfg.cart || {};
    var asins = qall(document, C.item).map(function (e) { return at(e, 'data-asin'); }).filter(Boolean);
    return { count: qt(document, (cfg.common || {}).cartCount), asins: asins };
  };

  EX.addtocart = function (cfg) {
    var P = cfg.product || {};
    var b = q(document, P.addToCart);
    if (!b) return { clicked: false, reason: 'No Add to cart button on this page' };
    var form = b.closest ? b.closest('form') : null;
    var sig = [b.id, at(b, 'name'), at(b, 'value'), at(b, 'aria-label'), at(b, 'title'), String(b.className || ''), txt(b)].join(' ');
    if (FORBIDDEN.test(sig)) return { clicked: false, reason: 'Refused: that button is not Add to cart' };
    if (!/cart/i.test(sig + ' ' + at(form, 'action'))) return { clicked: false, reason: 'Refused: button does not look like Add to cart' };
    b.click();
    return { clicked: true };
  };

  window.__cartwise = function (kind, cfg, id, arg) {
    var start = Date.now(); var sec = cfg[kind] || {}; var ready = sec.ready; var max = cfg.waitMs || 6000;
    function finish() {
      var r;
      try { r = EX[kind] ? EX[kind](cfg, arg) : { error: 'unknown kind ' + kind }; } catch (e) { r = { error: String((e && e.message) || e) }; }
      r.__url = location.href;
      r.__title = document.title;
      var head = '';
      try { head = (document.body ? document.body.innerText.slice(0, 600) : ''); } catch (e) {}
      r.__robot = /validateCaptcha|Robot Check|Type the characters you see/i.test(document.title + ' ' + head + ' ' + location.href);
      try { CartwiseBridge.post(id, JSON.stringify(r)); } catch (e) {}
    }
    (function wait() {
      if (!ready || q(document, ready) || Date.now() - start > max) finish();
      else setTimeout(wait, 250);
    })();
  };
})();
