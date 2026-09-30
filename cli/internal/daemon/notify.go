package daemon

import (
	"net"
	"os"
)

// NotifyReady tells systemd (Type=notify) the daemon serves; a no-op elsewhere.
func NotifyReady() {
	path := os.Getenv("NOTIFY_SOCKET")
	if path == "" {
		return
	}
	conn, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: path, Net: "unixgram"})
	if err != nil {
		return
	}
	defer func() { _ = conn.Close() }()
	_, _ = conn.Write([]byte("READY=1"))
}
