//! Go's `time.Time` as the state files hold it.
//!
//! A file holds an instant as RFC 3339 text with the offset it was written
//! with (`2026-11-21T13:00:00.123456789+03:00`), and Go writes the same text
//! back after reading it. So the offset is kept beside the instant.

use std::fmt::{self, Write as _};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::de::{Deserialize, Deserializer, Error as _};
use serde::ser::{Serialize, Serializer};

const SECONDS_A_DAY: i64 = 86_400;

/// An instant and the offset its text is written with.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Timestamp {
    /// Seconds since 1970-01-01T00:00:00Z.
    seconds: i64,
    nanos: u32,
    /// Seconds east of UTC.
    offset: i32,
}

impl Timestamp {
    /// Go's zero `time.Time`, 0001-01-01T00:00:00Z: what a field that was
    /// never set holds, and what Go writes for it.
    pub const ZERO: Timestamp = Timestamp {
        seconds: -62_135_596_800,
        nanos: 0,
        offset: 0,
    };

    /// The instant `seconds` and `nanos` after the Unix epoch, in UTC.
    pub fn from_unix(seconds: i64, nanos: u32) -> Timestamp {
        todo!()
    }

    /// Now, in UTC.
    pub fn now() -> Timestamp {
        todo!()
    }

    /// Seconds since the Unix epoch.
    pub fn unix(&self) -> i64 {
        todo!()
    }

    pub fn subsec_nanos(&self) -> u32 {
        todo!()
    }

    /// Seconds east of UTC of the zone the text is written in.
    pub fn offset_seconds(&self) -> i32 {
        todo!()
    }

    /// Go's `IsZero`.
    pub fn is_zero(&self) -> bool {
        todo!()
    }

    /// Go's `Format(time.RFC3339Nano)`: the fraction without its trailing
    /// zeros and absent when there is none, `Z` for an offset of zero.
    pub fn to_rfc3339(self) -> String {
        todo!()
    }

    /// What `time.Time` takes from a JSON string or from text: RFC 3339 as
    /// Go's `time.Parse(time.RFC3339, …)` reads it. Looser than the RFC in
    /// the places Go is: an hour of one digit, a comma before the fraction,
    /// an offset up to 24:60.
    pub fn parse_rfc3339(text: &str) -> Option<Timestamp> {
        todo!()
    }

    /// What yaml.v3 takes for a timestamp in a plain scalar: a year of four
    /// digits and a dash, then one of its four layouts.
    pub fn parse_yaml(text: &str) -> Option<Timestamp> {
        todo!()
    }
}

impl Default for Timestamp {
    fn default() -> Timestamp {
        todo!()
    }
}

impl fmt::Display for Timestamp {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        todo!()
    }
}

impl Serialize for Timestamp {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        todo!()
    }
}

impl<'de> Deserialize<'de> for Timestamp {
    /// A `null` leaves Go's field as it was, which for a new value is zero.
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Timestamp, D::Error> {
        todo!()
    }
}

/// What stands between the date and the time in a layout.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Separator {
    /// `T`
    Upper,
    /// `t`
    Lower,
    /// A space in the layout, which takes any run of spaces.
    Spaces,
    /// The layout ends with the date.
    DateOnly,
}

/// Go's `time.Parse` for the layouts of this file. `fixed` asks for two
/// digits in the month, the day, the minute and the second (`01`, `02`, `04`,
/// `05`); without it one digit will do (`1`, `2`, `4`, `5`). The hour is one
/// or two digits in every layout (`15`).
fn parse_layout(text: &str, fixed: bool, separator: Separator, zone: bool) -> Option<Timestamp> {
    todo!()
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn digit(&self, at: usize) -> Option<u32> {
        todo!()
    }

    fn literal(&mut self, byte: u8) -> Option<()> {
        todo!()
    }

    /// Go's `getnum`: two digits, or one where the layout allows it.
    fn number(&mut self, fixed: bool) -> Option<u32> {
        todo!()
    }

    /// A space in a layout: what follows is spaces or the end, and all of
    /// the spaces are taken.
    fn spaces(&mut self) -> Option<()> {
        todo!()
    }

    /// The fraction of a second, which Go reads after the seconds of every
    /// layout: a period or a comma and digits, of which nine count.
    fn fraction(&mut self) -> u32 {
        todo!()
    }

    /// `Z07:00`: a `Z`, or a sign, two digits, a colon and two digits. Go
    /// lets the hours reach 24 and the minutes 60.
    fn zone(&mut self) -> Option<i32> {
        todo!()
    }
}

fn days_in(month: u32, year: i64) -> u32 {
    todo!()
}

/// Days from 1970-01-01 to a date of the proleptic Gregorian calendar.
fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    todo!()
}

/// The date that many days after 1970-01-01.
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    todo!()
}
