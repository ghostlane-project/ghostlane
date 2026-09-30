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

const usage = `usage: ghostlane <command> [flags]

  add <list-url | ghostlane:// | olcrtc://>   add a subscription or one room line
  list [--json]                               the lines of every subscription
  connect <selector> [--tun | --proxy]        selector: country (DE), label, or index from list
  disconnect
  status [--json]
  refresh                                     re-fetch every list now
  remove <subscription>                       by URL, masked URL prefix, or title
  version [--json]
  run                                         the daemon (systemd runs this)

flags: --socket <path>  (default /run/ghostlane/ghostlane.sock)
`

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "run":
		return runDaemon(rest, stderr)
	case "version":
		fs := flag.NewFlagSet("version", flag.ContinueOnError)
		fs.SetOutput(stderr)
		asJSON := fs.Bool("json", false, "machine-readable output")
		if err := fs.Parse(rest); err != nil {
			return 2
		}
		v := ipc.VersionInfo{Version: version, Engine: enginePin, SingBox: singboxPin}
		if *asJSON {
			return printJSON(stdout, v)
		}
		fmt.Fprintf(stdout, "ghostlane %s\nengine %s\nsing-box %s\nrelease signing key sha256 %s\n", v.Version, v.Engine, v.SingBox, releasePubKeyFingerprint())
		return 0
	case "add", "list", "connect", "disconnect", "status", "refresh", "remove":
		return client(cmd, rest, stdout, stderr)
	case "-h", "--help", "help":
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
			case *tun:
				req.Mode = "tun"
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
		fmt.Fprintf(w, "Selection: %s (%s mode)\n", st.Selector, st.Mode)
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

// releasePubKeyFingerprint is replaced by pubkey.go once the release key exists.
func releasePubKeyFingerprint() string { return "unset" }
