// Command harness links the three Go cores the way the iPhone tunnel
// extension links them, one runtime for all three (scripts/build-cores-ios.sh:
// the patched sing-box, libXray with the patched xray-core, and the olcRTC
// engine's mobile package, built with the same tags), starts one of them on a
// config, and writes the Go runtime's own figures four times a second.
//
// The point is the figures a footprint cannot give: what the runtime holds
// and has not returned (sys minus released, the number an iOS memory limit is
// measured against; see feedback in PacketTunnelProvider.goMemoryLimit), the
// live heap, the collections. The process's resident size is read from
// outside by lab.py, the same way for this and for the exec'd cores.
//
//	harness -core singbox|xray|none -config FILE -mode ios|plain \
//	        -stats FILE -ready FILE
//
// -mode ios applies, in the extension's order (iosApp/PacketTunnel/
// PacketTunnelProvider.swift startEngine), what the phone applies:
//
//   - LibboxSetMemoryLimit(true): GC percent 10, and on iOS a 45 MiB limit
//     (sing-box experimental/libbox/memory.go; set here explicitly, since this
//     is not GOOS=ios);
//   - for Xray: the h2 receive windows through the environment
//     (XrayEngine.swift: 524288 per stream, 1048576 per connection), then
//     libXray's memory.InitForceFree as memory_ios.go does it (GC percent 10,
//     30 MiB, debug.FreeOSMemory every second, forever), which a non-iOS
//     build of libXray compiles out;
//   - after every engine: MobileSetMemoryLimit(40 MiB), the olcRTC mobile
//     package's own call, so the limit in force is the extension's;
//   - MemoryWatch.swift's release: debug.FreeOSMemory when the footprint is
//     above 36 MiB, at most every 5 s, checked four times a second.
//
// -mode plain applies none of it: Go's defaults, as the exec'd cores run on
// Android and the desktops.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime/debug"
	"runtime/metrics"
	"strings"
	"syscall"
	"time"
)

const (
	mib = 1 << 20
	// PacketTunnelProvider.goMemoryLimit.
	iosGoMemoryLimit = 40 * mib
	// What LibboxSetMemoryLimit(true) sets on iOS.
	libboxIOSLimit = 45 * mib
	// libXray memory/memory_ios.go.
	libXrayIOSLimit = 30 * mib
	// XrayEngine.swift h2StreamWindow / h2ConnWindow.
	iosH2StreamWindow = "524288"
	iosH2ConnWindow   = "1048576"
	// MemoryWatch.swift releaseAbove / releaseEvery.
	releaseAbove = 36 * mib
	releaseEvery = 5 * time.Second
)

func main() {
	core := flag.String("core", "none", "singbox | xray | none")
	configPath := flag.String("config", "", "the core's config (JSON)")
	mode := flag.String("mode", "plain", "ios | plain")
	statsPath := flag.String("stats", "harness-stats.jsonl", "where the runtime figures go, one JSON line per sample")
	readyPath := flag.String("ready", "", "written once the core is up and the settings are in force")
	every := flag.Duration("every", 250*time.Millisecond, "sampling period")
	flag.Parse()

	if err := run(*core, *configPath, *mode, *statsPath, *readyPath, *every); err != nil {
		fmt.Fprintln(os.Stderr, "harness:", err)
		os.Exit(1)
	}
}

