#!/bin/sh
# rpm passes 1 on an upgrade (and runs the old package's %preun after the new
# %post), deb passes "upgrade": the service must survive both. Only a removal
# stops and disables it.
set -e
case "${1:-}" in
  1|upgrade|failed-upgrade) exit 0 ;;
esac
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl stop ghostlane.service >/dev/null 2>&1 || true
  systemctl disable ghostlane.service >/dev/null 2>&1 || true
fi
