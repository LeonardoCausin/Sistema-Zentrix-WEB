import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const apiBaseScript = readFileSync(join(process.cwd(), "js", "core", "api-base.js"), "utf8");

const session = {
  token: "e2e-token",
  displayName: "Administrador",
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

  const devPorts = new Set(["5500", "5501", "5502", "5173", "3000"]);
  const localHost = result.hostname === "localhost" || result.hostname === "127.0.0.1";
  const expected = localHost && devPorts.has(result.port)
    ? `http://${result.hostname}:8080/api`
    : `${result.origin}/api`;

  expect(result.apiBase).toBe(expected);
  expect(result.fallbacks).toContain(expected);
});

test("same-origin app ignores stale stored api base", async ({ page }) => {
  await page.route("https://gestao.zentrixsystems.com.br/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      body: `<!doctype html><meta charset="UTF-8"><script>localStorage.setItem("zentrix-api-base","https://api.zentrixsystems.com.br/api");</script><script>${apiBaseScript}</script>`
    });
  });

  await page.goto("https://gestao.zentrixsystems.com.br/__api_base_test.html");

  const apiBase = await page.evaluate(() => window.ZentrixApiBase.getApiBase());
  expect(apiBase).toBe("https://gestao.zentrixsystems.com.br/api");
});

test("split frontend tunnel points to the public backend api", async ({ page }) => {
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

  expect(result.apiBase).toBe("https://api.zentrixsystems.com.br/api");
  expect(result.fallbacks[0]).toBe("https://api.zentrixsystems.com.br/api");
});

test("login falls back from a stale api base without showing fetch failure", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("zentrix-api-base", "http://127.0.0.1:9999/api");
  });
  await page.route("**/api/auth/login", async (route) => {
    if (route.request().url().includes("127.0.0.1:9999")) {
      await route.abort();
      return;
    }
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

test("dashboard loads an authenticated management flow", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/dashboard.html");
  await expect(page.getByText("Faturamento")).toBeVisible();
  await expect(page.locator(".metric-value", { hasText: "R$ 1.250,00" })).toBeVisible();
  await expect(page.getByText("Produtos mais vendidos")).toBeVisible();
});

test("authenticated top bar shows notifications and account menu", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/dashboard.html");

  await expect(page.locator("#themeButton")).toHaveCount(0);
  await expect(page.locator("[data-action='toggle-notifications']")).toBeVisible();
  await expect(page.locator("[data-action='toggle-user-menu']")).toContainText("Administrador");

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

test("audit page shows the sync outbox monitor", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/auditoria.html");
  await expect(page.getByText("Monitor de sincronização")).toBeVisible();
  await expect(page.getByText("Fila Web → PDV")).toBeVisible();
  await expect(page.locator(".list-title", { hasText: "DELIVERED" })).toBeVisible();
});

test("sync center shows diagnostics and retries a failed outbox item", async ({ page }) => {
  await mockPanelApi(page);
  await page.goto("/FrontEnd/pages/sincronizacao.html");
  await expect(page.getByText("Erros recentes")).toBeVisible();
  await expect(page.getByText("Tipo não suportado")).toBeVisible();
  await page.locator('[data-action="sync-retry"]').first().click();
  await expect(page.getByText("Item reenfileirado para o PDV.")).toBeVisible();
});

async function mockPanelApi(page) {
  await page.addInitScript((value) => {
    sessionStorage.setItem("zentrix-session", JSON.stringify(value));
    localStorage.setItem("zentrix-api-base", "http://127.0.0.1:8080/api");
  }, session);

  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({ json: session });
  });
  await page.route("**/api/stores", async (route) => {
    await route.fulfill({ json: [{ id: "all", name: "Todas as lojas", isAll: true }, { id: "WEB", name: "Loja Web" }] });
  });
  await page.route("**/api/settings**", async (route) => {
    await route.fulfill({ json: settingsPayload() });
  });
  await page.route("**/api/dashboard**", async (route) => {
    await route.fulfill({ json: dashboardPayload() });
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

function auditPayload() {
  return [
    {
      store: "Loja Web",
      action: "SYNC_SUCCESS",
      time: "2026-07-02 18:00",
      user: "PDV",
      description: "Sincronização recebida.",
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

function settingsPayload() {
  return {
    tenant: { id: "tenant-e2e", name: "Loja Web" },
    users: 2,
    stores: [{ id: "all", name: "Todas as lojas", isAll: true }, { id: "WEB", name: "Loja Web" }],
    api: "http://localhost:8080/api",
    sourceId: "PDV-01",
    lastSync: "2026-07-02 18:00"
  };
}
