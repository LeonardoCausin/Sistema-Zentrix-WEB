(function () {
  const root = document.documentElement;
  const body = document.body;
  const themeButton = document.getElementById("themeButton");
  const menuButton = document.getElementById("menuButton");
  const closeSidebarButton = document.getElementById("closeSidebarButton");
  const sidebarBackdrop = document.getElementById("sidebarBackdrop");
  const storedTheme = localStorage.getItem("zentrix-theme");
  const session = localStorage.getItem("zentrix-session");

  if (body.classList.contains("is-authenticated") && !session) {
    const loginPath = location.pathname.includes("/pages/") ? "../index.html" : "index.html";
    window.location.replace(loginPath);
    return;
  }

  if (storedTheme === "dark" || storedTheme === "light") {
    root.dataset.theme = storedTheme;
  }

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
    link.addEventListener("click", () => localStorage.removeItem("zentrix-session"));
  });

  window.zentrixApi = async function zentrixApi(path, options) {
    const storedSession = JSON.parse(localStorage.getItem("zentrix-session") || "null");
    const apiBase = localStorage.getItem("zentrix-api-base") || "http://localhost:8080/api";
    const response = await fetch(apiBase + path, {
      ...(options || {}),
      headers: {
        "Content-Type": "application/json",
        Authorization: storedSession ? "Bearer " + storedSession.token : "",
        ...((options && options.headers) || {})
      }
    });
    if (response.status === 401) {
      localStorage.removeItem("zentrix-session");
      window.location.replace(location.pathname.includes("/pages/") ? "../index.html" : "index.html");
      throw new Error("Sessao expirada");
    }
    if (!response.ok) {
      throw new Error("Falha ao carregar dados da API");
    }
    return response.json();
  };

  const viewHost = document.querySelector(".view-host");
  if (body.classList.contains("is-authenticated") && viewHost) {
    initChromeSkeleton();
    loadPageData();
  }

  async function loadPageData() {
    const page = location.pathname.split("/").pop().replace(".html", "");
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
      configuracoes: renderSettings
    };
    const loader = loaders[page];
    const chromePromise = refreshChrome();
    if (!loader) {
      await chromePromise;
      body.classList.add("app-data-ready");
      return;
    }
    viewHost.innerHTML = '<section class="panel"><strong>Carregando dados...</strong></section>';
    try {
      await loader();
      await chromePromise;
    } catch (error) {
      await chromePromise;
      renderError(error.message || "Nao foi possivel carregar os dados.");
    } finally {
      body.classList.add("app-data-ready");
    }
  }

  async function renderDashboard() {
    const data = await window.zentrixApi("/dashboard");
    renderShell("Visao geral", "Dados sincronizados do Zentrix PDV.", `
      <div class="grid metrics-grid">
        ${(data.metrics || []).map((item) => `
          <article class="metric-card">
            <div class="metric-top"><span class="metric-label">${esc(item.label)}</span><span class="tag ${tone(item.tone)}">${esc(item.trend || "Atual")}</span></div>
            <strong class="metric-value">${esc(item.value)}</strong>
            <span class="metric-note">Fonte: banco web sincronizado.</span>
          </article>
        `).join("")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel">
          <div class="panel-title"><div><h3>Formas de pagamento</h3><span>Hoje</span></div></div>
          <div class="stack-list">${paymentsHtml(data.payments || [])}</div>
        </section>
        <section class="panel">
          <div class="panel-title"><div><h3>Sincronizacao</h3><span>PDV para Web</span></div></div>
          <div class="stack-list">
            <div class="list-item"><span class="list-icon">OK</span><div><span class="list-title">Ultima sincronizacao</span><span class="list-subtitle">${esc(data.lastSync || "Aguardando envio")}</span></div><strong>${esc(String(data.syncProgress || 0))}%</strong></div>
          </div>
        </section>
      </div>
    `);
  }

  async function renderSales() {
    const rows = await window.zentrixApi("/sales");
    renderTablePage("Relatorio de vendas", "Vendas recebidas do PDV.", ["Codigo", "Horario", "Operador", "Pagamento", "Status", "Total"], rows, (row) => [
      row.code, row.time, row.operator, row.payment, tag(row.status), row.total
    ]);
  }

  async function renderFinance() {
    const data = await window.zentrixApi("/finance");
    renderShell("Financeiro", "Resumo financeiro vindo das vendas sincronizadas.", `
      <div class="grid metrics-grid">
        ${metricCard("Faturamento hoje", data.todayTotal)}
        ${metricCard("Faturamento do mes", data.monthTotal)}
        ${metricCard("Vendas pagas", data.paidSales)}
        ${metricCard("Canceladas", data.cancelledSales)}
      </div>
      <section class="panel" style="margin-top: 16px"><div class="panel-title"><div><h3>Formas de pagamento</h3><span>Hoje</span></div></div><div class="stack-list">${paymentsHtml(data.payments || [])}</div></section>
    `);
  }

  async function renderCash() {
    const rows = await window.zentrixApi("/cash-sessions");
    renderTablePage("Caixa", "Sessoes de caixa sincronizadas.", ["Codigo", "Operador", "Abertura", "Fechamento", "Status", "Valor inicial"], rows, (row) => [
      row.code, row.operator, row.openedAt, row.closedAt, tag(row.status), row.expected
    ]);
  }

  async function renderProducts() {
    const rows = await window.zentrixApi("/products");
    renderShell("Produtos", "Cadastro sincronizado do PDV.", `
      <div class="entity-grid">
        ${rows.map((row) => `
          <article class="entity-card">
            <div class="entity-head"><strong>${esc(row.name)}</strong>${tag(row.status)}</div>
            <div class="entity-meta">
              <div><span>Codigo</span><strong>${esc(row.code)}</strong></div>
              <div><span>Unidade</span><strong>${esc(row.category)}</strong></div>
              <div><span>Preco</span><strong>${esc(row.price)}</strong></div>
              <div><span>Estoque</span><strong>${esc(String(row.currentStock))}</strong></div>
            </div>
          </article>
        `).join("") || emptyState("Nenhum produto sincronizado.")}
      </div>
    `);
  }

  async function renderStock() {
    const rows = await window.zentrixApi("/stock/alerts");
    renderProductsList("Estoque", "Produtos abaixo do estoque minimo.", rows);
  }

  async function renderClients() {
    const rows = await window.zentrixApi("/clients");
    renderTablePage("Clientes", "Clientes sincronizados do PDV.", ["Nome", "CPF/CNPJ", "Telefone", "E-mail", "Endereco", "Cadastro"], rows, (row) => [
      row.name, row.cpfCnpj || "-", row.phone || "-", row.email || "-", row.address || "-", row.createdAt || "-"
    ]);
  }

  async function renderEmployees() {
    const rows = await window.zentrixApi("/employees");
    renderTablePage("Funcionarios", "Usuarios sincronizados do PDV.", ["Nome", "Usuario", "Perfil", "Status"], rows, (row) => [
      row.name, row.username, row.role, tag(row.active)
    ]);
  }

  async function renderAudit() {
    const rows = await window.zentrixApi("/audit");
    renderTablePage("Auditoria", "Eventos sincronizados do PDV.", ["Acao", "Horario", "Usuario", "Descricao", "Detalhes"], rows, (row) => [
      row.action, row.time, row.user || "-", row.description, row.value || "-"
    ]);
  }

  async function renderReports() {
    const data = await window.zentrixApi("/reports");
    renderShell("Relatorios", "Resumo das entidades sincronizadas.", `
      <div class="grid metrics-grid">
        ${metricCard("Vendas", data.sales)}
        ${metricCard("Produtos", data.products)}
        ${metricCard("Clientes", data.clients)}
        ${metricCard("Caixas", data.cashSessions)}
        ${metricCard("Alertas de estoque", data.stockAlerts)}
        ${metricCard("Auditoria", data.auditEvents)}
      </div>
      <section class="panel" style="margin-top: 16px"><strong>Ultima sincronizacao</strong><p>${esc(data.lastSync || "Aguardando envio")}</p></section>
    `);
  }

  async function renderBackups() {
    const rows = await window.zentrixApi("/backups");
    renderTablePage("Backups e sincronizacoes", "Historico real de lotes recebidos.", ["Data", "Origem", "Registros", "Status"], rows, (row) => [
      row.date, row.origin, row.size, tag(row.status)
    ]);
  }

  async function renderSettings() {
    const data = await window.zentrixApi("/settings");
    renderShell("Configuracoes", "Estado tecnico do backend web.", `
      <section class="panel"><div class="stack-list">
        <div class="list-item"><span class="list-icon">DB</span><div><span class="list-title">Banco</span><span class="list-subtitle">${esc(data.database)}</span></div><strong>Ativo</strong></div>
        <div class="list-item"><span class="list-icon">API</span><div><span class="list-title">Servico</span><span class="list-subtitle">${esc(data.api)}</span></div><strong>Online</strong></div>
        <div class="list-item"><span class="list-icon">PDV</span><div><span class="list-title">Origem da sincronizacao</span><span class="list-subtitle">${esc(data.sourceId || "Aguardando primeira sincronizacao")}</span></div><strong>${esc(data.lastSync || "-")}</strong></div>
        <div class="list-item"><span class="list-icon">US</span><div><span class="list-title">Usuarios</span><span class="list-subtitle">${esc(String(data.users))}</span></div><strong>${esc(String(data.products))} produtos</strong></div>
      </div></section>
    `);
  }

  function renderProductsList(title, subtitle, rows) {
    renderShell(title, subtitle, `
      <div class="entity-grid">
        ${rows.map((row) => `
          <article class="entity-card">
            <div class="entity-head"><strong>${esc(row.name)}</strong>${tag(row.status)}</div>
            <div class="entity-meta">
              <div><span>Codigo</span><strong>${esc(row.code)}</strong></div>
              <div><span>Estoque</span><strong>${esc(String(row.currentStock))}</strong></div>
              <div><span>Minimo</span><strong>${esc(String(row.minimumStock))}</strong></div>
              <div><span>Preco</span><strong>${esc(row.price)}</strong></div>
            </div>
          </article>
        `).join("") || emptyState("Nenhum alerta de estoque.")}
      </div>
    `);
  }

  function renderTablePage(title, subtitle, headers, rows, mapper) {
    renderShell(title, subtitle, `
      <section class="table-panel">
        <div class="table-title"><h3>${esc(title)}</h3><span>${rows.length} registros</span></div>
        <div class="table-wrap"><table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join("")}</tr></thead><tbody>
          ${rows.map((row) => `<tr>${mapper(row).map((value) => `<td>${isTrustedTag(value) ? value : esc(value)}</td>`).join("")}</tr>`).join("") || `<tr><td colspan="${headers.length}">${emptyState("Nenhum registro sincronizado.")}</td></tr>`}
        </tbody></table></div>
      </section>
    `);
  }

  function renderShell(title, subtitle, content) {
    viewHost.innerHTML = `<div class="page-header"><div><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div></div>${content}`;
  }

  function renderError(message) {
    viewHost.innerHTML = `<section class="panel"><strong>Falha ao carregar dados</strong><p>${esc(message)}</p></section>`;
  }

  function metricCard(label, value) {
    return `<article class="metric-card"><div class="metric-top"><span class="metric-label">${esc(label)}</span><span class="tag info">API</span></div><strong class="metric-value">${esc(value)}</strong><span class="metric-note">Fonte: Zentrix Web API.</span></article>`;
  }

  function paymentsHtml(rows) {
    return rows.map((row) => `<div class="payment-row"><strong>${esc(row.name)}</strong><div class="progress-track"><span style="width: ${percentWidth(row.percent)}%"></span></div><span>${esc(row.total)}</span></div>`).join("") || emptyState("Nenhuma forma de pagamento sincronizada.");
  }

  function tag(value) {
    const text = String(value || "-");
    return `<span class="tag ${tagTone(text)}">${esc(text)}</span>`;
  }

  function tagTone(value) {
    const text = value.toLowerCase();
    if (text.includes("cancel") || text.includes("baixo") || text.includes("sem estoque") || text.includes("failed")) return "danger";
    if (text.includes("aberto") || text.includes("pendente")) return "warning";
    return "success";
  }

  function tone(value) {
    return value || "info";
  }

  function emptyState(message) {
    return `<div class="empty-state">${esc(message)}</div>`;
  }

  function initChromeSkeleton() {
    setText(".status-pill", "Conectando API");
    const statusPill = document.querySelector(".status-pill");
    if (statusPill) statusPill.className = "status-pill warning";
    setText(".sidebar-sync strong", "Sincronizacao");
    setText(".sidebar-sync span", "Carregando estado real");
    const progress = document.querySelector(".sidebar-sync .progress-track span");
    if (progress) progress.style.width = "0%";
    setText(".sidebar-sync .button", "Historico");
    setText(".window-title span:last-child", "Zentrix Web");
    const activeStore = document.querySelector(".topbar-tools .select-field");
    if (activeStore) activeStore.innerHTML = "<option>Zentrix Web</option>";
  }

  async function refreshChrome() {
    try {
      const data = await window.zentrixApi("/dashboard");
      updateChrome(data);
    } catch (error) {
      updateChrome(null);
    }
  }

  function updateChrome(data) {
    const companyName = data && data.company && data.company.name ? String(data.company.name) : "Zentrix Web";
    const lastSync = data && data.lastSync ? String(data.lastSync) : "";
    const progress = Math.max(0, Math.min(100, Number((data && data.syncProgress) || 0)));
    const synced = progress === 100 && Boolean(lastSync);
    const title = companyName === "Zentrix Web" ? "Zentrix Web" : "Zentrix Web - " + companyName;

    setText(".window-title span:last-child", title);
    const statusPill = document.querySelector(".status-pill");
    if (statusPill) {
      statusPill.className = "status-pill " + (synced ? "success" : "warning");
      statusPill.textContent = synced ? "Sincronizado" : "Aguardando sync";
    }

    const activeStore = document.querySelector(".topbar-tools .select-field");
    if (activeStore) activeStore.innerHTML = `<option>${esc(companyName)}</option>`;

    setText(".sidebar-sync strong", "Sincronizacao");
    setText(".sidebar-sync span", lastSync ? "Ultima: " + lastSync : "Aguardando primeira sincronizacao");
    setText(".sidebar-sync .button", "Historico");
    const progressBar = document.querySelector(".sidebar-sync .progress-track span");
    if (progressBar) progressBar.style.width = progress + "%";
  }

  function setText(selector, value) {
    const element = document.querySelector(selector);
    if (element) element.textContent = value;
  }

  function percentWidth(value) {
    const percent = Number(value);
    if (!Number.isFinite(percent)) return 0;
    return Math.max(0, Math.min(100, percent));
  }

  function isTrustedTag(value) {
    return typeof value === "string" && /^<span class="tag (success|warning|danger|info)">[^<>]*<\/span>$/.test(value);
  }

  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }
})();
