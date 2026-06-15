# Zentrix Web - uso local

Este projeto e o painel web do Zentrix PDV. O usuario final nao precisa abrir terminal nem conhecer detalhes tecnicos.

## Como abrir

1. De dois cliques em `iniciar-zentrix-web.bat`.
2. Aguarde a janela do sistema preparar o acesso.
3. A tela de login sera aberta automaticamente.

## Login

Use o usuario criado no banco do Zentrix Web. Se o acesso falhar muitas vezes seguidas, o sistema bloqueia novas tentativas por alguns minutos para proteger a conta.

## Sincronizacao

O Zentrix PDV deve enviar os dados para o Zentrix Web pela fila de sincronizacao. Se o computador do PDV ficar sem internet, a fila guarda os envios e manda tudo quando a conexao voltar.

## Quando algo nao abrir

1. Confirme se o MySQL esta iniciado.
2. Abra `BackEnd\backend.log` para ver a execucao normal.
3. Abra `BackEnd\backend-error.log` para ver falhas de inicializacao.
4. Depois de corrigir, feche a janela do backend e abra novamente pelo `iniciar-zentrix-web.bat`.

## Arquivos principais

- `FrontEnd\index.html`: tela de login.
- `FrontEnd\pages`: telas do painel.
- `FrontEnd\css`: estilos.
- `FrontEnd\js`: comportamento do painel.
- `BackEnd`: servidor local, dados e sincronizacao.
