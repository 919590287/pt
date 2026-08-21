#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="${SERVER:-myserver2}"
REMOTE_ROOT="${REMOTE_ROOT:-/test/pt}"
DEPLOY_TAG="${DEPLOY_TAG:-$(date +%Y%m%d-%H%M%S)-refresh-fix}"
BACKEND_JAR="$ROOT_DIR/backend/target/gjcxfzksh-1.0-SNAPSHOT.jar"
FRONTEND_DIST="$ROOT_DIR/frontend/gjcxfzksh_web_dist"
REMOTE_RELEASE="$REMOTE_ROOT/releases/$DEPLOY_TAG"

for path in \
  "$BACKEND_JAR" \
  "$FRONTEND_DIST/index.html" \
  "$ROOT_DIR/docker-compose.yml" \
  "$ROOT_DIR/.deploy-runtime.backend.Dockerfile" \
  "$ROOT_DIR/.deploy-runtime.web.Dockerfile"; do
  if [ ! -e "$path" ]; then
    printf 'ERROR: required deployment artifact is missing: %s\n' "$path" >&2
    exit 1
  fi
done

printf 'Deploying tag %s to %s:%s\n' "$DEPLOY_TAG" "$SERVER" "$REMOTE_RELEASE"

ssh "$SERVER" "mkdir -p '$REMOTE_RELEASE/backend' '$REMOTE_RELEASE/web/dist'"
scp "$BACKEND_JAR" "$SERVER:$REMOTE_RELEASE/backend/app.jar"
scp "$ROOT_DIR/.deploy-runtime.backend.Dockerfile" "$SERVER:$REMOTE_RELEASE/backend/Dockerfile"
scp "$ROOT_DIR/.deploy-runtime.web.Dockerfile" "$SERVER:$REMOTE_RELEASE/web/Dockerfile"
scp "$ROOT_DIR/docker-compose.yml" "$SERVER:$REMOTE_RELEASE/docker-compose.yml"
rsync -az --delete "$FRONTEND_DIST/" "$SERVER:$REMOTE_RELEASE/web/dist/"

ssh "$SERVER" bash -s -- "$DEPLOY_TAG" "$REMOTE_ROOT" "$REMOTE_RELEASE" <<'REMOTE_SCRIPT'
set -Eeuo pipefail

DEPLOY_TAG="$1"
REMOTE_ROOT="$2"
REMOTE_RELEASE="$3"
cd "$REMOTE_ROOT"

printf 'Building incremental backend image...\n'
docker build --pull=false \
  -t "gjcxfzksh/gjcxfzksh-backend:$DEPLOY_TAG" \
  -f "$REMOTE_RELEASE/backend/Dockerfile" \
  "$REMOTE_RELEASE/backend"

printf 'Building incremental frontend image...\n'
docker build --pull=false \
  -t "gjcxfzksh/gjcxfzksh-web:$DEPLOY_TAG" \
  -f "$REMOTE_RELEASE/web/Dockerfile" \
  "$REMOTE_RELEASE/web"

docker image inspect \
  "gjcxfzksh/gjcxfzksh-backend:$DEPLOY_TAG" \
  "gjcxfzksh/gjcxfzksh-web:$DEPLOY_TAG" \
  --format '{{.RepoTags}} {{.Architecture}} {{.Size}}'

cp .env ".env.backup-$DEPLOY_TAG"
cp docker-compose.yml "docker-compose.yml.backup-$DEPLOY_TAG"
cp "$REMOTE_RELEASE/docker-compose.yml" docker-compose.yml

ENV_NEXT=".env.next-$DEPLOY_TAG"
awk -v tag="$DEPLOY_TAG" '
  BEGIN { image_tag = 0; trajectory_limit = 0 }
  /^IMAGE_TAG=/ {
    print "IMAGE_TAG=" tag
    image_tag = 1
    next
  }
  /^MATSIM_TRAJECTORY_QUERY_CONCURRENCY=/ {
    print "MATSIM_TRAJECTORY_QUERY_CONCURRENCY=2"
    trajectory_limit = 1
    next
  }
  { print }
  END {
    if (!image_tag) print "IMAGE_TAG=" tag
    if (!trajectory_limit) print "MATSIM_TRAJECTORY_QUERY_CONCURRENCY=2"
  }
' .env > "$ENV_NEXT"
mv "$ENV_NEXT" .env

printf 'Restarting containers...\n'
docker-compose up -d --no-build

for attempt in $(seq 1 60); do
  backend_health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' gjcxfzksh-backend-1 2>/dev/null || true)"
  web_health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' gjcxfzksh-web-1 2>/dev/null || true)"
  if [ "$backend_health" = healthy ] && [ "$web_health" = healthy ]; then
    break
  fi
  if [ "$attempt" -eq 60 ]; then
    printf 'ERROR: containers did not become healthy: backend=%s web=%s\n' "$backend_health" "$web_health" >&2
    docker-compose ps >&2
    docker-compose logs --tail=120 backend web >&2
    exit 1
  fi
  sleep 5
done

curl -fsS http://127.0.0.1:8090/index.html -o /dev/null
curl -fsS http://127.0.0.1:8090/v3/api-docs -o /dev/null

printf '\nDeployment completed.\n'
printf 'IMAGE_TAG=%s\n' "$DEPLOY_TAG"
grep '^MATSIM_TRAJECTORY_QUERY_CONCURRENCY=' .env
docker-compose ps
docker stats --no-stream gjcxfzksh-backend-1 gjcxfzksh-web-1
REMOTE_SCRIPT
