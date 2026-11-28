//! A rtnetlink socket, just enough for rules, routes and addresses: one
//! request at a time, native-endian, the kernel's own numbers.

use std::os::fd::{AsRawFd as _, FromRawFd as _, OwnedFd};

pub const RTM_GETADDR: u16 = 22;
pub const RTM_NEWROUTE: u16 = 24;
pub const RTM_DELROUTE: u16 = 25;
pub const RTM_GETROUTE: u16 = 26;
pub const RTM_NEWRULE: u16 = 32;
pub const RTM_DELRULE: u16 = 33;
pub const RTM_GETRULE: u16 = 34;

pub const NLM_F_REQUEST: u16 = 0x1;
pub const NLM_F_ACK: u16 = 0x4;
pub const NLM_F_REPLACE: u16 = 0x100;
pub const NLM_F_EXCL: u16 = 0x200;
pub const NLM_F_CREATE: u16 = 0x400;
pub const NLM_F_DUMP: u16 = 0x300;

const NLMSG_NOOP: u16 = 1;
const NLMSG_ERROR: u16 = 2;
const NLMSG_DONE: u16 = 3;
const HEADER: usize = 16;

pub struct Socket {
    fd: OwnedFd,
    seq: u32,
    buf: Vec<u8>,
}

fn align4(n: usize) -> usize {
    (n + 3) & !3
}

pub fn put_attr(buf: &mut Vec<u8>, ty: u16, data: &[u8]) {
    let len = 4 + data.len();
    buf.extend_from_slice(&(len as u16).to_ne_bytes());
    buf.extend_from_slice(&ty.to_ne_bytes());
    buf.extend_from_slice(data);
    while buf.len() % 4 != 0 {
        buf.push(0);
    }
}

/// The attributes after a fixed header, in order; a nested or byte-order flag
/// is dropped from the type.
pub fn attrs(mut data: &[u8]) -> Vec<(u16, &[u8])> {
    let mut out = Vec::new();
    while data.len() >= 4 {
        let len = u16::from_ne_bytes([data[0], data[1]]) as usize;
        let ty = u16::from_ne_bytes([data[2], data[3]]) & 0x3fff;
        if len < 4 || len > data.len() {
            break;
        }
        out.push((ty, &data[4..len]));
        let step = align4(len);
        if step >= data.len() {
            break;
        }
        data = &data[step..];
    }
    out
}

pub fn ne32(bytes: &[u8]) -> Option<u32> {
    let b: [u8; 4] = bytes.get(..4)?.try_into().ok()?;
    Some(u32::from_ne_bytes(b))
}

impl Socket {
    pub fn open() -> Result<Socket, i32> {
        // SAFETY: plain system calls; the descriptor is owned at once.
        let raw = unsafe {
            libc::socket(
                libc::AF_NETLINK,
                libc::SOCK_RAW | libc::SOCK_CLOEXEC,
                libc::NETLINK_ROUTE,
            )
        };
        if raw < 0 {
            return Err(errno());
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let mut addr: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
        addr.nl_family = libc::AF_NETLINK as libc::sa_family_t;
        let rc = unsafe {
            libc::bind(
                fd.as_raw_fd(),
                (&addr as *const libc::sockaddr_nl).cast::<libc::sockaddr>(),
                std::mem::size_of::<libc::sockaddr_nl>() as libc::socklen_t,
            )
        };
        if rc < 0 {
            return Err(errno());
        }
        Ok(Socket {
            fd,
            seq: 0,
            buf: vec![0u8; 256 * 1024],
        })
    }

    /// Sends one message (`payload` is the fixed header, `attributes` follow it)
    /// and reads until the kernel answers: the data messages of a dump, or the
    /// acknowledgement of a change. `Err` is the errno the kernel named.
    pub fn request(
        &mut self,
        ty: u16,
        flags: u16,
        payload: &[u8],
        attributes: &[u8],
    ) -> Result<Vec<Vec<u8>>, i32> {
        self.seq = self.seq.wrapping_add(1).max(1);
        let total = HEADER + payload.len() + attributes.len();
        let mut msg = Vec::with_capacity(total);
        msg.extend_from_slice(&(total as u32).to_ne_bytes());
        msg.extend_from_slice(&ty.to_ne_bytes());
        msg.extend_from_slice(&flags.to_ne_bytes());
        msg.extend_from_slice(&self.seq.to_ne_bytes());
        msg.extend_from_slice(&0u32.to_ne_bytes());
        msg.extend_from_slice(payload);
        msg.extend_from_slice(attributes);
        loop {
            let rc = unsafe {
                libc::send(
                    self.fd.as_raw_fd(),
                    msg.as_ptr().cast::<libc::c_void>(),
                    msg.len(),
                    0,
                )
            };
            if rc >= 0 {
                break;
            }
            let e = errno();
            if e != libc::EINTR {
                return Err(e);
            }
        }
        let mut out = Vec::new();
        loop {
            let n = unsafe {
                libc::recv(
                    self.fd.as_raw_fd(),
                    self.buf.as_mut_ptr().cast::<libc::c_void>(),
                    self.buf.len(),
                    0,
                )
            };
            if n < 0 {
                let e = errno();
                if e == libc::EINTR {
                    continue;
                }
                return Err(e);
            }
            let n = n as usize;
            let mut off = 0;
            while off + HEADER <= n {
                let len = ne32(&self.buf[off..]).unwrap_or(0) as usize;
                if len < HEADER || off + len > n {
                    break;
                }
                let mty = u16::from_ne_bytes([self.buf[off + 4], self.buf[off + 5]]);
                let seq = ne32(&self.buf[off + 8..]).unwrap_or(0);
                if seq == self.seq {
                    match mty {
                        NLMSG_ERROR => {
                            let code = ne32(&self.buf[off + HEADER..]).unwrap_or(0) as i32;
                            return if code == 0 { Ok(out) } else { Err(-code) };
                        }
                        NLMSG_DONE => return Ok(out),
                        NLMSG_NOOP => {}
                        _ => out.push(self.buf[off + HEADER..off + len].to_vec()),
                    }
                }
                off += align4(len);
            }
        }
    }
}

pub fn errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
}
