# cli/Makefile
#
# The Linux CLI: the Rust program (cli/Cargo.toml), the core's engine and the
# tun's hev beside it, in deb, rpm, apk and Arch packages, tarballs, sums, a
# signature and the image. packaging/build-arch.sh builds and stages one
# architecture; the engine (ghostlane-engine) and hev (hev-socks5-tunnel) come
# from the Core build (see its header).
VERSION ?= dev
# CRYPT_KEY_V1: the app's OLCBOX_CRYPT_KEY_V1 (base64 master key of crypt1 lists); empty = crypt1 unavailable
CRYPT_KEY_V1 ?=
export VERSION CRYPT_KEY_V1

.PHONY: build test lint
build:
	GHOSTLANE_VERSION='$(VERSION)' GHOSTLANE_CRYPT_KEY_V1='$(CRYPT_KEY_V1)' cargo build --locked --release
test:
	cargo test --locked --workspace
lint:
	cargo fmt --all --check
	cargo clippy --locked --workspace --all-targets -- -D warnings

ARCHES  ?= amd64 arm64 arm7
FORMATS := deb rpm apk archlinux
NFPM    ?= nfpm

.PHONY: build-all package tarballs sums dist docker-stage docker-image clean
build-all:
	@for a in $(ARCHES); do bash packaging/build-arch.sh $$a || exit 1; done

# nfpm expands no variables inside contents[].src, so the spec is templated per arch.
package: build-all
	@for a in $(ARCHES); do \
	  sed "s/\$${ARCH}/$$a/g; s/\$${VERSION}/$(VERSION)/g" packaging/nfpm.yaml > dist/nfpm-$$a.yaml; \
	  for f in $(FORMATS); do \
	    ext=$$f; [ "$$f" = archlinux ] && ext=pkg.tar.zst; \
	    echo "== $$f $$a"; \
	    $(NFPM) package -p $$f -f dist/nfpm-$$a.yaml -t dist/ghostlane-cli-$(VERSION)-linux-$$a.$$ext || exit 1; \
	  done; \
	done

tarballs: build-all
	@for a in $(ARCHES); do \
	  stage=dist/stage-$$a; rm -rf $$stage; mkdir -p $$stage; \
	  command cp -f dist/linux-$$a/ghostlane dist/linux-$$a/ghostlane-engine dist/linux-$$a/ghostlane-hev packaging/ghostlane.service packaging/README.md LICENSE COPYRIGHT $$stage/; \
	  command cp -f packaging/openrc/ghostlane $$stage/ghostlane.openrc; \
	  tar -C $$stage -czf dist/ghostlane-cli-$(VERSION)-linux-$$a.tar.gz .; rm -rf $$stage; \
	done

sums:
	cd dist && sha256sum ghostlane-cli-$(VERSION)-linux-* > ghostlane-cli-$(VERSION)-SHA256SUMS

dist: package tarballs sums

# The Docker image holds the released tarballs' binaries (stage.sh), nothing
# built separately. docker-image builds and loads this machine's platform as
# ghostlane-cli:dev; the release pushes every platform.
docker-stage: tarballs
	sh packaging/docker/stage.sh dist $(VERSION)

docker-image: docker-stage
	docker buildx build --load -t ghostlane-cli:dev -f packaging/docker/Dockerfile .

clean:
	rm -rf dist target

.PHONY: sign
sign:
	test -n "$(KEY)" || (echo "KEY=<ed25519 private key pem> is required" && exit 1)
	openssl pkeyutl -sign -inkey $(KEY) -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -out dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	openssl pkeyutl -verify -pubin -inkey packaging/cli-release.pub.pem -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -sigfile dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	sed "/^VERSION=/s/__VERSION__/$(VERSION)/" packaging/install.sh > dist/install.sh
