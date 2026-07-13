(function () {
  try {
    var theme = localStorage.getItem("zentrix-theme");
    document.documentElement.dataset.theme = theme === "light" ? "light" : "dark";
  } catch (error) {
    // Mantem a pagina carregando quando o navegador bloqueia storage.
  }
})();
