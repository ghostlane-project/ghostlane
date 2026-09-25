package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

/** What a location actually speaks, derived from its own link. */
enum class TransportKind {
    Reality,
    Hysteria2,
    Xhttp,
    Grpc,
    /** VLESS over TLS without Reality keys. */
    Tls,
    Trojan,
    Shadowsocks,
    Vmess,
    Olcrtc;

    /** Short label for the UI. */
    fun label(): String = when (this) {
        Reality -> "Reality"
        Hysteria2 -> "Hysteria2"
        Xhttp -> "XHTTP"
        Grpc -> "gRPC"
        Tls -> "TLS"
        Trojan -> "Trojan"
        Shadowsocks -> "Shadowsocks"
        Vmess -> "VMess"
        Olcrtc -> "olcRTC"
    }
}

fun LocationConfig.transportKind(): TransportKind = when (kind) {
    LocationKind.Olcrtc -> TransportKind.Olcrtc
    LocationKind.Hysteria2 -> TransportKind.Hysteria2
    LocationKind.Trojan -> TransportKind.Trojan
    LocationKind.Shadowsocks -> TransportKind.Shadowsocks
    LocationKind.Vmess -> TransportKind.Vmess
    LocationKind.Vless -> {
        when (val spec = rawLink?.let { LinkParser.parse(it) }) {
            is OutboundSpec.Vless ->
                when {
                    spec.transport is TransportSpec.Xhttp -> TransportKind.Xhttp
                    spec.transport is TransportSpec.Grpc -> TransportKind.Grpc
                    spec.publicKey.isNotBlank() -> TransportKind.Reality
                    else -> TransportKind.Tls
                }
            else -> TransportKind.Tls
        }
    }
}

object TransportSelector {

    /**
     * Reality first: fastest and usually passes. Hysteria2 next: fast wherever UDP
     * survives. XHTTP last: the most DPI-resistant and the slowest.
     */
    private val DEFAULT_ORDER = listOf(
        TransportKind.Reality,
        TransportKind.Hysteria2,
        TransportKind.Grpc,
        TransportKind.Xhttp,
        TransportKind.Tls,
        TransportKind.Trojan,
        TransportKind.Vmess,
        TransportKind.Shadowsocks
    )

    /**
     * Candidates to try, best first.
     *
     * [lastKnownGood] (a storageId) is promoted to the front so the common case
     * probes exactly once; the rest keep the default order. olcRTC is never a
     * candidate — see TransportGroup.
     */
    fun orderCandidates(
        group: List<LocationEntry>,
        lastKnownGood: String? = null
    ): List<LocationEntry> {
        val usable = group.filter { it.location.kind != LocationKind.Olcrtc }
        val byPreference = usable.sortedBy { entry ->
            val index = DEFAULT_ORDER.indexOf(entry.location.transportKind())
            if (index < 0) DEFAULT_ORDER.size else index
        }
        val promoted = byPreference.firstOrNull { it.storageId == lastKnownGood }
            ?: return byPreference
        return listOf(promoted) + byPreference.filterNot { it.storageId == promoted.storageId }
    }
}
