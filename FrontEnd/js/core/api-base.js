(function () {
  "use strict";

  const STORAGE_KEY = "zentrix-api-base";
  const LOCAL_FALLBACK = "http://localhost:8080/api";

  function normalizeApiBase(value) {
    return String(value || "").trim().replace(/\/+$/, "");
  }

  function readStoredApiBase() {
    try {
      return normalizeApiBase(localStorage.getItem(STORAGE_KEY));
    } catch (error) {
      return "";
    }
  }

  function inferApiBaseFromLocation() {
    if (!location || !location.hostname) {
      return LOCAL_FALLBACK;
    }
    const protocol = location.protocol === "https:" ? "https:" : "http:";
    return protocol + "//" + location.hostname + ":8080/api";
  }

  function getApiBase() {
    const configured = normalizeApiBase(window.ZENTRIX_API_BASE);
    if (configured) {
      return configured;
    }
    const stored = readStoredApiBase();
    if (stored) {
      return stored;
    }
    return normalizeApiBase(inferApiBaseFromLocation());
  }

  function getFallbackBases() {
    return Array.from(new Set([
      getApiBase(),
      inferApiBaseFromLocation(),
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
