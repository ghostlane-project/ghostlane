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
        Timestamp {
            seconds: seconds.saturating_add(i64::from(nanos / 1_000_000_000)),
            nanos: nanos % 1_000_000_000,
            offset: 0,
        }
    }

    /// Now, in UTC.
    pub fn now() -> Timestamp {
        match SystemTime::now().duration_since(UNIX_EPOCH) {
            Ok(since) => Timestamp::from_unix(
                i64::try_from(since.as_secs()).unwrap_or(i64::MAX),
                since.subsec_nanos(),
            ),
            Err(before) => {
                let before = before.duration();
                let whole = i64::try_from(before.as_secs()).unwrap_or(i64::MAX);
                match before.subsec_nanos() {
                    0 => Timestamp::from_unix(-whole, 0),
                    nanos => Timestamp::from_unix(-whole - 1, 1_000_000_000 - nanos),
                }
            }
        }
    }

    /// Seconds since the Unix epoch.
    pub fn unix(&self) -> i64 {
        self.seconds
    }

    pub fn subsec_nanos(&self) -> u32 {
        self.nanos
    }

    /// Seconds east of UTC of the zone the text is written in.
    pub fn offset_seconds(&self) -> i32 {
        self.offset
    }

    /// Go's `IsZero`.
    pub fn is_zero(&self) -> bool {
        self.seconds == Timestamp::ZERO.seconds && self.nanos == 0
    }

    /// Go's `Format(time.RFC3339Nano)`: the fraction without its trailing
    /// zeros and absent when there is none, `Z` for an offset of zero.
    pub fn to_rfc3339(self) -> String {
        let local = self.seconds.saturating_add(i64::from(self.offset));
        let (year, month, day) = civil_from_days(local.div_euclid(SECONDS_A_DAY));
        let second_of_day = local.rem_euclid(SECONDS_A_DAY);
        let mut out = String::with_capacity(35);
        if year < 0 {
            out.push('-');
        }
        let _ = write!(
            out,
            "{:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}",
            year.unsigned_abs(),
            second_of_day / 3600,
            second_of_day % 3600 / 60,
            second_of_day % 60
        );
        if self.nanos != 0 {
            let fraction = format!("{:09}", self.nanos);
            out.push('.');
            out.push_str(fraction.trim_end_matches('0'));
        }
        if self.offset == 0 {
            out.push('Z');
        } else {
            // Go counts the offset in whole minutes, towards zero
            let minutes = self.offset / 60;
            out.push(if minutes < 0 { '-' } else { '+' });
            let minutes = minutes.unsigned_abs();
            let _ = write!(out, "{:02}:{:02}", minutes / 60, minutes % 60);
        }
        out
    }

    /// What `time.Time` takes from a JSON string or from text: RFC 3339 as
    /// Go's `time.Parse(time.RFC3339, …)` reads it. Looser than the RFC in
    /// the places Go is: an hour of one digit, a comma before the fraction,
    /// an offset up to 24:60.
    pub fn parse_rfc3339(text: &str) -> Option<Timestamp> {
        parse_layout(text, true, Separator::Upper, true)
    }

    /// What yaml.v3 takes for a timestamp in a plain scalar: a year of four
    /// digits and a dash, then one of its four layouts.
    pub fn parse_yaml(text: &str) -> Option<Timestamp> {
        let digits = text.bytes().take_while(u8::is_ascii_digit).count();
        if digits != 4 || text.as_bytes().get(4) != Some(&b'-') {
            return None;
        }
        parse_layout(text, false, Separator::Upper, true)
            .or_else(|| parse_layout(text, false, Separator::Lower, true))
            .or_else(|| parse_layout(text, false, Separator::Spaces, false))
            .or_else(|| parse_layout(text, false, Separator::DateOnly, false))
    }
}

impl Default for Timestamp {
    fn default() -> Timestamp {
        Timestamp::ZERO
    }
}

impl fmt::Display for Timestamp {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_rfc3339())
    }
}

impl Serialize for Timestamp {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_rfc3339())
    }
}

