import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const apiBaseScript = readFileSync(join(process.cwd(), "js", "core", "api-base.js"), "utf8");

const session = {
  token: "e2e-token",
  username: "admin",
  displayName: "Usuário Teste",
  role: "ADMIN",
  tenantId: "tenant-e2e",
  storeId: "WEB",
  permissions: ["*"]
};

test("login page loads the Zentrix AppGestao shell", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#loginForm")).toBeVisible();
  await expect(page.locator("#loginUser")).toBeVisible();
  await expect(page.locator("#loginPassword")).toBeVisible();
});

test("api base is compatible with same-origin nginx and local dev", async ({ page }) => {
  await page.goto("/");
  const result = await page.evaluate(() => ({
    origin: window.location.origin,
    port: window.location.port,
    hostname: window.location.hostname,
    apiBase: window.ZentrixApiBase.getApiBase(),
    fallbacks: window.ZentrixApiBase.getFallbackBases()
  }));

  expect(result.apiBase).toBe("/api");
  expect(result.fallbacks).toEqual(["/api"]);
});

test("same-origin app ignores stale stored api base", async ({ page }) => {
  await page.route("https://pdv.zentrixsystems.com.br/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      body: `<!doctype html><meta charset="UTF-8"><script>localStorage.setItem("zentrix-api-base","https://old.example/api");</script><script>${apiBaseScript}</script>`
    });
  });

  await page.goto("https://pdv.zentrixsystems.com.br/__api_base_test.html");

  const apiBase = await page.evaluate(() => window.ZentrixApiBase.getApiBase());
  expect(apiBase).toBe("/api");
});

test("production domain uses same-origin api path", async ({ page }) => {
  await page.route("https://pdv.zentrixsystems.com.br/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      body: `<!doctype html><meta charset="UTF-8"><script>${apiBaseScript}</script>`
    });
  });

  await page.goto("https://pdv.zentrixsystems.com.br/__api_base_test.html");

  const result = await page.evaluate(() => ({
    apiBase: window.ZentrixApiBase.getApiBase(),
    fallbacks: window.ZentrixApiBase.getFallbackBases()
  }));

  expect(result.apiBase).toBe("/api");
  expect(result.fallbacks).toEqual(["/api"]);
});

test("login ignores a stale stored api base without showing fetch failure", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("zentrix-api-base", "https://old.example/api");
  });
  await page.route("**/api/auth/login", async (route) => {
    await route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "Usuário ou senha inválidos." })
    });
  });

  await page.goto("/");
  await page.locator("#loginUser").fill("admin");
  await page.locator("#loginPassword").fill("senha-errada");
  await page.locator("#loginForm button[type='submit']").click();

  await expect(page.locator("#loginError")).toHaveText("Usuário ou senha inválidos.");
  await expect(page.locator("#loginError")).not.toContainText("Failed to fetch");
});

test("authenticated page protects itself without local session", async ({ page }) => {
  await page.goto("/FrontEnd/pages/dashboard.html");
  await expect(page).toHaveURL(/index\.html$/);
});

test("user without dashboard permission opens the first allowed page", async ({ page }) => {
  const limitedSession = {
    ...session,
    role: "CONSULTA",
    permissions: ["produtos.visualizar"]
  };
  await mockPanelApi(page, { session: limitedSession });

  await page.goto("/FrontEnd/pages/dashboard.html");

  await expect(page).toHaveURL(/produtos\.html$/);
  await expect(page.locator(".nav-list").getByText("Dashboard")).toHaveCount(0);
  await expect(page.locator(".nav-list").getByText("Produtos")).toBeVisible();
});

test("direct access to a forbidden page shows a clear permission message", async ({ page }) => {
  const limitedSession = {
    ...session,
    role: "CONSULTA",
    permissions: ["produtos.visualizar"]
  };
  await mockPanelApi(page, { session: limitedSession });

  await page.goto("/FrontEnd/pages/relatorios.html");

  await expect(page.getByText("Voce nao tem permissao para acessar esta tela")).toBeVisible();
  await expect(page.getByRole("link", { name: /Ir para Produtos/ })).toBeVisible();
});

