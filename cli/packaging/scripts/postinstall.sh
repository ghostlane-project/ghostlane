#!/bin/sh
set -e
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
  systemctl enable ghostlane.service >/dev/null 2>&1 || true
  if systemctl is-active --quiet ghostlane.service; then
    systemctl restart ghostlane.service || true
  else
    systemctl start ghostlane.service || true
  fi
fi
echo "ghostlane installed. Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)."
