(function () {
  function create(context) {
    const ctx = context || {};

    function setupCsvExport(buttonId, title, headers, rows) {
      ctx.saveViewState(ctx.currentPageName(), "csv:" + buttonId, { title, headers, rows });
      const button = document.getElementById(buttonId);
      if (!button) return;
      wireCsvExportButton(button, title, headers, rows);
    }

    function restoreCsvExports(page) {
      ctx.viewHost.querySelectorAll("button[id^='export-']").forEach((button) => {
        const payload = ctx.readViewState(page, "csv:" + button.id);
        if (payload) {
          wireCsvExportButton(button, payload.title, payload.headers || [], payload.rows || []);
        }
      });
    }

    function wireCsvExportButton(button, title, headers, rows) {
      if (button.dataset.exportReady === "true") return;
      button.dataset.exportReady = "true";
      button.disabled = !Array.isArray(rows) || rows.length === 0;
      button.addEventListener("click", () => {
        downloadCsvPayload(title, headers, rows);
      });
    }

    function downloadCsvPayload(title, headers, rows) {
      const csv = [headers, ...rows].map((row) => row.map((value) => csvCell(ctx.normalizeText(value))).join(";")).join("\n");
      const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = title.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-") + ".csv";
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(link.href);
    }

    function csvCell(value) {
      return '"' + String(value ?? "").replace(/<[^>]+>/g, "").replace(/"/g, '""') + '"';
    }

    return Object.freeze({
      downloadCsvPayload,
      restoreCsvExports,
      setupCsvExport
    });
  }

  window.ZentrixPageCsv = Object.freeze({ create });
})();
