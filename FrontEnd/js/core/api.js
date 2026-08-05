(function () {
  "use strict";

  const SESSION_KEY = "zentrix-session";
  const DEFAULT_TIMEOUT_MS = 15000;

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
      const message = await errorMessage(response);
      const error = new Error(message || "Não conseguimos carregar as informações agora. Tente novamente.");
      error.status = response.status;
      if (response.status === 402) {
        showAccountBlocked(error.message);
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

  async function errorMessage(response) {
    try {
      const body = await response.json();
      return friendlyServerMessage(body.message || body.error || body.detail || "");
    } catch (error) {
      return "";
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

  function showAccountBlocked(message) {
    const safeMessage = friendlyServerMessage(message)
      || "A assinatura desta loja precisa ser regularizada para acessar o painel.";
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
        <span class="account-blocked-kicker">Acesso interrompido</span>
        <h1>Loja bloqueada</h1>
        <p>${escapeHtml(safeMessage)}</p>
        <div class="account-blocked-actions">
          <button class="button btn-primary" type="button" data-account-blocked-payment>Pagar assinatura</button>
          <button class="button btn-dark" type="button" data-account-blocked-logout>Sair do painel</button>
        </div>
      </div>
    `;
    document.body.classList.add("account-blocked");
    const paymentButton = blocker.querySelector("[data-account-blocked-payment]");
    if (paymentButton) {
      paymentButton.addEventListener("click", () => {
        const paymentUrl = window.ZentrixPaymentUrl || "";
        if (paymentUrl) {
          window.location.href = paymentUrl;
          return;
        }
        window.alert("Pagamento online sera liberado em breve. Entre em contato com o suporte Zentrix para regularizar a assinatura.");
      });
    }
    const logoutButton = blocker.querySelector("[data-account-blocked-logout]");
    if (logoutButton) {
      logoutButton.addEventListener("click", () => {
        clearSession();
        window.location.replace(loginPath());
      });
    }
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
})();
