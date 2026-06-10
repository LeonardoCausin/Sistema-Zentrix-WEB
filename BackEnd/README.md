# Zentrix Web API

Backend inicial do Zentrix Web, criado para ser a API online integrada ao Zentrix PDV Desktop.

O modelo de integracao agora e por envio do PDV para o Web: o sincronizador do Zentrix PDV chama a API do Web, e o Web grava os dados em um banco separado (`zentrix_web`).

## Rotas iniciais

- `GET /api/health`
- `POST /api/auth/login`
- `GET /api/dashboard`
- `GET /api/sales`
- `GET /api/products`
- `GET /api/cash-sessions`
- `GET /api/stock/alerts`
- `GET /api/audit`
- `GET /api/backups`
- `POST /api/sync/push`
- `GET /api/sync/status`

As rotas do painel leem o banco `zentrix_web`. O banco e criado automaticamente se o usuario MySQL tiver permissao para `CREATE DATABASE`.

## Banco web

Crie um `.env` a partir do `.env.example`:

```properties
WEB_DB_HOST=localhost
WEB_DB_PORT=3306
WEB_DB_NAME=zentrix_web
WEB_DB_USER=root
WEB_DB_PASSWORD=sua_senha_aqui
ZENTRIX_SYNC_KEY=troque-por-uma-chave-grande-e-secreta
```

`ZENTRIX_SYNC_KEY` e obrigatoria. O PDV deve enviar o mesmo valor no header `X-Zentrix-Sync-Key`.

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

- `FULL`: limpa as tabelas enviadas antes de gravar.
- `PARTIAL`: apenas insere/atualiza os registros enviados.

Tabelas aceitas: `users`, `suppliers`, `clients`, `products`, `stock_movements`, `cash_sessions`, `cash_movements`, `sales`, `sale_items`, `sale_cancellations`, `comandas`, `comanda_itens`, `audit_log`.

## Seguranca

- `GET /api/health` fica publico para diagnostico.
- `POST /api/auth/login` valida usuarios sincronizados na tabela `users`.
- Senhas sincronizadas devem vir em BCrypt, igual ao Zentrix PDV Desktop.
- Rotas do painel exigem `Authorization: Bearer <token>`.
- Rotas `/api/sync/**` exigem `X-Zentrix-Sync-Key`.
- O backend nao acessa diretamente o banco do PDV; ele apenas recebe dados enviados pela API.

## Como executar

```powershell
mvn spring-boot:run
```

Por padrao a API sobe em `http://localhost:8080`.
