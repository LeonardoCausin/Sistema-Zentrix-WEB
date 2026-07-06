#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/BackEnd"
ENV_FILE="$BACKEND_DIR/.env"
JAR_FILE="$BACKEND_DIR/target/zentrix-web-api-0.1.0-SNAPSHOT.jar"

cd "$BACKEND_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  cp ".env.example" ".env"
  echo "BackEnd/.env foi criado a partir do exemplo."
  echo "Configure WEB_DB_PASSWORD, ZENTRIX_SYNC_KEY e ZENTRIX_SETUP_KEY antes de iniciar."
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ "${WEB_DB_PASSWORD:-}" == "sua_senha_aqui" || -z "${WEB_DB_PASSWORD:-}" ]]; then
  echo "Configure WEB_DB_PASSWORD em BackEnd/.env."
  exit 1
fi

if [[ -z "${ZENTRIX_SYNC_KEY:-}" || "${ZENTRIX_SYNC_KEY:-}" == "troque-por-uma-chave-grande-e-secreta" ]]; then
  echo "Configure ZENTRIX_SYNC_KEY em BackEnd/.env."
  exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
  ./mvnw -q -DskipTests package 2>/dev/null || mvn -q -DskipTests package
fi

SERVER_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
SERVER_IP="${SERVER_IP:-localhost}"
PUBLIC_URL="${ZENTRIX_PUBLIC_URL:-http://$SERVER_IP:8080/}"

echo "Iniciando Zentrix Web..."
echo "Frontend: $PUBLIC_URL"
echo "API: ${PUBLIC_URL%/}/api"
echo "Banco: ${WEB_DB_HOST:-localhost}:${WEB_DB_PORT:-3306}/${WEB_DB_NAME:-zentrix_web}"

exec java -jar "$JAR_FILE"