test("dashboard loads an authenticated management flow", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/dashboard.html");
  await expect(page.getByText("Faturamento")).toBeVisible();
  await expect(page.locator(".metric-value", { hasText: "R$ 1.250,00" })).toBeVisible();
  await expect(page.getByText("Produtos mais vendidos")).toBeVisible();
  await expect(page.locator(".nav-list").getByText("Sincronização")).toHaveCount(0);
  await expect(page.getByText("PDV conectado")).toHaveCount(0);
  await expect(page.getByText("PDV desconectado")).toHaveCount(0);
  await expect(page.getByText("Última sincronização")).toHaveCount(0);
});

test("dark theme is the default even with stale light preference", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("zentrix-theme", "light");
    localStorage.removeItem("zentrix-theme-user-choice");
  });
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/dashboard.html");

  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const bg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--bg-primary").trim());
  expect(bg).toBe("#081323");
});

test("authenticated top bar shows notifications and account menu", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/dashboard.html");

  await expect(page.locator("#themeButton")).toHaveCount(0);
  await expect(page.locator("[data-action='toggle-notifications']")).toBeVisible();
  await expect(page.locator("[data-action='toggle-user-menu']")).toContainText("Usuário Teste");
  await expect(page.locator("[data-action='toggle-user-menu']")).toContainText("Administrador");

  await page.locator("[data-action='toggle-notifications']").click();
  await expect(page.locator(".notification-menu").getByText("Estoque baixo")).toBeVisible();
  await expect(page.locator(".notification-menu").getByText("Repor estoque")).toBeVisible();

  await page.locator("[data-action='toggle-user-menu']").click();
  await expect(page.locator("[data-action='logout']")).toBeVisible();
});

test("theme preference lives in settings", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/configuracoes.html");

  await page.locator("[data-action='set-theme'][data-theme='dark']").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await page.locator("[data-action='set-theme'][data-theme='light']").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

test("audit page shows administrative audit instead of sync monitor", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/auditoria.html");
  await expect(page.getByText("Timeline de auditoria")).toBeVisible();
  await expect(page.getByText("Ações críticas")).toBeVisible();
  await expect(page.getByText("Status do PDV")).toHaveCount(0);
  await expect(page.getByText("Envios ao PDV")).toHaveCount(0);
  await expect(page.getByText("Dados recebidos com sucesso").first()).toBeVisible();
  await expect(page.getByText("02/07/2026 18:00").first()).toBeVisible();
  await expect(page.getByText("SYNC_SUCCESS")).toHaveCount(0);
});


test("employee page protects the logged account from self permission changes", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/funcionarios.html");

  const ownCard = page.locator(".entity-card", { hasText: "Teste" }).first();
  await expect(ownCard.getByText("Sua conta e protegida")).toBeVisible();
  await expect(ownCard.locator('[data-action="edit-employee"]')).toHaveCount(0);
  await expect(ownCard.locator('[data-action="toggle-employee-status"]')).toHaveCount(0);
  await expect(page.locator(".entity-card", { hasText: "Operador E2E" }).locator('[data-action="edit-employee"]')).toBeVisible();
});

test("product flow creates a product from the web panel", async ({ page }) => {
  const calls = [];
  await mockPanelApi(page, { calls });
  await page.goto("/FrontEnd/pages/produtos.html");

  await page.locator('[data-action="new-product"]').click();
  const form = page.locator('[data-admin-form="product"] form');
  await form.locator('[name="code"]').fill("P-E2E");
  await form.locator('[name="description"]').fill("Produto E2E");
  await form.locator('[name="price"]').fill("19,90");
  await form.locator('[name="stock"]').fill("5");
  await form.locator('[name="minStock"]').fill("1");
  await form.locator('button[type="submit"]').click();

  await expect(page.getByText("Produto cadastrado.")).toBeVisible();
  expect(calls.some((call) => call.method === "POST" && call.url.includes("/api/admin/produtos"))).toBeTruthy();
});

test("client flow creates a client from the web panel", async ({ page }) => {
  const calls = [];
  await mockPanelApi(page, { calls });
  await page.goto("/FrontEnd/pages/clientes.html");

  await page.locator('[data-action="new-client"]').click();
  const form = page.locator('[data-admin-form="client"] form');
  await form.locator('[name="name"]').fill("Cliente E2E");
  await form.locator('[name="cpfCnpj"]').fill("12345678900");
  await form.locator('[name="phone"]').fill("(11) 99999-0000");
  await form.locator('[name="email"]').fill("cliente@teste.com");
  await form.locator('button[type="submit"]').click();

  await expect(page.getByText("Cliente cadastrado.")).toBeVisible();
  expect(calls.some((call) => call.method === "POST" && call.url.includes("/api/admin/clientes"))).toBeTruthy();
});

