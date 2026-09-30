# cli/Makefile
GO      ?= go
TAGS    ?= with_utls,with_quic
VERSION ?= dev
ENGINE_PIN  := $(shell sed -n 's/^OLCRTC_VERSION="$${OLCRTC_VERSION:-\(.*\)}"$$/\1/p' ../scripts/cores-pins.sh)
SINGBOX_PIN := $(shell grep -m1 'SINGBOX_VERSION:' ../.github/workflows/release.yml | sed 's/.*"\(.*\)".*/\1/')
XRAY_PIN    := $(shell sed -n 's/^[[:space:]]*github.com\/xtls\/xray-core \([^ ]*\).*/\1/p' go.mod | head -1)
# CRYPT_KEY_V1: the app's OLCBOX_CRYPT_KEY_V1 (base64 master key of crypt1 lists); empty = crypt1 unavailable
CRYPT_KEY_V1 ?=
LDFLAGS := -s -w -X main.version=$(VERSION) -X main.enginePin=$(ENGINE_PIN) -X main.singboxPin=$(SINGBOX_PIN) -X main.xrayPin=$(XRAY_PIN) -X main.cryptKeyV1=$(CRYPT_KEY_V1)
export CGO_ENABLED = 0

.PHONY: build test vet lint
build:
	$(GO) build -trimpath -tags $(TAGS) -ldflags '$(LDFLAGS)' -o dist/ghostlane ./cmd/ghostlane
test:
	$(GO) test -tags $(TAGS) ./...
vet:
	$(GO) vet -tags $(TAGS) ./...

ARCHES  := amd64 arm64 arm7
FORMATS := deb rpm apk archlinux
NFPM    ?= nfpm

.PHONY: build-all package tarballs sums dist clean
build-all:
	@for a in $(ARCHES); do \
	  goarch=$$a; goarm=; if [ "$$a" = arm7 ]; then goarch=arm; goarm=7; fi; \
	  echo "== $$a"; \
	  GOOS=linux GOARCH=$$goarch GOARM=$$goarm $(GO) build -trimpath -tags $(TAGS) -ldflags '$(LDFLAGS)' -o dist/linux-$$a/ghostlane ./cmd/ghostlane || exit 1; \
	done

# nfpm expands no variables inside contents[].src, so the spec is templated per arch.
package: build-all
	@for a in $(ARCHES); do \
	  sed "s/\$${GOARCH}/$$a/g; s/\$${VERSION}/$(VERSION)/g" packaging/nfpm.yaml > dist/nfpm-$$a.yaml; \
	  for f in $(FORMATS); do \
	    ext=$$f; [ "$$f" = archlinux ] && ext=pkg.tar.zst; \
	    echo "== $$f $$a"; \
	    $(NFPM) package -p $$f -f dist/nfpm-$$a.yaml -t dist/ghostlane-cli-$(VERSION)-linux-$$a.$$ext || exit 1; \
	  done; \
	done

tarballs: build-all
	@for a in $(ARCHES); do \
	  stage=dist/stage-$$a; rm -rf $$stage; mkdir -p $$stage; \
	  command cp -f dist/linux-$$a/ghostlane packaging/ghostlane.service packaging/README.md LICENSE COPYRIGHT $$stage/; \
	  tar -C $$stage -czf dist/ghostlane-cli-$(VERSION)-linux-$$a.tar.gz .; rm -rf $$stage; \
	done

sums:
	cd dist && sha256sum ghostlane-cli-$(VERSION)-linux-* > ghostlane-cli-$(VERSION)-SHA256SUMS

dist: package tarballs sums

clean:
	rm -rf dist

.PHONY: sign
sign:
	test -n "$(KEY)" || (echo "KEY=<ed25519 private key pem> is required" && exit 1)
	openssl pkeyutl -sign -inkey $(KEY) -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -out dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	openssl pkeyutl -verify -pubin -inkey packaging/cli-release.pub.pem -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -sigfile dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	sed "/^VERSION=/s/__VERSION__/$(VERSION)/" packaging/install.sh > dist/install.sh

# An xray binary at the pinned module version, for the Reality end-to-end test
# (XRAY_SERVER_BIN): the server side must be its own process.
.PHONY: xray-server
xray-server:
	$(GO) build -trimpath -o dist/xray-server github.com/xtls/xray-core/main
