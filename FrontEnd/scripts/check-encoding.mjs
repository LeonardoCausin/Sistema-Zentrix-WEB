import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const root = fileURLToPath(new URL("..", import.meta.url));
const ignoredDirs = new Set(["node_modules", "test-results"]);
const checkedExtensions = new Set([".html", ".js", ".css", ".md", ".txt"]);
const mojibakePattern = /[\u00c3\u00c2\ufffd]|\u00e2[\u0080-\u20ac]/;
const findings = [];

function extensionOf(file) {
  const index = file.lastIndexOf(".");
  return index >= 0 ? file.slice(index).toLowerCase() : "";
}

function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!ignoredDirs.has(entry.name)) walk(join(dir, entry.name));
      continue;
    }
    if (!checkedExtensions.has(extensionOf(entry.name))) continue;
    const file = join(dir, entry.name);
    const lines = readFileSync(file, "utf8").split(/\r?\n/);
    lines.forEach((line, index) => {
      if (mojibakePattern.test(line)) {
        findings.push(`${file}:${index + 1}: ${line.trim().slice(0, 160)}`);
      }
    });
  }
}

walk(root);

if (findings.length) {
  console.error("Encoding quebrado encontrado. Corrija antes de publicar:");
  console.error(findings.join("\n"));
  process.exit(1);
}

console.log("Encoding OK");
