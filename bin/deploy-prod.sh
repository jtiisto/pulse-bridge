#!/bin/bash
#
# Deploy Pulse Bridge server/MCP runtime to a production directory.
# Usage: ./bin/deploy-prod.sh /path/to/production/directory

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -z "$1" ]; then
    echo -e "${RED}Error: Production directory not specified${NC}"
    echo ""
    echo "Usage: $0 /path/to/production/directory"
    echo ""
    echo "Example:"
    echo "  $0 /home/user/pulse-bridge-prod"
    exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
    echo -e "${RED}Error: rsync is required for deployment${NC}"
    exit 1
fi

PROD_DIR="${1/#\~/$HOME}"
PROD_PARENT="$(dirname "$PROD_DIR")"
PROD_BASENAME="$(basename "$PROD_DIR")"
if [ -d "$PROD_PARENT" ]; then
    PROD_DIR="$(cd "$PROD_PARENT" && pwd)/$PROD_BASENAME"
fi

if [ "$PROD_DIR" = "$PROJECT_ROOT" ]; then
    echo -e "${RED}Error: Production directory cannot be the development checkout${NC}"
    exit 1
fi

echo -e "${GREEN}Deploying Pulse Bridge server runtime...${NC}"
echo "  Source: $PROJECT_ROOT"
echo "  Target: $PROD_DIR"
echo ""

mkdir -p "$PROD_DIR"

sync_dir() {
    local src="$1"
    local dest="$2"
    local name="$3"

    if [ ! -d "$src" ]; then
        echo -e "  ${YELLOW}Skipping $name (not present locally)${NC}"
        return
    fi

    echo "  Syncing $name..."
    mkdir -p "$dest"
    rsync -a --delete \
        --exclude='__pycache__' \
        --exclude='*.pyc' \
        --exclude='*.pyo' \
        --exclude='.pytest_cache' \
        --exclude='.venv' \
        --exclude='data' \
        --exclude='*.db' \
        --exclude='*.db-wal' \
        --exclude='*.db-shm' \
        --exclude='test_*.py' \
        "$src/" "$dest/"
}

copy_file() {
    local src="$1"
    local dest="$2"
    local name="$3"

    if [ ! -f "$src" ]; then
        echo -e "  ${YELLOW}Skipping $name (not present locally)${NC}"
        return
    fi

    echo "  Copying $name..."
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
}

MANIFEST="$PROJECT_ROOT/bin/deploy.manifest"
if [ ! -f "$MANIFEST" ]; then
    echo -e "${RED}Error: deploy manifest not found: $MANIFEST${NC}"
    exit 1
fi

echo -e "${GREEN}Copying production files (per bin/deploy.manifest)...${NC}"
echo ""

SHIPPED_BIN=""

while read -r action target _rest; do
    [[ -z "$action" || "$action" == \#* ]] && continue

    case "$action" in
        ship-dir)
            sync_dir "$PROJECT_ROOT/$target" "$PROD_DIR/$target" "$target/"
            ;;
        ship-file)
            copy_file "$PROJECT_ROOT/$target" "$PROD_DIR/$target" "$target"
            ;;
        ship-bin)
            SHIPPED_BIN="$SHIPPED_BIN $target"
            src="$PROJECT_ROOT/bin/$target"
            if [ ! -f "$src" ]; then
                echo -e "  ${YELLOW}Skipping bin/$target (not present locally)${NC}"
                continue
            fi
            echo "  Copying bin/$target..."
            mkdir -p "$PROD_DIR/bin"
            cp "$src" "$PROD_DIR/bin/$target"
            chmod +x "$PROD_DIR/bin/$target"
            ;;
        exclude|exclude-bin)
            :
            ;;
        *)
            echo -e "  ${YELLOW}Unknown manifest action '$action' (target: $target) -- skipping${NC}"
            ;;
    esac
done < "$MANIFEST"

mkdir -p "$PROD_DIR/server/data" "$PROD_DIR/logs" "$PROD_DIR/run"

