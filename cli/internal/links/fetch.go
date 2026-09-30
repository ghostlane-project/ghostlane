package links

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	neturl "net/url"
)

var errBadURL = errors.New("list: the URL cannot be parsed")

const (
	UserAgentPrefix = "Ghostlane-cli/"
	maxBody         = 4 << 20
)

func Fetch(ctx context.Context, client *http.Client, url, userAgent string) ([]byte, Headers, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, Headers{}, errBadURL
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/plain, */*")
	resp, err := client.Do(req)
	if err != nil {
		// *url.Error prints the URL, and the URL is the credential.
		var ue *neturl.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, Headers{}, fmt.Errorf("list: fetch: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, Headers{}, fmt.Errorf("list: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return nil, Headers{}, err
	}
	if len(body) > maxBody {
		return nil, Headers{}, fmt.Errorf("list: body over %d bytes", maxBody)
	}
	return body, ParseHeaders(resp.Header), nil
}
