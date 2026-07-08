(function () {
  "use strict";

  const permissions = Object.freeze([
    ["Operação", "dashboard.visualizar", "Dashboard"],
    ["Operação", "vendas.visualizar", "Ver vendas"],
    ["Operação", "vendas.cancelar", "Cancelar vendas"],
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
    ["Equipe", "funcionarios.visualizar", "Ver funcionários"],
    ["Equipe", "funcionarios.criar", "Criar funcionários"],
    ["Equipe", "funcionarios.editar", "Editar funcionários"],
    ["Equipe", "funcionarios.permissoes", "Alterar permissões"],
    ["Financeiro", "financeiro.visualizar", "Ver financeiro"],
    ["Financeiro", "financeiro.editar", "Editar financeiro"],
    ["Relatórios", "relatorios.visualizar", "Ver relatórios"],
    ["Segurança", "auditoria.visualizar", "Ver auditoria"],
    ["Segurança", "backups.gerar", "Gerar backups"],
    ["Segurança", "backups.restaurar", "Restaurar backups"],
    ["Segurança", "configuracoes.visualizar", "Ver configurações"],
    ["Segurança", "configuracoes.editar", "Editar configurações"]
  ]);
  const rolePresets = Object.freeze({
    ADMIN: permissions.map((item) => item[1]),
    GERENTE: permissions.map((item) => item[1]).filter((value) => !["funcionarios.permissoes", "backups.restaurar", "configuracoes.editar"].includes(value)),
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
