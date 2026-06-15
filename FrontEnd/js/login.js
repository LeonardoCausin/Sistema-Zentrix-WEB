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

      localStorage.setItem('zentrix-session', JSON.stringify(session));
      window.location.href = form.getAttribute('action') || 'pages/dashboard.html';
    } catch (error) {
      showError(error.message || 'Nao foi possivel entrar no painel.');
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = 'Entrar no painel';
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
            throw new Error('Usuario ou senha invalidos.');
          }
          if (response.status === 429) {
            throw new Error('Muitas tentativas. Aguarde alguns minutos e tente novamente.');
          }
          throw new Error('Nao foi possivel entrar agora. Verifique se o sistema esta aberto.');
        }

        localStorage.setItem('zentrix-api-base', base);
        return response.json();
      } catch (error) {
        lastError = error;
      }
    }

    throw lastError || new Error('Nao foi possivel conectar ao sistema.');
  }

  async function refreshPreview() {
    if (!previewStatus || !previewLastSync) return;
    try {
      const response = await fetchFirstHealth();
      const health = await response.json();
      previewStatus.className = 'status-pill ' + (health.status === 'UP' ? 'success' : 'warning');
      previewStatus.textContent = health.status === 'UP' ? 'Sistema online' : 'Sistema iniciando';
      if (previewService) previewService.textContent = 'Zentrix Web';
      previewLastSync.textContent = health.lastSync || 'Aguardando primeira sincronizacao';
    } catch (error) {
      previewStatus.className = 'status-pill warning';
      previewStatus.textContent = 'Sistema offline';
      previewLastSync.textContent = 'Abra o Zentrix Web';
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
    throw lastError || new Error('Sistema indisponivel');
  }

  function apiBases() {
    return Array.from(new Set([storedApiBase, defaultApiBase].filter(Boolean)));
  }
})();
