(function () {
  "use strict";

  const permissions = Object.freeze([
    ["Operacao", "dashboard.visualizar", "Dashboard"],
    ["Operacao", "vendas.visualizar", "Ver vendas"],
    ["Operacao", "vendas.cancelar", "Cancelar vendas"],
    ["Caixa", "caixa.visualizar", "Ver caixa"],
    ["Caixa", "caixa.abrir", "Abrir caixa"],
    ["Caixa", "caixa.fechar", "Fechar caixa"],
    ["Caixa", "caixa.sangria", "Sangria"],
    ["Caixa", "caixa.suprimento", "Suprimento"],
    ["Produtos", "produtos.visualizar", "Ver produtos"],
    ["Produtos", "produtos.criar", "Criar produtos"],
    ["Produtos", "produtos.editar", "Editar produtos"],
    ["Produtos", "produtos.desativar", "Inativar produtos"],
    ["Estoque", "estoque.visualizar", "Ver estoque"],
    ["Estoque", "estoque.movimentar", "Movimentar estoque"],
    ["Clientes", "clientes.visualizar", "Ver clientes"],
    ["Clientes", "clientes.criar", "Criar clientes"],
    ["Clientes", "clientes.editar", "Editar clientes"],
    ["Equipe", "funcionarios.visualizar", "Ver funcionarios"],
    ["Equipe", "funcionarios.criar", "Criar funcionarios"],
    ["Equipe", "funcionarios.editar", "Editar funcionarios"],
    ["Equipe", "funcionarios.permissoes", "Alterar permissoes"],
    ["Financeiro", "financeiro.visualizar", "Ver financeiro"],
    ["Financeiro", "financeiro.editar", "Editar financeiro"],
    ["Relatorios", "relatorios.visualizar", "Ver relatorios"],
    ["Seguranca", "auditoria.visualizar", "Ver auditoria"],
    ["Seguranca", "backups.gerar", "Gerar backups"],
    ["Seguranca", "backups.restaurar", "Restaurar backups"],
    ["Seguranca", "configuracoes.visualizar", "Ver configuracoes"],
    ["Seguranca", "configuracoes.editar", "Editar configuracoes"]
  ]);
  const rolePresets = Object.freeze({
    ADMIN: EMPLOYEE_PERMISSIONS.map((item) => item[1]),
    GERENTE: EMPLOYEE_PERMISSIONS.map((item) => item[1]).filter((value) => !["funcionarios.permissoes", "backups.restaurar", "configuracoes.editar"].includes(value)),
    CAIXA: ["dashboard.visualizar", "vendas.visualizar", "caixa.visualizar", "caixa.abrir", "caixa.sangria", "caixa.suprimento", "clientes.visualizar", "clientes.criar"],
    ESTOQUISTA: ["dashboard.visualizar", "produtos.visualizar", "produtos.criar", "produtos.editar", "estoque.visualizar", "estoque.movimentar", "relatorios.visualizar"],
    FINANCEIRO: ["dashboard.visualizar", "vendas.visualizar", "caixa.visualizar", "financeiro.visualizar", "financeiro.editar", "relatorios.visualizar"],
    CONSULTA: ["dashboard.visualizar", "vendas.visualizar", "caixa.visualizar", "produtos.visualizar", "estoque.visualizar", "clientes.visualizar", "financeiro.visualizar", "relatorios.visualizar"]
  });

  window.ZentrixEmployeePermissions = Object.freeze({
    permissions,
    rolePresets
  });
})();
