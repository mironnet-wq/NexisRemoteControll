# -*- coding: utf-8 -*-
"""Sobe o Nexis dos coletores na VM, só em 127.0.0.1:6061.

Não imprime senha nem token. Reusa o token remoto se já existir.
"""
from __future__ import annotations

import io
import os
import secrets
import sys
import tempfile
from pathlib import Path

import paramiko

GESTOR = Path(r"C:\_Install\Gestor.IT.V2")
NEXIS = Path(r"C:\_Install\NexisRemoteControll")
REMOTE = "/opt/nexis-coletores"
GESTOR_REMOTE = "/opt/gestor-it-v2/homologacao"
HOST = "10.70.2.5"
USER = "quatrok"


def _load_password() -> str:
    path = GESTOR / ".deploy.env"
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        if key.strip() == "GESTOR_IT_V2_DEPLOY_PASSWORD":
            return val.strip().strip('"').strip("'")
    return ""


def _parse(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in (text or "").splitlines():
        raw = line.strip()
        if not raw or raw.startswith("#") or "=" not in raw:
            continue
        key, val = raw.split("=", 1)
        out[key.strip()] = val.strip().strip('"').strip("'")
    return out


def _upsert(text: str, updates: dict[str, str]) -> str:
    seen: set[str] = set()
    lines: list[str] = []
    for line in (text or "").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            lines.append(line)
            continue
        key = stripped.split("=", 1)[0].strip()
        if key in updates:
            lines.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            lines.append(line)
    for key, val in updates.items():
        if key not in seen:
            lines.append(f"{key}={val}")
    return "\n".join(lines).rstrip() + "\n"


def _run(client, cmd: str, password: str, timeout: int = 180) -> tuple[int, str]:
    shown = cmd.replace(password, "***")
    print(f"\n> {shown}", flush=True)
    _stdin, stdout, stderr = client.exec_command(cmd, get_pty=True, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace").replace(password, "***")
    err = stderr.read().decode("utf-8", errors="replace").replace(password, "***")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        print(out, flush=True)
    if err.strip():
        print(err, flush=True)
    print(f"exit {code}", flush=True)
    return code, out


def _read_remote(sftp, path: str) -> str:
    try:
        with sftp.open(path, "r") as fh:
            return fh.read().decode("utf-8", errors="replace")
    except IOError:
        return ""


def _put_text(sftp, path: str, text: str, mode: int = 0o644) -> None:
    with sftp.file(path, "w") as fh:
        fh.write(text)
    sftp.chmod(path, mode)


def main() -> int:
    if sys.platform == "win32":
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

    password = _load_password()
    if not password:
        print("senha de deploy ausente")
        return 2

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)
    sftp = client.open_sftp()

    code, _ = _run(
        client,
        (
            "python3 --version && "
            f"echo '{password}' | sudo -S mkdir -p {REMOTE}/apks && "
            f"echo '{password}' | sudo -S chown -R {USER}:{USER} {REMOTE}"
        ),
        password,
    )
    if code != 0:
        return code

    print("enviando backend", flush=True)
    sftp.put(str(NEXIS / "backend" / "main.py"), REMOTE + "/main.py")
    sftp.put(str(NEXIS / "backend" / "requirements.txt"), REMOTE + "/requirements.txt")

    remote_env = _parse(_read_remote(sftp, REMOTE + "/.env"))
    gestor_env_text = _read_remote(sftp, GESTOR_REMOTE + "/server/.env")
    gestor_env = _parse(gestor_env_text)
    token = remote_env.get("NEXIS_GESTOR_TOKEN") or gestor_env.get("NEXIS_GESTOR_TOKEN") or ""
    if len(token) < 32:
        token = secrets.token_urlsafe(48)
        print("token novo gerado (não exibido)", flush=True)
    else:
        print("token remoto reutilizado (não exibido)", flush=True)
    secret = remote_env.get("NEXIS_SECRET_KEY") or ""
    if len(secret) < 32:
        secret = secrets.token_urlsafe(48)

    nexis_env = (
        "NEXIS_BIND_HOST=127.0.0.1\n"
        "NEXIS_PORT=6061\n"
        "NEXIS_LEGACY_PANEL=0\n"
        "NEXIS_DEVICE_TOKEN=\n"
        f"NEXIS_GESTOR_TOKEN={token}\n"
        f"NEXIS_SECRET_KEY={secret}\n"
    )
    _put_text(sftp, REMOTE + "/.env", nexis_env, 0o600)

    gestor_merged = _upsert(
        gestor_env_text,
        {
            "NEXIS_GESTOR_TOKEN": token,
            "NEXIS_INTERNAL_URL": "http://127.0.0.1:6061",
        },
    )
    _put_text(sftp, GESTOR_REMOTE + "/server/.env", gestor_merged, 0o600)
    print("token gravado no .env do Gestor (não exibido)", flush=True)

    local_env_path = GESTOR / "server" / ".env"
    local_text = local_env_path.read_text(encoding="utf-8", errors="replace") if local_env_path.is_file() else ""
    local_env_path.write_text(
        _upsert(
            local_text,
            {
                "NEXIS_GESTOR_TOKEN": token,
                "NEXIS_INTERNAL_URL": "http://127.0.0.1:6061",
            },
        ),
        encoding="utf-8",
    )
    print("token alinhado no .env local (não exibido)", flush=True)

    venv_py = REMOTE + "/venv/bin/python"
    code, _ = _run(client, f"test -x {venv_py} || python3 -m venv {REMOTE}/venv", password, timeout=120)
    if code != 0:
        _run(
            client,
            f"echo '{password}' | sudo -S apt-get update -qq && echo '{password}' | sudo -S apt-get install -y -qq python3-venv python3-pip",
            password,
            timeout=300,
        )
        code, _ = _run(client, f"python3 -m venv {REMOTE}/venv", password, timeout=120)
        if code != 0:
            return code

    code, _ = _run(
        client,
        f"{venv_py} -m pip install -r {REMOTE}/requirements.txt",
        password,
        timeout=300,
    )
    if code != 0:
        return code

    unit = f"""[Unit]
Description=Nexis coletores (Zebra MC33)
After=network.target

[Service]
Type=simple
User={USER}
Group={USER}
WorkingDirectory={REMOTE}
EnvironmentFile={REMOTE}/.env
ExecStart={REMOTE}/venv/bin/uvicorn main:app --host 127.0.0.1 --port 6061
Restart=on-failure
RestartSec=3
MemoryMax=256M
CPUQuota=50%
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
"""
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".service") as tmp:
        tmp.write(unit)
        unit_local = tmp.name
    sftp.put(unit_local, "/tmp/nexis-coletores.service")
    os.unlink(unit_local)

    code, _ = _run(
        client,
        (
            f"echo '{password}' | sudo -S mv /tmp/nexis-coletores.service /etc/systemd/system/nexis-coletores.service"
            f" && echo '{password}' | sudo -S systemctl daemon-reload"
            f" && echo '{password}' | sudo -S systemctl enable nexis-coletores.service"
            f" && echo '{password}' | sudo -S systemctl restart nexis-coletores.service"
        ),
        password,
        timeout=60,
    )
    if code != 0:
        return code

    _run(client, "systemctl is-active nexis-coletores.service; ss -tln | grep 6061 || true", password)
    check = r"""
import json, urllib.request
env = {}
for line in open("/opt/nexis-coletores/.env", encoding="utf-8"):
    if line.startswith("NEXIS_GESTOR_TOKEN="):
        env["t"] = line.split("=", 1)[1].strip()
req = urllib.request.Request(
    "http://127.0.0.1:6061/internal/health",
    headers={"X-Nexis-Token": env.get("t", "")},
)
try:
    with urllib.request.urlopen(req, timeout=5) as resp:
        body = json.loads(resp.read().decode())
        print("health", resp.status, "ok" if body.get("ok") else "fail", "online", body.get("devices_online"))
except Exception as exc:
    print("health_fail", type(exc).__name__)
"""
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".py") as tmp:
        tmp.write(check)
        check_local = tmp.name
    sftp.put(check_local, "/tmp/nexis-health.py")
    os.unlink(check_local)
    code, _ = _run(client, f"{venv_py} /tmp/nexis-health.py; rm -f /tmp/nexis-health.py", password)
    sftp.close()
    client.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
