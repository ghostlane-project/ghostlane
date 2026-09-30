package daemon

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/proxy"
)

// HTTPProbe fetches probeURL through the SOCKS at socksAddr; 2xx is alive.
func HTTPProbe(probeURL string) func(ctx context.Context, socksAddr, user, pass string) error {
	return func(ctx context.Context, socksAddr, user, pass string) error {
		var auth *proxy.Auth
		if user != "" {
			auth = &proxy.Auth{User: user, Password: pass}
		}
		d, err := proxy.SOCKS5("tcp", socksAddr, auth, &net.Dialer{Timeout: 5 * time.Second})
		if err != nil {
			return err
		}
		cd, ok := d.(proxy.ContextDialer)
		if !ok {
			return fmt.Errorf("probe: dialer has no context dial")
		}
		tr := &http.Transport{DialContext: cd.DialContext, DisableKeepAlives: true}
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
		if err != nil {
			return err
		}
		resp, err := tr.RoundTrip(req)
		if err != nil {
			return err
		}
		resp.Body.Close()
		if resp.StatusCode/100 != 2 {
			return fmt.Errorf("probe: HTTP %d", resp.StatusCode)
		}
		return nil
	}
}
