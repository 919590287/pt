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
MATSIM_CACHE="${MATSIM_CACHE:-${MATSIM_CACHE_PATH:-/Volumes/USB DISK/pt_cache/}}"
MATSIM_LARGE_MODEL_THRESHOLD_BYTES="${MATSIM_LARGE_MODEL_THRESHOLD_BYTES:-21474836480}"
MATSIM_LARGE_MODEL_PLANS_THRESHOLD_BYTES="${MATSIM_LARGE_MODEL_PLANS_THRESHOLD_BYTES:-8589934592}"
MATSIM_LARGE_MODEL_EVENTS_THRESHOLD_BYTES="${MATSIM_LARGE_MODEL_EVENTS_THRESHOLD_BYTES:-8589934592}"
MATSIM_CACHE_BUILD_THREADS="${MATSIM_CACHE_BUILD_THREADS:-1}"
MATSIM_PROCESSING_THREADS="${MATSIM_PROCESSING_THREADS:-4}"
MATSIM_TRAJECTORY_QUERY_CONCURRENCY="${MATSIM_TRAJECTORY_QUERY_CONCURRENCY:-2}"
MATSIM_CACHE_STATUS_PROBE_TTL_MS="${MATSIM_CACHE_STATUS_PROBE_TTL_MS:-10000}"
MATSIM_CACHE_PREBUILD_ON_STARTUP="${MATSIM_CACHE_PREBUILD_ON_STARTUP:-false}"
MATSIM_REAL_PASSENGER_CACHE_PREBUILD_ON_STARTUP="${MATSIM_REAL_PASSENGER_CACHE_PREBUILD_ON_STARTUP:-false}"
MATSIM_REAL_PASSENGER_AGGREGATE_BUILDER_ENABLED="${MATSIM_REAL_PASSENGER_AGGREGATE_BUILDER_ENABLED:-true}"
MATSIM_REAL_PASSENGER_PYTHON_COMMAND="${MATSIM_REAL_PASSENGER_PYTHON_COMMAND:-python3}"
MATSIM_CACHE_MIN_FREE_BYTES="${MATSIM_CACHE_MIN_FREE_BYTES:-21474836480}"
MATSIM_CACHE_CLEAN_OLD_VERSIONS="${MATSIM_CACHE_CLEAN_OLD_VERSIONS:-true}"
MATSIM_CACHE_BUILDER_ISOLATED_ENABLED="${MATSIM_CACHE_BUILDER_ISOLATED_ENABLED:-true}"
MATSIM_CACHE_BUILDER_XMS="${MATSIM_CACHE_BUILDER_XMS:-256m}"
MATSIM_CACHE_BUILDER_XMX="${MATSIM_CACHE_BUILDER_XMX:-6g}"
MATSIM_CACHE_BUILDER_MAX_METASPACE="${MATSIM_CACHE_BUILDER_MAX_METASPACE:-512m}"
MATSIM_RUNTIME_MAX_COMPUTE_MODELS="${MATSIM_RUNTIME_MAX_COMPUTE_MODELS:-1}"
MATSIM_RUNTIME_MAX_VISUAL_MODELS="${MATSIM_RUNTIME_MAX_VISUAL_MODELS:-1}"
BACKEND_MEMORY_CACHE_MAX_BYTES="${BACKEND_MEMORY_CACHE_MAX_BYTES:-268435456}"
UNDERTOW_WORKER_THREADS="${UNDERTOW_WORKER_THREADS:-32}"
UNDERTOW_IO_THREADS="${UNDERTOW_IO_THREADS:-4}"
GJCXFZKSH_EVENTS_WORKERS="${GJCXFZKSH_EVENTS_WORKERS:-}"
GJCXFZKSH_EVENTS_PIGZ_THREADS="${GJCXFZKSH_EVENTS_PIGZ_THREADS:-}"
GJCXFZKSH_EVENTS_PIGZ_ENABLED="${GJCXFZKSH_EVENTS_PIGZ_ENABLED:-}"
CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx2g -Xss768k -XX:SoftMaxHeapSize=768m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=300000 -XX:G1PeriodicGCSystemLoadThreshold=0 -XX:+ExplicitGCInvokesConcurrent -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m}"
FRONTEND_INSTALL="${FRONTEND_INSTALL:-auto}"
VITE_MODE="${VITE_MODE:-production}"
JAVA_CMD="${JAVA_CMD:-java}"
MVN_CMD="${MVN_CMD:-mvn}"
NPM_CMD="${NPM_CMD:-npm}"
NODE_CMD="${NODE_CMD:-node}"
SCREEN_CMD="${SCREEN_CMD:-screen}"

