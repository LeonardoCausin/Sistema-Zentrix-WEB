(function () {
  const form = document.getElementById('loginForm');
  if (!form) return;

  const userField = document.getElementById('loginUser');
  const passwordField = document.getElementById('loginPassword');
  const errorBox = document.getElementById('loginError');
  const submitButton = form.querySelector('button[type="submit"]');
  const apiBase = localStorage.getItem('zentrix-api-base') || 'http://localhost:8080/api';
  const previewStatus = document.getElementById('previewStatus');
  const previewService = document.getElementById('previewService');
  const previewLastSync = document.getElementById('previewLastSync');

  refreshPreview();

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
      const response = await fetch(apiBase + '/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: userField.value.trim(),
          password: passwordField.value
        })
      });

      if (!response.ok) {
        throw new Error('Usuario ou senha invalidos.');
      }

      const session = await response.json();
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

  async function refreshPreview() {
    if (!previewStatus || !previewLastSync) return;
    try {
      const response = await fetch(apiBase + '/health');
      if (!response.ok) throw new Error('API indisponivel');
      const health = await response.json();
      previewStatus.className = 'status-pill ' + (health.status === 'UP' ? 'success' : 'warning');
      previewStatus.textContent = health.status === 'UP' ? 'API online' : 'API degradada';
      if (previewService) previewService.textContent = health.service || 'Zentrix Web';
      previewLastSync.textContent = health.lastSync || 'Aguardando primeira sincronizacao';
    } catch (error) {
      previewStatus.className = 'status-pill warning';
      previewStatus.textContent = 'API offline';
      previewLastSync.textContent = 'Verifique o backend';
    }
  }
})();
