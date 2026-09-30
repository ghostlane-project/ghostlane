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
  echo "ghostlane installed and running. Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)."
else
  echo "ghostlane installed. This system has no systemd: run the daemon yourself,"
  echo "  ghostlane run   (as root, or as a user with CAP_NET_ADMIN for --tun)"
  echo "or wrap that command in your init system; then: ghostlane add <list-url>; ghostlane connect DE --proxy"
fi