if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi

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
MAP_BASEMAP_DEFAULT="${MAP_BASEMAP_DEFAULT:-esri-dark}"
NETWORK_LINE_MIN_PIXELS="${NETWORK_LINE_MIN_PIXELS:-0.8}"
NETWORK_LINE_SOFT_EDGE_PIXELS="${NETWORK_LINE_SOFT_EDGE_PIXELS:-0}"
MAP_PIXEL_RATIO="${MAP_PIXEL_RATIO:-}"

BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
DIST_DIR="${FRONTEND_DIST_DIR:-$FRONTEND_DIR/gjcxfzksh_web_dist}"
FRONTEND_SERVER_SCRIPT="$ROOT_DIR/scripts/serve-frontend.mjs"
RUN_DIR="${RUN_DIR:-$ROOT_DIR/.deploy}"
LOG_DIR="${LOG_DIR:-$RUN_DIR/logs}"
BACKEND_PID="$RUN_DIR/backend.pid"
FRONTEND_PID="$RUN_DIR/frontend.pid"
BACKEND_SCREEN="${BACKEND_SCREEN:-${APP_NAME}-backend}"
FRONTEND_SCREEN="${FRONTEND_SCREEN:-${APP_NAME}-frontend}"
BACKEND_PROCESS_MARKER="${BACKEND_PROCESS_MARKER:-${BACKEND_JAR:-$BACKEND_DIR/}}"
FRONTEND_PROCESS_MARKER="${FRONTEND_PROCESS_MARKER:-$FRONTEND_SERVER_SCRIPT}"

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

