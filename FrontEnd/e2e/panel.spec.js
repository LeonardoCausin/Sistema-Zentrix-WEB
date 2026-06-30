import { expect, test } from "@playwright/test";

test("login page loads the Zentrix AppGestao shell", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#loginForm")).toBeVisible();
  await expect(page.locator("#loginUser")).toBeVisible();
  await expect(page.locator("#loginPassword")).toBeVisible();
});

test("authenticated page protects itself without local session", async ({ page }) => {
  await page.goto("/FrontEnd/pages/dashboard.html");
  await expect(page).toHaveURL(/index\.html$/);
});
