(function () {
  window.ZentrixPageRenderers = Object.freeze({
    create: function createPageRenderers(ctx) {
      const {
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
        syncAlertOwnerHtml,
        syncStatusLabel,
        tag,
        metricFilterCard,
        employeePermissions,
        wireReportActions
      } = ctx;

  async function safeApi(path, fallback, options) {
    try {
      const data = await window.zentrixApi(path, options);
      return data == null ? fallback : data;
    } catch (error) {
      console.warn("Zentrix AppGestão: falha ao carregar", path, error);
      if (!options || options.optional !== true) {
        throw error;
      }
      return fallback;
    }
  }

  async function renderDashboard() {
    const snapshot = periodLoadSnapshot();
    const data = await safeApi("/dashboard" + periodQuery(), {});
    if (isStalePeriodLoad(snapshot)) return false;
    setStores(data.stores);
    const metrics = dashboardMetrics(data);
    const hero = `
      <section class="dashboard-hero">
        <div>
          <span class="hero-eyebrow">Zentrix PDV + AppGestão</span>
          <h1 data-account-greeting>${esc(greeting())}, ${esc(currentUserFirstName())}.</h1>
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
          <div class="panel-title"><div><h3>Produtos mais vendidos</h3><span>Ranking por quantidade vendida</span></div></div>
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
    const snapshot = periodLoadSnapshot();
    const rows = await safeApi("/sales" + periodQuery(), []);
    if (isStalePeriodLoad(snapshot)) return false;
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
      ${searchPanelHtml("sales", "Buscar venda por codigo, operador, pagamento ou loja", [["all", "Todas"], ["paid", "Pagas"], ["cancelled", "Canceladas"]])}
      <div class="grid metrics-grid">
        ${metricCard("Total vendido", formatCurrency(total), "Vendas concluídas no período", "success", "$", periodLabel())}
        ${metricCard("Vendas canceladas", String(cancelledRows), "Registros com cancelamento", cancelledRows ? "danger" : "success", "X", cancelledRows ? "Revisar" : "OK")}
        ${metricCard("Ticket médio", formatCurrency(average), "Média por venda paga", "info", "~", "Atual")}
        ${metricCard("Número de vendas", String(rows.length), "Últimos registros recebidos", "info", "#", "PDV")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        ${searchableDataTableHtml("Vendas", ["Código", "Loja", "Horário", "Operador", "Pagamento", "Status", "Total"], rows, (row) => [
          row.code, row.store, row.time, row.operator, row.payment, tag(row.status), row.total
        ], exportId, (row) => searchableRowAttrs("sales", `${row.code} ${row.store} ${row.time} ${row.operator} ${row.payment} ${row.status} ${row.total}`, String(row.status || "").toLowerCase().includes("cancel") ? "cancelled" : "paid"))}
        <section class="panel receipt-card">
          <div class="panel-title"><div><h3>Detalhe da venda</h3><span>Resumo visual do Último cupom</span></div></div>
          ${saleReceiptHtml(rows[0])}
        </section>
      </div>
    `);
    setupCsvExport(exportId, "Vendas", ["Código", "Loja", "Horário", "Operador", "Pagamento", "Status", "Total"], rows.map((row) => [
      row.code, row.store, row.time, row.operator, row.payment, row.status, row.total
    ]));
  }

  async function renderFinance() {
    const snapshot = periodLoadSnapshot();
    const [data, entries] = await Promise.all([
      safeApi("/finance" + periodQuery(), {}),
      safeApi("/finance/entries" + periodQuery(), [])
    ]);
    if (isStalePeriodLoad(snapshot)) return false;
    const cancelled = Number(data.cancelledSales || 0);
    const exportId = "export-financeiro-lancamentos";
    renderShell("Financeiro", "Entradas, saldo, previsão e recebimentos por forma de pagamento.", `
      <div class="grid metrics-grid">
        ${metricCard("Entradas", data.periodTotal || data.todayTotal, periodLabel(), "success", "+", "Recebido")}
        ${metricCard("Saídas", data.manualExpenses || "R$ 0,00", "Despesas manuais pagas", moneyValue(data.manualExpenses) ? "warning" : "success", "-", "AppGestão")}
        ${metricCard("Saldo", data.netTotal || data.periodTotal || data.todayTotal, "Vendas + receitas - despesas", "info", "$", "Atual")}
        ${metricCard("Previsão", data.monthTotal, "Faturamento do mês", "info", ">", "Mês")}
      </div>
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-finance-entry">Novo lançamento</button><button class="button btn-light compact-button" id="${esc(exportId)}" type="button">Exportar CSV</button><span class="chip info">Receitas e despesas separadas das vendas PDV</span></div>
      ${financialEntryFormHtml()}
      <div class="entity-grid" style="margin-top: 16px">
        ${entries.slice(0, 8).map((row) => financialEntryCardHtml(row)).join("") || emptyState("Ainda não há lançamentos financeiros manuais.")}
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
    setupCsvExport(exportId, "Financeiro - Lançamentos", ["Loja", "Data", "Tipo", "Categoria", "Descrição", "Valor", "Status", "Origem"], entries.map((row) => [
      row.store || row.storeId || "-", row.entryDate || "-", row.type || "-", row.category || "-", row.description || "-", row.amount || "R$ 0,00", row.status || "-", row.origin || "-"
    ]));
  }

  async function renderCash() {
    const snapshot = periodLoadSnapshot();
    const rows = await loadCashRows();
    if (isStalePeriodLoad(snapshot)) return false;
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
          <p>${openRows.length ? "Há sessão ativa acompanhada pelo AppGestão." : "Nenhuma sessão aberta nos Últimos registros."}</p>
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

  async function loadCashRows() {
    try {
      return await safeApi("/cash-sessions" + periodQuery(), [], { cache: "refresh" });
    } catch (error) {
      try {
        const report = await safeApi("/reports/cash" + periodQuery(), {}, { cache: "refresh" });
        return Array.isArray(report.sessions) ? report.sessions : [];
      } catch (fallbackError) {
        throw fallbackError;
      }
    }
  }

  async function renderProducts() {
    const rows = await safeApi("/admin/produtos" + storeQuery(), []);
    const inactive = rows.filter((row) => String(row.status || "").toLowerCase().includes("inativo")).length;
    const empty = rows.filter((row) => Number(row.currentStock || 0) <= 0).length;
    renderShell("Produtos", "Cadastro sincronizado por loja, com categoria, preço, estoque e status.", `
      <div class="grid metrics-grid">
        ${metricCard("Total de produtos", String(rows.length), "Itens recebidos do PDV", "info", "#", activeStoreName())}
        ${metricCard("Ativos", String(rows.length - inactive), "Disponíveis para venda", "success", "OK", "Catálogo")}
        ${metricCard("Inativos", String(inactive), "Sem operação ativa", inactive ? "warning" : "success", "II", "Cadastro")}
        ${metricCard("Sem estoque", String(empty), "Risco de ruptura", empty ? "danger" : "success", "0", "Estoque")}
      </div>
      ${searchPanelHtml("products", "Buscar produto por nome, codigo, categoria ou codigo de barras", [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"], ["empty", "Sem estoque"]])}
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-product">Novo produto</button><span class="chip info">Cadastro web habilitado</span></div>
      ${productFormHtml()}
      <div class="entity-grid" data-search-container="products">
        ${rows.map((row) => productCardHtml(row)).join("") || emptyState("Ainda não há produtos nesta loja.")}
      </div>
    `);
  }

  async function renderStock() {
    const [rows, alerts, movements] = await Promise.all([
      safeApi("/admin/produtos" + storeQuery({ limit: 500 }), []),
      safeApi("/stock/alerts" + storeQuery(), []),
      safeApi("/stock/movements" + storeQuery(), [])
    ]);
    const critical = rows.filter((row) => Number(row.currentStock || 0) <= 0).length;
    const low = alerts.length;
    const exportId = "export-estoque-movimentos";
    renderShell("Estoque", "Produtos abaixo do estoque mínimo, risco de ruptura e alertas críticos.", `
      <div class="grid metrics-grid">
        ${metricCard("Produtos críticos", String(critical), "Sem estoque disponível", critical ? "danger" : "success", "!", "Agora")}
        ${metricCard("Estoque baixo", String(low), "Abaixo do mínimo", low ? "warning" : "success", "-", "Monitorado")}
        ${metricCard("Movimentos", String(movements.length), "Histórico recente", "info", ">", activeStoreName())}
        ${metricCard("Ação manual", "Disponível", "Entrada, saída e ajuste com motivo", "success", "+", "Web")}
      </div>
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-stock-movement">Movimentar estoque</button><span class="chip info">Motivo obrigatório</span></div>
      ${searchPanelHtml("stock", "Buscar item por produto, codigo, categoria ou loja", [["all", "Todos"], ["low", "Estoque baixo"], ["critical", "Sem estoque"], ["healthy", "Saudavel"]])}
      ${stockMovementFormHtml()}
      <div class="entity-grid" data-search-container="stock" style="margin-top: 16px">
        ${rows.map((row) => stockCardHtml(row)).join("") || emptyState("Ainda não há produtos nesta loja.")}
      </div>
      <div style="margin-top: 16px">
        ${dataTableHtml("Movimentações recentes", ["Produto", "Tipo", "Quantidade", "Anterior", "Atual", "Motivo", "Usuário", "Data"], movements, (row) => [
          row.productCode, row.type, row.quantity, row.previousStock || "-", row.newStock || "-", row.reason || "-", row.user || "-", row.createdAt || "-"
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Movimentacoes Estoque", ["Produto", "Tipo", "Quantidade", "Anterior", "Atual", "Motivo", "Usuario", "Data"], movements.map((row) => [
      row.productCode, row.type, row.quantity, row.previousStock || "-", row.newStock || "-", row.reason || "-", row.user || "-", row.createdAt || "-"
    ]));
  }

  async function renderClients() {
    const rows = await safeApi("/admin/clientes" + storeQuery(), []);
    const exportId = "export-clientes";
    renderShell("Clientes", "Cadastro, Última compra, total gasto e frequência de relacionamento.", `
      <div class="grid metrics-grid">
        ${metricCard("Total de clientes", String(rows.length), "Base sincronizada", "info", "#", activeStoreName())}
        ${metricCard("Recorrentes", "Em análise", "Depende do histórico de compras", "warning", "~", "CRM")}
        ${metricCard("Novos", String(rows.length), "Cadastros recebidos do PDV", "success", "+", periodLabel())}
        ${metricCard("Frequência", "PDV", "Acompanhamento comercial", "info", "R", "Online")}
      </div>
      ${searchPanelHtml("clients", "Buscar cliente por nome, CPF/CNPJ, telefone, email ou endereco", [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"]])}
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-client">Novo cliente</button><span class="chip info">Cadastro web habilitado</span></div>
      ${clientFormHtml()}
      <div class="entity-grid" data-search-container="clients" style="margin-top: 16px">
        ${rows.map((row) => clientCardHtml(row)).join("") || emptyState("Ainda não há clientes para mostrar.")}
      </div>
    `);
    setupCsvExport(exportId, "Clientes", ["Loja", "Nome", "CPF/CNPJ", "Telefone", "E-mail", "Endereço", "Cadastro"], rows.map((row) => [
      row.store, row.name, row.cpfCnpj || "-", row.phone || "-", row.email || "-", row.address || "-", row.createdAt || "-"
    ]));
  }

  async function renderEmployees() {
    const rows = await safeApi("/employees" + storeQuery(), []);
    const exportId = "export-funcionarios";
    const active = rows.filter((row) => rowIsActive(row)).length;
    const employeeExportRows = rows.map((row) => [
      row.displayName || row.name || row.username,
      row.username,
      row.role || "-",
      rowIsActive(row) ? "Ativo" : "Inativo",
      row.lastLoginAt || "-",
      String(employeePermissions(row).length)
    ]);
    renderShell("Funcionários", "Cargo, status, vendas realizadas e permissões operacionais.", `
      <div class="grid metrics-grid">
        ${metricCard("Funcionários", String(rows.length), "Usuários sincronizados", "info", "#", activeStoreName())}
        ${metricCard("Ativos", String(active), "Podem operar no PDV", "success", "OK", "Permissões")}
        ${metricCard("Administradores", String(rows.filter((row) => roleTone(row.role) === "danger").length), "Acesso elevado", "warning", "AD", "Segurança")}
        ${metricCard("Operadores", String(rows.filter((row) => roleTone(row.role) !== "danger").length), "Atendimento e vendas", "info", "OP", "Equipe")}
      </div>
      ${searchPanelHtml("employees", "Buscar funcionário por nome, login, cargo ou permissão", [["all", "Todos"], ["active", "Ativos"], ["inactive", "Inativos"], ["admin", "Administradores"]])}
      <div class="page-actions" style="margin: 16px 0"><button class="button btn-primary" type="button" data-action="new-employee">Novo funcionário</button><span class="chip info">Permissões por perfil e área</span></div>
      ${employeeFormHtml()}
      <div style="margin-bottom: 16px">
        ${dataTableHtml("Funcionarios", ["Nome", "Login", "Cargo", "Status", "Ultimo login", "Permissoes"], rows, (row) => [
          row.displayName || row.name || row.username,
          row.username,
          row.role || "-",
          rowIsActive(row) ? "Ativo" : "Inativo",
          row.lastLoginAt || "-",
          String(employeePermissions(row).length)
        ], exportId)}
      </div>
      <div class="entity-grid" data-search-container="employees" style="margin-top: 16px">
        ${rows.map((row) => employeeCardHtml(row)).join("") || emptyState("Ainda não há funcionários recebidos do PDV.")}
      </div>
    `);
    setupCsvExport(exportId, "Funcionarios", ["Nome", "Login", "Cargo", "Status", "Ultimo login", "Permissoes"], employeeExportRows);
  }

  async function renderAudit() {
    const snapshot = periodLoadSnapshot();
    const [rows, monitor] = await Promise.all([
      safeApi("/audit" + periodQuery(), []),
      safeApi("/sync/monitor" + storeQuery(), {}, { optional: true })
    ]);
    if (isStalePeriodLoad(snapshot)) return false;
    const risky = rows.filter((row) => auditTone(row) !== "info").length;
    const pending = Number(monitor.pendingCount || 0);
    const retryable = Number(monitor.retryableErrorCount || 0);
    const dead = Number(monitor.deadLetterCount || 0);
    const syncTone = dead ? "danger" : retryable ? "warning" : "success";
    const syncLabel = dead ? "Ação" : retryable ? "Reenvio" : "OK";
    const exportId = "export-auditoria";
    renderShell("Auditoria", "Ações sensíveis, falhas de sincronização e alterações manuais.", `
      <div class="grid metrics-grid">
        ${metricCard("Eventos", String(rows.length), "Registros no período", "info", "#", periodLabel())}
        ${metricCard("Alertas", String(risky), "Cancelamentos, exclusões e falhas", risky ? "warning" : "success", "!", "Risco")}
        ${metricCard("Envios ao PDV", String(pending), "Alterações aguardando envio", pending ? "warning" : "success", "PDV", "Fila")}
        ${metricCard("Sincronização", dead ? String(dead) : String(retryable), dead ? "Itens pausados pedem atenção" : retryable ? "Erros aguardando novo envio" : "Fila saudável", syncTone, syncLabel, "Monitor")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Timeline de auditoria</h3><span>Eventos mais recentes</span></div></div>${auditTimelineHtml(rows)}</section>
        <section class="panel"><div class="panel-title"><div><h3>Monitor de sincronização</h3><span>Envios, confirmações e últimos recebimentos do PDV</span></div></div>${syncMonitorHtml(monitor)}</section>
      </div>
      <div style="margin-top: 16px">
        ${dataTableHtml("Auditoria", ["Loja", "Ação", "Horário", "Usuário", "Descrição", "Detalhes"], rows, (row) => [
          row.store, tag(row.action), row.time, row.user || "-", row.description, row.value || "-"
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Auditoria", ["Loja", "Ação", "Horário", "Usuário", "Descrição", "Detalhes"], rows.map((row) => [
      row.store, row.action, row.time, row.user || "-", row.description, row.value || "-"
    ]));
  }

  function syncMonitorHtml(monitor) {
    if (!monitor || !Object.keys(monitor).length) {
      return emptyState("Monitor de sincronização indisponível no momento.");
    }
    const rows = [
      ["PENDING", monitor.pendingCount || 0, "Aguardando envio ao PDV"],
      ["DELIVERED", monitor.deliveredCount || 0, "Enviado e aguardando confirmação"],
      ["ERROR", monitor.retryableErrorCount || 0, "Aguardando novo envio"],
      ["DEAD", monitor.deadLetterCount || 0, "Pausado para análise"]
    ];
    const summary = rows.map(([status, count, subtitle]) => `<div class="list-item"><span class="list-icon ${syncMonitorTone(status, count)}">${esc(status)}</span><div><span class="list-title">${esc(status)}</span><span class="list-subtitle">${esc(subtitle)}</span></div><strong>${esc(String(count))}</strong></div>`).join("");
    const oldest = monitor.oldestPending && Object.keys(monitor.oldestPending).length
      ? `<div class="list-item"><span class="list-icon warning">OLD</span><div><span class="list-title">Mais antigo na fila</span><span class="list-subtitle">${esc(monitor.oldestPending.entityType || "-")} #${esc(String(monitor.oldestPending.id || "-"))} · ${esc(monitor.oldestPending.status || "-")}</span></div><strong>${esc(monitor.oldestPending.createdAt || "-")}</strong></div>`
      : "";
    const recentRuns = Array.isArray(monitor.recentSyncRuns) ? monitor.recentSyncRuns.slice(0, 3) : [];
    const runs = recentRuns.map((row) => `<div class="list-item"><span class="list-icon ${row.status === "SUCCESS" ? "success" : "warning"}">IN</span><div><span class="list-title">${esc(row.sourceId || "PDV")}</span><span class="list-subtitle">${esc(row.receivedAt || "-")} · ${esc(String(row.recordCount || 0))} registros</span></div><strong>${esc(row.status || "-")}</strong></div>`).join("");
    return `<div class="stack-list">${summary}${oldest}${runs || emptyState("Ainda não há recebimentos recentes do PDV.")}</div>`;
  }

  function syncMonitorTone(status, count) {
    const value = Number(count || 0);
    if (status === "DEAD" && value > 0) return "danger";
    if ((status === "ERROR" || status === "PENDING" || status === "DELIVERED") && value > 0) return "warning";
    return "success";
  }

  async function renderSyncCenter() {
    const [monitor, observability] = await Promise.all([
      safeApi("/sync/monitor" + storeQuery(), {}),
      safeApi("/observability", {}, { optional: true })
    ]);
    const pending = Number(monitor.pendingCount || 0);
    const delivered = Number(monitor.deliveredCount || 0);
    const retryable = Number(monitor.retryableErrorCount || 0);
    const dead = Number(monitor.deadLetterCount || 0);
    const healthTone = dead ? "danger" : retryable ? "warning" : "success";
    renderShell("Sincronização", "Envios do painel para o PDV, status da loja e ações seguras.", `
      ${filterStrip([
        ["Loja", activeStoreName()],
        ["Contrato", monitor.contractVersion || "-"],
        ["Sistema", observability.status || "Monitorando"]
      ])}
      <div class="grid metrics-grid">
        ${metricCard("Aguardando envio", String(pending), "Alterações feitas no painel", pending ? "warning" : "success", "PDV", "PENDING")}
        ${metricCard("Enviados", String(delivered), "Aguardando confirmação do PDV", delivered ? "info" : "success", "OK", "DELIVERED")}
        ${metricCard("Novo envio", String(retryable), "Erros com tentativa agendada", retryable ? "warning" : "success", "RE", "ERROR")}
        ${metricCard("Pausados", String(dead), "Não bloqueia os próximos envios", dead ? "danger" : "success", "PA", "DEAD")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Resumo dos envios</h3><span>Status por etapa de entrega</span></div>${tag(dead ? "Atenção" : retryable ? "Reenvio ativo" : "Saudável")}</div>${syncCenterSummaryHtml(monitor)}</section>
        <section class="panel"><div class="panel-title"><div><h3>Monitoramento</h3><span>Saúde do Zentrix Web</span></div>${tag(observability.status || "UP")}</div>${observabilityHtml(observability)}</section>
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Erros recentes</h3><span>Reenvie ou pause sem travar os próximos envios</span></div></div>${syncErrorsHtml(monitor.recentErrors || [])}</section>
        <section class="panel"><div class="panel-title"><div><h3>Recebimentos do PDV</h3><span>Últimas cargas recebidas pelo painel</span></div></div>${syncRunsHtml(monitor.recentSyncRuns || [])}</section>
      </div>
    `);
  }

  function syncCenterSummaryHtml(monitor) {
    if (!monitor || !Object.keys(monitor).length) {
      return emptyState("Monitor de sincronização indisponível no momento.");
    }
    const summary = Array.isArray(monitor.summary) ? monitor.summary : [];
    const rows = summary.map((row) => `<div class="list-item"><span class="list-icon ${syncMonitorTone(row.status, row.count)}">${esc(row.status || "-")}</span><div><span class="list-title">${esc(row.status || "-")}</span><span class="list-subtitle">Último registro ${esc(String(row.lastId || 0))}</span></div><strong>${esc(String(row.count || 0))}</strong></div>`).join("");
    const oldest = monitor.oldestPending && Object.keys(monitor.oldestPending).length
      ? `<div class="list-item"><span class="list-icon warning">OLD</span><div><span class="list-title">Mais antigo ativo</span><span class="list-subtitle">${esc(monitor.oldestPending.entityType || "-")} ${esc(monitor.oldestPending.entityId || "-")} | ${esc(monitor.oldestPending.operation || "-")}</span></div><strong>${esc(monitor.oldestPending.status || "-")}</strong></div>`
      : "";
    return `<div class="stack-list">${rows}${oldest || emptyState("Fila sem registros ativos.")}</div>`;
  }

  function syncErrorsHtml(rows) {
    if (!Array.isArray(rows) || !rows.length) {
      return emptyState("Não há erros recentes nos envios ao PDV.");
    }
    return `<div class="stack-list">${rows.map((row) => `
      <div class="list-item">
        <span class="list-icon ${syncMonitorTone(row.status, 1)}">${esc(row.status || "-")}</span>
        <div><span class="list-title">#${esc(String(row.id || "-"))} ${esc(row.entityType || "-")} ${esc(row.entityId || "")}</span><span class="list-subtitle">${esc(row.lastError || row.operation || "Sem detalhe")} | tentativas ${esc(String(row.errorCount || 0))}</span></div>
        <div class="inline-actions">
          <button class="button btn-light compact-button" type="button" data-action="sync-retry" data-id="${esc(String(row.id || ""))}" data-store-id="${esc(row.storeId || "")}">Reenviar</button>
          <button class="button btn-dark compact-button" type="button" data-action="sync-dead-letter" data-id="${esc(String(row.id || ""))}" data-store-id="${esc(row.storeId || "")}">Pausar</button>
        </div>
      </div>`).join("")}</div>`;
  }

  function syncRunsHtml(rows) {
    if (!Array.isArray(rows) || !rows.length) {
      return emptyState("Ainda não há recebimentos recentes do PDV.");
    }
    return `<div class="stack-list">${rows.map((row) => `<div class="list-item"><span class="list-icon ${String(row.status || "").toUpperCase() === "SUCCESS" ? "success" : "warning"}">IN</span><div><span class="list-title">${esc(row.sourceId || row.deviceId || "PDV")}</span><span class="list-subtitle">${esc(row.receivedAt || "-")} | loja ${esc(row.storeId || "-")}</span></div><strong>${esc(String(row.recordCount || 0))}</strong></div>`).join("")}</div>`;
  }

  function observabilityHtml(data) {
    if (!data || !Object.keys(data).length) {
      return emptyState("Monitoramento indisponível para esta sessão.");
    }
    const memory = data.memory || {};
    const database = data.database || {};
    const rows = [
      ["Tempo online", data.uptime || data.uptimeMs || "-", "Tempo desde a última inicialização"],
      ["Memória usada", memory.used || memory.usedMb || "-", "Uso atual do sistema"],
      ["Banco de dados", database.status || data.databaseStatus || "OK", "Conexão com os dados"],
      ["Rastreamento", "Ativo", "Ajuda o suporte a localizar atendimentos"]
    ];
    return `<div class="stack-list">${rows.map(([title, value, subtitle]) => `<div class="list-item"><span class="list-icon info">OK</span><div><span class="list-title">${esc(title)}</span><span class="list-subtitle">${esc(subtitle)}</span></div><strong>${esc(String(value))}</strong></div>`).join("")}</div>`;
  }

  async function renderReports() {
    const snapshot = periodLoadSnapshot();
    const data = await safeApi("/reports" + periodQuery(), {});
    if (isStalePeriodLoad(snapshot)) return false;
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
        <section class="panel"><div class="panel-title"><div><h3>Top produtos</h3><span>Quantidade vendida</span></div></div><div class="stack-list">${rankingHtml(data.topProducts || [])}</div></section>
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
    const rows = await safeApi("/backups" + storeQuery(), []);
    const latest = rows.find((row) => String(row.status || "").toUpperCase() === "CONCLUIDO") || rows[0];
    const failed = rows.filter((row) => String(row.status || "").toUpperCase().includes("FALH")).length;
    const validBackups = rows.filter((row) => String(row.integrity || "").toLowerCase() === "íntegro").length;
    const completedMissingFile = rows.filter((row) => String(row.status || "").toUpperCase() === "CONCLUIDO" && !row.fileExists).length;
    const latestCanDownload = latest && latest.id && latest.fileExists && latest.checksumValid !== false;
    const exportId = "export-backups";
    renderShell("Backups", "Histórico, status e segurança dos dados sincronizados.", `
      <div class="grid metrics-grid">
        ${metricCard("Último backup", latest ? latest.date : "Aguardando", latest ? latest.integrity || "Verificando integridade" : "Arquivo de segurança gerado pelo AppGestão", latestCanDownload ? "success" : latest ? "warning" : "warning", "BK", "Arquivo")}
        ${metricCard("Integridade", rows.length ? `${validBackups}/${rows.length}` : "Aguardando", "Backups íntegros no histórico", completedMissingFile ? "warning" : validBackups ? "success" : "warning", "OK", "Checksum")}
        ${metricCard("Arquivos gerados", String(rows.length), "Histórico de backups reais", "info", "#", activeStoreName())}
        ${metricCard("Falhas", String(failed), "Backups com erro", failed ? "danger" : "success", "X", "Auditoria")}
      </div>
      <div class="page-actions" style="margin: 16px 0">
        <button class="button btn-primary" type="button" data-action="create-backup">Gerar backup agora</button>
        ${latestCanDownload ? `<button class="button btn-light" type="button" data-action="download-real-backup" data-id="${escAttr(latest.id)}">Baixar último backup</button>` : ""}
        <span class="chip ${latestCanDownload ? "success" : "warning"}">${latestCanDownload ? "Backup íntegro" : "Verifique o último backup"}</span>
      </div>
      <div class="grid two-column">
        <section class="panel"><div class="panel-title"><div><h3>Timeline de backups</h3><span>Últimos arquivos gerados</span></div></div>${backupTimelineHtml(rows)}</section>
        <section class="panel"><div class="panel-title"><div><h3>Integridade do último backup</h3><span>Conferência antes de baixar ou restaurar</span></div></div>${backupIntegrityHtml(latest)}</section>
      </div>
      <div style="margin-top: 16px">
        ${dataTableHtml("Backups", ["Data", "Responsável", "Arquivo", "Tamanho", "Registros", "Integridade", "Status"], rows, (row) => [
          row.date, row.createdBy || "-", row.fileName || "-", row.size || "0 B", row.rows || 0, tag(row.integrity || "-"), tag(row.status)
        ], exportId)}
      </div>
    `);
    setupCsvExport(exportId, "Backups", ["Data", "Responsável", "Arquivo", "Tamanho", "Registros", "Integridade", "Status", "Checksum"], rows.map((row) => [
      row.date, row.createdBy || "-", row.fileName || "-", row.size || "0 B", row.rows || 0, row.integrity || "-", row.status, row.checksum || ""
    ]));
  }

  async function renderOwnerSettings() {
    const data = await safeApi("/settings" + storeQuery(), {});
    setStores(data.stores);
    const tenantName = data.tenant && data.tenant.name ? data.tenant.name : "Cliente Zentrix";
    const users = data.users || 0;
    const lastUpdate = data.lastSync || "Aguardando o primeiro envio do PDV";
    renderShell("Configurações", "Ajustes principais para deixar o painel com a cara e as regras da sua empresa.", `
      <div class="module-grid settings-grid">
        ${settingsCard("Abertura do painel", "Tela inicial da equipe", settingsLabel(data.settings && data.settings.dashboardPeriodoPadrao, "today"), "info")}
        ${settingsCard("Proteção de acesso", "Sessão e senha", `${settingValue(data, "sessaoExpiraMinutos", 480)} min`, "success")}
        ${settingsCard("Regras da loja", "Vendas e estoque", settingValue(data, "permitirEstoqueNegativo", false) ? "Venda sem estoque liberada" : "Venda sem estoque bloqueada", "warning")}
        ${settingsCard("Avisos ao dono", "Alertas importantes", "Estoque, caixa, PDV e backup", "info")}
        ${settingsCard("Backup", "Cópia de segurança", `${settingValue(data, "backupHorario", "23:30")} | ${settingValue(data, "backupRetencao", 14)} dias`, "success")}
        ${settingsCard("Conexão da loja", "PDV vinculado", activeStoreName(), "info")}
      </div>
      <div class="grid two-column" style="margin-top: 16px">
        <section class="panel"><div class="panel-title"><div><h3>Resumo da conta</h3><span>Informações que ajudam o dono a acompanhar a loja</span></div></div><div class="stack-list">
          <div class="list-item"><span class="list-icon success">OK</span><div><span class="list-title">Painel liberado</span><span class="list-subtitle">Sua equipe pode acompanhar a operação</span></div><strong>Ativo</strong></div>
          <div class="list-item"><span class="list-icon info">LO</span><div><span class="list-title">Empresa</span><span class="list-subtitle">Nome usado nos controles internos</span></div><strong>${esc(tenantName)}</strong></div>
          <div class="list-item"><span class="list-icon success">ON</span><div><span class="list-title">Acesso online</span><span class="list-subtitle">Painel disponível para a empresa</span></div><strong>Online</strong></div>
          <div class="list-item"><span class="list-icon info">PDV</span><div><span class="list-title">Último dado recebido</span><span class="list-subtitle">${esc(activeStoreName())}</span></div><strong>${esc(lastUpdate)}</strong></div>
        </div></section>
        <section class="panel">
          <div class="panel-title"><div><h3>Visual do painel</h3><span>Escolha uma aparência confortável para a equipe</span></div></div>
          <div class="theme-choice">
            <button class="button btn-light" type="button" data-action="set-theme" data-theme="light">Claro</button>
            <button class="button btn-dark" type="button" data-action="set-theme" data-theme="dark">Escuro</button>
          </div>
        </section>
      </div>
      ${settingsFormHtml(data)}
    `);
  }

  async function renderSettings() {
    return renderOwnerSettings();
  }

  function settingsFormHtml(data) {
    const settings = data.settings || {};
    return `<form class="settings-form" data-settings-form style="margin-top: 16px">
      <div class="grid two-column">
        ${settingsPanel("Como o painel abre", "Defina o que a equipe vê primeiro ao entrar.", [
          selectField("dashboard_periodo_padrao", "Período inicial dos indicadores", settings.dashboardPeriodoPadrao || "today", [["today", "Hoje"], ["7d", "7 dias"], ["month", "30 dias"], ["year", "1 ano"]]),
          selectField("loja_padrao", "Loja inicial", settings.lojaPadrao || "all", storeOptions(data.stores || [])),
          selectField("pagina_inicial", "Tela inicial", settings.paginaInicial || "dashboard.html", [["dashboard.html", "Dashboard"], ["vendas.html", "Vendas"], ["financeiro.html", "Financeiro"], ["sincronizacao.html", "Sincronização"]]),
          selectField("tema_padrao", "Visual padrão", settings.temaPadrao || "system", [["system", "Seguir navegador"], ["light", "Claro"], ["dark", "Escuro"]])
        ])}
        ${settingsPanel("Proteção de acesso", "Ajustes para reduzir acesso indevido ao painel.", [
          numberField("sessao_expira_minutos", "Encerrar sessão após minutos sem uso", settings.sessaoExpiraMinutos || 480, 15, 1440),
          numberField("bloqueio_tentativas_login", "Bloquear login após tentativas erradas", settings.bloqueioTentativasLogin || 5, 3, 20),
          switchField("senha_forte_obrigatoria", "Pedir senhas mais seguras para a equipe", settings.senhaForteObrigatoria !== false)
        ])}
        ${settingsPanel("Regras da loja", "Escolha limites para evitar prejuízo e retrabalho.", [
          switchField("permitir_estoque_negativo", "Permitir venda quando o estoque estiver zerado", settings.permitirEstoqueNegativo === true),
          switchField("exigir_motivo_cancelamento", "Pedir motivo ao cancelar uma venda", settings.exigirMotivoCancelamento !== false),
          switchField("exigir_motivo_desconto", "Pedir motivo para desconto alto", settings.exigirMotivoDesconto !== false),
          numberField("desconto_maximo_padrao", "Desconto máximo sem autorização (%)", settings.descontoMaximoPadrao || 10, 0, 100)
        ])}
        ${settingsPanel("Avisos importantes", "Escolha o que deve chamar atenção do dono e dos responsáveis.", [
          switchField("alerta_estoque_baixo", "Estoque baixo", settings.alertaEstoqueBaixo !== false),
          switchField("alerta_pdv_offline", "PDV offline", settings.alertaPdvOffline !== false),
          switchField("alerta_sync_falha", "Falha ao receber dados da loja", settings.alertaSyncFalha !== false),
          switchField("alerta_caixa_divergente", "Caixa divergente", settings.alertaCaixaDivergente !== false),
          switchField("alerta_backup_atrasado", "Backup atrasado", settings.alertaBackupAtrasado !== false)
        ])}
        ${settingsPanel("Cópia de segurança", "Defina quando o sistema deve acompanhar os backups.", [
          inputField("backup_horario", "Horário preferido para backup", settings.backupHorario || "23:30", "time"),
          numberField("backup_retencao", "Guardar histórico por dias", settings.backupRetencao || 14, 1, 365),
          numberField("backup_alertar_dias", "Avisar se ficar dias sem backup", settings.backupAlertarDias || 1, 1, 30)
        ])}
        ${settingsPanel("Conexão com a loja", "Ajustes para manter o painel recebendo dados do PDV.", [
          numberField("sync_intervalo_segundos", "Tempo esperado para receber novas informações", settings.syncIntervaloSegundos || 30, 10, 3600),
          selectField("ambiente_nome", "Tipo de uso", settings.ambienteNome || "Produção", [["Produção", "Uso real"], ["Local", "Uso local"], ["Teste", "Teste ou demonstração"]]),
          inputField("pdv_integration_token", settings.pdvIntegrationTokenConfigured ? "Trocar código de conexão do PDV" : "Código de conexão do PDV", "", "password", "Preencha somente quando precisar trocar")
        ])}
        ${settingsPanel("Suporte e atualização", "Informações simples para atendimento e manutenção.", [
          inputField("maintenance_cache_version", "Código de atualização do painel", settings.maintenanceCacheVersion || "", "text", "Use quando o suporte solicitar"),
          `<div class="list-item"><span class="list-icon success">ON</span><div><span class="list-title">Painel online</span><span class="list-subtitle">Acesso disponível para a empresa</span></div><strong>Ativo</strong></div>`,
          `<div class="list-item"><span class="list-icon info">PDV</span><div><span class="list-title">Último dado recebido</span><span class="list-subtitle">${esc(activeStoreName())}</span></div><strong>${esc(data.lastSync || "-")}</strong></div>`
        ])}
      </div>
      <div class="page-actions settings-actions">
        <button class="button btn-primary" type="submit">Salvar configurações</button>
        <span class="chip info">Vale para ${esc(activeStoreName())}</span>
      </div>
    </form>`;
  }

  function settingsPanel(title, subtitle, content) {
    return `<section class="panel settings-section"><div class="panel-title"><div><h3>${esc(title)}</h3><span>${esc(subtitle)}</span></div></div><div class="settings-fields">${content.join("")}</div></section>`;
  }

  function settingValue(data, key, fallback) {
    return data.settings && data.settings[key] != null ? data.settings[key] : fallback;
  }

  function settingsLabel(value, fallback) {
    return ({ today: "Hoje", "7d": "7 dias", month: "30 dias", year: "1 ano" })[value || fallback] || value || fallback;
  }

  function storeOptions(stores) {
    const rows = stores.length ? stores : [{ id: "all", name: "Todas as lojas" }];
    return rows.map((store) => [store.id || "all", store.name || store.label || "Loja"]);
  }

  function inputField(name, label, value, type, placeholder) {
    return `<label class="field"><span>${esc(label)}</span><input class="text-field" type="${escAttr(type || "text")}" name="${escAttr(name)}" value="${escAttr(value)}" placeholder="${escAttr(placeholder || "")}" /></label>`;
  }

  function numberField(name, label, value, min, max) {
    return `<label class="field"><span>${esc(label)}</span><input class="text-field" type="number" name="${escAttr(name)}" value="${escAttr(value)}" min="${escAttr(min)}" max="${escAttr(max)}" /></label>`;
  }

  function selectField(name, label, value, options) {
    return `<label class="field"><span>${esc(label)}</span><select class="select-field" name="${escAttr(name)}">${options.map(([optionValue, optionLabel]) => `<option value="${escAttr(optionValue)}" ${String(optionValue) === String(value) ? "selected" : ""}>${esc(optionLabel)}</option>`).join("")}</select></label>`;
  }

  function switchField(name, label, checked) {
    return `<label class="permission-row"><span>${esc(label)}</span><span class="switch"><input type="checkbox" name="${escAttr(name)}" data-setting-checkbox ${checked ? "checked" : ""} /><span></span></span></label>`;
  }


          return Object.freeze({
            renderDashboard,
            renderSales,
            renderFinance,
            renderCash,
            renderProducts,
            renderStock,
            renderClients,
            renderEmployees,
            renderAudit,
            renderSyncCenter,
            renderReports,
            renderBackups,
            renderOwnerSettings,
            renderSettings
          });
    }
  });
})();
