(function () {
  "use strict";

  var STORAGE_KEY = "xiangkou.order.draft.v1";
  var LAST_ORDER_KEY = "xiangkou.order.last.v1";
  var CUSTOMER_KEY = "xiangkou.customer.v1";
  var POLL_MS = 5000;

  var state = {
    menu: [],
    stock: {},
    cart: {},
    draft: null,
    currentOrder: null,
    pollTimer: null,
    placing: false,
    cancelling: false
  };

  var el = {};

  function $(id) { return document.getElementById(id); }

  function cacheEls() {
    el.menuList = $("menu-list");
    el.menuStatus = $("menu-status");
    el.cart = $("cart");
    el.placeOrder = $("place-order");
    el.orderFeedback = $("order-feedback");
    el.lookupForm = $("lookup-form");
    el.orderIdInput = $("order-id-input");
    el.orderPanel = $("order-panel");
    el.orderStatus = $("order-status");
    el.orderIdDisplay = $("order-id-display");
    el.orderItems = $("order-items");
    el.orderTotal = $("order-total");
    el.cancelOrder = $("cancel-order");
    el.orderFeedback = $("order-feedback");
  }

  function safeGet(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }
  function safeSet(key, val) {
    try { window.localStorage.setItem(key, val); } catch (e) { /* ignore */ }
  }
  function safeRemove(key) {
    try { window.localStorage.removeItem(key); } catch (e) { /* ignore */ }
  }

  function formatPrice(cents) {
    var n = Number(cents);
    if (!isFinite(n)) { return "—"; }
    return "¥" + (n / 100).toFixed(2);
  }

  function statusLabel(status) {
    switch (status) {
      case "PENDING": return "待确认";
      case "CONFIRMED": return "已确认";
      case "CANCELLED": return "已取消";
      default: return status || "未知";
    }
  }

  function statusClass(status) {
    switch (status) {
      case "PENDING": return "pending";
      case "CONFIRMED": return "confirmed";
      case "CANCELLED": return "cancelled";
      default: return "";
    }
  }

  function getCustomerId() {
    var existing = safeGet(CUSTOMER_KEY);
    if (existing && /^[A-Za-z0-9-]{1,64}$/.test(existing)) { return existing; }
    var id = generateId();
    safeSet(CUSTOMER_KEY, id);
    return id;
  }

  function generateId() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return window.crypto.randomUUID();
    }
    var bytes = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < 16; i++) { bytes[i] = Math.floor(Math.random() * 256); }
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = [];
    for (var j = 0; j < 16; j++) { hex.push((bytes[j] + 0x100).toString(16).slice(1)); }
    return hex.slice(0, 4).join("") + "-" + hex.slice(4, 6).join("") + "-" +
      hex.slice(6, 8).join("") + "-" + hex.slice(8, 10).join("") + "-" + hex.slice(10, 16).join("");
  }

  function isValidUuid(v) {
    return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(v);
  }

  function setFeedback(node, msg, kind) {
    if (!node) { return; }
    node.textContent = msg || "";
    node.className = "feedback" + (kind ? " " + kind : "");
  }

  function setMenuStatus(msg, isError) {
    el.menuStatus.textContent = msg || "";
    el.menuStatus.className = "state-line" + (isError ? " error" : "");
  }

  /* ---------- API ---------- */

  function apiFetch(path, options) {
    var opts = options || {};
    return fetch(path, {
      method: opts.method || "GET",
      headers: opts.headers || {},
      body: opts.body,
      credentials: "same-origin"
    }).then(function (res) {
      var ct = res.headers.get("content-type") || "";
      var parse = ct.indexOf("application/json") !== -1 ? res.json() : res.text();
      return parse.then(function (data) {
        if (!res.ok) {
          var err = new Error("HTTP " + res.status);
          err.status = res.status;
          err.data = data;
          throw err;
        }
        return data;
      }, function () {
        if (!res.ok) {
          var err2 = new Error("HTTP " + res.status);
          err2.status = res.status;
          throw err2;
        }
        return null;
      });
    });
  }

  function loadMenu() {
    setMenuStatus("正在加载菜单…", false);
    el.menuList.textContent = "";
    return apiFetch("/api/menu").then(function (data) {
      var list = Array.isArray(data) ? data : (data && Array.isArray(data.items) ? data.items : []);
      state.menu = list.filter(function (m) {
        return m && m.id != null && typeof m.name === "string" && typeof m.priceCents === "number";
      });
      if (state.menu.length === 0) {
        setMenuStatus("今日菜单暂未上架，请稍后再来。", false);
      } else {
        setMenuStatus("", false);
      }
      renderMenu();
      loadAllStock();
    }).catch(function () {
      setMenuStatus("菜单加载失败，请检查网络后重试。", true);
      renderMenuError();
    });
  }

  function loadAllStock() {
    state.menu.forEach(function (m) { loadStock(m.id); });
  }

  function loadStock(id) {
    state.stock[id] = { loading: true };
    updateStockTag(id);
    apiFetch("/api/menu/" + encodeURIComponent(id) + "/stock").then(function (data) {
      var available = data && typeof data.available === "number" ? data.available : null;
      state.stock[id] = { loading: false, available: available, error: false };
      updateStockTag(id);
      reconcileCartWithStock(id);
    }).catch(function () {
      state.stock[id] = { loading: false, available: null, error: true };
      updateStockTag(id);
    });
  }

  function stockInfo(id) {
    return state.stock[id] || { loading: true };
  }

  /* ---------- Menu render ---------- */

  function renderMenu() {
    el.menuList.textContent = "";
    state.menu.forEach(function (item) {
      var li = document.createElement("li");
      li.className = "menu-card";
      li.setAttribute("data-testid", "menu-item");
      li.setAttribute("data-id", String(item.id));

      var name = document.createElement("h3");
      name.className = "menu-name";
      name.textContent = item.name;

      var meta = document.createElement("div");
      meta.className = "menu-meta";

      var price = document.createElement("span");
      price.className = "menu-price";
      price.textContent = formatPrice(item.priceCents);

      var tag = document.createElement("span");
      tag.className = "stock-tag unknown";
      tag.id = "stock-" + item.id;
      tag.textContent = "库存加载中";

      meta.appendChild(price);
      meta.appendChild(tag);

      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-add";
      btn.setAttribute("data-testid", "add-to-cart");
      btn.setAttribute("data-id", String(item.id));
      btn.textContent = "加入餐篮";
      btn.addEventListener("click", function () { addToCart(item.id); });

      li.appendChild(name);
      li.appendChild(meta);
      li.appendChild(btn);
      el.menuList.appendChild(li);
      updateStockTag(item.id);
    });
  }

  function renderMenuError() {
    el.menuList.textContent = "";
    var li = document.createElement("li");
    li.className = "menu-card";
    var p = document.createElement("p");
    p.textContent = "菜单暂时无法显示。";
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn btn-secondary";
    btn.textContent = "重新加载";
    btn.addEventListener("click", loadMenu);
    li.appendChild(p);
    li.appendChild(btn);
    el.menuList.appendChild(li);
  }

  function updateStockTag(id) {
    var tag = document.getElementById("stock-" + id);
    if (!tag) { return; }
    var info = stockInfo(id);
    var card = tag.closest(".menu-card");
    var btn = card ? card.querySelector('[data-testid="add-to-cart"]') : null;
    if (info.loading) {
      tag.className = "stock-tag unknown";
      tag.textContent = "库存加载中";
      if (btn) { btn.disabled = true; }
      return;
    }
    if (info.error || info.available == null) {
      tag.className = "stock-tag unknown";
      tag.textContent = "库存未知";
      if (btn) { btn.disabled = false; }
      return;
    }
    if (info.available <= 0) {
      tag.className = "stock-tag out";
      tag.textContent = "已售完";
      if (card) { card.classList.add("sold-out"); }
      if (btn) { btn.disabled = true; }
    } else {
      tag.className = "stock-tag" + (info.available <= 3 ? " low" : "");
      tag.textContent = "剩余 " + info.available + " 份";
      if (card) { card.classList.remove("sold-out"); }
      if (btn) { btn.disabled = false; }
    }
  }

  /* ---------- Cart ---------- */

  function findMenuItem(id) {
    for (var i = 0; i < state.menu.length; i++) {
      if (String(state.menu[i].id) === String(id)) { return state.menu[i]; }
    }
    return null;
  }

  function addToCart(id) {
    var item = findMenuItem(id);
    if (!item) { return; }
    var info = stockInfo(id);
    var current = state.cart[id] || 0;
    if (info.available != null && current + 1 > info.available) {
      setFeedback(el.orderFeedback, "「" + item.name + "」库存不足，无法再加。", "error");
      return;
    }
    state.cart[id] = current + 1;
    setFeedback(el.orderFeedback, "", "");
    renderCart();
  }

  function changeQty(id, delta) {
    var item = findMenuItem(id);
    var current = state.cart[id] || 0;
    var next = current + delta;
    if (next <= 0) {
      delete state.cart[id];
    } else {
      var info = stockInfo(id);
      if (info.available != null && next > info.available) {
        setFeedback(el.orderFeedback, "「" + (item ? item.name : "该菜品") + "」库存不足。", "error");
        return;
      }
      state.cart[id] = next;
    }
    renderCart();
  }

  function reconcileCartWithStock(id) {
    var info = stockInfo(id);
    if (info.available == null) { return; }
    if (state.cart[id] && state.cart[id] > info.available) {
      if (info.available <= 0) {
        delete state.cart[id];
      } else {
        state.cart[id] = info.available;
      }
      renderCart();
    }
  }

  function cartEntries() {
    var entries = [];
    Object.keys(state.cart).forEach(function (id) {
      var item = findMenuItem(id);
      if (item && state.cart[id] > 0) {
        entries.push({ item: item, quantity: state.cart[id] });
      }
    });
    return entries;
  }

  function cartTotalCents() {
    return cartEntries().reduce(function (sum, e) {
      return sum + e.item.priceCents * e.quantity;
    }, 0);
  }

  function renderCart() {
    el.cart.textContent = "";
    var entries = cartEntries();
    if (entries.length === 0) {
      var empty = document.createElement("p");
      empty.className = "cart-empty";
      empty.textContent = "餐篮还是空的，挑几样喜欢的吧。";
      el.cart.appendChild(empty);
      el.placeOrder.disabled = true;
      return;
    }

    var ul = document.createElement("ul");
    ul.className = "cart-list";
    entries.forEach(function (e) {
      var li = document.createElement("li");
      li.className = "cart-row";

      var name = document.createElement("span");
      name.className = "cart-name";
      name.textContent = e.item.name;

      var qty = document.createElement("div");
      qty.className = "cart-qty";

      var minus = document.createElement("button");
      minus.type = "button";
      minus.className = "qty-btn";
      minus.textContent = "−";
      minus.setAttribute("aria-label", "减少 " + e.item.name + " 数量");
      minus.addEventListener("click", function () { changeQty(e.item.id, -1); });

      var count = document.createElement("span");
      count.textContent = String(e.quantity);
      count.setAttribute("aria-label", e.item.name + " 数量 " + e.quantity);

      var plus = document.createElement("button");
      plus.type = "button";
      plus.className = "qty-btn";
      plus.textContent = "+";
      plus.setAttribute("aria-label", "增加 " + e.item.name + " 数量");
      plus.addEventListener("click", function () { changeQty(e.item.id, 1); });

      qty.appendChild(minus);
      qty.appendChild(count);
      qty.appendChild(plus);

      var lineTotal = document.createElement("span");
      lineTotal.className = "cart-line-total";
      lineTotal.textContent = formatPrice(e.item.priceCents * e.quantity);

      li.appendChild(name);
      li.appendChild(qty);
      li.appendChild(lineTotal);
      ul.appendChild(li);
    });

    el.cart.appendChild(ul);

    var summary = document.createElement("div");
    summary.className = "cart-summary";
    var label = document.createElement("span");
    label.textContent = "合计";
    var total = document.createElement("span");
    total.textContent = formatPrice(cartTotalCents());
    summary.appendChild(label);
    summary.appendChild(total);
    el.cart.appendChild(summary);

    el.placeOrder.disabled = state.placing;
  }

  /* ---------- Order creation (idempotent) ---------- */

  function buildOrderBody() {
    var entries = cartEntries();
    return {
      customerId: getCustomerId(),
      items: entries.map(function (e) {
        return { menuItemId: e.item.id, quantity: e.quantity };
      })
    };
  }

  function bodySignature(body) {
    return JSON.stringify(body);
  }

  function getOrCreateDraft() {
    var body = buildOrderBody();
    var sig = bodySignature(body);
    var raw = safeGet(STORAGE_KEY);
    if (raw) {
      try {
        var parsed = JSON.parse(raw);
        if (parsed && parsed.key && parsed.signature === sig) {
          return { key: parsed.key, body: body, signature: sig };
        }
      } catch (e) { /* ignore */ }
    }
    var draft = { key: generateId(), body: body, signature: sig };
    safeSet(STORAGE_KEY, JSON.stringify(draft));
    return draft;
  }

  function clearDraft() {
    safeRemove(STORAGE_KEY);
    state.draft = null;
  }

  function placeOrder() {
    if (state.placing) { return; }
    var entries = cartEntries();
    if (entries.length === 0) {
      setFeedback(el.orderFeedback, "餐篮是空的，请先选择菜品。", "error");
      return;
    }

    var draft = getOrCreateDraft();
    state.draft = draft;
    state.placing = true;
    el.placeOrder.disabled = true;
    el.placeOrder.textContent = "下单中…";
    setFeedback(el.orderFeedback, "正在提交订单…", "");

    apiFetch("/api/orders", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": draft.key
      },
      body: JSON.stringify(draft.body)
    }).then(function (order) {
      state.placing = false;
      el.placeOrder.textContent = "下单";
      if (!order || order.id == null) {
        throw new Error("bad response");
      }
      clearDraft();
      state.cart = {};
      renderCart();
      safeSet(LAST_ORDER_KEY, String(order.id));
      setFeedback(el.orderFeedback, "下单成功！订单号 " + order.id, "success");
      showOrder(order);
      loadAllStock();
    }).catch(function (err) {
      state.placing = false;
      el.placeOrder.textContent = "下单";
      el.placeOrder.disabled = false;
      var msg = "下单失败，请重试。";
      if (err && err.status === 409) {
        msg = "库存不足，请调整餐篮后重试。";
        loadAllStock();
      } else if (err && err.status === 400) {
        msg = "订单信息有误，请检查后重试。";
      }
      setFeedback(el.orderFeedback, msg + "（重试不会重复下单）", "error");
    });
  }

  /* ---------- Order lookup / status ---------- */

  function showOrder(order) {
    if (!order || order.id == null) { return; }
    state.currentOrder = order;
    el.orderPanel.hidden = false;
    el.orderIdDisplay.textContent = "订单号：" + order.id;

    var status = order.status || "";
    el.orderStatus.textContent = statusLabel(status);
    el.orderStatus.className = "status-badge " + statusClass(status);

    el.orderItems.textContent = "";
    var items = Array.isArray(order.items) ? order.items : [];
    items.forEach(function (it) {
      var li = document.createElement("li");
      var nameSpan = document.createElement("span");
      var name = it.name || (findMenuItem(it.menuItemId) ? findMenuItem(it.menuItemId).name : "菜品");
      nameSpan.textContent = name + " × " + (it.quantity != null ? it.quantity : 1);
      var priceSpan = document.createElement("span");
      if (typeof it.priceCents === "number") {
        priceSpan.textContent = formatPrice(it.priceCents * (it.quantity || 1));
      }
      li.appendChild(nameSpan);
      li.appendChild(priceSpan);
      el.orderItems.appendChild(li);
    });

    if (typeof order.totalCents === "number") {
      el.orderTotal.textContent = "合计 " + formatPrice(order.totalCents);
    } else {
      el.orderTotal.textContent = "";
    }

    var cancellable = status === "PENDING" || status === "CONFIRMED";
    el.cancelOrder.hidden = !cancellable;
    el.cancelOrder.disabled = state.cancelling;

    schedulePoll(status);
  }

  function schedulePoll(status) {
    if (state.pollTimer) {
      clearTimeout(state.pollTimer);
      state.pollTimer = null;
    }
    if (status === "PENDING" && state.currentOrder) {
      var id = state.currentOrder.id;
      state.pollTimer = setTimeout(function () {
        fetchOrder(id, true);
      }, POLL_MS);
    }
  }

  function fetchOrder(id, silent) {
    if (!silent) {
      setFeedback(el.orderFeedback, "正在查询订单…", "");
    }
    return apiFetch("/api/orders/" + encodeURIComponent(id)).then(function (order) {
      if (!order || order.id == null) { throw new Error("bad response"); }
      if (!silent) { setFeedback(el.orderFeedback, "", ""); }
      showOrder(order);
      safeSet(LAST_ORDER_KEY, String(order.id));
    }).catch(function (err) {
      if (err && err.status === 404) {
        if (!silent) { setFeedback(el.orderFeedback, "未找到该订单，请核对订单号。", "error"); }
        el.orderPanel.hidden = true;
        state.currentOrder = null;
        if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
      } else if (!silent) {
        setFeedback(el.orderFeedback, "查询失败，请稍后重试。", "error");
      }
    });
  }

  function lookupOrder(evt) {
    if (evt) { evt.preventDefault(); }
    var raw = (el.orderIdInput.value || "").trim();
    if (!raw) {
      setFeedback(el.orderFeedback, "请输入订单号。", "error");
      return;
    }
    if (!isValidUuid(raw)) {
      setFeedback(el.orderFeedback, "订单号格式不正确，应为 36 位 UUID。", "error");
      return;
    }
    fetchOrder(raw, false);
  }

  function cancelOrder() {
    if (state.cancelling || !state.currentOrder) { return; }
    var id = state.currentOrder.id;
    state.cancelling = true;
    el.cancelOrder.disabled = true;
    el.cancelOrder.textContent = "取消中…";
    setFeedback(el.orderFeedback, "正在取消订单…", "");

    apiFetch("/api/orders/" + encodeURIComponent(id) + "/cancel", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({})
    }).then(function (order) {
      state.cancelling = false;
      el.cancelOrder.textContent = "取消订单";
      if (order && order.id != null) {
        showOrder(order);
      } else {
        fetchOrder(id, false);
      }
      setFeedback(el.orderFeedback, "订单已取消。", "success");
    }).catch(function (err) {
      state.cancelling = false;
      el.cancelOrder.textContent = "取消订单";
      el.cancelOrder.disabled = false;
      if (err && err.status === 409) {
        setFeedback(el.orderFeedback, "该订单当前状态无法取消。", "error");
        fetchOrder(id, true);
      } else {
        setFeedback(el.orderFeedback, "取消失败，请稍后重试。", "error");
      }
    });
  }

  /* ---------- Init ---------- */

  function restoreLastOrder() {
    var last = safeGet(LAST_ORDER_KEY);
    if (last && isValidUuid(last)) {
      el.orderIdInput.value = last;
      fetchOrder(last, true);
    }
  }

  function init() {
    cacheEls();
    el.placeOrder.addEventListener("click", placeOrder);
    el.lookupForm.addEventListener("submit", lookupOrder);
    el.cancelOrder.addEventListener("click", cancelOrder);
    renderCart();
    loadMenu();
    restoreLastOrder();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
