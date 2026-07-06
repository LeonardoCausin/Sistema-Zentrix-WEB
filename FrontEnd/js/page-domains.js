(function () {
  "use strict";

  window.ZentrixPageDomains = Object.freeze({
    dashboard: Object.freeze({ renderer: "renderDashboard", endpoints: ["/dashboard"], query: "period" }),
    vendas: Object.freeze({ renderer: "renderSales", endpoints: ["/sales"], query: "period" }),
    financeiro: Object.freeze({ renderer: "renderFinance", endpoints: ["/finance", "/finance/entries"], query: "period" }),
    caixa: Object.freeze({ renderer: "renderCash", endpoints: ["/cash-sessions"], query: "period" }),
    produtos: Object.freeze({ renderer: "renderProducts", endpoints: ["/admin/produtos"], query: "store" }),
    estoque: Object.freeze({ renderer: "renderStock", endpoints: ["/admin/produtos?limit=500", "/stock/alerts", "/stock/movements"], query: "store" }),
    clientes: Object.freeze({ renderer: "renderClients", endpoints: ["/admin/clientes"], query: "store" }),
    funcionarios: Object.freeze({ renderer: "renderEmployees", endpoints: ["/employees"], query: "store" }),
    auditoria: Object.freeze({ renderer: "renderAudit", endpoints: ["/audit", "/sync/monitor"], query: "period" }),
    sincronizacao: Object.freeze({ renderer: "renderSyncCenter", endpoints: ["/sync/monitor", "/observability"], query: "store" }),
    relatorios: Object.freeze({ renderer: "renderReports", endpoints: ["/reports"], query: "period" }),
    backups: Object.freeze({ renderer: "renderBackups", endpoints: ["/backups"], query: "store" }),
    configuracoes: Object.freeze({ renderer: "renderOwnerSettings", endpoints: ["/settings"], query: "store" })
  });
})();
