#!/bin/sh
# rpm passes 1 on an upgrade (and runs the old package's %preun after the new
# %post), deb passes "upgrade": the service must survive both. Only a removal
# stops and disables it.
set -e
case "${1:-}" in
  1|upgrade|failed-upgrade) exit 0 ;;
esac
systemd_dir="${GHOSTLANE_SYSTEMD_DIR:-/run/systemd/system}"
openrc_dir="${GHOSTLANE_OPENRC_DIR:-/run/openrc}"
if [ -d "$systemd_dir" ] && command -v systemctl >/dev/null 2>&1; then
  systemctl stop ghostlane.service >/dev/null 2>&1 || true
  systemctl disable ghostlane.service >/dev/null 2>&1 || true
elif [ -d "$openrc_dir" ] && command -v rc-update >/dev/null 2>&1; then
  rc-service ghostlane stop >/dev/null 2>&1 || true
  rc-update del ghostlane default >/dev/null 2>&1 || true
fi
