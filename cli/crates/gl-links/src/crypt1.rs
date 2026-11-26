//! crypt1, decrypt only: base64url (no padding) of
//! `IV(16) | AES-256-CBC/PKCS7 ciphertext | HMAC-SHA256(IV | ciphertext)(32)`.
//!
//! The two keys are `sha256("olcrtc-crypt-v1-enc" | master)` and
//! `sha256("olcrtc-crypt-v1-mac" | master)`. AES-256 is written here as a
//! table-driven block cipher so the CLI's lock needs no checksummed crypto
//! crate: sha2 provides the two hashes, base64 provides the wire form.

use sha2::{Digest, Sha256};

pub const LINK_PREFIX: &str = "olcrtc://crypt1/";

const SBOX: [u8; 256] = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
];

fn inv_sbox() -> [u8; 256] {
    let mut out = [0u8; 256];
    for (i, &v) in SBOX.iter().enumerate() {
        out[v as usize] = i as u8;
    }
    out
}

fn xtime(b: u8) -> u8 {
    (b << 1) ^ if b & 0x80 != 0 { 0x1b } else { 0 }
}

fn mul(a: u8, b: u8) -> u8 {
    let mut p = 0u8;
    let mut a = a;
    let mut b = b;
    while b != 0 {
        if b & 1 != 0 {
            p ^= a;
        }
        a = xtime(a);
        b >>= 1;
    }
    p
}

/// AES-256 key expansion: 60 words of four bytes.
fn expand_key(key: &[u8; 32]) -> [u8; 240] {
    let mut w = [0u8; 240];
    w[..32].copy_from_slice(key);
    let mut rcon: u8 = 1;
    for i in 8..60 {
        let mut t = [
            w[(i - 1) * 4],
            w[(i - 1) * 4 + 1],
            w[(i - 1) * 4 + 2],
            w[(i - 1) * 4 + 3],
        ];
        if i.is_multiple_of(8) {
            t.rotate_left(1);
            for b in t.iter_mut() {
                *b = SBOX[*b as usize];
            }
            t[0] ^= rcon;
            rcon = xtime(rcon);
        } else if (i - 4).is_multiple_of(8) {
            for b in t.iter_mut() {
                *b = SBOX[*b as usize];
            }
        }
        for j in 0..4 {
            w[i * 4 + j] = w[(i - 8) * 4 + j] ^ t[j];
        }
    }
    w
}

/// One AES-256 block decryption (14 rounds, inverse cipher).
fn decrypt_block(w: &[u8; 240], inv: &[u8; 256], block: &mut [u8; 16]) {
    let round_key = |r: usize, c: usize| -> [u8; 4] {
        let i = (r * 4 + c) * 4;
        [w[i], w[i + 1], w[i + 2], w[i + 3]]
    };
    let add_round_key = |s: &mut [u8; 16], r: usize| {
        for c in 0..4 {
            let k = round_key(r, c);
            for row in 0..4 {
                s[c * 4 + row] ^= k[row];
            }
        }
    };

    add_round_key(block, 14);
    for round in (1..14).rev() {
        // Inverse ShiftRows: row r moves right by r.
        let mut shifted = [0u8; 16];
        for c in 0..4 {
            for row in 0..4 {
                shifted[c * 4 + row] = block[((c + row) % 4) * 4 + row];
            }
        }
        *block = shifted;
        for b in block.iter_mut() {
            *b = inv[*b as usize];
        }
        add_round_key(block, round);
        // Inverse MixColumns.
        for c in 0..4 {
            let s = [
                block[c * 4],
                block[c * 4 + 1],
                block[c * 4 + 2],
                block[c * 4 + 3],
            ];
            block[c * 4] = mul(s[0], 14) ^ mul(s[1], 11) ^ mul(s[2], 13) ^ mul(s[3], 9);
            block[c * 4 + 1] = mul(s[0], 9) ^ mul(s[1], 14) ^ mul(s[2], 11) ^ mul(s[3], 13);
            block[c * 4 + 2] = mul(s[0], 13) ^ mul(s[1], 9) ^ mul(s[2], 14) ^ mul(s[3], 11);
            block[c * 4 + 3] = mul(s[0], 11) ^ mul(s[1], 13) ^ mul(s[2], 9) ^ mul(s[3], 14);
        }
    }
    // Last round: no MixColumns.
    let mut shifted = [0u8; 16];
    for c in 0..4 {
        for row in 0..4 {
            shifted[c * 4 + row] = block[((c + row) % 4) * 4 + row];
        }
    }
    *block = shifted;
    for b in block.iter_mut() {
        *b = inv[*b as usize];
    }
    add_round_key(block, 0);
}

