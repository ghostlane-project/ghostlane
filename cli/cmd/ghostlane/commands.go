package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"text/tabwriter"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
)

const docsURL = "https://github.com/ghostlane-project/ghostlane/blob/main/docs/cli.md"

const usage = `ghostlane — Ghostlane for Linux servers and boxes.
Connects the lines of your subscription — olcRTC rooms (a tunnel inside a
video call), VLESS Reality, Hysteria2, XHTTP — with failover between them,
and routes the machine through the tunnel: the whole box (tun) or a local
proxy (proxy).

Usage:
  ghostlane <command> [arguments] [flags]

Commands:
  add <source>         Add a subscription: a list URL, a ghostlane:// link, or one olcrtc://, vless:// or hysteria2:// line
  list                 Every line with its index, country and carrier
  connect <selector>   Connect a country (DE), a label ("DE · SJ") or an index (6); --tun [--kill-switch] or --proxy
  status               What is connected, since when, and the proxy lines to paste
  disconnect           Stop the tunnel and forget the selection
  refresh              Re-fetch every list now
  remove <sub>         Remove a subscription by URL, masked-URL prefix or title
  version              Version, engine and sing-box pins, release signing key
  run                  The daemon itself (systemd runs it; use directly in containers)
  help [command]       This text, or one command's details and examples

Quick start:
  ghostlane add 'https://…/sub/…'      # the URL your provider gave you (or one vless:// / hy2:// / olcrtc:// line)
  ghostlane list
  ghostlane connect DE --proxy         # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
  ghostlane connect DE --tun           # the whole machine; SSH and your services keep working
  ghostlane status

Global flags:
  --socket <path>   control socket (default /run/ghostlane/ghostlane.sock)
  --json            machine-readable output for list, status and version

Docs: ` + docsURL + `
`

var commandHelp = map[string]string{
	"add": `ghostlane add <source>

Adds a subscription and fetches it at once. <source> is one of:
  a list URL          https://provider.example/sub/…  (the ?c=olcbox variant of a partner list)
  a ghostlane:// link ghostlane://add?url=…  or  ghostlane://add/<url>
  one line            'olcrtc://telemost?vp8channel@…#<key>$DE · olcRTC', a vless:// or a
                      hysteria2:// (hy2://) line, as a provider hands them out

The list is refreshed on the interval the provider announces (default every
24 h) and on 'ghostlane refresh'. The URL is a credential: it is stored
root-only under /var/lib/ghostlane and never printed in full.

Examples:
  ghostlane add 'https://sub.example/sub/a1b2c3/token?c=olcbox'
  ghostlane add 'olcrtc://wbstream?vp8channel@room_x#<64 hex>$DE · VP8 · WB'
  ghostlane add 'vless://<uuid>@203.0.113.9:443?type=tcp&security=reality&sni=…&pbk=…&sid=…&flow=xtls-rprx-vision#DE'
`,
	"list": `ghostlane list [--json]

Shows every line of every subscription: its index (for 'connect <index>'),
country, kind (olcrtc, vless, hysteria2), carrier and label. A line this
version cannot connect (VLESS over grpc/ws, trojan, shadowsocks, vmess) is
listed with a note saying why.

Example:
  ghostlane list
   #   COUNTRY  KIND    CARRIER     LABEL             NOTE
   1   DE       olcrtc  telemost    🇩🇪 DE · VP8
   2   DE       olcrtc  wbstream    🇩🇪 DE · VP8 · WB
`,
	"connect": `ghostlane connect <selector> [--tun [--kill-switch] | --proxy] [--subscription <url-prefix>]

<selector> is a country code (all of that country's lines, tried in the
list's order with failover between them — rooms and servers alike), an
exact label from 'list' (one line), or an index from 'list'. Lists whose
labels carry no country code (city names) are selected by label or index.

Which core carries what: olcRTC rooms run in the engine, XHTTP lines in
Xray-core, VLESS Reality and Hysteria2 in sing-box itself. In tun mode a
Reality or Hysteria2 server is proven through a local proxy first, so a dead
server never gets the tun.

  --proxy   (default) a local SOCKS5 + HTTP proxy on 127.0.0.1:1080; needs no
            privileges; 'status' prints the environment lines to paste.
  --tun     routes the whole machine through the tunnel. Inbound services
            (SSH, web, databases) keep answering on the public address; private
            ranges stay direct; IPv6 is refused rather than leaked. Needs the
            service's CAP_NET_ADMIN (the installed unit has it).
  --kill-switch  with --tun: while the selection stands and no line is up
            (boot, a failover, a dead server), traffic that is not for the
            tunnel is refused instead of leaking. The LAN, Docker networks and
            inbound services keep working; 'disconnect' or stopping the service
            opens the box again. Connections opened before the switch keep
            their path.

The selection is remembered: the service reconnects it after a reboot.
A line that does not answer within a minute, or three failed liveness probes
in a row, moves the connection to the next line of the selection.

Examples:
  ghostlane connect DE --tun
  ghostlane connect "🇩🇪 DE · SJ" --proxy
  ghostlane connect 6
`,
	"status": `ghostlane status [--json]

Prints the state (idle | connecting | up | failed), the selection and mode,
the connected line and carrier, since when, the last error, the proxy
addresses with the export lines to paste, and each subscription with its
next refresh, traffic quota and last error.
`,
	"disconnect": `ghostlane disconnect

Stops the tunnel (the tun and its rules go away; traffic flows directly
again) and clears the stored selection, so nothing reconnects at boot.
`,
	"refresh": `ghostlane refresh

Re-fetches every subscription now. A refresh never disconnects by itself;
if the connected room or key was rotated, the daemon reconnects to the new one.
`,
	"remove": `ghostlane remove <subscription>

Removes a subscription by its URL, by the masked URL 'status' shows
(prefix is enough), or by its title. Removing the subscription in use
disconnects first.

Example:
  ghostlane remove 'https://sub.example/sub/a1b2c3/'
`,
	"version": `ghostlane version [--json]

Prints the version, the pinned engine and sing-box versions, and the sha256
of the public key every release is signed with (install.sh verifies against it).
`,
	"run": `ghostlane run [--state-dir <dir>] [--socket <path>] [--subscription <url> --connect <selector> --mode tun|proxy [--kill-switch]]

The daemon, in the foreground. systemd (or OpenRC) runs it as user ghostlane
with CAP_NET_ADMIN; in a container run it yourself:

  ghostlane run --state-dir /data --subscription 'https://…' --connect DE --mode proxy

The proxy is set through the environment in a container: GHOSTLANE_PROXY_LISTEN
(0.0.0.0 to publish the port), GHOSTLANE_PROXY_PORT, GHOSTLANE_PROXY_USER and
GHOSTLANE_PROXY_PASS — a listen that is not loopback needs the credentials.
For --mode tun the container needs --cap-add NET_ADMIN --device /dev/net/tun.
Other flags: --probe-url <url> (liveness probe through the tunnel).
Image: ghcr.io/ghostlane-project/ghostlane-cli (see docs/cli.md).
`,
}