test("stock flow records a manual movement", async ({ page }) => {
  const calls = [];
  await mockPanelApi(page, { calls });
  await page.goto("/FrontEnd/pages/estoque.html");

  await page.locator('[data-action="new-stock-movement"]').click();
  const form = page.locator('[data-admin-form="stock"] form');
  await form.locator('[name="productCode"]').fill("P-E2E");
  await form.locator('[name="quantity"]').fill("3");
  await form.locator('[name="reason"]').fill("Reposição de teste");
  await form.locator('button[type="submit"]').click();

  await expect(page.getByText("Movimentação de estoque registrada.")).toBeVisible();
  expect(calls.some((call) => call.method === "POST" && call.url.includes("/api/stock/entry"))).toBeTruthy();
});

test("cash page loads sessions and backup monitor shows integrity", async ({ page }) => {
  await mockPanelApi(page);

  await page.goto("/FrontEnd/pages/caixa.html");
  await expect(page.getByText("Caixa aberto")).toBeVisible();
  await expect(page.getByRole("cell", { name: "Operador E2E" })).toBeVisible();

  await page.goto("/FrontEnd/pages/backups.html");
  await expect(page.getByText("Backup íntegro")).toBeVisible();
  await expect(page.getByText("Checksum confirmado")).toBeVisible();
  await expect(page.locator('[data-action="download-real-backup"]')).toBeVisible();
});

test("backup flow requests a real manual backup", async ({ page }) => {
  const calls = [];
  await mockPanelApi(page, { calls });
  await page.goto("/FrontEnd/pages/backups.html");

  await page.locator('[data-action="create-backup"]').click();

  await expect(page.getByText("Backup gerado com sucesso.")).toBeVisible();
  expect(calls.some((call) => call.method === "POST" && call.url.includes("/api/backups/manual"))).toBeTruthy();
});

test("backup restore requires staging before transactional application", async ({ page }) => {
  const calls = [];
  await mockPanelApi(page, { calls });
  await page.route("**/api/backups/7/restore/preview", (route) => route.fulfill({ json: {
    id: 7,
    totalRows: 3,
    createdAt: "2026-07-08 08:00:00",
    requiredConfirmation: "RESTAURAR BACKUP 7",
    restoreAvailable: true,
    warnings: []
  } }));
  await page.route("**/api/backups/7/restore", async (route) => {
    calls.push({ method: route.request().method(), url: route.request().url() });
    await route.fulfill({ json: {
      status: "RESTORE_STAGED",
      stagingId: 31,
      totalRows: 3,
      tables: { products: 2, clients: 1 },
      warnings: []
    } });
  });
  await page.route("**/api/backups/restore-staging/31/apply", async (route) => {
    calls.push({ method: route.request().method(), url: route.request().url() });
    await route.fulfill({ json: {
      status: "RESTORE_APPLIED",
      restoreExecuted: true,
      message: "Backup restaurado com sucesso."
    } });
  });
  page.on("dialog", async (dialog) => {
    const answer = dialog.message().includes("APLICAR RESTAURACAO")
      ? "APLICAR RESTAURACAO 31"
      : "RESTAURAR BACKUP 7";
    await dialog.accept(answer);
  });

  await page.goto("/FrontEnd/pages/backups.html");
  await page.locator('[data-action="prepare-backup-restore"]').click();
  await expect.poll(() => calls.filter((call) => call.method === "POST").length).toBe(2);
  expect(calls.some((call) => call.url.includes("/restore-staging/31/apply"))).toBeTruthy();
});

