#!/usr/bin/env bash
set -euo pipefail

repo=${1:-/home/ubuntu/eodiisseo}
sql=${2:-"$repo/backend/scripts/database/production-readiness.sql"}
test -r "$sql"
cd "$repo/deploy"
sudo -v
docker=(sudo docker)
dc=("${docker[@]}" compose -p eodiisseo-runtime -f compose.yaml -f compose.override.yaml
  -f compose.web.yaml -f compose.mtu.yaml -f compose.gpu.yaml)

git -C "$repo" rev-parse HEAD
"${dc[@]}" config --quiet
"${dc[@]}" config --services
"${dc[@]}" ps
for service in backend gpu-tunnel db web; do
  id=$("${dc[@]}" ps -q "$service")
  if [[ -z "$id" ]]; then printf '%s unavailable\n' "$service"; continue; fi
  "${docker[@]}" inspect --format '{{.Name}} {{.Image}} network={{.HostConfig.NetworkMode}}' "$id"
  if [[ "$service" == backend ]]; then
    "${docker[@]}" inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" |
      awk -F= '
        $1 ~ /^(COLLECTION_WORKER_ENABLED|AUTH_BRIDGES_ENABLED|EODIISSEO_(AI_OLLAMA|OWNED_HTTP|OWNED_RUNTIME|FCM|RUNTIME)_ENABLED|SPRING_FLYWAY_ENABLED)$/ &&
          $2 ~ /^(true|false)$/ {print $1 "=" $2}
        $1 ~ /^(KMA_SERVICE_KEY|DATA_GO_KR_SERVICE_KEY|TMAP_APP_KEY)$/ {
          print $1 "=" (length(substr($0,index($0,"=")+1)) ? "configured" : "empty")
        }'
  fi
done

"${dc[@]}" exec -T db sh -c \
  'exec psql -X -w -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${POSTGRES_DB:-$POSTGRES_USER}"' < "$sql"

if ! "${dc[@]}" exec -T web nginx -T 2>/dev/null |
  awk '$1 ~ /^(client_max_body_size|client_body_timeout|proxy_read_timeout|proxy_send_timeout)$/ && $2 ~ /^[0-9]+[kKmMsShHdD]?;$/ {print $1,$2}'; then
  printf '%s\n' 'Nginx inspection unavailable.'
fi

if command -v jq >/dev/null; then
  if ! "${dc[@]}" exec -T backend curl -fsS --max-time 5 'http://127.0.0.1:11434/api/tags' |
    jq -e '.models | map(select(.name == "qwen3-vl:4b-instruct" or .name == "nomic-embed-text:latest") | {name,digest})'; then
    printf '%s\n' 'GPU model inventory unavailable from the backend network namespace.'
  fi
else
  printf '%s\n' 'jq unavailable. Inspect Ollama model names and digests from the backend network namespace.'
fi
