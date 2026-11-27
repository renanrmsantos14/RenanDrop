//! Integração com o sistema: menu de contexto ("Enviar com RenanDrop"), início com o sistema,
//! abrir arquivo e mostrar na pasta. Linux (Nemo e `.desktop`) e Windows (registro do usuário).

use anyhow::Result;
use std::path::Path;
use std::process::Command;

#[cfg(windows)]
pub(crate) fn sem_janela(c: &mut Command) -> &mut Command {
    use std::os::windows::process::CommandExt;
    c.creation_flags(0x0800_0000)
}
#[cfg(not(windows))]
pub(crate) fn sem_janela(c: &mut Command) -> &mut Command {
    c
}

/// Instala o menu de contexto para o executável atual. Idempotente: reescreve se o caminho mudou.
pub fn install_context_menu(exe: &Path) -> Result<()> {
    #[cfg(windows)]
    {
        let exe = exe.display();
        let cmd = format!("\"{exe}\" --send \"%1\"");
        for base in [
            r"HKCU\Software\Classes\*\shell\RenanDrop",
            r"HKCU\Software\Classes\Directory\shell\RenanDrop",
        ] {
            reg(&[base, "/ve", "/d", "Enviar com RenanDrop"])?;
            reg(&[base, "/v", "Icon", "/d", &exe.to_string()])?;
            reg(&[&format!(r"{base}\command"), "/ve", "/d", &cmd])?;
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let dir = xdg_data().join("nemo").join("actions");
        std::fs::create_dir_all(&dir)?;
        // %F entrega os caminhos selecionados; Quote=double protege espaços.
        let texto = format!(
            "[Nemo Action]\nName=Enviar com RenanDrop\nComment=Enviar para outro aparelho pareado\nExec=\"{}\" --send %F\n\
             Icon-Name=document-send\nSelection=any\nExtensions=any;dir;\nQuote=double\n",
            exe.display()
        );
        write_if_changed(&dir.join("renandrop.nemo_action"), &texto)
    }
}

pub fn set_autostart(exe: &Path, on: bool) -> Result<()> {
    #[cfg(windows)]
    {
        let chave = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        if on {
            reg(&[
                chave,
                "/v",
                "RenanDrop",
                "/d",
                &format!("\"{}\" --hidden", exe.display()),
            ])
        } else {
            let _ =
                sem_janela(Command::new("reg").args(["delete", chave, "/v", "RenanDrop", "/f"]))
                    .status();
            Ok(())
        }
    }
    #[cfg(not(windows))]
    {
        let arq = xdg_config().join("autostart").join("renandrop.desktop");
        if !on {
            let _ = std::fs::remove_file(&arq);
            return Ok(());
        }
        std::fs::create_dir_all(arq.parent().unwrap())?;
        let texto = format!(
            "[Desktop Entry]\nType=Application\nName=RenanDrop\n\
             Comment=Envio de arquivos entre seus aparelhos\nExec=\"{}\" --hidden\n\
             Icon=document-send\nTerminal=false\nX-GNOME-Autostart-enabled=true\n",
            exe.display()
        );
        write_if_changed(&arq, &texto)
    }
}

#[cfg(windows)]
fn reg(args: &[&str]) -> Result<()> {
    let mut c = Command::new("reg");
    c.arg("add").args(args).arg("/f");
    if sem_janela(&mut c).status()?.success() {
        Ok(())
    } else {
        Err(anyhow::anyhow!("reg add falhou"))
    }
}

#[cfg(not(windows))]
fn write_if_changed(p: &Path, texto: &str) -> Result<()> {
    if std::fs::read_to_string(p).ok().as_deref() != Some(texto) {
        std::fs::write(p, texto)?;
    }
    Ok(())
}

#[cfg(not(windows))]
fn xdg_data() -> std::path::PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .map(Into::into)
        .unwrap_or_else(|| renandrop_core::store::home_dir().join(".local/share"))
}
#[cfg(not(windows))]
fn xdg_config() -> std::path::PathBuf {
    std::env::var_os("XDG_CONFIG_HOME")
        .map(Into::into)
        .unwrap_or_else(|| renandrop_core::store::home_dir().join(".config"))
}

/// Abre o arquivo ou a pasta com o programa padrão.
pub fn open(path: &Path) {
    #[cfg(windows)]
    let mut c = {
        let mut c = Command::new("explorer");
        c.arg(path);
        c
    };
    #[cfg(not(windows))]
    let mut c = {
        let mut c = Command::new("xdg-open");
        c.arg(path);
        c
    };
    let _ = sem_janela(&mut c).spawn();
}

/// Mostra o item dentro da pasta (selecionado, quando o sistema permite).
pub fn reveal(path: &Path) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // O explorer exige /select,"caminho" com as aspas coladas; arg() as poria em volta de tudo.
        let mut c = Command::new("explorer");
        c.raw_arg(format!("/select,\"{}\"", path.display()));
        let _ = sem_janela(&mut c).spawn();
    }
    #[cfg(not(windows))]
    {
        // FileManager1.ShowItems (Nemo, Nautilus, Dolphin) abre a pasta com o item selecionado.
        if path.is_file() {
            let uri = format!(
                "['file://{}']",
                path.display().to_string().replace('\'', "%27")
            );
            let ok = Command::new("gdbus")
                .args([
                    "call",
                    "--session",
                    "--dest",
                    "org.freedesktop.FileManager1",
                ])
                .args(["--object-path", "/org/freedesktop/FileManager1"])
                .args([
                    "--method",
                    "org.freedesktop.FileManager1.ShowItems",
                    &uri,
                    "",
                ])
                .output()
                .map(|o| o.status.success())
                .unwrap_or(false);
            if ok {
                return;
            }
        }
        open(if path.is_dir() {
            path
        } else {
            path.parent().unwrap_or(path)
        });
    }
}