func helpFor(cmd string) (string, bool) {
	h, ok := commandHelp[cmd]
	return h, ok
}

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "run":
		if len(rest) > 0 && (rest[0] == "-h" || rest[0] == "--help") {
			h, _ := helpFor("run")
			fmt.Fprint(stdout, h)
			return 0
		}
		return runDaemon(rest, stderr)
	case "version":
		if len(rest) > 0 && (rest[0] == "-h" || rest[0] == "--help") {
			h, _ := helpFor("version")
			fmt.Fprint(stdout, h)
			return 0
		}
		fs := flag.NewFlagSet("version", flag.ContinueOnError)
		fs.SetOutput(stderr)
		asJSON := fs.Bool("json", false, "machine-readable output")
		if err := fs.Parse(rest); err != nil {
			return 2
		}
		v := versionInfo()
		if *asJSON {
			return printJSON(stdout, v)
		}
		crypt := "unavailable (no key in this build)"
		if v.Crypt1 {
			crypt = "available"
		}
		fmt.Fprintf(stdout, "ghostlane %s\nengine %s\nsing-box %s\nxray-core %s\ncrypt1 lists %s\nrelease signing key sha256 %s\n", v.Version, v.Engine, v.SingBox, v.Xray, crypt, releasePubKeyFingerprint())
		return 0
	case "add", "list", "connect", "disconnect", "status", "refresh", "remove":
		if len(rest) > 0 && (rest[0] == "-h" || rest[0] == "--help") {
			h, _ := helpFor(cmd)
			fmt.Fprint(stdout, h)
			return 0
		}
		return client(cmd, rest, stdout, stderr)
	case "-h", "--help", "help":
		if len(rest) > 0 {
			if h, ok := helpFor(rest[0]); ok {
				fmt.Fprint(stdout, h)
				return 0
			}
			fmt.Fprintf(stderr, "no help for %q\n%s", rest[0], usage)
			return 2
		}
		fmt.Fprint(stdout, usage)
		return 0
	}
	fmt.Fprintf(stderr, "unknown command %q\n%s", cmd, usage)
	return 2
}

