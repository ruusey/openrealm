#!/bin/bash
###############################################################################
# OpenRealm Game Server — Amazon Linux 2023 deployment script
#
# Installs JDK 17, Maven, builds the jar, and runs as a systemd service
# in -server mode pointing at the production data service.
#
# Usage:
#   chmod +x deploy-server.sh
#   sudo ./deploy-server.sh
#
# After running:
#   sudo systemctl start|stop|restart|status openrealm
#   sudo journalctl -u openrealm -f
#
# IMPORTANT: After deploying, add this server's public IP to the
# TRUSTED_HOSTS env var on the data server (98.95.5.4) so the game
# server can make authenticated API calls. Then restart openrealm-data.
###############################################################################
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: Run this script as root (sudo ./deploy-server.sh)"
  exit 1
fi

### ── Configuration ─────────────────────────────────────────────────────────
APP_NAME="openrealm"
APP_DIR="/opt/${APP_NAME}"
APP_USER="openrealm"
DATA_SERVER_URL="http://98.95.5.4"
GAME_PORT_TCP=2222
GAME_PORT_WS=2223

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================="
echo " OpenRealm Game Server — Amazon Linux Deploy"
echo "============================================="

### ── 1. System packages ────────────────────────────────────────────────────
echo "[1/5] Installing system dependencies..."
dnf update -y -q
dnf install -y -q java-17-amazon-corretto-devel git openssl

### ── 2. Maven ──────────────────────────────────────────────────────────────
echo "[2/5] Installing Maven..."
if ! command -v mvn &>/dev/null; then
  MVN_VER="3.9.9"
  MVN_URL="https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz"
  cd /tmp
  curl -fSL -o "apache-maven-${MVN_VER}-bin.tar.gz" "${MVN_URL}"
  tar -xzf "apache-maven-${MVN_VER}-bin.tar.gz" -C /opt
  ln -sf "/opt/apache-maven-${MVN_VER}/bin/mvn" /usr/local/bin/mvn
  rm -f "apache-maven-${MVN_VER}-bin.tar.gz"
  echo "Maven ${MVN_VER} installed."
else
  echo "Maven already installed: $(mvn -version | head -1)"
fi

### ── 3. Build ──────────────────────────────────────────────────────────────
echo "[3/5] Building openrealm..."
mvn -B clean package -DskipTests -f "${SCRIPT_DIR}/pom.xml"

### ── 4. Install ────────────────────────────────────────────────────────────
echo "[4/5] Installing to ${APP_DIR}..."
mkdir -p "${APP_DIR}"
cp "${SCRIPT_DIR}/target/openrealm.jar" "${APP_DIR}/${APP_NAME}.jar"

if ! id "${APP_USER}" &>/dev/null; then
  useradd --system --no-create-home --shell /sbin/nologin "${APP_USER}"
fi

cat > "${APP_DIR}/env" <<ENVFILE
DATA_SERVER_URL=${DATA_SERVER_URL}
ENVFILE

chmod 600 "${APP_DIR}/env"
chown -R "${APP_USER}:${APP_USER}" "${APP_DIR}"

### ── 5. Systemd service ───────────────────────────────────────────────────
echo "[5/5] Creating systemd service..."
cat > /etc/systemd/system/${APP_NAME}.service <<UNIT
[Unit]
Description=OpenRealm Game Server
After=network.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}
EnvironmentFile=${APP_DIR}/env
ExecStart=/usr/bin/java -Xms512m -Xmx2048m -jar ${APP_DIR}/${APP_NAME}.jar ${DATA_SERVER_URL}
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${APP_NAME}

NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "${APP_NAME}"
systemctl start "${APP_NAME}"

### ── Firewall ──────────────────────────────────────────────────────────────
if command -v firewall-cmd &>/dev/null; then
  firewall-cmd --permanent --add-port=${GAME_PORT_TCP}/tcp
  firewall-cmd --permanent --add-port=${GAME_PORT_WS}/tcp
  firewall-cmd --reload
fi

echo ""
echo "============================================="
echo " GAME SERVER DEPLOYED"
echo "============================================="
echo ""
echo " Service:    sudo systemctl status ${APP_NAME}"
echo " Logs:       sudo journalctl -u ${APP_NAME} -f"
echo " Data server: ${DATA_SERVER_URL}"
echo " TCP port:   ${GAME_PORT_TCP}"
echo " WS port:    ${GAME_PORT_WS}"
echo ""
echo " !! REQUIRED STEPS !!"
echo ""
echo " 1. Open these ports in this EC2 instance's Security Group:"
echo "    - TCP ${GAME_PORT_TCP} (native clients)"
echo "    - TCP ${GAME_PORT_WS}  (web clients)"
echo ""
echo " 2. On the DATA SERVER (98.95.5.4), add this server's PUBLIC IP"
echo "    to TRUSTED_HOSTS so it can call the data API:"
echo ""
echo "    Edit /opt/openrealm-data/env and add:"
echo "      TRUSTED_HOSTS=<this-server-public-ip>"
echo "    Then: sudo systemctl restart openrealm-data"
echo ""
echo "    For multiple game servers, comma-separate:"
echo "      TRUSTED_HOSTS=1.2.3.4,5.6.7.8"
echo "============================================="
