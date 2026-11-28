//! The address watcher: calls a function, debounced, whenever an address is
//! added or removed on any interface, until it is dropped. Dropping it waits
//! for the thread (and any call in flight), so a caller that drops it before
//! clearing the rules knows no sync follows.

use std::os::fd::{AsRawFd as _, FromRawFd as _, OwnedFd};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::netlink::Socket;
use crate::Error;

const RTMGRP_IPV4_IFADDR: u32 = 0x10;
const RTMGRP_IPV6_IFADDR: u32 = 0x100;
const DEBOUNCE: Duration = Duration::from_millis(500);

pub struct Watcher {
    stop: OwnedFd,
    thread: Option<JoinHandle<()>>,
}

impl Watcher {
    pub fn start(on_change: impl Fn() + Send + 'static) -> Result<Watcher, Error> {
        let mut sock = Socket::open_groups(RTMGRP_IPV4_IFADDR | RTMGRP_IPV6_IFADDR)
            .map_err(|e| Error::os(e, "address watcher"))?;
        let mut fds = [0i32; 2];
        // SAFETY: fds has room for the two descriptors.
        if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) } < 0 {
            return Err(Error::os(crate::netlink::errno(), "address watcher pipe"));
        }
        // SAFETY: pipe2 made both, and each is owned once.
        let (read, write) = unsafe { (OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1])) };
        let thread = std::thread::spawn(move || {
            let mut deadline: Option<Instant> = None;
            loop {
                let timeout = match deadline {
                    Some(at) => at.saturating_duration_since(Instant::now()).as_millis() as i32,
                    None => -1,
                };
                let mut polled = [
                    libc::pollfd {
                        fd: sock.raw_fd(),
                        events: libc::POLLIN,
                        revents: 0,
                    },
                    libc::pollfd {
                        fd: read.as_raw_fd(),
                        events: libc::POLLIN,
                        revents: 0,
                    },
                ];
                // SAFETY: two valid pollfd entries.
                let rc = unsafe { libc::poll(polled.as_mut_ptr(), 2, timeout) };
                if rc < 0 {
                    if crate::netlink::errno() == libc::EINTR {
                        continue;
                    }
                    return;
                }
                if polled[1].revents != 0 {
                    return;
                }
                if polled[0].revents != 0 {
                    sock.drain();
                    deadline = Some(Instant::now() + DEBOUNCE);
                } else if rc == 0 && deadline.take().is_some() {
                    on_change();
                }
            }
        });
        Ok(Watcher {
            stop: write,
            thread: Some(thread),
        })
    }
}

impl Drop for Watcher {
    fn drop(&mut self) {
        // SAFETY: one byte from a live buffer to our own pipe.
        unsafe {
            libc::write(
                self.stop.as_raw_fd(),
                [1u8].as_ptr().cast::<libc::c_void>(),
                1,
            );
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
