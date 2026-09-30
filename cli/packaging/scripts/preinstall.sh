#!/bin/sh
set -e
if ! getent group ghostlane >/dev/null 2>&1; then
  if command -v groupadd >/dev/null 2>&1; then groupadd -r ghostlane; else addgroup -S ghostlane; fi
fi
if ! getent passwd ghostlane >/dev/null 2>&1; then
  if command -v useradd >/dev/null 2>&1; then
    useradd -r -g ghostlane -d /var/lib/ghostlane -s /sbin/nologin -c "Ghostlane tunnel" ghostlane
  else
    adduser -S -G ghostlane -h /var/lib/ghostlane -s /sbin/nologin ghostlane
  fi
fi
