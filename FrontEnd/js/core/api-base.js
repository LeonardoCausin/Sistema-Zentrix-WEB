(function () {
  "use strict";

  const STORAGE_KEY = "zentrix-api-base";
  const PUBLIC_API_BASE = "https://api.zentrixsystems.com.br/api";
  const LOCAL_FALLBACK = "http://localhost:8080/api";

  function normalizeApiBase(value) {
    return String(value || "").trim().replace(/\/+$/, "");
  }

  function isLocalHost(hostname) {
    return hostname === "localhost" || hostname === "127.0.0.1";
  }

  function isLocalApiBase(value) {
    return /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(value || "");
  }

  function readStoredApiBase() {
    try {
      return normalizeApiBase(localStorage.getItem(STORAGE_KEY));
    } catch (error) {
      return "";
    }
  }

  function inferApiBaseFromLocation() {
    if (!location || !location.hostname || !/^https?:$/.test(location.protocol)) {
      return PUBLIC_API_BASE;
    }
    if (isLocalHost(location.hostname)) {
      return location.origin.replace(/\/+$/, "") + "/api";
    }
    return PUBLIC_API_BASE;
  }

  function getApiBase() {
    const configured = normalizeApiBase(window.ZENTRIX_API_BASE);
    if (configured) {
      return configured;
    }
    const stored = readStoredApiBase();
    if (stored && (isLocalHost(location.hostname) || !isLocalApiBase(stored))) {
      return stored;
    }
    return normalizeApiBase(inferApiBaseFromLocation());
  }

  function getFallbackBases() {
    return Array.from(new Set([
      getApiBase(),
      inferApiBaseFromLocation(),
      PUBLIC_API_BASE,
      LOCAL_FALLBACK,
      "http://127.0.0.1:8080/api"
    ].map(normalizeApiBase).filter(Boolean)));
  }

  function rememberApiBase(value) {
    const normalized = normalizeApiBase(value);
    if (!normalized) {
      return;
    }
    try {
      localStorage.setItem(STORAGE_KEY, normalized);
    } catch (error) {
      // Mantem o painel funcionando em navegadores com storage restrito.
    }
  }

  window.ZentrixApiBase = Object.freeze({
    getApiBase,
    getFallbackBases,
    normalizeApiBase,
    rememberApiBase
  });
})();