android_changes_present() {
    if ! command -v git >/dev/null 2>&1; then
        return 1
    fi

    (
        cd "$PROJECT_ROOT"
        git status --porcelain -- \
            app \
            core \
            feature \
            build.gradle.kts \
            settings.gradle.kts \
            gradle.properties \
            gradle \
            gradlew \
            gradlew.bat \
            testdata \
            | grep -q .
    )
}

apk_remote_dir() {
    echo "${PULSE_BRIDGE_APK_REMOTE_DIR:-gdrive:Pulse Bridge/APKs}"
}

copy_fresh_apk_if_needed() {
    local apk_policy="${PULSE_BRIDGE_COPY_APK:-changed}"
    local should_copy=0

    case "$apk_policy" in
        always)
            should_copy=1
            ;;
        never)
            return
            ;;
        changed)
            if android_changes_present; then
                should_copy=1
            fi
            ;;
        *)
            echo -e "${YELLOW}Unknown PULSE_BRIDGE_COPY_APK='$apk_policy'; expected changed, always, or never${NC}"
            return
            ;;
    esac

    if [ "$should_copy" -ne 1 ]; then
        echo "  No Android app changes detected; skipping APK build/copy."
        return
    fi

    if ! command -v rclone >/dev/null 2>&1; then
        echo -e "${YELLOW}Android changes detected, but rclone is not installed or not on PATH.${NC}"
        echo -e "${YELLOW}Install/configure rclone or set PULSE_BRIDGE_COPY_APK=never to skip APK upload.${NC}"
        return
    fi

    echo ""
    echo -e "${GREEN}Android app changes detected; building fresh APK...${NC}"
    (
        cd "$PROJECT_ROOT"
        ./gradlew clean :app:assembleDebug
    )

    local apk_src="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$apk_src" ]; then
        echo -e "${RED}Error: expected APK not found: $apk_src${NC}"
        exit 1
    fi

    local stamp
    stamp="$(date +%Y%m%d-%H%M%S)"
    local remote_dir
    remote_dir="$(apk_remote_dir)"
    local stamped_remote="$remote_dir/pulse-bridge-debug-$stamp.apk"
    local latest_remote="$remote_dir/pulse-bridge-debug-latest.apk"

    rclone mkdir "$remote_dir"
    rclone copyto "$apk_src" "$stamped_remote"
    rclone copyto "$apk_src" "$latest_remote"
    echo "  Copied APK: $stamped_remote"
    echo "  Updated APK: $latest_remote"
}

copy_fresh_apk_if_needed

if [ -d "$PROD_DIR/bin" ]; then
    for f in "$PROD_DIR/bin"/*; do
        [ -f "$f" ] || continue
        name="$(basename "$f")"
        case " $SHIPPED_BIN " in
            *" $name "*) ;;
            *) echo -e "  ${YELLOW}Stray prod bin/$name -- not in the manifest; remove it manually if dead${NC}" ;;
        esac
    done
fi

echo ""
echo -e "${GREEN}Deployment complete!${NC}"
echo ""
echo -e "${YELLOW}Next steps for production setup:${NC}"
echo ""
echo "  1. Create or refresh the server virtual environment:"
echo "     cd $PROD_DIR/server"
echo "     python3 -m venv .venv"
echo "     .venv/bin/pip install -r requirements.txt"
echo ""
echo "  2. Start the FastAPI server:"
echo "     cd $PROD_DIR"
echo "     ./bin/server.sh start"
echo ""
echo "  3. Configure the MCP server command where needed:"
echo "     PULSE_BRIDGE_DB_PATH=$PROD_DIR/server/data/pulse_bridge_prod.db PYTHONPATH=$PROD_DIR $PROD_DIR/server/.venv/bin/python -m mcp_servers.pulse_bridge_mcp"
echo ""
echo "  APK copy policy:"
echo "     changed Android files build/upload a debug APK to \${PULSE_BRIDGE_APK_REMOTE_DIR:-gdrive:Pulse Bridge/APKs}"
echo "     override with PULSE_BRIDGE_COPY_APK=always or PULSE_BRIDGE_COPY_APK=never"
echo ""
