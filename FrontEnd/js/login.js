(function () {
  const form = document.getElementById('loginForm');
  if (!form) return;

  const userField = document.getElementById('loginUser');
  const passwordField = document.getElementById('loginPassword');
  const togglePasswordButton = document.getElementById('togglePasswordButton');
  const errorBox = document.getElementById('loginError');
  const submitButton = form.querySelector('button[type="submit"]');
  const previewStatus = document.getElementById('previewStatus');
  const previewService = document.getElementById('previewService');
  const previewLastSync = document.getElementById('previewLastSync');

  refreshPreview();

  if (togglePasswordButton && passwordField) {
    togglePasswordButton.addEventListener('click', function () {
      const showing = passwordField.type === 'text';
      passwordField.type = showing ? 'password' : 'text';
      togglePasswordButton.textContent = showing ? 'Mostrar' : 'Ocultar';
    });
  }

  function showError(message) {
    if (!errorBox) return;
    errorBox.textContent = message;
    errorBox.hidden = false;
  }

  function clearError() {
    if (!errorBox) return;
    errorBox.textContent = '';
    errorBox.hidden = true;
  }

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    clearError();

    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = 'Entrando...';
    }

    try {
      const login = userField.value.trim();
      const session = await loginWithFallback({
        email: login,
        username: login,
        password: passwordField.value
      });

      writeSession(session);
      window.location.href = form.getAttribute('action') || 'pages/dashboard.html';
    } catch (error) {
      showError(error.message || 'Não foi possível entrar no painel.');
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = 'Acessar AppGestão';
      }
    }
  });

  async function loginWithFallback(credentials) {
    const bases = apiBases();
    let lastError = null;

    for (const base of bases) {
      try {
        const response = await fetch(base + '/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(credentials)
        });

        if (!response.ok) {
          throw new LoginHttpError(loginErrorMessage(response.status));
        }

        rememberApiBase(base);
        return response.json();
      } catch (error) {
        if (error instanceof LoginHttpError) {
          throw error;
        }
        lastError = error;
      }
    }

    throw new Error(
      'Não foi possível conectar ao sistema. Abra pelo http://localhost:8080/ e confira se o backend está iniciado.'
    );
  }

  class LoginHttpError extends Error {}

  function loginErrorMessage(status) {
    if (status === 401) {
      return 'Usuário ou senha inválidos.';
    }
    if (status === 429) {
      return 'Muitas tentativas. Aguarde alguns minutos e tente novamente.';
    }
    if (status === 403) {
      return 'Acesso restrito a usuários administradores.';
    }
    if (status >= 500) {
      return 'Não foi possível entrar agora. O servidor está iniciando ou indisponível.';
    }
    return 'Não foi possível entrar agora. Confira os dados informados.';
  }

  async function refreshPreview() {
    if (!previewStatus || !previewLastSync) return;
    try {
      const response = await fetchFirstHealth();
      const health = await response.json();
      previewStatus.className = 'status-pill ' + (health.status === 'UP' ? 'success' : 'warning');
      previewStatus.textContent = health.status === 'UP' ? 'Sistema online' : 'Sistema iniciando';
      if (previewService) previewService.textContent = 'Zentrix AppGestão';
      previewLastSync.textContent = health.lastSync || 'Aguardando primeira sincronização';
    } catch (error) {
      previewStatus.className = 'status-pill warning';
      previewStatus.textContent = 'Sistema offline';
      previewLastSync.textContent = 'Abra o Zentrix AppGestão';
    }
  }

  async function fetchFirstHealth() {
    let lastError = null;
    for (const base of apiBases()) {
      try {
        const response = await fetch(base + '/health');
        if (response.ok) {
          rememberApiBase(base);
          return response;
        }
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error('Sistema indisponível');
  }

  function apiBases() {
    if (window.ZentrixApiBase && typeof window.ZentrixApiBase.getFallbackBases === 'function') {
      return window.ZentrixApiBase.getFallbackBases();
    }
    return ['http://localhost:8080/api', 'http://127.0.0.1:8080/api'];
  }

  function rememberApiBase(base) {
    if (window.ZentrixApiBase && typeof window.ZentrixApiBase.rememberApiBase === 'function') {
      window.ZentrixApiBase.rememberApiBase(base);
      return;
    }
    localStorage.setItem('zentrix-api-base', base);
  }

  function writeSession(session) {
    try {
      sessionStorage.setItem('zentrix-session', JSON.stringify(session));
      localStorage.removeItem('zentrix-session');
    } catch (error) {
      localStorage.setItem('zentrix-session', JSON.stringify(session));
    }
  }
})();
