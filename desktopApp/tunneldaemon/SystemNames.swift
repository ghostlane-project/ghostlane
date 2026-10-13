// Who the system asks for a name while a tunnel is up.
//
// A tun takes the machine's routes and leaves its resolver where it was. Where
// that resolver is the router, as on most home networks, its address is on the
// local link, which no default route outranks: every name the system looked up
// went to the router beside the tunnel, in the clear, and the answer it gave
// was the address the connection then used. Measured on macOS 26 with the
// app's own config, question and answer both on the physical interface.
//
// A route to the resolver through the tun does not move those questions: the
// system's resolver binds its sockets to the service's interface. What does is
// a resolver of the tunnel's own in the system's dynamic store, for every
// domain and ahead of the others: one key, under `State:`, which the system
// forgets at a restart and this daemon removes whenever its tunnel is gone.
// The address is inside the tun's own network, so the question enters the tun,
// where sing-box answers it (`hijack-dns`).
import Foundation
import SystemConfiguration

enum SystemNames {
    private static let key = "State:/Network/Service/org.olcbox.app.tunnel/DNS" as CFString

    private static func store() -> SCDynamicStore? {
        SCDynamicStoreCreate(nil, "org.olcbox.app.tunneld" as CFString, nil, nil)
    }

    /// Makes [address] the resolver the system asks first, for every name.
    static func ask(_ address: String) -> Bool {
        guard let store = store() else { return false }
        let value: [String: Any] = [
            "ServerAddresses": [address],
            // The empty domain is every domain: a supplemental resolver that
            // matches all of them is asked before the network's own.
            "SupplementalMatchDomains": [""],
        ]
        return SCDynamicStoreSetValue(store, key, value as CFDictionary)
    }

    /// Takes the key away, so the system asks whoever it asked before. True when none is left.
    @discardableResult
    static func restore() -> Bool {
        guard let store = store() else { return false }
        guard SCDynamicStoreCopyValue(store, key) != nil else { return true }
        return SCDynamicStoreRemoveValue(store, key)
    }

    static var isSet: Bool {
        guard let store = store() else { return false }
        return SCDynamicStoreCopyValue(store, key) != nil
    }
}