async function mockPanelApi(page, options = {}) {
  const calls = options.calls || [];
  const activeSession = options.session || session;
  await page.addInitScript((value) => {
    sessionStorage.setItem("zentrix-session", JSON.stringify(value));
  }, activeSession);

  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({ json: activeSession });
  });
  await page.route("**/api/stores", async (route) => {
    await route.fulfill({ json: [{ id: "all", name: "Todas as lojas", isAll: true }, { id: "WEB", name: "Loja Web" }] });
  });
  await page.route("**/api/settings**", async (route) => {
    await route.fulfill({ json: settingsPayload() });
  });
  await page.route("**/api/alerts**", async (route) => {
    await route.fulfill({ json: alertsPayload() });
  });
  await page.route("**/api/dashboard**", async (route) => {
    await route.fulfill({ json: dashboardPayload() });
  });
  await page.route("**/api/cash-sessions**", async (route) => {
    await route.fulfill({ json: cashSessionsPayload() });
  });
  await page.route("**/api/admin/produtos**", async (route) => {
    const request = route.request();
    calls.push({ method: request.method(), url: request.url(), body: request.postDataJSON?.() });
    if (request.method() === "POST") {
      await route.fulfill({ json: { code: "P-E2E", description: "Produto E2E" } });
      return;
    }
    await route.fulfill({ json: productsPayload() });
  });
  await page.route("**/api/admin/clientes**", async (route) => {
    const request = route.request();
    calls.push({ method: request.method(), url: request.url(), body: request.postDataJSON?.() });
    if (request.method() === "POST") {
      await route.fulfill({ json: { id: 99, name: "Cliente E2E" } });
      return;
    }
    await route.fulfill({ json: clientsPayload() });
  });
  await page.route("**/api/stock/alerts**", async (route) => {
    await route.fulfill({ json: stockAlertsPayload() });
  });
  await page.route("**/api/stock/movements**", async (route) => {
    await route.fulfill({ json: stockMovementsPayload() });
  });
  await page.route("**/api/stock/entry**", async (route) => {
    const request = route.request();
    calls.push({ method: request.method(), url: request.url(), body: request.postDataJSON?.() });
    await route.fulfill({ json: { status: "OK" } });
  });
  await page.route("**/api/employees**", async (route) => {
    await route.fulfill({ json: employeesPayload(activeSession) });
  });
  await page.route("**/api/backups/manual**", async (route) => {
    const request = route.request();
    calls.push({ method: request.method(), url: request.url(), body: request.postDataJSON?.() });
    await route.fulfill({ json: { status: "CONCLUIDO", id: 8, message: "Backup gerado com sucesso." } });
  });
  await page.route("**/api/backups**", async (route) => {
    const request = route.request();
    if (request.url().includes("/api/backups/manual")) {
      calls.push({ method: request.method(), url: request.url(), body: request.postDataJSON?.() });
      await route.fulfill({ json: { status: "CONCLUIDO", id: 8, message: "Backup gerado com sucesso." } });
      return;
    }
    await route.fulfill({ json: backupsPayload() });
  });
  await page.route("**/api/audit**", async (route) => {
    await route.fulfill({ json: auditPayload() });
  });
  await page.route("**/api/sync/monitor**", async (route) => {
    await route.fulfill({ json: syncMonitorPayload() });
  });
  await page.route("**/api/observability", async (route) => {
    await route.fulfill({ json: observabilityPayload() });
  });
  await page.route("**/api/sync/outbox/*/retry**", async (route) => {
    await route.fulfill({ json: { status: "RETRY_SCHEDULED", count: 1 } });
  });
}

function dashboardPayload() {
  return {
    activeStore: "Loja Web",
    lastSync: "2026-07-02 18:00",
    syncProgress: 100,
    stores: [{ id: "all", name: "Todas as lojas", isAll: true }, { id: "WEB", name: "Loja Web" }],
    metrics: [
      { label: "Faturamento", value: "R$ 1.250,00", trend: "Hoje" },
      { label: "Vendas pagas", value: "5", trend: "Hoje" },
      { label: "Ticket médio", value: "R$ 250,00", trend: "Hoje" },
      { label: "Estoque baixo", value: "1", trend: "Atenção" }
    ],
    revenueChart: [{ label: "Hoje", value: 1250, display: "R$ 1.250" }],
    topProducts: [{ label: "Produto Teste", quantity: 3, display: "3 itens" }],
    payments: [{ name: "Dinheiro", percent: 100, total: "R$ 1.250,00" }],
    salesByStore: [{ label: "Loja Web", sales: 5, display: "R$ 1.250,00" }],
    stockHealth: [{ label: "Estoque baixo", display: "1", tone: "warning" }]
  };
}

