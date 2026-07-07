(function () {
  "use strict";

  const API_BASE = "/api";

  function normalizeApiBase(value) {
    const text = String(value || "").trim().replace(/\/+$/, "");
    return text || API_BASE;
  }

  function getApiBase() {
    return API_BASE;
  }

  function getFallbackBases() {
    return [API_BASE];
  }

  function rememberApiBase() {
    try {
      localStorage.removeItem("zentrix-api-base");
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
