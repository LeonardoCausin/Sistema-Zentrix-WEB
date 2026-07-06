# Zentrix Web API

Backend inicial do Zentrix Web, criado para ser a API online integrada ao Zentrix PDV Desktop.

O modelo de integracao agora e por envio do PDV para o Web: o sincronizador do Zentrix PDV chama a API do Web, e o Web grava os dados em um banco separado (`zentrix_web`).

## Rotas iniciais

- `GET /api/health`
- `POST /api/auth/login`
- `GET /api/dashboard`
- `GET /api/stores`
- `GET /api/sales`
- `GET /api/products`
- `GET /api/cash-sessions`
- `GET /api/stock/alerts`
- `GET /api/audit`
- `GET /api/backups`
- `POST /api/backups/manual`
- `GET /api/backups/{id}/restore/preview`
- `POST /api/backups/{id}/restore`
- `GET /api/finance`
- `GET /api/reports`
- `GET /api/settings`
- `GET /api/observability`
- `GET /api/admin/produtos`
- `POST /api/admin/produtos`
- `PUT /api/admin/produtos/{code}`
- `PATCH /api/admin/produtos/{code}/status`
- `PATCH /api/admin/produtos/{code}/preco`
- `GET /api/admin/clientes`
- `POST /api/admin/clientes`
- `PUT /api/admin/clientes/{id}`
- `PATCH /api/admin/clientes/{id}/status`
- `POST /api/provisioning/bootstrap`
- `POST /api/provisioning/stores`
- `POST /api/provisioning/activation-codes`
- `POST /api/provisioning/activate`
- `POST /api/sync/push`
- `GET /api/sync/pull`
- `POST /api/sync/ack`
- `GET /api/sync/monitor`
- `POST /api/sync/outbox/{id}/retry`
- `POST /api/sync/outbox/{id}/dead-letter`
- `GET /api/sync/status`

As rotas do painel leem o banco `zentrix_web`. O banco e criado automaticamente se o usuario MySQL tiver permissao para `CREATE DATABASE`.

## Clientes, lojas e dados gerais

O Web separa os dados por:

- `tenantId`: cliente/empresa dona da conta.
- `storeId`: loja/filial dentro do cliente.
- `deviceId`: computador/caixa instalado.
- `sourceId`: origem amigavel da sincronizacao, apenas para identificacao visual.

O painel usa o `tenantId` do usuario logado. Assim, `store=all` significa todas as lojas daquele cliente, e `store=<storeId>` filtra uma loja especifica.

Exemplos:

- `GET /api/dashboard?period=today&store=all`
- `GET /api/dashboard?period=month&store=<storeId>`
- `GET /api/products?store=<storeId>`
- `GET /api/stores`

## Provisionamento do PDV

Primeira abertura do PDV:

```http
POST /api/provisioning/bootstrap
Content-Type: application/json
X-Zentrix-Sync-Key: sua-chave-de-sync
```

```json
{
  "companyName": "Mercado Silva",
  "document": "00.000.000/0001-00",
  "storeName": "Loja Matriz",
  "sourceId": "LOJA-MATRIZ-CAIXA-01",
  "deviceId": "uuid-do-computador",
  "deviceName": "Caixa 01",
  "adminUsername": "admin",
  "adminDisplayName": "Administrador",
  "adminPasswordHash": "$2a$12$..."
}
```

Resposta:

```json
{
  "tenantId": "...",
  "tenantName": "Mercado Silva",
  "storeId": "...",
  "storeName": "Loja Matriz",
  "deviceId": "...",
  "deviceName": "Caixa 01",
  "sourceId": "LOJA-MATRIZ-CAIXA-01",
  "firstAdminCreated": true,
  "status": "ACTIVE"
}
```

Nova loja/dispositivo:

```http
POST /api/provisioning/stores
Content-Type: application/json
X-Zentrix-Sync-Key: sua-chave-de-sync
```

```json
{
  "tenantId": "...",
  "storeName": "Loja Centro",
  "sourceId": "LOJA-CENTRO-CAIXA-01",
  "deviceId": "uuid-do-computador",
  "deviceName": "Caixa 01"
}
```

Gerar codigo curto para a tela "Ativar loja" do PDV:

```http
POST /api/provisioning/activation-codes
Content-Type: application/json
X-Zentrix-Sync-Key: sua-chave-de-sync
```

```json
{
  "companyName": "LC Multimarcas",
  "storeName": "LC Multimarcas",
  "sourceId": "LC-MULTIMARCAS-CAIXA-01",
  "expiresMinutes": 10080
}
```

Ativar no PDV usando o codigo:

```http
POST /api/provisioning/activate
Content-Type: application/json
```

