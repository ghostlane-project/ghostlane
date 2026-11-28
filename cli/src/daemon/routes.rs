//! The daemon's `Routes` over the policy rules of `gl-routes`, and the tun
//! (`gl_routes::hev::Hev`) as a `Front`.
//!
//! `watch` starts an address watcher that puts the own-address rules right
//! again (debounced, 500 ms) whenever an address comes or goes; `unwatch`
//! stops it and waits for a sync in flight, so the daemon can clear the rules
//! after it knowing none follows.

use std::collections::BTreeMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

use gl_routes::hev::{Hev, TUN_NAME};
use gl_routes::{Manager, Watcher};

use super::{Front, Routes};

pub struct RulesRoutes {
    manager: Arc<Manager>,
    watchers: Mutex<BTreeMap<usize, Watcher>>,
    logf: Arc<dyn Fn(&str) + Send + Sync>,
}

impl RulesRoutes {
    /// Rules for a daemon whose tun is `ghostlane0`.
    pub fn new(logf: Arc<dyn Fn(&str) + Send + Sync>) -> RulesRoutes {
        RulesRoutes {
            manager: Arc::new(Manager::new(TUN_NAME)),
            watchers: Mutex::new(BTreeMap::new()),
            logf,
        }
    }

    /// The manager the tun's rules are put in with: hev takes the same one.
    pub fn manager(&self) -> Arc<Manager> {
        Arc::clone(&self.manager)
    }
}

impl Routes for RulesRoutes {
    fn global_addresses(&self) -> Result<Vec<String>, String> {
        let addresses = self.manager.global_addresses().map_err(|e| e.to_string())?;
        Ok(addresses.iter().map(IpAddr::to_string).collect())
    }

    fn sync(&self, addresses: &[String]) -> Result<(), String> {
        let parsed = addresses
            .iter()
            .map(|a| a.parse::<IpAddr>().map_err(|e| format!("{a}: {e}")))
            .collect::<Result<Vec<_>, _>>()?;
        self.manager.sync(&parsed).map_err(|e| e.to_string())
    }

    fn clear(&self) -> Result<(), String> {
        self.manager.clear().map_err(|e| e.to_string())
    }

    fn cleanup_stale(&self) -> Result<(), String> {
        self.manager.cleanup_stale().map_err(|e| e.to_string())
    }

    fn install_kill_switch(&self, uid: i32) -> Result<(), String> {
        let uid = u32::try_from(uid).map_err(|_| format!("uid {uid}"))?;
        self.manager
            .install_kill_switch(uid)
            .map_err(|e| e.to_string())
    }

    fn remove_kill_switch(&self) -> Result<(), String> {
        self.manager.remove_kill_switch().map_err(|e| e.to_string())
    }

    fn watch(&self, id: usize) -> Result<(), String> {
        let manager = Arc::clone(&self.manager);
        let logf = Arc::clone(&self.logf);
        let watcher = Watcher::start(move || {
            let result = manager
                .global_addresses()
                .and_then(|addresses| manager.sync(&addresses));
            if let Err(err) = result {
                logf(&format!("own-address rules: {err}"));
            }
        })
        .map_err(|e| e.to_string())?;
        self.watchers
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(id, watcher);
        Ok(())
    }

    fn unwatch(&self, id: usize) -> Result<(), String> {
        // dropped outside the lock: it waits for the watcher's thread
        let watcher = self
            .watchers
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&id);
        drop(watcher);
        Ok(())
    }
}

impl Front for Hev {
    fn close(&self) -> Result<(), String> {
        Hev::close(self)
    }
}
