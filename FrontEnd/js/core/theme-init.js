(function () {
  try {
    var theme = localStorage.getItem("zentrix-theme");
    if (theme === "dark" || theme === "light") {
      document.documentElement.dataset.theme = theme;
    }
  } catch (error) {
    // Mantem a pagina carregando quando o navegador bloqueia storage.
  }
})();
