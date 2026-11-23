//! The kinds of line the core runs. None is written yet.

use crate::spec::Spec;

/// A kind of line.
pub trait Line: Sync {
    /// The kind's name, the app's object's.
    fn name(&self) -> &'static str;

    /// The shapes this kind writes, each by its slug.
    fn shapes(&self) -> &'static [&'static str];

    /// The slug of `spec`'s shape when the link is this kind's.
    fn shape(&self, spec: &Spec) -> Option<&'static str>;
}

/// Every kind, in the app's order.
pub static LINES: [&dyn Line; 0] = [];

/// The kind `spec` belongs to.
pub fn of(spec: &Spec) -> Option<&'static dyn Line> {
    LINES
        .iter()
        .copied()
        .find(|line| line.shape(spec).is_some())
}
