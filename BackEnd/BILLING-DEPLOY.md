# Implantacao financeira Zentrix

## Configuracao obrigatoria

Configure no `BackEnd/.env` do servidor:

```properties
ASAAS_ENABLED=true
ASAAS_API_URL=https://api-sandbox.asaas.com/v3
ASAAS_API_KEY=SUA_CHAVE
ASAAS_WEBHOOK_TOKEN=SEU_TOKEN_DE_WEBHOOK_COM_32_OU_MAIS_CARACTERES
ZENTRIX_PUBLIC_URL=https://pdv.zentrixsystems.com.br

ZENTRIX_AUTH_SESSION_PERSISTENCE_ENABLED=true
ZENTRIX_AUTH_MFA_ENABLED=true
ZENTRIX_AUTH_MFA_SECRET_KEY=CHAVE_ALEATORIA_COM_32_OU_MAIS_CARACTERES

ZENTRIX_BILLING_GRACE_DAYS=3
ZENTRIX_BILLING_RECONCILIATION_ENABLED=true
ZENTRIX_BILLING_NOTIFICATION_ENABLED=true
```

Gere segredos com `openssl rand -base64 48`. Nunca envie `.env`, chaves do Asaas ou a chave do MFA ao Git.

## Webhook Asaas

- URL: `https://pdv.zentrixsystems.com.br/api/webhooks/asaas`
- Token: o mesmo valor de `ASAAS_WEBHOOK_TOKEN`
- Fila de sincronizacao: ativa
- Eventos: confirmacao, recebimento, vencimento, estorno e chargeback de pagamentos

O endpoint confirma o token, grava o evento e responde rapidamente. Uma fila interna processa com repeticao automatica e reconcilia cobrancas pendentes diretamente com o Asaas.

## E-mail e recuperacao

Para avisos 7, 3 e 1 dia antes e recuperacao de senha:

```properties
ZENTRIX_BILLING_MAIL_ENABLED=true
ZENTRIX_BILLING_MAIL_FROM=financeiro@seudominio.com.br
ZENTRIX_MAIL_HOST=smtp.seuprovedor.com
ZENTRIX_MAIL_PORT=587
ZENTRIX_MAIL_USERNAME=financeiro@seudominio.com.br
ZENTRIX_MAIL_PASSWORD=SENHA_SMTP
ZENTRIX_MAIL_AUTH=true
ZENTRIX_MAIL_STARTTLS=true
ZENTRIX_AUTH_PASSWORD_RESET_ENABLED=true
```

Sem SMTP, os avisos continuam disponíveis dentro do portal do cliente. O webhook opcional `ZENTRIX_BILLING_NOTIFICATION_WEBHOOK_URL` pode alimentar uma automacao de WhatsApp.

## Publicacao no Ubuntu

```bash
cd /var/www/Sistema-Zentrix-WEB
git pull
cd BackEnd
./mvnw clean package
# Antes do primeiro restart desta versao, gere e confira um backup completo do MySQL.
# Exemplo: mysqldump --single-transaction -u USUARIO -p BANCO > zentrix-pre-deploy.sql
sudo systemctl restart zentrix-backend
sudo systemctl status zentrix-backend --no-pager
journalctl -u zentrix-backend -n 100 --no-pager
```

As tabelas e indices sao criados por migracoes idempotentes na inicializacao. O painel administrativo atualizado fica empacotado no JAR. O frontend do cliente permanece em `FrontEnd`, conforme a configuracao atual do Nginx.

Neste deploy, a primeira inicializacao tambem separa os IDs operacionais por PDV e cria o controle idempotente de estoque. Em bases grandes, execute o restart em uma janela de manutencao e aguarde o log `Migracao Zentrix aplicada` antes de liberar vendas e sincronizacoes.

## Validacao

1. Gere uma cobranca no portal `Assinatura` do AppGestao.
2. Pague no sandbox e confirme que o status muda sem reiniciar o backend.
3. No Zentrix Admin, abra `Financeiro` e confira a fatura.
4. Desative e reative um PDV na ficha do cliente e confira o valor mensal.
5. Teste vencimento, tolerancia e bloqueio em uma conta de homologacao.
6. Ative o 2FA de um usuario administrativo e valide um novo login.

Para rotacionar o token do webhook sem interrupcao, coloque o token antigo temporariamente em `ASAAS_WEBHOOK_TOKEN_PREVIOUS`, atualize o Asaas e depois remova o valor anterior.
