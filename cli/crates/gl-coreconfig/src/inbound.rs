//! What listens for the traffic a core carries. Not written yet.

use crate::spec::Plan;

/// Why the core would refuse to listen as `plan` says, or None.
pub fn refusal(_plan: &Plan) -> Option<&'static str> {
    None
}
