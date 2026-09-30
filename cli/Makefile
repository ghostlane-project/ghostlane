# cli/Makefile
GO      ?= go
TAGS    ?= with_utls,with_quic
VERSION ?= dev
ENGINE_PIN  := $(shell sed -n 's/^OLCRTC_VERSION="$${OLCRTC_VERSION:-\(.*\)}"$$/\1/p' ../scripts/cores-pins.sh)
SINGBOX_PIN := $(shell grep -m1 'SINGBOX_VERSION:' ../.github/workflows/release.yml | sed 's/.*"\(.*\)".*/\1/')
LDFLAGS := -s -w -X main.version=$(VERSION) -X main.enginePin=$(ENGINE_PIN) -X main.singboxPin=$(SINGBOX_PIN)
export CGO_ENABLED = 0

.PHONY: build test vet lint
build:
	$(GO) build -trimpath -tags $(TAGS) -ldflags '$(LDFLAGS)' -o dist/ghostlane ./cmd/ghostlane
test:
	$(GO) test -tags $(TAGS) ./...
vet:
	$(GO) vet -tags $(TAGS) ./...
