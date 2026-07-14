(function () {
  "use strict";

  function generateReportFile(format, title, reportData, context) {
    const normalizedFormat = String(format || "CSV").toUpperCase();
    const safeReportData = sanitizeReportData(reportData);
    if (normalizedFormat === "PDF") {
      openPrintableReport(title, safeReportData, context);
      return;
    }
    if (normalizedFormat === "XLS" || normalizedFormat === "EXCEL") {
      downloadXlsReport(title, safeReportData, context);
      return;
    }
    if (normalizedFormat === "JSON") {
      downloadBlob(slug(title, context) + ".json", JSON.stringify(safeReportData, null, 2), "application/json;charset=utf-8");
      return;
    }
    downloadCsvReport(title, safeReportData, context);
  }

  function downloadCsvReport(title, reportData, context) {
    const rows = reportRows(reportData, context);
    const headers = reportHeaders(rows);
    const csv = [headers, ...rows.map((row) => headers.map((header) => row[header] ?? ""))]
      .map((row) => row.map((value) => csvCell(value, context)).join(";"))
      .join("\n");
    downloadBlob(slug(title, context) + ".csv", "\ufeff" + csv, "text/csv;charset=utf-8");
  }

  function downloadXlsReport(title, reportData, context) {
    const sections = reportSections(reportData, context);
    const tables = sections.map((section) => `<h2>${esc(section.title, context)}</h2>${reportTableHtml(section.rows, context)}`).join("");
    const html = `<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:Arial,sans-serif;color:#142033}h1{color:#0b4dd8}h2{margin-top:24px;color:#14233b}table{border-collapse:collapse;width:100%;margin-bottom:12px}th,td{border:1px solid #d7dee8;padding:8px;text-align:left;font-size:12px}th{background:#eaf3ff;color:#0b4dd8}.meta{color:#64748b}</style></head><body><h1>${esc(title, context)}</h1><p class="meta">Zentrix AppGestão - ${esc(periodLabel(context), context)} - ${esc(activeStoreName(context), context)}</p>${tables || "<p>Sem dados para o período escolhido.</p>"}</body></html>`;
    downloadBlob(slug(title, context) + ".xls", "\ufeff" + html, "application/vnd.ms-excel;charset=utf-8");
  }

  function openPrintableReport(title, reportData, context) {
    const sections = reportSections(reportData, context).map((section) => ({ ...section, rows: section.rows.slice(0, 120) }));
    const cards = Array.isArray(reportData && reportData.summaryCards) ? reportData.summaryCards : [];
    const diagnostics = Array.isArray(reportData && reportData.diagnostics) ? reportData.diagnostics : [];
    const printWindow = window.open("", "_blank");
    if (!printWindow) {
      downloadXlsReport(title, reportData, context);
      notify(context, "Pop-up bloqueado. Baixei uma versão em Excel do relatório.", "warning");
      return;
    }
    printWindow.document.write(`<!doctype html>
      <html><head><meta charset="utf-8"><title>${esc(title, context)}</title>
      <style>
        body{margin:0;background:#f4f8fc;color:#142033;font-family:Arial,sans-serif}
        .page{max-width:1100px;margin:0 auto;padding:32px}
        .hero{background:#0b4dd8;color:#fff;border-radius:18px;padding:28px;margin-bottom:18px}
        .hero h1{margin:0 0 8px;font-size:30px}.hero p{margin:0;color:#dceaff}
        .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}
        .card,.panel{background:#fff;border:1px solid #d9e6f5;border-radius:14px;padding:16px;box-shadow:0 8px 24px rgba(20,42,80,.08)}
        .card span{display:block;color:#64748b;font-size:12px}.card strong{display:block;font-size:22px;margin-top:6px}
        table{width:100%;border-collapse:collapse;background:#fff;border-radius:14px;overflow:hidden}
        th,td{border-bottom:1px solid #e4edf8;padding:10px;text-align:left;font-size:12px}th{background:#eaf3ff;color:#0b4dd8}
        .diag{margin:0;padding-left:18px;color:#45556b}.footer{margin-top:18px;color:#64748b;font-size:12px}
        @media print{body{background:#fff}.page{padding:0}.card,.panel{box-shadow:none}.no-print{display:none}}
      </style></head><body><main class="page">
        <section class="hero"><h1>${esc(title, context)}</h1><p>Zentrix AppGestão | ${esc(periodLabel(context), context)} | ${esc(activeStoreName(context), context)}</p></section>
        <section class="grid">${cards.slice(0, 4).map((card) => `<article class="card"><span>${esc(card.label, context)}</span><strong>${esc(card.value, context)}</strong><small>${esc(card.description || card.note || "", context)}</small></article>`).join("")}</section>
        ${sections.map((section) => `<section class="panel"><h2>${esc(section.title, context)}</h2>${reportTableHtml(section.rows, context)}</section>`).join("") || `<section class="panel"><h2>Dados do relatório</h2>${emptyState("Este relatório ainda não tem dados no período escolhido.", context)}</section>`}
        ${diagnostics.length ? `<section class="panel"><h2>Diagnóstico</h2><ul class="diag">${diagnostics.map((item) => `<li>${esc(item, context)}</li>`).join("")}</ul></section>` : ""}
        <p class="footer">Gerado em ${esc(new Date().toLocaleString("pt-BR"), context)}. Use Ctrl+P para salvar como PDF.</p>
        <button class="no-print" type="button" id="printReportButton">Imprimir ou salvar PDF</button>
      </main></body></html>`);
    printWindow.document.close();
    const printButton = printWindow.document.getElementById("printReportButton");
    if (printButton) {
      printButton.addEventListener("click", () => printWindow.print());
    }
    printWindow.focus();
    setTimeout(() => printWindow.print(), 350);
  }

  function reportSections(data, context) {
    if (Array.isArray(data)) {
      const rows = data.map((row) => flattenReportRow(row, context));
      return rows.length ? [{ title: "Detalhamento", rows }] : [];
    }
    if (!data || typeof data !== "object") return [];
    const sections = [];
    const summaryRows = Array.isArray(data.summaryCards) ? data.summaryCards.map((card) => flattenReportRow({
      Indicador: card.label,
      Valor: card.value,
      Descrição: card.description || card.note || "",
      Status: card.tone || ""
    }, context)) : [];
    if (summaryRows.length) sections.push({ title: "Resumo executivo", rows: summaryRows });
    const topRows = topProductsReportRows(data.topProducts, context);
    if (topRows.length) sections.push({ title: "Top produtos por quantidade vendida", rows: topRows });
    const paymentRows = paymentReportRows(data.payments, context);
    if (paymentRows.length) sections.push({ title: "Formas de pagamento", rows: paymentRows });
    const hasPrimaryRows = [data.rows, data.sessions, data.events, data.alerts, data.movements, data.reportCards]
      .some((rows) => Array.isArray(rows) && rows.length);
    const detailRows = reportRows(data, context);
    if (hasPrimaryRows && detailRows.length) sections.push({ title: "Detalhamento", rows: detailRows });
    return sections;
  }

  function topProductsReportRows(rows, context) {
    if (!Array.isArray(rows)) return [];
    return rows.map((row, index) => flattenReportRow({
      Posicao: index + 1,
      Produto: row.label || row.description || "-",
      Codigo: row.code || "",
      "Qtd. vendida": row.display || `${quantityLabel(row.quantity ?? row.value, context)} itens`,
      Vendas: row.sales ?? "",
      Faturamento: row.revenueDisplay || row.totalDisplay || (row.revenue != null ? formatCurrency(row.revenue, context) : row.total || "")
    }, context));
  }

  function paymentReportRows(rows, context) {
    if (!Array.isArray(rows)) return [];
    return rows.map((row) => flattenReportRow({
      Forma: row.label || row.name || row.payment_method || "-",
      Vendas: row.sales || row.sales_count || "",
      Total: row.display || row.total || row.value || ""
    }, context));
  }

  function reportTableHtml(rows, context) {
    const headers = reportHeaders(rows);
    if (!headers.length) return emptyState("Sem dados para o período escolhido.", context);
    return `<table><thead><tr>${headers.map((header) => `<th>${esc(header, context)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${headers.map((header) => `<td>${esc(row[header] ?? "", context)}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
  }

  function reportRows(data, context) {
    if (Array.isArray(data)) return data.map((row) => flattenReportRow(row, context));
    if (!data || typeof data !== "object") return [];
    const preferred = data.rows || data.sessions || data.events || data.alerts || data.movements || data.topProducts || data.payments || data.reportCards;
    if (Array.isArray(preferred) && preferred.length) return preferred.map((row) => flattenReportRow(row, context));
    if (Array.isArray(data.summaryCards) && data.summaryCards.length) {
      return data.summaryCards.map((card) => flattenReportRow({
        indicador: card.label,
        valor: card.value,
        descricao: card.description || card.note || "",
        status: card.tone || ""
      }, context));
    }
    return Object.entries(data)
      .filter(([, value]) => value == null || ["string", "number", "boolean"].includes(typeof value))
      .map(([key, value]) => ({ campo: key, valor: value }));
  }

  function flattenReportRow(row, context) {
    const output = {};
    Object.entries(row || {}).forEach(([key, value]) => {
      if (isHiddenReportField(key)) return;
      if (value == null) {
        output[reportFieldLabel(key)] = "";
      } else if (typeof value === "object") {
        output[reportFieldLabel(key)] = JSON.stringify(sanitizeReportData(value));
      } else {
        output[reportFieldLabel(key)] = normalizeText(String(value).replace(/<[^>]+>/g, ""), context);
      }
    });
    return output;
  }

  function sanitizeReportData(value) {
    if (Array.isArray(value)) {
      return value.map((item) => sanitizeReportData(item));
    }
    if (!value || typeof value !== "object") {
      return value;
    }
    const output = {};
    Object.entries(value).forEach(([key, item]) => {
      if (isHiddenReportField(key)) return;
      output[key] = sanitizeReportData(item);
    });
    return output;
  }

  function isHiddenReportField(key) {
    const normalized = String(key || "").trim();
    const lower = normalized.toLowerCase();
    return lower === "id"
      || lower === "tenantid"
      || lower === "tenant_id"
      || lower === "store"
      || lower === "storeid"
      || lower === "store_id"
      || lower === "sourceid"
      || lower === "source_id"
      || lower === "deviceid"
      || lower === "device_id"
      || lower === "entityid"
      || lower === "entity_id"
      || lower === "sessionid"
      || lower === "session_id"
      || lower === "saleid"
      || lower === "sale_id"
      || lower === "runid"
      || lower === "run_id"
      || lower === "stagingid"
      || lower === "staging_id"
      || lower.endsWith("_id")
      || /(^|[A-Z_\-\s])Id$/.test(normalized);
  }

  function reportFieldLabel(key) {
    const labels = {
      title: "Relatório",
      generatedAt: "Gerado em",
      period: "Período",
      periodLabel: "Período",
      formats: "Formatos",
      action: "Ação",
      actionCode: "Ação",
      module: "Módulo",
      date: "Data",
      time: "Hora",
      dateTime: "Data e hora",
      createdAt: "Criado em",
      updatedAt: "Atualizado em",
      user: "Usuário",
      description: "Descrição",
      riskLevel: "Nível",
      level: "Nível",
      status: "Status",
      value: "Valor",
      total: "Total",
      display: "Total",
      quantity: "Quantidade",
      paymentMethod: "Pagamento",
      operator: "Operador",
      customer: "Cliente",
      product: "Produto",
      productCode: "Código do produto",
      productName: "Produto"
    };
    return labels[key] || humanizeReportHeader(key);
  }

  function humanizeReportHeader(key) {
    return String(key || "")
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .replace(/[_-]+/g, " ")
      .trim()
      .replace(/\s+/g, " ")
      .replace(/^./, (char) => char.toUpperCase());
  }

  function reportHeaders(rows) {
    const headers = [];
    rows.forEach((row) => Object.keys(row).forEach((key) => {
      if (!headers.includes(key)) headers.push(key);
    }));
    return headers;
  }

  function downloadBlob(fileName, content, type) {
    const blob = new Blob([content], { type });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  function periodLabel(context) {
    if (context && typeof context.periodLabel === "function") return context.periodLabel();
    return "Hoje";
  }

  function activeStoreName(context) {
    if (context && typeof context.activeStoreName === "function") return context.activeStoreName();
    return "Todas as lojas";
  }

  function notify(context, message, level) {
    if (context && typeof context.renderToast === "function") {
      context.renderToast(message, level);
    }
  }

  function formatCurrency(value, context) {
    if (context && typeof context.formatCurrency === "function") return context.formatCurrency(value);
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(Number(value) || 0);
  }

  function quantityLabel(value, context) {
    if (context && typeof context.quantityLabel === "function") return context.quantityLabel(value);
    const number = Number(value);
    if (!Number.isFinite(number)) return "0";
    return number.toLocaleString("pt-BR", { maximumFractionDigits: 3 });
  }

  function emptyState(message, context) {
    return `<div class="empty-state"><strong>${esc(message || "Ainda não há informações para mostrar.", context)}</strong><span>Quando o PDV enviar novos dados, esta área será atualizada automaticamente.</span></div>`;
  }

  function csvCell(value, context) {
    return '"' + normalizeText(value, context).replace(/<[^>]+>/g, "").replace(/"/g, '""') + '"';
  }

  function slug(value, context) {
    return normalizeText(value, context).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "relatorio";
  }

  function esc(value, context) {
    return normalizeText(value, context).replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }

  function normalizeText(value, context) {
    if (context && typeof context.normalizeText === "function") return context.normalizeText(value);
    return String(value ?? "");
  }

  window.ZentrixReportExport = Object.freeze({ generateReportFile });
})();
