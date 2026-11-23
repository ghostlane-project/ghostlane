//! The kinds of line the core runs: one module per kind, as the app has one
//! file per kind under `net/core/lines`, with what is that kind's own (which
//! links are its, why the core would refuse one, how a server named by a
//! hostname is pinned, and the outbound it writes).
//!
//! [`LINES`] lists them in the app's order (its index is made from the
//! directory, by file name); the vectors name the app's kinds and their
//! shapes, and the tests fail for one this list lacks.

mod hysteria2;
mod reality;
mod shadowsocks;
mod trojan;
mod vless_shapes;
mod vmess;
mod webtunnel;

pub use reality::hello as reality_hello;
pub use reality::hello_note;

use crate::json::Object;
use crate::spec::Spec;

/// A kind of line.
pub trait Line: Sync {
    /// The kind's name, the app's object's.
    fn name(&self) -> &'static str;

    /// The shapes this kind writes, each by its slug.
    fn shapes(&self) -> &'static [&'static str];

    /// The slug of `spec`'s shape when the link is this kind's, by its shape
    /// alone: a link the kind refuses still has its shape.
    fn shape(&self, spec: &Spec) -> Option<&'static str>;

    /// Why the core would refuse `spec`, a link of this kind, or None. Never
    /// a secret: words about the link's shape.
    fn refusal(&self, spec: &Spec) -> Option<String>;

    /// `spec`, a link of this kind named by a hostname, written for
    /// `address`: each place the name was also the wire's (a TLS server
    /// name, a Host header) is given the name.
    fn pin_host(&self, spec: &Spec, address: &str) -> Spec;

    /// The outbound's `protocol`, `settings` and `streamSettings`, for a
    /// link of this kind that it does not refuse. The tag is the caller's.
    fn put_outbound(&self, spec: &Spec, out: &mut Object);
}

/// Every kind, in the app's order.
pub static LINES: [&dyn Line; 7] = [
    &hysteria2::Hysteria2,
    &reality::Reality,
    &shadowsocks::Shadowsocks,
    &trojan::Trojan,
    &vless_shapes::VlessShapes,
    &vmess::Vmess,
    &webtunnel::WebTunnel,
];

/// The kind `spec` belongs to, by its shape alone; None when it is no kind's.
pub fn of(spec: &Spec) -> Option<&'static dyn Line> {
    LINES
        .iter()
        .copied()
        .find(|line| line.shape(spec).is_some())
}

/// The kind called `name`.
pub fn named(name: &str) -> Option<&'static dyn Line> {
    LINES.iter().copied().find(|line| line.name() == name)
}

/// Called with a spec of another protocol: a kind is asked only about its own links.
fn not_mine(kind: &str) -> ! {
    unreachable!("{kind} was handed a link that is not its own")
}
