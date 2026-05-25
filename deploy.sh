#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PATH="$ROOT_DIR/$(basename "${BASH_SOURCE[0]}")"
LOCAL_ENV="$ROOT_DIR/deploy.local.env"

if [ -f "$LOCAL_ENV" ]; then
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
fi

APP_NAME="${APP_NAME:-gjcxfzksh}"
BACKEND_PORT="${BACKEND_PORT:-8090}"
FRONTEND_PORT="${FRONTEND_PORT:-8088}"
HOST="${HOST:-0.0.0.0}"
MATSIM_DATA="${MATSIM_DATA:-${MATSIM_DATA_PATH:-/Volumes/USB DISK/pt_data/}}"
JAVA_OPTS="${JAVA_OPTS:--Xms2g -Xmx8g}"
FRONTEND_INSTALL="${FRONTEND_INSTALL:-auto}"
VITE_MODE="${VITE_MODE:-production}"
JAVA_CMD="${JAVA_CMD:-java}"
MVN_CMD="${MVN_CMD:-mvn}"
NPM_CMD="${NPM_CMD:-npm}"

API_BASE_URL="${API_BASE_URL:-}"
MODEL_FILES_PATH="${MODEL_FILES_PATH:-}"
VEHICLE_MODELS_PATH="${VEHICLE_MODELS_PATH:-$MODEL_FILES_PATH}"
VEHICLE_MODELS_BASE_URL="${VEHICLE_MODELS_BASE_URL:-/models/vehicles}"
CITY_BUILDINGS_SHP_PATH="${CITY_BUILDINGS_SHP_PATH:-}"
CITY_BUILDINGS_STATIC_PATH="${CITY_BUILDINGS_STATIC_PATH:-}"
CITY_BUILDINGS_ENABLED="${CITY_BUILDINGS_ENABLED:-true}"
CITY_BUILDINGS_HEIGHT_FIELD="${CITY_BUILDINGS_HEIGHT_FIELD:-HEIGHT}"
CITY_BUILDINGS_MAX_FEATURES="${CITY_BUILDINGS_MAX_FEATURES:-20000}"
MAP_TILE_URL_TEMPLATE="${MAP_TILE_URL_TEMPLATE:-}"
NETWORK_LINE_MIN_PIXELS="${NETWORK_LINE_MIN_PIXELS:-0.8}"

BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
DIST_DIR="${FRONTEND_DIST_DIR:-$FRONTEND_DIR/gjcxfzksh_web_dist}"
RUN_DIR="${RUN_DIR:-$ROOT_DIR/.deploy}"
LOG_DIR="${LOG_DIR:-$RUN_DIR/logs}"
BACKEND_PID="$RUN_DIR/backend.pid"
FRONTEND_PID="$RUN_DIR/frontend.pid"

say() {
  printf '%s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "command not found: $1"
}

ensure_dirs() {
  mkdir -p "$RUN_DIR" "$LOG_DIR"
}

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

