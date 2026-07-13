(function () {
  try {
    var theme = localStorage.getItem("zentrix-theme");
    var explicitChoice = localStorage.getItem("zentrix-theme-user-choice") === "true";
    document.documentElement.dataset.theme = explicitChoice && theme === "light" ? "light" : "dark";
  } catch (error) {
    // Mantem a pagina carregando quando o navegador bloqueia storage.
  }
})();
