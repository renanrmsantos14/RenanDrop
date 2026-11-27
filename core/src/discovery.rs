//! Descoberta de aparelhos na rede local por mDNS (`_renandrop._tcp`).

use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};

pub const SERVICE: &str = "_renandrop._tcp.local.";

#[derive(Clone, Debug)]
pub struct Seen {
    pub id: String,
    pub name: String,
    pub addrs: Vec<Ipv4Addr>,
    pub port: u16,
}

impl Seen {
    pub fn socket_addrs(&self) -> Vec<SocketAddr> {
        self.addrs
            .iter()
            .map(|a| SocketAddr::new(IpAddr::V4(*a), self.port))
            .collect()
    }
}

pub struct Discovery {
    daemon: ServiceDaemon,
    seen: Arc<Mutex<HashMap<String, Seen>>>,
    me: String,
    /// O nome do serviço vem do id, então não muda quando o aparelho é renomeado.
    fullname: String,
    port: u16,
}

fn info_for(id: &str, name: &str, port: u16) -> Result<ServiceInfo> {
    let host = format!("rd-{}.local.", &id[..12.min(id.len())]);
    let props = [("id", id), ("n", name), ("v", "1")];
    Ok(ServiceInfo::new(SERVICE, id, &host, "", port, &props[..])?.enable_addr_auto())
}

impl Discovery {
    /// `on_change` é chamado quando a lista de aparelhos muda.
    pub fn start(
        id: &str,
        name: &str,
        port: u16,
        on_change: Arc<dyn Fn() + Send + Sync>,
    ) -> Result<Discovery> {
        let daemon = ServiceDaemon::new()?;
        let info = info_for(id, name, port)?;
        let fullname = info.get_fullname().to_string();
        daemon.register(info)?;
        let rx = daemon.browse(SERVICE)?;
        let seen: Arc<Mutex<HashMap<String, Seen>>> = Arc::new(Mutex::new(HashMap::new()));
        let seen2 = seen.clone();
        let me = id.to_string();
        let me2 = me.clone();
        std::thread::Builder::new()
            .name("mdns-events".into())
            .spawn(move || {
                while let Ok(ev) = rx.recv() {
                    match ev {
                        ServiceEvent::ServiceResolved(info) => {
                            let Some(pid) = info.get_property_val_str("id").map(str::to_string)
                            else {
                                continue;
                            };
                            if pid == me2 {
                                continue;
                            }
                            let name = info
                                .get_property_val_str("n")
                                .unwrap_or("Aparelho")
                                .to_string();
                            let mut addrs: Vec<Ipv4Addr> =
                                info.get_addresses_v4().into_iter().copied().collect();
                            addrs.sort();
                            if addrs.is_empty() {
                                continue;
                            }
                            let s = Seen {
                                id: pid.clone(),
                                name,
                                addrs,
                                port: info.get_port(),
                            };
                            // Reanúncios iguais são ignorados; a UI só é avisada se algo mudou.
                            let changed =
                                seen2
                                    .lock()
                                    .unwrap()
                                    .insert(pid, s.clone())
                                    .is_none_or(|o| {
                                        (o.name, o.addrs, o.port) != (s.name, s.addrs, s.port)
                                    });
                            if changed {
                                on_change();
                            }
                        }
                        ServiceEvent::ServiceRemoved(_, full) => {
                            let id = full.split('.').next().unwrap_or("").to_string();
                            if seen2.lock().unwrap().remove(&id).is_some() {
                                on_change();
                            }
                        }
                        _ => {}
                    }
                }
            })?;
        Ok(Discovery {
            daemon,
            seen,
            me,
            fullname,
            port,
        })
    }

    pub fn seen(&self) -> Vec<Seen> {
        self.seen.lock().unwrap().values().cloned().collect()
    }

    pub fn get(&self, id: &str) -> Option<Seen> {
        self.seen.lock().unwrap().get(id).cloned()
    }

    /// Reanuncia o serviço com outro nome de exibição.
    pub fn rename(&self, name: &str) -> Result<()> {
        self.daemon.register(info_for(&self.me, name, self.port)?)?;
        Ok(())
    }

    pub fn stop(&self) {
        let _ = self.daemon.unregister(&self.fullname);
        if let Ok(rx) = self.daemon.shutdown() {
            let _ = rx.recv_timeout(std::time::Duration::from_millis(500));
        }
    }
}
