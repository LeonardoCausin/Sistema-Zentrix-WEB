(function () {
  "use strict";

  const SESSION_KEY = "zentrix-session";
  const DEFAULT_TIMEOUT_MS = 15000;
  let paymentStatusTimer = 0;

  function readSessionRaw() {
    try {
      return sessionStorage.getItem(SESSION_KEY);
    } catch (error) {
      return null;
    }
  }

  function readSession() {
    try {
      return JSON.parse(readSessionRaw() || "null");
    } catch (error) {
      clearSession();
      return null;
    }
  }

  function clearSession() {
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch (error) {
      // Mantem navegacao funcional mesmo quando sessionStorage esta bloqueado.
    }
    try {
      localStorage.removeItem(SESSION_KEY);
    } catch (error) {
      // Mantem navegacao funcional mesmo quando localStorage esta bloqueado.
    }
  }

  function loginPath() {
    return location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html";
  }

  function apiBase() {
    if (window.ZentrixApiBase && typeof window.ZentrixApiBase.getApiBase === "function") {
      return window.ZentrixApiBase.getApiBase();
    }
    return "/api";
  }

  async function request(path, options, context) {
    const requestOptions = { ...(options || {}) };
    const timeoutMs = Number(requestOptions.timeoutMs || DEFAULT_TIMEOUT_MS);
    delete requestOptions.timeoutMs;
    const session = context && context.session ? context.session : readSession();
    const base = context && context.apiBase ? context.apiBase : apiBase();
    const headers = {
      "Content-Type": "application/json",
      Authorization: session ? "Bearer " + session.token : "",
      ...(requestOptions.headers || {})
    };
    let response;
    try {
      response = await fetchWithTimeout(base + path, {
        ...requestOptions,
        headers,
        credentials: "include"
      }, timeoutMs);
    } catch (error) {
      throw friendlyConnectionError(error);
    }

    if (response.status === 401) {
      clearSession();
      window.location.replace((context && context.loginPath) || loginPath());
      throw new Error("Sua sessão expirou. Entre novamente.");
    }
    if (!response.ok) {
      const details = await errorDetails(response);
      const error = new Error(details.message || "Não conseguimos carregar as informações agora. Tente novamente.");
      error.status = response.status;
      error.reasonCode = details.reasonCode;
      if (response.status === 402) {
        showAccountBlocked(error.message, error.reasonCode);
      }
      throw error;
    }
    if (response.status === 204) {
      return null;
    }
    const contentType = response.headers.get("Content-Type") || "";
    if (!contentType.toLowerCase().includes("application/json")) {
      return response.text();
    }
    return response.json();
  }

  async function fetchWithTimeout(url, options, timeoutMs) {
    if (!window.AbortController || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      return fetch(url, options);
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, { ...options, signal: controller.signal });
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function friendlyConnectionError(error) {
    if (error && error.name === "AbortError") {
      return new Error("A resposta demorou mais que o esperado. Confira a conexão e tente novamente.");
    }
    return new Error("Não conseguimos conectar ao Zentrix agora. Verifique a internet ou se o servidor está ligado.");
  }

  async function errorDetails(response) {
    try {
      const body = await response.json();
      return {
        message: friendlyServerMessage(body.message || body.error || body.detail || ""),
        reasonCode: String(body.reasonCode || "").trim().toUpperCase()
      };
    } catch (error) {
      return { message: "", reasonCode: "" };
    }
  }

  function friendlyServerMessage(message) {
    const text = String(message || "").trim();
    if (!text) return "";
    if (text.toLowerCase().includes("invalid cors request")) {
      return "Este endereço ainda não foi liberado para acessar o sistema. Peça ao responsável para liberar o domínio no Zentrix Web.";
    }
    return text;
  }

  function showAccountBlocked(message, reasonCode) {
    const safeMessage = friendlyServerMessage(message)
      || "A assinatura desta loja precisa ser regularizada para acessar o painel.";
    const normalizedCode = accountRestrictionCode(reasonCode, safeMessage);
    const expired = normalizedCode === "PAYMENT_EXPIRED";
    const upgrade = normalizedCode === "PLAN_UPGRADE_REQUIRED";
    const title = expired ? "Pagamento expirado" : upgrade ? "Plano sem acesso ao AppGestão" : "Loja bloqueada";
    const kicker = expired ? "Assinatura vencida" : upgrade ? "Alteração de plano necessária" : "Acesso interrompido";
    const paymentLabel = expired ? "Prosseguir para pagamento" : upgrade ? "Ver planos" : "Pagar assinatura";
    let blocker = document.getElementById("zentrixAccountBlocked");
    if (!blocker) {
      blocker = document.createElement("section");
      blocker.id = "zentrixAccountBlocked";
      blocker.className = "account-blocked-screen";
      blocker.setAttribute("role", "alertdialog");
      blocker.setAttribute("aria-modal", "true");
      document.body.appendChild(blocker);
    }
    blocker.innerHTML = `
      <div class="account-blocked-card">
        <span class="account-blocked-kicker">${escapeHtml(kicker)}</span>
        <h1>${escapeHtml(title)}</h1>
        <p>${escapeHtml(safeMessage)}</p>
        <div class="account-blocked-actions">
          <button class="button btn-primary" type="button" data-account-blocked-payment>${escapeHtml(paymentLabel)}</button>
          <button class="button btn-dark" type="button" data-account-blocked-check>Consultar pagamento</button>
          <button class="button btn-dark" type="button" data-account-blocked-logout>Sair do painel</button>
        </div>
      </div>
    `;
    document.body.classList.add("account-blocked");
    const paymentButton = blocker.querySelector("[data-account-blocked-payment]");
    if (paymentButton) {
      paymentButton.addEventListener("click", async () => {
        if (upgrade) {
          await openBillingPortal();
          return;
        }
        if (typeof window.ZentrixStartPayment === "function") {
          window.ZentrixStartPayment({ reasonCode: normalizedCode, message: safeMessage });
          return;
        }
        const paymentUrl = window.ZentrixPaymentUrl || "";
        if (paymentUrl) {
          window.location.href = paymentUrl;
          return;
        }
        const originalLabel = paymentButton.textContent;
        paymentButton.disabled = true;
        paymentButton.textContent = "Preparando pagamento...";
        try {
          const checkout = await request("/billing/checkout", { method: "POST" });
          const checkoutUrl = safeAsaasCheckoutUrl(checkout && checkout.checkoutUrl);
          if (!checkoutUrl) {
            throw new Error("Nao recebemos um link de pagamento valido. Tente novamente.");
          }
          window.open(checkoutUrl, "_blank", "noopener,noreferrer");
          startPaymentStatusPolling();
        } catch (error) {
          window.alert(error && error.message
            ? error.message
            : "Nao foi possivel iniciar o pagamento agora. Tente novamente.");
          paymentButton.disabled = false;
          paymentButton.textContent = originalLabel;
        }
      });
    }
    const checkButton = blocker.querySelector("[data-account-blocked-check]");
    if (checkButton) {
      checkButton.addEventListener("click", () => checkPaymentStatus(checkButton));
    }
    const logoutButton = blocker.querySelector("[data-account-blocked-logout]");
    if (logoutButton) {
      logoutButton.addEventListener("click", () => {
        clearSession();
        window.location.replace(loginPath());
      });
    }
    startPaymentStatusPolling();
  }

  function startPaymentStatusPolling() {
    window.clearInterval(paymentStatusTimer);
    paymentStatusTimer = window.setInterval(() => checkPaymentStatus(null, true), 15000);
  }

  async function checkPaymentStatus(button, silent) {
    const original = button && button.textContent;
    if (button) {
      button.disabled = true;
      button.textContent = "Consultando...";
    }
    try {
      const portal = await request("/billing/portal", { method: "GET" });
      const access = String(portal && portal.license && portal.license.accessStatus || "").toUpperCase();
      const invoice = String(portal && portal.currentInvoice && portal.currentInvoice.status || "").toUpperCase();
      if (["ACTIVE", "TRIAL"].includes(access) && ["CONFIRMED", "RECEIVED", "NONE"].includes(invoice)) {
        window.clearInterval(paymentStatusTimer);
        window.location.reload();
        return;
      }
      if (!silent) window.alert("O pagamento ainda nao foi confirmado. A consulta sera repetida automaticamente.");
    } catch (error) {
      if (!silent) window.alert(error.message || "Nao foi possivel consultar o pagamento agora.");
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = original;
      }
    }
  }

  function installBillingPortal() {
    if (!document.body.classList.contains("is-authenticated") || document.querySelector("[data-open-billing-portal]")) return;
    const toolbar = document.querySelector(".window-toolbar") || document.querySelector(".topbar-tools");
    if (!toolbar) return;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "button btn-dark billing-portal-button";
    button.dataset.openBillingPortal = "true";
    button.textContent = "Assinatura";
    button.addEventListener("click", openBillingPortal);
    const logout = toolbar.querySelector('a[href*="index.html"]');
    toolbar.insertBefore(button, logout || null);
  }

  async function openBillingPortal() {
    let modal = document.getElementById("zentrixBillingPortal");
    if (!modal) {
      modal = document.createElement("section");
      modal.id = "zentrixBillingPortal";
      modal.className = "billing-portal-overlay";
      modal.setAttribute("role", "dialog");
      modal.setAttribute("aria-modal", "true");
      document.body.appendChild(modal);
    }
    modal.hidden = false;
    modal.innerHTML = '<div class="billing-portal"><div class="billing-portal-loading">Carregando assinatura...</div></div>';
    try {
      const data = await request("/billing/portal", { method: "GET" });
      renderBillingPortal(modal, data || {});
    } catch (error) {
      modal.innerHTML = `<div class="billing-portal"><header><h2>Assinatura</h2><button type="button" data-close-billing aria-label="Fechar">x</button></header><p>${escapeHtml(error.message)}</p></div>`;
      wireBillingPortal(modal);
    }
  }

  function renderBillingPortal(modal, data) {
    const billing = data.billing || {};
    const license = data.license || {};
    const invoices = Array.isArray(data.invoices) ? data.invoices : [];
    const devices = Array.isArray(data.devices) ? data.devices : [];
    const notifications = Array.isArray(data.notifications) ? data.notifications : [];
    modal.innerHTML = `
      <div class="billing-portal">
        <header><div><span class="billing-eyebrow">Conta Zentrix</span><h2>Assinatura e pagamentos</h2><p>${escapeHtml(data.companyName || "Cliente")}</p></div><button type="button" data-close-billing aria-label="Fechar">x</button></header>
        <div class="billing-summary-band">
          <div><span>Plano</span><strong>${escapeHtml(data.plan || license.planName || "BASICO")}</strong></div>
          <div><span>Status</span><strong>${escapeHtml(license.accessStatus || license.status || "-")}</strong></div>
          <div><span>Vencimento</span><strong>${formatDate(license.expiresAt)}</strong></div>
          <div><span>Mensalidade atual</span><strong>${formatMoney(billing.monthlyTotal)}</strong></div>
        </div>
        <section class="billing-section">
          <div class="billing-section-title"><div><h3>Composicao do plano</h3><p>A cobranca acompanha lojas e aplicativos faturaveis.</p></div><button class="button btn-primary" type="button" data-new-checkout>Gerar pagamento</button></div>
          <div class="billing-breakdown">
            <div><span>Lojas ativas</span><strong>${escapeHtml(billing.activeStores || 0)}</strong><small>${formatMoney(billing.storeSubtotal)}</small></div>
            <div><span>PDVs instalados</span><strong>${escapeHtml(billing.pdvApps || 0)}</strong><small>${escapeHtml(billing.extraPdvApps || 0)} adicional(is) a ${formatMoney(billing.extraPdvPrice)}</small></div>
            <div><span>AppGestao</span><strong>${escapeHtml(billing.appGestaoApps || 0)}</strong><small>${billing.appGestaoIncluded ? "Incluido no plano" : "Requer upgrade"}</small></div>
          </div>
        </section>
        <section class="billing-section"><h3>Cobrancas recentes</h3>${billingInvoiceTable(invoices)}</section>
        <section class="billing-section"><h3>Aplicativos faturaveis</h3>${billingDeviceTable(devices)}</section>
        <section class="billing-section"><h3>Avisos</h3>${notifications.length ? `<div class="billing-notices">${notifications.map((item) => `<div><strong>${escapeHtml(item.title || item.type || "Aviso")}</strong><span>${escapeHtml(item.message || "")}</span></div>`).join("")}</div>` : '<p class="billing-empty">Nenhum aviso pendente.</p>'}</section>
      </div>`;
    wireBillingPortal(modal);
  }

  function billingInvoiceTable(rows) {
    if (!rows.length) return '<p class="billing-empty">Nenhuma cobranca emitida.</p>';
    return `<div class="billing-table-wrap"><table><thead><tr><th>Vencimento</th><th>Plano</th><th>Valor</th><th>Status</th><th></th></tr></thead><tbody>${rows.map((row) => {
      const url = safeAsaasCheckoutUrl(row.checkoutUrl);
      return `<tr><td>${formatDate(row.dueDate)}</td><td>${escapeHtml(row.planName || "-")}</td><td>${formatMoney(row.amount)}</td><td>${escapeHtml(row.status || "-")}</td><td>${url ? `<a class="button btn-dark" href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">Pagar</a>` : "-"}</td></tr>`;
    }).join("")}</tbody></table></div>`;
  }

  function billingDeviceTable(rows) {
    if (!rows.length) return '<p class="billing-empty">Nenhum aplicativo identificado.</p>';
    return `<div class="billing-table-wrap"><table><thead><tr><th>Aplicativo</th><th>Loja</th><th>Tipo</th><th>Faturavel</th><th>Ultimo acesso</th></tr></thead><tbody>${rows.map((row) => `<tr><td>${escapeHtml(row.name || row.id)}</td><td>${escapeHtml(row.storeId || "-")}</td><td>${escapeHtml(row.appType || "PDV")}</td><td>${row.billable === false ? "Nao" : "Sim"}</td><td>${formatDate(row.lastSeenAt)}</td></tr>`).join("")}</tbody></table></div>`;
  }

  function wireBillingPortal(modal) {
    const close = modal.querySelector("[data-close-billing]");
    if (close) close.onclick = () => { modal.hidden = true; };
    modal.onclick = (event) => { if (event.target === modal) modal.hidden = true; };
    const checkout = modal.querySelector("[data-new-checkout]");
    if (checkout) checkout.onclick = async () => {
      checkout.disabled = true;
      checkout.textContent = "Preparando...";
      try {
        const invoice = await request("/billing/checkout", { method: "POST" });
        const url = safeAsaasCheckoutUrl(invoice && invoice.checkoutUrl);
        if (!url) throw new Error("O provedor nao retornou um link de pagamento valido.");
        window.open(url, "_blank", "noopener,noreferrer");
        startPaymentStatusPolling();
        await openBillingPortal();
      } catch (error) {
        window.alert(error.message || "Nao foi possivel gerar o pagamento.");
        checkout.disabled = false;
        checkout.textContent = "Gerar pagamento";
      }
    };
  }

  function formatMoney(value) {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(Number(value || 0));
  }

  function formatDate(value) {
    if (!value) return "-";
    const parsed = new Date(String(value).replace(" ", "T"));
    return Number.isNaN(parsed.getTime()) ? escapeHtml(value) : parsed.toLocaleDateString("pt-BR");
  }

  function safeAsaasCheckoutUrl(value) {
    try {
      const url = new URL(String(value || ""));
      const host = url.hostname.toLowerCase();
      const asaasHost = host === "asaas.com"
        || host.endsWith(".asaas.com")
        || host === "asaas.com.br"
        || host.endsWith(".asaas.com.br");
      return url.protocol === "https:" && asaasHost ? url.href : "";
    } catch (error) {
      return "";
    }
  }

  function accountRestrictionCode(reasonCode, message) {
    const code = String(reasonCode || "").trim().toUpperCase();
    if (code) return code;
    const text = String(message || "").toLowerCase();
    if (text.includes("expir") || text.includes("vencid")) return "PAYMENT_EXPIRED";
    if (text.includes("plano basico") || text.includes("plano básico")) return "PLAN_UPGRADE_REQUIRED";
    return "ACCOUNT_BLOCKED";
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    }[char]));
  }

  function withJson(method, path, data, options) {
    return request(path, {
      ...(options || {}),
      method,
      body: data === undefined ? undefined : JSON.stringify(data)
    });
  }

  window.ZentrixApi = Object.freeze({
    request,
    get: (path, options) => request(path, { ...(options || {}), method: "GET" }),
    post: (path, data, options) => withJson("POST", path, data, options),
    put: (path, data, options) => withJson("PUT", path, data, options),
    patch: (path, data, options) => withJson("PATCH", path, data, options),
    delete: (path, options) => request(path, { ...(options || {}), method: "DELETE" }),
    readSession,
    clearSession
  });
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", installBillingPortal);
  } else {
    installBillingPortal();
  }
})();