js_escape() {
  local value="${1:-}"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

js_bool() {
  if is_true "$1"; then
    printf 'true'
  else
    printf 'false'
  fi
}

xml_escape() {
  local value="${1:-}"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  value="${value//\"/&quot;}"
  value="${value//\'/&apos;}"
  printf '%s' "$value"
}

pid_value() {
  local pid_file="$1"
  [ -f "$pid_file" ] || return 1
  tr -cd '0-9' < "$pid_file"
}

is_running() {
  local pid_file="$1"
  local pid
  pid="$(pid_value "$pid_file" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

find_backend_jar() {
  if [ -n "${BACKEND_JAR:-}" ]; then
    printf '%s' "$BACKEND_JAR"
    return
  fi

  [ -d "$BACKEND_DIR/target" ] || return 0
  find "$BACKEND_DIR/target" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null \
    | sort | tail -n 1 || true
}

build_backend() {
  require_cmd "$MVN_CMD"
  say "Building backend..."
  "$MVN_CMD" -f "$BACKEND_DIR/pom.xml" -DskipTests package
}

install_frontend_deps_if_needed() {
  require_cmd "$NPM_CMD"
  if [ "$FRONTEND_INSTALL" = "1" ] || { [ "$FRONTEND_INSTALL" = "auto" ] && [ ! -d "$FRONTEND_DIR/node_modules" ]; }; then
    say "Installing frontend dependencies..."
    if [ -f "$FRONTEND_DIR/package-lock.json" ]; then
      (cd "$FRONTEND_DIR" && "$NPM_CMD" ci)
    else
      (cd "$FRONTEND_DIR" && "$NPM_CMD" install)
    fi
  fi
}

build_frontend() {
  install_frontend_deps_if_needed
  say "Building frontend..."
  (cd "$FRONTEND_DIR" && VITE_APP_BASE_API="" "$NPM_CMD" run build -- --mode "$VITE_MODE")
}

write_runtime_config() {
  [ -d "$DIST_DIR" ] || die "frontend dist not found: $DIST_DIR. Run ./deploy.sh build first."

  cat > "$DIST_DIR/runtime-config.js" <<EOF
(function () {
  var backendPort = "$(js_escape "$BACKEND_PORT")";
  var explicitApiBaseUrl = "$(js_escape "$API_BASE_URL")";

  window.APP_CONFIG = Object.assign({}, window.APP_CONFIG || {}, {
    backendPort: backendPort,
    apiBaseUrl: explicitApiBaseUrl || (window.location.protocol + "//" + window.location.hostname + ":" + backendPort),
    vehicleModelsBaseUrl: "$(js_escape "$VEHICLE_MODELS_BASE_URL")",
    cityBuildingsEnabled: $(js_bool "$CITY_BUILDINGS_ENABLED"),
    cityBuildingsShpPath: "$(js_escape "$CITY_BUILDINGS_SHP_PATH")",
    cityBuildingsHeightField: "$(js_escape "$CITY_BUILDINGS_HEIGHT_FIELD")",
    cityBuildingsMaxFeatures: Number("$(js_escape "$CITY_BUILDINGS_MAX_FEATURES")") || 20000,
    mapTileUrlTemplate: "$(js_escape "$MAP_TILE_URL_TEMPLATE")",
    networkLineMinPixels: Number("$(js_escape "$NETWORK_LINE_MIN_PIXELS")") || 0.8
  });
})();
EOF
}

link_static_dir() {
  local source_dir="$1"
  local target_dir="$2"
  local label="$3"

  [ -n "$source_dir" ] || return 0
  [ -d "$source_dir" ] || die "$label path does not exist or is not a directory: $source_dir"

  case "$target_dir" in
    "$DIST_DIR"/*) ;;
    *) die "refuse to replace path outside frontend dist: $target_dir" ;;
  esac

  rm -rf "$target_dir"
  ln -s "$source_dir" "$target_dir"
  say "$label linked: $target_dir -> $source_dir"
}

prepare_runtime_assets() {
  ensure_dirs
  write_runtime_config
  link_static_dir "$VEHICLE_MODELS_PATH" "$DIST_DIR/models" "Vehicle models"
  link_static_dir "$CITY_BUILDINGS_STATIC_PATH" "$DIST_DIR/city-buildings" "City building assets"
}

build_all() {
  build_backend
  build_frontend
  prepare_runtime_assets
}

start_backend() {
  ensure_dirs
  require_cmd "$JAVA_CMD"

  if is_running "$BACKEND_PID"; then
    say "Backend already running, pid=$(pid_value "$BACKEND_PID")"
    return
  fi

  local jar_file
  jar_file="$(find_backend_jar)"
  [ -n "$jar_file" ] && [ -f "$jar_file" ] || die "backend jar not found. Run ./deploy.sh build or ./deploy.sh deploy first."

  if [ ! -d "$MATSIM_DATA" ]; then
    say "MATSIM_DATA does not exist, creating: $MATSIM_DATA"
    mkdir -p "$MATSIM_DATA"
  fi

  (
    cd "$BACKEND_DIR"
    nohup "$JAVA_CMD" $JAVA_OPTS -jar "$jar_file" \
      --server.port="$BACKEND_PORT" \
      --matsim.data="$MATSIM_DATA" \
      > "$LOG_DIR/backend-console.log" 2>&1 &
    echo $! > "$BACKEND_PID"
  )
  say "Backend started on port $BACKEND_PORT, pid=$(pid_value "$BACKEND_PID")"
}

start_frontend() {
  ensure_dirs
  require_cmd "$JAVA_CMD"
  [ -d "$DIST_DIR" ] || die "frontend dist not found: $DIST_DIR. Run ./deploy.sh build or ./deploy.sh deploy first."

  if is_running "$FRONTEND_PID"; then
    say "Frontend already running, pid=$(pid_value "$FRONTEND_PID")"
    return
  fi

  nohup "$JAVA_CMD" -m jdk.httpserver -b "$HOST" -p "$FRONTEND_PORT" -d "$DIST_DIR" \
    > "$LOG_DIR/frontend-console.log" 2>&1 &
  echo $! > "$FRONTEND_PID"
  say "Frontend started on port $FRONTEND_PORT, pid=$(pid_value "$FRONTEND_PID")"
}

stop_one() {
  local name="$1"
  local pid_file="$2"
  local pid

  pid="$(pid_value "$pid_file" 2>/dev/null || true)"
  if [ -z "$pid" ]; then
    say "$name is not running: no pid file"
    return
  fi

  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    say "Stopped $name, pid=$pid"
  else
    say "$name pid file exists, but process is not running: pid=$pid"
  fi
  rm -f "$pid_file"
}

stop_all() {
  stop_one frontend "$FRONTEND_PID"
  stop_one backend "$BACKEND_PID"
}

start_all() {
  prepare_runtime_assets
  start_backend
  start_frontend
}

restart_all() {
  stop_all
  start_all
}

print_one_status() {
  local name="$1"
  local pid_file="$2"

  if is_running "$pid_file"; then
    say "$name: running, pid=$(pid_value "$pid_file")"
  else
    say "$name: stopped"
  fi
}

status_all() {
  ensure_dirs
  print_one_status "Backend" "$BACKEND_PID"
  print_one_status "Frontend" "$FRONTEND_PID"
  say "Frontend URL: http://localhost:$FRONTEND_PORT"
  say "Backend API:   http://localhost:$BACKEND_PORT"
  say "MATSIM_DATA:   $MATSIM_DATA"
  say "Logs:          $LOG_DIR"
}

show_logs() {
  local target="${1:-all}"
  local lines="${LOG_LINES:-80}"

  case "$target" in
    backend)
      tail -n "$lines" "$LOG_DIR/backend-console.log"
      ;;
    frontend)
      tail -n "$lines" "$LOG_DIR/frontend-console.log"
      ;;
    all)
      say "== backend =="
      tail -n "$lines" "$LOG_DIR/backend-console.log" 2>/dev/null || true
      say "== frontend =="
      tail -n "$lines" "$LOG_DIR/frontend-console.log" 2>/dev/null || true
      ;;
    *)
      die "unknown logs target: $target"
      ;;
  esac
}

init_config() {
  if [ -f "$LOCAL_ENV" ]; then
    say "deploy.local.env already exists: $LOCAL_ENV"
    return
  fi

  if [ -f "$ROOT_DIR/deploy.local.env.example" ]; then
    cp "$ROOT_DIR/deploy.local.env.example" "$LOCAL_ENV"
  else
    cat > "$LOCAL_ENV" <<'EOF'
BACKEND_PORT=8090
FRONTEND_PORT=8088
HOST=0.0.0.0
MATSIM_DATA="/Volumes/USB DISK/pt_data/"
JAVA_OPTS="-Xms2g -Xmx8g"
JAVA_CMD=java
MVN_CMD=mvn
NPM_CMD=npm
VEHICLE_MODELS_PATH=""
VEHICLE_MODELS_BASE_URL="/models/vehicles"
CITY_BUILDINGS_SHP_PATH=""
CITY_BUILDINGS_STATIC_PATH=""
CITY_BUILDINGS_ENABLED=true
CITY_BUILDINGS_HEIGHT_FIELD=HEIGHT
CITY_BUILDINGS_MAX_FEATURES=20000
MAP_TILE_URL_TEMPLATE=""
NETWORK_LINE_MIN_PIXELS=0.8
FRONTEND_INSTALL=auto
EOF
  fi
  say "Created $LOCAL_ENV"
}

install_autostart_macos() {
  require_cmd launchctl
  ensure_dirs

  local label="com.jts.${APP_NAME}.deploy"
  local plist="$HOME/Library/LaunchAgents/$label.plist"
  mkdir -p "$HOME/Library/LaunchAgents"

  cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$(xml_escape "$label")</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml_escape "$SCRIPT_PATH")</string>
    <string>start</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$(xml_escape "$ROOT_DIR")</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <false/>
  <key>StandardOutPath</key>
  <string>$(xml_escape "$LOG_DIR/autostart.out.log")</string>
  <key>StandardErrorPath</key>
  <string>$(xml_escape "$LOG_DIR/autostart.err.log")</string>
</dict>
</plist>
EOF

  launchctl unload "$plist" >/dev/null 2>&1 || true
  launchctl load -w "$plist"
  say "macOS autostart installed: $plist"
}

install_autostart_linux() {
  require_cmd systemctl
  ensure_dirs

  local service_dir="$HOME/.config/systemd/user"
  local service="$service_dir/${APP_NAME}.service"
  mkdir -p "$service_dir"

  cat > "$service" <<EOF
[Unit]
Description=GJCXFZKSH frontend and backend
After=network.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$ROOT_DIR
ExecStart=/bin/bash "$SCRIPT_PATH" start
ExecStop=/bin/bash "$SCRIPT_PATH" stop

[Install]
WantedBy=default.target
EOF

  systemctl --user daemon-reload
  systemctl --user enable --now "${APP_NAME}.service"
  say "Linux user autostart installed: $service"
  say "For boot before login, run: loginctl enable-linger $USER"
}

install_autostart() {
  case "$(uname -s)" in
    Darwin) install_autostart_macos ;;
    Linux) install_autostart_linux ;;
    *) die "autostart is only implemented for macOS launchd and Linux systemd user services" ;;
  esac
}

uninstall_autostart() {
  case "$(uname -s)" in
    Darwin)
      local label="com.jts.${APP_NAME}.deploy"
      local plist="$HOME/Library/LaunchAgents/$label.plist"
      launchctl unload "$plist" >/dev/null 2>&1 || true
      rm -f "$plist"
      say "macOS autostart removed: $plist"
      ;;
    Linux)
      local service="$HOME/.config/systemd/user/${APP_NAME}.service"
      systemctl --user disable --now "${APP_NAME}.service" >/dev/null 2>&1 || true
      rm -f "$service"
      systemctl --user daemon-reload >/dev/null 2>&1 || true
      say "Linux user autostart removed: $service"
      ;;
    *)
      die "autostart is only implemented for macOS launchd and Linux systemd user services"
      ;;
  esac
}

usage() {
  cat <<EOF
Usage: ./deploy.sh [command]

Commands:
  deploy              Build latest backend/frontend, restart both services (default)
  build               Build backend/frontend and write runtime config
  start               Start existing built artifacts without rebuilding
  stop                Stop frontend and backend
  restart             Restart existing built artifacts without rebuilding
  status              Show process status and configured ports
  logs [all|backend|frontend]
  init-config         Create deploy.local.env from the example
  install-autostart   Install login/boot autostart for this computer
  uninstall-autostart Remove autostart entry

Per-computer settings:
  Copy deploy.local.env.example to deploy.local.env, then edit ports and paths.
EOF
}

main() {
  local command="${1:-deploy}"

  case "$command" in
    deploy)
      build_all
      restart_all
      status_all
      ;;
    build)
      build_all
      ;;
    start)
      start_all
      status_all
      ;;
    stop)
      stop_all
      ;;
    restart)
      restart_all
      status_all
      ;;
    status)
      status_all
      ;;
    logs)
      show_logs "${2:-all}"
      ;;
    init-config)
      init_config
      ;;
    install-autostart)
      install_autostart
      ;;
    uninstall-autostart)
      uninstall_autostart
      ;;
    help|-h|--help)
      usage
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
