#!/usr/bin/env python3
"""Sobe o PATCH da versão de build do app e grava data/hora (America/Sao_Paulo).

Chamado por scripts/build.sh antes de cada build de publicação. Mantém em sincronia
app/version.json, app/Cargo.toml, app/tauri.conf.json e o Cargo.lock.
"""
import json, re, subprocess
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

raiz = Path(__file__).resolve().parent.parent
vj = raiz / "app" / "version.json"
atual = json.loads(vj.read_text(encoding="utf-8"))["version"]
ma, mi, pa = (int(x) for x in atual.split("."))
nova = f"{ma}.{mi}.{pa + 1}"
agora = datetime.now(ZoneInfo("America/Sao_Paulo")).isoformat(timespec="seconds")
vj.write_text(json.dumps({"version": nova, "built": agora}, ensure_ascii=False) + "\n", encoding="utf-8")

cargo = raiz / "app" / "Cargo.toml"
cargo.write_text(re.sub(r'(?m)^version = "[^"]+"', f'version = "{nova}"', cargo.read_text(encoding="utf-8"), count=1), encoding="utf-8")
tj = raiz / "app" / "tauri.conf.json"
tj.write_text(re.sub(r'("version":\s*)"[^"]+"', rf'\1"{nova}"', tj.read_text(encoding="utf-8"), count=1), encoding="utf-8")
subprocess.run(["cargo", "update", "-w", "--offline"], cwd=raiz, check=False, capture_output=True)
print(f"v{nova} · {datetime.fromisoformat(agora):%d/%m/%Y %H:%M}")