func client(cmd string, args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet(cmd, flag.ContinueOnError)
	fs.SetOutput(stderr)
	socket := fs.String("socket", ipc.DefaultSocketPath, "control socket")
	asJSON := fs.Bool("json", false, "machine-readable output")
	tun := fs.Bool("tun", false, "route the whole machine (needs the service's CAP_NET_ADMIN)")
	proxyMode := fs.Bool("proxy", false, "local SOCKS5/HTTP proxy only")
	killSwitch := fs.Bool("kill-switch", false, "with --tun: refuse traffic outside the tunnel while no line is up")
	sub := fs.String("subscription", "", "restrict to one subscription")
	// flags may follow the positional argument: `connect DE --tun`
	var positional []string
	for len(args) > 0 {
		if err := fs.Parse(args); err != nil {
			return 2
		}
		args = fs.Args()
		if len(args) > 0 {
			positional = append(positional, args[0])
			args = args[1:]
		}
	}
	req := ipc.Request{Verb: cmd, Subscription: *sub}
	switch cmd {
	case "add", "remove", "connect":
		if len(positional) != 1 {
			fmt.Fprintf(stderr, "%s needs exactly one argument\n%s", cmd, usage)
			return 2
		}
		switch cmd {
		case "add":
			req.Source = positional[0]
		case "remove":
			req.Subscription = positional[0]
		default:
			req.Selector = positional[0]
			switch {
			case *tun && *proxyMode:
				fmt.Fprintln(stderr, "pick one of --tun and --proxy")
				return 2
			case *killSwitch && !*tun:
				fmt.Fprintln(stderr, "the kill switch needs --tun")
				return 2
			case *tun:
				req.Mode = "tun"
				req.KillSwitch = *killSwitch
			case *proxyMode:
				req.Mode = "proxy"
			}
		}
	default:
		if len(positional) != 0 {
			fmt.Fprintf(stderr, "%s takes no argument\n", cmd)
			return 2
		}
	}
	resp, err := ipc.Call(context.Background(), *socket, req)
	if err != nil {
		fmt.Fprintln(stderr, err)
		if errors.Is(err, ipc.ErrDaemonDown) {
			return 3
		}
		return 1
	}
	if !resp.OK {
		fmt.Fprintf(stderr, "%s: %s\n", resp.Error, resp.Message)
		return 1
	}
	if *asJSON {
		return printJSON(stdout, resp)
	}
	switch cmd {
	case "status":
		printStatus(stdout, resp.Status)
	case "list":
		printList(stdout, resp.Entries)
	default:
		if resp.Message != "" {
			fmt.Fprintln(stdout, resp.Message)
		}
	}
	return 0
}

func printJSON(w io.Writer, v any) int {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		return 1
	}
	return 0
}

func printStatus(w io.Writer, st *ipc.Status) {
	if st == nil {
		fmt.Fprintln(w, "no status")
		return
	}
	fmt.Fprintf(w, "State:     %s\n", st.State)
	if st.Selector != "" {
		mode := st.Mode + " mode"
		if st.KillSwitch {
			mode += ", kill switch on"
		}
		fmt.Fprintf(w, "Selection: %s (%s)\n", st.Selector, mode)
	}
	if st.Line != nil {
		fmt.Fprintf(w, "Line:      %s", st.Line.Label)
		if st.Line.Carrier != "" {
			fmt.Fprintf(w, " via %s", st.Line.Carrier)
		}
		fmt.Fprintln(w)
	}
	if st.Since != "" {
		fmt.Fprintf(w, "Since:     %s\n", st.Since)
	}
	if st.LastError != "" {
		fmt.Fprintf(w, "Last error: %s\n", st.LastError)
	}
	if st.Proxy != nil {
		fmt.Fprintf(w, "Proxy:     socks5://%s  http://%s\n", st.Proxy.Socks, st.Proxy.HTTP)
		fmt.Fprintf(w, "  export ALL_PROXY=socks5h://%s\n  export http_proxy=http://%s https_proxy=http://%s\n", st.Proxy.Socks, st.Proxy.HTTP, st.Proxy.HTTP)
	}
	for _, s := range st.Subscriptions {
		fmt.Fprintf(w, "List:      %s", s.URL)
		if s.Title != "" {
			fmt.Fprintf(w, "  (%s)", s.Title)
		}
		if s.NextRefresh != "" {
			fmt.Fprintf(w, "  next refresh %s", s.NextRefresh)
		}
		if s.UserInfo != nil && s.UserInfo.Total > 0 {
			fmt.Fprintf(w, "  used %.1f of %.1f GB", float64(s.UserInfo.Upload+s.UserInfo.Download)/1e9, float64(s.UserInfo.Total)/1e9)
		}
		if s.Error != "" {
			fmt.Fprintf(w, "  ERROR: %s", s.Error)
		}
		fmt.Fprintln(w)
	}
}

func printList(w io.Writer, entries []ipc.EntryView) {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "#\tCOUNTRY\tKIND\tCARRIER\tLABEL\tNOTE")
	for _, e := range entries {
		fmt.Fprintf(tw, " %d \t%s\t%s\t%s\t%s\t%s\n", e.Index, e.Country, e.Kind, e.Carrier, e.Label, e.Problem)
	}
	_ = tw.Flush()
	if len(entries) == 0 {
		fmt.Fprintln(w, "(no lines; ghostlane add <url>)")
	}
}
