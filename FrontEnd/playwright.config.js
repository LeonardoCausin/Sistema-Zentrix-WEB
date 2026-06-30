import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30000,
  use: {
    baseURL: process.env.ZENTRIX_FRONTEND_URL || "http://127.0.0.1:5500",
    trace: "retain-on-failure"
  }
});
