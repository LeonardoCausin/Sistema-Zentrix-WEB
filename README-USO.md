# Zentrix Web - uso local

Este projeto e o painel web do Zentrix PDV. O usuario final nao precisa abrir terminal nem conhecer detalhes tecnicos.

## Como abrir

1. De dois cliques em `iniciar-zentrix-web.bat`.
2. Aguarde a janela do sistema preparar o acesso.
3. A tela de login sera aberta automaticamente em `http://localhost:8080/`.

Tambem e possivel abrir diretamente pelo backend oficial:

`http://localhost:8080/`

Evite depender de Live Server/VS Code para uso normal, porque outra pasta aberta na mesma porta pode mostrar uma tela antiga ou de outro projeto.

## Login

Use o usuario criado no banco do Zentrix Web. Se o acesso falhar muitas vezes seguidas, o sistema bloqueia novas tentativas por alguns minutos para proteger a conta.

## Sincronizacao

O Zentrix PDV deve enviar os dados para o Zentrix Web pela fila de sincronizacao. Se o computador do PDV ficar sem internet, a fila guarda os envios e manda tudo quando a conexao voltar.

No painel, abra `Sincronizacao` para acompanhar:

- itens pendentes Web -> PDV;
- itens entregues aguardando ACK;
- erros com retry;
- dead-letter para mudancas que nao devem travar a fila.

Cada chamada da API tambem recebe `X-Request-Id`, que aparece no log do backend e ajuda a localizar falhas em producao.

## Servidor Ubuntu por IP

No servidor, configure `BackEnd/.env` e rode:

```bash
chmod +x iniciar-zentrix-web-ubuntu.sh
./iniciar-zentrix-web-ubuntu.sh
```

Abra pelo IP do servidor, por exemplo:

`http://192.168.1.240:8080/`

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
