//! The clock the daemon reads. In the program it is the wall clock; in the
//! cases it is paused and only moves when a case moves it, so a probe interval
//! or a backoff is an amount of time, never a sleep on a runner.

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use gocompat::time::Timestamp;

/// What the daemon asks for "now".
pub type Clock = Arc<dyn Fn() -> Timestamp>;

/// Milliseconds since the Unix epoch, the unit every daemon deadline uses.
pub fn millis(at: Timestamp) -> i64 {
    at.unix() * 1000 + i64::from(at.subsec_nanos() / 1_000_000)
}

/// A clock that stands still until a case advances it.
pub struct FakeClock {
    at: AtomicI64,
}

impl FakeClock {
    /// An arbitrary but fixed instant: cases do not depend on the day.
    pub fn new() -> Arc<Self> {
        Arc::new(FakeClock {
            at: AtomicI64::new(1_760_000_000_000),
        })
    }

    pub fn millis(&self) -> i64 {
        self.at.load(Ordering::SeqCst)
    }

    pub fn now(&self) -> Timestamp {
        let at = self.millis();
        Timestamp::from_unix(at / 1000, ((at % 1000) * 1_000_000) as u32)
    }

    /// Move by `by` milliseconds, never backwards.
    pub fn advance(&self, by: i64) {
        self.advance_to(self.millis() + by);
    }

    /// Move to an instant, never backwards.
    pub fn advance_to(&self, at: i64) {
        let mut now = self.millis();
        while at > now {
            match self
                .at
                .compare_exchange(now, at, Ordering::SeqCst, Ordering::SeqCst)
            {
                Ok(_) => return,
                Err(current) => now = current,
            }
        }
    }

    /// The closure the daemon holds.
    pub fn handle(self: &Arc<Self>) -> Clock {
        let clock = Arc::clone(self);
        Arc::new(move || clock.now())
    }
}
