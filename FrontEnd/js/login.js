(function () {
  const form = document.getElementById('loginForm');
  if (!form) return;

  const LOGIN_TIMEOUT_MS = 5000;
  const HEALTH_TIMEOUT_MS = 2500;
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
        const response = await fetchWithTimeout(base + '/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify(credentials)
        }, LOGIN_TIMEOUT_MS);

        if (!response.ok) {
          throw new LoginHttpError(await loginErrorMessage(response));
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
      'Não conseguimos conectar ao Zentrix agora. Verifique se o servidor está ligado e tente novamente.'
    );
  }

  class LoginHttpError extends Error {}

  async function loginErrorMessage(response) {
    const status = response.status;
    const serverMessage = await responseMessage(response);
    if (serverMessage && status !== 401) {
      return serverMessage;
    }
    if (status === 401) {
      return 'Usuário ou senha inválidos.';
    }
    if (status === 429) {
      return 'Muitas tentativas. Aguarde alguns minutos e tente novamente.';
    }
    if (status === 403) {
      return 'Este usuário não tem permissão para acessar o painel. Entre com uma conta autorizada ou peça liberação ao responsável.';
    }
    if (status >= 500) {
      return 'O sistema está iniciando ou passou por uma instabilidade. Tente novamente em instantes.';
    }
    return 'Não foi possível entrar agora. Confira os dados informados.';
  }

  async function responseMessage(response) {
    try {
      const contentType = response.headers.get('Content-Type') || '';
      if (contentType.toLowerCase().includes('application/json')) {
        const body = await response.json();
        return body.message || body.error || body.detail || '';
      }
      const text = await response.text();
      if (text && text.toLowerCase().includes('invalid cors request')) {
        return 'Este endereço ainda não foi liberado para acessar o sistema. Peça ao responsável para liberar o domínio no Zentrix Web.';
      }
      return text && text.length < 180 ? text : '';
    } catch (error) {
      return '';
    }
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
        const response = await fetchWithTimeout(base + '/health', { credentials: 'include' }, HEALTH_TIMEOUT_MS);
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
    return ['/api'];
  }

  function rememberApiBase(base) {
    if (window.ZentrixApiBase && typeof window.ZentrixApiBase.rememberApiBase === 'function') {
      window.ZentrixApiBase.rememberApiBase(base);
      return;
    }
    try {
      localStorage.removeItem('zentrix-api-base');
    } catch (error) {
      // Mantem o login funcionando quando storage estiver restrito.
    }
  }

  async function fetchWithTimeout(url, options, timeoutMs) {
    if (!window.AbortController || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      return fetch(url, options);
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, { ...(options || {}), signal: controller.signal });
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function writeSession(session) {
    try {
      sessionStorage.setItem('zentrix-session', JSON.stringify(session));
      localStorage.removeItem('zentrix-session');
    } catch (error) {
      throw new Error('O navegador bloqueou o armazenamento da sessão. Habilite o armazenamento do site e tente novamente.');
    }
  }
})();