fn cbc_decrypt(key: &[u8; 32], iv: &[u8], ct: &[u8]) -> Option<Vec<u8>> {
    if !ct.len().is_multiple_of(16) || iv.len() != 16 {
        return None;
    }
    let w = expand_key(key);
    let inv = inv_sbox();
    let mut out = Vec::with_capacity(ct.len());
    let mut prev = [0u8; 16];
    prev.copy_from_slice(iv);
    for chunk in ct.chunks_exact(16) {
        let mut block = [0u8; 16];
        block.copy_from_slice(chunk);
        decrypt_block(&w, &inv, &mut block);
        for i in 0..16 {
            block[i] ^= prev[i];
        }
        prev.copy_from_slice(chunk);
        out.extend_from_slice(&block);
    }
    Some(out)
}

fn keys(master: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut enc = [0u8; 32];
    let mut mac = [0u8; 32];
    enc.copy_from_slice(&Sha256::digest(
        [b"olcrtc-crypt-v1-enc".as_slice(), master.as_slice()].concat(),
    ));
    mac.copy_from_slice(&Sha256::digest(
        [b"olcrtc-crypt-v1-mac".as_slice(), master.as_slice()].concat(),
    ));
    (enc, mac)
}

fn decode_base64(s: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    if s.is_empty() {
        return None;
    }
    for enc in [
        base64::engine::general_purpose::URL_SAFE_NO_PAD,
        base64::engine::general_purpose::URL_SAFE,
        base64::engine::general_purpose::STANDARD_NO_PAD,
        base64::engine::general_purpose::STANDARD,
    ] {
        if let Ok(b) = enc.decode(s) {
            return Some(b);
        }
    }
    None
}

/// Parse a master key: 32 bytes in base64, standard or url-safe, padded or not.
pub fn parse_key(s: &str) -> Option<[u8; 32]> {
    let raw = decode_base64(s.trim())?;
    if raw.len() != 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&raw);
    Some(key)
}

pub fn is_link(s: &str) -> bool {
    s.trim().starts_with(LINK_PREFIX)
}

pub fn decrypt_link(master: &[u8; 32], link: &str) -> Option<Vec<u8>> {
    let link = link.trim();
    decrypt(master, link.strip_prefix(LINK_PREFIX)?)
}

/// HMAC-SHA256, written on sha2 because the `hmac` crate is not in the CLI
/// lock and no new checksummed dependency is needed for one primitive.
fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    let block = if key.len() > 64 {
        let mut hashed = [0u8; 32];
        hashed.copy_from_slice(&Sha256::digest(key));
        let mut b = [0u8; 64];
        b[..32].copy_from_slice(&hashed);
        b
    } else {
        let mut b = [0u8; 64];
        b[..key.len()].copy_from_slice(key);
        b
    };
    let inner: Vec<u8> = block
        .iter()
        .map(|b| b ^ 0x36)
        .chain(message.iter().copied())
        .collect();
    let outer: Vec<u8> = block
        .iter()
        .map(|b| b ^ 0x5c)
        .chain(Sha256::digest(&inner).iter().copied())
        .collect();
    let mut out = [0u8; 32];
    out.copy_from_slice(&Sha256::digest(&outer));
    out
}

/// Verify and decrypt one blob. A short blob, a bad MAC, a wrong key or bad
/// padding all return `None`.
pub fn decrypt(master: &[u8; 32], blob: &str) -> Option<Vec<u8>> {
    let raw = decode_base64(blob.trim())?;
    if raw.len() < 16 + 16 + 32 {
        return None;
    }
    let (iv, rest) = raw.split_at(16);
    let (ct, tag) = rest.split_at(rest.len() - 32);
    if !ct.len().is_multiple_of(16) {
        return None;
    }
    let (enc_key, mac_key) = keys(master);
    let expected = hmac_sha256(&mac_key, &[iv, ct].concat());
    if expected != tag {
        return None;
    }
    let plain = cbc_decrypt(&enc_key, iv, ct)?;
    unpad(&plain)
}

fn unpad(plain: &[u8]) -> Option<Vec<u8>> {
    let n = *plain.last()? as usize;
    if n == 0
        || n > 16
        || n > plain.len()
        || !plain[plain.len() - n..].iter().all(|b| *b as usize == n)
    {
        return None;
    }
    Some(plain[..plain.len() - n].to_vec())
}
