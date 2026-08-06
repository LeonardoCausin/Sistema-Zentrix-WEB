(function () {
  "use strict";

  const SESSION_KEY = "zentrix-admin-session";
  const API_BASE = (window.ZentrixAdminConfig && window.ZentrixAdminConfig.apiBase) || "/api";

  const state = {
    view: "dashboard",
    session: readSession(),
    overview: null,
    clients: [],
    plans: [],
    support: null,
    viewRequestId: 0
  };

  const els = {
    loginPanel: document.getElementById("loginPanel"),
    loginForm: document.getElementById("loginForm"),
    appView: document.getElementById("appView"),
    viewHost: document.getElementById("viewHost"),
    toast: document.getElementById("toast"),
    pageTitle: document.getElementById("pageTitle"),
    pageSubtitle: document.getElementById("pageSubtitle"),
    sessionName: document.getElementById("sessionName"),
    logoutButton: document.getElementById("logoutButton"),
    refreshButton: document.getElementById("refreshButton"),
    storeSelect: document.getElementById("storeSelect"),
    modal: document.getElementById("modal"),
    modalTitle: document.getElementById("modalTitle"),
    modalBody: document.getElementById("modalBody"),
    modalCloseButton: document.getElementById("modalCloseButton")
  };

  document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.view));
  });
  els.loginForm.addEventListener("submit", login);
  els.logoutButton.addEventListener("click", logout);
  els.refreshButton.addEventListener("click", () => loadView(true));
  els.storeSelect.addEventListener("change", () => state.view === "support" && loadView(true));
  els.modalCloseButton.addEventListener("click", closeModal);

  boot();

  async function boot() {
    if (!state.session) {
      showLogin();
      return;
    }
    showApp();
    await initializeAppData();
  }

  async function login(event) {
    event.preventDefault();
    const data = formData(event.currentTarget);
    let session;
    try {
      session = await rawRequest("/auth/login", {
        method: "POST",
        body: JSON.stringify(data)
      });
    } catch (error) {
      toast(error.message, true);
      return;
    }

    state.session = session;
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    showApp();
    await initializeAppData();
    toast("Acesso liberado.");
  }

  async function initializeAppData() {
    try {
      await loadStores();
    } catch (error) {
      els.storeSelect.innerHTML = '<option value="all">Todas as lojas</option>';
    }
    try {
      await loadView(true);
    } catch (error) {
      renderLoadError(error);
      toast(error.message, true);
    }
  }

  function logout() {
    sessionStorage.removeItem(SESSION_KEY);
    state.session = null;
    showLogin();
  }

  function showLogin() {
    els.loginPanel.hidden = false;
    els.appView.hidden = true;
    els.sessionName.textContent = "Desconectado";
  }

  function showApp() {
    els.loginPanel.hidden = true;
    els.appView.hidden = false;
    els.sessionName.textContent = state.session.displayName || state.session.userName || state.session.username || "Admin";
  }

  function setView(view) {
    state.view = view;
    document.querySelectorAll("[data-view]").forEach((button) => {
      button.classList.toggle("active", button.dataset.view === view);
    });
    loadView(false);
  }

  async function loadView(fresh) {
    const requestId = ++state.viewRequestId;
    renderLoading();
    setBusy(true);
    try {
      if (state.view === "dashboard") await loadDashboard(fresh);
      if (state.view === "clients") await loadClients(fresh);
      if (state.view === "subscriptions") await loadSubscriptions(fresh);
      if (state.view === "permissions") await loadPermissions();
      if (state.view === "support") await loadSupport();
    } catch (error) {
      if (requestId === state.viewRequestId) {
        renderLoadError(error);
      }
      toast(error.message, true);
    } finally {
      if (requestId === state.viewRequestId) {
        setBusy(false);
      }
    }
  }

  async function loadStores() {
    try {
      const stores = await api("/stores");
      const options = [{ id: "all", name: "Todas as lojas" }, ...(Array.isArray(stores) ? stores : []).filter((store) => store && store.id !== "all")];
      els.storeSelect.innerHTML = options.map((store) => `<option value="${escAttr(store.id)}">${esc(store.name || store.id)}</option>`).join("");
    } catch (error) {
      els.storeSelect.innerHTML = '<option value="all">Todas as lojas</option>';
    }
  }

  async function loadPlans(fresh) {
    if (fresh || !state.plans.length) {
      state.plans = await api("/zentrix-admin/plans");
    }
    return state.plans;
  }

  async function loadDashboard(fresh) {
    setHeading("Dashboard", "Visao geral da operacao Zentrix.");
    if (fresh || !state.overview) {
      state.overview = await api("/zentrix-admin/overview");
    }
    const data = state.overview;
    state.plans = data.plans || state.plans;
    const alerts = data.expirationAlerts || [];
    els.viewHost.innerHTML = `
      <div class="grid">
        ${metric("Clientes", data.clients, "Empresas cadastradas")}
        ${metric("Ativos", data.activeClients, "Clientes liberados")}
        ${metric("Assinaturas", data.activeSubscriptions, "Planos ativos")}
        ${metric("Vencendo", data.expiringSoon, "Proximos 7 dias")}
      </div>
      <section class="panel">
        <div class="panel-title">
          <div><h2>Avisos de vencimento</h2><span class="muted">Alertas automaticos em 7, 3 e 1 dia antes</span></div>
        </div>
        ${alerts.length ? simpleTable(["Cliente", "Plano", "Vencimento", "Aviso"], alerts, (row) => [
          row.name,
          row.planName,
          date(row.expiresAt),
          `${row.daysLeft} dia(s)`
        ]) : emptyState("Nenhum vencimento em 7, 3 ou 1 dia.")}
      </section>
      <section class="panel">
        <div class="panel-title">
          <div><h2>Planos</h2><span class="muted">Basico, Intermediario e Pro</span></div>
        </div>
        <div class="grid three">
          ${(state.plans || []).map((plan) => `
            <article class="card metric">
              <span>${esc(plan.name)}</span>
              <strong>${money(plan.monthlyStorePrice)}</strong>
              <small class="muted">Por loja/mês - ${esc(plan.description)}</small>
              <small class="muted">Inclui ${esc(plan.includedPdvPerStore)} PDV e ${esc(plan.includedAppGestaoPerStore)} AppGestao por loja</small>
              <small class="muted">PDV adicional: ${money(plan.extraPdvPrice)} por mês</small>
            </article>
          `).join("")}
        </div>
      </section>
      <section class="panel">
        <div class="panel-title">
          <div><h2>Clientes recentes</h2><span class="muted">Ultimas empresas cadastradas</span></div>
          <button class="primary" type="button" data-action="new-client">Novo cliente</button>
        </div>
        ${clientsTable(data.recentClients || [])}
      </section>
    `;
    wireCommonActions();
  }

  async function loadClients(fresh) {
    setHeading("Clientes", "Cadastro de empresas, lojas e administradores.");
    if (fresh || !state.clients.length) {
      state.clients = await api("/zentrix-admin/clients?limit=150");
    }
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="panel-title">
          <div><h2>Clientes</h2><span class="muted">${state.clients.length} registros</span></div>
          <div class="toolbar">
            <input id="clientSearch" placeholder="Buscar cliente" />
            <button class="primary" type="button" data-action="new-client">Novo cliente</button>
          </div>
        </div>
        <div id="clientsTable">${clientsTable(state.clients)}</div>
      </section>
    `;
    document.getElementById("clientSearch").addEventListener("input", (event) => {
      const q = event.target.value.trim().toLowerCase();
      const rows = state.clients.filter((row) => [row.name, row.document, row.tenantId].some((value) => String(value || "").toLowerCase().includes(q)));
      document.getElementById("clientsTable").innerHTML = clientsTable(rows);
      wireCommonActions();
    });
    wireCommonActions();
  }

  async function loadSubscriptions(fresh) {
    setHeading("Assinaturas", "Planos, vencimentos, bloqueios e renovacoes.");
    if (fresh || !state.clients.length) {
      state.clients = await api("/zentrix-admin/clients?limit=150");
    }
    await loadPlans(fresh).catch(() => []);
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="panel-title">
          <div><h2>Assinaturas</h2><span class="muted">Controle comercial dos clientes</span></div>
          <div class="toolbar">
            <input id="subscriptionSearch" placeholder="Buscar cliente" />
            <select id="subscriptionFilter" aria-label="Filtrar status">
              <option value="all">Todos</option>
              <option value="active">Ativos</option>
              <option value="attention">Vencendo</option>
              <option value="blocked">Bloqueados</option>
              <option value="expired">Vencidos</option>
            </select>
          </div>
        </div>
        <div class="grid two compact-grid">
          ${metric("Bloqueados", state.clients.filter((row) => restrictedStatus(row.status) || restrictedStatus(row.licenseStatus)).length, "Clientes sem acesso")}
          ${metric("Vencendo", state.clients.filter((row) => daysUntil(row.expiresAt) >= 0 && daysUntil(row.expiresAt) <= 7).length, "Proximos 7 dias")}
        </div>
        <div id="subscriptionsTable">${subscriptionsTable(state.clients)}</div>
      </section>
    `;
    document.getElementById("subscriptionSearch").addEventListener("input", filterSubscriptions);
    document.getElementById("subscriptionFilter").addEventListener("change", filterSubscriptions);
    wireCommonActions();
  }

  function filterSubscriptions() {
    const q = document.getElementById("subscriptionSearch").value.trim().toLowerCase();
    const filter = document.getElementById("subscriptionFilter").value;
    const rows = state.clients.filter((row) => {
      const searchable = [row.name, row.document, row.tenantId, row.planName].some((value) => String(value || "").toLowerCase().includes(q));
      if (!searchable) return false;
      const status = String(row.licenseStatus || row.status || "").toUpperCase();
      const tenantStatus = String(row.status || "").toUpperCase();
      const days = daysUntil(row.expiresAt);
      if (filter === "active") return tenantStatus === "ACTIVE" && status === "ACTIVE";
      if (filter === "attention") return days >= 0 && days <= 7;
      if (filter === "blocked") return restrictedStatus(tenantStatus) || restrictedStatus(status);
      if (filter === "expired") return status === "EXPIRED" || days < 0;
      return true;
    });
    document.getElementById("subscriptionsTable").innerHTML = subscriptionsTable(rows);
    wireCommonActions();
  }

  function subscriptionsTable(rows) {
    return `<table>
      <thead><tr><th>Cliente</th><th>Plano</th><th>Status</th><th>Vencimento</th><th>Cobranca</th><th>Acoes</th></tr></thead>
      <tbody>${rows.map((row) => `
        <tr>
          <td><strong>${esc(row.name)}</strong><br><span class="muted">${esc(formatCpfCnpj(row.document) || "CPF/CNPJ nao cadastrado")}</span>${row.blockReason ? `<br><span class="danger-text">${esc(row.blockReason)}</span>` : ""}</td>
          <td>${esc(row.planName || "BASICO")}<br><span class="muted">${planAccessLabel(row.billing)}</span></td>
          <td>${tag(row.status)} ${tag(row.licenseStatus || "-")}</td>
          <td>${date(row.expiresAt)}<br><span class="muted">${expirationLabel(row.expiresAt)}</span></td>
          <td><strong>${money(row.monthlyTotal)}</strong><br><span class="muted">${billingLine(row.billing)}</span></td>
          <td class="actions">
            <button type="button" data-action="renew-license" data-tenant="${escAttr(row.tenantId)}">Renovar</button>
            <button type="button" data-action="billing-profile" data-tenant="${escAttr(row.tenantId)}" data-name="${escAttr(row.name)}" data-document="${escAttr(row.document || "")}">Editar CPF/CNPJ</button>
            <button class="danger" type="button" data-action="block-client" data-tenant="${escAttr(row.tenantId)}">Bloquear</button>
            <button type="button" data-action="activate-client" data-tenant="${escAttr(row.tenantId)}">Liberar</button>
            <button type="button" data-action="test-access" data-tenant="${escAttr(row.tenantId)}">Testar</button>
          </td>
        </tr>
      `).join("") || emptyRow(6)}</tbody>
    </table>`;
  }

  async function loadSupport() {
    setHeading("Suporte", "Ferramentas locais para manutencao controlada.");
    const store = encodeURIComponent(els.storeSelect.value || "all");
    state.support = await api("/local-admin/overview?store=" + store);
    const data = state.support;
    els.viewHost.innerHTML = `
      <div class="grid">
        ${metric("Caixas abertos", data.cashOpenCount, "Registros pendentes")}
        ${metric("Status incorreto", data.cashStatusIssueCount, "Fechado marcado como OPEN")}
        ${metric("Falhas sync", data.syncFailureCount, "Ultimos 7 dias")}
        ${metric("Backups erro", data.backupErrorCount, "Historico com erro")}
      </div>
      <section class="panel">
        <div class="panel-title">
          <div><h2>Manutencao rapida</h2><span class="muted">Acoes exigem motivo e ficam auditadas</span></div>
        </div>
        <div class="actions">
          <button type="button" data-action="normalize-cash">Normalizar status de caixas</button>
          <button type="button" data-action="clear-sync">Limpar falhas de sync</button>
          <button type="button" data-action="clear-backups">Limpar backups com erro</button>
          <button type="button" data-action="clear-cache">Limpar cache</button>
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><div><h2>Caixas para revisar</h2><span class="muted">${(data.cashSessions || []).length} registros</span></div></div>
        ${cashTable(data.cashSessions || [])}
      </section>
      <section class="panel">
        <div class="panel-title"><div><h2>Falhas recentes</h2><span class="muted">${(data.syncFailures || []).length} registros</span></div></div>
        ${simpleTable(["ID", "Loja", "Status", "Data", "Mensagem"], data.syncFailures || [], (row) => [row.id, row.storeId, tag(row.status), date(row.receivedAt), row.message || "-"])}
      </section>
    `;
    wireCommonActions();
  }

  function clientsTable(rows) {
    return `<table>
      <thead><tr><th>Cliente</th><th>Status</th><th>Plano</th><th>Vencimento</th><th>Cobranca</th><th>Acoes</th></tr></thead>
      <tbody>${rows.map((row) => `
        <tr>
          <td><strong>${esc(row.name)}</strong><br><span class="muted">${esc(formatCpfCnpj(row.document) || "CPF/CNPJ nao cadastrado")}</span></td>
          <td>${tag(row.status)}</td>
          <td>${esc(row.planName || "BASICO")}<br>${tag(row.licenseStatus || "-")}</td>
          <td>${date(row.expiresAt)}</td>
          <td>${money(row.monthlyTotal)}<br><span class="muted">${billingLine(row.billing)}</span></td>
          <td class="actions">
            <button type="button" data-action="client-detail" data-tenant="${escAttr(row.tenantId)}">Abrir</button>
            <button type="button" data-action="billing-profile" data-tenant="${escAttr(row.tenantId)}" data-name="${escAttr(row.name)}" data-document="${escAttr(row.document || "")}">Editar CPF/CNPJ</button>
            <button type="button" data-action="activation-code" data-tenant="${escAttr(row.tenantId)}">Codigo PDV</button>
            <button type="button" data-action="test-access" data-tenant="${escAttr(row.tenantId)}">Testar</button>
          </td>
        </tr>
      `).join("") || emptyRow(6)}</tbody>
    </table>`;
  }

  function cashTable(rows) {
    return simpleTable(["ID", "Loja", "Operador", "Abertura", "Fechamento", "Status", "Acoes"], rows, (row) => [
      row.id,
      row.storeId,
      row.operator,
      date(row.openedAt),
      date(row.closedAt),
      tag(row.status || (row.open ? "OPEN" : "-")),
      `<button type="button" data-action="close-cash" data-id="${escAttr(row.id)}" data-store="${escAttr(row.storeId)}">Fechar</button>
       <button class="danger" type="button" data-action="delete-cash" data-id="${escAttr(row.id)}" data-store="${escAttr(row.storeId)}">Apagar</button>`
    ]);
  }

  function simpleTable(headers, rows, mapper) {
    return `<table><thead><tr>${headers.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead><tbody>
      ${rows.map((row) => `<tr>${mapper(row).map((value) => `<td>${value && String(value).startsWith("<") ? value : esc(value)}</td>`).join("")}</tr>`).join("") || emptyRow(headers.length)}
    </tbody></table>`;
  }

  function wireCommonActions() {
    document.querySelectorAll("[data-action]").forEach((button) => {
      button.onclick = async () => {
        if (button.dataset.busy === "true") return;
        button.dataset.busy = "true";
        button.disabled = true;
        try {
          await handleAction(button);
        } catch (error) {
          toast(error.message, true);
        } finally {
          button.dataset.busy = "false";
          button.disabled = false;
        }
      };
    });
  }

  async function handleAction(button) {
    const action = button.dataset.action;
    if (action === "new-client") return showClientForm();
    if (action === "client-detail") return showClientDetail(button.dataset.tenant);
    if (action === "billing-profile") return showBillingProfileForm(button.dataset.tenant, button.dataset.name, button.dataset.document);
    if (action === "renew-license") return showLicenseForm(button.dataset.tenant);
    if (action === "activation-code") return showActivationForm(button.dataset.tenant);
    if (action === "block-client") return showStatusForm(button.dataset.tenant, "BLOCKED");
    if (action === "activate-client") return showStatusForm(button.dataset.tenant, "ACTIVE");
    if (action === "store-status") return showStoreStatusForm(button.dataset.tenant, button.dataset.store, button.dataset.status);
    if (action === "test-access") return testClientAccess(button.dataset.tenant);
    if (action === "normalize-cash") return reasonAction("Normalizar caixas", "/local-admin/cash/normalize-statuses?store=" + encodeURIComponent(els.storeSelect.value), "POST");
    if (action === "clear-sync") return reasonAction("Limpar falhas de sincronizacao", "/local-admin/sync/clear-failures?store=" + encodeURIComponent(els.storeSelect.value), "POST", { days: 7 });
    if (action === "clear-backups") return reasonAction("Limpar backups com erro", "/local-admin/backups/clear-errors?store=" + encodeURIComponent(els.storeSelect.value), "POST");
    if (action === "clear-cache") return reasonAction("Limpar cache", "/local-admin/cache/clear", "POST");
    if (action === "close-cash") return showCloseCashForm(button.dataset.id, button.dataset.store);
    if (action === "delete-cash") return deleteCash(button.dataset.id, button.dataset.store);
  }

  async function loadPermissions() {
    setHeading("Permissoes", "Acessos separados para dono, financeiro e suporte.");
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="panel-title">
          <div><h2>Perfis do Zentrix Admin</h2><span class="muted">Use estas chaves no permissions_json do usuario</span></div>
        </div>
        <div class="grid three">
          ${permissionCard("Dono", "zentrix.dono", "Acesso total ao admin local, clientes, assinaturas, saude, suporte e testes.")}
          ${permissionCard("Financeiro", "zentrix.financeiro", "Cria clientes, renova assinaturas, bloqueia e libera lojas.")}
          ${permissionCard("Suporte", "zentrix.suporte", "Consulta saude, historico, teste de acesso e gera codigo PDV.")}
        </div>
      </section>
    `;
  }

  async function showClientForm() {
    await loadPlans(false).catch(() => []);
    openModal("Novo cliente", `
      <form id="clientForm" class="form-grid">
        ${field("companyName", "Empresa", "text", true)}
        <label>CPF ou CNPJ<input name="document" type="text" inputmode="numeric" maxlength="18" placeholder="000.000.000-00" required /></label>
        ${field("storeName", "Loja inicial")}
        ${field("sourceId", "Nome/origem do PDV")}
        ${field("adminUsername", "Usuario admin", "text", true)}
        ${field("adminDisplayName", "Nome do admin")}
        ${field("adminPassword", "Senha inicial", "password", true)}
        ${planSelect("planName", "Plano", "BASICO")}
        ${field("expiresAt", "Vencimento", "date")}
        ${field("maxStores", "Lojas base cobradas", "number", false, "1")}
        ${field("maxDevices", "Apps incluidos por loja", "number", false, "1")}
        <label class="full">Motivo<textarea name="reason">Cadastro de novo cliente.</textarea></label>
        <button class="primary full" type="submit">Criar cliente</button>
      </form>
    `);
    document.getElementById("clientForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        await api("/zentrix-admin/clients", { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
        closeModal();
        state.clients = [];
        state.overview = null;
        await loadView(true);
        toast("Cliente criado.");
      } catch (error) {
        toast(error.message, true);
      }
    };
    wireCpfCnpjInput("clientForm");
    wirePlanDefaults("clientForm");
  }

  async function showClientDetail(tenantId) {
    const [detail, history, health] = await Promise.all([
      api("/zentrix-admin/clients/" + encodeURIComponent(tenantId)),
      api("/zentrix-admin/clients/" + encodeURIComponent(tenantId) + "/history").catch(() => []),
      api("/zentrix-admin/clients/" + encodeURIComponent(tenantId) + "/health").catch(() => ({ stores: [] }))
    ]);
    openModal("Cliente", `
      <div class="grid two">
        ${metric("Empresa", detail.name, detail.tenantId)}
        ${metric("Status", detail.status, detail.document || "CPF/CNPJ nao cadastrado")}
      </div>
      ${detail.blockReason ? `<section class="notice danger-note"><strong>Cliente bloqueado</strong><span>${esc(detail.blockReason)}</span></section>` : ""}
      <section class="panel">
        <div class="panel-title">
          <div><h3>Resumo de cobranca</h3><span class="muted">Por loja ativa e aplicativos instalados</span></div>
          <div class="actions">
            <strong>${money(detail.billing && detail.billing.monthlyTotal)}</strong>
            <button type="button" data-action="billing-profile" data-tenant="${escAttr(tenantId)}" data-name="${escAttr(detail.name)}" data-document="${escAttr(detail.document || "")}">Dados de cobranca</button>
          </div>
        </div>
        ${billingDetails(detail.billing)}
      </section>
      <section class="panel">
        <div class="panel-title">
          <div><h3>Saude por loja</h3><span class="muted">Ultimo sync, backup e PDVs ativos</span></div>
          <button type="button" data-action="test-access" data-tenant="${escAttr(tenantId)}">Testar acesso</button>
        </div>
        ${simpleTable(["Loja", "Sync", "Backup", "PDVs"], health.stores || [], (row) => [
          `<strong>${esc(row.name || row.storeId)}</strong><br><span class="muted">${esc(row.storeId)}</span>`,
          `${tag(row.lastSyncStatus || "-")}<br><span class="muted">${date(row.lastSyncAt)}</span>`,
          `${tag(row.lastBackupStatus || "-")}<br><span class="muted">${date(row.lastBackupAt)}</span>`,
          `<strong>${esc(row.activeDevices || 0)}</strong><br><span class="muted">${esc(date(row.lastDeviceSeenAt))}</span>`
        ])}
      </section>
      <section class="panel">
        <h3>Assinaturas</h3>
        ${simpleTable(["Plano", "Status", "Inicio", "Fim", "Limites"], detail.licenses || [], (row) => [row.planName, tag(row.status), date(row.startsAt), date(row.expiresAt), `${row.maxStores} lojas / ${row.maxDevices} disp.`])}
      </section>
      <section class="panel">
        <h3>Historico visual</h3>
        ${timeline(history || [])}
      </section>
      <section class="panel">
        <div class="panel-title">
          <div><h3>Lojas</h3><span class="muted">Altere o status individual de cada loja</span></div>
        </div>
        ${simpleTable(["ID", "Nome", "Origem", "Status", "Acao"], detail.stores || [], (row) => [
          row.id,
          row.name,
          row.sourceId,
          tag(row.status),
          `<button type="button" data-action="store-status" data-tenant="${escAttr(tenantId)}" data-store="${escAttr(row.id)}" data-status="${escAttr(row.status || "ACTIVE")}">Alterar status</button>`
        ])}
      </section>
      <section class="panel">
        <h3>Codigos de ativacao</h3>
        ${simpleTable(["Codigo", "Loja", "Status", "Expira", "Usado"], detail.activationCodes || [], (row) => [row.code, row.storeName, tag(row.status), date(row.expiresAt), date(row.usedAt)])}
      </section>
    `);
    wireCommonActions();
  }

  function showBillingProfileForm(tenantId, name, documentValue) {
    openModal("Dados de cobranca", `
      <form id="billingProfileForm" class="form-grid">
        ${field("name", "Razao social ou nome", "text", true, name || "")}
        ${field("document", "CPF ou CNPJ", "text", true, documentValue || "")}
        <label class="full">Motivo<textarea name="reason">Atualizacao dos dados usados na cobranca Asaas.</textarea></label>
        <button class="primary full" type="submit">Salvar dados de cobranca</button>
      </form>
    `);
    document.getElementById("billingProfileForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/billing-profile`, {
          method: "PUT",
          body: JSON.stringify(formData(event.currentTarget))
        });
        state.clients = [];
        closeModal();
        await loadView(true);
        toast("Dados de cobranca atualizados.");
      } catch (error) {
        toast(error.message, true);
      }
    };
    wireCpfCnpjInput("billingProfileForm");
  }

  async function showLicenseForm(tenantId) {
    await loadPlans(false).catch(() => []);
    openModal("Renovar assinatura", `
      <form id="licenseForm" class="form-grid">
        ${planSelect("planName", "Plano", "BASICO")}
        <label>Status
          <select name="status">
            <option value="ACTIVE">ACTIVE</option>
            <option value="BLOCKED">BLOCKED</option>
            <option value="SUSPENDED">SUSPENDED</option>
            <option value="EXPIRED">EXPIRED</option>
          </select>
        </label>
        ${field("startsAt", "Inicio", "date")}
        ${field("expiresAt", "Vencimento", "date")}
        ${field("maxStores", "Lojas base cobradas", "number", false, "1")}
        ${field("maxDevices", "Apps incluidos por loja", "number", false, "1")}
        <label class="check-field full">
          <input name="activateClient" type="checkbox" value="true" checked />
          Liberar cliente se a assinatura ficar ACTIVE
        </label>
        <label class="full">Motivo<textarea name="reason">Renovacao de assinatura.</textarea></label>
        <button class="primary full" type="submit">Salvar assinatura</button>
      </form>
    `);
    document.getElementById("licenseForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        const data = formData(event.currentTarget);
        data.activateClient = data.activateClient === "true";
        await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/licenses`, { method: "POST", body: JSON.stringify(data) });
        closeModal();
        state.clients = [];
        state.overview = null;
        await loadView(true);
        toast("Assinatura atualizada.");
      } catch (error) {
        toast(error.message, true);
      }
    };
    wirePlanDefaults("licenseForm");
  }

  function showActivationForm(tenantId) {
    openModal("Codigo de ativacao PDV", `
      <form id="activationForm" class="form-grid">
        ${field("storeName", "Nome da loja", "text", true, "Nova loja")}
        ${field("sourceId", "Origem/PDV")}
        ${field("expiresMinutes", "Validade em minutos", "number", false, "1440")}
        <label class="full">Motivo<textarea name="reason">Ativacao de novo PDV.</textarea></label>
        <button class="primary full" type="submit">Gerar codigo</button>
      </form>
      <div id="activationResult"></div>
    `);
    document.getElementById("activationForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        const result = await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/activation-codes`, { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
        document.getElementById("activationResult").innerHTML = `<section class="panel"><h3>Codigo gerado</h3><strong style="font-size:32px">${esc(result.code)}</strong><p class="muted">Expira em ${date(result.expiresAt)}</p></section>`;
        state.clients = [];
      } catch (error) {
        toast(error.message, true);
      }
    };
  }

  function showStatusForm(tenantId, status) {
    const blocking = restrictedStatus(status);
    openModal(blocking ? "Bloquear cliente" : "Liberar cliente", `
      <form id="statusForm" class="form-grid">
        <section class="notice ${blocking ? "danger-note" : "success-note"} full">
          <strong>${blocking ? "A loja ficara sem acesso ao AppGestao." : "A loja voltara a acessar o AppGestao."}</strong>
          <span>${blocking ? "O motivo informado sera exibido na tela do cliente." : "O motivo anterior de bloqueio sera removido."}</span>
        </section>
        <label>Status
          <select name="status">
            <option value="${escAttr(status)}">${esc(status)}</option>
          </select>
        </label>
        <label class="check-field">
          <input name="updateLicense" type="checkbox" value="true" checked />
          Atualizar tambem a assinatura mais recente
        </label>
        <label class="full">Motivo<textarea name="reason" required>${blocking ? "Bloqueio administrativo por pendencia de assinatura." : "Liberacao administrativa apos regularizacao."}</textarea></label>
        <button class="primary full" type="submit">${blocking ? "Bloquear cliente" : "Liberar cliente"}</button>
      </form>
    `);
    document.getElementById("statusForm").onsubmit = async (event) => {
      event.preventDefault();
      const data = formData(event.currentTarget);
      data.updateLicense = data.updateLicense === "true";
      try {
        await updateClientStatus(tenantId, data);
        closeModal();
      } catch (error) {
        toast(error.message, true);
      }
    };
  }

  async function updateClientStatus(tenantId, data) {
    await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/status`, {
      method: "PUT",
      body: JSON.stringify(data)
    });
    state.clients = [];
    state.overview = null;
    await loadView(true);
    toast("Status atualizado.");
  }

  function showStoreStatusForm(tenantId, storeId, currentStatus) {
    openModal("Status da loja", `
      <form id="storeStatusForm" class="form-grid">
        <section class="notice full">
          <strong>${esc(storeId)}</strong>
          <span>Uma loja inativa, suspensa ou bloqueada deixa de acessar o sistema. O cliente e as outras lojas permanecem como estao.</span>
        </section>
        <label>Status
          <select name="status">
            ${["ACTIVE", "BLOCKED", "SUSPENDED", "INACTIVE"].map((status) => `<option value="${status}" ${status === String(currentStatus || "ACTIVE").toUpperCase() ? "selected" : ""}>${status}</option>`).join("")}
          </select>
        </label>
        <label class="full">Motivo<textarea name="reason">Alteracao administrativa do status da loja.</textarea></label>
        <button class="primary full" type="submit">Salvar status da loja</button>
      </form>
    `);
    document.getElementById("storeStatusForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/stores/${encodeURIComponent(storeId)}/status`, {
          method: "PUT",
          body: JSON.stringify(formData(event.currentTarget))
        });
        state.clients = [];
        state.overview = null;
        await showClientDetail(tenantId);
        toast("Status da loja atualizado.");
      } catch (error) {
        toast(error.message, true);
      }
    };
  }

  async function testClientAccess(tenantId) {
    const result = await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/access-test`, { method: "POST" });
    openModal("Teste de acesso", `
      <section class="notice ${result.allowed ? "success-note" : "danger-note"}">
        <strong>${result.allowed ? "Acesso liberado" : "Acesso bloqueado"}</strong>
        <span>${esc(result.message || "-")}</span>
      </section>
    `);
  }

  function showCloseCashForm(id, store) {
    openModal("Fechar caixa", `
      <form id="closeCashForm" class="form-grid">
        ${field("closingBalance", "Valor informado", "number", true)}
        ${field("expectedBalance", "Valor esperado", "number")}
        ${field("closedAt", "Fechamento", "datetime-local")}
        <label class="full">Motivo<textarea name="reason">Manutencao de caixa pelo painel local.</textarea></label>
        <button class="primary full" type="submit">Fechar caixa</button>
      </form>
    `);
    document.getElementById("closeCashForm").onsubmit = async (event) => {
      event.preventDefault();
      try {
        await api(`/local-admin/cash/${encodeURIComponent(id)}/close?store=${encodeURIComponent(store)}`, { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
        closeModal();
        await loadSupport();
        toast("Caixa fechado.");
      } catch (error) {
        toast(error.message, true);
      }
    };
  }

  async function deleteCash(id, store) {
    const reason = prompt("Motivo para apagar este caixa sem vinculos:");
    if (!reason) return;
    await api(`/local-admin/cash/${encodeURIComponent(id)}?store=${encodeURIComponent(store)}`, { method: "DELETE", body: JSON.stringify({ reason }) });
    await loadSupport();
    toast("Caixa apagado.");
  }

  async function reasonAction(title, path, method, extra) {
    const reason = prompt(title + " - informe o motivo:");
    if (!reason) return;
    await api(path, { method, body: JSON.stringify({ ...(extra || {}), reason }) });
    await loadSupport();
    toast("Acao executada.");
  }

  function setHeading(title, subtitle) {
    els.pageTitle.textContent = title;
    els.pageSubtitle.textContent = subtitle;
  }

  function setBusy(busy) {
    els.refreshButton.disabled = Boolean(busy);
    els.viewHost.setAttribute("aria-busy", String(Boolean(busy)));
  }

  function renderLoading() {
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="skeleton-line wide"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-grid">
          <span></span><span></span><span></span>
        </div>
      </section>
    `;
  }

  function metric(label, value, note) {
    return `<article class="card metric"><span>${esc(label)}</span><strong>${esc(value ?? 0)}</strong><small class="muted">${esc(note || "")}</small></article>`;
  }

  function permissionCard(title, key, description) {
    return `<article class="card metric">
      <span>${esc(title)}</span>
      <strong>${esc(key)}</strong>
      <small class="muted">${esc(description)}</small>
    </article>`;
  }

  function tag(value) {
    const text = String(value || "-").toUpperCase();
    return `<span class="tag ${escAttr(text)}">${esc(text)}</span>`;
  }

  function field(name, label, type, required, value) {
    return `<label>${esc(label)}<input name="${escAttr(name)}" type="${escAttr(type || "text")}" ${required ? "required" : ""} value="${escAttr(value || "")}" /></label>`;
  }

  function wireCpfCnpjInput(formId) {
    const input = document.querySelector(`#${formId} [name="document"]`);
    if (!input) return;
    const update = () => {
      input.value = formatCpfCnpj(input.value);
      const digits = input.value.replace(/\D/g, "");
      input.setCustomValidity(digits.length === 11 || digits.length === 14 ? "" : "Informe um CPF ou CNPJ completo.");
    };
    input.addEventListener("input", update);
    update();
  }

  function formatCpfCnpj(value) {
    const digits = String(value || "").replace(/\D/g, "").slice(0, 14);
    if (digits.length <= 11) {
      return digits
        .replace(/^(\d{3})(\d)/, "$1.$2")
        .replace(/^(\d{3})\.(\d{3})(\d)/, "$1.$2.$3")
        .replace(/\.(\d{3})(\d)/, ".$1-$2");
    }
    return digits
      .replace(/^(\d{2})(\d)/, "$1.$2")
      .replace(/^(\d{2})\.(\d{3})(\d)/, "$1.$2.$3")
      .replace(/\.(\d{3})(\d)/, ".$1/$2")
      .replace(/(\/\d{4})(\d)/, "$1-$2");
  }

  function planSelect(name, label, selected) {
    const plans = state.plans && state.plans.length ? state.plans : [
      { code: "BASICO", name: "Basico", monthlyStorePrice: 99.90, includedPdvPerStore: 1, includedAppGestaoPerStore: 0 },
      { code: "INTERMEDIARIO", name: "Intermediario", monthlyStorePrice: 169.90, includedPdvPerStore: 1, includedAppGestaoPerStore: 1 },
      { code: "PRO", name: "Pro", monthlyStorePrice: 269.90, includedPdvPerStore: 2, includedAppGestaoPerStore: 2 }
    ];
    return `<label>${esc(label)}
      <select name="${escAttr(name)}" data-plan-select>
        ${plans.map((plan) => `<option value="${escAttr(plan.code)}" ${plan.code === selected ? "selected" : ""}>${esc(plan.name)} - ${money(plan.monthlyStorePrice)} por loja</option>`).join("")}
      </select>
    </label>`;
  }

  function wirePlanDefaults(formId) {
    const form = document.getElementById(formId);
    if (!form) return;
    const select = form.querySelector("[data-plan-select]");
    const stores = form.querySelector('[name="maxStores"]');
    const devices = form.querySelector('[name="maxDevices"]');
    const apply = () => {
      const plan = findPlan(select.value);
      if (stores && plan) stores.value = 1;
      if (devices && plan) devices.value = (Number(plan.includedPdvPerStore || 0) + Number(plan.includedAppGestaoPerStore || 0));
    };
    if (select) {
      select.addEventListener("change", apply);
      apply();
    }
  }

  function findPlan(code) {
    return (state.plans || []).find((plan) => plan.code === code)
      || { code: "BASICO", monthlyStorePrice: 99.90, includedPdvPerStore: 1, includedAppGestaoPerStore: 0 };
  }

  function money(value) {
    const number = Number(value || 0);
    return number.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
  }

  function billingLine(billing) {
    if (!billing) return "Sem dados de cobranca";
    return `${billing.activeStores || 0} loja(s), ${billing.pdvApps || 0} PDV(s), ${billing.appGestaoApps || 0} AppGestao`;
  }

  function planAccessLabel(billing) {
    if (!billing) return "Plano sem resumo";
    return billing.appGestaoIncluded ? "PDV + AppGestao" : "Somente PDV";
  }

  function billingDetails(billing) {
    if (!billing) return emptyState("Resumo de cobranca indisponivel.");
    return simpleTable(["Item", "Uso", "Incluido", "Extra", "Valor adicional", "Subtotal"], [
      {
        item: "Lojas",
        use: billing.activeStores,
        included: "-",
        extra: "-",
        extraPrice: "-",
        subtotal: billing.storeSubtotal
      },
      {
        item: "PDVs",
        use: billing.pdvApps,
        included: billing.includedPdvApps,
        extra: billing.extraPdvApps,
        extraPrice: money(billing.extraPdvPrice),
        subtotal: billing.extraPdvSubtotal
      },
      {
        item: "AppGestao",
        use: billing.appGestaoApps,
        included: billing.includedAppGestaoApps,
        extra: billing.appGestaoIncluded ? billing.extraAppGestaoApps : "Upgrade",
        extraPrice: billing.appGestaoIncluded ? money(billing.extraAppGestaoPrice) : "-",
        subtotal: billing.extraAppGestaoSubtotal
      }
    ], (row) => [row.item, row.use, row.included, row.extra, row.extraPrice, money(row.subtotal)]);
  }

  function timeline(rows) {
    if (!rows.length) return emptyState("Ainda nao ha historico para este cliente.");
    return `<ol class="timeline">
      ${rows.map((row) => `<li>
        <strong>${esc(row.title || row.type || "Evento")}</strong>
        <span>${esc(row.description || "-")}</span>
        <small class="muted">${esc(date(row.createdAt))}</small>
      </li>`).join("")}
    </ol>`;
  }

  function emptyState(message) {
    return `<div class="empty-state">${esc(message)}</div>`;
  }

  function openModal(title, body) {
    els.modalTitle.textContent = title;
    els.modalBody.innerHTML = body;
    if (!els.modal.open) els.modal.showModal();
  }

  function closeModal() {
    els.modal.close();
    els.modalBody.innerHTML = "";
  }

  async function api(path, options) {
    const session = state.session || readSession();
    if (!session) throw new Error("Entre novamente.");
    return rawRequest(path, {
      ...(options || {}),
      headers: {
        Authorization: "Bearer " + session.token,
        "Content-Type": "application/json",
        ...((options && options.headers) || {})
      }
    });
  }

  async function rawRequest(path, options) {
    let response;
    try {
      response = await fetch(API_BASE + path, {
        credentials: "include",
        ...(options || {}),
        headers: {
          "Content-Type": "application/json",
          ...((options && options.headers) || {})
        }
      });
    } catch (error) {
      throw new Error("Nao consegui conectar a API do Zentrix. Verifique se o servidor esta online.");
    }
    if (response.status === 401) {
      logout();
      throw new Error("Sessao expirada.");
    }
    if (!response.ok) {
      let message = "Nao foi possivel executar a acao.";
      try {
        const body = await response.json();
        message = body.message || body.error || message;
      } catch (error) {
        message = await response.text().catch(() => message) || message;
      }
      const apiError = new Error(message);
      apiError.status = response.status;
      throw apiError;
    }
    if (response.status === 204) return null;
    const contentType = response.headers.get("Content-Type") || "";
    return contentType.toLowerCase().includes("application/json") ? response.json() : response.text();
  }

  function renderLoadError(error) {
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="panel-title">
          <div><h2>Não foi possível carregar os dados</h2><span class="muted">${esc(error.message)}</span></div>
          <button class="primary" type="button" id="retryLoadButton">Tentar novamente</button>
        </div>
      </section>
    `;
    document.getElementById("retryLoadButton").addEventListener("click", () => loadView(true));
  }

  function readSession() {
    try {
      return JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
    } catch (error) {
      return null;
    }
  }

  function formData(form) {
    return Object.fromEntries(new FormData(form).entries());
  }

  function date(value) {
    if (!value) return "-";
    return String(value).replace("T", " ").replace(".0", "");
  }

  function daysUntil(value) {
    if (!value) return 999999;
    const expires = new Date(String(value).replace(" ", "T"));
    if (Number.isNaN(expires.getTime())) return 999999;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    expires.setHours(0, 0, 0, 0);
    return Math.ceil((expires.getTime() - today.getTime()) / 86400000);
  }

  function expirationLabel(value) {
    const days = daysUntil(value);
    if (days === 999999) return "Sem vencimento";
    if (days < 0) return "Vencido ha " + Math.abs(days) + " dia(s)";
    if (days === 0) return "Vence hoje";
    return "Faltam " + days + " dia(s)";
  }

  function restrictedStatus(value) {
    const status = String(value || "").toUpperCase();
    return ["BLOCKED", "SUSPENDED", "EXPIRED", "CANCELLED", "CANCELED", "INACTIVE"].includes(status);
  }

  function emptyRow(cols) {
    return `<tr><td colspan="${cols}" class="muted">Nenhum registro encontrado.</td></tr>`;
  }

  function toast(message, danger) {
    els.toast.textContent = message;
    els.toast.style.borderLeftColor = danger ? "var(--danger)" : "var(--primary)";
    els.toast.hidden = false;
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => {
      els.toast.hidden = true;
    }, 3500);
  }

  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    }[char]));
  }

  function escAttr(value) {
    return esc(value).replace(/`/g, "&#96;");
  }
})();
