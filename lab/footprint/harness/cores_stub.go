//go:build labnocores

// Stand-ins for cores.go, so the harness's own code can be vetted on a
// machine that must not build the cores (see lab/footprint/README.md). The
// lab never builds with this tag.

package main

import (
	"errors"
	"runtime"
	"runtime/debug"
)

var errNoCores = errors.New("built with labnocores: no cores linked")

func applyLibboxMemoryLimit()                   { debug.SetGCPercent(10) }
func libboxSetsIOSLimit() bool                  { return false }
func libXrayForcesFree() bool                   { return runtime.GOOS == "ios" }
func setOlcrtcMemoryLimit(bytes int64)          { debug.SetMemoryLimit(bytes) }
func olcrtcMemoryLimit() int64                  { return debug.SetMemoryLimit(-1) }
func startSingBox([]byte) (func() error, error) { return nil, errNoCores }
func startXray(string) (func() error, error)    { return nil, errNoCores }
func coreVersions() map[string]string           { return map[string]string{"go": runtime.Version()} }
