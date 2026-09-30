#!/bin/sh
set -e
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl stop ghostlane.service >/dev/null 2>&1 || true
  systemctl disable ghostlane.service >/dev/null 2>&1 || true
fi
