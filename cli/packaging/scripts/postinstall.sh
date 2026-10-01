#!/bin/sh
set -e
# The init system is detected by its runtime directory (overridable for tests).
systemd_dir="${GHOSTLANE_SYSTEMD_DIR:-/run/systemd/system}"
openrc_dir="${GHOSTLANE_OPENRC_DIR:-/run/openrc}"
init_script="${GHOSTLANE_INIT_SCRIPT:-/etc/init.d/ghostlane}"
next="Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)."
if [ -d "$systemd_dir" ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
  systemctl enable ghostlane.service >/dev/null 2>&1 || true
  if systemctl is-active --quiet ghostlane.service; then
    systemctl restart ghostlane.service || true
  else
    systemctl start ghostlane.service || true
  fi
  echo "ghostlane installed and running. $next"
elif [ -d "$openrc_dir" ] && command -v rc-update >/dev/null 2>&1 && [ -f "$init_script" ]; then
  rc-update add ghostlane default >/dev/null 2>&1 || true
  if rc-service ghostlane status >/dev/null 2>&1; then
    rc-service ghostlane restart || true
  else
    rc-service ghostlane start || true
  fi
  echo "ghostlane installed and running (OpenRC). $next"
else
  echo "ghostlane installed. This system has no systemd or OpenRC: run the daemon yourself,"
  echo "  ghostlane run   (as root, or as a user with CAP_NET_ADMIN for --tun)"
  echo "or wrap that command in your init system; then: ghostlane add <list-url>; ghostlane connect DE --proxy"
fi
