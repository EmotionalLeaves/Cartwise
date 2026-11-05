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
  // Amazon swaps real photos in late; until then <img src> is a grey or transparent placeholder.
  var IMG_RE = /https?:\/\/[^"'\s,<>]*?\/images\/I\/[A-Za-z0-9+%\-]{6,}\.[^"'\s,<>]*?\.(?:jpe?g|png|webp)/gi;
  function goodImg(u) {
    return !!u && u.indexOf('data:') !== 0 && /\/images\/I\//.test(u) && !/\.gif(\?|$)/i.test(u) &&
      !/(grey-pixel|transparent-pixel|01MKUOLsA5L|11EIQ5IGqaL)/i.test(u);
  }
  function srcsetList(v) {
    if (!v) return [];
    return v.split(',').map(function (p) { return p.trim().split(/\s+/)[0]; }).filter(Boolean).reverse();
  }
  function imgOf(el, scope) {
    var c = [];
    if (el) {
      c.push(at(el, 'data-old-hires'), at(el, 'data-a-hires'));
      var dyn = at(el, 'data-a-dynamic-image');
      if (dyn) {
        try {
          var o = JSON.parse(dyn); var ks = Object.keys(o);
          ks.sort(function (a, b) { return ((o[b] && o[b][0]) || 0) - ((o[a] && o[a][0]) || 0); });
          c = c.concat(ks);
        } catch (e) {}
      }
      c = c.concat(srcsetList(at(el, 'srcset')), srcsetList(at(el, 'data-srcset')));
      c.push(at(el, 'data-src'), at(el, 'data-lazy-src'), at(el, 'data-image-src'), at(el, 'src'));
      var pic = el.closest ? el.closest('picture') : null;
      if (pic) qall(pic, 'source').forEach(function (s) { c = c.concat(srcsetList(at(s, 'srcset'))); });
    }
    for (var i = 0; i < c.length; i++) { var u = c[i] ? abs(String(c[i]).trim()) : ''; if (goodImg(u)) return u; }
    if (scope && scope.innerHTML) {
      IMG_RE.lastIndex = 0; var m;
      var html = scope.innerHTML;
      while ((m = IMG_RE.exec(html))) { var x = m[0].replace(/&amp;/g, '&'); if (goodImg(x)) return x; }
    }
    return '';
  }
  function bodyText(sels) {
    var el = q(document, sels); if (!el) return '';
    try { return String(el.innerText || el.textContent || '').replace(/\s+/g, ' '); } catch (e) { return txt(el); }
  }
  function textNodes(needle, limit) {
    var out = [];
    try {
      var x = document.evaluate("//body//text()[contains(., '" + needle.replace(/'/g, '') + "')]", document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
      for (var i = 0; i < x.snapshotLength && out.length < (limit || 10); i++) {
        var n = x.snapshotItem(i).parentElement;
        if (n && !n.closest('script,style,noscript')) out.push(n);
      }
    } catch (e) {}
    return out;
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
    return m ? m[0].replace(/\s+(Ships from|Sold by|Order within|Details|Returns|Or fastest).*$/i, '').trim() : '';
  }
  function cardFor(cfg, a, root) {
    var T = cfg.tiles || {}; var sels = arr(T.card); var card = null;
    var bounded = root && root !== document && root.contains;
    for (var i = 0; i < sels.length && !card; i++) {
      try {
        var c = a.closest(sels[i]);
        if (c && txt(c).length < 1500 && (!bounded || root.contains(c))) card = c;
      } catch (e) {}
    }
    if (!card) card = (a.parentElement && a.parentElement.parentElement) || a;
    if (bounded && !root.contains(card)) {
      var pe = a.parentElement;
      card = (pe && pe !== root && root.contains(pe)) ? pe : root;
    }
    return card;
  }
  function tiles(cfg, root) {
    root = root || document; var T = cfg.tiles || {};
    var map = {}, order = [];
    var links = root.querySelectorAll('a[href*="/dp/"],a[href*="/gp/product/"],a[href*="/gp/aw/d/"]');
    for (var i = 0; i < links.length && i < 600; i++) {
      var a = links[i]; var asin = asinOf(at(a, 'href')); if (!asin) continue;
      var card = cardFor(cfg, a, root);
      var t = map[asin];
      if (!t) { t = map[asin] = { asin: asin, url: abs('/dp/' + asin), title: '', image: '', price: '', listPrice: '', pctOff: 0, rating: '', reviews: '', unitPrice: '' }; order.push(asin); }
      var im = a.querySelector('img') || card.querySelector('img');
      if (!t.image) t.image = imgOf(im, card);
      var ct = txt(card);
      if (!t.title) {
        var cand = at(im, 'alt');
        var lt = txt(a);
        if (lt.length > cand.length && lt.length < 300 && !/^\$/.test(lt) && !/out of 5/.test(lt)) cand = lt;
        var tt = q(card, T.title);
        if (tt && txt(tt).length > cand.length && txt(tt).length < 300 && !/^\$/.test(txt(tt))) cand = txt(tt);
        t.title = cand;
      }
      if (!t.price) {
        var ps = qall(card, ['.a-price .a-offscreen', '.a-price', '[class*="price"]']).map(function (e) { return priceIn(txt(e)); }).filter(Boolean);
        if (!ps.length) { var all = (ct.match(/\$\s?\d[\d,]*(?:\.\d{2})?/g) || []); ps = all; }
        if (ps.length) {
          var nums = ps.map(function (x) { return parseFloat(x.replace(/[$,\s]/g, '')); }).filter(function (n) { return !isNaN(n) && n > 0; });
          if (nums.length) {
            var lo = Math.min.apply(null, nums), hi = Math.max.apply(null, nums);
            t.price = '$' + lo.toFixed(2);
            if (hi > lo * 1.02 && hi < lo * 20) t.listPrice = '$' + hi.toFixed(2);
          }
        }
        var po = /(\d{1,2})%\s*off/i.exec(ct) || /-\s?(\d{1,2})%/.exec(ct);
        if (po) t.pctOff = parseInt(po[1], 10);
      }
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
      // Phone layout puts the brand in its own heading; the title is the longest heading text.
      var heads = qall(c, S.title).map(txt).filter(function (t) { return t && t.length < 400; });
      var alt = at(im, 'alt').replace(/^Sponsored Ad\s*[-–—]\s*/i, '');
      var title = heads.concat([alt]).reduce(function (a, b) { return b.length > a.length ? b : a; }, '');
      var brandLine = '';
      heads.forEach(function (h) { if (h !== title && h.length < 40 && h.split(' ').length <= 4 && h.length > brandLine.length) brandLine = h; });
      var sp = !!q(c, S.sponsored);
      if (!sp) {
        var els = c.querySelectorAll('span,a');
        for (var i = 0; i < els.length && i < 400; i++) {
          if (els[i].childElementCount === 0 && txt(els[i]) === 'Sponsored') { sp = true; break; }
        }
      }
      var rv = q(c, S.reviews);
      var listPrice = qt(c, S.listPrice);
      if (!listPrice) { var lm = /(?:List|Typical|Was)(?: Price)?:?\s*(\$\s?\d[\d,]*(?:\.\d{2})?)/i.exec(ct); if (lm) listPrice = lm[1]; }
      var pm = /(\d{1,2})%\s*off/i.exec(ct) || /-\s?(\d{1,2})%/.exec(ct);
      out.push({
        asin: asin,
        title: title,
        url: a ? abs(at(a, 'href')) : abs('/dp/' + asin),
        price: qt(c, S.price) || priceIn(ct),
        unitPrice: unitIn(ct),
        listPrice: listPrice,
        pctOff: pm ? parseInt(pm[1], 10) : 0,
        rating: ratingOf(c, S.rating),
        reviews: rv ? (at(rv, 'aria-label') || txt(rv)) : '',
        image: imgOf(im, c),
        delivery: qt(c, S.delivery) || deliveryIn(ct),
        prime: !!q(c, S.prime),
        sponsored: sp,
        brand: qt(c, S.brand) || brandLine
      });
    });
    return { items: out, hasNext: !!q(document, S.next) || out.length >= 12 };
  };

  EX.product = function (cfg) {
    var P = cfg.product || {};
    var asin = asinOf(location.href) || at(document.querySelector('input#ASIN, input[name="ASIN"]'), 'value');
    var pbt = qt(document, P.priceBlock);
    var healed = [];
    // Page text is only measured when a field needs a fallback (it forces layout, which is slow).
    var mainT = null, buyT = null;
    function main() { if (mainT === null) mainT = bodyText(['#ppd', '#dp', '#a-page']); return mainT; }
    function buy() { if (buyT === null) buyT = bodyText(['#buybox', '#desktop_buybox', '#apex_offerDisplay_mobile', '#rightCol', '#ppd']) || main(); return buyT; }
    var imgs = [];
    qall(document, P.images).forEach(function (e) { var s = imgOf(e); if (s && imgs.indexOf(s) < 0) imgs.push(s); });
    if (!imgs.length) {
      var og = at(document.querySelector("meta[property='og:image']"), 'content');
      var s2 = goodImg(og) ? og : imgOf(null, q(document, ['#imageBlock', '#main-image-container', '#imageBlock_feature_div', '#ppd']));
      if (s2) { imgs.push(s2); healed.push('images:fallback'); }
    }
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
    // Category: breadcrumbs, else Best Sellers Rank, else "See Top 100 in", else the page title.
    var crumbs = qa(document, P.breadcrumbs).map(txt).filter(function (s) { return s.length > 0 && s.length < 60 && s !== '›'; });
    if (!crumbs.length) {
      var bsr = '';
      textNodes('Best Sellers Rank', 4).forEach(function (n) {
        var box = n.closest('li,tr,td') || n.parentElement || n; var t = '';
        try { t = String(box.innerText || box.textContent).replace(/\s+/g, ' '); } catch (e) { t = txt(box); }
        if (t.length > 600) t = t.slice(0, 600);
        if (!bsr || t.length > bsr.length) bsr = t;
      });
      var re = /#\s?[\d,]+\s+in\s+([^#(]+?)(?=\s*\(|\s*#|$)/g, m, cats = [];
      while ((m = re.exec(bsr))) { var cn = m[1].trim(); if (cn && cn.length < 60 && cats.indexOf(cn) < 0) cats.push(cn); }
      if (cats.length) { crumbs = cats.length > 1 ? [cats[0], cats[cats.length - 1]] : cats; healed.push('category:best-sellers-rank'); }
    }
    if (!crumbs.length) {
      qall(document, ['a[href*="/gp/bestsellers/"]']).forEach(function (a) {
        var mm = /Top 100 in (.+)$/i.exec(txt(a)); if (mm && crumbs.indexOf(mm[1].trim()) < 0) crumbs.push(mm[1].trim());
      });
      if (crumbs.length) healed.push('category:top-100-link');
    }
    if (!crumbs.length) {
      var parts = String(document.title || '').split(' : ');
      if (parts.length >= 3) { var last = parts[parts.length - 1].trim(); if (last && last.length < 50) { crumbs = [last]; healed.push('category:page-title'); } }
    }
    var title = qt(document, P.title);
    if (!title) {
      title = at(document.querySelector("meta[name='title']"), 'content') || String(document.title || '');
      title = title.replace(/^Amazon\.com\s*:\s*/i, '').split(' : ')[0].trim();
      if (title) healed.push('title:page-title');
    }
    var price = qt(document, P.price) || priceIn(pbt);
    if (!price) { price = priceIn(buy()); if (price) healed.push('price:buy-box-text'); }
    var rating = ratingOf(document, P.rating);
    if (!rating) { var rm = /(\d\.\d) out of 5 stars/.exec(main()); if (rm) { rating = rm[0]; healed.push('rating:text'); } }
    var reviewCount = qt(document, P.reviewCount);
    if (!reviewCount) { var cm = /([\d,]+)\s+(?:global\s+)?ratings?/i.exec(main()); if (cm) { reviewCount = cm[0]; healed.push('reviews:text'); } }
    var delivery = qt(document, P.delivery);
    if (!delivery) { delivery = deliveryIn(buy()); if (delivery) healed.push('delivery:text'); }
    var soldBy = qt(document, P.soldBy);
    if (!soldBy) { var sm = /Sold by\s+(.{2,60}?)(?=\s+(?:Ships from|Returns|Payment|Packaging|Gift|Details)|$)/i.exec(buy()); if (sm) { soldBy = sm[1]; healed.push('soldBy:text'); } }
    var shipsFrom = qt(document, P.shipsFrom);
    if (!shipsFrom) { var fm = /Ships from\s+(.{2,40}?)(?=\s+(?:Sold by|Returns|Payment|Packaging)|$)/i.exec(buy()); if (fm) shipsFrom = fm[1]; }
    var returns = qt(document, P.returns);
    if (!returns) { var rt = /((?:FREE\s+)?(?:\d+-day\s+)?(?:refund\/replacement|returns?)[^.]{0,60})/i.exec(buy()); if (rt) returns = rt[1]; }
    var cartBtn = q(document, P.addToCart) || document.querySelector("form[action*='add-to-cart'] input[type='submit'], form[action*='add-to-cart'] button");
    return {
      asin: asin,
      title: title,
      brand: qt(document, P.brand),
      price: price,
      listPrice: qt(document, P.listPrice) || (function () { var m = /(?:List|Typical|Was)(?: Price)?:?\s*(\$\s?\d[\d,]*(?:\.\d{2})?)/i.exec(pbt); return m ? m[1] : ''; })(),
      pctOff: (function () { var m = /-\s?(\d{1,2})%/.exec(pbt) || /(\d{1,2})%\s*off/i.exec(pbt); return m ? parseInt(m[1], 10) : 0; })(),
      unitPrice: unitIn(pbt),
      images: imgs.slice(0, 8),
      rating: rating,
      reviewCount: reviewCount,
      histogram: hist.slice(0, 5),
      delivery: delivery,
      soldBy: soldBy,
      shipsFrom: shipsFrom,
      returns: returns,
      availability: qt(document, P.availability),
      bullets: qa(document, P.bullets).map(txt).filter(function (s) { return s.length > 1; }).slice(0, 15),
      details: details.slice(0, 40),
      reviews: reviews,
      breadcrumbs: crumbs,
      variants: variants.slice(0, 60),
      related: rel.slice(0, 30),
      canAddToCart: !!cartBtn,
      healed: healed,
      cartCount: qt(document, (cfg.common || {}).cartCount)
    };
  };

  EX.tiles = function (cfg) { return { items: tiles(cfg, document) }; };

  EX.orders = function (cfg) {
    var O = cfg.orders || {}; var out = [];
    qa(document, O.card).forEach(function (c) {
      var ct = txt(c);
      var dm = /(?:Order placed|ORDER PLACED)\s*([A-Z][a-z]+\.? \d{1,2}, \d{4})/.exec(ct) || /([A-Z][a-z]+ \d{1,2}, \d{4})/.exec(ct);
      var id = /\d{3}-\d{7}-\d{7}(?!\d)/.exec(ct);
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
      items.push({ asin: asin, title: (a ? (at(a, 'title') || txt(a)) : '') || at(im, 'alt'), image: imgOf(im, li), price: price, url: abs('/dp/' + asin) });
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
    var b = q(document, P.addToCart) || document.querySelector("form[action*='add-to-cart'] input[type='submit'], form[action*='add-to-cart'] button");
    if (!b) return { clicked: false, reason: 'No Add to cart button on this page' };
    var form = b.closest ? b.closest('form') : null;
    var sig = [b.id, at(b, 'name'), at(b, 'value'), at(b, 'aria-label'), at(b, 'title'), String(b.className || ''), txt(b)].join(' ');
    if (FORBIDDEN.test(sig)) return { clicked: false, reason: 'Refused: that button is not Add to cart' };
    if (!/cart/i.test(sig + ' ' + at(form, 'action'))) return { clicked: false, reason: 'Refused: button does not look like Add to cart' };
    b.click();
    return { clicked: true };
  };

  // A small, targeted page report for fixing selectors.json from a phone: no nav, ads, scripts or tracking.
  // arg = {"missing": ["category", ...], "max": 15000}
  var SKIP = 'script,style,noscript,svg,iframe,link,template,canvas,#nav-main,#navbar,header,[id^="ape_"],.ape-wrapper,[data-csa-c-painter],#rhf,#navFooter,#nav-flyout-rufus,#skiplink';
  var KEEP_ATTR = /^(id|class|href|aria-label|title|name|value|type|action|data-asin|data-hook|data-cy|data-component-type|data-feature-name|data-a-strike|data-price|tabular-attribute-name)$/;
  function cleanHtml(el, maxLen) {
    var c = el.cloneNode(true);
    var drop = c.querySelectorAll(SKIP);
    for (var i = 0; i < drop.length; i++) if (drop[i].parentNode) drop[i].parentNode.removeChild(drop[i]);
    var all = [c].concat(Array.prototype.slice.call(c.querySelectorAll('*')));
    all.forEach(function (n) {
      var names = []; for (var j = 0; j < n.attributes.length; j++) names.push(n.attributes[j].name);
      names.forEach(function (a) {
        if (a === 'src' && goodImg(n.getAttribute('src'))) return;
        if (!KEEP_ATTR.test(a)) { n.removeAttribute(a); return; }
        var v = n.getAttribute(a) || '';
        if (a === 'class') { v = v.split(/\s+/).filter(function (x) { return x && !/^(a-spacing|a-size|a-color|aok-|celwidget|a-text-)/.test(x); }).slice(0, 4).join(' '); n.setAttribute(a, v); }
        else if (a === 'href') n.setAttribute(a, v.replace(/[?&].*$/, '').replace(/\/ref=.*$/, '').slice(0, 80));
        else if (v.length > 100) n.setAttribute(a, v.slice(0, 100));
      });
    });
    var h = c.outerHTML.replace(/<!--[\s\S]*?-->/g, '').replace(/\s{2,}/g, ' ').replace(/>\s+</g, '><');
    return h.length > maxLen ? h.slice(0, maxLen) + '…' : h;
  }
  function blockFor(n, maxLen) {
    var b = n, best = n;
    while (b && b.parentElement && b !== document.body) {
      b = b.parentElement;
      if (cleanHtml(b, 99999).length > maxLen) break;
      best = b;
    }
    return best;
  }
  var HINTS = {
    category: { text: ['Best Sellers Rank', 'See Top 100', 'Back to results'], sel: ['#wayfinding-breadcrumbs_feature_div', '.a-breadcrumb', '[id*="breadcrumb"]', 'a[href*="/gp/bestsellers/"]'] },
    title: { text: [], sel: ['h1', '#title', '[id*="Title"]', "meta[name='title']"] },
    price: { text: [], sel: ['[id*="price" i]', '.a-price', '[id*="Price"]'] },
    images: { text: [], sel: ['#imageBlock', '#main-image-container', '[id*="image" i] img', 'img[src*="/images/I/"]'] },
    rating: { text: ['out of 5 stars'], sel: ['#averageCustomerReviews', '[id*="acr"]'] },
    reviews: { text: ['ratings'], sel: ['#acrCustomerReviewText'] },
    delivery: { text: ['delivery', 'Arrives'], sel: ['[id*="DELIVERY"]', '[id*="delivery" i]'] },
    'sold by': { text: ['Sold by', 'Ships from'], sel: ['#merchant-info', '[id*="merchant" i]', '.tabular-buybox-text'] },
    'add to cart': { text: ['Add to Cart', 'Add to cart'], sel: ['[id*="add-to-cart"]', "form[action*='cart']"] },
    title_s: { text: [], sel: ['h2'] },
    price_s: { text: [], sel: ['.a-price'] },
    image: { text: [], sel: ['img.s-image', 'img'] },
    unit: { text: ['/Count)', '/Ounce)'], sel: [] },
    items: { text: [], sel: ['[data-asin]', '[data-component-type]'] },
    date: { text: ['Order placed', 'ORDER PLACED'], sel: ['.order-card', '.js-order-card'] },
    lists: { text: [], sel: ['a[href*="/hz/wishlist/ls/"]'] }
  };
  EX.snapshot = function (cfg, arg) {
    var o = {}; try { o = JSON.parse(arg || '{}'); } catch (e) {}
    var max = o.max || 15000;
    var missing = o.missing || [];
    var out = [];
    var used = 0;
    var seen = [];
    function add(s) { if (used + s.length > max) return false; out.push(s); used += s.length + 1; return true; }
    missing.forEach(function (f) {
      var h = HINTS[f] || HINTS[f + '_s'] || { text: [f], sel: [] };
      add('## Where "' + f + '" might be');
      var nodes = [];
      h.text.forEach(function (t) { nodes = nodes.concat(textNodes(t, 3)); });
      h.sel.forEach(function (sel) { try { var r = document.querySelectorAll(sel); for (var i = 0; i < r.length && i < 3; i++) nodes.push(r[i]); } catch (e) {} });
      var n = 0;
      nodes.forEach(function (node) {
        if (n >= 4) return;
        var b = blockFor(node, 1400);
        if (seen.indexOf(b) >= 0) return; seen.push(b);
        if (add(cleanHtml(b, 1400))) n++;
      });
      if (!n) add('(nothing on the page mentions it)');
    });
    add('## Page title: ' + document.title);
    add('## Outline of the main content (tag#id.classes "text")');
    var root = q(document, ['#dp', '#search', '#ppd', '.your-orders-content-container', '#wishlist-page', '#a-page']) || document.body;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, {
      acceptNode: function (n) {
        try { if (n.matches(SKIP)) return NodeFilter.FILTER_REJECT; } catch (e) {}
        return (n.id || n.getAttribute('data-component-type') || n.getAttribute('data-hook') || n.getAttribute('data-cy')) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
    var lines = 0;
    while (walker.nextNode() && lines < 400) {
      var e = walker.currentNode;
      var cls = String(e.className && e.className.baseVal === undefined ? e.className : '').split(/\s+/).filter(function (x) { return x && !/^(a-spacing|a-size|a-color|aok-|celwidget)/.test(x); }).slice(0, 3);
      var extra = ['data-component-type', 'data-hook', 'data-cy'].map(function (a) { var v = e.getAttribute(a); return v ? '[' + a + '=' + v + ']' : ''; }).join('');
      var t = txt(e).slice(0, 50);
      if (!add(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (cls.length ? '.' + cls.join('.') : '') + extra + (t ? ' "' + t + '"' : ''))) break;
      lines++;
    }
    var full = 0; try { full = document.documentElement.outerHTML.length; } catch (e) {}
    return { html: out.join('\n'), length: full };
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
      var parsed = document.readyState !== 'loading';
      if ((parsed && (!ready || q(document, ready))) || Date.now() - start > max) finish();
      else setTimeout(wait, 100);
    })();
  };
})();