function alertsPayload() {
  return [
    {
      id: "stock-low-e2e",
      level: "warning",
      title: "Estoque baixo",
      message: "1 produto abaixo do mínimo.",
      actionLabel: "Repor estoque",
      actionUrl: "/estoque.html"
    }
  ];
}

function employeesPayload(activeSession) {
  return [
    {
      username: activeSession.username || "admin",
      displayName: activeSession.displayName || "Usuario Teste",
      role: activeSession.role || "ADMIN",
      active: true,
      lastLoginAt: "2026-07-13 15:00",
      permissions: activeSession.permissions || ["*"]
    },
    {
      username: "operador",
      displayName: "Operador E2E",
      role: "CAIXA",
      active: true,
      lastLoginAt: "2026-07-13 14:00",
      permissions: ["vendas.visualizar", "caixa.visualizar"]
    }
  ];
}

function auditPayload() {
  return [
    {
      store: "Loja Web",
      action: "SYNC_SUCCESS",
      time: "2026-07-02 18:00",
      createdAt: "2026-07-02 18:00:00",
      entityType: "sync_runs",
      entityId: "10",
      user: "PDV",
      description: "Dados recebidos.",
      value: "5 registros"
    }
  ];
}

function syncMonitorPayload() {
  return {
    pendingCount: 1,
    deliveredCount: 2,
    retryableErrorCount: 0,
    deadLetterCount: 0,
    oldestPending: { id: 10, entityType: "PRODUCT", status: "PENDING", createdAt: "2026-07-02 18:00" },
    recentErrors: [{ id: 20, storeId: "WEB", entityType: "PRODUCT", entityId: "P1", operation: "PRODUCT_UPSERT", status: "ERROR", errorCount: 1, lastError: "Tipo nao suportado" }],
    recentSyncRuns: [{ sourceId: "PDV-01", status: "SUCCESS", recordCount: 5, receivedAt: "2026-07-02 18:00" }]
  };
}

function observabilityPayload() {
  return {
    status: "UP",
    uptime: "5 min",
    memory: { used: "128 MB" },
    database: { status: "UP" }
  };
}

function productsPayload() {
  return [
    {
      store: "Loja Web",
      storeId: "WEB",
      code: "P-E2E",
      description: "Produto Teste",
      unit: "UN",
      price: "R$ 19,90",
      costPrice: "R$ 10,00",
      stock: "5",
      currentStock: "5",
      minimumStock: "1",
      category: "Geral",
      active: true
    }
  ];
}

function clientsPayload() {
  return [
    {
      store: "Loja Web",
      storeId: "WEB",
      id: 1,
      name: "Cliente Teste",
      cpfCnpj: "12345678900",
      phone: "(11) 99999-0000",
      email: "cliente@teste.com",
      address: "Rua Teste",
      active: true,
      createdAt: "2026-07-02"
    }
  ];
}

function stockAlertsPayload() {
  return [{ productCode: "P-E2E", product: "Produto Teste", currentStock: "5", minimumStock: "10" }];
}

function stockMovementsPayload() {
  return [
    { productCode: "P-E2E", type: "ENTRADA", quantity: "2", previousStock: "3", newStock: "5", reason: "Reposição", user: "admin", createdAt: "2026-07-02 18:00" }
  ];
}

function cashSessionsPayload() {
  return [
    {
      store: "Loja Web",
      code: "CX-1",
      operator: "Operador E2E",
      openedAt: "2026-07-02 09:00",
      closedAt: "-",
      status: "Aberto",
      expected: "R$ 500,00",
      informed: "-",
      difference: "-"
    }
  ];
}

function backupsPayload() {
  return [
    {
      id: 7,
      date: "2026-07-02 18:00",
      createdBy: "admin",
      fileName: "zentrix-backup-WEB-7.sql",
      size: "12.0 KB",
      rows: 42,
      status: "CONCLUIDO",
      fileExists: true,
      checksumValid: true,
      integrity: "Íntegro",
      checksum: "1234567890abcdef1234567890abcdef"
    }
  ];
}

function settingsPayload() {
  return {
    tenant: { id: "tenant-e2e", name: "Loja Web" },
    users: 2,
    stores: [{ id: "all", name: "Todas as lojas", isAll: true }, { id: "WEB", name: "Loja Web" }],
    api: "/api",
    sourceId: "PDV-01",
    lastSync: "2026-07-02 18:00"
  };
}
