(function () {
  const root = document.documentElement;
  const body = document.body;
  const themeButton = document.getElementById("themeButton");
  const menuButton = document.getElementById("menuButton");
  const closeSidebarButton = document.getElementById("closeSidebarButton");
  const sidebarBackdrop = document.getElementById("sidebarBackdrop");
  const storedTheme = localStorage.getItem("zentrix-theme");
  const session = localStorage.getItem("zentrix-session");

  if (body.classList.contains("is-authenticated") && !session) {
    const loginPath = location.pathname.includes("/pages/") ? "../index.html" : "index.html";
    window.location.replace(loginPath);
    return;
  }

  if (storedTheme === "dark" || storedTheme === "light") {
    root.dataset.theme = storedTheme;
  }

  if (themeButton) {
    themeButton.addEventListener("click", () => {
      const nextTheme = root.dataset.theme === "dark" ? "light" : "dark";
      root.dataset.theme = nextTheme;
      localStorage.setItem("zentrix-theme", nextTheme);
    });
  }

  if (menuButton) {
    menuButton.addEventListener("click", () => body.classList.add("sidebar-open"));
  }

  if (closeSidebarButton) {
    closeSidebarButton.addEventListener("click", () => body.classList.remove("sidebar-open"));
  }

  if (sidebarBackdrop) {
    sidebarBackdrop.addEventListener("click", () => body.classList.remove("sidebar-open"));
  }

  document.querySelectorAll('a[href$="index.html"]').forEach((link) => {
    link.addEventListener("click", () => localStorage.removeItem("zentrix-session"));
  });

  window.zentrixApi = async function zentrixApi(path, options) {
    const storedSession = JSON.parse(localStorage.getItem("zentrix-session") || "null");
    const apiBase = localStorage.getItem("zentrix-api-base") || "http://localhost:8080/api";
    const response = await fetch(apiBase + path, {
      ...(options || {}),
      headers: {
        "Content-Type": "application/json",
        Authorization: storedSession ? "Bearer " + storedSession.token : "",
        ...((options && options.headers) || {})
      }
    });
    if (response.status === 401) {
      localStorage.removeItem("zentrix-session");
      window.location.replace(location.pathname.includes("/pages/") ? "../index.html" : "index.html");
      throw new Error("Sessao expirada");
    }
    if (!response.ok) {
      throw new Error("Falha ao carregar dados da API");
    }
    return response.json();
  };
})();
