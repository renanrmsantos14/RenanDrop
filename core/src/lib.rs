//! RenanDrop: envio de arquivos entre aparelhos pessoais pela rede local.
pub mod crypto;
pub mod discovery;
pub mod engine;
pub mod fsx;
pub mod pairing;
pub mod session;
pub mod store;
pub mod tailnet;
pub mod transfer;
pub mod updater;

pub use engine::{Engine, EngineOptions, Event, PeerView, TransferView};
