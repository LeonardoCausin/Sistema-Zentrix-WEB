(function () {
  const root = document.documentElement;
  const body = document.body;
  const themeButton = document.getElementById("themeButton");
  const menuButton = document.getElementById("menuButton");
  const closeSidebarButton = document.getElementById("closeSidebarButton");
  const sidebarBackdrop = document.getElementById("sidebarBackdrop");
  const storedTheme = localStorage.getItem("zentrix-theme");
  const SESSION_KEY = "zentrix-session";
  const session = readSessionRaw();
  let currentPeriod = normalizePeriodValue(localStorage.getItem("zentrix-period"));
  let currentStore = localStorage.getItem("zentrix-store") || "all";
  let storesCache = readStoresCache();
  let refreshTimer = null;
  let loadingData = false;
  let pendingDataReload = false;
  let prefetchTimer = null;
  let loadSequence = 0;
  let rendererCache = null;
  let csvToolsCache = null;
  const prefetchJobs = new Set();
  let forceFreshData = false;
  let adminFormLockUntil = 0;
  let renderingSilentLoad = false;
  let topbarAlerts = [];
  const pageConfig = window.ZentrixPageConfig || {};
  const API_CACHE_MAX_AGE = pageConfig.apiCacheMaxAge || 15 * 60 * 1000;
  const VIEW_CACHE_MAX_AGE = pageConfig.viewCacheMaxAge || 10 * 60 * 1000;
  const VIEW_CACHE_PREFIX = pageConfig.viewCachePrefix || "zentrix-view-cache:";
  const VIEW_STATE_PREFIX = pageConfig.viewStatePrefix || "zentrix-view-state:";
  const CLIENT_CACHE_VERSION = pageConfig.clientCacheVersion || "20260713-audit-events";
  const pendingApiRefresh = new Set();
  const pendingApiRequests = new Map();
  const PREFETCH_PERIODS = pageConfig.prefetchPeriods || ["today", "7d", "month", "year"];
  const employeePermissionsConfig = window.ZentrixEmployeePermissions || {};
  const EMPLOYEE_PERMISSIONS = Object.freeze(employeePermissionsConfig.permissions || []);
  const EMPLOYEE_ROLE_PRESETS = Object.freeze(employeePermissionsConfig.rolePresets || {});
  const PAGE_ORDER = Object.freeze(["dashboard", "vendas", "caixa", "financeiro", "produtos", "estoque", "clientes", "funcionarios", "auditoria", "relatorios", "backups", "configuracoes"]);
  const PAGE_LABELS = Object.freeze({
    dashboard: "Dashboard",
    vendas: "Vendas",
    caixa: "Caixa",
    financeiro: "Financeiro",
    produtos: "Produtos",
    estoque: "Estoque",
    clientes: "Clientes",
    funcionarios: "Funcionarios",
    auditoria: "Auditoria",
    relatorios: "Relatorios",
    backups: "Backups",
    configuracoes: "Configuracoes"
  });
  const PAGE_ACCESS = Object.freeze({
    dashboard: ["dashboard.visualizar"],
    vendas: ["vendas.visualizar"],
    caixa: ["caixa.visualizar"],
    financeiro: ["financeiro.visualizar", "financeiro.editar"],
    produtos: ["produtos.visualizar"],
    estoque: ["estoque.visualizar", "estoque.movimentar"],
    clientes: ["clientes.visualizar"],
    funcionarios: ["funcionarios.visualizar", "funcionarios.criar", "funcionarios.editar", "funcionarios.permissoes"],
    auditoria: ["auditoria.visualizar"],
    relatorios: ["relatorios.visualizar"],
    backups: ["backups.gerar", "backups.restaurar"],
    configuracoes: ["configuracoes.visualizar", "configuracoes.editar"]
  });
  const ACTION_PERMISSION_RULES = Object.freeze([
    ['[data-action="new-product"]', "produtos.criar"],
    ['[data-action="edit-product"]', "produtos.editar"],
    ['[data-action="toggle-product-status"]', "produtos.desativar"],
    ['[data-action="new-client"]', "clientes.criar"],
    ['[data-action="edit-client"]', "clientes.editar"],
    ['[data-action="toggle-client-status"]', "clientes.editar"],
    ['[data-action="new-stock-movement"]', "estoque.movimentar"],
    ['[data-action="new-employee"]', "funcionarios.criar"],
    ['[data-action="edit-employee"]', "funcionarios.editar"],
    ['[data-action="toggle-employee-status"]', "funcionarios.editar"],
    ['[data-action="new-finance-entry"]', "financeiro.editar"],
    ['[data-action="edit-finance-entry"]', "financeiro.editar"],
    ['[data-action="set-finance-status"]', "financeiro.editar"],
    ['[data-action="create-backup"]', "backups.gerar"],
    ['[data-action="prepare-backup-restore"]', "backups.restaurar"]
  ]);
  const pageUtils = window.ZentrixPageUtils || {};
  const {
    decimalField,
    esc,
    escAttr,
    formatCurrency,
    greeting,
    initials,
    moneyValue,
    normalizeKey,
    normalizeText,
    quantityLabel,
    sumMoney,
    todayDateValue
  } = pageUtils;

  try {
    if (sessionStorage.getItem("zentrix-client-cache-version") !== CLIENT_CACHE_VERSION) {
      clearApiCache();
      sessionStorage.setItem("zentrix-client-cache-version", CLIENT_CACHE_VERSION);
    }
  } catch (error) {
    // Mantem a navegação funcionando mesmo se o navegador bloquear sessionStorage.
  }

  if (body.classList.contains("is-authenticated") && !session) {
    const loginPath = location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html";
    window.location.replace(loginPath);
    return;
  }

  const explicitThemeChoice = localStorage.getItem("zentrix-theme-user-choice") === "true";
  root.dataset.theme = explicitThemeChoice && storedTheme === "light" ? "light" : "dark";

  enhanceChrome();

  document.addEventListener("click", handleGlobalClick);

  if (menuButton) {
    menuButton.addEventListener("click", () => body.classList.add("sidebar-open"));
  }

  if (closeSidebarButton) {
    closeSidebarButton.addEventListener("click", () => body.classList.remove("sidebar-open"));
  }

  if (sidebarBackdrop) {
    sidebarBackdrop.addEventListener("click", () => body.classList.remove("sidebar-open"));
  }

  document.querySelectorAll('a[href$="index.html"]').forEach((link) => {
    link.addEventListener("click", () => {
      clearApiCache();
      clearStoredSession();
    });
  });

  window.zentrixApi = async function zentrixApi(path, options) {
    const storedSession = readStoredSession();
    const apiBase = currentApiBase();
    const requestOptions = { ...(options || {}) };
    if (forceFreshData && requestOptions.cache !== "no-store") {
      requestOptions.cache = "refresh";
    }
    const method = String(requestOptions.method || "GET").toUpperCase();
    const canCache = method === "GET" && !requestOptions.body && requestOptions.cache !== "no-store";
    const cacheKey = canCache ? apiCacheKey(apiBase, path, storedSession && storedSession.token) : null;
    const cached = canCache ? readApiCache(cacheKey) : null;

    if (cached && requestOptions.cache !== "refresh") {
      refreshApiCache(apiBase, path, requestOptions, storedSession, cacheKey);
      return cached.data;
    }

    if (canCache && pendingApiRequests.has(cacheKey)) {
      return pendingApiRequests.get(cacheKey);
    }

    const request = fetchApiJson(apiBase, path, requestOptions, storedSession)
      .then((data) => {
        if (canCache) {
          writeApiCache(cacheKey, data);
        }
        return data;
      })
      .finally(() => {
        if (canCache) {
          pendingApiRequests.delete(cacheKey);
        }
      });

    if (canCache) {
      pendingApiRequests.set(cacheKey, request);
    }
    return request;
  };

  if (body.classList.contains("is-authenticated")) {
    refreshAccountToolbar().catch(() => null);
  }

  const viewHost = document.querySelector(".view-host");
  if (body.classList.contains("is-authenticated") && viewHost) {
    initChromeSkeleton();
    setupPeriodControl();
    loadPageData();
    setupAutoRefresh();
  }

  async function fetchApiJson(apiBase, path, options, storedSession) {
    const fetchOptions = { ...(options || {}) };
    const timeoutMs = Number(fetchOptions.timeoutMs || 15000);
    delete fetchOptions.cache;
    delete fetchOptions.prefetch;
    delete fetchOptions.timeoutMs;
    if (window.ZentrixApi && typeof window.ZentrixApi.request === "function") {
      return window.ZentrixApi.request(path, fetchOptions, {
        apiBase,
        session: storedSession,
        loginPath: location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html"
      });
    }
    let response;
    try {
      response = await fetchWithTimeout(apiBase + path, {
        ...fetchOptions,
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          Authorization: storedSession ? "Bearer " + storedSession.token : "",
          ...((options && options.headers) || {})
        }
      }, timeoutMs);
    } catch (error) {
      throw apiConnectionError(error);
    }
    if (response.status === 401) {
      clearApiCache();
      clearStoredSession();
      window.location.replace(location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html");
      throw new Error("Sua sessão expirou. Entre novamente.");
    }
    if (!response.ok) {
      throw new Error("Não conseguimos carregar as informações agora. Tente novamente.");
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

  function apiConnectionError(error) {
    if (error && error.name === "AbortError") {
      return new Error("A resposta demorou mais que o esperado. Confira a conexão e tente novamente.");
    }
    return new Error("Não conseguimos conectar ao Zentrix agora. Verifique a internet ou se o servidor está ligado.");
  }

  function apiCacheKey(apiBase, path, token) {
    return "zentrix-api-cache:" + encodeURIComponent([token || "public", apiBase, path].join("|"));
  }

  function currentApiCacheKey(path) {
    const storedSession = readStoredSession();
    const apiBase = currentApiBase();
    return apiCacheKey(apiBase, path, storedSession && storedSession.token);
  }

  function currentApiBase() {
    if (window.ZentrixApiBase && typeof window.ZentrixApiBase.getApiBase === "function") {
      return window.ZentrixApiBase.getApiBase();
    }
    return "/api";
  }

  function readApiCache(key) {
    try {
      const cached = JSON.parse(sessionStorage.getItem(key) || "null");
      if (!cached || Date.now() - cached.savedAt > API_CACHE_MAX_AGE) {
        return null;
      }
      return cached;
    } catch (error) {
      return null;
    }
  }

  function writeApiCache(key, data) {
    try {
      sessionStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), data }));
    } catch (error) {
      clearApiCache();
    }
  }

  function refreshApiCache(apiBase, path, options, storedSession, cacheKey) {
    if (pendingApiRefresh.has(cacheKey)) {
      return;
    }
    pendingApiRefresh.add(cacheKey);
    fetchApiJson(apiBase, path, { ...options, cache: "refresh" }, storedSession)
      .then((data) => writeApiCache(cacheKey, data))
      .catch(() => null)
      .finally(() => pendingApiRefresh.delete(cacheKey));
  }

  function clearApiCache() {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith("zentrix-api-cache:"))
      .forEach((key) => sessionStorage.removeItem(key));
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(VIEW_CACHE_PREFIX) || key.startsWith(VIEW_STATE_PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
    pendingApiRefresh.clear();
    pendingApiRequests.clear();
  }

  function readSessionRaw() {
    try {
      return sessionStorage.getItem(SESSION_KEY);
    } catch (error) {
      return null;
    }
  }

  function readStoredSession() {
    try {
      return JSON.parse(readSessionRaw() || "null");
    } catch (error) {
      clearStoredSession();
      return null;
    }
  }

  function clearStoredSession() {
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch (error) {
      // Ignora navegadores que bloqueiam sessionStorage.
    }
    try {
      localStorage.removeItem(SESSION_KEY);
    } catch (error) {
      // Ignora navegadores que bloqueiam localStorage.
    }
  }

  function roleKeyValue(role) {
    const value = typeof normalizeKey === "function" ? normalizeKey(role || "") : String(role || "").trim().toUpperCase();
    return String(value || "").toUpperCase().replace(/[^A-Z0-9_]/g, "_").replace(/_+/g, "_");
  }

  function isMasterRole(role) {
    return ["SUPER_ADMIN", "SUPERADMIN", "MASTER_ADMIN", "MASTERADMIN"].includes(roleKeyValue(role));
  }

  function isFullAccessRole(role) {
    return isMasterRole(role) || ["DONO", "OWNER", "ADMIN", "ADMINISTRADOR", "ADMINISTRATOR"].includes(roleKeyValue(role));
  }

  function explicitSessionPermissions(account) {
    if (!account || !Array.isArray(account.permissions)) {
      return [];
    }
    return account.permissions
      .map((permission) => String(permission || "").trim())
      .filter(Boolean);
  }

  function sessionPermissionSet() {
    const account = readStoredSession() || {};
    if (isMasterRole(account.role)) {
      return new Set(["*"]);
    }
    const explicitPermissions = explicitSessionPermissions(account);
    if (explicitPermissions.length > 0) {
      return new Set(explicitPermissions);
    }
    if (isFullAccessRole(account.role)) {
      return new Set(["*"]);
    }
    const preset = EMPLOYEE_ROLE_PRESETS[roleKeyValue(account.role)] || [];
    return new Set(preset);
  }

  function hasPermission(permission) {
    const permissions = sessionPermissionSet();
    return permissions.has("*") || permissions.has("admin") || permissions.has(permission);
  }

  function currentUsername() {
    const account = readStoredSession() || {};
    return String(account.username || account.user || account.login || "").trim().toLowerCase();
  }

  function isCurrentUsername(username) {
    const current = currentUsername();
    return Boolean(current) && current === String(username || "").trim().toLowerCase();
  }

  function canAccessPage(page) {
    const required = PAGE_ACCESS[page] || [];
    return required.length === 0 || required.some((permission) => hasPermission(permission));
  }

  function firstPermittedPage() {
    return PAGE_ORDER.find((page) => canAccessPage(page)) || null;
  }

  function visibleNavigationLinks(links) {
    return links.filter(([href]) => {
      const page = String(href || "").replace(/\.html$/i, "");
      return canAccessPage(page);
    });
  }

  function enforceActionPermissions() {
    if (!viewHost) return;
    ACTION_PERMISSION_RULES.forEach(([selector, permission]) => {
      if (hasPermission(permission)) return;
      viewHost.querySelectorAll(selector).forEach((element) => element.remove());
    });
    if (!hasPermission("funcionarios.permissoes")) {
      viewHost.querySelectorAll('[name="permissions"], [data-permission-field]').forEach((field) => {
        field.disabled = true;
        field.setAttribute("aria-disabled", "true");
      });
    }
    viewHost.querySelectorAll('[data-action="edit-employee"], [data-action="toggle-employee-status"]').forEach((button) => {
      if (isCurrentUsername(button.dataset.username)) {
        button.remove();
      }
    });
  }

  function renderPermissionDenied(page, fallbackPage) {
    const title = PAGE_LABELS[page] || "Acesso restrito";
    const fallbackLabel = PAGE_LABELS[fallbackPage] || "uma tela permitida";
    const action = fallbackPage
      ? `<a class="button btn-primary compact-button" href="${escAttr(fallbackPage)}.html">Ir para ${esc(fallbackLabel)}</a>`
      : "";
    renderShell(title, "Permissao necessaria", `
      <section class="panel">
        <div class="panel-title">
          <div>
            <h3>Voce nao tem permissao para acessar esta tela</h3>
            <span>Peça ao administrador ou dono da loja para revisar suas permissoes.</span>
          </div>
          ${action}
        </div>
        ${emptyState("Esta area esta protegida para manter os dados da loja seguros.")}
      </section>
    `, { noStoreTabs: true });
    body.classList.add("app-data-ready");
  }

  function ensurePageAccess(page, silent) {
    if (canAccessPage(page)) {
      return true;
    }
    const fallbackPage = firstPermittedPage();
    if (page === "dashboard" && fallbackPage && fallbackPage !== page && !silent) {
      window.location.replace(`${fallbackPage}.html`);
      return false;
    }
    renderPermissionDenied(page, fallbackPage);
    return false;
  }

  function prefetchApi(path) {
    if (loadingData) {
      return;
    }
    if (readApiCache(currentApiCacheKey(path))) {
      return;
    }
    window.zentrixApi(path, { prefetch: true }).catch(() => null);
  }

  function viewCacheKey(page) {
    const storedSession = readStoredSession();
    const tokenPart = storedSession && storedSession.token ? storedSession.token.slice(-18) : "public";
    return [tokenPart, page || currentPageName(), currentStore, currentPeriod].join("|");
  }

  function restoreCachedView(page) {
    try {
      const cached = JSON.parse(sessionStorage.getItem(VIEW_CACHE_PREFIX + viewCacheKey(page)) || "null");
      if (!cached || Date.now() - cached.savedAt > VIEW_CACHE_MAX_AGE || !cached.html) {
        return false;
      }
      viewHost.innerHTML = cached.html;
      body.classList.add("app-data-ready");
      wireCachedView(page);
      return true;
    } catch (error) {
      return false;
    }
  }

  function cacheCurrentView(page) {
    try {
      if (!viewHost || !viewHost.innerHTML || viewHost.querySelector(".skeleton") || viewHost.querySelector(".state-box")) {
        return;
      }
      sessionStorage.setItem(VIEW_CACHE_PREFIX + viewCacheKey(page), JSON.stringify({ savedAt: Date.now(), html: viewHost.innerHTML }));
    } catch (error) {
      clearOldViewCache();
    }
  }

  function clearOldViewCache() {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(VIEW_CACHE_PREFIX))
      .forEach((key) => {
        try {
          const cached = JSON.parse(sessionStorage.getItem(key) || "null");
          if (!cached || Date.now() - cached.savedAt > VIEW_CACHE_MAX_AGE) {
            sessionStorage.removeItem(key);
          }
        } catch (error) {
          sessionStorage.removeItem(key);
        }
      });
  }

  function viewStateKey(page, name) {
    return VIEW_STATE_PREFIX + viewCacheKey(page) + ":" + name;
  }

  function saveViewState(page, name, value) {
    try {
      sessionStorage.setItem(viewStateKey(page, name), JSON.stringify(value));
    } catch (error) {
      clearOldViewCache();
    }
  }

  function readViewState(page, name) {
    try {
      return JSON.parse(sessionStorage.getItem(viewStateKey(page, name)) || "null");
    } catch (error) {
      return null;
    }
  }

  function wireCachedView(page) {
    wireStoreTabs();
    restoreCsvExports(page);
    wirePageActions(page);
    if (page === "relatorios") {
      wireReportActions(readViewState(page, "reports-overview"));
    }
  }

  function wirePageActions(page) {
    enforceActionPermissions();

    viewHost.querySelectorAll('[data-action="set-theme"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => setTheme(button.dataset.theme || "light"));
    });
    syncThemeControls();

    const settingsForm = viewHost.querySelector("[data-settings-form]");
    if (settingsForm && settingsForm.dataset.ready !== "true") {
      settingsForm.dataset.ready = "true";
      settingsForm.addEventListener("submit", (event) => submitSettings(event, settingsForm));
    }

    const productButton = viewHost.querySelector('[data-action="new-product"]');
    if (productButton && productButton.dataset.ready !== "true") {
      productButton.dataset.ready = "true";
      productButton.addEventListener("click", () => {
        toggleAdminForm("product");
      });
    }

    const clientButton = viewHost.querySelector('[data-action="new-client"]');
    if (clientButton && clientButton.dataset.ready !== "true") {
      clientButton.dataset.ready = "true";
      clientButton.addEventListener("click", () => {
        toggleAdminForm("client");
      });
    }

    const stockButton = viewHost.querySelector('[data-action="new-stock-movement"]');
    if (stockButton && stockButton.dataset.ready !== "true") {
      stockButton.dataset.ready = "true";
      stockButton.addEventListener("click", () => {
        toggleAdminForm("stock");
      });
    }

    const employeeButton = viewHost.querySelector('[data-action="new-employee"]');
    if (employeeButton && employeeButton.dataset.ready !== "true") {
      employeeButton.dataset.ready = "true";
      employeeButton.addEventListener("click", () => {
        toggleAdminForm("employee");
      });
    }

    const financeButton = viewHost.querySelector('[data-action="new-finance-entry"]');
    if (financeButton && financeButton.dataset.ready !== "true") {
      financeButton.dataset.ready = "true";
      financeButton.addEventListener("click", () => {
        toggleAdminForm("finance");
      });
    }

    const productForm = viewHost.querySelector('[data-admin-form="product"] form');
    if (productForm && productForm.dataset.ready !== "true") {
      productForm.dataset.ready = "true";
      productForm.addEventListener("submit", (event) => submitAdminProduct(event, productForm));
    }

    const clientForm = viewHost.querySelector('[data-admin-form="client"] form');
    if (clientForm && clientForm.dataset.ready !== "true") {
      clientForm.dataset.ready = "true";
      clientForm.addEventListener("submit", (event) => submitAdminClient(event, clientForm));
    }

    const stockForm = viewHost.querySelector('[data-admin-form="stock"] form');
    if (stockForm && stockForm.dataset.ready !== "true") {
      stockForm.dataset.ready = "true";
      stockForm.addEventListener("submit", (event) => submitStockMovement(event, stockForm));
    }

    const employeeForm = viewHost.querySelector('[data-admin-form="employee"] form');
    if (employeeForm && employeeForm.dataset.ready !== "true") {
      employeeForm.dataset.ready = "true";
      employeeForm.addEventListener("submit", (event) => submitAdminEmployee(event, employeeForm));
      const roleField = employeeForm.elements.role;
      if (roleField) {
        roleField.addEventListener("change", () => applyRolePermissions(employeeForm, roleField.value));
      }
    }

    const financeForm = viewHost.querySelector('[data-admin-form="finance"] form');
    if (financeForm && financeForm.dataset.ready !== "true") {
      financeForm.dataset.ready = "true";
      financeForm.addEventListener("submit", (event) => submitFinancialEntry(event, financeForm));
    }

    viewHost.querySelectorAll('[data-action="cancel-admin-form"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => {
        const panel = button.closest("[data-admin-form]");
        if (panel) panel.hidden = true;
        releaseAdminFormLock();
      });
    });

    viewHost.querySelectorAll('[data-action="edit-product"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => openProductEditor(button), "Abrindo..."));
    });

    viewHost.querySelectorAll('[data-action="toggle-product-status"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => toggleProductStatus(button), "Salvando..."));
    });

    viewHost.querySelectorAll('[data-action="edit-client"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => openClientEditor(button), "Abrindo..."));
    });

    viewHost.querySelectorAll('[data-action="toggle-client-status"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => toggleClientStatus(button), "Salvando..."));
    });

    viewHost.querySelectorAll('[data-action="edit-employee"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => openEmployeeEditor(button), "Abrindo..."));
    });

    viewHost.querySelectorAll('[data-action="toggle-employee-status"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => toggleEmployeeStatus(button), "Salvando..."));
    });

    viewHost.querySelectorAll('[data-action="edit-finance-entry"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => openFinancialEntryEditor(button), "Abrindo..."));
    });

    viewHost.querySelectorAll('[data-action="set-finance-status"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => setFinancialEntryStatus(button));
    });

    const createBackupButton = viewHost.querySelector('[data-action="create-backup"]');
    if (createBackupButton && createBackupButton.dataset.ready !== "true") {
      createBackupButton.dataset.ready = "true";
      createBackupButton.addEventListener("click", () => runButtonTask(createBackupButton, createManualBackup, "Gerando..."));
    }

    viewHost.querySelectorAll('[data-action="download-real-backup"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => downloadRealBackup(button.dataset.id));
    });

    viewHost.querySelectorAll('[data-action="prepare-backup-restore"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => runButtonTask(button, () => prepareBackupRestore(button.dataset.id), "Validando..."));
    });

    viewHost.querySelectorAll('[data-action="sync-retry"], [data-action="sync-dead-letter"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => handleSyncOutboxAction(button));
    });

    ensurePageSearchPanel(page || currentPageName());
    wireListFilters();
    enforceActionPermissions();
  }

  function handleGlobalClick(event) {
    const notificationButton = event.target.closest("[data-action='toggle-notifications']");
    const userButton = event.target.closest("[data-action='toggle-user-menu']");
    const logoutButton = event.target.closest("[data-action='logout']");
    const themeAction = event.target.closest("[data-action='set-theme']");

    if (notificationButton) {
      event.preventDefault();
      togglePopover(".notification-menu", notificationButton);
      return;
    }

    if (userButton) {
      event.preventDefault();
      togglePopover(".user-menu", userButton);
      return;
    }

    if (logoutButton) {
      event.preventDefault();
      logoutAndRedirect();
      return;
    }

    if (themeAction && !themeAction.closest(".view-host")) {
      event.preventDefault();
      setTheme(themeAction.dataset.theme || "light");
      return;
    }

    if (!event.target.closest(".toolbar-popover, .user-card, .notification-button")) {
      closeTopbarMenus();
    }
  }

  function togglePopover(selector, trigger) {
    const menu = document.querySelector(selector);
    if (!menu) return;
    const willOpen = menu.hidden;
    closeTopbarMenus();
    menu.hidden = !willOpen;
    if (trigger) {
      trigger.setAttribute("aria-expanded", String(willOpen));
    }
  }

  async function logoutAndRedirect() {
    try {
      await window.zentrixApi("/auth/logout", {
        method: "POST",
        cache: "no-store",
        timeoutMs: 3000
      });
    } catch (error) {
      // Mesmo sem conexão, a saída local deve acontecer.
    } finally {
      clearApiCache();
      clearStoredSession();
      window.location.href = location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html";
    }
  }

  function closeTopbarMenus() {
    document.querySelectorAll(".toolbar-popover").forEach((menu) => {
      menu.hidden = true;
    });
    document.querySelectorAll("[aria-expanded='true']").forEach((button) => {
      button.setAttribute("aria-expanded", "false");
    });
  }

  function setTheme(theme) {
    const nextTheme = theme === "dark" ? "dark" : "light";
    root.dataset.theme = nextTheme;
    try {
      localStorage.setItem("zentrix-theme", nextTheme);
      localStorage.setItem("zentrix-theme-user-choice", "true");
    } catch (error) {
      // Mantem o tema aplicado mesmo se o navegador bloquear storage.
    }
    syncThemeControls();
  }

  function syncThemeControls() {
    const currentTheme = root.dataset.theme === "dark" ? "dark" : "light";
    document.querySelectorAll("[data-action='set-theme']").forEach((button) => {
      button.classList.toggle("active", button.dataset.theme === currentTheme);
      button.setAttribute("aria-pressed", String(button.dataset.theme === currentTheme));
    });
  }

  async function runButtonTask(button, task, busyText) {
    if (!button || button.disabled || button.dataset.busy === "true") {
      return;
    }
    const previousText = button.textContent;
    button.dataset.busy = "true";
    button.disabled = true;
    if (busyText) {
      button.textContent = busyText;
    }
    try {
      await task();
    } finally {
      button.disabled = false;
      button.dataset.busy = "false";
      button.textContent = previousText;
    }
  }

  function ensurePageSearchPanel(page) {
    const configs = {
      vendas: {
        scope: "sales",
        placeholder: "Buscar venda por codigo, operador, pagamento ou loja",
        filters: [["all", "Todas"], ["paid", "Pagas"], ["cancelled", "Canceladas"]],
        container: ".table-panel"
      },
      produtos: {
        scope: "products",
        placeholder: "Buscar produto por nome, codigo, categoria ou codigo de barras",
        filters: [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"], ["empty", "Sem estoque"]],
        container: ".entity-grid"
      },
      clientes: {
        scope: "clients",
        placeholder: "Buscar cliente por nome, CPF/CNPJ, telefone, email ou endereco",
        filters: [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"]],
        container: ".entity-grid"
      },
      funcionarios: {
        scope: "employees",
        placeholder: "Buscar funcionário por nome, login, cargo ou permissão",
        filters: [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"], ["admin", "Administradores"]],
        container: ".entity-grid"
      }
    };
    const config = configs[page];
    if (!config) return;
    if (!viewHost.querySelector(`[data-search-controls="${config.scope}"]`)) {
      const anchor = viewHost.querySelector(".page-actions") || viewHost.querySelector(".grid.metrics-grid") || viewHost.firstElementChild;
      if (anchor) {
        anchor.insertAdjacentHTML(anchor.classList && anchor.classList.contains("page-actions") ? "beforebegin" : "afterend", searchPanelHtml(config.scope, config.placeholder, config.filters));
      }
    }
    viewHost.querySelectorAll(config.container).forEach((container) => {
      if (!container.dataset.searchContainer) {
        container.dataset.searchContainer = config.scope;
      }
    });
  }

  function wireListFilters() {
    viewHost.querySelectorAll("[data-search-controls]").forEach((panel) => {
      const scope = panel.dataset.searchControls;
      if (!scope) return;
      const input = panel.querySelector(`[data-search-input="${scope}"]`);
      const select = panel.querySelector(`[data-search-filter="${scope}"]`);
      if (input && input.dataset.ready !== "true") {
        input.dataset.ready = "true";
        input.addEventListener("input", () => applyListFilter(scope));
      }
      if (select && select.dataset.ready !== "true") {
        select.dataset.ready = "true";
        select.addEventListener("change", () => applyListFilter(scope));
      }
      applyListFilter(scope);
    });

    viewHost.querySelectorAll('[data-action="clear-list-filter"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => {
        const scope = button.dataset.filterScope;
        const input = viewHost.querySelector(`[data-search-input="${scope}"]`);
        const select = viewHost.querySelector(`[data-search-filter="${scope}"]`);
        if (input) input.value = "";
        if (select) select.value = "all";
        applyListFilter(scope);
      });
    });

    viewHost.querySelectorAll('[data-action="apply-list-filter"]').forEach((button) => {
      if (button.dataset.ready === "true") return;
      button.dataset.ready = "true";
      button.addEventListener("click", () => {
        const scope = button.dataset.filterScope;
        const value = button.dataset.filterValue || "all";
        const select = viewHost.querySelector(`[data-search-filter="${scope}"]`);
        if (select) select.value = value;
        applyListFilter(scope);
        const controls = viewHost.querySelector(`[data-search-controls="${scope}"]`);
        if (controls) controls.scrollIntoView({ behavior: "smooth", block: "nearest" });
      });
    });

    viewHost.querySelectorAll(".metric-card").forEach((card) => {
      if (card.dataset.ready === "true") return;
      const text = normalizeKey(card.textContent || "");
      if (!text.includes("estoque baixo") || !viewHost.querySelector('[data-search-controls="stock"]')) {
        return;
      }
      card.dataset.ready = "true";
      card.classList.add("metric-action-card");
      card.setAttribute("role", "button");
      card.setAttribute("tabindex", "0");
      const applyLowStock = () => {
        const select = viewHost.querySelector('[data-search-filter="stock"]');
        if (select) select.value = "low";
        applyListFilter("stock");
        const controls = viewHost.querySelector('[data-search-controls="stock"]');
        if (controls) controls.scrollIntoView({ behavior: "smooth", block: "nearest" });
      };
      card.addEventListener("click", applyLowStock);
      card.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          applyLowStock();
        }
      });
    });
  }

  function applyListFilter(scope) {
    if (!scope) return;
    const input = viewHost.querySelector(`[data-search-input="${scope}"]`);
    const select = viewHost.querySelector(`[data-search-filter="${scope}"]`);
    const query = normalizeKey(input && input.value ? input.value : "").trim();
    const tokens = query.split(/\s+/).filter(Boolean);
    const filter = select && select.value ? select.value : "all";
    let visible = 0;
    let items = Array.from(viewHost.querySelectorAll(`[data-search-scope="${scope}"][data-search-item]`));
    if (!items.length) {
      items = Array.from(viewHost.querySelectorAll(`[data-search-container="${scope}"] .entity-card, [data-search-container="${scope}"] tbody tr`));
    }
    items.forEach((item) => {
      const text = normalizeKey(item.dataset.searchText || "");
      const visibleText = normalizeKey(item.textContent || "");
      const searchableText = text || visibleText;
      const itemFilters = String(item.dataset.filterValue || inferListFilter(scope, visibleText)).split(/\s+/);
      const matchesText = tokens.every((token) => searchableText.includes(token));
      const matchesFilter = filter === "all" || itemFilters.includes(filter);
      const show = matchesText && matchesFilter;
      item.classList.toggle("is-filtered-out", !show);
      if (show) visible += 1;
    });
    viewHost.querySelectorAll(`[data-search-count="${scope}"]`).forEach((node) => {
      node.textContent = String(visible);
    });
  }

  function inferListFilter(scope, text) {
    if (scope === "stock") {
      if (text.includes("sem estoque") || text.includes("critico")) return "critical low";
      if (text.includes("estoque baixo") || text.includes("baixo")) return "low";
      return "healthy";
    }
    if (scope === "employees") {
      const status = text.includes("inativo") || text.includes("inactive") ? "inactive" : "active";
      const admin = text.includes("admin") || text.includes("dono") || text.includes("owner") ? " admin" : "";
      return `${status}${admin}`;
    }
    if (scope === "products") {
      const status = text.includes("inativo") || text.includes("inactive") ? "inactive" : "active";
      return `${status}${text.includes("sem estoque") ? " empty" : ""}`;
    }
    if (scope === "clients") {
      if (text.includes("inativo") || text.includes("inactive")) return "inactive";
      return "active";
    }
    if (scope === "sales") {
      return text.includes("cancel") ? "cancelled" : "paid";
    }
    return "all";
  }

  function toggleAdminForm(type) {
    const panel = viewHost.querySelector(`[data-admin-form="${type}"]`);
    if (!panel) return;
    lockAdminFormInteraction();
    const form = panel.querySelector("form");
    if (form) resetAdminForm(type, form);
    panel.hidden = !panel.hidden;
    if (!panel.hidden) {
      const firstField = panel.querySelector("input, select, textarea");
      if (firstField) firstField.focus();
    } else {
      releaseAdminFormLock();
    }
  }

  function hasActiveAdminForm() {
    if (Date.now() < adminFormLockUntil) {
      return true;
    }
    const openPanel = viewHost.querySelector("[data-admin-form]:not([hidden])");
    if (openPanel) {
      return true;
    }
    const activeElement = document.activeElement;
    return Boolean(activeElement && activeElement.closest && activeElement.closest("[data-admin-form]"));
  }

  function lockAdminFormInteraction() {
    adminFormLockUntil = Date.now() + 5 * 60 * 1000;
  }

  function releaseAdminFormLock() {
    adminFormLockUntil = 0;
  }

  async function submitAdminProduct(event, form) {
    event.preventDefault();
    const mode = formValue(form, "mode") || "create";
    const originalCode = formValue(form, "originalCode");
    const store = formValue(form, "storeId") || writeStore();
    const payload = {
      code: mode === "edit" ? originalCode : formValue(form, "code"),
      description: formValue(form, "description"),
      unit: formValue(form, "unit") || "UN",
      price: decimalField(formValue(form, "price")),
      costPrice: decimalField(formValue(form, "costPrice")),
      stock: decimalField(formValue(form, "stock")),
      minStock: decimalField(formValue(form, "minStock")),
      category: formValue(form, "category"),
      barcode: formValue(form, "barcode"),
      active: formValue(form, "active") !== "false"
    };
    const endpoint = mode === "edit"
      ? "/admin/produtos/" + encodeURIComponent(originalCode) + "?store=" + encodeURIComponent(store)
      : "/admin/produtos?store=" + encodeURIComponent(store);
    await submitAdminForm(form, endpoint, payload, mode === "edit" ? "Produto atualizado." : "Produto cadastrado.", mode === "edit" ? "PUT" : "POST");
  }

  async function submitAdminClient(event, form) {
    event.preventDefault();
    const mode = formValue(form, "mode") || "create";
    const id = formValue(form, "id");
    const store = formValue(form, "storeId") || writeStore();
    const payload = {
      id: id ? Number(id) : undefined,
      name: formValue(form, "name"),
      cpfCnpj: formValue(form, "cpfCnpj"),
      phone: formValue(form, "phone"),
      email: formValue(form, "email"),
      address: formValue(form, "address"),
      active: formValue(form, "active") !== "false"
    };
    const endpoint = mode === "edit"
      ? "/admin/clientes/" + encodeURIComponent(id) + "?store=" + encodeURIComponent(store)
      : "/admin/clientes?store=" + encodeURIComponent(store);
    await submitAdminForm(form, endpoint, payload, mode === "edit" ? "Cliente atualizado." : "Cliente cadastrado.", mode === "edit" ? "PUT" : "POST");
  }

  async function submitStockMovement(event, form) {
    event.preventDefault();
    const type = formValue(form, "type") || "ENTRADA";
    const endpoint = stockMovementEndpoint(type) + "?store=" + encodeURIComponent(writeStore());
    const payload = {
      productCode: formValue(form, "productCode"),
      quantity: decimalField(formValue(form, "quantity")),
      reason: formValue(form, "reason"),
      referenceType: "APPGESTAO",
      referenceId: "manual"
    };
    await submitAdminForm(form, endpoint, payload, "Movimentação de estoque registrada.", "POST");
  }

  async function submitAdminEmployee(event, form) {
    event.preventDefault();
    const mode = formValue(form, "mode") || "create";
    const originalUsername = formValue(form, "originalUsername");
    const username = mode === "edit" ? originalUsername : formValue(form, "username");
    if (mode === "edit" && isCurrentUsername(username)) {
      renderToast("Voce nao pode alterar as permissoes da propria conta.", "danger");
      return;
    }
    const password = formValue(form, "password");
    const payload = {
      username,
      displayName: formValue(form, "displayName"),
      role: formValue(form, "role") || "CONSULTA",
      active: formValue(form, "activeSelect") !== "false"
    };
    if (password) {
      payload.password = password;
    }
    const store = formValue(form, "storeId") || writeStore();
    const endpoint = mode === "edit"
      ? "/employees/" + encodeURIComponent(originalUsername)
      : "/employees?store=" + encodeURIComponent(store);
    const submitButton = form.querySelector('button[type="submit"]');
    if (submitButton) submitButton.disabled = true;
    try {
      await window.zentrixApi(endpoint, {
        method: mode === "edit" ? "PUT" : "POST",
        cache: "no-store",
        body: JSON.stringify(payload)
      });
      await window.zentrixApi("/employees/" + encodeURIComponent(username) + "/permissions", {
        method: "PUT",
        cache: "no-store",
        body: JSON.stringify({ permissions: selectedEmployeePermissions(form) })
      });
      form.reset();
      const panel = form.closest("[data-admin-form]");
      if (panel) panel.hidden = true;
      releaseAdminFormLock();
      clearApiCache();
      renderToast(mode === "edit" ? "Funcionário atualizado." : "Funcionário cadastrado.", "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível salvar o funcionário.", "danger");
    } finally {
      if (submitButton) submitButton.disabled = false;
    }
  }

  async function submitAdminForm(form, endpoint, payload, successMessage, method) {
    const submitButton = form.querySelector('button[type="submit"]');
    if (submitButton) submitButton.disabled = true;
    try {
      await window.zentrixApi(endpoint, {
        method: method || "POST",
        cache: "no-store",
        body: JSON.stringify(payload)
      });
      form.reset();
      const panel = form.closest("[data-admin-form]");
      if (panel) panel.hidden = true;
      releaseAdminFormLock();
      clearApiCache();
      renderToast(successMessage, "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível salvar.", "danger");
    } finally {
      if (submitButton) submitButton.disabled = false;
    }
  }

  async function handleSyncOutboxAction(button) {
    const id = button.dataset.id;
    const action = button.dataset.action === "sync-dead-letter" ? "dead-letter" : "retry";
    const store = button.dataset.storeId || currentStore || "all";
    if (!id) {
      renderToast("Não encontramos esse evento técnico. Atualize a tela e tente novamente.", "warning");
      return;
    }
    const previousText = button.textContent;
    button.disabled = true;
    button.textContent = action === "retry" ? "Tentando novamente..." : "Pausando...";
    try {
      await window.zentrixApi("/sync/outbox/" + encodeURIComponent(id) + "/" + action + "?store=" + encodeURIComponent(store), {
        method: "POST",
        cache: "no-store",
        body: JSON.stringify({ reason: action === "retry" ? "Nova tentativa solicitada pelo painel" : "Item pausado pelo painel para não travar os próximos eventos" })
      });
      clearApiCache();
      renderToast(action === "retry" ? "Evento colocado novamente para processamento." : "Evento pausado para não travar os próximos processos.", "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não conseguimos atualizar esse evento agora. Tente novamente.", "danger");
    } finally {
      button.disabled = false;
      button.textContent = previousText;
    }
  }

  async function openProductEditor(button) {
    lockAdminFormInteraction();
    const code = button.dataset.code || "";
    const store = button.dataset.storeId || writeStore();
    if (!code) {
      releaseAdminFormLock();
      return;
    }
    try {
      const row = await window.zentrixApi("/admin/produtos/" + encodeURIComponent(code) + "?store=" + encodeURIComponent(store), { cache: "no-store" });
      const panel = viewHost.querySelector('[data-admin-form="product"]');
      const form = panel && panel.querySelector("form");
      if (!panel || !form) return;
      panel.hidden = false;
      setAdminFormTitle(panel, "Editar produto", "Atualize dados comerciais; estoque atual deve ser ajustado pela tela de estoque");
      setFormValue(form, "mode", "edit");
      setFormValue(form, "originalCode", row.code || code);
      setFormValue(form, "storeId", row.storeId || store);
      setFormValue(form, "active", rowIsActive(row) ? "true" : "false");
      setFormValue(form, "code", row.code || code);
      setFormValue(form, "description", row.description || row.name || "");
      setFormValue(form, "unit", row.unit || "UN");
      setFormValue(form, "price", row.price || "");
      setFormValue(form, "costPrice", row.costPrice || "");
      setFormValue(form, "stock", row.currentStock || row.stock || "");
      setFormValue(form, "minStock", row.minimumStock || row.minStock || "");
      setFormValue(form, "category", row.category || "");
      setFormValue(form, "barcode", row.barcode || "");
      setFormSubmitText(form, "Salvar alterações");
      setFieldReadOnly(form, "code", true);
      setFieldReadOnly(form, "stock", true);
      form.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      releaseAdminFormLock();
      renderToast(error.message || "Não foi possível abrir o produto.", "danger");
    }
  }

  async function openClientEditor(button) {
    lockAdminFormInteraction();
    const id = button.dataset.id || "";
    const store = button.dataset.storeId || writeStore();
    if (!id) {
      releaseAdminFormLock();
      return;
    }
    try {
      const row = await window.zentrixApi("/admin/clientes/" + encodeURIComponent(id) + "?store=" + encodeURIComponent(store), { cache: "no-store" });
      const panel = viewHost.querySelector('[data-admin-form="client"]');
      const form = panel && panel.querySelector("form");
      if (!panel || !form) return;
      panel.hidden = false;
      setAdminFormTitle(panel, "Editar cliente", "Atualize cadastro e contatos do cliente");
      setFormValue(form, "mode", "edit");
      setFormValue(form, "id", row.id || id);
      setFormValue(form, "storeId", row.storeId || store);
      setFormValue(form, "active", rowIsActive(row) ? "true" : "false");
      setFormValue(form, "name", row.name || "");
      setFormValue(form, "cpfCnpj", row.cpfCnpj || "");
      setFormValue(form, "phone", row.phone || "");
      setFormValue(form, "email", row.email || "");
      setFormValue(form, "address", row.address || "");
      setFormSubmitText(form, "Salvar alterações");
      form.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      releaseAdminFormLock();
      renderToast(error.message || "Não foi possível abrir o cliente.", "danger");
    }
  }

  async function toggleProductStatus(button) {
    const code = button.dataset.code || "";
    const store = button.dataset.storeId || writeStore();
    const active = button.dataset.active === "true";
    const nextActive = !active;
    if (!code || !window.confirm(nextActive ? "Reativar este produto" : "Inativar este produto")) return;
    await patchStatus("/admin/produtos/" + encodeURIComponent(code) + "/status?store=" + encodeURIComponent(store), nextActive, nextActive ? "Produto reativado." : "Produto inativado.");
  }

  async function toggleClientStatus(button) {
    const id = button.dataset.id || "";
    const store = button.dataset.storeId || writeStore();
    const active = button.dataset.active === "true";
    const nextActive = !active;
    if (!id || !window.confirm(nextActive ? "Reativar este cliente" : "Inativar este cliente")) return;
    await patchStatus("/admin/clientes/" + encodeURIComponent(id) + "/status?store=" + encodeURIComponent(store), nextActive, nextActive ? "Cliente reativado." : "Cliente inativado.");
  }

  async function openEmployeeEditor(button) {
    lockAdminFormInteraction();
    const username = button.dataset.username || "";
    if (!username) {
      releaseAdminFormLock();
      return;
    }
    try {
      const row = await window.zentrixApi("/employees/" + encodeURIComponent(username), { cache: "no-store" });
      const panel = viewHost.querySelector('[data-admin-form="employee"]');
      const form = panel && panel.querySelector("form");
      if (!panel || !form) return;
      panel.hidden = false;
      setAdminFormTitle(panel, "Editar funcionário", "Atualize cadastro, senha e permissões");
      setFormValue(form, "mode", "edit");
      setFormValue(form, "originalUsername", row.username || username);
      setFormValue(form, "storeId", row.storeId || writeStore());
      setFormValue(form, "username", row.username || username);
      setFormValue(form, "displayName", row.displayName || row.name || "");
      setFormValue(form, "password", "");
      setFormValue(form, "role", roleKey(row.role));
      setFormValue(form, "activeSelect", rowIsActive(row) ? "true" : "false");
      setFormValue(form, "active", rowIsActive(row) ? "true" : "false");
      setFieldReadOnly(form, "username", true);
      setEmployeePasswordRequired(form, false);
      setEmployeePermissions(form, employeePermissions(row));
      setFormSubmitText(form, "Salvar alterações");
      form.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      releaseAdminFormLock();
      renderToast(error.message || "Não foi possível abrir o funcionário.", "danger");
    }
  }

  async function toggleEmployeeStatus(button) {
    const username = button.dataset.username || "";
    const active = button.dataset.active === "true";
    const nextActive = !active;
    if (!username || !window.confirm(nextActive ? "Reativar este funcionário?" : "Inativar este funcionário?")) return;
    try {
      await window.zentrixApi("/employees/" + encodeURIComponent(username) + "/status?active=" + encodeURIComponent(String(nextActive)), {
        method: "PATCH",
        cache: "no-store"
      });
      clearApiCache();
      renderToast(nextActive ? "Funcionário reativado." : "Funcionário inativado.", "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível alterar o funcionário.", "danger");
    }
  }

  async function submitFinancialEntry(event, form) {
    event.preventDefault();
    const mode = formValue(form, "mode") || "create";
    const id = formValue(form, "id");
    const store = formValue(form, "storeId") || writeStore();
    const payload = {
      type: formValue(form, "type") || "RECEITA",
      category: formValue(form, "category"),
      description: formValue(form, "description"),
      amount: decimalField(formValue(form, "amount")),
      entryDate: formValue(form, "entryDate") || todayDateValue(),
      status: formValue(form, "status") || "PAGO",
      notes: formValue(form, "notes")
    };
    const endpoint = mode === "edit"
      ? "/finance/entries/" + encodeURIComponent(id) + "?store=" + encodeURIComponent(store)
      : "/finance/entries?store=" + encodeURIComponent(store);
    await submitAdminForm(form, endpoint, payload, mode === "edit" ? "Lançamento atualizado." : "Lançamento registrado.", mode === "edit" ? "PUT" : "POST");
  }

  async function openFinancialEntryEditor(button) {
    lockAdminFormInteraction();
    const id = button.dataset.id || "";
    const store = button.dataset.storeId || writeStore();
    if (!id) {
      releaseAdminFormLock();
      return;
    }
    try {
      const row = await window.zentrixApi("/finance/entries/" + encodeURIComponent(id) + "?store=" + encodeURIComponent(store), { cache: "no-store" });
      const panel = viewHost.querySelector('[data-admin-form="finance"]');
      const form = panel && panel.querySelector("form");
      if (!panel || !form) return;
      panel.hidden = false;
      setAdminFormTitle(panel, "Editar lançamento", "Atualize dados financeiros manuais com auditoria");
      setFormValue(form, "mode", "edit");
      setFormValue(form, "id", row.id || id);
      setFormValue(form, "storeId", row.storeId || store);
      setFormValue(form, "type", row.type || "RECEITA");
      setFormValue(form, "category", row.category || "");
      setFormValue(form, "description", row.description || "");
      setFormValue(form, "amount", row.amount || "");
      setFormValue(form, "entryDate", row.entryDate || todayDateValue());
      setFormValue(form, "status", row.status || "PAGO");
      setFormValue(form, "notes", row.notes || "");
      setFormSubmitText(form, "Salvar alterações");
      form.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      releaseAdminFormLock();
      renderToast(error.message || "Não foi possível abrir o lançamento.", "danger");
    }
  }

  async function setFinancialEntryStatus(button) {
    const id = button.dataset.id || "";
    const store = button.dataset.storeId || writeStore();
    const status = button.dataset.nextStatus || "";
    if (!id || !status) return;
    const label = status === "PAGO" ? "marcar como pago" : status === "CANCELADO" ? "cancelar" : "marcar como pendente";
    if (!window.confirm("Deseja " + label + " este lançamento")) return;
    button.disabled = true;
    try {
      await window.zentrixApi("/finance/entries/" + encodeURIComponent(id) + "/status?store=" + encodeURIComponent(store), {
        method: "PATCH",
        cache: "no-store",
        body: JSON.stringify({ status, reason: "Alteração feita no AppGestão" })
      });
      clearApiCache();
      renderToast("Status financeiro atualizado.", "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível alterar o lançamento.", "danger");
    } finally {
      button.disabled = false;
    }
  }

  async function createManualBackup() {
    try {
      const result = await window.zentrixApi("/backups/manual?store=" + encodeURIComponent(writeStore()), {
        method: "POST",
        cache: "no-store",
        body: JSON.stringify({ reason: "Backup manual solicitado no AppGestão" }),
        timeoutMs: 30000
      });
      clearApiCache();
      renderToast(result && result.message ? result.message : "Backup gerado com sucesso.", "success");
      loadPageData({ fresh: true, userInitiated: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível gerar o backup agora.", "danger");
    }
  }

  function downloadRealBackup(id) {
    if (!id) {
      renderToast("Backup indisponível para download.", "warning");
      return;
    }
    window.location.href = currentApiBase() + "/backups/" + encodeURIComponent(id) + "/download";
  }

  async function prepareBackupRestore(id) {
    const preview = await window.zentrixApi("/backups/" + encodeURIComponent(id) + "/restore/preview", {
      cache: "no-store"
    });
    if (!preview.restoreAvailable) {
      throw new Error((preview.warnings || []).join(" ") || "Este backup não está disponível para restauração.");
    }
    const impact = Number(preview.totalRows || 0).toLocaleString("pt-BR");
    const confirmation = window.prompt(
      "Esta operação substituirá os dados da loja pelo backup de " + (preview.createdAt || "data informada")
      + " (" + impact + " registros). Para preparar, digite exatamente:\n" + preview.requiredConfirmation
    );
    if (confirmation !== preview.requiredConfirmation) {
      if (confirmation !== null) renderToast("A confirmação não confere. Nenhum dado foi alterado.", "warning");
      return;
    }
    const staged = await window.zentrixApi("/backups/" + encodeURIComponent(id) + "/restore", {
      method: "POST",
      cache: "no-store",
      body: JSON.stringify({ confirmation })
    });
    if (staged.status !== "RESTORE_STAGED") {
      throw new Error(staged.message || "A restauração foi bloqueada durante a validação.");
    }
    const tables = Object.entries(staged.tables || {})
      .filter((entry) => Number(entry[1]) > 0)
      .map((entry) => entry[0] + ": " + entry[1])
      .join("\n");
    const applyText = "APLICAR RESTAURACAO " + staged.stagingId;
    const applyConfirmation = window.prompt(
      "Validação concluída. Serão restaurados " + staged.totalRows + " registros"
      + (tables ? ":\n" + tables : "")
      + "\n\nSe ocorrer qualquer erro, tudo será desfeito automaticamente."
      + "\nPara aplicar, digite exatamente:\n" + applyText
    );
    if (applyConfirmation !== applyText) {
      renderToast("Backup preparado, mas ainda não aplicado. Seus dados atuais continuam iguais.", "warning");
      return;
    }
    const result = await window.zentrixApi("/backups/restore-staging/" + encodeURIComponent(staged.stagingId) + "/apply", {
      method: "POST",
      cache: "no-store",
      body: JSON.stringify({ confirmation: applyConfirmation }),
      timeoutMs: 60000
    });
    clearApiCache();
    renderToast(result.message || "Backup restaurado com sucesso.", "success");
    await loadPageData({ fresh: true, userInitiated: true });
  }

  async function patchStatus(endpoint, active, successMessage) {
    try {
      await window.zentrixApi(endpoint, {
        method: "PATCH",
        cache: "no-store",
        body: JSON.stringify({ active, reason: "Alteração feita no AppGestão" })
      });
      clearApiCache();
      renderToast(successMessage, "success");
      loadPageData({ fresh: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível alterar o status.", "danger");
    }
  }

  async function submitSettings(event, form) {
    event.preventDefault();
    const submitButton = form.querySelector('button[type="submit"]');
    const payload = settingsPayload(form);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "Salvando...";
    }
    try {
      await window.zentrixApi("/settings?store=" + encodeURIComponent(currentStore || "all"), {
        method: "PUT",
        cache: "no-store",
        body: JSON.stringify(payload)
      });
      clearApiCache();
      renderToast("Configurações salvas com sucesso.", "success");
      loadPageData({ fresh: true, userInitiated: true });
    } catch (error) {
      renderToast(error.message || "Não foi possível salvar as configurações.", "danger");
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "Salvar configurações";
      }
    }
  }

  function settingsPayload(form) {
    const payload = {};
    Array.from(form.elements).forEach((field) => {
      if (!field.name || field.disabled || field.type === "submit") return;
      if (field.type === "checkbox") {
        payload[field.name] = field.checked;
        return;
      }
      payload[field.name] = field.value;
    });
    payload.reason = "Alteração feita em Configurações";
    return payload;
  }

  function schedulePrefetch(page) {
    cancelPrefetch();
    prefetchTimer = window.setTimeout(() => {
      const paths = prefetchPaths(page || currentPageName());
      paths.forEach((path, index) => {
        const job = window.setTimeout(() => {
          prefetchJobs.delete(job);
          prefetchApi(path);
        }, index * 120);
        prefetchJobs.add(job);
      });
    }, 900);
  }

  function cancelPrefetch() {
    if (prefetchTimer) {
      window.clearTimeout(prefetchTimer);
      prefetchTimer = null;
    }
    prefetchJobs.forEach((job) => window.clearTimeout(job));
    prefetchJobs.clear();
  }

  function prefetchPaths(page) {
    const paths = new Set();
    PREFETCH_PERIODS.forEach((period) => {
      if (period === currentPeriod) return;
      pageApiPaths(page, period, currentStore).forEach((path) => paths.add(path));
    });
    paths.add("/stores");
    return Array.from(paths).slice(0, 8);
  }

  function pageApiPaths(page, period, store) {
    const domain = pageDomains()[page];
    if (!domain || !Array.isArray(domain.endpoints)) return [];
    const suffix = domain.query === "period" ? queryString(period, store) : queryString(null, store);
    return domain.endpoints.map((endpoint) => appendQuery(endpoint, suffix));
  }

  function periodLoadSnapshot() {
    return { page: currentPageName(), period: currentPeriod, store: currentStore };
  }

  function isStalePeriodLoad(snapshot) {
    return !snapshot
      || snapshot.page !== currentPageName()
      || snapshot.period !== currentPeriod
      || snapshot.store !== currentStore;
  }

  async function loadPageData(options) {
    const silent = options && options.silent;
    const userInitiated = options && options.userInitiated;
    const page = currentPageName();
    if (silent && hasActiveAdminForm()) {
      await refreshChrome().catch(() => null);
      return;
    }
    if (!ensurePageAccess(page, silent)) {
      return;
    }
    if (loadingData && !userInitiated) {
      pendingDataReload = true;
      return;
    }
    const loadId = ++loadSequence;
    loadingData = true;
    cancelPrefetch();
    if (!silent) {
      setPeriodControlBusy(true);
    }
    const previousForceFresh = forceFreshData;
    forceFreshData = Boolean(options && options.fresh);
    const restoredFromCache = !silent && restoreCachedView(page);
    const domain = pageDomains()[page];
    const loader = domain ? pageRenderers()[domain.renderer] : null;
    if (!silent && !restoredFromCache && !body.classList.contains("app-data-ready")) {
      viewHost.innerHTML = '<section class="skeleton" aria-label="Carregando dados"></section>';
    }
    try {
      const hadStores = Array.isArray(storesCache) && storesCache.length > 0;
      const storesPromise = ensureStores().catch(() => null);
      const chromePromise = refreshChrome().catch(() => null);
      if (loader) {
        renderingSilentLoad = Boolean(silent);
        const rendered = await loader();
        renderingSilentLoad = false;
        if (rendered === false || loadId !== loadSequence) {
          return;
        }
        wirePageActions(page);
        cacheCurrentView(page);
        body.classList.add("app-data-ready");
      }
      await storesPromise;
      if (!hadStores && Array.isArray(storesCache) && storesCache.length > 1 && !silent) {
        wireStoreTabs();
      }
      await chromePromise;
      if (loadId !== loadSequence) {
        return;
      }
      schedulePrefetch(page);
    } catch (error) {
      if (loadId !== loadSequence) {
        return;
      }
      await refreshChrome().catch(() => null);
      renderPageFallback(page, error.message || "Não foi possível carregar os dados.");
    } finally {
      renderingSilentLoad = false;
      if (loadId === loadSequence) {
        body.classList.add("app-data-ready");
        loadingData = false;
        forceFreshData = previousForceFresh;
        if (!silent) {
          setPeriodControlBusy(false);
        }
        if (pendingDataReload) {
          pendingDataReload = false;
          loadPageData({ silent: true });
        }
      }
    }
  }

  function setupAutoRefresh() {
    if (refreshTimer) {
      window.clearInterval(refreshTimer);
    }
    refreshTimer = window.setInterval(() => {
      if (document.hidden || !body.classList.contains("is-authenticated")) {
        return;
      }
      if (hasActiveAdminForm()) {
        refreshChrome().catch(() => null);
        return;
      }
      loadPageData({ silent: true, fresh: true });
    }, 8000);
  }

  function pageDomains() {
    return window.ZentrixPageDomains || {
      dashboard: { renderer: "renderDashboard", endpoints: ["/dashboard"], query: "period" },
      vendas: { renderer: "renderSales", endpoints: ["/sales"], query: "period" },
      financeiro: { renderer: "renderFinance", endpoints: ["/finance", "/finance/entries"], query: "period" },
      caixa: { renderer: "renderCash", endpoints: ["/cash-sessions"], query: "period" },
      produtos: { renderer: "renderProducts", endpoints: ["/admin/produtos"], query: "store" },
      estoque: { renderer: "renderStock", endpoints: ["/admin/produtos?limit=500", "/stock/alerts", "/stock/movements"], query: "store" },
      clientes: { renderer: "renderClients", endpoints: ["/admin/clientes"], query: "store" },
      funcionarios: { renderer: "renderEmployees", endpoints: ["/employees"], query: "store" },
      auditoria: { renderer: "renderAudit", endpoints: ["/audit"], query: "period" },
      relatorios: { renderer: "renderReports", endpoints: ["/reports"], query: "period" },
      backups: { renderer: "renderBackups", endpoints: ["/backups"], query: "store" },
      configuracoes: { renderer: "renderOwnerSettings", endpoints: ["/settings"], query: "store" }
    };
  }

  function pageRenderers() {
    if (window.ZentrixPageRenderers && typeof window.ZentrixPageRenderers.create === "function") {
      if (!rendererCache) {
        rendererCache = window.ZentrixPageRenderers.create(pageRuntimeContext());
      }
      return rendererCache;
    }
    return {};
  }

  function pageRuntimeContext() {
    return {
      get currentPeriod() { return currentPeriod; },
      get currentStore() { return currentStore; },
      activeStoreLabel,
      activeStoreName,
      auditTone,
      auditTimelineHtml,
      backupIntegrityHtml,
      backupTimelineHtml,
      barChartHtml,
      cashDifferenceTag,
      cashDifferenceValue,
      cashTimelineHtml,
      clientCardHtml,
      clientFormHtml,
      compactSalesList,
      currentPageName,
      currentUserFirstName,
      dashboardMetrics,
      dataTableHtml,
      diagnosticsHtml,
      emptyState,
      employeeCardHtml,
      employeeFormHtml,
      esc,
      escAttr,
      filterStrip,
      financialEntryCardHtml,
      financialEntryFormHtml,
      formatCurrency,
      greeting,
      isStalePeriodLoad,
      metricCard,
      moneyValue,
      paymentsHtml,
      periodLabel,
      periodLoadSnapshot,
      periodQuery,
      productCardHtml,
      productFormHtml,
      rankingHtml,
      renderShell,
      reportCard,
      reportMetricCard,
      reportModuleCard,
      reportSummaryPanel,
      reportsHistoryHtml,
      roleTone,
      rowIsActive,
      saleReceiptHtml,
      saveViewState,
      setStores,
      settingsCard,
      setupCsvExport,
      searchableCardHtml,
      searchableDataTableHtml,
      searchableRowAttrs,
      searchPanelHtml,
      statusRowsHtml,
      stockCardHtml,
      stockFilterValue,
      stockLevel,
      stockMovementFormHtml,
      storeQuery,
      storeTabsHtml,
      storesListHtml,
      sumMoney,
      tag,
      metricFilterCard,
      employeePermissions,
      wireReportActions
    };
  }

  function renderShell(title, subtitle, content, options) {
    if (renderingSilentLoad && hasActiveAdminForm() && body.classList.contains("app-data-ready")) {
      return false;
    }
    const header = options && options.hideHeader ? "" : `<div class="page-header"><div><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div>${options && options.actions ? `<div class="page-actions">${options.actions}</div>` : ""}</div>`;
    const tabs = options && options.noStoreTabs ? "" : storeTabsHtml();
    viewHost.innerHTML = `${header}${tabs}${content}`;
    wireStoreTabs();
  }

  function renderError(message) {
    const detail = message ? friendlyMessage(message) : "Verifique se o servidor do Zentrix está ligado.";
    viewHost.innerHTML = `<section class="state-box"><div><strong>Não conseguimos mostrar os dados agora</strong><p>${esc(detail)}</p></div></section>`;
  }

  function renderPageFallback(page, message) {
    const labels = {
      dashboard: ["Dashboard", "Indicadores da operação em tempo real"],
      vendas: ["Vendas", "Consulta de vendas, itens e pagamentos"],
      financeiro: ["Financeiro", "Receitas, despesas e fechamento"],
      caixa: ["Caixa", "Sessões abertas, fechadas e movimentações"],
      produtos: ["Produtos", "Cadastro, preço, categoria e status"],
      estoque: ["Estoque", "Níveis mínimos, entradas e saídas"],
      clientes: ["Clientes", "Cadastro e relacionamento"],
      funcionarios: ["Funcionários", "Equipe e permissões"],
      auditoria: ["Auditoria", "Ações sensíveis e registros importantes"],
      relatorios: ["Relatórios", "Exportações por período"],
      backups: ["Backups", "Histórico e segurança dos dados"],
      configuracoes: ["Configurações", "Empresa, usuários e preferências"]
    };
    const [title, subtitle] = labels[page] || ["Painel", "Dados do Zentrix AppGestão"];
    renderShell(title, subtitle, `
      <section class="panel">
        <div class="panel-title">
          <div><h3>Dados temporariamente indisponíveis</h3><span>${esc(friendlyMessage(message))}</span></div>
          <button class="button btn-primary compact-button" type="button" data-action="retry-page-load">Atualizar</button>
        </div>
        ${emptyState("Aguardando uma resposta válida do Zentrix.")}
      </section>
    `);
    const retryButton = viewHost.querySelector('[data-action="retry-page-load"]');
    if (retryButton) {
      retryButton.addEventListener("click", () => loadPageData({ userInitiated: true, fresh: true }));
    }
  }

  function metricCard(label, value, note, toneValue, icon, trend) {
    return `<article class="metric-card">
      <div class="metric-top"><span class="metric-icon ${toneValue || "info"}">${esc(icon || "#")}</span><span class="tag ${tone(toneValue)}">${esc(trend || "Atual")}</span></div>
      <span class="metric-label">${esc(label)}</span>
      <strong class="metric-value">${esc(value)}</strong>
      <span class="metric-note">${esc(note || "Atualizado pelo Zentrix.")}</span>
    </article>`;
  }

  function dataTableHtml(title, headers, rows, mapper, exportId, rowAttrs) {
    return `<section class="table-panel">
      <div class="table-title"><h3>${esc(title)}</h3><div class="table-actions"><span>${esc(String(rows.length))} registros</span><button class="button btn-light compact-button" id="${esc(exportId)}" type="button">Exportar CSV</button></div></div>
      <div class="table-wrap"><table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>
        ${rows.map((row) => `<tr>${mapper(row).map((value) => `<td>${isTrustedTag(value) ? value : esc(value)}</td>`).join("")}</tr>`).join("") || `<tr><td colspan="${headers.length}">${emptyState("Ainda não há informações para este período.")}</td></tr>`}
      </tbody></table></div>
    </section>`;
  }

  function searchableDataTableHtml(title, headers, rows, mapper, exportId, rowAttrs) {
    return `<section class="table-panel">
      <div class="table-title"><h3>${esc(title)}</h3><div class="table-actions"><span>${esc(String(rows.length))} registros</span><button class="button btn-light compact-button" id="${esc(exportId)}" type="button">Exportar CSV</button></div></div>
      <div class="table-wrap"><table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>
        ${rows.map((row) => `<tr ${typeof rowAttrs === "function" ? rowAttrs(row) : ""}>${mapper(row).map((value) => `<td>${isTrustedTag(value) ? value : esc(value)}</td>`).join("")}</tr>`).join("") || `<tr><td colspan="${headers.length}">${emptyState("Ainda não há informações para este período.")}</td></tr>`}
      </tbody></table></div>
    </section>`;
  }

  function searchPanelHtml(scope, placeholder, filters) {
    const safeScope = escAttr(scope);
    const options = (filters || [["all", "Todos"]]).map(([value, label]) => `<option value="${escAttr(value)}">${esc(label)}</option>`).join("");
    return `<section class="search-panel" data-search-controls="${safeScope}">
      <label class="search-field"><span>Buscar</span><input class="text-field" type="search" placeholder="${escAttr(placeholder)}" data-search-input="${safeScope}" autocomplete="off" /></label>
      <label class="search-filter"><span>Filtro</span><select class="select-field" data-search-filter="${safeScope}">${options}</select></label>
      <button class="button btn-light compact-button" type="button" data-action="clear-list-filter" data-filter-scope="${safeScope}">Limpar</button>
      <span class="chip info"><strong data-search-count="${safeScope}">0</strong>&nbsp;itens visíveis</span>
    </section>`;
  }

  function searchableCardHtml(scope, row, html, searchText, filterValue) {
    return `<div class="search-result-card" data-search-scope="${escAttr(scope)}" data-search-item data-search-text="${escAttr(searchText)}" data-filter-value="${escAttr(filterValue || "all")}">${html}</div>`;
  }

  function searchableRowAttrs(scope, searchText, filterValue) {
    return `data-search-scope="${escAttr(scope)}" data-search-item data-search-text="${escAttr(searchText)}" data-filter-value="${escAttr(filterValue || "all")}"`;
  }

  function metricFilterCard(scope, filterValue, label, value, note, toneValue, icon, trend) {
    return `<button class="metric-card metric-action-card" type="button" data-action="apply-list-filter" data-filter-scope="${escAttr(scope)}" data-filter-value="${escAttr(filterValue)}">
      <div class="metric-top"><span class="metric-icon ${toneValue || "info"}">${esc(icon || "#")}</span><span class="tag ${tone(toneValue)}">${esc(trend || "Atual")}</span></div>
      <span class="metric-label">${esc(label)}</span>
      <strong class="metric-value">${esc(value)}</strong>
      <span class="metric-note">${esc(note || "Atualizado pelo Zentrix.")}</span>
    </button>`;
  }

  function stockFilterValue(row) {
    const current = Number(row && row.currentStock || 0);
    const minimum = Number(row && row.minimumStock || 0);
    if (current <= 0) return "critical low";
    if (minimum > 0 && current <= minimum) return "low";
    return "healthy";
  }

  function filterStrip(items) {
    return `<div class="filter-strip">${items.map(([label, value]) => `<span class="chip info">${esc(label)}: ${esc(value)}</span>`).join("")}</div>`;
  }

  function productFormHtml() {
    return `<section class="panel" hidden data-admin-form="product" style="margin-bottom: 16px">
      <div class="panel-title"><div><h3>Novo produto</h3><span>Cadastro direto no AppGestão para uso na loja</span></div></div>
      <form class="form-grid">
        <input type="hidden" name="mode" value="create" />
        <input type="hidden" name="originalCode" />
        <input type="hidden" name="storeId" />
        <input type="hidden" name="active" value="true" />
        <label class="field"><span>Código interno</span><input class="text-field" name="code" required /></label>
        <label class="field"><span>Nome</span><input class="text-field" name="description" required /></label>
        <label class="field"><span>Unidade</span><input class="text-field" name="unit" value="UN" /></label>
        <label class="field"><span>Preço de venda</span><input class="text-field" name="price" inputmode="decimal" required /></label>
        <label class="field"><span>Preço de custo</span><input class="text-field" name="costPrice" inputmode="decimal" /></label>
        <label class="field"><span>Estoque inicial</span><input class="text-field" name="stock" inputmode="decimal" /></label>
        <label class="field"><span>Estoque mínimo</span><input class="text-field" name="minStock" inputmode="decimal" /></label>
        <label class="field"><span>Categoria</span><input class="text-field" name="category" /></label>
        <label class="field full"><span>Código de barras</span><input class="text-field" name="barcode" /></label>
        <div class="form-line full"><button class="button btn-primary" type="submit">Salvar produto</button><button class="button btn-light" type="button" data-action="cancel-admin-form">Cancelar</button></div>
      </form>
    </section>`;
  }

  function clientFormHtml() {
    return `<section class="panel" hidden data-admin-form="client" style="margin-bottom: 16px">
      <div class="panel-title"><div><h3>Novo cliente</h3><span>Cadastro direto no AppGestão</span></div></div>
      <form class="form-grid">
        <input type="hidden" name="mode" value="create" />
        <input type="hidden" name="id" />
        <input type="hidden" name="storeId" />
        <input type="hidden" name="active" value="true" />
        <label class="field full"><span>Nome</span><input class="text-field" name="name" required /></label>
        <label class="field"><span>CPF/CNPJ</span><input class="text-field" name="cpfCnpj" /></label>
        <label class="field"><span>Telefone</span><input class="text-field" name="phone" /></label>
        <label class="field full"><span>E-mail</span><input class="text-field" name="email" type="email" /></label>
        <label class="field full"><span>Endereço</span><input class="text-field" name="address" /></label>
        <div class="form-line full"><button class="button btn-primary" type="submit">Salvar cliente</button><button class="button btn-light" type="button" data-action="cancel-admin-form">Cancelar</button></div>
      </form>
    </section>`;
  }

  function stockMovementFormHtml() {
    return `<section class="panel" hidden data-admin-form="stock" style="margin-bottom: 16px">
      <div class="panel-title"><div><h3>Movimentar estoque</h3><span>Registre entrada, saída ou ajuste manual com motivo</span></div></div>
      <form class="form-grid">
        <label class="field"><span>Código do produto</span><input class="text-field" name="productCode" required /></label>
        <label class="field"><span>Tipo</span><select class="select-field" name="type"><option value="ENTRADA">Entrada</option><option value="SAIDA_MANUAL">Saída manual</option><option value="AJUSTE">Ajuste</option></select></label>
        <label class="field"><span>Quantidade</span><input class="text-field" name="quantity" inputmode="decimal" required /></label>
        <label class="field full"><span>Motivo</span><input class="text-field" name="reason" required /></label>
        <div class="form-line full"><button class="button btn-primary" type="submit">Registrar movimento</button><button class="button btn-light" type="button" data-action="cancel-admin-form">Cancelar</button></div>
      </form>
    </section>`;
  }

  function employeeFormHtml() {
    return `<section class="panel" hidden data-admin-form="employee" style="margin-bottom: 16px">
      <div class="panel-title"><div><h3>Novo funcionário</h3><span>Cadastro administrativo com senha e permissões</span></div></div>
      <form class="form-grid">
        <input type="hidden" name="mode" value="create" />
        <input type="hidden" name="originalUsername" />
        <input type="hidden" name="storeId" />
        <input type="hidden" name="active" value="true" />
        <label class="field"><span>Login</span><input class="text-field" name="username" required autocomplete="username" /></label>
        <label class="field"><span>Nome</span><input class="text-field" name="displayName" required autocomplete="name" /></label>
        <label class="field"><span>Senha</span><input class="text-field" name="password" type="password" autocomplete="new-password" /></label>
        <label class="field"><span>Cargo</span><select class="select-field" name="role">
          <option value="ADMIN">ADMIN</option>
          <option value="GERENTE">GERENTE</option>
          <option value="CAIXA">CAIXA</option>
          <option value="ESTOQUISTA">ESTOQUISTA</option>
          <option value="FINANCEIRO">FINANCEIRO</option>
          <option value="CONSULTA">CONSULTA</option>
        </select></label>
        <label class="field"><span>Status</span><select class="select-field" name="activeSelect"><option value="true">Ativo</option><option value="false">Inativo</option></select></label>
        <div class="permission-grid full">${employeePermissionsHtml()}</div>
        <div class="form-line full"><button class="button btn-primary" type="submit">Salvar funcionário</button><button class="button btn-light" type="button" data-action="cancel-admin-form">Cancelar</button></div>
      </form>
    </section>`;
  }

  function employeePermissionsHtml() {
    const groups = new Map();
    EMPLOYEE_PERMISSIONS.forEach(([group, value, label]) => {
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push([value, label]);
    });
    return Array.from(groups.entries()).map(([group, permissions]) => `
      <section class="permission-group">
        <h4>${esc(group)}</h4>
        ${permissions.map(([value, label]) => `<label class="permission-row"><span>${esc(label)}</span><span class="switch"><input type="checkbox" name="permissions" value="${escAttr(value)}" /><span></span></span></label>`).join("")}
      </section>
    `).join("");
  }

  function financialEntryFormHtml() {
    return `<section class="panel" hidden data-admin-form="finance" style="margin-bottom: 16px">
      <div class="panel-title"><div><h3>Novo lançamento</h3><span>Receita ou despesa manual com auditoria</span></div></div>
      <form class="form-grid">
        <input type="hidden" name="mode" value="create" />
        <input type="hidden" name="id" />
        <input type="hidden" name="storeId" />
        <label class="field"><span>Tipo</span><select class="select-field" name="type"><option value="RECEITA">Receita</option><option value="DESPESA">Despesa</option></select></label>
        <label class="field"><span>Status</span><select class="select-field" name="status"><option value="PAGO">Pago</option><option value="PENDENTE">Pendente</option><option value="CANCELADO">Cancelado</option></select></label>
        <label class="field"><span>Data</span><input class="text-field" name="entryDate" type="date" required /></label>
        <label class="field"><span>Valor</span><input class="text-field" name="amount" inputmode="decimal" required /></label>
        <label class="field"><span>Categoria</span><input class="text-field" name="category" required /></label>
        <label class="field full"><span>Descrição</span><input class="text-field" name="description" required /></label>
        <label class="field full"><span>Observações</span><input class="text-field" name="notes" /></label>
        <div class="form-line full"><button class="button btn-primary" type="submit">Salvar lançamento</button><button class="button btn-light" type="button" data-action="cancel-admin-form">Cancelar</button></div>
      </form>
    </section>`;
  }

  function dashboardMetrics(data) {
    const metricMap = new Map((data.metrics || []).map((item) => [normalizeKey(item.label), item]));
    const revenue = metricMap.get("faturamento") || {};
    const sales = metricMap.get("vendas pagas") || {};
    const ticket = metricMap.get("ticket medio") || metricMap.get("ticket médio") || {};
    const stock = metricMap.get("estoque baixo") || {};
    const productsSold = (data.topProducts || []).reduce((total, row) => total + Number(row.quantity || 0), 0);
    return [
      { label: "Vendas hoje", value: sales.value || "0", note: "Quantidade de vendas pagas", tone: "success", icon: "$", trend: sales.trend || periodLabel() },
      { label: "Faturamento", value: revenue.value || "R$ 0,00", note: activeStoreLabel(data), tone: "info", icon: "R$", trend: revenue.trend || periodLabel() },
      { label: "Lucro estimado", value: "Em análise", note: "Depende do custo cadastrado", tone: "warning", icon: "%", trend: "Estimativa" },
      { label: "Ticket médio", value: ticket.value || "R$ 0,00", note: "Média por venda concluída", tone: "info", icon: "~", trend: ticket.trend || periodLabel() },
      { label: "Produtos vendidos", value: productsSold ? String(Math.round(productsSold)) : "0", note: "Itens presentes no ranking", tone: "success", icon: "#", trend: "Top itens" },
      { label: "Caixa atual", value: data.cashStatus || "Operacional", note: "Status operacional da loja", tone: "success", icon: "CX", trend: "Loja" },
      { label: "Estoque crítico", value: stock.value || "0", note: stock.trend || "Produtos em atenção", tone: "danger", icon: "!", trend: "Crítico" }
    ];
  }

  function productCardHtml(row) {
    const level = stockLevel(row);
    const active = rowIsActive(row);
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar info">${esc(initials(row.name || row.code))}</span>${tag(row.status)}</div>
      <strong>${esc(row.name)}</strong>
      <div class="stock-meter"><header><span>Estoque</span><strong>${esc(String(row.currentStock))}/${esc(String(row.minimumStock))}</strong></header><div class="progress-track"><span style="width: ${level.width}%"></span></div></div>
      <div class="entity-meta">
        <div><span>Loja</span><strong>${esc(row.store)}</strong></div>
        <div><span>Código</span><strong>${esc(row.code)}</strong></div>
        <div><span>Categoria</span><strong>${esc(row.category)}</strong></div>
        <div><span>Preço</span><strong>${esc(row.price)}</strong></div>
      </div>
      <div class="entity-actions">
        <button class="button btn-light compact-button" type="button" data-action="edit-product" data-code="${escAttr(row.code)}" data-store-id="${escAttr(row.storeId || writeStore())}">Editar</button>
        <button class="button ${active ? "btn-light" : "btn-primary"} compact-button" type="button" data-action="toggle-product-status" data-code="${escAttr(row.code)}" data-store-id="${escAttr(row.storeId || writeStore())}" data-active="${active ? "true" : "false"}">${active ? "Inativar" : "Reativar"}</button>
      </div>
    </article>`;
  }

  function stockCardHtml(row) {
    const level = stockLevel(row);
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar ${level.tone}">${esc(String(row.currentStock))}</span>${tag(row.status)}</div>
      <strong>${esc(row.name)}</strong>
      <div class="stock-meter"><header><span>Risco de ruptura</span><strong>${esc(level.label)}</strong></header><div class="progress-track"><span style="width: ${level.width}%"></span></div></div>
      <div class="entity-meta">
        <div><span>Loja</span><strong>${esc(row.store)}</strong></div>
        <div><span>Código</span><strong>${esc(row.code)}</strong></div>
        <div><span>Estoque mínimo</span><strong>${esc(String(row.minimumStock))}</strong></div>
        <div><span>Preço</span><strong>${esc(row.price)}</strong></div>
      </div>
    </article>`;
  }

  function clientCardHtml(row) {
    const active = rowIsActive(row);
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar info">${esc(initials(row.name))}</span>${tag(row.status || "Cliente")}</div>
      <strong>${esc(row.name || "Cliente")}</strong>
      <div class="entity-meta">
        <div><span>?ltima compra</span><strong>Em análise</strong></div>
        <div><span>Total gasto</span><strong>Em análise</strong></div>
        <div><span>Frequência</span><strong>Atualizada</strong></div>
        <div><span>Telefone</span><strong>${esc(row.phone || "-")}</strong></div>
      </div>
      <div class="entity-actions">
        <button class="button btn-light compact-button" type="button" data-action="edit-client" data-id="${escAttr(row.id)}" data-store-id="${escAttr(row.storeId || writeStore())}">Editar</button>
        <button class="button ${active ? "btn-light" : "btn-primary"} compact-button" type="button" data-action="toggle-client-status" data-id="${escAttr(row.id)}" data-store-id="${escAttr(row.storeId || writeStore())}" data-active="${active ? "true" : "false"}">${active ? "Inativar" : "Reativar"}</button>
      </div>
    </article>`;
  }

  function employeeCardHtml(row) {
    const role = roleTone(row.role);
    const active = rowIsActive(row);
    const displayName = row.displayName || row.name || row.username;
    const permissions = employeePermissions(row);
    const selfAccount = isCurrentUsername(row.username);
    const actions = selfAccount
      ? `<span class="tag warning">Sua conta e protegida</span>`
      : `
        <button class="button btn-light compact-button" type="button" data-action="edit-employee" data-username="${escAttr(row.username)}">Editar</button>
        <button class="button ${active ? "btn-light" : "btn-primary"} compact-button" type="button" data-action="toggle-employee-status" data-username="${escAttr(row.username)}" data-active="${active ? "true" : "false"}">${active ? "Inativar" : "Reativar"}</button>
      `;
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar ${role}">${esc(initials(displayName))}</span>${tag(active ? "Ativo" : "Inativo")}</div>
      <strong>${esc(displayName)}</strong>
      <div class="entity-meta">
        <div><span>Cargo</span><strong>${esc(row.role || "Operador")}</strong></div>
        <div><span>Usuário</span><strong>${esc(row.username)}</strong></div>
        <div><span>Ultimo login</span><strong>${esc(row.lastLoginAt || "-")}</strong></div>
        <div><span>Permissoes configuradas</span><strong>${esc(String(permissions.length))}</strong></div>
      </div>
      <div class="entity-actions">
        ${actions}
      </div>
      <div class="entity-meta" hidden>
        <div><span>Permissões</span><strong>${esc(role === "danger" ? "Administrador" : role === "warning" ? "Gerente" : "Operador")}</strong></div>
      </div>
    </article>`;
  }

  function financialEntryCardHtml(row) {
    const type = String(row.type || "RECEITA").toUpperCase();
    const status = String(row.status || "PAGO").toUpperCase();
    const storeId = row.storeId || writeStore();
    const statusButtons = [
      status !== "PAGO" ? `<button class="button btn-primary compact-button" type="button" data-action="set-finance-status" data-id="${escAttr(row.id)}" data-store-id="${escAttr(storeId)}" data-next-status="PAGO">Pagar</button>` : "",
      status !== "PENDENTE" ? `<button class="button btn-light compact-button" type="button" data-action="set-finance-status" data-id="${escAttr(row.id)}" data-store-id="${escAttr(storeId)}" data-next-status="PENDENTE">Pendente</button>` : "",
      status !== "CANCELADO" ? `<button class="button btn-light compact-button" type="button" data-action="set-finance-status" data-id="${escAttr(row.id)}" data-store-id="${escAttr(storeId)}" data-next-status="CANCELADO">Cancelar</button>` : ""
    ].filter(Boolean).join("");
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar ${financialEntryTypeTone(type)}">${type === "DESPESA" ? "-" : "+"}</span>${tag(status)}</div>
      <strong>${esc(row.description || "Lançamento financeiro")}</strong>
      <div class="entity-meta">
        <div><span>Valor</span><strong>${esc(row.amount || "R$ 0,00")}</strong></div>
        <div><span>Tipo</span><strong>${esc(type)}</strong></div>
        <div><span>Categoria</span><strong>${esc(row.category || "-")}</strong></div>
        <div><span>Data</span><strong>${esc(row.entryDate || "-")}</strong></div>
        <div><span>Loja</span><strong>${esc(row.store || storeId)}</strong></div>
        <div><span>Origem</span><strong>${esc(row.origin || "APPGESTAO")}</strong></div>
      </div>
      <div class="entity-actions">
        <button class="button btn-light compact-button" type="button" data-action="edit-finance-entry" data-id="${escAttr(row.id)}" data-store-id="${escAttr(storeId)}">Editar</button>
        ${statusButtons}
      </div>
    </article>`;
  }

  function reportCard(format, title, description, action) {
    return `<article class="module-card">
      <div class="module-head"><span class="module-icon info">${esc(format)}</span><span class="tag info">${esc(format)}</span></div>
      <div><h3>${esc(title)}</h3><p>${esc(description)}</p></div>
      <button class="button btn-primary" type="button">${esc(action)}</button>
    </article>`;
  }

  function reportModuleCard(row) {
    const formatList = Array.isArray(row.formats) && row.formats.length ? row.formats : ["PDF", "XLS", "CSV"];
    const formats = formatList.map((format) => String(format).toUpperCase() === "XLS" ? "Excel" : format).join(", ");
    const buttons = formatList.map((format) => `
      <button class="button ${String(format).toUpperCase() === "PDF" ? "btn-primary" : "btn-light"} report-action-button" type="button"
        data-report-type="${esc(row.type || row.title || "report")}"
        data-report-title="${esc(row.title || "Relatório")}"
        data-report-format="${esc(format)}"
        data-report-endpoint="${esc(row.endpoint || "/reports")}">
        ${esc(String(format).toUpperCase() === "XLS" ? "Excel" : format)}
      </button>`).join("");
    return `<article class="module-card">
      <div class="module-head"><span class="module-icon info">${esc(initials(row.title || row.type || "Relatório"))}</span><span class="tag info">${esc(formats)}</span></div>
      <div><h3>${esc(row.title || "Relatório")}</h3><p>${esc(row.description || "Relatório profissional do AppGestão.")}</p></div>
      <div class="report-actions" aria-label="Formatos do relatório">${buttons}</div>
    </article>`;
  }

  function reportMetricCard(row) {
    return metricCard(row.label || "Indicador", row.value || "0", row.description || row.note || "Atualizado pelo Zentrix.", row.tone || "info", initials(row.label || "#"), row.trend || periodLabel());
  }

  function reportSummaryPanel(title, report) {
    const cards = report && Array.isArray(report.summaryCards) ? report.summaryCards : [];
    const diagnostics = report && Array.isArray(report.diagnostics) ? report.diagnostics : [];
    return `<section class="panel"><div class="panel-title"><div><h3>${esc(title)}</h3><span>Resumo profissional</span></div></div>
      <div class="stack-list">
        ${cards.slice(0, 4).map((card) => `<div class="list-item"><span class="list-icon ${tone(card.tone)}">${esc(initials(card.label || title))}</span><div><span class="list-title">${esc(card.label)}</span><span class="list-subtitle">${esc(card.description || card.note || "Indicador do relatório")}</span></div><strong>${esc(card.value)}</strong></div>`).join("") || emptyState("Relatório aguardando dados da loja.")}
        ${diagnostics.slice(0, 2).map((item) => `<div class="list-item"><span class="list-icon warning">!</span><div><span class="list-title">Diagnóstico</span><span class="list-subtitle">${esc(item)}</span></div><strong>Loja</strong></div>`).join("")}
      </div>
    </section>`;
  }

  function wireReportActions(overviewData) {
    viewHost.querySelectorAll(".report-action-button").forEach((button) => {
      button.addEventListener("click", async () => {
        const originalText = button.textContent;
        button.disabled = true;
        button.textContent = "Gerando...";
        try {
          const format = String(button.dataset.reportFormat || "PDF").toUpperCase();
          const title = button.dataset.reportTitle || "Relatório";
          const endpoint = normalizeReportEndpoint(button.dataset.reportEndpoint || "/reports");
          const reportData = endpoint === "/reports" && overviewData ? overviewData : await loadReportEndpoint(endpoint, overviewData);
          generateReportFile(format, title, reportData);
          button.textContent = "Pronto";
          setTimeout(() => {
            button.textContent = originalText;
            button.disabled = false;
          }, 900);
        } catch (error) {
          button.textContent = "Erro";
          button.disabled = false;
          renderToast(error.message || "Não foi possível gerar o relatório.", "danger");
        }
      });
    });
  }

  async function loadReportEndpoint(endpoint, fallback) {
    try {
      return await window.zentrixApi(endpoint + periodQuery());
    } catch (error) {
      const legacyEndpoint = legacyReportEndpoint(endpoint);
      if (legacyEndpoint && legacyEndpoint !== endpoint) {
        try {
          return await window.zentrixApi(legacyEndpoint + periodQuery());
        } catch (legacyError) {
          if (fallback) return fallback;
          throw legacyError;
        }
      }
      if (fallback) return fallback;
      throw error;
    }
  }

  function legacyReportEndpoint(endpoint) {
    return {
      "/reports/sales": "/sales",
      "/reports/products": "/products",
      "/reports/stock": "/stock/alerts",
      "/reports/cash": "/cash-sessions",
      "/reports/finance": "/finance",
      "/reports/audit": "/audit"
    }[endpoint] || endpoint;
  }

  function normalizeReportEndpoint(endpoint) {
    const value = String(endpoint || "/reports").trim();
    if (value.startsWith("/api/")) return value.slice(4);
    if (value.startsWith("/")) return value;
    return "/" + value;
  }

  function generateReportFile(format, title, reportData) {
    const exporter = window.ZentrixReportExport;
    if (!exporter || typeof exporter.generateReportFile !== "function") {
      renderToast("Exportador de relatórios indisponível.", "danger");
      return;
    }
    exporter.generateReportFile(format, title, reportData, {
      activeStoreName,
      formatCurrency,
      normalizeText,
      periodLabel,
      quantityLabel,
      renderToast
    });
  }

  function renderToast(message, level) {
    const toast = document.createElement("div");
    toast.className = "toast " + tone(level || "info");
    toast.textContent = normalizeText(message);
    document.body.appendChild(toast);
    setTimeout(() => toast.classList.add("show"), 20);
    setTimeout(() => toast.remove(), 3200);
  }

  function settingsCard(title, subtitle, value, toneValue) {
    return `<article class="module-card">
      <div class="module-head"><span class="module-icon ${toneValue}">${esc(initials(title))}</span><span class="tag ${toneValue}">Configuração</span></div>
      <div><h3>${esc(title)}</h3><p>${esc(subtitle)}</p></div>
      <strong>${esc(value)}</strong>
    </article>`;
  }

  function saleReceiptHtml(row) {
    if (!row) return emptyState("Ainda não há venda no período escolhido.");
    return `<ul class="detail-list">
      <li><span>Código</span><strong>${esc(row.code)}</strong></li>
      <li><span>Loja</span><strong>${esc(row.store)}</strong></li>
      <li><span>Operador</span><strong>${esc(row.operator || "-")}</strong></li>
      <li><span>Pagamento</span><strong>${esc(row.payment || "-")}</strong></li>
      <li><span>Status</span><strong>${tag(row.status)}</strong></li>
      <li><span>Total</span><strong>${esc(row.total || "R$ 0,00")}</strong></li>
    </ul>`;
  }

  function cashDifferenceValue(row) {
    if (!row) return 0;
    if (row.difference && row.difference !== "-") {
      return moneyValue(row.difference);
    }
    if (row.informed && row.informed !== "-" && row.expected && row.expected !== "-") {
      return moneyValue(row.informed) - moneyValue(row.expected);
    }
    return 0;
  }

  function cashDifferenceTag(row) {
    if (!row || !row.informed || row.informed === "-") {
      return tag("Aguardando fechamento");
    }
    const difference = cashDifferenceValue(row);
    if (difference < 0) {
      return `<span class="tag danger">Faltando ${esc(formatCurrency(Math.abs(difference)))}</span>`;
    }
    if (difference > 0) {
      return `<span class="tag warning">Sobra ${esc(formatCurrency(difference))}</span>`;
    }
    return `<span class="tag success">Conferido</span>`;
  }

  function cashTimelineHtml(rows) {
    if (!rows.length) return emptyState("Ainda não há caixa registrado no período escolhido.");
    return `<div class="timeline">${rows.slice(0, 8).map((row) => `
      <div class="timeline-item">
        <span class="list-icon ${tagTone(row.status)}">${String(row.status || "").toLowerCase().includes("aberto") ? "ON" : "OK"}</span>
        <div><span class="list-title">${esc(row.code)} - ${esc(row.operator || "Operador")}</span><span class="list-subtitle">Abertura: ${esc(row.openedAt)} | Fechamento: ${esc(row.closedAt)} | Esperado: ${esc(row.expected || "-")}</span></div>
        <strong>${esc(row.informed && row.informed !== "-" ? row.informed : row.expected)}</strong>
      </div>`).join("")}</div>`;
  }

  function auditTimelineHtml(rows) {
    if (!rows.length) return emptyState("Nenhuma ação importante registrada no período.");
    return `<div class="timeline">${rows.slice(0, 10).map((row) => {
      const level = auditTone(row);
      const dateText = row.dateTime || [row.date, row.time].filter(Boolean).join(" ") || "-";
      return `<div class="timeline-item">
        <span class="list-icon ${level}">${level === "danger" ? "!" : level === "warning" ? "AT" : "IN"}</span>
        <div><span class="list-title">${esc(row.action || "Evento")}</span><span class="list-subtitle">${esc(row.user || "Usuário")} - ${esc(row.description || "-")}</span></div>
        <strong>${esc(dateText)}</strong>
      </div>`;
    }).join("")}</div>`;
  }

  function backupTimelineHtml(rows) {
    if (!rows.length) return emptyState("Ainda não há backup gerado. Use o botão para criar a primeira cópia de segurança.");
    return `<div class="timeline">${rows.slice(0, 8).map((row) => `
      <div class="timeline-item">
        <span class="list-icon ${tagTone(row.status)}">BK</span>
        <div><span class="list-title">${esc(row.fileName || row.origin || "Backup")}</span><span class="list-subtitle">${esc(row.size || "0 B")} | ${esc(row.createdBy || "Sistema")}</span></div>
        <strong>${esc(row.date || "-")}</strong>
      </div>`).join("")}</div>`;
  }

  function backupIntegrityHtml(row) {
    if (!row) {
      return emptyState("Gere o primeiro backup para acompanhar a integridade dos dados.");
    }
    const checksum = row.checksum ? String(row.checksum) : "";
    const shortChecksum = checksum ? checksum.slice(0, 16) + "..." : "Não informado";
    const integrity = row.integrity || "Verificando";
    const fileLabel = row.fileExists ? "Arquivo encontrado" : "Arquivo não encontrado";
    const checksumLabel = row.checksumValid ? "Checksum confirmado" : checksum ? "Checksum divergente" : "Sem checksum";
    return `<div class="stack-list">
      <div class="list-item"><span class="list-icon ${row.checksumValid ? "success" : "warning"}">OK</span><div><span class="list-title">${esc(integrity)}</span><span class="list-subtitle">Status de integridade do arquivo</span></div><strong>${esc(row.status || "-")}</strong></div>
      <div class="list-item"><span class="list-icon ${row.fileExists ? "success" : "danger"}">AR</span><div><span class="list-title">${esc(fileLabel)}</span><span class="list-subtitle">${esc(row.fileName || "Arquivo ainda não gerado")}</span></div><strong>${esc(row.size || "0 B")}</strong></div>
      <div class="list-item"><span class="list-icon ${row.checksumValid ? "success" : "warning"}">SH</span><div><span class="list-title">${esc(checksumLabel)}</span><span class="list-subtitle">${esc(shortChecksum)}</span></div><strong>${esc(String(row.rows || 0))}</strong></div>
    </div>`;
  }

  function reportsHistoryHtml(data) {
    const rows = [
      ["PDF", "Relatório financeiro", data.lastSync || "Aguardando geração"],
      ["XLS", "Produtos e estoque", periodLabel()],
      ["CSV", "Vendas detalhadas", activeStoreName()]
    ];
    return rows.map(([icon, title, subtitle]) => `<div class="list-item"><span class="list-icon info">${esc(icon)}</span><div><span class="list-title">${esc(title)}</span><span class="list-subtitle">${esc(subtitle)}</span></div><strong>Pronto</strong></div>`).join("");
  }

  function diagnosticsHtml(rows) {
    const list = Array.isArray(rows) && rows.length ? rows : ["Dados atualizados e relatórios prontos para análise."];
    return list.map((item, index) => `<div class="list-item"><span class="list-icon ${index ? "info" : "warning"}">${index ? "IN" : "LO"}</span><div><span class="list-title">${index ? "Observação" : "Diagnóstico"}</span><span class="list-subtitle">${esc(item)}</span></div><strong>${index ? "OK" : "Loja"}</strong></div>`).join("");
  }

  function paymentsHtml(rows) {
    return rows.map((row) => `<div class="payment-row"><strong>${esc(row.name)}</strong><div class="progress-track"><span style="width: ${percentWidth(row.percent)}%"></span></div><span>${esc(row.total)}</span></div>`).join("") || emptyState("Ainda não há pagamentos no período escolhido.");
  }

  function barChartHtml(rows) {
    if (!Array.isArray(rows) || !rows.length) return emptyState("Ainda não há dados para o gráfico.");
    const points = compactChartRows(rows);
    const values = points.map((row) => Number(row.value) || 0);
    const max = Math.max(1, ...values);
    const min = Math.min(0, ...values);
    const width = 760;
    const height = 260;
    const ticks = chartTicks(min, max);
    const yAxisLabels = ticks.map((tick) => compactMoney(tick));
    const yAxisLabelX = 8;
    const pad = { top: 22, right: 52, bottom: 54, left: chartYAxisPadding(yAxisLabels, yAxisLabelX) };
    const plotWidth = width - pad.left - pad.right;
    const plotHeight = height - pad.top - pad.bottom;
    const range = Math.max(1, max - min);
    const gradientId = `chartAreaGradient-${points.length}-${Math.round(totalFromRows(points))}`;
    const clipId = `chartClip-${points.length}-${Math.round(max)}-${Math.round(min)}`;
    const coords = points.map((row, index) => {
      const x = pad.left + (points.length === 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth);
      const y = pad.top + plotHeight - (((Number(row.value) || 0) - min) / range) * plotHeight;
      return { ...row, x, y };
    });
    const linePath = coords.map((point, index) => `${index ? "L" : "M"} ${round(point.x)} ${round(point.y)}`).join(" ");
    const areaPath = `${linePath} L ${round(coords[coords.length - 1].x)} ${pad.top + plotHeight} L ${round(coords[0].x)} ${pad.top + plotHeight} Z`;
    const labels = chartLabelIndexes(points.length);
    const total = values.reduce((sum, value) => sum + value, 0);
    const average = total / values.length;
    return `<div class="modern-chart" role="img" aria-label="Gráfico de evolução do período">
      <div class="chart-summary">
        <span>Total no período</span>
        <strong>${esc(formatCurrency(total))}</strong>
        <em>Média ${esc(formatCurrency(average))}</em>
      </div>
      <svg class="line-chart" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="${gradientId}" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="currentColor" stop-opacity="0.28" />
            <stop offset="100%" stop-color="currentColor" stop-opacity="0.02" />
          </linearGradient>
          <clipPath id="${clipId}">
            <rect x="${pad.left - 8}" y="${pad.top - 10}" width="${plotWidth + 16}" height="${plotHeight + 18}" rx="6"></rect>
          </clipPath>
        </defs>
        ${ticks.map((tick, index) => {
          const y = pad.top + plotHeight - ((tick - min) / range) * plotHeight;
          return `<g class="chart-grid"><line x1="${pad.left}" y1="${round(y)}" x2="${width - pad.right}" y2="${round(y)}"></line><text x="${yAxisLabelX}" y="${round(y + 4)}">${esc(yAxisLabels[index])}</text></g>`;
        }).join("")}
        <g clip-path="url(#${clipId})">
          <path class="chart-area" style="fill:url(#${gradientId})" d="${areaPath}"></path>
          <path class="chart-line" d="${linePath}"></path>
          ${coords.map((point, index) => `<circle class="chart-point ${labels.includes(index) ? "is-labeled" : ""}" cx="${round(point.x)}" cy="${round(point.y)}" r="${labels.includes(index) ? 3.8 : manyChartPoints(points.length) ? 1.8 : 2.5}"><title>${esc(point.label)}: ${esc(point.display || formatCurrency(point.value || 0))}</title></circle>`).join("")}
        </g>
        ${labels.map((index) => {
          const point = coords[index];
          if (!point) return "";
          return `<text class="chart-x-label" x="${round(point.x)}" y="${height - 18}">${esc(chartAxisLabel(point.label))}</text>`;
        }).join("")}
      </svg>
      <div class="chart-footnote">${esc(rows.length > points.length ? `Mostrando tendência resumida de ${rows.length} registros.` : "Mostrando todos os pontos do período.")}</div>
    </div>`;
  }

  function compactChartRows(rows) {
    const normalized = rows.map((row) => ({
      label: String(row.label || ""),
      value: Number(row.value) || 0,
      display: row.display || formatCurrency(Number(row.value) || 0)
    }));
    const maxPoints = 32;
    if (normalized.length <= maxPoints) return normalized;
    const bucketSize = Math.ceil(normalized.length / maxPoints);
    const buckets = [];
    for (let index = 0; index < normalized.length; index += bucketSize) {
      const chunk = normalized.slice(index, index + bucketSize);
      const value = chunk.reduce((sum, row) => sum + row.value, 0);
      const first = chunk[0];
      const last = chunk[chunk.length - 1];
      buckets.push({
        label: first.label === last.label ? first.label : `${first.label}-${last.label}`,
        value,
        display: formatCurrency(value)
      });
    }
    return buckets;
  }

  function chartTicks(min, max) {
    const top = Math.max(1, max);
    return [top, top * 0.66, top * 0.33, Math.min(0, min)];
  }

  function chartLabelIndexes(length) {
    if (length <= 1) return [0];
    const count = Math.min(length > 18 ? 5 : 6, length);
    const indexes = new Set();
    for (let index = 0; index < count; index += 1) {
      indexes.add(Math.round((index / (count - 1)) * (length - 1)));
    }
    return Array.from(indexes).sort((a, b) => a - b);
  }

  function chartAxisLabel(label) {
    const text = String(label || "").trim();
    if (!text) return "";
    if (text.includes("-")) return text.split("-")[0].trim();
    return text.length > 8 ? text.slice(0, 8) : text;
  }

  function chartYAxisPadding(labels, labelX) {
    const longest = labels.reduce((maxLength, label) => Math.max(maxLength, String(label || "").length), 0);
    return Math.min(150, Math.max(76, labelX + Math.ceil(longest * 6.6) + 16));
  }

  function manyChartPoints(length) {
    return Number(length) > 22;
  }

  function totalFromRows(rows) {
    return rows.reduce((sum, row) => sum + (Number(row.value) || 0), 0);
  }

  function compactMoney(value) {
    const number = Number(value) || 0;
    if (Math.abs(number) >= 1000000) return "R$ " + quantityLabel(number / 1000000) + " mi";
    if (Math.abs(number) >= 1000) return "R$ " + quantityLabel(number / 1000) + " mil";
    return formatCurrency(number).replace(",00", "");
  }

  function round(value) {
    return Math.round(value * 10) / 10;
  }

  function rankingHtml(rows) {
    const values = rows.map((row) => rankingValue(row));
    const max = Math.max(0, ...values);
    return rows.map((row, index) => {
      const value = rankingValue(row);
      const width = max <= 0 ? 0 : Math.max(6, Math.round((value / max) * 100));
      const subtitle = rankingSubtitle(row);
      return `<div class="rank-row">
        <span class="list-icon info">${esc(String(index + 1).padStart(2, "0"))}</span>
        <div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">${esc(subtitle)}</span><div class="progress-track"><span style="width: ${width}%"></span></div></div>
        <strong>${esc(rankingDisplay(row))}</strong>
      </div>`;
    }).join("") || emptyState("Ainda não há dados no período escolhido.");
  }

  function rankingValue(row) {
    if (row && row.quantity != null) return Number(row.quantity) || 0;
    return Number(row && row.value) || 0;
  }

  function rankingDisplay(row) {
    if (row && row.quantity != null) {
      return row.display || `${quantityLabel(row.quantity)} itens`;
    }
    return row && (row.display || row.value) || "0";
  }

  function rankingSubtitle(row) {
    if (!row) return "Período atual";
    if (row.quantity != null) {
      const parts = [];
      if (row.sales != null) parts.push(`${quantityLabel(row.sales)} vendas`);
      if (row.revenueDisplay) parts.push(row.revenueDisplay);
      if (!parts.length && row.code) parts.push(`Código ${row.code}`);
      return parts.join(" | ") || "Quantidade vendida";
    }
    if (row.sales) return `${row.sales} vendas`;
    if (row.code) return `Código ${row.code}`;
    return "Período atual";
  }

  function compactSalesList(rows) {
    return rows.slice(0, 4).map((row) => `<div class="list-item"><span class="list-icon success">$</span><div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">${esc(row.sales ? row.sales + " vendas" : "Loja atualizada")}</span></div><strong>${esc(row.display || row.value || "0")}</strong></div>`).join("") || emptyState("Nenhuma venda encontrada para o período selecionado.");
  }

  function statusRowsHtml(rows) {
    return rows.map((row) => `<div class="list-item"><span class="list-icon ${tone(row.tone)}">${esc(row.display)}</span><div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">Status atual dos produtos</span></div><strong>${esc(row.display)}</strong></div>`).join("") || emptyState("Ainda não há produtos para acompanhar.");
  }

  function storesListHtml(rows) {
    return rows.filter((row) => !row.isAll).map((row) => `<div class="list-item"><span class="list-icon info">LJ</span><div><span class="list-title">${esc(row.name)}</span><span class="list-subtitle">Loja vinculada ao painel</span></div><strong>${esc(row.status || "Ativa")}</strong></div>`).join("") || emptyState("Nenhuma loja vinculada ao painel.");
  }

  function storeTabsHtml() {
    const stores = storesCache || [];
    if (stores.length <= 1) return "";
    return `<div class="store-tabs" role="tablist" aria-label="Lojas">${stores.map((store) => `
      <button class="${store.id === currentStore ? "active" : ""}" type="button" data-store-id="${esc(store.id)}" role="tab" aria-selected="${store.id === currentStore ? "true" : "false"}">
        <span>${esc(store.name)}</span>
        <small>${esc(store.isAll ? "Todas as lojas" : "Loja vinculada")}</small>
      </button>
    `).join("")}</div>`;
  }

  function wireStoreTabs() {
    viewHost.querySelectorAll(".store-tabs [data-store-id]").forEach((button) => {
      button.addEventListener("click", () => changeStore(button.dataset.storeId || "all"));
    });
  }

  function tag(value) {
    const text = normalizeText(String(value || "-"));
    return `<span class="tag ${tagTone(text)}">${esc(text)}</span>`;
  }

  function tagTone(value) {
    const text = normalizeText(value).toLowerCase();
    if (text.includes("cancel") || text.includes("baixo") || text.includes("sem estoque") || text.includes("falha") || text.includes("failed") || text.includes("crítico") || text.includes("diverg")) return "danger";
    if (text.includes("aberto") || text.includes("pendente") || text.includes("atenção")) return "warning";
    if (text.includes("info") || text.includes("fechado")) return "info";
    return "success";
  }

  function tone(value) {
    return value || "info";
  }

  function emptyState(message) {
    return `<div class="empty-state"><strong>${esc(message || "Ainda não há informações para mostrar.")}</strong><span>Os dados aparecerão aqui assim que houver registros para o filtro atual.</span></div>`;
  }

  function friendlyMessage(message) {
    const text = normalizeText(String(message || ""));
    if (!text) return "Verifique se o servidor do Zentrix está ligado.";
    if (text.toLowerCase().includes("failed to fetch") || text.toLowerCase().includes("network")) {
      return "Não conseguimos conectar ao Zentrix agora. Verifique a internet ou se o servidor está ligado.";
    }
    if (text.includes("401") || text.toLowerCase().includes("unauthorized")) {
      return "Sua sessão expirou. Entre novamente.";
    }
    if (text.includes("403") || text.toLowerCase().includes("forbidden") || text.toLowerCase().includes("permiss")) {
      return "Voce nao tem permissao para realizar esta acao.";
    }
    if (text.includes("404")) {
      return "Esta informação ainda não está disponível no painel.";
    }
    if (text.includes("500") || text.includes("503")) {
      return "O Zentrix está iniciando ou encontrou uma instabilidade. Tente atualizar novamente.";
    }
    return text.replace(/\bAPI\b/g, "Zentrix").replace(/\bendpoint\b/gi, "recurso");
  }

  function initChromeSkeleton() {
    setText(".status-pill", "Conectando");
    const statusPill = document.querySelector(".status-pill");
    if (statusPill) statusPill.className = "status-pill warning";
    setText(".sidebar-status strong", "Atualização");
    setText(".sidebar-status span", "Carregando estado real");
    const progress = document.querySelector(".sidebar-status .progress-track span");
    if (progress) progress.style.width = "0%";
    setText(".sidebar-status strong", "Preparando painel");
    setText(".sidebar-status span", "Buscando dados da loja");
    setText(".sidebar-status .button", "Ver histórico");
    setText(".sidebar-status .button", "Histórico");
    setText(".window-title span:last-child", "Zentrix AppGestão");
    setText(".sidebar-status .button", "Ver histórico");
    populateStoreSelect([{ id: "all", name: "Geral", label: "Todas as lojas", isAll: true }]);
  }

  async function ensureStores() {
    if (storesCache) return storesCache;
    const stores = await window.zentrixApi("/stores");
    setStores(stores);
    return storesCache;
  }

  function readStoresCache() {
    try {
      const stores = JSON.parse(localStorage.getItem("zentrix-stores-cache") || "null");
      return Array.isArray(stores) && stores.length ? stores : null;
    } catch (error) {
      return null;
    }
  }

  function setStores(stores) {
    if (!Array.isArray(stores) || stores.length === 0) return;
    storesCache = stores;
    localStorage.setItem("zentrix-stores-cache", JSON.stringify(storesCache));
    if (!storesCache.some((store) => store.id === currentStore)) {
      currentStore = "all";
      localStorage.setItem("zentrix-store", currentStore);
    }
    populateStoreSelect(storesCache);
  }

  function populateStoreSelect(stores) {
    const activeStore = document.querySelector(".topbar-tools .select-field");
    if (!activeStore) return;
    activeStore.setAttribute("aria-label", "Empresa ativa");
    activeStore.innerHTML = stores.map((store) => `<option value="${esc(store.id)}">${esc(store.name)}</option>`).join("");
    activeStore.value = currentStore;
    if (!activeStore.dataset.storeReady) {
      activeStore.dataset.storeReady = "true";
      activeStore.addEventListener("change", () => changeStore(activeStore.value || "all"));
    }
  }

  function changeStore(nextStore) {
    const next = nextStore || "all";
    if (currentStore === next) return;
    currentStore = next;
    localStorage.setItem("zentrix-store", currentStore);
    loadPageData();
  }

  async function refreshChrome() {
    try {
      const data = await window.zentrixApi("/dashboard" + periodQuery());
      updateChrome(data);
      refreshTopbarAlerts().catch(() => null);
    } catch (error) {
      try {
        const health = await window.zentrixApi("/health", { cache: "refresh" });
        updateChrome({
          activeStore: { name: "Geral", label: "Todas as lojas" },
          lastSync: health && health.lastSync,
          syncProgress: health && health.lastSync ? 100 : 0,
          stores: storesCache || [{ id: "all", name: "Geral", label: "Todas as lojas", isAll: true }]
        });
      } catch (healthError) {
        updateChrome(null);
      }
    }
  }

  function updateChrome(data) {
    if (data && Array.isArray(data.stores)) {
      setStores(data.stores);
    }
    const active = data && data.activeStore ? data.activeStore : { name: "Geral", label: "Todas as lojas" };
    const companyName = active && active.name ? String(active.name) : "Geral";
    const lastSync = data && data.lastSync ? String(data.lastSync) : "";
    const apiOnline = Boolean(data);
    const storeStatus = data && (data.storeStatus || (data.activeStore && data.activeStore.status)) || "Ativa";
    const planStatus = data && (data.planStatus || data.subscriptionStatus || data.billingStatus) || "Em dia";
    const progress = apiOnline ? 100 : 0;
    const title = "Zentrix AppGestão - " + companyName;

    setText(".window-title span:last-child", title);
    const statusPill = document.querySelector(".sidebar-status .status-pill");
    if (statusPill) {
      statusPill.className = "status-pill " + (apiOnline ? "success" : "warning");
      statusPill.textContent = apiOnline ? "Online" : "Atualizando";
    }
    updateNotifications(apiOnline, apiOnline, lastSync);

    setText(".sidebar-status strong", "Loja " + storeStatus);
    setText(".sidebar-status span", "Plano " + planStatus);
    setText(".sidebar-status .button", "Histórico");
    const progressBar = document.querySelector(".sidebar-status .progress-track span");
    if (progressBar) progressBar.style.width = progress + "%";
    setText(".sidebar-status strong", "Loja " + storeStatus);
    setText(".sidebar-status span", "Plano " + planStatus);
    setText(".sidebar-status .button", "Ver histórico");
  }

  async function refreshTopbarAlerts() {
    const alerts = await window.zentrixApi("/alerts?store=" + encodeURIComponent(currentStore || "all"), {
      cache: "refresh",
      timeoutMs: 6000
    });
    topbarAlerts = Array.isArray(alerts) ? alerts : [];
    const lastSync = document.querySelector(".sidebar-status span") ? document.querySelector(".sidebar-status span").textContent.replace("?ltima atualização: ", "") : "";
    updateNotifications(true, Boolean(lastSync && !lastSync.includes("Aguardando")), lastSync, topbarAlerts);
  }

  function enhanceChrome() {
    document.title = normalizeText(document.title).replace("Zentrix Web", "Zentrix AppGestão");
    setText(".window-title span:last-child", "Zentrix AppGestão");
    setText(".sidebar-brand span", "Gestão comercial Zentrix");
    if (menuButton) menuButton.setAttribute("aria-label", "Abrir menu");
    if (closeSidebarButton) {
      closeSidebarButton.textContent = "x";
      closeSidebarButton.setAttribute("aria-label", "Fechar menu");
    }
    renderAccountToolbar();
    rebuildNavigationWithAssets();
  }

  function renderAccountToolbar() {
    const toolbar = document.querySelector(".window-toolbar");
    if (!toolbar || toolbar.dataset.accountReady === "true") {
      return;
    }
    const user = currentUserName();
    const role = currentUserRoleLabel();
    toolbar.dataset.accountReady = "true";
    toolbar.innerHTML = `
      <div class="notification-wrap">
        <button class="icon-button notification-button" type="button" aria-label="Notificações" aria-expanded="false" data-action="toggle-notifications">
          <img class="topbar-icon-img notification-img" src="../assets/Icons/topbar-bell.png" alt="" data-icon-fallback />
          <span class="notification-icon" aria-hidden="true"></span>
          <span class="notification-count" hidden>0</span>
        </button>
        <div class="toolbar-popover notification-menu" hidden>
          <strong>Tudo certo</strong>
          <span>Sistema acompanhando a loja.</span>
        </div>
      </div>
      <div class="user-menu-wrap">
        <button class="user-card" type="button" aria-label="Menu do usuário" aria-expanded="false" data-action="toggle-user-menu">
          <span class="user-info">
            <span class="user-name">${esc(user)}</span>
            <span class="user-role">${esc(role)}</span>
          </span>
          <span class="user-avatar">
            <img class="topbar-icon-img user-avatar-img" src="../assets/Icons/topbar-user.png" alt="" data-icon-fallback />
            <span class="user-avatar-fallback">${esc(initials(user))}</span>
          </span>
        </button>
        <div class="toolbar-popover user-menu" hidden>
          <strong>${esc(user)}</strong>
          <span>${esc(role)}</span>
          <button type="button" data-action="logout">Sair da conta</button>
        </div>
      </div>
    `;
    wireTopbarIconFallbacks(toolbar);
  }

  function updateNotifications(apiOnline, storeUpdated, lastSync, alerts) {
    const button = document.querySelector(".notification-button");
    const menu = document.querySelector(".notification-menu");
    if (!button || !menu) return;
    const items = Array.isArray(alerts) ? alerts : topbarAlerts;
    const importantAlerts = items.filter((item) => {
      const level = String(item.level || "").toLowerCase();
      return level === "danger" || level === "warning";
    });
    const visibleAlerts = items.slice(0, 5);
    const hasAttention = !apiOnline || !storeUpdated || importantAlerts.length > 0;
    const badge = button.querySelector(".notification-count");
    button.classList.toggle("has-alert", hasAttention);
    button.setAttribute("aria-label", hasAttention ? "Notificações com atenção" : "Notificações");
    if (badge) {
      const count = importantAlerts.length || (!apiOnline || !storeUpdated ? 1 : 0);
      badge.hidden = count <= 0;
      badge.textContent = String(Math.min(count, 9));
    }
    if (visibleAlerts.length) {
      menu.innerHTML = `
        <strong>Notificações</strong>
        <div class="notification-list">
          ${visibleAlerts.map(notificationItemHtml).join("")}
        </div>
      `;
      return;
    }
    menu.innerHTML = hasAttention
      ? `<strong>Atenção</strong><span>${esc(apiOnline ? "Aguardando atualização da loja." : "Conexão instável no momento.")}</span>`
      : `<strong>Tudo certo</strong><span>${esc(lastSync ? "Última atualização: " + lastSync : "Sistema acompanhando a loja.")}</span>`;
  }

  async function refreshAccountToolbar() {
    if (!window.zentrixApi) return;
    const account = await window.zentrixApi("/auth/me", {
      cache: "no-store",
      timeoutMs: 5000
    });
    if (!account || typeof account !== "object") return;
    const stored = readStoredSession() || {};
    const nextSession = { ...stored, ...account };
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(nextSession));
    } catch (error) {
      // Mantem a barra funcionando mesmo quando o navegador limita storage.
    }
    updateAccountToolbar(nextSession);
  }

  function updateAccountToolbar(account) {
    const user = accountDisplayName(account);
    const role = accountRoleLabel(account);
    document.querySelectorAll(".user-name").forEach((item) => { item.textContent = user; });
    document.querySelectorAll(".user-role").forEach((item) => { item.textContent = role; });
    const menu = document.querySelector(".user-menu");
    if (menu) {
      const title = menu.querySelector("strong");
      const subtitle = menu.querySelector("span");
      if (title) title.textContent = user;
      if (subtitle) subtitle.textContent = role;
    }
    document.querySelectorAll(".user-avatar-fallback").forEach((item) => { item.textContent = initials(user); });
    updateDashboardGreeting(account);
  }

  function currentUserName() {
    return accountDisplayName(readStoredSession());
  }

  function currentUserFirstName() {
    return firstNameFromAccount(readStoredSession());
  }

  function updateDashboardGreeting(account) {
    const title = document.querySelector("[data-account-greeting]");
    if (title) {
      title.textContent = greeting() + ", " + firstNameFromAccount(account) + ".";
    }
  }

  function firstNameFromAccount(account) {
    const name = accountDisplayName(account);
    return normalizeText(String(name).trim().split(/\s+/).filter(Boolean)[0] || "Tudo bem");
  }

  function currentUserRoleLabel() {
    return accountRoleLabel(readStoredSession());
  }

  function accountDisplayName(account) {
    const candidates = [
      account && account.displayName,
      account && account.name,
      account && account.fullName,
      account && account.username,
      account && account.login,
      account && account.email
    ];
    const selected = candidates.find((value) => {
      const text = String(value || "").trim();
      const key = normalizeKey(text).toLowerCase();
      return text && key !== "usuario" && key !== "user";
    });
    return normalizeText(String(selected || "Conta"));
  }

  function accountRoleLabel(account) {
    const role = account && (account.role || account.cargo || account.profile);
    const key = normalizeKey(role || "").toUpperCase();
    const labels = {
      SUPER_ADMIN: "Master",
      SUPERADMIN: "Master",
      MASTER_ADMIN: "Master",
      MASTERADMIN: "Master",
      ADMIN: "Administrador",
      ADMINISTRADOR: "Administrador",
      ADMINISTRATOR: "Administrador",
      DONO: "Administrador",
      OWNER: "Administrador",
      GERENTE: "Gerente",
      MANAGER: "Gerente",
      CAIXA: "Caixa",
      OPERADOR: "Operador",
      OPERATOR: "Operador",
      ESTOQUISTA: "Estoquista",
      FINANCEIRO: "Financeiro",
      CONSULTA: "Consulta"
    };
    return labels[key] || normalizeText(String(role || "Equipe"));
  }

  function notificationItemHtml(item) {
    const title = item.title || "Notificação";
    const message = item.message || "Veja os detalhes no painel.";
    const level = String(item.level || "info").toLowerCase();
    const actionLabel = item.actionLabel || "Abrir";
    const actionUrl = normalizeNotificationUrl(item.actionUrl);
    return `
      <a class="notification-item ${esc(level)}" href="${escAttr(actionUrl)}">
        <span class="notification-dot ${esc(level)}"></span>
        <span class="notification-copy">
          <strong>${esc(title)}</strong>
          <span>${esc(message)}</span>
          <em>${esc(actionLabel)}</em>
        </span>
      </a>
    `;
  }

  function normalizeNotificationUrl(url) {
    const value = String(url || "/dashboard.html").trim();
    const file = value.split("/").pop() || "dashboard.html";
    if (!file.includes(".html")) {
      return "dashboard.html";
    }
    const page = file.replace(/\.html$/i, "");
    if (canAccessPage(page)) {
      return file;
    }
    const fallbackPage = firstPermittedPage();
    return fallbackPage ? `${fallbackPage}.html` : "dashboard.html";
  }

  function wireTopbarIconFallbacks(container) {
    container.querySelectorAll("img[data-icon-fallback]").forEach((image) => {
      image.addEventListener("error", () => {
        image.hidden = true;
        const parent = image.parentElement;
        if (parent) parent.classList.add("is-fallback");
      }, { once: true });
    });
  }

  function rebuildNavigationWithAssets() {
    const nav = document.querySelector(".nav-list");
    if (!nav) return;
    const currentPage = location.pathname.split("/").pop();
    const groups = [
      ["Operação", [
        ["dashboard.html", "Dashboard", "dashboard.png"],
        ["vendas.html", "Vendas", "vendas.png"],
        ["caixa.html", "Caixa", "caixa.png"]
      ]],
      ["Gestão", [
        ["financeiro.html", "Financeiro", "financeiro.png"],
        ["produtos.html", "Produtos", "produtos.png"],
        ["estoque.html", "Estoque", "estoque.png"],
        ["clientes.html", "Clientes", "clientes.png"],
        ["funcionarios.html", "Funcionários", "funcionarios.png"]
      ]],
      ["Segurança e Sistema", [
        ["auditoria.html", "Auditoria", "auditoria.png"],
        ["relatorios.html", "Relatórios", "relatorios.png"],
        ["backups.html", "Backups", "backups.png"],
        ["configuracoes.html", "Configurações", "configuracoes.png"]
      ]]
    ];
    nav.innerHTML = groups.map(([group, links]) => {
      const visibleLinks = visibleNavigationLinks(links);
      if (!visibleLinks.length) return "";
      return `
        <div class="nav-section">${esc(group)}</div>
        ${visibleLinks.map(([href, label, icon]) => `<a class="nav-item ${href === currentPage ? "active" : ""}" href="${href}"><span class="nav-icon"><img src="../assets/Icons/${escAttr(icon)}" data-fallback="../assets/Icons/${escAttr(iconFallbackFile(icon))}" alt="" loading="eager" decoding="async" /></span><span>${esc(label)}</span></a>`).join("")}
      `;
    }).join("");
    wireNavigationIconFallbacks(nav);
  }

  function iconFallbackFile(name) {
    return String(name || "").replace(/\.png$/i, "-removebg-preview.png");
  }

  function wireNavigationIconFallbacks(nav) {
    nav.querySelectorAll("img[data-fallback]").forEach((image) => {
      image.addEventListener("error", () => {
        const fallback = image.dataset.fallback;
        if (fallback && image.src !== fallback) {
          image.src = fallback;
        }
      }, { once: true });
    });
  }

  function rebuildNavigation() {
    const nav = document.querySelector(".nav-list");
    if (!nav) return;
    const currentPage = location.pathname.split("/").pop();
    const groups = [
      ["Operação", [
        ["dashboard.html", "Dashboard", "?"],
        ["vendas.html", "Vendas", "$"],
        ["caixa.html", "Caixa", "?"]
      ]],
      ["Gestão", [
        ["financeiro.html", "Financeiro", "R$"],
        ["produtos.html", "Produtos", "?"],
        ["estoque.html", "Estoque", "?"],
        ["clientes.html", "Clientes", "?"],
        ["funcionarios.html", "Funcionários", "OP"]
      ]],
      ["Segurança e Sistema", [
        ["auditoria.html", "Auditoria", "!"],
        ["relatorios.html", "Relatórios", "?"],
        ["backups.html", "Backups", "CL"],
        ["configuracoes.html", "Configurações", "?"]
      ]]
    ];
    nav.innerHTML = groups.map(([group, links]) => {
      const visibleLinks = visibleNavigationLinks(links);
      if (!visibleLinks.length) return "";
      return `
        <div class="nav-section">${esc(group)}</div>
        ${visibleLinks.map(([href, label, icon]) => `<a class="nav-item ${href === currentPage ? "active" : ""}" data-icon="${esc(icon)}" href="${href}"><span>${esc(label)}</span></a>`).join("")}
      `;
    }).join("");
  }

  function setText(selector, value) {
    const element = document.querySelector(selector);
    if (element) element.textContent = value;
  }

  function setupPeriodControl() {
    const control = document.querySelector(".segmented-control");
    if (!control) return;
    const periodByText = {
      "hoje": "today",
      "7 dias": "7d",
      "30 dias": "month",
      "mes": "month",
      "mês": "month",
      "1 ano": "year",
      "ano": "year"
    };
    control.querySelectorAll("button").forEach((button) => {
      const key = normalizeText(button.textContent.trim().toLowerCase());
      const period = normalizePeriodValue(button.dataset.period || periodByText[key]);
      button.dataset.period = period;
      button.textContent = periodLabel(period);
      button.classList.toggle("active", period === currentPeriod);
      button.addEventListener("click", () => {
        if (currentPeriod === period) return;
        currentPeriod = period;
        localStorage.setItem("zentrix-period", currentPeriod);
        control.querySelectorAll("button").forEach((item) => item.classList.toggle("active", item === button));
        cancelPrefetch();
        loadPageData({ userInitiated: true });
      });
    });
  }

  function setPeriodControlBusy(isBusy) {
    const control = document.querySelector(".segmented-control");
    if (!control) return;
    control.setAttribute("aria-busy", isBusy ? "true" : "false");
    control.querySelectorAll("button").forEach((button) => {
      button.classList.toggle("is-loading", Boolean(isBusy));
    });
  }

  function periodQuery() {
    return queryString(normalizePeriodValue(currentPeriod), currentStore);
  }

  function storeQuery(extraParams) {
    return queryString(null, currentStore, extraParams);
  }

  function queryString(period, store, extraParams) {
    const params = new URLSearchParams();
    if (period) {
      params.set("period", normalizePeriodValue(period));
    }
    params.set("store", store || currentStore || "all");
    Object.entries(extraParams || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        params.set(key, value);
      }
    });
    const query = params.toString();
    return query ? "?" + query : "";
  }

  function appendQuery(endpoint, suffix) {
    if (!suffix) {
      return endpoint;
    }
    return endpoint + (endpoint.includes("?") ? "&" + suffix.slice(1) : suffix);
  }

  function currentPageName() {
    return location.pathname.split("/").pop().replace(".html", "");
  }

  function periodLabel(value) {
    const period = normalizePeriodValue(value || currentPeriod);
    return {
      today: "Hoje",
      "7d": "7 dias",
      month: "30 dias",
      year: "1 ano"
    }[period] || "Hoje";
  }

  function normalizePeriodValue(value) {
    const normalized = String(value || "today")
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLowerCase()
      .replace(/\s+/g, "");
    if (["7d", "7", "7dias", "week", "semana"].includes(normalized)) return "7d";
    if (["month", "mes", "30d", "30dias"].includes(normalized)) return "month";
    if (["year", "ano", "1ano", "365d"].includes(normalized)) return "year";
    return "today";
  }

  function activeStoreLabel(data) {
    if (!data || !data.activeStore) return "Todas as lojas";
    return data.activeStore.label || data.activeStore.name || "Todas as lojas";
  }

  function activeStoreName() {
    const stores = storesCache || [];
    const active = stores.find((store) => store.id === currentStore);
    return active ? active.name : "Todas as lojas";
  }

  function percentWidth(value) {
    const percent = Number(value);
    if (!Number.isFinite(percent)) return 0;
    return Math.max(0, Math.min(100, percent));
  }

  function setupCsvExport(buttonId, title, headers, rows) {
    pageCsvTools().setupCsvExport(buttonId, title, headers, rows);
  }

  function restoreCsvExports(page) {
    pageCsvTools().restoreCsvExports(page);
  }

  function downloadCsvPayload(title, headers, rows) {
    pageCsvTools().downloadCsvPayload(title, headers, rows);
  }

  function pageCsvTools() {
    if (!csvToolsCache) {
      csvToolsCache = window.ZentrixPageCsv.create({
        currentPageName,
        normalizeText,
        readViewState,
        saveViewState,
        viewHost
      });
    }
    return csvToolsCache;
  }

  function isTrustedTag(value) {
    return typeof value === "string" && /^<span class="tag (success|warning|danger|info)">[^<>]*<\/span>$/.test(value);
  }

  function stockLevel(row) {
    const current = Number(row.currentStock || 0);
    const minimum = Number(row.minimumStock || 0);
    const ratio = minimum <= 0 ? (current > 0 ? 100 : 0) : Math.round((current / minimum) * 100);
    const width = Math.max(4, Math.min(100, ratio));
    const toneValue = current <= 0 ? "danger" : current <= minimum ? "warning" : "success";
    const label = current <= 0 ? "Crítico" : current <= minimum ? "Baixo" : "Saudável";
    return { width, tone: toneValue, label };
  }

  function roleTone(role) {
    const text = normalizeText(role).toLowerCase();
    if (text.includes("admin")) return "danger";
    if (text.includes("gerente") || text.includes("manager")) return "warning";
    return "info";
  }

  function financialEntryTypeTone(type) {
    return String(type || "").toUpperCase() === "DESPESA" ? "warning" : "success";
  }

  function auditTone(row) {
    const text = normalizeText(`${row.action || ""} ${row.description || ""} ${row.value || ""}`).toLowerCase();
    if (text.includes("exclus") || text.includes("cancel") || text.includes("falha") || text.includes("delete")) return "danger";
    if (text.includes("alter") || text.includes("manual") || text.includes("sync")) return "warning";
    return "info";
  }

  function formValue(form, name) {
    const field = form.elements[name];
    return field && typeof field.value === "string" ? field.value.trim() : "";
  }

  function setFormValue(form, name, value) {
    const field = form.elements[name];
    if (field) {
      field.value = value == null ? "" : String(value);
    }
  }

  function resetAdminForm(type, form) {
    form.reset();
    setFormValue(form, "mode", "create");
    setFormValue(form, "storeId", writeStore());
    setFormValue(form, "active", "true");
    if (type === "product") {
      setFormValue(form, "originalCode", "");
      setFormValue(form, "unit", "UN");
      setAdminFormTitle(form.closest("[data-admin-form]"), "Novo produto", "Cadastro direto no AppGestão para uso na loja");
      setFormSubmitText(form, "Salvar produto");
      setFieldReadOnly(form, "code", false);
      setFieldReadOnly(form, "stock", false);
    }
    if (type === "client") {
      setFormValue(form, "id", "");
      setAdminFormTitle(form.closest("[data-admin-form]"), "Novo cliente", "Cadastro direto no AppGestão");
      setFormSubmitText(form, "Salvar cliente");
    }
    if (type === "stock") {
      setAdminFormTitle(form.closest("[data-admin-form]"), "Movimentar estoque", "Registre entrada, saída ou ajuste manual com motivo");
      setFormSubmitText(form, "Registrar movimento");
    }
    if (type === "employee") {
      setFormValue(form, "originalUsername", "");
      setFormValue(form, "role", "CONSULTA");
      setAdminFormTitle(form.closest("[data-admin-form]"), "Novo funcionário", "Cadastro administrativo com senha e permissões");
      setFormSubmitText(form, "Salvar funcionário");
      setFieldReadOnly(form, "username", false);
      setEmployeePasswordRequired(form, true);
      applyRolePermissions(form, "CONSULTA");
    }
    if (type === "finance") {
      setFormValue(form, "id", "");
      setFormValue(form, "type", "RECEITA");
      setFormValue(form, "status", "PAGO");
      setFormValue(form, "entryDate", todayDateValue());
      setAdminFormTitle(form.closest("[data-admin-form]"), "Novo lançamento", "Receita ou despesa manual com auditoria");
      setFormSubmitText(form, "Salvar lançamento");
    }
  }

  function setAdminFormTitle(panel, title, subtitle) {
    if (!panel) return;
    const heading = panel.querySelector(".panel-title h3");
    const detail = panel.querySelector(".panel-title span");
    if (heading) heading.textContent = title;
    if (detail) detail.textContent = subtitle;
  }

  function setFormSubmitText(form, text) {
    const button = form.querySelector('button[type="submit"]');
    if (button) button.textContent = text;
  }

  function setFieldReadOnly(form, name, readOnly) {
    const field = form.elements[name];
    if (field) {
      field.readOnly = Boolean(readOnly);
    }
  }

  function setEmployeePasswordRequired(form, required) {
    const field = form.elements.password;
    if (!field) return;
    field.required = Boolean(required);
    field.placeholder = required ? "" : "Preencha apenas para redefinir";
  }

  function selectedEmployeePermissions(form) {
    return Array.from(form.querySelectorAll('input[name="permissions"]:checked')).map((field) => field.value);
  }

  function setEmployeePermissions(form, permissions) {
    const selected = new Set((permissions || []).map((item) => String(item).trim().toLowerCase()).filter(Boolean));
    form.querySelectorAll('input[name="permissions"]').forEach((field) => {
      field.checked = selected.has(String(field.value).toLowerCase());
    });
  }

  function applyRolePermissions(form, role) {
    setEmployeePermissions(form, EMPLOYEE_ROLE_PRESETS[roleKey(role)] || EMPLOYEE_ROLE_PRESETS.CONSULTA);
  }

  function employeePermissions(row) {
    if (!row) return [];
    if (Array.isArray(row.permissions)) {
      const permissions = row.permissions.map((item) => String(item).trim().toLowerCase()).filter(Boolean);
      if (permissions.length) {
        return permissions;
      }
    }
    if (row.permissionsJson) {
      const text = String(row.permissionsJson).trim();
      if (text) {
        try {
          const parsed = JSON.parse(text);
          if (Array.isArray(parsed)) {
            return parsed.map((item) => String(item).trim().toLowerCase()).filter(Boolean);
          }
        } catch (error) {
          return text.replace("[", "").replace("]", "").split(",").map((item) => item.trim().replace(/"/g, "").toLowerCase()).filter(Boolean);
        }
      }
    }
    return EMPLOYEE_ROLE_PRESETS[roleKey(row.role)] || [];
  }

  function roleKey(role) {
    const normalized = normalizeKey(role).toUpperCase();
    if (normalized.includes("ADMIN") || normalized.includes("DONO") || normalized.includes("OWNER")) return "ADMIN";
    if (normalized.includes("GERENTE") || normalized.includes("MANAGER")) return "GERENTE";
    if (normalized.includes("CAIXA") || normalized.includes("OPERADOR")) return "CAIXA";
    if (normalized.includes("ESTOQUISTA") || normalized.includes("STOCK")) return "ESTOQUISTA";
    if (normalized.includes("FINANCEIRO") || normalized.includes("FINANCE")) return "FINANCEIRO";
    return "CONSULTA";
  }

  function rowIsActive(row) {
    if (!row) return true;
    if (typeof row.active === "boolean") return row.active;
    const value = String(row.active || row.status || "").toLowerCase();
    return !value.includes("inativo") && !value.includes("inactive") && value !== "false";
  }

  function stockMovementEndpoint(type) {
    const value = String(type || "").toUpperCase();
    if (value === "ENTRADA") return "/stock/entry";
    if (value === "AJUSTE") return "/stock/adjust";
    return "/stock/manual-output";
  }

  function writeStore() {
    if (currentStore && currentStore !== "all") {
      return currentStore;
    }
    const firstStore = (storesCache || []).find((store) => store && store.id && store.id !== "all");
    return firstStore ? firstStore.id : "WEB";
  }

})();
