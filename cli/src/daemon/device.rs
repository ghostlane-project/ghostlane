//! The file the olcRTC engine keeps this machine's id in. The daemon reads
//! the same file at its start, so a provider counts one device, not one per
//! start of the CLI.

use std::fs::{self, OpenOptions};
use std::io::Read as _;
use std::os::unix::fs::{DirBuilderExt as _, OpenOptionsExt as _, PermissionsExt as _};
use std::path::{Path, PathBuf};

/// A version 4 UUID, the way the engine writes it.
pub fn new_uuid() -> Result<String, String> {
    let mut bytes = [0u8; 16];
    let mut random = fs::File::open("/dev/urandom").map_err(|e| format!("random: {e}"))?;
    random
        .read_exact(&mut bytes)
        .map_err(|e| format!("random: {e}"))?;
    bytes[6] = bytes[6] & 0x0f | 0x40;
    bytes[8] = bytes[8] & 0x3f | 0x80;
    let hex = gocompat::text::hex(&bytes);
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    ))
}

fn path(state_dir: &Path) -> PathBuf {
    state_dir.join("device-id")
}

/// The id in the file, trimmed; a fresh v4 id is written with a newline and
/// mode 0600 when the file is absent or holds no id. An existing id is never
/// rewritten.
pub fn device_id(state_dir: &Path) -> Result<String, String> {
    let file = path(state_dir);
    match fs::read_to_string(&file) {
        Ok(text) => {
            let id = text.trim();
            if !id.is_empty() {
                return Ok(id.to_string());
            }
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {}
        Err(err) => return Err(format!("read {}: {}", file.display(), err)),
    }
    let id = new_uuid()?;
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(state_dir)
        .map_err(|e| format!("make {}: {e}", state_dir.display()))?;
    let mut opened = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&file)
        .map_err(|e| format!("write {}: {e}", file.display()))?;
    use std::io::Write as _;
    opened
        .write_all(format!("{id}\n").as_bytes())
        .map_err(|e| format!("write {}: {e}", file.display()))?;
    drop(opened);
    fs::set_permissions(&file, fs::Permissions::from_mode(0o600))
        .map_err(|e| format!("chmod {}: {e}", file.display()))?;
    Ok(id)
}
