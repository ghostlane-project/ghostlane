//go:build !(darwin && cgo)

package main

import (
	"bufio"
	"bytes"
	"os"
	"strconv"
)

// selfFootprint is the resident set (VmRSS) where there is no phys_footprint:
// the stand-in for MemoryWatch's release threshold on Linux and Android.
func selfFootprint() uint64 {
	raw, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return 0
	}
	sc := bufio.NewScanner(bytes.NewReader(raw))
	for sc.Scan() {
		line := sc.Bytes()
		if !bytes.HasPrefix(line, []byte("VmRSS:")) {
			continue
		}
		fields := bytes.Fields(line[len("VmRSS:"):])
		if len(fields) == 0 {
			return 0
		}
		kb, err := strconv.ParseUint(string(fields[0]), 10, 64)
		if err != nil {
			return 0
		}
		return kb * 1024
	}
	return 0
}
