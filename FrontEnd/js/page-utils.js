(function () {
  function formatCurrency(value) {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(Number(value) || 0);
  }

  function quantityLabel(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "0";
    return number.toLocaleString("pt-BR", { maximumFractionDigits: 3 });
  }

  function sumMoney(values) {
    return values.reduce((total, value) => total + moneyValue(value), 0);
  }

  function moneyValue(value) {
    const normalized = String(value || "0").replace(/[^\d,-]/g, "").replace(/\./g, "").replace(",", ".");
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function decimalField(value) {
    const text = String(value || "").trim();
    if (!text) return 0;
    const normalized = text.includes(",")
      ? text.replace(/[^\d,.-]/g, "").replace(/\./g, "").replace(",", ".")
      : text.replace(/[^\d.-]/g, "");
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function todayDateValue() {
    const now = new Date();
    const offset = now.getTimezoneOffset() * 60000;
    return new Date(now.getTime() - offset).toISOString().slice(0, 10);
  }

  function initials(value) {
    const words = normalizeText(value || "").trim().split(/\s+/).filter(Boolean);
    if (!words.length) return "ZT";
    return words.slice(0, 2).map((word) => word[0]).join("").toUpperCase();
  }

  function greeting() {
    const hour = new Date().getHours();
    if (hour < 12) return "Bom dia";
    if (hour < 18) return "Boa tarde";
    return "Boa noite";
  }

  function normalizeKey(value) {
    return String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
  }

  function normalizeText(value) {
    let text = String(value ?? "");
    const replacements = [
      [/\bZentrix Web\b/g, "Zentrix AppGest\u00e3o"],
      [/\bExtensao\b/g, "Extens\u00e3o"],
      [/\bSincronizacao\b/g, "Sincroniza\u00e7\u00e3o"],
      [/\bsincronizacao\b/g, "sincroniza\u00e7\u00e3o"],
      [/\bHistorico\b/g, "Hist\u00f3rico"],
      [/\bhistorico\b/g, "hist\u00f3rico"],
      [/\bConfiguracoes\b/g, "Configura\u00e7\u00f5es"],
      [/\bconfiguracoes\b/g, "configura\u00e7\u00f5es"],
      [/\bFuncionarios\b/g, "Funcion\u00e1rios"],
      [/\bfuncionarios\b/g, "funcion\u00e1rios"],
      [/\bRelatorios\b/g, "Relat\u00f3rios"],
      [/\brelatorios\b/g, "relat\u00f3rios"],
      [/\bOperacao\b/g, "Opera\u00e7\u00e3o"],
      [/\boperacao\b/g, "opera\u00e7\u00e3o"],
      [/\bMes\b/g, "M\u00eas"],
      [/\bMovimentacoes\b/g, "Movimenta\u00e7\u00f5es"],
      [/\bmovimentacoes\b/g, "movimenta\u00e7\u00f5es"],
      [/\bDecisoes\b/g, "Decis\u00f5es"],
      [/\bdecisoes\b/g, "decis\u00f5es"],
      [/\bUsuario\b/g, "Usu\u00e1rio"],
      [/\busuario\b/g, "usu\u00e1rio"],
      [/\bUsuarios\b/g, "Usu\u00e1rios"],
      [/\busuarios\b/g, "usu\u00e1rios"],
      [/\bSessao\b/g, "Sess\u00e3o"],
      [/\bsessoes\b/g, "sess\u00f5es"],
      [/\bSessoes\b/g, "Sess\u00f5es"],
      [/\bNao\b/g, "N\u00e3o"],
      [/\bnao\b/g, "n\u00e3o"],
      [/\bpossivel\b/g, "poss\u00edvel"],
      [/\binvalidos\b/g, "inv\u00e1lidos"],
      [/\besta\b/g, "est\u00e1"],
      [/\bindisponivel\b/g, "indispon\u00edvel"],
      [/\bmedio\b/g, "m\u00e9dio"],
      [/\bcriticos\b/g, "cr\u00edticos"],
      [/\bSaudavel\b/g, "Saud\u00e1vel"],
      [/\bperiodo\b/g, "per\u00edodo"],
      [/\bPeriodo\b/g, "Per\u00edodo"],
      [/\bCodigo\b/g, "C\u00f3digo"],
      [/\bpreco\b/g, "pre\u00e7o"],
      [/\bPreco\b/g, "Pre\u00e7o"],
      [/\bMinimo\b/g, "M\u00ednimo"],
      [/\bminimo\b/g, "m\u00ednimo"],
      [/\bEndereco\b/g, "Endere\u00e7o"],
      [/\bendereco\b/g, "endere\u00e7o"],
      [/\bAcao\b/g, "A\u00e7\u00e3o"],
      [/\bAcoes\b/g, "A\u00e7\u00f5es"],
      [/\bacoes\b/g, "a\u00e7\u00f5es"],
      [/\bDescricao\b/g, "Descri\u00e7\u00e3o"],
      [/\bdescricao\b/g, "descri\u00e7\u00e3o"],
      [/\bDetalhes\b/g, "Detalhes"],
      [/\bSaude\b/g, "Sa\u00fade"],
      [/\bAtualizacao\b/g, "Atualiza\u00e7\u00e3o"],
      [/\batualizacao\b/g, "atualiza\u00e7\u00e3o"],
      [/\bÚltima\b/g, "\u00daltima"],
      [/\bultima\b/g, "\u00faltima"],
      [/\bParticipacao\b/g, "Participa\u00e7\u00e3o"],
      [/\bCartao\b/g, "Cart\u00e3o"],
      [/\bConcluida\b/g, "Conclu\u00edda"],
      [/\bpermissoes\b/g, "permiss\u00f5es"],
      [/\bPermissoes\b/g, "Permiss\u00f5es"],
      [/\bsaidas\b/g, "sa\u00eddas"],
      [/\bSa?das\b/g, "Sa\u00eddas"],
      [/\bNiveis\b/g, "N\u00edveis"],
      [/\bsensiveis\b/g, "sens\u00edveis"],
      [/\bSensiveis\b/g, "Sens\u00edveis"],
      [/\bExportacoes\b/g, "Exporta\u00e7\u00f5es"],
      [/\bseguranca\b/g, "seguran\u00e7a"],
      [/\bSeguranca\b/g, "Seguran\u00e7a"],
      [/\bdiario\b/g, "di\u00e1rio"]
    ];
    replacements.forEach(([pattern, replacement]) => {
      text = text.replace(pattern, replacement);
    });
    return text;
  }

  function esc(value) {
    return normalizeText(value).replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }

  function escAttr(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#039;"
    })[char]);
  }

  window.ZentrixPageUtils = Object.freeze({
    decimalField,
    esc,
    escAttr,
    formatCurrency,
    greeting,
    initials,
    moneyValue,
    normalizeKey,
    normalizeText,
    quantityLabel,
    sumMoney,
    todayDateValue
  });
})();
