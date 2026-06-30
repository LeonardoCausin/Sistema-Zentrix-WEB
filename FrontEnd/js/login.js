(function () {
  const form = document.getElementById('loginForm');
  if (!form) return;

  const userField = document.getElementById('loginUser');
  const passwordField = document.getElementById('loginPassword');
  const togglePasswordButton = document.getElementById('togglePasswordButton');
  const errorBox = document.getElementById('loginError');
  const submitButton = form.querySelector('button[type="submit"]');
  const defaultApiBase = 'http://localhost:8080/api';
  const storedApiBase = localStorage.getItem('zentrix-api-base');
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
      const session = await loginWithFallback({
        email: userField.value.trim(),
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
          if (response.status === 401) {
            throw new Error('Usuário ou senha inválidos.');
          }
          if (response.status === 429) {
            throw new Error('Muitas tentativas. Aguarde alguns minutos e tente novamente.');
          }
          if (response.status === 403) {
            throw new Error('Acesso restrito a usuários administradores.');
          }
          throw new Error('Não foi possível entrar agora. Verifique se o sistema está aberto.');
        }

        localStorage.setItem('zentrix-api-base', base);
        return response.json();
      } catch (error) {
        lastError = error;
      }
    }

    throw lastError || new Error('Não foi possível conectar ao sistema.');
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
          localStorage.setItem('zentrix-api-base', base);
          return response;
        }
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error('Sistema indisponível');
  }

  function apiBases() {
    return Array.from(new Set([storedApiBase, defaultApiBase].filter(Boolean)));
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
