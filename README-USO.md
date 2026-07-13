# Zentrix Web - uso local

Este projeto e o painel web de gestao do Zentrix AppGestao. O usuario final nao precisa abrir terminal nem conhecer detalhes tecnicos.

## Como abrir

1. De dois cliques em `iniciar-zentrix-web.bat`.
2. Aguarde a janela do sistema preparar o acesso.
3. A tela de login sera aberta automaticamente em `http://localhost:8080/`.

Tambem e possivel abrir diretamente pelo backend oficial:

`http://localhost:8080/`

Evite depender de Live Server/VS Code para uso normal, porque outra pasta aberta na mesma porta pode mostrar uma tela antiga ou de outro projeto.

## Login

Use o usuario criado no banco do Zentrix Web. Se o acesso falhar muitas vezes seguidas, o sistema bloqueia novas tentativas por alguns minutos para proteger a conta.

## Auditoria e eventos

O painel principal da loja exibe Auditoria, eventos do sistema, alertas, backups e indicadores comerciais. A tela de Auditoria mostra acoes sensiveis, alteracoes manuais, cancelamentos, falhas e registros importantes para operacao.

## Modelo comercial e permissoes

O painel da loja e voltado ao dono, administrador ou equipe autorizada da empresa contratante. Ele deve mostrar apenas recursos de uso diario: dashboard, vendas, produtos, estoque, clientes, caixa, financeiro, funcionarios, auditoria, relatorios, backups, configuracoes e alertas.

A criacao de empresas, criacao de lojas, ativacao, suspensao, bloqueio, cancelamento, reativacao, plano, vencimento, valor mensal e cobranca sao responsabilidades do painel master/administrativo. Usuario comum da loja nao deve criar loja nem alterar status comercial da assinatura.

Perfis master, como `SUPER_ADMIN` e `MASTER_ADMIN`, devem ser usados somente para administracao do sistema, empresas, lojas, planos, cobrancas, usuarios master, auditoria geral e eventos tecnicos. Dados tecnicos sensiveis nao devem aparecer no painel comum da loja.

## Sincronizacao interna

A sincronizacao continua existindo internamente no backend para integracao tecnica. Nao remova controllers, services, DTOs, tabelas, filas ou endpoints de sincronizacao sem analise especifica.

O usuario comum da loja nao possui menu visual de Sincronizacao. Dados tecnicos de fila, ACK, retry, dead-letter e monitoramento devem ficar restritos a area administrativa/master ou suporte tecnico.

Cada chamada da API tambem recebe `X-Request-Id`, que aparece no log do backend e ajuda a localizar falhas em producao.

## Servidor Ubuntu por IP

No servidor, configure `BackEnd/.env` e rode:

```bash
chmod +x iniciar-zentrix-web-ubuntu.sh
./iniciar-zentrix-web-ubuntu.sh
```

Abra pelo dominio de producao:

`https://pdv.zentrixsystems.com.br/`

Se o painel for acessado por outro dominio/IP, configure `ZENTRIX_CORS_ALLOWED_ORIGINS` no `.env`.

Para producao com Nginx, use os modelos em:

- `deploy/nginx/zentrix-web.conf`: proxy reverso para `/`, `/FrontEnd` e `/api`.
- `deploy/systemd/zentrix-web.service`: servico Linux com restart automatico.

Fluxo recomendado no Ubuntu:

```bash
sudo cp deploy/systemd/zentrix-web.service /etc/systemd/system/zentrix-web.service
sudo systemctl daemon-reload
sudo systemctl enable --now zentrix-web

sudo cp deploy/nginx/zentrix-web.conf /etc/nginx/sites-available/zentrix-web
sudo ln -sf /etc/nginx/sites-available/zentrix-web /etc/nginx/sites-enabled/zentrix-web
sudo nginx -t
sudo systemctl reload nginx
```

Com Nginx, o frontend usa a API no mesmo dominio em `/api`, sem depender de porta `8080` publica.

## Testes do backend

O backend agora possui Maven Wrapper dentro de `BackEnd`, entao nao depende de Maven instalado na maquina:

```bash
cd BackEnd
./mvnw test
```

No Windows:

```bat
cd BackEnd
mvnw.cmd test
```

## Dominio de producao

O frontend e a API rodam na mesma origem. Em producao:

- painel: `https://pdv.zentrixsystems.com.br/`
- API: `https://pdv.zentrixsystems.com.br/api/...`

O frontend usa sempre o caminho relativo `/api`, entao tambem funciona em desenvolvimento pelo `http://localhost:8080/` sem alterar codigo.

Se o frontend for servido separado do backend durante diagnostico, inclua a origem exata em `ZENTRIX_CORS_ALLOWED_ORIGINS`. Para uso normal e producao, prefira sempre mesma origem pelo Nginx.

## Seguranca em producao

Para publicar em servidor Ubuntu:

- mantenha a porta `8080` fechada para a internet; exponha somente Nginx nas portas `80/443`;
- use HTTPS no Nginx e renove certificado automaticamente;
- mantenha `ZENTRIX_CORS_ALLOW_NULL_ORIGIN=false`;
- configure `ZENTRIX_CORS_ALLOWED_ORIGINS` somente com dominios reais do painel;
- se o backend estiver somente atras do Nginx, use `ZENTRIX_RATE_LIMIT_TRUST_PROXY_HEADERS=true`;
- mantenha `ZENTRIX_SYNC_REQUIRE_KNOWN_DEVICE_SCOPE=true`;
- em HTTPS, use `ZENTRIX_AUTH_COOKIE_SECURE=true`;
- se frontend e API ficarem em dominios diferentes e ambos HTTPS, use `ZENTRIX_AUTH_COOKIE_SAME_SITE=None`;
- use valores longos e diferentes para `ZENTRIX_SYNC_KEY` e `ZENTRIX_SETUP_KEY`;
- nao reutilize senha de banco, senha de admin e chaves de sync/setup.

O login continua retornando token para compatibilidade, mas o backend tambem grava um cookie HttpOnly. Em publicacao Nginx no mesmo dominio, as chamadas autenticadas usam esse cookie automaticamente.

O sync Web -> PDV agora exige `sourceId` ou `deviceId` quando a loja ja possui device cadastrado. Isso impede que uma chave global de sync seja usada sozinha para puxar dados de outra loja.

## Backups e restauracao

A restauracao agora exige preview e frase de confirmacao. Antes de qualquer tentativa destrutiva, o backend registra um snapshot de seguranca. Como o backup fisico ainda nao esta implementado, a restauracao real fica bloqueada com mensagem clara em vez de apagar dados sem arquivo validado.

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
