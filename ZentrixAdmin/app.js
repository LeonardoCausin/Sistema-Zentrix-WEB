(function () {
  "use strict";

  const SESSION_KEY = "zentrix-admin-session";
  const API_BASE = (window.ZentrixAdminConfig && window.ZentrixAdminConfig.apiBase) || "/api";

  const state = {
    view: "dashboard",
    session: readSession(),
    overview: null,
    clients: [],
    support: null
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
    modalBody: document.getElementById("modalBody")
  };

  document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.view));
  });
  els.loginForm.addEventListener("submit", login);
  els.logoutButton.addEventListener("click", logout);
  els.refreshButton.addEventListener("click", () => loadView(true));
  els.storeSelect.addEventListener("change", () => state.view === "support" && loadSupport());

  boot();

  async function boot() {
    if (!state.session) {
      showLogin();
      return;
    }
    showApp();
    await Promise.all([loadStores(), loadView(true)]);
  }

  async function login(event) {
    event.preventDefault();
    const data = formData(event.currentTarget);
    try {
      const session = await rawRequest("/auth/login", {
        method: "POST",
        body: JSON.stringify(data)
      });
      state.session = session;
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
      showApp();
      await Promise.all([loadStores(), loadView(true)]);
      toast("Acesso liberado.");
    } catch (error) {
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
    try {
      if (state.view === "dashboard") await loadDashboard(fresh);
      if (state.view === "clients") await loadClients(fresh);
      if (state.view === "subscriptions") await loadSubscriptions(fresh);
      if (state.view === "support") await loadSupport();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function loadStores() {
    try {
      const stores = await api("/stores");
      els.storeSelect.innerHTML = stores.map((store) => `<option value="${escAttr(store.id)}">${esc(store.name || store.id)}</option>`).join("");
    } catch (error) {
      els.storeSelect.innerHTML = '<option value="all">Todas as lojas</option>';
    }
  }

  async function loadDashboard(fresh) {
    setHeading("Dashboard", "Visao geral da operacao Zentrix.");
    if (fresh || !state.overview) {
      state.overview = await api("/zentrix-admin/overview");
    }
    const data = state.overview;
    els.viewHost.innerHTML = `
      <div class="grid">
        ${metric("Clientes", data.clients, "Empresas cadastradas")}
        ${metric("Ativos", data.activeClients, "Clientes liberados")}
        ${metric("Assinaturas", data.activeSubscriptions, "Planos ativos")}
        ${metric("Vencendo", data.expiringSoon, "Proximos 7 dias")}
      </div>
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
    els.viewHost.innerHTML = `
      <section class="panel" style="margin-top:0">
        <div class="panel-title"><div><h2>Assinaturas</h2><span class="muted">Controle comercial dos clientes</span></div></div>
        <table>
          <thead><tr><th>Cliente</th><th>Plano</th><th>Status</th><th>Vencimento</th><th>Limites</th><th>Acoes</th></tr></thead>
          <tbody>${state.clients.map((row) => `
            <tr>
              <td><strong>${esc(row.name)}</strong><br><span class="muted">${esc(row.tenantId)}</span></td>
              <td>${esc(row.planName || "Sem plano")}</td>
              <td>${tag(row.licenseStatus || row.status)}</td>
              <td>${date(row.expiresAt)}</td>
              <td>${esc(row.maxStores || 0)} lojas / ${esc(row.maxDevices || 0)} disp.</td>
              <td class="actions">
                <button type="button" data-action="renew-license" data-tenant="${escAttr(row.tenantId)}">Renovar</button>
                <button type="button" data-action="block-client" data-tenant="${escAttr(row.tenantId)}">Bloquear</button>
                <button type="button" data-action="activate-client" data-tenant="${escAttr(row.tenantId)}">Liberar</button>
              </td>
            </tr>
          `).join("") || emptyRow(6)}</tbody>
        </table>
      </section>
    `;
    wireCommonActions();
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
      <thead><tr><th>Cliente</th><th>Status</th><th>Plano</th><th>Vencimento</th><th>Estrutura</th><th>Acoes</th></tr></thead>
      <tbody>${rows.map((row) => `
        <tr>
          <td><strong>${esc(row.name)}</strong><br><span class="muted">${esc(row.document || row.tenantId)}</span></td>
          <td>${tag(row.status)}</td>
          <td>${esc(row.planName || "Sem plano")}<br>${tag(row.licenseStatus || "-")}</td>
          <td>${date(row.expiresAt)}</td>
          <td>${esc(row.stores || 0)} lojas / ${esc(row.devices || 0)} disp. / ${esc(row.users || 0)} usuarios</td>
          <td class="actions">
            <button type="button" data-action="client-detail" data-tenant="${escAttr(row.tenantId)}">Abrir</button>
            <button type="button" data-action="activation-code" data-tenant="${escAttr(row.tenantId)}">Codigo PDV</button>
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
      button.onclick = () => handleAction(button);
    });
  }

  async function handleAction(button) {
    const action = button.dataset.action;
    if (action === "new-client") return showClientForm();
    if (action === "client-detail") return showClientDetail(button.dataset.tenant);
    if (action === "renew-license") return showLicenseForm(button.dataset.tenant);
    if (action === "activation-code") return showActivationForm(button.dataset.tenant);
    if (action === "block-client") return updateClientStatus(button.dataset.tenant, "BLOCKED");
    if (action === "activate-client") return updateClientStatus(button.dataset.tenant, "ACTIVE");
    if (action === "normalize-cash") return reasonAction("Normalizar caixas", "/local-admin/cash/normalize-statuses?store=" + encodeURIComponent(els.storeSelect.value), "POST");
    if (action === "clear-sync") return reasonAction("Limpar falhas de sincronizacao", "/local-admin/sync/clear-failures?store=" + encodeURIComponent(els.storeSelect.value), "POST", { days: 7 });
    if (action === "clear-backups") return reasonAction("Limpar backups com erro", "/local-admin/backups/clear-errors?store=" + encodeURIComponent(els.storeSelect.value), "POST");
    if (action === "clear-cache") return reasonAction("Limpar cache", "/local-admin/cache/clear", "POST");
    if (action === "close-cash") return showCloseCashForm(button.dataset.id, button.dataset.store);
    if (action === "delete-cash") return deleteCash(button.dataset.id, button.dataset.store);
  }

  function showClientForm() {
    openModal("Novo cliente", `
      <form id="clientForm" class="form-grid">
        ${field("companyName", "Empresa", "text", true)}
        ${field("document", "Documento")}
        ${field("storeName", "Loja inicial")}
        ${field("sourceId", "Nome/origem do PDV")}
        ${field("adminUsername", "Usuario admin", "text", true)}
        ${field("adminDisplayName", "Nome do admin")}
        ${field("adminPassword", "Senha inicial", "password", true)}
        ${field("planName", "Plano", "text", false, "BASICO")}
        ${field("expiresAt", "Vencimento", "date")}
        ${field("maxStores", "Max lojas", "number", false, "1")}
        ${field("maxDevices", "Max dispositivos", "number", false, "1")}
        <label class="full">Motivo<textarea name="reason">Cadastro de novo cliente.</textarea></label>
        <button class="primary full" type="submit">Criar cliente</button>
      </form>
    `);
    document.getElementById("clientForm").onsubmit = async (event) => {
      event.preventDefault();
      await api("/zentrix-admin/clients", { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
      closeModal();
      state.clients = [];
      state.overview = null;
      await loadView(true);
      toast("Cliente criado.");
    };
  }

  async function showClientDetail(tenantId) {
    const detail = await api("/zentrix-admin/clients/" + encodeURIComponent(tenantId));
    openModal("Cliente", `
      <div class="grid two">
        ${metric("Empresa", detail.name, detail.tenantId)}
        ${metric("Status", detail.status, "Conta")}
      </div>
      <section class="panel">
        <h3>Assinaturas</h3>
        ${simpleTable(["Plano", "Status", "Inicio", "Fim", "Limites"], detail.licenses || [], (row) => [row.planName, tag(row.status), date(row.startsAt), date(row.expiresAt), `${row.maxStores} lojas / ${row.maxDevices} disp.`])}
      </section>
      <section class="panel">
        <h3>Lojas</h3>
        ${simpleTable(["ID", "Nome", "Origem", "Status"], detail.stores || [], (row) => [row.id, row.name, row.sourceId, tag(row.status)])}
      </section>
      <section class="panel">
        <h3>Codigos de ativacao</h3>
        ${simpleTable(["Codigo", "Loja", "Status", "Expira", "Usado"], detail.activationCodes || [], (row) => [row.code, row.storeName, tag(row.status), date(row.expiresAt), date(row.usedAt)])}
      </section>
    `);
  }

  function showLicenseForm(tenantId) {
    openModal("Renovar assinatura", `
      <form id="licenseForm" class="form-grid">
        ${field("planName", "Plano", "text", true, "BASICO")}
        ${field("status", "Status", "text", true, "ACTIVE")}
        ${field("startsAt", "Inicio", "date")}
        ${field("expiresAt", "Vencimento", "date")}
        ${field("maxStores", "Max lojas", "number", false, "1")}
        ${field("maxDevices", "Max dispositivos", "number", false, "1")}
        <label class="full">Motivo<textarea name="reason">Renovacao de assinatura.</textarea></label>
        <button class="primary full" type="submit">Salvar assinatura</button>
      </form>
    `);
    document.getElementById("licenseForm").onsubmit = async (event) => {
      event.preventDefault();
      await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/licenses`, { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
      closeModal();
      state.clients = [];
      await loadView(true);
      toast("Assinatura atualizada.");
    };
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
      const result = await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/activation-codes`, { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
      document.getElementById("activationResult").innerHTML = `<section class="panel"><h3>Codigo gerado</h3><strong style="font-size:32px">${esc(result.code)}</strong><p class="muted">Expira em ${date(result.expiresAt)}</p></section>`;
      state.clients = [];
    };
  }

  async function updateClientStatus(tenantId, status) {
    const reason = prompt("Motivo da alteracao:");
    if (!reason) return;
    await api(`/zentrix-admin/clients/${encodeURIComponent(tenantId)}/status`, {
      method: "PUT",
      body: JSON.stringify({ status, updateLicense: true, reason })
    });
    state.clients = [];
    await loadView(true);
    toast("Status atualizado.");
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
      await api(`/local-admin/cash/${encodeURIComponent(id)}/close?store=${encodeURIComponent(store)}`, { method: "POST", body: JSON.stringify(formData(event.currentTarget)) });
      closeModal();
      await loadSupport();
      toast("Caixa fechado.");
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

  function metric(label, value, note) {
    return `<article class="card metric"><span>${esc(label)}</span><strong>${esc(value ?? 0)}</strong><small class="muted">${esc(note || "")}</small></article>`;
  }

  function tag(value) {
    const text = String(value || "-").toUpperCase();
    return `<span class="tag ${escAttr(text)}">${esc(text)}</span>`;
  }

  function field(name, label, type, required, value) {
    return `<label>${esc(label)}<input name="${escAttr(name)}" type="${escAttr(type || "text")}" ${required ? "required" : ""} value="${escAttr(value || "")}" /></label>`;
  }

  function openModal(title, body) {
    els.modalTitle.textContent = title;
    els.modalBody.innerHTML = body;
    els.modal.showModal();
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
    const response = await fetch(API_BASE + path, {
      credentials: "include",
      ...(options || {}),
      headers: {
        "Content-Type": "application/json",
        ...((options && options.headers) || {})
      }
    });
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
        // Mantem mensagem padrao.
      }
      throw new Error(message);
    }
    if (response.status === 204) return null;
    return response.json();
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
