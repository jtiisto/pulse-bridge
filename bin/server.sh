#!/bin/bash
#
# Manage the Pulse Bridge FastAPI server from a deployed checkout.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/server"
VENV_DIR="$SERVER_DIR/.venv"
RUN_DIR="$PROJECT_ROOT/run"
LOG_DIR="$PROJECT_ROOT/logs"
PID_FILE="$RUN_DIR/pulse-bridge-server.pid"
LOG_FILE="$LOG_DIR/server.log"
HOST="${PULSE_BRIDGE_HOST:-0.0.0.0}"
PORT="${PULSE_BRIDGE_PORT:-8000}"

usage() {
    echo "Usage: $0 {start|stop|restart|status|logs|follow}"
}

is_running() {
    [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1
}

start() {
    if is_running; then
        echo "Pulse Bridge server is already running (pid $(cat "$PID_FILE"))"
        return
    fi

    if [ ! -x "$VENV_DIR/bin/uvicorn" ]; then
        echo "Missing $VENV_DIR/bin/uvicorn"
        echo "Run: cd $SERVER_DIR && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
        exit 1
    fi

    mkdir -p "$RUN_DIR" "$LOG_DIR" "$SERVER_DIR/data"
    cd "$SERVER_DIR"
    nohup "$VENV_DIR/bin/uvicorn" main:app --host "$HOST" --port "$PORT" >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    echo "Pulse Bridge server started on $HOST:$PORT (pid $(cat "$PID_FILE"))"
    echo "Logs: $LOG_FILE"
}

stop() {
    if ! is_running; then
        echo "Pulse Bridge server is not running"
        rm -f "$PID_FILE"
        return
    fi

    pid="$(cat "$PID_FILE")"
    kill "$pid"
    for _ in {1..20}; do
        if ! kill -0 "$pid" >/dev/null 2>&1; then
            rm -f "$PID_FILE"
            echo "Pulse Bridge server stopped"
            return
        fi
        sleep 0.25
    done

    echo "Pulse Bridge server did not stop after SIGTERM; pid $pid is still running"
    exit 1
}

status() {
    if is_running; then
        echo "Pulse Bridge server is running (pid $(cat "$PID_FILE"))"
    else
        echo "Pulse Bridge server is not running"
        rm -f "$PID_FILE"
    fi
}

case "${1:-}" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        start
        ;;
    status)
        status
        ;;
    logs)
        [ -f "$LOG_FILE" ] && tail -n 80 "$LOG_FILE" || echo "No log file yet: $LOG_FILE"
        ;;
    follow)
        mkdir -p "$LOG_DIR"
        touch "$LOG_FILE"
        tail -f "$LOG_FILE"
        ;;
    *)
        usage
        exit 1
        ;;
esac