impl<'de> Deserialize<'de> for Timestamp {
    /// A `null` leaves Go's field as it was, which for a new value is zero.
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Timestamp, D::Error> {
        match Option::<String>::deserialize(deserializer)? {
            None => Ok(Timestamp::ZERO),
            Some(text) => Timestamp::parse_rfc3339(&text)
                .ok_or_else(|| D::Error::custom(format!("parsing time {text:?} as RFC 3339"))),
        }
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
    let bytes = text.as_bytes();
    if bytes.len() < 4 || !bytes[..4].iter().all(u8::is_ascii_digit) {
        return None;
    }
    let year = bytes[..4]
        .iter()
        .fold(0_i64, |year, digit| year * 10 + i64::from(digit - b'0'));
    let mut cursor = Cursor { bytes, at: 4 };
    cursor.literal(b'-')?;
    let month = cursor.number(fixed)?;
    cursor.literal(b'-')?;
    let day = cursor.number(fixed)?;
    let (mut hour, mut minute, mut second, mut nanos, mut offset) = (0, 0, 0, 0, 0);
    if separator != Separator::DateOnly {
        match separator {
            Separator::Upper => cursor.literal(b'T')?,
            Separator::Lower => cursor.literal(b't')?,
            _ => cursor.spaces()?,
        }
        hour = cursor.number(false)?;
        cursor.literal(b':')?;
        minute = cursor.number(fixed)?;
        cursor.literal(b':')?;
        second = cursor.number(fixed)?;
        nanos = cursor.fraction();
        if zone {
            offset = cursor.zone()?;
        }
    }
    let in_range = (1..=12).contains(&month)
        && (1..=days_in(month, year)).contains(&day)
        && hour < 24
        && minute < 60
        && second < 60;
    if cursor.at != bytes.len() || !in_range {
        return None;
    }
    let seconds = days_from_civil(year, month, day) * SECONDS_A_DAY
        + i64::from(hour * 3600 + minute * 60 + second)
        - i64::from(offset);
    Some(Timestamp {
        seconds,
        nanos,
        offset,
    })
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn digit(&self, at: usize) -> Option<u32> {
        self.bytes
            .get(at)
            .filter(|byte| byte.is_ascii_digit())
            .map(|byte| u32::from(byte - b'0'))
    }

    fn literal(&mut self, byte: u8) -> Option<()> {
        if self.bytes.get(self.at) != Some(&byte) {
            return None;
        }
        self.at += 1;
        Some(())
    }

    /// Go's `getnum`: two digits, or one where the layout allows it.
    fn number(&mut self, fixed: bool) -> Option<u32> {
        let first = self.digit(self.at)?;
        match self.digit(self.at + 1) {
            Some(second) => {
                self.at += 2;
                Some(first * 10 + second)
            }
            None if fixed => None,
            None => {
                self.at += 1;
                Some(first)
            }
        }
    }

    /// A space in a layout: what follows is spaces or the end, and all of
    /// the spaces are taken.
    fn spaces(&mut self) -> Option<()> {
        if self.bytes.get(self.at).is_some_and(|byte| *byte != b' ') {
            return None;
        }
        while self.bytes.get(self.at) == Some(&b' ') {
            self.at += 1;
        }
        Some(())
    }

    /// The fraction of a second, which Go reads after the seconds of every
    /// layout: a period or a comma and digits, of which nine count.
    fn fraction(&mut self) -> u32 {
        if !matches!(self.bytes.get(self.at), Some(b'.' | b','))
            || self.digit(self.at + 1).is_none()
        {
            return 0;
        }
        let (mut nanos, mut counted) = (0, 0);
        self.at += 1;
        while let Some(digit) = self.digit(self.at) {
            if counted < 9 {
                nanos = nanos * 10 + digit;
                counted += 1;
            }
            self.at += 1;
        }
        nanos * 10_u32.pow(9 - counted)
    }

    /// `Z07:00`: a `Z`, or a sign, two digits, a colon and two digits. Go
    /// lets the hours reach 24 and the minutes 60.
    fn zone(&mut self) -> Option<i32> {
        let bytes = self.bytes;
        let rest = bytes.get(self.at..)?;
        if rest.first() == Some(&b'Z') {
            self.at += 1;
            return Some(0);
        }
        if rest.len() < 6 || rest[3] != b':' {
            return None;
        }
        let hours = self.digit(self.at + 1)? * 10 + self.digit(self.at + 2)?;
        let minutes = self.digit(self.at + 4)? * 10 + self.digit(self.at + 5)?;
        if hours > 24 || minutes > 60 {
            return None;
        }
        let seconds = i32::try_from((hours * 60 + minutes) * 60).ok()?;
        let offset = match rest[0] {
            b'+' => seconds,
            b'-' => -seconds,
            _ => return None,
        };
        self.at += 6;
        Some(offset)
    }
}

fn days_in(month: u32, year: i64) -> u32 {
    match month {
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Days from 1970-01-01 to a date of the proleptic Gregorian calendar.
fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year.rem_euclid(400);
    let month_from_march = (i64::from(month) + 9) % 12;
    let day_of_year = (153 * month_from_march + 2) / 5 + i64::from(day) - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

/// The date that many days after 1970-01-01.
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let days = days + 719_468;
    let era = days.div_euclid(146_097);
    let day_of_era = days.rem_euclid(146_097);
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_from_march = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_from_march + 2) / 5 + 1;
    let month = if month_from_march < 10 {
        month_from_march + 3
    } else {
        month_from_march - 9
    };
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    (year, month, day)
}
