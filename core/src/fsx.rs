//! Utilitários de arquivo: leitura/escrita posicional, varredura de pastas e nomes seguros.

use crate::session::Item;
use anyhow::{bail, Result};
use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};

#[cfg(unix)]
fn read_at(f: &File, buf: &mut [u8], off: u64) -> io::Result<usize> {
    std::os::unix::fs::FileExt::read_at(f, buf, off)
}
#[cfg(windows)]
fn read_at(f: &File, buf: &mut [u8], off: u64) -> io::Result<usize> {
    std::os::windows::fs::FileExt::seek_read(f, buf, off)
}

pub fn read_exact_at(f: &File, mut buf: &mut [u8], mut off: u64) -> io::Result<()> {
    while !buf.is_empty() {
        let n = read_at(f, buf, off)?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        buf = &mut buf[n..];
        off += n as u64;
    }
    Ok(())
}

#[cfg(unix)]
fn write_at(f: &File, buf: &[u8], off: u64) -> io::Result<usize> {
    std::os::unix::fs::FileExt::write_at(f, buf, off)
}
#[cfg(windows)]
fn write_at(f: &File, buf: &[u8], off: u64) -> io::Result<usize> {
    std::os::windows::fs::FileExt::seek_write(f, buf, off)
}

pub fn write_all_at(f: &File, mut buf: &[u8], mut off: u64) -> io::Result<()> {
    while !buf.is_empty() {
        let n = write_at(f, buf, off)?;
        if n == 0 {
            return Err(io::ErrorKind::WriteZero.into());
        }
        buf = &buf[n..];
        off += n as u64;
    }
    Ok(())
}

fn mtime_ms(m: &fs::Metadata) -> i64 {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn file_item(path: String, m: &fs::Metadata) -> Item {
    Item {
        i: 0,
        path,
        size: m.len(),
        mtime: mtime_ms(m),
        dir: false,
    }
}

/// Varre arquivos e pastas; devolve itens (caminho relativo com a pasta de topo) e o caminho local.
pub fn scan(paths: &[PathBuf]) -> Result<Vec<(Item, PathBuf)>> {
    let mut out: Vec<(Item, PathBuf)> = Vec::new();
    for p in paths {
        let meta = fs::metadata(p).map_err(|e| anyhow::anyhow!("{}: {e}", p.display()))?;
        let name = p
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "pasta".into());
        if meta.is_dir() {
            walk(p, &name, &mut out)?;
        } else {
            out.push((file_item(name, &meta), p.clone()));
        }
    }
    if out.is_empty() {
        bail!("nada para enviar");
    }
    out.sort_by(|a, b| a.0.path.cmp(&b.0.path));
    for (i, (it, _)) in out.iter_mut().enumerate() {
        it.i = i as u32;
    }
    Ok(out)
}

fn walk(dir: &Path, rel: &str, out: &mut Vec<(Item, PathBuf)>) -> Result<()> {
    let before = out.len();
    for ent in fs::read_dir(dir)? {
        let ent = ent?;
        let ft = ent.file_type()?;
        let rel_child = format!("{rel}/{}", ent.file_name().to_string_lossy());
        if ft.is_dir() {
            walk(&ent.path(), &rel_child, out)?;
        } else if ft.is_file() || (ft.is_symlink() && ent.path().is_file()) {
            let m = fs::metadata(ent.path())?;
            out.push((file_item(rel_child, &m), ent.path()));
        }
    }
    // Pasta sem nada dentro: segue como item de pasta para ser recriada no destino.
    if out.len() == before {
        out.push((
            Item {
                i: 0,
                path: rel.to_string(),
                size: 0,
                mtime: 0,
                dir: true,
            },
            dir.to_path_buf(),
        ));
    }
    Ok(())
}

fn clean_component(c: &str) -> String {
    let mut s: String = c
        .chars()
        .map(|ch| {
            if ch.is_control() || "<>:\"|?*\\".contains(ch) {
                '_'
            } else {
                ch
            }
        })
        .collect();
    s = s.trim_end_matches(['.', ' ']).to_string();
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
        || (stem.len() == 4
            && (stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.as_bytes()[3].is_ascii_digit());
    if reserved {
        s.insert(0, '_');
    }
    if s.len() > 200 {
        let mut cut = 200;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        s.truncate(cut);
    }
    s
}

/// Valida e limpa um caminho relativo recebido da rede. `None` = caminho malicioso.
pub fn sanitize_rel(path: &str) -> Option<Vec<String>> {
    if path.is_empty() || path.starts_with('/') || path.contains('\0') {
        return None;
    }
    let mut parts = Vec::new();
    for c in path.split('/') {
        if c.is_empty() || c == "." || c == ".." {
            return None;
        }
        let cleaned = clean_component(c);
        if cleaned.is_empty() {
            return None;
        }
        parts.push(cleaned);
    }
    Some(parts)
}

/// Gera "nome (1).ext" até achar um nome livre.
pub fn unique_path(p: &Path) -> PathBuf {
    if !p.exists() {
        return p.to_path_buf();
    }
    let stem = p
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let ext = p
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    let parent = p.parent().unwrap_or_else(|| Path::new("."));
    for n in 1..10_000 {
        let c = parent.join(format!("{stem} ({n}){ext}"));
        if !c.exists() {
            return c;
        }
    }
    p.to_path_buf()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sanitiza_caminhos() {
        assert!(sanitize_rel("../x").is_none());
        assert!(sanitize_rel("/etc/passwd").is_none());
        assert!(sanitize_rel("a//b").is_none());
        assert_eq!(sanitize_rel("a/b?.txt").unwrap(), vec!["a", "b_.txt"]);
        assert_eq!(sanitize_rel("CON.txt").unwrap(), vec!["_CON.txt"]);
        assert!(sanitize_rel("a/./b").is_none());
    }
}
