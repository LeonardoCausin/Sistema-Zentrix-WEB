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
  let currentPeriod = localStorage.getItem("zentrix-period") || "today";
  let currentStore = localStorage.getItem("zentrix-store") || "all";
  let storesCache = readStoresCache();
  let refreshTimer = null;
  let loadingData = false;
  let pendingDataReload = false;
  let prefetchTimer = null;
  let forceFreshData = false;
  const API_CACHE_MAX_AGE = 15 * 60 * 1000;
  const VIEW_CACHE_MAX_AGE = 10 * 60 * 1000;
  const VIEW_CACHE_PREFIX = "zentrix-view-cache:";
  const VIEW_STATE_PREFIX = "zentrix-view-state:";
  const CLIENT_CACHE_VERSION = "20260629-cash-close";
  const pendingApiRefresh = new Set();
  const pendingApiRequests = new Map();
  const PREFETCH_PERIODS = ["today", "7d", "month", "year"];

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

  if (storedTheme === "dark" || storedTheme === "light") {
    root.dataset.theme = storedTheme;
  }

  enhanceChrome();

  if (themeButton) {
    themeButton.addEventListener("click", () => {
      const nextTheme = root.dataset.theme === "dark" ? "light" : "dark";
      root.dataset.theme = nextTheme;
      localStorage.setItem("zentrix-theme", nextTheme);
    });
  }

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
    const apiBase = localStorage.getItem("zentrix-api-base") || "http://localhost:8080/api";
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

  const viewHost = document.querySelector(".view-host");
  if (body.classList.contains("is-authenticated") && viewHost) {
    initChromeSkeleton();
    setupPeriodControl();
    loadPageData();
    setupAutoRefresh();
  }

  async function fetchApiJson(apiBase, path, options, storedSession) {
    const fetchOptions = { ...(options || {}) };
    delete fetchOptions.cache;
    delete fetchOptions.prefetch;
    const response = await fetch(apiBase + path, {
      ...fetchOptions,
      headers: {
        "Content-Type": "application/json",
        Authorization: storedSession ? "Bearer " + storedSession.token : "",
        ...((options && options.headers) || {})
      }
    });
    if (response.status === 401) {
      clearApiCache();
      clearStoredSession();
      window.location.replace(location.pathname.includes("/FrontEnd/pages/") ? "../../index.html" : "../index.html");
      throw new Error("Sessao expirada");
    }
    if (!response.ok) {
      throw new Error("Nao foi possivel carregar os dados.");
    }
    return response.json();
  }

  function apiCacheKey(apiBase, path, token) {
    return "zentrix-api-cache:" + encodeURIComponent([token || "public", apiBase, path].join("|"));
  }

  function currentApiCacheKey(path) {
    const storedSession = readStoredSession();
    const apiBase = localStorage.getItem("zentrix-api-base") || "http://localhost:8080/api";
    return apiCacheKey(apiBase, path, storedSession && storedSession.token);
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
      return sessionStorage.getItem(SESSION_KEY) || localStorage.getItem(SESSION_KEY);
    } catch (error) {
      return localStorage.getItem(SESSION_KEY);
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

  function prefetchApi(path) {
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
    const productButton = viewHost.querySelector('[data-action="new-product"]');
    if (productButton && productButton.dataset.ready !== "true") {
      productButton.dataset.ready = "true";
      productButton.addEventListener("click", () => {
        renderToast("Cadastre o produto no Zentrix PDV para manter preço e estoque iguais. Depois ele aparece aqui automaticamente.", "info");
      });
    }

    const backupButton = viewHost.querySelector('[data-action="download-backup"]');
    if (backupButton && backupButton.dataset.ready !== "true") {
      backupButton.dataset.ready = "true";
      backupButton.addEventListener("click", () => {
        const payload = readViewState(page || currentPageName(), "csv:export-backups");
        if (payload) {
          downloadCsvPayload(payload.title, payload.headers || [], payload.rows || []);
          renderToast("Backup baixado com sucesso. Guarde este arquivo em um local seguro.", "success");
          return;
        }
        const exportButton = document.getElementById("export-backups");
        if (exportButton && !exportButton.disabled) {
          exportButton.click();
          return;
        }
        renderToast("Ainda não há backup recebido do PDV para baixar.", "warning");
      });
    }
  }

  function schedulePrefetch(page) {
    if (prefetchTimer) {
      window.clearTimeout(prefetchTimer);
    }
    prefetchTimer = window.setTimeout(() => {
      const paths = prefetchPaths(page || currentPageName());
      paths.forEach((path, index) => {
        window.setTimeout(() => prefetchApi(path), index * 80);
      });
    }, 250);
  }

  function prefetchPaths(page) {
    const paths = new Set();
    PREFETCH_PERIODS.forEach((period) => {
      pageApiPaths(page, period, currentStore).forEach((path) => paths.add(path));
    });
    ["dashboard", "vendas", "financeiro", "caixa", "produtos", "estoque", "clientes", "relatorios"].forEach((name) => {
      pageApiPaths(name, currentPeriod, currentStore).forEach((path) => paths.add(path));
    });
    paths.add("/stores");
    return Array.from(paths).slice(0, 18);
  }

  function pageApiPaths(page, period, store) {
    const periodSuffix = queryString(period, store);
    const storeSuffix = queryString(null, store);
    return {
      dashboard: ["/dashboard" + periodSuffix],
      vendas: ["/sales" + periodSuffix],
      financeiro: ["/finance" + periodSuffix],
      caixa: ["/cash-sessions" + periodSuffix],
      auditoria: ["/audit" + periodSuffix],
      relatorios: ["/reports" + periodSuffix],
      produtos: ["/products" + storeSuffix],
      estoque: ["/stock/alerts" + storeSuffix],
      clientes: ["/clients" + storeSuffix],
      funcionarios: ["/employees" + storeSuffix],
      backups: ["/backups" + storeSuffix],
      configuracoes: ["/settings" + storeSuffix]
    }[page] || [];
  }

  async function loadPageData(options) {
    const silent = options && options.silent;
    if (loadingData) {
      pendingDataReload = true;
      return;
    }
    loadingData = true;
    const previousForceFresh = forceFreshData;
    forceFreshData = Boolean(options && options.fresh);
    const page = currentPageName();
    const restoredFromCache = !silent && restoreCachedView(page);
    const loaders = {
      dashboard: renderDashboard,
      vendas: renderSales,
      financeiro: renderFinance,
      caixa: renderCash,
      produtos: renderProducts,
      estoque: renderStock,
      clientes: renderClients,
      funcionarios: renderEmployees,
      auditoria: renderAudit,
      relatorios: renderReports,
      backups: renderBackups,
      configuracoes: renderOwnerSettings
    };
    const loader = loaders[page];
    if (!silent && !restoredFromCache && !body.classList.contains("app-data-ready")) {
      viewHost.innerHTML = '<section class="skeleton" aria-label="Carregando dados"></section>';
    }
    try {
      const hadStores = Array.isArray(storesCache) && storesCache.length > 0;
      const storesPromise = ensureStores().catch(() => null);
      const chromePromise = refreshChrome();
      if (loader) {
        await loader();
        wirePageActions(page);
        cacheCurrentView(page);
        body.classList.add("app-data-ready");
      }
      await storesPromise;
      if (!hadStores && Array.isArray(storesCache) && storesCache.length > 1 && !silent) {
        wireStoreTabs();
      }
      await chromePromise;
      schedulePrefetch(page);
    } catch (error) {
      await refreshChrome();
      renderError(error.message || "Não foi possível carregar os dados.");
    } finally {
      body.classList.add("app-data-ready");
      loadingData = false;
      forceFreshData = previousForceFresh;
      if (pendingDataReload) {
        pendingDataReload = false;
        loadPageData({ silent: true });
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
      loadPageData({ silent: true, fresh: true });
    }, 8000);
  }

  async function renderDashboard() {
    const data = await window.zentrixApi("/dashboard" + periodQuery());
    setStores(data.stores);
    const metrics = dashboardMetrics(data);
    const hero = `
      <section class="dashboard-hero">
        <div>
          <span class="hero-eyebrow">Zentrix PDV + AppGestão</span>
          <h1>${esc(greeting())}, Leonardo.</h1>
          <p>Aqui está o resumo da sua loja hoje. Controle vendas, estoque, caixa e financeiro de qualquer lugar.</p>
        </div>
        <div class="hero-status">
          <div class="status-card"><span>Status do PDV</span><strong>${esc(syncStatusLabel(data))}</strong></div>
          <div class="status-card"><span>Última atualização</span><strong>${esc(data.lastSync || "Aguardando dados do PDV")}</strong></div>
        </div>
      </section>
    `;
    renderShell("", "", `
      ${hero}
      ${storeTabsHtml()}
      <div class="grid metrics-grid">
        ${metrics.map((item) => metricCard(item.label, item.value, item.note, item.tone, item.icon, item.trend)).join("")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel">
          <div class="panel-title"><div><h3>Vendas da semana</h3><span>${esc(periodLabel())}</span></div><span class="tag info">Tempo real</span></div>
          ${barChartHtml(data.revenueChart || [])}
        </section>
        <section class="panel">
          <div class="panel-title"><div><h3>Produtos mais vendidos</h3><span>Ranking por receita</span></div></div>
          <div class="stack-list">${rankingHtml(data.topProducts || [])}</div>
        </section>
      </div>
      <div class="grid three-column" style="margin-top: 16px">
        <section class="panel">
          <div class="panel-title"><div><h3>Formas de pagamento</h3><span>Resumo do período</span></div></div>
          <div class="stack-list">${paymentsHtml(data.payments || [])}</div>
        </section>
        <section class="panel">
          <div class="panel-title"><div><h3>Últimas vendas</h3><span>Fluxo conectado ao PDV</span></div></div>
          <div class="stack-list">${compactSalesList(data.salesByStore || [])}</div>
        </section>
        <section class="panel">
          <div class="panel-title"><div><h3>Alertas importantes</h3><span>Operação e estoque</span></div></div>
          <div class="stack-list">${statusRowsHtml(data.stockHealth || [])}${syncAlertOwnerHtml(data)}</div>
        </section>
      </div>
    `, { hideHeader: true, noStoreTabs: true });
  }

  async function renderSales() {
    const rows = await window.zentrixApi("/sales" + periodQuery());
    const paidRows = rows.filter((row) => !String(row.status || "").toLowerCase().includes("cancel"));
    const cancelledRows = rows.length - paidRows.length;
    const total = sumMoney(paidRows.map((row) => row.total));
    const average = paidRows.length ? total / paidRows.length : 0;
    const exportId = "export-vendas";
    renderShell("Vendas", "Consulta de vendas, itens, pagamentos e cancelamentos sincronizados do PDV.", `
      ${filterStrip([
        ["Período", periodLabel()],
        ["Operador", "Todos"],
        ["Forma de pagamento", "Todas"],
        ["Loja", activeStoreName()]
      ])}
      <div class="grid metrics-grid">
        ${metricCard("Total vendido", formatCurrency(total), "Vendas concluídas no período", "success", "$", periodLabel())}
        ${metricCard("Vendas canceladas", String(cancelledRows), "Registros com cancelamento", cancelledRows ? "danger" : "success", "X", cancelledRows ? "Revisar" : "OK")}
        ${metricCard("Ticket médio", formatCurrency(average), "Média por venda paga", "info", "~", "Atual")}
        ${metricCard("Número de vendas", String(rows.length), "Últimos registros recebidos", "info", "#", "PDV")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        ${dataTableHtml("Vendas", ["Código", "Loja", "Horário", "Operador", "Pagamento", "Status", "Total"], rows, (row) => [
          row.code, row.store, row.time, row.operator, row.payment, tag(row.status), row.total
        ], exportId)}
        <section class="panel receipt-card">
          <div class="panel-title"><div><h3>Detalhe da venda</h3><span>Resumo visual do último cupom</span></div></div>
          ${saleReceiptHtml(rows[0])}
        </section>
      </div>
    `);
    setupCsvExport(exportId, "Vendas", ["Código", "Loja", "Horário", "Operador", "Pagamento", "Status", "Total"], rows.map((row) => [
      row.code, row.store, row.time, row.operator, row.payment, row.status, row.total
    ]));
  }

  async function renderFinance() {
    const data = await window.zentrixApi("/finance" + periodQuery());
    const cancelled = Number(data.cancelledSales || 0);
    renderShell("Financeiro", "Entradas, saldo, previsão e recebimentos por forma de pagamento.", `
      <div class="grid metrics-grid">
        ${metricCard("Entradas", data.periodTotal || data.todayTotal, periodLabel(), "success", "+", "Recebido")}
        ${metricCard("Saídas", "A informar", "Quando houver despesas no PDV, elas entram aqui", "warning", "-", "Pendente")}
        ${metricCard("Saldo", data.periodTotal || data.todayTotal, "Saldo operacional do período", "info", "$", "Atual")}
        ${metricCard("Previsão", data.monthTotal, "Faturamento do mês", "info", ">", "Mês")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Receita</h3><span>${esc(periodLabel())}</span></div></div>${barChartHtml(data.revenueChart || [])}</section>
        <section class="panel"><div class="panel-title"><div><h3>Divergências</h3><span>Cancelamentos e atenção financeira</span></div>${tag(cancelled ? "Atenção" : "Sem alerta")}</div>
          <div class="stack-list">
            <div class="list-item"><span class="list-icon ${cancelled ? "warning" : "success"}">${cancelled ? "!" : "OK"}</span><div><span class="list-title">Vendas canceladas</span><span class="list-subtitle">Impacto no fechamento financeiro</span></div><strong>${esc(String(cancelled))}</strong></div>
            <div class="list-item"><span class="list-icon info">$</span><div><span class="list-title">Participação por loja</span><span class="list-subtitle">Comparativo do período atual</span></div><strong>${esc(activeStoreName())}</strong></div>
          </div>
        </section>
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Formas de pagamento</h3><span>Dinheiro, Pix, cartão e crédito</span></div></div><div class="stack-list">${paymentsHtml(data.payments || [])}</div></section>
        <section class="panel"><div class="panel-title"><div><h3>Lojas</h3><span>Participação no período</span></div></div><div class="stack-list">${rankingHtml(data.salesByStore || [])}</div></section>
      </div>
    `);
  }

  async function renderCash() {
    const rows = await window.zentrixApi("/cash-sessions" + periodQuery());
    const openRows = rows.filter((row) => String(row.status || "").toLowerCase().includes("aberto"));
    const closedRows = rows.filter((row) => !String(row.status || "").toLowerCase().includes("aberto") && row.informed && row.informed !== "-");
    const expectedTotal = sumMoney(closedRows.map((row) => row.expected));
    const closingTotal = sumMoney(closedRows.map((row) => row.informed));
    const missingTotal = closedRows.reduce((total, row) => {
      const difference = cashDifferenceValue(row);
      return difference < 0 ? total + Math.abs(difference) : total;
    }, 0);
    const divergentRows = closedRows.filter((row) => cashDifferenceValue(row) < 0).length;
    const exportId = "export-caixa";
    renderShell("Caixa", "Sessões abertas, fechadas e movimentações operacionais.", `
      <section class="cash-hero">
        <div>
          <h3>${openRows.length ? "Caixa aberto" : "Caixa fechado"}</h3>
          <p>${openRows.length ? "Há sessão ativa acompanhada pelo AppGestão." : "Nenhuma sessão aberta nos últimos registros."}</p>
        </div>
        ${tag(openRows.length ? "Operação ativa" : "Fechado")}
      </section>
      <div class="grid metrics-grid">
        ${metricCard("Sessões", String(rows.length), "Registros no período", "info", "#", periodLabel())}
        ${metricCard("Abertos", String(openRows.length), "Caixas em operação", openRows.length ? "warning" : "success", "ON", "Agora")}
        ${metricCard("Fechados", String(rows.length - openRows.length), "Sessões finalizadas", "success", "OK", "PDV")}
        ${metricCard("Diferença no fechamento", formatCurrency(closingTotal - expectedTotal), "Valor fechado menos saldo esperado", missingTotal ? "danger" : "success", "!", missingTotal ? "Atenção" : "OK")}
        ${metricCard("Valor fechado", formatCurrency(closingTotal), "Total informado ao fechar o caixa", closedRows.length ? "info" : "warning", "$", closedRows.length ? "Conferido" : "Aguardando")}
        ${metricCard("Saldo esperado", formatCurrency(expectedTotal), "Valor que deveria estar no caixa", closedRows.length ? "info" : "warning", "=", "Sistema")}
        ${metricCard("Falta no caixa", formatCurrency(missingTotal), divergentRows ? `${divergentRows} fechamento(s) abaixo do esperado` : "Nenhum fechamento abaixo do esperado", divergentRows ? "danger" : "success", "!", divergentRows ? "Atenção" : "OK")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Timeline do caixa</h3><span>Abertura, sangria, suprimento e fechamento</span></div></div>${cashTimelineHtml(rows)}</section>
        ${dataTableHtml("Caixas", ["Loja", "Código", "Operador", "Abertura", "Fechamento", "Status", "Saldo esperado", "Valor fechado", "Diferença"], rows, (row) => [
          row.store, row.code, row.operator, row.openedAt, row.closedAt, tag(row.status), row.expected, row.informed || "-", cashDifferenceTag(row)
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Caixas", ["Loja", "Código", "Operador", "Abertura", "Fechamento", "Status", "Saldo esperado", "Valor fechado", "Diferença"], rows.map((row) => [
      row.store, row.code, row.operator, row.openedAt, row.closedAt, row.status, row.expected, row.informed || "-", row.difference || "-"
    ]));
  }

  async function renderProducts() {
    const rows = await window.zentrixApi("/products" + storeQuery());
    const inactive = rows.filter((row) => String(row.status || "").toLowerCase().includes("inativo")).length;
    const empty = rows.filter((row) => Number(row.currentStock || 0) <= 0).length;
    renderShell("Produtos", "Cadastro sincronizado por loja, com categoria, preço, estoque e status.", `
      <div class="grid metrics-grid">
        ${metricCard("Total de produtos", String(rows.length), "Itens recebidos do PDV", "info", "#", activeStoreName())}
        ${metricCard("Ativos", String(rows.length - inactive), "Disponíveis para venda", "success", "OK", "Catálogo")}
        ${metricCard("Inativos", String(inactive), "Sem operação ativa", inactive ? "warning" : "success", "II", "Cadastro")}
        ${metricCard("Sem estoque", String(empty), "Risco de ruptura", empty ? "danger" : "success", "0", "Estoque")}
      </div>
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-product">Novo produto</button><span class="chip info">Cadastro feito no PDV</span></div>
      <div class="entity-grid">
        ${rows.map((row) => productCardHtml(row)).join("") || emptyState("Ainda não há produtos nesta loja.")}
      </div>
    `);
  }

  async function renderStock() {
    const rows = await window.zentrixApi("/stock/alerts" + storeQuery());
    const critical = rows.filter((row) => Number(row.currentStock || 0) <= 0).length;
    renderShell("Estoque", "Produtos abaixo do estoque mínimo, risco de ruptura e alertas críticos.", `
      <div class="grid metrics-grid">
        ${metricCard("Produtos críticos", String(critical), "Sem estoque disponível", critical ? "danger" : "success", "!", "Agora")}
        ${metricCard("Estoque baixo", String(rows.length), "Abaixo do mínimo", rows.length ? "warning" : "success", "-", "Monitorado")}
        ${metricCard("Entradas", "Sincronizadas", "Movimentações recebidas do PDV", "success", "+", "PDV")}
        ${metricCard("Saídas", "Sincronizadas", "Vendas e baixas operacionais", "info", ">", "PDV")}
      </div>
      <div class="entity-grid" style="margin-top: 16px">
        ${rows.map((row) => stockCardHtml(row)).join("") || emptyState("Nenhum produto precisa de atenção no estoque.")}
      </div>
    `);
  }

  async function renderClients() {
    const rows = await window.zentrixApi("/clients" + storeQuery());
    const exportId = "export-clientes";
    renderShell("Clientes", "Cadastro, última compra, total gasto e frequência de relacionamento.", `
      <div class="grid metrics-grid">
        ${metricCard("Total de clientes", String(rows.length), "Base sincronizada", "info", "#", activeStoreName())}
        ${metricCard("Recorrentes", "Em análise", "Depende do histórico de compras", "warning", "~", "CRM")}
        ${metricCard("Novos", String(rows.length), "Cadastros recebidos do PDV", "success", "+", periodLabel())}
        ${metricCard("Frequência", "PDV", "Acompanhamento comercial", "info", "R", "Online")}
      </div>
      <div class="entity-grid" style="margin-top: 16px">
        ${rows.slice(0, 9).map((row) => clientCardHtml(row)).join("") || emptyState("Ainda não há clientes para mostrar.")}
      </div>
      <div style="margin-top: 16px">
        ${dataTableHtml("Clientes", ["Loja", "Nome", "CPF/CNPJ", "Telefone", "E-mail", "Endereço", "Cadastro"], rows, (row) => [
          row.store, row.name, row.cpfCnpj || "-", row.phone || "-", row.email || "-", row.address || "-", row.createdAt || "-"
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Clientes", ["Loja", "Nome", "CPF/CNPJ", "Telefone", "E-mail", "Endereço", "Cadastro"], rows.map((row) => [
      row.store, row.name, row.cpfCnpj || "-", row.phone || "-", row.email || "-", row.address || "-", row.createdAt || "-"
    ]));
  }

  async function renderEmployees() {
    const rows = await window.zentrixApi("/employees" + storeQuery());
    const active = rows.filter((row) => String(row.active || "").toLowerCase().includes("ativo")).length;
    renderShell("Funcionários", "Cargo, status, vendas realizadas e permissões operacionais.", `
      <div class="grid metrics-grid">
        ${metricCard("Funcionários", String(rows.length), "Usuários sincronizados", "info", "#", activeStoreName())}
        ${metricCard("Ativos", String(active), "Podem operar no PDV", "success", "OK", "Permissões")}
        ${metricCard("Administradores", String(rows.filter((row) => roleTone(row.role) === "danger").length), "Acesso elevado", "warning", "AD", "Segurança")}
        ${metricCard("Operadores", String(rows.filter((row) => roleTone(row.role) !== "danger").length), "Atendimento e vendas", "info", "OP", "Equipe")}
      </div>
      <div class="entity-grid" style="margin-top: 16px">
        ${rows.map((row) => employeeCardHtml(row)).join("") || emptyState("Ainda não há funcionários recebidos do PDV.")}
      </div>
    `);
  }

  async function renderAudit() {
    const rows = await window.zentrixApi("/audit" + periodQuery());
    const risky = rows.filter((row) => auditTone(row) !== "info").length;
    const exportId = "export-auditoria";
    renderShell("Auditoria", "Ações sensíveis, falhas de sincronização e alterações manuais.", `
      <div class="grid metrics-grid">
        ${metricCard("Eventos", String(rows.length), "Registros no período", "info", "#", periodLabel())}
        ${metricCard("Alertas", String(risky), "Cancelamentos, exclusões e falhas", risky ? "warning" : "success", "!", "Risco")}
        ${metricCard("Usuários", "PDV", "Origem dos eventos", "info", "US", "Auditoria")}
        ${metricCard("Sincronização", "Monitorada", "Eventos conectados ao PDV", "success", "OK", "Online")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Timeline de auditoria</h3><span>Eventos mais recentes</span></div></div>${auditTimelineHtml(rows)}</section>
        ${dataTableHtml("Auditoria", ["Loja", "Ação", "Horário", "Usuário", "Descrição", "Detalhes"], rows, (row) => [
          row.store, tag(row.action), row.time, row.user || "-", row.description, row.value || "-"
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Auditoria", ["Loja", "Ação", "Horário", "Usuário", "Descrição", "Detalhes"], rows.map((row) => [
      row.store, row.action, row.time, row.user || "-", row.description, row.value || "-"
    ]));
  }

  async function renderReports() {
    const data = await window.zentrixApi("/reports" + periodQuery());
    saveViewState(currentPageName(), "reports-overview", data);
    const summaryCards = Array.isArray(data.summaryCards) && data.summaryCards.length ? data.summaryCards : [
      { label: "Vendas", value: data.sales || 0, description: "Registros do per\u00edodo", tone: "info" },
      { label: "Produtos", value: data.products || 0, description: "Itens no cat\u00e1logo", tone: "info" },
      { label: "Clientes", value: data.clients || 0, description: "Base comercial", tone: "success" },
      { label: "Alertas de estoque", value: data.stockAlerts || 0, description: "Itens que pedem aten\u00e7\u00e3o", tone: Number(data.stockAlerts || 0) ? "warning" : "success" }
    ];
    const modules = Array.isArray(data.reportCards) && data.reportCards.length ? data.reportCards : [
      { type: "executive", title: "Relat\u00f3rio executivo", description: "Resumo de vendas, caixa e indicadores.", endpoint: "/reports", formats: ["PDF", "CSV"] },
      { type: "finance", title: "Planilha anal\u00edtica", description: "Dados para concilia\u00e7\u00e3o e confer\u00eancia.", endpoint: "/reports/finance", formats: ["XLS", "CSV"] },
      { type: "sales", title: "Exporta\u00e7\u00e3o operacional", description: "Arquivo leve para integra\u00e7\u00e3o externa.", endpoint: "/reports/sales", formats: ["CSV"] }
    ];
    renderShell("Relatórios", "Exportações por período, categoria e histórico gerencial.", `
      ${filterStrip([["Período", periodLabel()], ["Loja", activeStoreName()], ["Formatos", "PDF, Excel e CSV"]])}
      <div class="module-grid">
        ${modules.map(reportModuleCard).join("")}
      </div>
      <div class="grid metrics-grid" style="margin-top: 16px">
        ${summaryCards.map(reportMetricCard).join("")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Receita</h3><span>${esc(periodLabel())}</span></div></div>${barChartHtml(data.revenueChart || [])}</section>
        <section class="panel"><div class="panel-title"><div><h3>Diagnóstico</h3><span>Compatibilidade PDV + AppGestão</span></div></div><div class="stack-list">${diagnosticsHtml(data.diagnostics || [])}</div></section>
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Top produtos</h3><span>Maior faturamento</span></div></div><div class="stack-list">${rankingHtml(data.topProducts || [])}</div></section>
        <section class="panel"><div class="panel-title"><div><h3>Estoque</h3><span>Saúde operacional</span></div></div><div class="stack-list">${statusRowsHtml(data.stockHealth || [])}</div></section>
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        ${reportSummaryPanel("Vendas", data.salesReport)}
        ${reportSummaryPanel("Caixa", data.cashReport)}
      </div>
      <section class="panel" style="margin-top: 16px"><div class="panel-title"><div><h3>Histórico visual</h3><span>Relatórios gerados</span></div></div><div class="stack-list">${reportsHistoryHtml(data)}</div></section>
    `);
    wireReportActions(data);
  }

  async function renderBackups() {
    const rows = await window.zentrixApi("/backups" + storeQuery());
    const latest = rows[0];
    const failed = rows.filter((row) => String(row.status || "").toLowerCase().includes("fail")).length;
    const exportId = "export-backups";
    renderShell("Backups", "Histórico, status e segurança dos dados sincronizados.", `
      <div class="grid metrics-grid">
        ${metricCard("Último backup", latest ? latest.date : "Aguardando", "Recebido pelo AppGestão", latest ? "success" : "warning", "CL", "Nuvem")}
        ${metricCard("Status", failed ? "Falha no backup" : rows.length ? "Backup em dia" : "Backup pendente", "Monitoramento de segurança", failed ? "danger" : rows.length ? "success" : "warning", failed ? "!" : "OK", "Dados")}
        ${metricCard("Lotes recebidos", String(rows.length), "Sincronizações recentes", "info", "#", activeStoreName())}
        ${metricCard("Falhas", String(failed), "Backups com erro", failed ? "danger" : "success", "X", "Auditoria")}
      </div>
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="download-backup">Baixar backup</button><span class="chip success">Dados protegidos</span></div>
      <div class="grid two-column">
        <section class="panel"><div class="panel-title"><div><h3>Timeline de backups</h3><span>Últimas sincronizações</span></div></div>${backupTimelineHtml(rows)}</section>
        ${dataTableHtml("Backups", ["Data", "Origem", "Registros", "Status"], rows, (row) => [
          row.date, row.origin, row.size, tag(row.status)
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Backups", ["Data", "Origem", "Registros", "Status"], rows.map((row) => [
      row.date, row.origin, row.size, row.status
    ]));
  }

  async function renderOwnerSettings() {
    const data = await window.zentrixApi("/settings" + storeQuery());
    setStores(data.stores);
    const tenantName = data.tenant && data.tenant.name ? data.tenant.name : "Cliente Zentrix";
    const users = data.users || 0;
    const lastUpdate = data.lastSync || "Aguardando o primeiro envio do PDV";
    renderShell("Configurações", "Dados da loja, equipe, segurança e integração com o Zentrix PDV.", `
      <div class="module-grid settings-grid">
        ${settingsCard("Empresa", "Dados principais da loja", tenantName, "info")}
        ${settingsCard("Usuários", "Equipe com acesso ao painel", `${users} usuários`, "success")}
        ${settingsCard("Permissões", "Perfis de acesso", "Administrador, gerente e operador", "warning")}
        ${settingsCard("Aparência", "Tema claro e escuro", "Preferência salva neste computador", "info")}
        ${settingsCard("Segurança", "Acesso protegido", "Conta protegida para clientes Zentrix", "success")}
        ${settingsCard("Integração com PDV", "Loja conectada", activeStoreName(), "info")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Resumo do painel</h3><span>Zentrix AppGestão</span></div></div><div class="stack-list">
          <div class="list-item"><span class="list-icon success">OK</span><div><span class="list-title">Painel pronto para uso</span><span class="list-subtitle">Dados da loja protegidos</span></div><strong>Ativo</strong></div>
          <div class="list-item"><span class="list-icon info">LO</span><div><span class="list-title">Conta da loja</span><span class="list-subtitle">Identificação interna protegida</span></div><strong>${esc(tenantName)}</strong></div>
          <div class="list-item"><span class="list-icon success">ON</span><div><span class="list-title">Serviço online</span><span class="list-subtitle">Conectado com segurança</span></div><strong>Online</strong></div>
          <div class="list-item"><span class="list-icon info">PDV</span><div><span class="list-title">Última atualização</span><span class="list-subtitle">${esc(activeStoreName())}</span></div><strong>${esc(lastUpdate)}</strong></div>
        </div></section>
        <section class="panel">
          <div class="panel-title"><div><h3>Lojas cadastradas</h3><span>Lojas conectadas ao painel</span></div></div>
          <div class="stack-list">${storesListHtml(data.stores || [])}</div>
        </section>
      </div>
    `);
  }

  async function renderSettings() {
    const data = await window.zentrixApi("/settings" + storeQuery());
    setStores(data.stores);
    renderShell("Configurações", "Empresa, usuários, permissões, aparência, segurança e integração com PDV.", `
      <div class="module-grid settings-grid">
        ${settingsCard("Empresa", "Dados do cliente", data.tenant && data.tenant.name ? data.tenant.name : "Cliente Zentrix", "info")}
        ${settingsCard("Usuários", "Acessos administrativos", `${data.users || 0} usuários`, "success")}
        ${settingsCard("Permissões", "Perfis e níveis de acesso", "Administrador, gerente e operador", "warning")}
        ${settingsCard("Aparência", "Tema claro e escuro", "Preferência salva no navegador", "info")}
        ${settingsCard("Segurança", "Sessão e autenticação", "Acesso seguro para clientes Zentrix", "success")}
        ${settingsCard("Integração com PDV", "Origem selecionada", data.sourceId || "Todas as lojas", "info")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Resumo do painel</h3><span>Zentrix AppGestão</span></div></div><div class="stack-list">
          <div class="list-item"><span class="list-icon success">OK</span><div><span class="list-title">Dados do painel</span><span class="list-subtitle">Armazenamento ativo</span></div><strong>Ativo</strong></div>
          <div class="list-item"><span class="list-icon info">ID</span><div><span class="list-title">Cliente</span><span class="list-subtitle">${esc(data.tenant && data.tenant.id ? data.tenant.id : "legacy")}</span></div><strong>${esc(data.tenant && data.tenant.name ? data.tenant.name : "Cliente")}</strong></div>
          <div class="list-item"><span class="list-icon success">ON</span><div><span class="list-title">Acesso online</span><span class="list-subtitle">${esc(data.api)}</span></div><strong>Online</strong></div>
          <div class="list-item"><span class="list-icon info">PDV</span><div><span class="list-title">Última sincronização</span><span class="list-subtitle">${esc(data.sourceId || "Todas as lojas")}</span></div><strong>${esc(data.lastSync || "-")}</strong></div>
        </div></section>
        <section class="panel">
          <div class="panel-title"><div><h3>Lojas cadastradas</h3><span>Origem da sincronização</span></div></div>
          <div class="stack-list">${storesListHtml(data.stores || [])}</div>
        </section>
      </div>
    `);
  }

  function renderShell(title, subtitle, content, options) {
    const header = options && options.hideHeader ? "" : `<div class="page-header"><div><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div>${options && options.actions ? `<div class="page-actions">${options.actions}</div>` : ""}</div>`;
    const tabs = options && options.noStoreTabs ? "" : storeTabsHtml();
    viewHost.innerHTML = `${header}${tabs}${content}`;
    wireStoreTabs();
  }

  function renderError(message) {
    const detail = message ? friendlyMessage(message) : "Verifique se o Zentrix PDV e o serviço online estão abertos.";
    viewHost.innerHTML = `<section class="state-box"><div><strong>Não conseguimos mostrar os dados agora</strong><p>${esc(detail)}</p></div></section>`;
  }

  function metricCard(label, value, note, toneValue, icon, trend) {
    return `<article class="metric-card">
      <div class="metric-top"><span class="metric-icon ${toneValue || "info"}">${esc(icon || "#")}</span><span class="tag ${tone(toneValue)}">${esc(trend || "Atual")}</span></div>
      <span class="metric-label">${esc(label)}</span>
      <strong class="metric-value">${esc(value)}</strong>
      <span class="metric-note">${esc(note || "Atualizado pelo Zentrix PDV.")}</span>
    </article>`;
  }

  function dataTableHtml(title, headers, rows, mapper, exportId) {
    return `<section class="table-panel">
      <div class="table-title"><h3>${esc(title)}</h3><div class="table-actions"><span>${esc(String(rows.length))} registros</span><button class="button btn-light compact-button" id="${esc(exportId)}" type="button">Exportar CSV</button></div></div>
      <div class="table-wrap"><table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>
        ${rows.map((row) => `<tr>${mapper(row).map((value) => `<td>${isTrustedTag(value) ? value : esc(value)}</td>`).join("")}</tr>`).join("") || `<tr><td colspan="${headers.length}">${emptyState("Ainda não há informações para este período.")}</td></tr>`}
      </tbody></table></div>
    </section>`;
  }

  function filterStrip(items) {
    return `<div class="filter-strip">${items.map(([label, value]) => `<span class="chip info">${esc(label)}: ${esc(value)}</span>`).join("")}</div>`;
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
      { label: "Lucro estimado", value: "Em análise", note: "Depende do custo sincronizado", tone: "warning", icon: "%", trend: "Estimativa" },
      { label: "Ticket médio", value: ticket.value || "R$ 0,00", note: "Média por venda concluída", tone: "info", icon: "~", trend: ticket.trend || periodLabel() },
      { label: "Produtos vendidos", value: productsSold ? String(Math.round(productsSold)) : "0", note: "Itens presentes no ranking", tone: "success", icon: "#", trend: "Top itens" },
      { label: "Caixa atual", value: syncStatusLabel(data), note: "Status recebido do Zentrix PDV", tone: data.syncProgress === 100 ? "success" : "warning", icon: "PDV", trend: "Online" },
      { label: "Estoque crítico", value: stock.value || "0", note: stock.trend || "Produtos em atenção", tone: "danger", icon: "!", trend: "Crítico" }
    ];
  }

  function productCardHtml(row) {
    const level = stockLevel(row);
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
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar info">${esc(initials(row.name))}</span><span class="tag info">Cliente</span></div>
      <strong>${esc(row.name || "Cliente")}</strong>
      <div class="entity-meta">
        <div><span>Última compra</span><strong>Em análise</strong></div>
        <div><span>Total gasto</span><strong>PDV</strong></div>
        <div><span>Frequência</span><strong>Sincronizada</strong></div>
        <div><span>Telefone</span><strong>${esc(row.phone || "-")}</strong></div>
      </div>
    </article>`;
  }

  function employeeCardHtml(row) {
    const role = roleTone(row.role);
    return `<article class="entity-card">
      <div class="entity-head"><span class="avatar ${role}">${esc(initials(row.name || row.username))}</span>${tag(row.active)}</div>
      <strong>${esc(row.name || row.username)}</strong>
      <div class="entity-meta">
        <div><span>Cargo</span><strong>${esc(row.role || "Operador")}</strong></div>
        <div><span>Usuário</span><strong>${esc(row.username)}</strong></div>
        <div><span>Vendas realizadas</span><strong>PDV</strong></div>
        <div><span>Permissões</span><strong>${esc(role === "danger" ? "Administrador" : role === "warning" ? "Gerente" : "Operador")}</strong></div>
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
    return metricCard(row.label || "Indicador", row.value || "0", row.description || row.note || "Atualizado pelo Zentrix PDV.", row.tone || "info", initials(row.label || "#"), row.trend || periodLabel());
  }

  function reportSummaryPanel(title, report) {
    const cards = report && Array.isArray(report.summaryCards) ? report.summaryCards : [];
    const diagnostics = report && Array.isArray(report.diagnostics) ? report.diagnostics : [];
    return `<section class="panel"><div class="panel-title"><div><h3>${esc(title)}</h3><span>Resumo profissional</span></div></div>
      <div class="stack-list">
        ${cards.slice(0, 4).map((card) => `<div class="list-item"><span class="list-icon ${tone(card.tone)}">${esc(initials(card.label || title))}</span><div><span class="list-title">${esc(card.label)}</span><span class="list-subtitle">${esc(card.description || card.note || "Indicador do relatório")}</span></div><strong>${esc(card.value)}</strong></div>`).join("") || emptyState("Relatório aguardando dados do PDV.")}
        ${diagnostics.slice(0, 2).map((item) => `<div class="list-item"><span class="list-icon warning">!</span><div><span class="list-title">Diagnóstico</span><span class="list-subtitle">${esc(item)}</span></div><strong>PDV</strong></div>`).join("")}
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
    if (format === "PDF") {
      openPrintableReport(title, reportData);
      return;
    }
    if (format === "XLS" || format === "EXCEL") {
      downloadXlsReport(title, reportData);
      return;
    }
    if (format === "JSON") {
      downloadBlob(slug(title) + ".json", JSON.stringify(reportData, null, 2), "application/json;charset=utf-8");
      return;
    }
    downloadCsvReport(title, reportData);
  }

  function downloadCsvReport(title, reportData) {
    const rows = reportRows(reportData);
    const headers = reportHeaders(rows);
    const csv = [headers, ...rows.map((row) => headers.map((header) => row[header] ?? ""))]
      .map((row) => row.map((value) => csvCell(normalizeText(value))).join(";"))
      .join("\n");
    downloadBlob(slug(title) + ".csv", "\ufeff" + csv, "text/csv;charset=utf-8");
  }

  function downloadXlsReport(title, reportData) {
    const rows = reportRows(reportData);
    const headers = reportHeaders(rows);
    const table = `<table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${headers.map((header) => `<td>${esc(row[header] ?? "")}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
    const html = `<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:Arial,sans-serif}table{border-collapse:collapse;width:100%}th,td{border:1px solid #d7dee8;padding:8px;text-align:left}th{background:#eaf3ff}</style></head><body><h1>${esc(title)}</h1><p>Zentrix AppGestão - ${esc(periodLabel())} - ${esc(activeStoreName())}</p>${table}</body></html>`;
    downloadBlob(slug(title) + ".xls", "\ufeff" + html, "application/vnd.ms-excel;charset=utf-8");
  }

  function openPrintableReport(title, reportData) {
    const rows = reportRows(reportData).slice(0, 120);
    const headers = reportHeaders(rows);
    const cards = Array.isArray(reportData && reportData.summaryCards) ? reportData.summaryCards : [];
    const diagnostics = Array.isArray(reportData && reportData.diagnostics) ? reportData.diagnostics : [];
    const printWindow = window.open("", "_blank");
    if (!printWindow) {
      downloadXlsReport(title, reportData);
      renderToast("Pop-up bloqueado. Baixei uma versão em Excel do relatório.", "warning");
      return;
    }
    printWindow.document.write(`<!doctype html>
      <html><head><meta charset="utf-8"><title>${esc(title)}</title>
      <style>
        body{margin:0;background:#f4f8fc;color:#142033;font-family:Arial,sans-serif}
        .page{max-width:1100px;margin:0 auto;padding:32px}
        .hero{background:#0b4dd8;color:#fff;border-radius:18px;padding:28px;margin-bottom:18px}
        .hero h1{margin:0 0 8px;font-size:30px}.hero p{margin:0;color:#dceaff}
        .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}
        .card,.panel{background:#fff;border:1px solid #d9e6f5;border-radius:14px;padding:16px;box-shadow:0 8px 24px rgba(20,42,80,.08)}
        .card span{display:block;color:#64748b;font-size:12px}.card strong{display:block;font-size:22px;margin-top:6px}
        table{width:100%;border-collapse:collapse;background:#fff;border-radius:14px;overflow:hidden}
        th,td{border-bottom:1px solid #e4edf8;padding:10px;text-align:left;font-size:12px}th{background:#eaf3ff;color:#0b4dd8}
        .diag{margin:0;padding-left:18px;color:#45556b}.footer{margin-top:18px;color:#64748b;font-size:12px}
        @media print{body{background:#fff}.page{padding:0}.card,.panel{box-shadow:none}.no-print{display:none}}
      </style></head><body><main class="page">
        <section class="hero"><h1>${esc(title)}</h1><p>Zentrix AppGestão conectado ao Zentrix PDV | ${esc(periodLabel())} | ${esc(activeStoreName())}</p></section>
        <section class="grid">${cards.slice(0, 4).map((card) => `<article class="card"><span>${esc(card.label)}</span><strong>${esc(card.value)}</strong><small>${esc(card.description || card.note || "")}</small></article>`).join("")}</section>
        <section class="panel"><h2>Dados do relatório</h2>${headers.length ? `<table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${headers.map((header) => `<td>${esc(row[header] ?? "")}</td>`).join("")}</tr>`).join("")}</tbody></table>` : emptyState("Este relatório ainda não tem dados no período escolhido.")}</section>
        ${diagnostics.length ? `<section class="panel"><h2>Diagnóstico</h2><ul class="diag">${diagnostics.map((item) => `<li>${esc(item)}</li>`).join("")}</ul></section>` : ""}
        <p class="footer">Gerado em ${esc(new Date().toLocaleString("pt-BR"))}. Use Ctrl+P para salvar como PDF.</p>
        <button class="no-print" onclick="window.print()">Imprimir ou salvar PDF</button>
      </main></body></html>`);
    printWindow.document.close();
    printWindow.focus();
    setTimeout(() => printWindow.print(), 350);
  }

  function reportRows(data) {
    if (Array.isArray(data)) return data.map(flattenReportRow);
    if (!data || typeof data !== "object") return [];
    const preferred = data.rows || data.sessions || data.events || data.alerts || data.movements || data.topProducts || data.payments || data.reportCards;
    if (Array.isArray(preferred) && preferred.length) return preferred.map(flattenReportRow);
    if (Array.isArray(data.summaryCards) && data.summaryCards.length) {
      return data.summaryCards.map((card) => flattenReportRow({
        indicador: card.label,
        valor: card.value,
        descricao: card.description || card.note || "",
        status: card.tone || ""
      }));
    }
    return Object.entries(data)
      .filter(([, value]) => value == null || ["string", "number", "boolean"].includes(typeof value))
      .map(([key, value]) => ({ campo: key, valor: value }));
  }

  function flattenReportRow(row) {
    const output = {};
    Object.entries(row || {}).forEach(([key, value]) => {
      if (value == null) {
        output[key] = "";
      } else if (typeof value === "object") {
        output[key] = JSON.stringify(value);
      } else {
        output[key] = normalizeText(String(value).replace(/<[^>]+>/g, ""));
      }
    });
    return output;
  }

  function reportHeaders(rows) {
    const headers = [];
    rows.forEach((row) => Object.keys(row).forEach((key) => {
      if (!headers.includes(key)) headers.push(key);
    }));
    return headers;
  }

  function downloadBlob(fileName, content, type) {
    const blob = new Blob([content], { type });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  function renderToast(message, level) {
    const toast = document.createElement("div");
    toast.className = "toast " + tone(level || "info");
    toast.textContent = normalizeText(message);
    document.body.appendChild(toast);
    setTimeout(() => toast.classList.add("show"), 20);
    setTimeout(() => toast.remove(), 3200);
  }

  function slug(value) {
    return normalizeText(value).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "relatorio";
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
      return `<div class="timeline-item">
        <span class="list-icon ${level}">${level === "danger" ? "!" : level === "warning" ? "AT" : "IN"}</span>
        <div><span class="list-title">${esc(row.action || "Evento")}</span><span class="list-subtitle">${esc(row.user || "Usuário")} - ${esc(row.description || "-")}</span></div>
        <strong>${esc(row.time || "-")}</strong>
      </div>`;
    }).join("")}</div>`;
  }

  function backupTimelineHtml(rows) {
    if (!rows.length) return emptyState("Ainda não há backup recebido do PDV.");
    return `<div class="timeline">${rows.slice(0, 8).map((row) => `
      <div class="timeline-item">
        <span class="list-icon ${tagTone(row.status)}">CL</span>
        <div><span class="list-title">${esc(row.origin || "Origem")}</span><span class="list-subtitle">${esc(row.size || "0 registros")}</span></div>
        <strong>${esc(row.date || "-")}</strong>
      </div>`).join("")}</div>`;
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
    const list = Array.isArray(rows) && rows.length ? rows : ["Dados sincronizados e relatórios prontos para análise."];
    return list.map((item, index) => `<div class="list-item"><span class="list-icon ${index ? "info" : "warning"}">${index ? "IN" : "PDV"}</span><div><span class="list-title">${index ? "Observação" : "Diagnóstico"}</span><span class="list-subtitle">${esc(item)}</span></div><strong>${index ? "OK" : "Sync"}</strong></div>`).join("");
  }

  function paymentsHtml(rows) {
    return rows.map((row) => `<div class="payment-row"><strong>${esc(row.name)}</strong><div class="progress-track"><span style="width: ${percentWidth(row.percent)}%"></span></div><span>${esc(row.total)}</span></div>`).join("") || emptyState("Ainda não há pagamentos no período escolhido.");
  }

  function barChartHtml(rows) {
    const values = rows.map((row) => Number(row.value) || 0);
    const max = Math.max(0, ...values);
    if (!rows.length) return emptyState("Ainda não há dados para o gráfico.");
    return `<div class="bar-chart management-chart">
      ${rows.map((row) => {
        const value = Number(row.value) || 0;
        const height = max <= 0 ? 0 : Math.max(8, Math.round((value / max) * 100));
        return `<div class="bar-column"><span style="height: ${height}%"><em>${esc(row.display || "")}</em></span><small>${esc(row.label)}</small></div>`;
      }).join("")}
    </div>`;
  }

  function rankingHtml(rows) {
    const values = rows.map((row) => Number(row.value) || 0);
    const max = Math.max(0, ...values);
    return rows.map((row, index) => {
      const value = Number(row.value) || 0;
      const width = max <= 0 ? 0 : Math.max(6, Math.round((value / max) * 100));
      const subtitle = row.sales ? `${row.sales} vendas` : row.code ? `Código ${row.code}` : row.quantity ? `${row.quantity} itens vendidos` : "Período atual";
      return `<div class="rank-row">
        <span class="list-icon info">${esc(String(index + 1).padStart(2, "0"))}</span>
        <div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">${esc(subtitle)}</span><div class="progress-track"><span style="width: ${width}%"></span></div></div>
        <strong>${esc(row.display || row.value || "0")}</strong>
      </div>`;
    }).join("") || emptyState("Ainda não há dados no período escolhido.");
  }

  function compactSalesList(rows) {
    return rows.slice(0, 4).map((row) => `<div class="list-item"><span class="list-icon success">$</span><div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">${esc(row.sales ? row.sales + " vendas" : "Loja atualizada")}</span></div><strong>${esc(row.display || row.value || "0")}</strong></div>`).join("") || emptyState("Aguardando vendas do PDV.");
  }

  function statusRowsHtml(rows) {
    return rows.map((row) => `<div class="list-item"><span class="list-icon ${tone(row.tone)}">${esc(row.display)}</span><div><span class="list-title">${esc(row.label)}</span><span class="list-subtitle">Status atual dos produtos</span></div><strong>${esc(row.display)}</strong></div>`).join("") || emptyState("Ainda não há produtos para acompanhar.");
  }

  function syncAlertOwnerHtml(data) {
    const synced = data && Number(data.syncProgress || 0) === 100 && data.lastSync;
    const title = synced ? "PDV conectado" : "Atualização pendente";
    const subtitle = data && data.lastSync ? data.lastSync : "Aguardando o primeiro envio do PDV";
    return `<div class="list-item"><span class="list-icon ${synced ? "success" : "warning"}">${synced ? "OK" : "!"}</span><div><span class="list-title">${esc(title)}</span><span class="list-subtitle">${esc(subtitle)}</span></div><strong>${esc(String((data && data.syncProgress) || 0))}%</strong></div>`;
  }

  function syncAlertHtml(data) {
    const synced = data && Number(data.syncProgress || 0) === 100 && data.lastSync;
    return `<div class="list-item"><span class="list-icon ${synced ? "success" : "warning"}">${synced ? "OK" : "!"}</span><div><span class="list-title">${synced ? "PDV conectado" : "Sincronização pendente"}</span><span class="list-subtitle">${esc(data.lastSync || "Aguardando primeira sincronização")}</span></div><strong>${esc(String(data.syncProgress || 0))}%</strong></div>`;
  }

  function storesListHtml(rows) {
    return rows.filter((row) => !row.isAll).map((row) => `<div class="list-item"><span class="list-icon info">LJ</span><div><span class="list-title">${esc(row.name)}</span><span class="list-subtitle">Loja conectada ao Zentrix PDV</span></div><strong>${esc(row.lastSync || "Aguardando atualização")}</strong></div>`).join("") || emptyState("Nenhuma loja conectada ao painel.");
  }

  function storeTabsHtml() {
    const stores = storesCache || [];
    if (stores.length <= 1) return "";
    return `<div class="store-tabs" role="tablist" aria-label="Lojas">${stores.map((store) => `
      <button class="${store.id === currentStore ? "active" : ""}" type="button" data-store-id="${esc(store.id)}" role="tab" aria-selected="${store.id === currentStore ? "true" : "false"}">
        <span>${esc(store.name)}</span>
        <small>${esc(store.isAll ? "Todas as lojas" : "Loja conectada")}</small>
      </button>
    `).join("")}</div>`;
  }

  function wireStoreTabs() {
    viewHost.querySelectorAll("[data-store-id]").forEach((button) => {
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
    return `<div class="empty-state"><strong>${esc(message || "Ainda não há informações para mostrar.")}</strong><span>Quando o PDV enviar novos dados, esta área será atualizada automaticamente.</span></div>`;
  }

  function friendlyMessage(message) {
    const text = normalizeText(String(message || ""));
    if (!text) return "Verifique se o Zentrix PDV e o serviço online estão abertos.";
    if (text.toLowerCase().includes("failed to fetch") || text.toLowerCase().includes("network")) {
      return "Não foi possível conversar com o serviço online. Confira se o backend do Zentrix está aberto.";
    }
    if (text.includes("401") || text.toLowerCase().includes("unauthorized")) {
      return "A chave de acesso entre PDV e painel precisa ser conferida.";
    }
    if (text.includes("404")) {
      return "Esta informação ainda não está disponível no painel.";
    }
    if (text.includes("500") || text.includes("503")) {
      return "O serviço online do Zentrix está iniciando ou encontrou instabilidade. Tente atualizar novamente.";
    }
    return text.replace(/\bAPI\b/g, "serviço online").replace(/\bendpoint\b/gi, "recurso");
  }

  function initChromeSkeleton() {
    setText(".status-pill", "Conectando");
    const statusPill = document.querySelector(".status-pill");
    if (statusPill) statusPill.className = "status-pill warning";
    setText(".sidebar-sync strong", "Atualização");
    setText(".sidebar-sync span", "Carregando estado real");
    const progress = document.querySelector(".sidebar-sync .progress-track span");
    if (progress) progress.style.width = "0%";
    setText(".sidebar-sync strong", "Preparando painel");
    setText(".sidebar-sync span", "Buscando dados da loja");
    setText(".sidebar-sync .button", "Ver histórico");
    setText(".sidebar-sync .button", "Histórico");
    setText(".window-title span:last-child", "Zentrix AppGestão");
    setText(".sidebar-sync .button", "Ver histórico");
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
    } catch (error) {
      updateChrome(null);
    }
  }

  function updateChrome(data) {
    if (data && Array.isArray(data.stores)) {
      setStores(data.stores);
    }
    const active = data && data.activeStore ? data.activeStore : { name: "Geral", label: "Todas as lojas" };
    const companyName = active && active.name ? String(active.name) : "Geral";
    const lastSync = data && data.lastSync ? String(data.lastSync) : "";
    const progress = Math.max(0, Math.min(100, Number((data && data.syncProgress) || 0)));
    const synced = progress === 100 && Boolean(lastSync);
    const title = "Zentrix AppGestão - " + companyName;

    setText(".window-title span:last-child", title);
    const statusPill = document.querySelector(".status-pill");
    if (statusPill) {
      statusPill.className = "status-pill " + (synced ? "success" : "warning");
      statusPill.textContent = synced ? "Online" : "Atualizando";
    }

    setText(".sidebar-sync strong", synced ? "PDV conectado" : companyName);
    setText(".sidebar-sync span", lastSync ? "Última sincronização: " + lastSync : "Aguardando primeira sincronização");
    setText(".sidebar-sync .button", "Histórico");
    const progressBar = document.querySelector(".sidebar-sync .progress-track span");
    if (progressBar) progressBar.style.width = progress + "%";
    setText(".sidebar-sync strong", synced ? "Loja atualizada" : "Atualizando dados");
    setText(".sidebar-sync span", lastSync ? "Última atualização: " + lastSync : "Aguardando o primeiro envio do PDV");
    setText(".sidebar-sync .button", "Ver histórico");
    setText(".topbar-connection", synced ? "Online" : "Atualizando");
    setText(".topbar-pdv", synced ? "PDV conectado" : "PDV aguardando");
    setText(".topbar-pdv", synced ? "PDV conectado" : "Aguardando PDV");
  }

  function enhanceChrome() {
    document.title = normalizeText(document.title).replace("Zentrix Web", "Zentrix AppGestão");
    setText(".window-title span:last-child", "Zentrix AppGestão");
    setText(".sidebar-brand span", "Gestão online conectada ao Zentrix PDV");
    if (themeButton) {
      themeButton.textContent = "Tema";
      themeButton.classList.add("theme-toggle");
      themeButton.setAttribute("aria-label", "Alternar tema claro ou escuro");
    }
    if (menuButton) menuButton.setAttribute("aria-label", "Abrir menu");
    if (closeSidebarButton) {
      closeSidebarButton.textContent = "x";
      closeSidebarButton.setAttribute("aria-label", "Fechar menu");
    }
    const toolbar = document.querySelector(".window-toolbar");
    if (toolbar && !toolbar.querySelector(".notification-button")) {
      const notification = document.createElement("button");
      notification.className = "icon-button notification-button";
      notification.type = "button";
      notification.setAttribute("aria-label", "Notificações");
      notification.textContent = "!";
      toolbar.insertBefore(notification, toolbar.querySelector(".button"));
    }
    const topbarTools = document.querySelector(".topbar-tools");
    if (topbarTools && !topbarTools.querySelector(".topbar-connection")) {
      topbarTools.insertAdjacentHTML("afterbegin", '<span class="chip success topbar-connection">Online</span><span class="chip info topbar-pdv">PDV conectado</span>');
    }
    rebuildNavigationWithAssets();
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
    nav.innerHTML = groups.map(([group, links]) => `
      <div class="nav-section">${esc(group)}</div>
      ${links.map(([href, label, icon]) => `<a class="nav-item ${href === currentPage ? "active" : ""}" href="${href}"><span class="nav-icon"><img src="../assets/Icons/${escAttr(icon)}" data-fallback="../assets/Icons/${escAttr(iconFallbackFile(icon))}" alt="" loading="eager" decoding="async" onerror="this.onerror=null;this.src=this.dataset.fallback;" /></span><span>${esc(label)}</span></a>`).join("")}
    `).join("");
  }

  function iconFallbackFile(name) {
    return String(name || "").replace(/\.png$/i, "-removebg-preview.png");
  }

  function rebuildNavigation() {
    const nav = document.querySelector(".nav-list");
    if (!nav) return;
    const currentPage = location.pathname.split("/").pop();
    const groups = [
      ["Operação", [
        ["dashboard.html", "Dashboard", "◆"],
        ["vendas.html", "Vendas", "$"],
        ["caixa.html", "Caixa", "▣"]
      ]],
      ["Gestão", [
        ["financeiro.html", "Financeiro", "R$"],
        ["produtos.html", "Produtos", "□"],
        ["estoque.html", "Estoque", "▤"],
        ["clientes.html", "Clientes", "◎"],
        ["funcionarios.html", "Funcionários", "●"]
      ]],
      ["Segurança e Sistema", [
        ["auditoria.html", "Auditoria", "!"],
        ["relatorios.html", "Relatórios", "▥"],
        ["backups.html", "Backups", "CL"],
        ["configuracoes.html", "Configurações", "⚙"]
      ]]
    ];
    nav.innerHTML = groups.map(([group, links]) => `
      <div class="nav-section">${esc(group)}</div>
      ${links.map(([href, label, icon]) => `<a class="nav-item ${href === currentPage ? "active" : ""}" data-icon="${esc(icon)}" href="${href}"><span>${esc(label)}</span></a>`).join("")}
    `).join("");
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
      "mes": "month",
      "mês": "month",
      "ano": "year"
    };
    control.querySelectorAll("button").forEach((button) => {
      const key = normalizeText(button.textContent.trim().toLowerCase());
      const period = periodByText[key] || "today";
      button.dataset.period = period;
      button.textContent = periodLabel(period);
      button.classList.toggle("active", period === currentPeriod);
      button.addEventListener("click", () => {
        if (currentPeriod === period) return;
        currentPeriod = period;
        localStorage.setItem("zentrix-period", currentPeriod);
        control.querySelectorAll("button").forEach((item) => item.classList.toggle("active", item === button));
        schedulePrefetch(currentPageName());
        loadPageData();
      });
    });
  }

  function periodQuery() {
    return queryString(currentPeriod, currentStore);
  }

  function storeQuery() {
    return queryString(null, currentStore);
  }

  function queryString(period, store) {
    const params = new URLSearchParams();
    if (period) {
      params.set("period", period);
    }
    params.set("store", store || currentStore || "all");
    return "?" + params.toString();
  }

  function currentPageName() {
    return location.pathname.split("/").pop().replace(".html", "");
  }

  function periodLabel(value) {
    const period = value || currentPeriod;
    return {
      today: "Hoje",
      "7d": "7 dias",
      month: "Mês",
      year: "Ano"
    }[period] || "Hoje";
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

  function syncStatusLabel(data) {
    return data && Number(data.syncProgress || 0) === 100 && data.lastSync ? "PDV conectado" : "Atualizando";
  }

  function percentWidth(value) {
    const percent = Number(value);
    if (!Number.isFinite(percent)) return 0;
    return Math.max(0, Math.min(100, percent));
  }

  function setupCsvExport(buttonId, title, headers, rows) {
    saveViewState(currentPageName(), "csv:" + buttonId, { title, headers, rows });
    const button = document.getElementById(buttonId);
    if (!button) return;
    wireCsvExportButton(button, title, headers, rows);
  }

  function restoreCsvExports(page) {
    viewHost.querySelectorAll("button[id^='export-']").forEach((button) => {
      const payload = readViewState(page, "csv:" + button.id);
      if (payload) {
        wireCsvExportButton(button, payload.title, payload.headers || [], payload.rows || []);
      }
    });
  }

  function wireCsvExportButton(button, title, headers, rows) {
    if (button.dataset.exportReady === "true") return;
    button.dataset.exportReady = "true";
    button.disabled = !Array.isArray(rows) || rows.length === 0;
    button.addEventListener("click", () => {
      downloadCsvPayload(title, headers, rows);
    });
  }

  function downloadCsvPayload(title, headers, rows) {
    const csv = [headers, ...rows].map((row) => row.map((value) => csvCell(normalizeText(value))).join(";")).join("\n");
    const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = title.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-") + ".csv";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  function csvCell(value) {
    return '"' + String(value ?? "").replace(/<[^>]+>/g, "").replace(/"/g, '""') + '"';
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

  function auditTone(row) {
    const text = normalizeText(`${row.action || ""} ${row.description || ""} ${row.value || ""}`).toLowerCase();
    if (text.includes("exclus") || text.includes("cancel") || text.includes("falha") || text.includes("delete")) return "danger";
    if (text.includes("alter") || text.includes("manual") || text.includes("sync")) return "warning";
    return "info";
  }

  function sumMoney(values) {
    return values.reduce((total, value) => total + moneyValue(value), 0);
  }

  function moneyValue(value) {
    const normalized = String(value || "0").replace(/[^\d,-]/g, "").replace(/\./g, "").replace(",", ".");
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function formatCurrency(value) {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(Number(value) || 0);
  }

  function initials(value) {
    const words = normalizeText(value || "").trim().split(/\s+/).filter(Boolean);
    if (!words.length) return "ZT";
    return words.slice(0, 2).map((word) => word[0]).join("").toUpperCase();
  }

  function greeting() {
    const hour = new Date().getHours();
    if (hour < 12) return "Bom dia";
    if (hour < 18) return "Boa tarde";
    return "Boa noite";
  }

  function normalizeKey(value) {
    return String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
  }

  function normalizeText(value) {
    let text = String(value ?? "");
    const replacements = [
      [/\bZentrix Web\b/g, "Zentrix AppGestão"],
      [/\bExtensao\b/g, "Extensão"],
      [/\bSincronizacao\b/g, "Sincronização"],
      [/\bsincronizacao\b/g, "sincronização"],
      [/\bHistorico\b/g, "Histórico"],
      [/\bhistorico\b/g, "histórico"],
      [/\bConfiguracoes\b/g, "Configurações"],
      [/\bconfiguracoes\b/g, "configurações"],
      [/\bFuncionarios\b/g, "Funcionários"],
      [/\bfuncionarios\b/g, "funcionários"],
      [/\bRelatorios\b/g, "Relatórios"],
      [/\brelatorios\b/g, "relatórios"],
      [/\bOperacao\b/g, "Operação"],
      [/\boperacao\b/g, "operação"],
      [/\bMes\b/g, "Mês"],
      [/\bMovimentacoes\b/g, "Movimentações"],
      [/\bmovimentacoes\b/g, "movimentações"],
      [/\bDecisoes\b/g, "Decisões"],
      [/\bdecisoes\b/g, "decisões"],
      [/\bUsuario\b/g, "Usuário"],
      [/\busuario\b/g, "usuário"],
      [/\bUsuarios\b/g, "Usuários"],
      [/\busuarios\b/g, "usuários"],
      [/\bSessao\b/g, "Sessão"],
      [/\bsessoes\b/g, "sessões"],
      [/\bSessoes\b/g, "Sessões"],
      [/\bNao\b/g, "Não"],
      [/\bnao\b/g, "não"],
      [/\bpossivel\b/g, "possível"],
      [/\binvalidos\b/g, "inválidos"],
      [/\besta\b/g, "está"],
      [/\bindisponivel\b/g, "indisponível"],
      [/\bmedio\b/g, "médio"],
      [/\bcriticos\b/g, "críticos"],
      [/\bSaudavel\b/g, "Saudável"],
      [/\bperiodo\b/g, "período"],
      [/\bPeriodo\b/g, "Período"],
      [/\bCodigo\b/g, "Código"],
      [/\bpreco\b/g, "preço"],
      [/\bPreco\b/g, "Preço"],
      [/\bMinimo\b/g, "Mínimo"],
      [/\bminimo\b/g, "mínimo"],
      [/\bEndereco\b/g, "Endereço"],
      [/\bendereco\b/g, "endereço"],
      [/\bAcao\b/g, "Ação"],
      [/\bAcoes\b/g, "Ações"],
      [/\bacoes\b/g, "ações"],
      [/\bDescricao\b/g, "Descrição"],
      [/\bdescricao\b/g, "descrição"],
      [/\bDetalhes\b/g, "Detalhes"],
      [/\bSaude\b/g, "Saúde"],
      [/\bAtualizacao\b/g, "Atualização"],
      [/\batualizacao\b/g, "atualização"],
      [/\bUltima\b/g, "Última"],
      [/\bultima\b/g, "última"],
      [/\bParticipacao\b/g, "Participação"],
      [/\bCartao\b/g, "Cartão"],
      [/\bConcluida\b/g, "Concluída"],
      [/\bpermissoes\b/g, "permissões"],
      [/\bPermissoes\b/g, "Permissões"],
      [/\bsaidas\b/g, "saídas"],
      [/\bSaidas\b/g, "Saídas"],
      [/\bNiveis\b/g, "Níveis"],
      [/\bsensiveis\b/g, "sensíveis"],
      [/\bSensiveis\b/g, "Sensíveis"],
      [/\bExportacoes\b/g, "Exportações"],
      [/\bseguranca\b/g, "segurança"],
      [/\bSeguranca\b/g, "Segurança"],
      [/\bdiario\b/g, "diário"],
      [/\bCartao\b/g, "Cartão"]
    ];
    replacements.forEach(([pattern, replacement]) => {
      text = text.replace(pattern, replacement);
    });
    return text;
  }

  function esc(value) {
    return normalizeText(value).replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }

  function escAttr(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }
})();
