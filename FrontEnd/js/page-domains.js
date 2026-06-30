(function () {
  "use strict";

  window.ZentrixPageDomains = Object.freeze({
    dashboard: Object.freeze({ renderer: "renderDashboard", endpoints: ["/dashboard"], query: "period" }),
    vendas: Object.freeze({ renderer: "renderSales", endpoints: ["/sales"], query: "period" }),
    financeiro: Object.freeze({ renderer: "renderFinance", endpoints: ["/finance"], query: "period" }),
    caixa: Object.freeze({ renderer: "renderCash", endpoints: ["/cash-sessions"], query: "period" }),
    produtos: Object.freeze({ renderer: "renderProducts", endpoints: ["/products"], query: "store" }),
    estoque: Object.freeze({ renderer: "renderStock", endpoints: ["/stock/alerts"], query: "store" }),
    clientes: Object.freeze({ renderer: "renderClients", endpoints: ["/clients"], query: "store" }),
    funcionarios: Object.freeze({ renderer: "renderEmployees", endpoints: ["/employees"], query: "store" }),
    auditoria: Object.freeze({ renderer: "renderAudit", endpoints: ["/audit"], query: "period" }),
    relatorios: Object.freeze({ renderer: "renderReports", endpoints: ["/reports"], query: "period" }),
    backups: Object.freeze({ renderer: "renderBackups", endpoints: ["/backups"], query: "store" }),
    configuracoes: Object.freeze({ renderer: "renderOwnerSettings", endpoints: ["/settings"], query: "store" })
  });
})();