func run(core, configPath, mode, statsPath, readyPath string, every time.Duration) error {
	if mode != "ios" && mode != "plain" {
		return fmt.Errorf("-mode %q: ios or plain", mode)
	}
	var config []byte
	if core != "none" {
		var err error
		if config, err = os.ReadFile(configPath); err != nil {
			return err
		}
	}
	stats, err := os.Create(statsPath)
	if err != nil {
		return err
	}
	defer stats.Close()
	sampler := newSampler(stats)
	sampler.sample("start")

	ios := mode == "ios"
	if ios {
		applyLibboxMemoryLimit()
		if !libboxSetsIOSLimit() {
			debug.SetMemoryLimit(libboxIOSLimit)
		}
	}

	var stop func() error
	switch core {
	case "singbox":
		stop, err = startSingBox(config)
	case "xray":
		if ios {
			if err = os.Setenv("XRAY_XHTTP_H2_STREAM_WINDOW", iosH2StreamWindow); err != nil {
				return err
			}
			if err = os.Setenv("XRAY_XHTTP_H2_CONN_WINDOW", iosH2ConnWindow); err != nil {
				return err
			}
			if !libXrayForcesFree() {
				initForceFreeAsOnIOS()
			}
		}
		stop, err = startXray(string(config))
	case "none":
		stop = func() error { return nil }
	default:
		err = fmt.Errorf("-core %q: singbox, xray or none", core)
	}
	if err != nil {
		return err
	}
	if ios {
		setOlcrtcMemoryLimit(iosGoMemoryLimit)
	}
	sampler.sample("ready")
	if readyPath != "" {
		line, _ := json.Marshal(map[string]any{
			"pid":             os.Getpid(),
			"core":            core,
			"mode":            mode,
			"limit_in_force":  olcrtcMemoryLimit(),
			"gc_percent":      gcPercent(),
			"versions":        coreVersions(),
			"footprint_bytes": selfFootprint(),
		})
		if err := os.WriteFile(readyPath, append(line, '\n'), 0o644); err != nil {
			return err
		}
	}

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	var lastRelease time.Time
	for {
		select {
		case <-signals:
			sampler.sample("stop")
			done := make(chan error, 1)
			go func() { done <- stop() }()
			select {
			case err := <-done:
				if err != nil && !errors.Is(err, os.ErrClosed) {
					fmt.Fprintln(os.Stderr, "harness: stop:", err)
				}
			case <-time.After(5 * time.Second):
				fmt.Fprintln(os.Stderr, "harness: the core did not stop within 5 s")
			}
			return nil
		case <-ticker.C:
			note := ""
			if ios && selfFootprint() > releaseAbove && time.Since(lastRelease) > releaseEvery {
				lastRelease = time.Now()
				debug.FreeOSMemory()
				note = "released"
			}
			sampler.sample(note)
		}
	}
}

// initForceFreeAsOnIOS is libXray's memory.InitForceFree from memory_ios.go,
// which only a GOOS=ios build contains: the goroutine is never stopped there
// either.
func initForceFreeAsOnIOS() {
	debug.SetGCPercent(10)
	debug.SetMemoryLimit(libXrayIOSLimit)
	go func() {
		for {
			time.Sleep(time.Second)
			debug.FreeOSMemory()
		}
	}()
}

func gcPercent() int {
	p := debug.SetGCPercent(100)
	debug.SetGCPercent(p)
	return p
}

type sampler struct {
	out     *json.Encoder
	samples []metrics.Sample
}

var metricNames = []string{
	"/memory/classes/total:bytes",
	"/memory/classes/heap/released:bytes",
	"/memory/classes/heap/objects:bytes",
	"/memory/classes/heap/stacks:bytes",
	"/memory/classes/os-stacks:bytes",
	"/gc/cycles/total:gc-cycles",
	"/sched/goroutines:goroutines",
	"/gc/gomemlimit:bytes",
	"/gc/gogc:percent",
}

func newSampler(f *os.File) *sampler {
	s := &sampler{out: json.NewEncoder(f)}
	for _, name := range metricNames {
		s.samples = append(s.samples, metrics.Sample{Name: name})
	}
	return s
}

// sample writes one line. runtime/metrics does not stop the world, unlike
// ReadMemStats; "total" is MemStats.Sys and "released" HeapReleased, so
// total - released is the `sys - rel` of the extension's trace.
func (s *sampler) sample(note string) {
	metrics.Read(s.samples)
	row := map[string]any{"t": time.Now().UnixMilli(), "footprint": selfFootprint()}
	if note != "" {
		row["note"] = note
	}
	for _, m := range s.samples {
		key := strings.TrimPrefix(m.Name, "/")
		key = key[:strings.IndexByte(key, ':')]
		switch m.Value.Kind() {
		case metrics.KindUint64:
			row[key] = m.Value.Uint64()
		case metrics.KindFloat64:
			row[key] = m.Value.Float64()
		}
	}
	_ = s.out.Encode(row)
}
