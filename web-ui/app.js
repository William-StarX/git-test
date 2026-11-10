(function () {
  'use strict';

  var DRAFT_KEY = 'xc_draft_v1';
  var LATEST_KEY = 'xc_latest_order_v1';
  var CUSTOMER_KEY = 'xc_customer_v1';
  var FETCH_TIMEOUT = 8000;

  var state = {
    menu: [],
    cart: {},
    draft: null,
    posting: false,
    lookupSeq: 0,
    pollTimer: null,
    currentOrderId: null
  };

  var memStore = {};
  function storeGet(k) {
    try { var v = window.localStorage.getItem(k); if (v !== null) return v; } catch (e) {}
    return Object.prototype.hasOwnProperty.call(memStore, k) ? memStore[k] : null;
  }
  function storeSet(k, v) {
    try { window.localStorage.setItem(k, v); } catch (e) {}
    memStore[k] = v;
  }
  function storeDel(k) {
    try { window.localStorage.removeItem(k); } catch (e) {}
    delete memStore[k];
  }

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      try { return window.crypto.randomUUID(); } catch (e) {}
    }
    var b = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(b);
    else for (var i = 0; i < 16; i++) b[i] = Math.floor(Math.random() * 256);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = [];
    for (var j = 0; j < 16; j++) h.push((b[j] + 0x100).toString(16).slice(1));
    return h.slice(0, 4).join('') + '-' + h.slice(4, 6).join('') + '-' + h.slice(6, 8).join('') + '-' + h.slice(8, 10).join('') + '-' + h.slice(10, 16).join('');
  }

  function customerId() {
    var id = storeGet(CUSTOMER_KEY);
    if (!id) { id = uuid(); storeSet(CUSTOMER_KEY, id); }
    return id;
  }

  function money(cents) {
    return '¥' + (cents / 100).toFixed(2);
  }

  function fetchJson(url, opts) {
    opts = opts || {};
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, FETCH_TIMEOUT);
    if (ctrl) opts.signal = ctrl.signal;
    return fetch(url, opts).then(function (res) {
      clearTimeout(timer);
      return res.text().then(function (txt) {
        var data = null;
        if (txt) { try { data = JSON.parse(txt); } catch (e) { data = null; } }
        return { ok: res.ok, status: res.status, data: data };
      });
    }, function (err) {
      clearTimeout(timer);
      throw err;
    });
  }

  var el = {};
  function $(id) { return document.getElementById(id); }

  function loadDraft() {
    var raw = storeGet(DRAFT_KEY);
    if (!raw) return null;
    try {
      var d = JSON.parse(raw);
      if (d && d.key && d.body) return d;
    } catch (e) {}
    return null;
  }

  function saveDraft(d) {
    state.draft = d;
    storeSet(DRAFT_KEY, JSON.stringify(d));
  }

  function clearDraft() {
    state.draft = null;
    storeDel(DRAFT_KEY);
  }

  function renderMenu() {
    var list = el.menuList;
    list.textContent = '';
    state.menu.forEach(function (item) {
      var card = document.createElement('div');
      card.className = 'menu-card';
      card.setAttribute('data-testid', 'menu-item');
      card.setAttribute('data-id', item.id);

      var name = document.createElement('h3');
      name.className = 'menu-name';
      name.textContent = item.name;

      var meta = document.createElement('p');
      meta.className = 'menu-meta';
      meta.textContent = '现点现做';

      var price = document.createElement('p');
      price.className = 'menu-price';
      price.textContent = money(item.priceCents);

      var stock = document.createElement('span');
      stock.className = 'stock-tag';
      stock.textContent = '库存 ' + (item.available == null ? '—' : item.available);

      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-add';
      btn.setAttribute('data-testid', 'add-to-cart');
      btn.textContent = '加入购物车';
      btn.addEventListener('click', function () { addToCart(item.id); });

      card.appendChild(name);
      card.appendChild(meta);
      card.appendChild(price);
      card.appendChild(stock);
      card.appendChild(btn);
      list.appendChild(card);
    });
  }

  function findMenu(id) {
    const target = String(id);
    for (let i = 0; i < state.menu.length; i++) {
      if (String(state.menu[i].id) === target) {
        return state.menu[i];
      }
    }
    return null;
  }
  function addToCart(id) {
    if (state.draft) return;
    var item = findMenu(id);
    if (!item) return;
    var cur = state.cart[id] || 0;
    if (item.available != null && cur >= item.available) return;
    state.cart[id] = cur + 1;
    renderCart();
  }

  function changeQty(id, delta) {
    if (state.draft) return;
    var cur = state.cart[id] || 0;
    var next = cur + delta;
    var item = findMenu(id);
    if (next <= 0) { delete state.cart[id]; }
    else if (item && item.available != null && next > item.available) { return; }
    else { state.cart[id] = next; }
    renderCart();
  }

  function cartTotal() {
    var total = 0;
    Object.keys(state.cart).forEach(function (id) {
      var item = findMenu(id);
      if (item) total += item.priceCents * state.cart[id];
    });
    return total;
  }

  function renderCart() {
    var box = el.cart;
    box.textContent = '';
    var ids = Object.keys(state.cart);
    ids.forEach(function (id) {
      var item = findMenu(id);
      if (!item) return;
      var qty = state.cart[id];
      var row = document.createElement('div');
      row.className = 'cart-row';

      var name = document.createElement('span');
      name.className = 'cart-name';
      name.textContent = item.name;

      var minus = document.createElement('button');
      minus.type = 'button';
      minus.className = 'qty-btn';
      minus.textContent = '−';
      minus.setAttribute('aria-label', '减少 ' + item.name);
      minus.disabled = !!state.draft;
      minus.addEventListener('click', function () { changeQty(id, -1); });

      var qtyEl = document.createElement('span');
      qtyEl.className = 'cart-qty';
      qtyEl.textContent = String(qty);

      var plus = document.createElement('button');
      plus.type = 'button';
      plus.className = 'qty-btn';
      plus.textContent = '+';
      plus.setAttribute('aria-label', '增加 ' + item.name);
      plus.disabled = !!state.draft;
      plus.addEventListener('click', function () { changeQty(id, 1); });

      var line = document.createElement('span');
      line.className = 'cart-line-total';
      line.textContent = money(item.priceCents * qty);

      row.appendChild(name);
      row.appendChild(minus);
      row.appendChild(qtyEl);
      row.appendChild(plus);
      row.appendChild(line);
      box.appendChild(row);
    });
    el.cartSummary.textContent = '合计：' + money(cartTotal());
    updateSubmitState();
  }

  function updateSubmitState() {
    var hasItems = Object.keys(state.cart).length > 0;
    el.placeOrder.disabled = state.posting || !hasItems;
    if (state.draft) {
      el.placeOrder.textContent = state.posting ? '提交中…' : '重试提交';
    } else {
      el.placeOrder.textContent = state.posting ? '提交中…' : '提交订单';
    }
  }

  function feedback(msg) {
    el.orderFeedback.textContent = msg || '';
  }

  function buildBody() {
    const items = Object.keys(state.cart).map(function (cartKey) {
      return {
        menuItemId: Number(cartKey),
        quantity: state.cart[cartKey]
      };
    });
    return {
      customerId: customerId(),
      items: items
    };
  }
  function submitOrder() {
    if (state.posting) return;
    var draft = state.draft;
    if (!draft) {
      if (Object.keys(state.cart).length === 0) return;
      draft = { key: uuid(), body: buildBody() };
      saveDraft(draft);
    }
    state.posting = true;
    updateSubmitState();
    feedback('正在提交订单…');

    fetchJson('/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': draft.key },
      body: JSON.stringify(draft.body)
    }).then(function (res) {
      state.posting = false;
      if (res.ok && res.data && res.data.id) {
        clearDraft();
        state.cart = {};
        renderCart();
        feedback('下单成功，订单号 ' + res.data.id);
        storeSet(LATEST_KEY, res.data.id);
        showOrder(res.data);
        refreshStocks();
        return;
      }
      if (res.status === 400 || res.status === 404 || res.status === 409) {
        clearDraft();
        feedback('订单未能提交（' + res.status + '），请检查后重试。');
        updateSubmitState();
        return;
      }
      feedback('网络异常，订单已保存，可点击重试。');
      updateSubmitState();
    }, function () {
      state.posting = false;
      feedback('网络异常，订单已保存，可点击重试。');
      updateSubmitState();
    });
  }

  function statusText(s) {
    if (s === 'PENDING') return '待确认';
    if (s === 'CONFIRMED') return '已确认';
    if (s === 'CANCELLED') return '已取消';
    return s || '';
  }

  function renderOrder(order) {
    el.orderPanel.hidden = false;
    el.orderIdDisplay.textContent = order.id;
    el.orderStatus.textContent = statusText(order.status);
    el.orderItems.textContent = '';
    (order.items || []).forEach(function (it) {
      var li = document.createElement('li');
      li.textContent = it.name + ' × ' + it.quantity + ' — ' + money(it.unitPriceCents * it.quantity);
      el.orderItems.appendChild(li);
    });
    el.orderTotal.textContent = money(order.totalCents || 0);
    el.cancelOrder.hidden = order.status !== 'PENDING' && order.status !== 'CONFIRMED';
  }

  function showOrder(order) {
    state.currentOrderId = order.id;
    renderOrder(order);
    schedulePoll(order);
  }

  function schedulePoll(order) {
    if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
    if (order.status !== 'PENDING') return;
    var seq = state.lookupSeq;
    var id = order.id;
    state.pollTimer = setTimeout(function () {
      state.pollTimer = null;
      if (seq !== state.lookupSeq || state.currentOrderId !== id) return;
      fetchJson('/api/orders/' + encodeURIComponent(id)).then(function (res) {
        if (seq !== state.lookupSeq || state.currentOrderId !== id) return;
        if (res.ok && res.data) { renderOrder(res.data); schedulePoll(res.data); }
      }, function () {});
    }, 3000);
  }

  function lookupOrder(id) {
    state.lookupSeq++;
    var seq = state.lookupSeq;
    if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
    state.currentOrderId = id;
    feedback('正在查询订单…');
    fetchJson('/api/orders/' + encodeURIComponent(id)).then(function (res) {
      if (seq !== state.lookupSeq) return;
      if (res.ok && res.data) {
        feedback('');
        storeSet(LATEST_KEY, res.data.id);
        showOrder(res.data);
      } else {
        feedback('未找到该订单。');
      }
    }, function () {
      if (seq !== state.lookupSeq) return;
      feedback('查询失败，请稍后重试。');
    });
  }

  function cancelOrder() {
    var id = state.currentOrderId;
    if (!id) return;
    state.lookupSeq++;
    var seq = state.lookupSeq;
    if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
    el.cancelOrder.disabled = true;
    fetchJson('/api/orders/' + encodeURIComponent(id) + '/cancel', { method: 'POST' }).then(function (res) {
      el.cancelOrder.disabled = false;
      if (seq !== state.lookupSeq) return;
      if (res.ok && res.data) {
        renderOrder(res.data);
        feedback('订单已取消。');
        refreshStocks();
      } else {
        feedback('取消失败，请稍后重试。');
      }
    }, function () {
      el.cancelOrder.disabled = false;
      if (seq !== state.lookupSeq) return;
      feedback('取消失败，请稍后重试。');
    });
  }

  function refreshStocks() {
    return Promise.all(state.menu.map(function (item) {
      return fetchJson('/api/menu/' + encodeURIComponent(item.id) + '/stock').then(function (res) {
        if (res.ok && res.data && typeof res.data.available === 'number') item.available = res.data.available;
      }, function () {});
    })).then(function () { renderMenu(); });
  }

  function loadMenu() {
    return fetchJson('/api/menu').then(function (res) {
      if (!res.ok || !Array.isArray(res.data)) throw new Error('menu');
      state.menu = res.data.map(function (m) {
        return { id: m.id, name: m.name, priceCents: m.priceCents, available: null };
      });
      el.menuStatus.textContent = '';
      renderMenu();
      return refreshStocks();
    }, function () {
      el.menuStatus.textContent = '菜单加载失败，请刷新重试。';
    });
  }

  function restoreDraft() {
    var draft = loadDraft();
    if (!draft) return;
    state.draft = draft;
    state.cart = {};
    (draft.body.items || []).forEach(function (it) {
      state.cart[it.menuItemId] = it.quantity;
    });
    feedback('检测到未完成的订单，已恢复购物车，可点击重试提交。');
  }

  function init() {
    el.menuStatus = $('menu-status');
    el.menuList = $('menu-list');
    el.cart = $('cart');
    el.cartSummary = $('cart-summary');
    el.placeOrder = $('place-order');
    el.orderFeedback = $('order-feedback');
    el.lookupForm = $('lookup-form');
    el.orderIdInput = $('order-id-input');
    el.orderPanel = $('order-panel');
    el.orderStatus = $('order-status');
    el.orderIdDisplay = $('order-id-display');
    el.orderItems = $('order-items');
    el.orderTotal = $('order-total');
    el.cancelOrder = $('cancel-order');

    el.placeOrder.addEventListener('click', submitOrder);
    el.cancelOrder.addEventListener('click', cancelOrder);
    el.lookupForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var id = el.orderIdInput.value.trim();
      if (id) lookupOrder(id);
    });

    restoreDraft();
    renderCart();

    loadMenu().then(function () {
      renderCart();
      var latest = storeGet(LATEST_KEY);
      if (latest) { el.orderIdInput.value = latest; lookupOrder(latest); }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
