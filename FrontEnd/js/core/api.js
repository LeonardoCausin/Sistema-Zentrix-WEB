(function () {
  "use strict";

  const SESSION_KEY = "zentrix-session";

  function readSessionRaw() {
    try {
      return sessionStorage.getItem(SESSION_KEY) || localStorage.getItem(SESSION_KEY);
    } catch (error) {
      try {
        return localStorage.getItem(SESSION_KEY);
      } catch (storageError) {
        return null;
      }
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
    return "http://localhost:8080/api";
  }

  async function request(path, options, context) {
    const requestOptions = { ...(options || {}) };
    const session = context && context.session ? context.session : readSession();
    const base = context && context.apiBase ? context.apiBase : apiBase();
    const headers = {
      "Content-Type": "application/json",
      Authorization: session ? "Bearer " + session.token : "",
      ...(requestOptions.headers || {})
    };
    const response = await fetch(base + path, {
      ...requestOptions,
      headers
    });

    if (response.status === 401) {
      clearSession();
      window.location.replace((context && context.loginPath) || loginPath());
      throw new Error("Sessão expirada");
    }
    if (!response.ok) {
      const message = await errorMessage(response);
      throw new Error(message || "Não foi possível carregar os dados.");
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

  async function errorMessage(response) {
    try {
      const body = await response.json();
      return body.message || body.error || body.detail || "";
    } catch (error) {
      return "";
    }
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
