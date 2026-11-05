// Regenerates app/src/test/resources/fixtures by running extractor.js on mock Amazon-like pages in headless Chromium.
// Usage: node tools/make-fixtures.js app/src/main/assets/extractor.js config/selectors.json app/src/test/resources/fixtures
const { chromium } = require('playwright');
const fs = require('fs');
const ex = fs.readFileSync(process.argv[2], 'utf8');
const cfg = fs.readFileSync(process.argv[3], 'utf8');
const pages = {
search: `<html><body>
<div data-component-type="s-search-result" data-asin="B0AAAAAAA1"><div><img class="s-image" src="https://m.media-amazon.com/images/I/71abcDEF._AC_UY218_.jpg" alt="Anker USB C Cable 6ft">
<div data-cy="title-recipe"><h2><a href="/Anker-Cable/dp/B0AAAAAAA1/ref=sr_1_1"><span>Anker USB C to USB C Cable, 6ft, 100W Fast Charging</span></a></h2></div>
<span aria-label="4.7 out of 5 stars"><i class="a-icon a-icon-star-small"><span class="a-icon-alt">4.7 out of 5 stars</span></i></span>
<span aria-label="45,123 ratings"><span class="s-underline-text">45,123</span></span>
<div data-cy="price-recipe"><span class="a-price"><span class="a-offscreen">$12.99</span></span> <span class="a-size-base a-color-secondary">($6.50/Count)</span></div>
<div data-cy="delivery-recipe">FREE delivery Tomorrow, Nov 6</div><i class="a-icon-prime"></i></div></div>
<div data-component-type="s-search-result" data-asin="B0AAAAAAA2"><img class="s-image" src="https://m.media-amazon.com/images/I/61xyz._AC_.jpg">
<span class="puis-sponsored-label-text">Sponsored</span><h2><a href="/dp/B0AAAAAAA2"><span>XYZBRAND USB C Cable 3 Pack</span></a></h2>
<span class="a-price"><span class="a-offscreen">$8.49</span></span><div>FREE delivery Mon, Nov 10</div></div>
<a class="s-pagination-next" href="/s?k=x&page=2">Next</a>
</body></html>`,
product: `<html><head><title>Product</title></head><body><input id="ASIN" value="B0AAAAAAA1">
<div id="wayfinding-breadcrumbs_feature_div"><a>Electronics</a><a>Computers &amp; Accessories</a><a>Cables</a></div>
<span id="productTitle"> Anker USB C Cable 6ft </span><a id="bylineInfo">Visit the Anker Store</a>
<div id="corePriceDisplay_mobile_feature_div"><span class="priceToPay"><span class="a-offscreen">$12.99</span></span> ($6.50 / Count)<span class="basisPrice"><span class="a-offscreen">$15.99</span></span></div>
<img id="landingImage" data-old-hires="https://m.media-amazon.com/images/I/71abcDEF._SL1500_.jpg" src="x.jpg">
<span id="acrPopover" title="4.7 out of 5 stars"></span><span id="acrCustomerReviewText">45,123 ratings</span>
<table id="histogramTable"><tr aria-label="80 percent of reviews have 5 stars"><td>80%</td></tr><tr><td>10%</td></tr><tr><td>5%</td></tr><tr><td>2%</td></tr><tr><td>3%</td></tr></table>
<div id="mir-layout-DELIVERY_BLOCK">FREE delivery Friday, November 7</div>
<div id="merchantInfoFeature_feature_div"><span class="offer-display-feature-text">Amazon.com</span></div>
<div id="fulfillerInfoFeature_feature_div"><span class="offer-display-feature-text">Amazon</span></div>
<div id="feature-bullets"><ul><li>Fast charging</li><li>Durable braid</li></ul></div>
<table id="productDetails_techSpec_section_1"><tr><th>Brand</th><td>Anker</td></tr></table>
<div id="twister-plus-inline-twister"><div id="inline-twister-row-size_name"><span class="inline-twister-dim-title">Size: 6ft</span><ul><li data-asin="B0AAAAAAA1" class="selected"><span>6ft</span></li><li data-asin="B0AAAAAAA3"><span>10ft</span></li></ul></div></div>
<div data-hook="review"><a data-hook="review-title">4.0 out of 5 stars Great cable</a><i data-hook="review-star-rating"><span class="a-icon-alt">4.0 out of 5 stars</span></i><span data-hook="review-body">Works well.</span><span class="a-profile-name">Sam</span></div>
<div id="sims-fbt"><ul><li><a href="/dp/B0BBBBBBB1"><img alt="Anker Charger 65W" src="https://m.media-amazon.com/images/I/5c.jpg"></a><span class="a-price"><span class="a-offscreen">$29.99</span></span></li></ul></div>
<span id="nav-cart-count">2</span>
<form action="/cart/add-to-cart/ref=dp_start"><input id="add-to-cart-button" name="submit.add-to-cart" value="Add to Cart" type="submit"></form>
<input id="buy-now-button" name="submit.buy-now" value="Buy Now">
</body></html>`,
searchMobile: `<html><body>
<div data-component-type="s-search-result" data-asin="B0SOUND001"><div class="puis-card">
<img class="s-image" src="https://m.media-amazon.com/images/I/01MKUOLsA5L._AC_UL320_.gif" data-src="https://m.media-amazon.com/images/I/71SoundBarA._AC_UY218_.jpg" alt="Sponsored Ad - TCL 2.1 Channel Sound Bar with Wireless Subwoofer">
<div data-cy="title-recipe"><h2 class="a-size-mini"><span>TCL</span></h2><a href="/dp/B0SOUND001"><h2 class="a-size-base-plus"><span>TCL 2.1 Channel Sound Bar with Wireless Subwoofer, Dolby Audio</span></h2></a></div>
<span aria-label="4.4 out of 5 stars"></span><span aria-label="824 ratings"></span><span class="a-price"><span class="a-offscreen">$119.99</span></span><div data-cy="delivery-recipe">FREE delivery Tomorrow</div></div></div>
<div data-component-type="s-search-result" data-asin="B0SOUND002"><img class="s-image" src="data:image/gif;base64,R0lGOD" srcset="https://m.media-amazon.com/images/I/61LGbar._AC_UY218_.jpg 1x, https://m.media-amazon.com/images/I/61LGbar._AC_UY436_.jpg 2x">
<h2><span>LG</span></h2><h2><a href="/dp/B0SOUND002"><span>LG S40T 2.1 Sound Bar with Subwoofer</span></a></h2><span class="a-price"><span class="a-offscreen">$169.99</span></span></div>
</body></html>`,
productMobile: `<html><head><title>Amazon.com: Burr Coffee Grinder : Home &amp; Kitchen</title></head><body><div id="ppd"><input id="ASIN" value="B01GGKZ2SC">
<span id="title">Burr Coffee Grinder, Electric</span>
<img id="landingImage" src="https://m.media-amazon.com/images/I/01MKUOLsA5L.gif" data-a-dynamic-image='{"https://m.media-amazon.com/images/I/71grinder._AC_SX300_.jpg":[300,300],"https://m.media-amazon.com/images/I/71grinder._AC_SL1500_.jpg":[1500,1500]}'>
<div id="buybox">$49.99 FREE delivery Saturday, November 8 Ships from Amazon Sold by Amazon.com Returns FREE 30-day refund/replacement
<form action="/cart/add-to-cart/ref=dp"><input type="submit" name="submit.add-to-cart" value="Add to Cart"></form></div>
<span>4.5 out of 5 stars</span><span>12,345 ratings</span>
<div id="productDetails_feature_div"><ul><li><span class="a-text-bold">Best Sellers Rank:</span> #1,234 in Kitchen &amp; Dining (<a href="/gp/bestsellers/kitchen/ref=pd_zg_ts_kitchen">See Top 100 in Kitchen &amp; Dining</a>) #12 in Burr Coffee Grinders</li></ul></div>
</div></body></html>`,
evil: `<html><body><input id="add-to-cart-button" name="placeYourOrder1" value="Place your order"></body></html>`,
orders: `<html><body><div class="order-card"><span>Order placed</span> <span>March 3, 2025</span> <span>Order # 112-1234567-1234567</span><div class="delivery-box__primary-text">Return complete</div><a href="/gp/product/B0CCCCCCC1"><img alt="Coffee beans 2lb" src="i.jpg"></a><a href="/gp/product/B0CCCCCCC1">Coffee beans 2lb</a><a>Return or replace items</a></div>
<div class="order-card"><span>Order placed</span> <span>January 9, 2025</span><span>113-7654321-7654321</span><div class="delivery-box__primary-text">Delivered Jan 11</div><a href="/gp/product/B0CCCCCCC2">Paper towels 12 pack</a><a>Return or replace items</a></div></body></html>`,
lists: `<html><body><div id="your-lists-nav"><a href="/hz/wishlist/ls/ABC123?ref_=lst">Shopping List</a><a href="/hz/wishlist/ls/DEF456">Gifts</a></div><h1 id="profile-list-name">Shopping List</h1>
<ul id="g-items"><li data-itemid="I1" data-price="24.5"><img src="a.jpg"><a id="itemName_I1" href="/dp/B0DDDDDDD1" title="Desk lamp">Desk lamp</a></li></ul></body></html>`,
};
(async () => {
  const b = await chromium.launch();

  for (const [kind, html] of [['search','searchMobile'],['product','productMobile'],['snapshot','productMobile'],['search','search'],['product','product'],['addtocart','product'],['addtocart','evil'],['orders','orders'],['lists','lists'],['cartcount','product']]) {
    const p = await b.newPage(); await p.setContent(pages[html]);
    const r = await p.evaluate(([ex, cfg, kind]) => new Promise(res => {
      window.CartwiseBridge = { post: (id, j) => res(j) };
      eval(ex + "\n;window.__cartwise(" + JSON.stringify(kind) + "," + cfg + ",'id1'," + JSON.stringify(kind === 'snapshot' ? JSON.stringify({missing: ['category', 'price'], max: 15000}) : '') + ");");
    }), [ex, cfg, kind]);
    console.log('=== ' + kind + ' on ' + html + '\n' + r.slice(0, 300)); if (process.argv[4]) fs.writeFileSync(process.argv[4] + '/' + kind + '_' + html + '.json', r);
  }
  await b.close();
})();