```json
{
  "code": "123456",
  "deviceId": "uuid-do-computador",
  "deviceName": "Caixa 01",
  "sourceId": "LC-MULTIMARCAS-CAIXA-01"
}
```

## Banco web

Crie um `.env` a partir do `.env.example`:

```properties
WEB_DB_HOST=localhost
WEB_DB_PORT=3306
WEB_DB_NAME=zentrix_web
WEB_DB_USER=root
WEB_DB_PASSWORD=sua_senha_aqui
WEB_DB_MAX_POOL_SIZE=10
WEB_DB_MIN_IDLE=1
WEB_DB_CONNECTION_TIMEOUT_MS=5000
WEB_DB_VALIDATION_TIMEOUT_MS=3000
WEB_DB_IDLE_TIMEOUT_MS=300000
WEB_DB_MAX_LIFETIME_MS=1800000
ZENTRIX_SYNC_KEY=troque-por-uma-chave-grande-e-secreta
ZENTRIX_AUTH_TOKEN_TTL_MINUTES=480
ZENTRIX_RATE_LIMIT_ENABLED=true
ZENTRIX_RATE_LIMIT_DEFAULT_LIMIT=240
ZENTRIX_RATE_LIMIT_AUTH_LIMIT=30
ZENTRIX_RATE_LIMIT_SYNC_LIMIT=120
ZENTRIX_CORS_ALLOWED_ORIGINS=http://192.168.1.240,http://192.168.1.240:5500
ZENTRIX_CSP_CONNECT_SRC=
```

`ZENTRIX_SYNC_KEY` e obrigatoria. O PDV deve enviar o mesmo valor no header `X-Zentrix-Sync-Key`.

Quando o painel for aberto por IP ou dominio em outro servidor, configure `ZENTRIX_CORS_ALLOWED_ORIGINS` com as origens exatas do frontend. O frontend oficial e servido pelo proprio backend em `http://<ip-do-servidor>:8080/`, e a API fica em `http://<ip-do-servidor>:8080/api`.

Em Ubuntu:

```bash
chmod +x ../iniciar-zentrix-web-ubuntu.sh
../iniciar-zentrix-web-ubuntu.sh
```

Toda resposta da API inclui `X-Request-Id`. Use esse valor para procurar a mesma chamada no log do backend.

## Contrato de sincronizacao

Endpoint:

```http
POST /api/sync/push
Content-Type: application/json
X-Zentrix-Sync-Key: sua-chave-de-sync
```

Formato:

```json
{
  "tenantId": "uuid-do-cliente",
  "tenantName": "Mercado Silva",
  "storeId": "uuid-da-loja",
  "storeName": "Loja Centro",
  "deviceId": "uuid-do-computador",
  "deviceName": "Caixa 01",
  "sourceId": "ZENTRIX-PDV-LOJA-CENTRO",
  "mode": "FULL",
  "generatedAt": "2026-06-10T14:17:00-03:00",
  "tables": {
    "products": [
      {
        "code": "7894900011517",
        "description": "Coca-Cola 2L",
        "unit": "UN",
        "price": 15.00,
        "stock": 3.000,
        "min_stock": 10.000
      }
    ]
  }
}
```

`mode` aceita:

- `FULL`: limpa somente os dados da loja (`tenantId + storeId`) nas tabelas enviadas antes de gravar.
- `PARTIAL`: apenas insere/atualiza os registros enviados.

No modelo novo, `FULL` limpa somente `tenantId + storeId` nas tabelas enviadas. `sourceId` nao e chave de isolamento; ele e apenas identificacao amigavel da origem.

Tabelas aceitas: `users`, `suppliers`, `clients`, `products`, `stock_movements`, `cash_sessions`, `cash_movements`, `sales`, `sale_items`, `sale_cancellations`, `comandas`, `comanda_itens`, `audit_log`.

Na tabela `users`, o Web aceita somente usuarios administrativos. Registros com perfil padrao/operador devem ser ignorados pelo sincronizador do PDV e, se chegarem por engano, tambem serao descartados pelo Web. Perfis aceitos para acesso web: `ADMIN`, `ADMINISTRADOR` ou `ADMINISTRATOR`.

## Seguranca

- `GET /api/health` fica publico para diagnostico.
- `POST /api/auth/login` valida somente usuarios administrativos sincronizados na tabela `users`.
- Senhas sincronizadas devem vir em BCrypt, igual ao Zentrix PDV Desktop.
- Rotas do painel exigem `Authorization: Bearer <token>`.
- Rotas `/api/sync/**` exigem `X-Zentrix-Sync-Key`.
- O backend nao acessa diretamente o banco do PDV; ele apenas recebe dados enviados pela API.

## Como executar

```powershell
mvn spring-boot:run
```

Por padrao a API sobe em `http://localhost:8080`.