sh_escape() {
  local value="${1:-}"
  value="${value//\'/\'\\\'\'}"
  printf "'%s'" "$value"
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

process_matches() {
  local pid="$1"
  local marker="$2"
  local command

  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [ -n "$command" ] && [[ "$command" == *"$marker"* ]]
}

is_service_running() {
  local pid_file="$1"
  local marker="$2"
  local pid

  pid="$(pid_value "$pid_file" 2>/dev/null || true)"
  process_matches "$pid" "$marker"
}

listening_pids() {
  local port="$1"

  command -v lsof >/dev/null 2>&1 || return 0
  lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u || true
}

find_managed_listener_pid() {
  local port="$1"
  local marker="$2"
  local pid

  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    if process_matches "$pid" "$marker"; then
      printf '%s' "$pid"
      return 0
    fi
  done < <(listening_pids "$port")

  return 1
}

port_is_listening() {
  local port="$1"

  if command -v lsof >/dev/null 2>&1; then
    [ -n "$(listening_pids "$port")" ]
  elif command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    return 0
  fi
}

describe_port_owner() {
  local port="$1"
  local pid
  local command
  local found=0

  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    say "  pid=$pid ${command:-<command unavailable>}"
    found=1
  done < <(listening_pids "$port")

  [ "$found" -eq 1 ] || say "  owner unavailable (install lsof for process details)"
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

wait_for_pid() {
  local pid_file="$1"
  local attempts="${2:-50}"

  while [ "$attempts" -gt 0 ]; do
    if is_running "$pid_file"; then
      return 0
    fi
    sleep 0.2
    attempts=$((attempts - 1))
  done

  return 1
}

wait_for_service() {
  local pid_file="$1"
  local marker="$2"
  local port="$3"
  local attempts="${4:-150}"

  while [ "$attempts" -gt 0 ]; do
    is_service_running "$pid_file" "$marker" || return 1
    if port_is_listening "$port"; then
      return 0
    fi
    sleep 0.2
    attempts=$((attempts - 1))
  done

  return 1
}

wait_for_exit() {
  local pid="$1"
  local attempts="${2:-75}"

  while [ "$attempts" -gt 0 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
    attempts=$((attempts - 1))
  done

  return 1
}

stop_screen() {
  local screen_name="$1"

  [ -n "$screen_name" ] || return 0
  command -v "$SCREEN_CMD" >/dev/null 2>&1 || return 0
  "$SCREEN_CMD" -S "$screen_name" -X quit >/dev/null 2>&1 || true
}

start_detached() {
  local service_name="$1"
  local script_file="$2"
  local pid_file="$3"
  local screen_name="$4"
  local marker="$5"
  local port="$6"
  local log_file="$7"

  rm -f "$pid_file"
  if command -v "$SCREEN_CMD" >/dev/null 2>&1; then
    stop_screen "$screen_name"
    # macOS screen can fail to execute scripts directly from non-ASCII paths.
    "$SCREEN_CMD" -dmS "$screen_name" /bin/bash "$script_file"
  else
    nohup /bin/bash "$script_file" >/dev/null 2>&1 &
  fi

  if ! wait_for_pid "$pid_file" 50 || ! wait_for_service "$pid_file" "$marker" "$port" 150; then
    rm -f "$pid_file"
    say "== $service_name log tail ==" >&2
    tail -n 40 "$log_file" >&2 2>/dev/null || true
    die "$service_name failed to start on port $port. Full log: $log_file"
  fi
}

write_backend_launcher() {
  local script_file="$1"
  local jar_file="$2"

  cat > "$script_file" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
cd $(sh_escape "$BACKEND_DIR")
echo \$\$ > $(sh_escape "$BACKEND_PID")
export GJCXFZKSH_EVENTS_WORKERS=$(sh_escape "$GJCXFZKSH_EVENTS_WORKERS")
export GJCXFZKSH_EVENTS_PIGZ_THREADS=$(sh_escape "$GJCXFZKSH_EVENTS_PIGZ_THREADS")
export GJCXFZKSH_EVENTS_PIGZ_ENABLED=$(sh_escape "$GJCXFZKSH_EVENTS_PIGZ_ENABLED")
export BACKEND_MEMORY_CACHE_MAX_BYTES=$(sh_escape "$BACKEND_MEMORY_CACHE_MAX_BYTES")
exec $(sh_escape "$JAVA_CMD") $JAVA_OPTS -jar $(sh_escape "$jar_file") \\
  --server.port=$(sh_escape "$BACKEND_PORT") \\
  --server.undertow.threads.worker=$(sh_escape "$UNDERTOW_WORKER_THREADS") \\
  --server.undertow.threads.io=$(sh_escape "$UNDERTOW_IO_THREADS") \\
  --matsim.data=$(sh_escape "$MATSIM_DATA") \\
  --matsim.cache=$(sh_escape "$MATSIM_CACHE") \\
  --matsim.large-model-threshold-bytes=$(sh_escape "$MATSIM_LARGE_MODEL_THRESHOLD_BYTES") \\
  --matsim.large-model-plans-threshold-bytes=$(sh_escape "$MATSIM_LARGE_MODEL_PLANS_THRESHOLD_BYTES") \\
  --matsim.large-model-events-threshold-bytes=$(sh_escape "$MATSIM_LARGE_MODEL_EVENTS_THRESHOLD_BYTES") \\
  --matsim.cache-build-threads=$(sh_escape "$MATSIM_CACHE_BUILD_THREADS") \\
  --matsim.processing-threads=$(sh_escape "$MATSIM_PROCESSING_THREADS") \\
  --matsim.trajectory-query-concurrency=$(sh_escape "$MATSIM_TRAJECTORY_QUERY_CONCURRENCY") \\
  --matsim.cache-status-probe-ttl-ms=$(sh_escape "$MATSIM_CACHE_STATUS_PROBE_TTL_MS") \\
  --matsim.cache-prebuild-on-startup=$(sh_escape "$MATSIM_CACHE_PREBUILD_ON_STARTUP") \\
  --matsim.real-passenger-cache-prebuild-on-startup=$(sh_escape "$MATSIM_REAL_PASSENGER_CACHE_PREBUILD_ON_STARTUP") \\
  --matsim.real-passenger-aggregate-builder-enabled=$(sh_escape "$MATSIM_REAL_PASSENGER_AGGREGATE_BUILDER_ENABLED") \\
  --matsim.real-passenger-python-command=$(sh_escape "$MATSIM_REAL_PASSENGER_PYTHON_COMMAND") \\
  --matsim.cache-builder.isolated-enabled=$(sh_escape "$MATSIM_CACHE_BUILDER_ISOLATED_ENABLED") \\
  --matsim.cache-builder.xms=$(sh_escape "$MATSIM_CACHE_BUILDER_XMS") \\
  --matsim.cache-builder.xmx=$(sh_escape "$MATSIM_CACHE_BUILDER_XMX") \\
  --matsim.cache-builder.max-metaspace=$(sh_escape "$MATSIM_CACHE_BUILDER_MAX_METASPACE") \\
  --matsim.runtime.max-compute-models=$(sh_escape "$MATSIM_RUNTIME_MAX_COMPUTE_MODELS") \\
  --matsim.runtime.max-visual-models=$(sh_escape "$MATSIM_RUNTIME_MAX_VISUAL_MODELS") \\
  --matsim.cache-min-free-bytes=$(sh_escape "$MATSIM_CACHE_MIN_FREE_BYTES") \\
  --matsim.cache-clean-old-versions=$(sh_escape "$MATSIM_CACHE_CLEAN_OLD_VERSIONS") \\
  --cors.allowed-origins=$(sh_escape "$CORS_ALLOWED_ORIGINS") \\
  > $(sh_escape "$LOG_DIR/backend-console.log") 2>&1
EOF
  chmod +x "$script_file"
}

write_frontend_launcher() {
  local script_file="$1"

  cat > "$script_file" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
echo \$\$ > $(sh_escape "$FRONTEND_PID")
export FRONTEND_DIST_DIR=$(sh_escape "$DIST_DIR")
export FRONTEND_HOST=$(sh_escape "$HOST")
export FRONTEND_PORT=$(sh_escape "$FRONTEND_PORT")
exec $(sh_escape "$NODE_CMD") $(sh_escape "$FRONTEND_SERVER_SCRIPT") \\
  > $(sh_escape "$LOG_DIR/frontend-console.log") 2>&1
EOF
  chmod +x "$script_file"
}

build_backend() {
  require_cmd "$MVN_CMD"
  say "Building backend..."
  # target/classes may have been written by an IDE compiler. A clean build prevents
  # unresolved/error bytecode from being mistaken for an up-to-date Maven output.
  "$MVN_CMD" -f "$BACKEND_DIR/pom.xml" -DskipTests clean package
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
    defaultBasemapKey: "$(js_escape "$MAP_BASEMAP_DEFAULT")",
    networkLineMinPixels: Number("$(js_escape "$NETWORK_LINE_MIN_PIXELS")") || 0.8,
    networkLineSoftEdgePixels: Number("$(js_escape "$NETWORK_LINE_SOFT_EDGE_PIXELS")") || 0,
    mapPixelRatio: Number("$(js_escape "$MAP_PIXEL_RATIO")") || null
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

build_frontend_only() {
  build_frontend
  prepare_runtime_assets
}

start_backend() {
  ensure_dirs
  require_cmd "$JAVA_CMD"

  if is_service_running "$BACKEND_PID" "$BACKEND_PROCESS_MARKER"; then
    say "Backend already running, pid=$(pid_value "$BACKEND_PID")"
    return
  fi

  local managed_pid
  managed_pid="$(find_managed_listener_pid "$BACKEND_PORT" "$BACKEND_PROCESS_MARKER" || true)"
  if [ -n "$managed_pid" ]; then
    printf '%s\n' "$managed_pid" > "$BACKEND_PID"
    say "Backend already running on port $BACKEND_PORT, adopted pid=$managed_pid"
    return
  fi
  if port_is_listening "$BACKEND_PORT"; then
    say "Backend port $BACKEND_PORT is already in use:" >&2
    describe_port_owner "$BACKEND_PORT" >&2
    die "cannot start backend while port $BACKEND_PORT is occupied"
  fi

  local jar_file
  jar_file="$(find_backend_jar)"
  [ -n "$jar_file" ] && [ -f "$jar_file" ] || die "backend jar not found. Run ./deploy.sh build or ./deploy.sh deploy first."

  if [ ! -d "$MATSIM_DATA" ]; then
    say "MATSIM_DATA does not exist, creating: $MATSIM_DATA"
    mkdir -p "$MATSIM_DATA"
  fi
  if [ ! -d "$MATSIM_CACHE" ]; then
    say "MATSIM_CACHE does not exist, creating: $MATSIM_CACHE"
    mkdir -p "$MATSIM_CACHE"
  fi

  local launcher="$RUN_DIR/start-backend.sh"
  write_backend_launcher "$launcher" "$jar_file"
  start_detached backend "$launcher" "$BACKEND_PID" "$BACKEND_SCREEN" \
    "$BACKEND_PROCESS_MARKER" "$BACKEND_PORT" "$LOG_DIR/backend-console.log"
  say "Backend started on port $BACKEND_PORT, pid=$(pid_value "$BACKEND_PID")"
}

start_frontend() {
  ensure_dirs
  require_cmd "$NODE_CMD"
  [ -d "$DIST_DIR" ] || die "frontend dist not found: $DIST_DIR. Run ./deploy.sh build or ./deploy.sh deploy first."
  [ -f "$FRONTEND_SERVER_SCRIPT" ] || die "frontend server script not found: $FRONTEND_SERVER_SCRIPT"

  if is_service_running "$FRONTEND_PID" "$FRONTEND_PROCESS_MARKER"; then
    say "Frontend already running, pid=$(pid_value "$FRONTEND_PID")"
    return
  fi

  local managed_pid
  managed_pid="$(find_managed_listener_pid "$FRONTEND_PORT" "$FRONTEND_PROCESS_MARKER" || true)"
  if [ -n "$managed_pid" ]; then
    printf '%s\n' "$managed_pid" > "$FRONTEND_PID"
    say "Frontend already running on port $FRONTEND_PORT, adopted pid=$managed_pid"
    return
  fi
  if port_is_listening "$FRONTEND_PORT"; then
    say "Frontend port $FRONTEND_PORT is already in use:" >&2
    describe_port_owner "$FRONTEND_PORT" >&2
    die "cannot start frontend while port $FRONTEND_PORT is occupied"
  fi

  local launcher="$RUN_DIR/start-frontend.sh"
  write_frontend_launcher "$launcher"
  start_detached frontend "$launcher" "$FRONTEND_PID" "$FRONTEND_SCREEN" \
    "$FRONTEND_PROCESS_MARKER" "$FRONTEND_PORT" "$LOG_DIR/frontend-console.log"
  say "Frontend started on port $FRONTEND_PORT, pid=$(pid_value "$FRONTEND_PID")"
}

stop_one() {
  local name="$1"
  local pid_file="$2"
  local screen_name="$3"
  local port="$4"
  local marker="$5"
  local pid

  pid="$(pid_value "$pid_file" 2>/dev/null || true)"
  if ! process_matches "$pid" "$marker"; then
    pid="$(find_managed_listener_pid "$port" "$marker" || true)"
  fi
  if [ -z "$pid" ]; then
    say "$name is not running"
    stop_screen "$screen_name"
    rm -f "$pid_file"
    return
  fi

  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    say "Stopped $name, pid=$pid"
    if ! wait_for_exit "$pid" 75; then
      kill -9 "$pid" 2>/dev/null || true
      wait_for_exit "$pid" 25 || true
      say "Force stopped $name, pid=$pid"
    fi
  else
    say "$name pid file exists, but process is not running: pid=$pid"
  fi
  stop_screen "$screen_name"
  rm -f "$pid_file"
}

stop_all() {
  stop_one frontend "$FRONTEND_PID" "$FRONTEND_SCREEN" "$FRONTEND_PORT" "$FRONTEND_PROCESS_MARKER"
  stop_one backend "$BACKEND_PID" "$BACKEND_SCREEN" "$BACKEND_PORT" "$BACKEND_PROCESS_MARKER"
}

stop_frontend() {
  stop_one frontend "$FRONTEND_PID" "$FRONTEND_SCREEN" "$FRONTEND_PORT" "$FRONTEND_PROCESS_MARKER"
}

start_all() {
  prepare_runtime_assets
  start_backend
  start_frontend
}

start_frontend_only() {
  prepare_runtime_assets
  start_frontend
}

restart_all() {
  stop_all
  start_all
}

restart_frontend() {
  stop_frontend
  start_frontend_only
}

serve_all() {
  trap 'stop_all; exit 0' INT TERM
  start_all
  while true; do
    if ! is_service_running "$BACKEND_PID" "$BACKEND_PROCESS_MARKER" \
      || ! is_service_running "$FRONTEND_PID" "$FRONTEND_PROCESS_MARKER"; then
      say "A service exited; stopping remaining services"
      stop_all
      exit 1
    fi
    sleep 5
  done
}

print_one_status() {
  local name="$1"
  local pid_file="$2"
  local port="$3"
  local marker="$4"
  local pid

  if is_service_running "$pid_file" "$marker"; then
    say "$name: running, pid=$(pid_value "$pid_file")"
  elif pid="$(find_managed_listener_pid "$port" "$marker" || true)" && [ -n "$pid" ]; then
    say "$name: running without pid file, pid=$pid"
  elif port_is_listening "$port"; then
    say "$name: stopped (port $port is occupied by another process)"
  else
    say "$name: stopped"
  fi
}

status_all() {
  ensure_dirs
  print_one_status "Backend" "$BACKEND_PID" "$BACKEND_PORT" "$BACKEND_PROCESS_MARKER"
  print_one_status "Frontend" "$FRONTEND_PID" "$FRONTEND_PORT" "$FRONTEND_PROCESS_MARKER"
  say "Frontend URL: http://localhost:$FRONTEND_PORT"
  say "Backend API:   http://localhost:$BACKEND_PORT"
  say "MATSIM_DATA:   $MATSIM_DATA"
  say "MATSIM_CACHE:  $MATSIM_CACHE"
  say "Large model threshold bytes: $MATSIM_LARGE_MODEL_THRESHOLD_BYTES"
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
MATSIM_CACHE="/Volumes/USB DISK/pt_cache/"
MATSIM_LARGE_MODEL_THRESHOLD_BYTES=21474836480
MATSIM_LARGE_MODEL_PLANS_THRESHOLD_BYTES=8589934592
MATSIM_LARGE_MODEL_EVENTS_THRESHOLD_BYTES=8589934592
MATSIM_CACHE_BUILD_THREADS=1
MATSIM_PROCESSING_THREADS=4
MATSIM_CACHE_STATUS_PROBE_TTL_MS=10000
MATSIM_CACHE_PREBUILD_ON_STARTUP=false
MATSIM_REAL_PASSENGER_CACHE_PREBUILD_ON_STARTUP=false
MATSIM_REAL_PASSENGER_AGGREGATE_BUILDER_ENABLED=true
MATSIM_REAL_PASSENGER_PYTHON_COMMAND=python3
MATSIM_CACHE_BUILDER_ISOLATED_ENABLED=true
MATSIM_CACHE_BUILDER_XMS=256m
MATSIM_CACHE_BUILDER_XMX=6g
MATSIM_CACHE_BUILDER_MAX_METASPACE=512m
MATSIM_RUNTIME_MAX_COMPUTE_MODELS=1
MATSIM_RUNTIME_MAX_VISUAL_MODELS=1
BACKEND_MEMORY_CACHE_MAX_BYTES=268435456
UNDERTOW_WORKER_THREADS=32
UNDERTOW_IO_THREADS=4
MATSIM_CACHE_MIN_FREE_BYTES=21474836480
MATSIM_CACHE_CLEAN_OLD_VERSIONS=true
JAVA_OPTS="-Xms128m -Xmx2g -Xss768k -XX:SoftMaxHeapSize=768m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=300000 -XX:G1PeriodicGCSystemLoadThreshold=0 -XX:+ExplicitGCInvokesConcurrent -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m"
JAVA_CMD=java
MVN_CMD=mvn
NPM_CMD=npm
NODE_CMD=node
VEHICLE_MODELS_PATH=""
VEHICLE_MODELS_BASE_URL="/models/vehicles"
CITY_BUILDINGS_SHP_PATH=""
CITY_BUILDINGS_STATIC_PATH=""
CITY_BUILDINGS_ENABLED=true
CITY_BUILDINGS_HEIGHT_FIELD=HEIGHT
CITY_BUILDINGS_MAX_FEATURES=20000
MAP_TILE_URL_TEMPLATE=""
MAP_BASEMAP_DEFAULT=esri-dark
NETWORK_LINE_MIN_PIXELS=0.8
NETWORK_LINE_SOFT_EDGE_PIXELS=0
MAP_PIXEL_RATIO=""
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
    <string>serve</string>
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
  deploy-frontend     Build latest frontend and restart frontend only
  build               Build backend/frontend and write runtime config
  build-frontend      Build frontend and write runtime config
  start               Start existing built artifacts without rebuilding
  start-frontend      Start frontend only from existing built artifacts
  stop                Stop frontend and backend
  stop-frontend       Stop frontend only
  restart             Restart existing built artifacts without rebuilding
  restart-frontend    Restart frontend only without rebuilding
  serve               Start existing artifacts and keep them supervised
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
    deploy-frontend|frontend)
      build_frontend_only
      restart_frontend
      status_all
      ;;
    build)
      build_all
      ;;
    build-frontend)
      build_frontend_only
      ;;
    start)
      start_all
      status_all
      ;;
    start-frontend)
      start_frontend_only
      status_all
      ;;
    stop)
      stop_all
      ;;
    stop-frontend)
      stop_frontend
      ;;
    restart)
      restart_all
      status_all
      ;;
    restart-frontend)
      restart_frontend
      status_all
      ;;
    serve)
      serve_all
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
