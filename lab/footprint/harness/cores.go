//go:build !labnocores

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"runtime"

	"github.com/openlibrecommunity/olcrtc/mobile"
	box "github.com/sagernet/sing-box"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	libXray "github.com/xtls/libxray"
)

// applyLibboxMemoryLimit is the extension's LibboxSetMemoryLimit(true).
func applyLibboxMemoryLimit() { libbox.SetMemoryLimit(true) }

// libboxSetsIOSLimit: libbox sets its 45 MiB only where C.IsIos.
func libboxSetsIOSLimit() bool { return C.IsIos }

// libXrayForcesFree: memory_ios.go is compiled only for GOOS=ios.
func libXrayForcesFree() bool { return runtime.GOOS == "ios" }

// setOlcrtcMemoryLimit is the extension's MobileSetMemoryLimit.
func setOlcrtcMemoryLimit(bytes int64) { mobile.SetMemoryLimit(bytes) }

func olcrtcMemoryLimit() int64 { return mobile.MemoryLimit() }

// startSingBox runs a config the way `sing-box run` does (cmd/sing-box
// cmd.go preRun and cmd_run.go create at v1.13.14): the include registries
// in the context, the options read with them, box.New, Start. The extension
// reaches the same box through libbox's command server, whose platform glue
// (the tun's descriptor from NEPacketTunnelFlow, the interface pinning) a
// process on a runner has no use for.
func startSingBox(config []byte) (func() error, error) {
	base := include.Context(service.ContextWith(context.Background(), deprecated.NewStderrManager(log.StdLogger())))
	options, err := sjson.UnmarshalExtendedContext[option.Options](base, config)
	if err != nil {
		return nil, fmt.Errorf("sing-box config: %w", err)
	}
	ctx, cancel := context.WithCancel(base)
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("sing-box create: %w", err)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		cancel()
		return nil, fmt.Errorf("sing-box start: %w", err)
	}
	return func() error {
		err := instance.Close()
		cancel()
		return err
	}, nil
}

type invokeResponse struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Error   string          `json:"error"`
}

// invoke is LibXrayInvoke, the whole of libXray's API, with the envelope
// XrayEngine.swift sends (apiVersion 1).
func invoke(method string, payload map[string]string) (invokeResponse, error) {
	req, err := json.Marshal(map[string]any{"apiVersion": 1, "method": method, "payload": payload})
	if err != nil {
		return invokeResponse{}, err
	}
	var resp invokeResponse
	if err := json.Unmarshal([]byte(libXray.Invoke(string(req))), &resp); err != nil {
		return invokeResponse{}, err
	}
	if !resp.Success {
		if resp.Error == "" {
			resp.Error = "refused without a reason"
		}
		return resp, errors.New("xray " + method + ": " + resp.Error)
	}
	return resp, nil
}

func startXray(config string) (func() error, error) {
	if _, err := invoke("runXrayFromJson", map[string]string{"configJSON": config}); err != nil {
		return nil, err
	}
	return func() error {
		_, err := invoke("stopXray", map[string]string{})
		return err
	}, nil
}

func coreVersions() map[string]string {
	v := map[string]string{"sing-box constant.Version": C.Version, "go": runtime.Version()}
	if resp, err := invoke("xrayVersion", map[string]string{}); err == nil {
		var data struct {
			Version string `json:"version"`
		}
		if json.Unmarshal(resp.Data, &data) == nil {
			v["xray-core"] = data.Version
		}
	}
	return v
}
