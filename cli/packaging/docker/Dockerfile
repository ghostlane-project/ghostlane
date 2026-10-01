# ghcr.io/ghostlane-project/ghostlane-cli — the static binary on distroless.
# Build context: cli/ after `packaging/docker/stage.sh dist <version>` (or
# `make docker-stage`), which puts the released binaries under dist/docker/.
#
#   proxy: docker run -d --name ghostlane -p 127.0.0.1:1080:1080 \
#            -e GHOSTLANE_PROXY_LISTEN=0.0.0.0 -e GHOSTLANE_PROXY_USER=u -e GHOSTLANE_PROXY_PASS=p \
#            -v ghostlane:/data ghcr.io/ghostlane-project/ghostlane-cli \
#            run --subscription 'https://…' --connect DE --mode proxy
#   tun:   add --cap-add NET_ADMIN --device /dev/net/tun and --mode tun
#   then:  docker exec ghostlane ghostlane status
FROM gcr.io/distroless/static-debian12:latest
ARG TARGETPLATFORM
COPY dist/docker/${TARGETPLATFORM}/ghostlane /usr/bin/ghostlane
# the state (config, caches, device id) lives on the volume, whatever the
# command line says
ENV GHOSTLANE_STATE_DIR=/data
VOLUME /data
WORKDIR /data
ENTRYPOINT ["/usr/bin/ghostlane"]
CMD ["run"]
