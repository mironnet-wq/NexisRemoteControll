import os
import shutil
import logging
import base64
import glob
import hashlib
import hmac
import json
import re
import secrets
import subprocess
import time
import asyncio
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Set
from datetime import datetime, timedelta
import bcrypt
import jwt
from fastapi import FastAPI, Depends, Header, HTTPException, status, WebSocket, WebSocketDisconnect, UploadFile, File, Form
from fastapi.security import OAuth2PasswordBearer
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# Configurações de Log
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("NexisBackend")

# Diretórios do projeto
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
APKS_DIR = os.path.join(BASE_DIR, "apks")
TRANSFERS_DIR = os.path.join(STATIC_DIR, "transfers")
DOWNLOADS_DIR = os.path.join(TRANSFERS_DIR, "downloads")
UPLOADS_DIR = os.path.join(TRANSFERS_DIR, "uploads")

os.makedirs(STATIC_DIR, exist_ok=True)
os.makedirs(APKS_DIR, exist_ok=True)
os.makedirs(DOWNLOADS_DIR, exist_ok=True)
os.makedirs(UPLOADS_DIR, exist_ok=True)

# Host padrão do servidor (IP:porta) — usado no QR de provisionamento e fallbacks
DEFAULT_SERVER_HOST = os.getenv("NEXIS_SERVER_HOST", "10.70.100.251:6061")

def _env_flag(name: str, default: str = "0") -> bool:
    return (os.getenv(name, default) or "").strip().lower() in ("1", "true", "yes", "on")


# Configurações de Segurança (todas vêm do ambiente; ver .env.example)
SECRET_KEY = os.getenv("NEXIS_SECRET_KEY") or secrets.token_urlsafe(48)
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 120

# Segredo compartilhado com o Gestor: protege /internal e assina os tickets do navegador.
GESTOR_TOKEN = (os.getenv("NEXIS_GESTOR_TOKEN") or "").strip()
# Token que os coletores enviam ao conectar. Vazio = aceita agentes antigos sem token.
DEVICE_TOKEN = (os.getenv("NEXIS_DEVICE_TOKEN") or "").strip()
# Painel web antigo (backend/static) e WebSocket de cliente sem ticket. Só para testes.
LEGACY_PANEL = _env_flag("NEXIS_LEGACY_PANEL")

USERS_DB: Dict[str, Dict] = {}
_admin_email = (os.getenv("NEXIS_ADMIN_EMAIL") or "").strip()
_admin_password = os.getenv("NEXIS_ADMIN_PASSWORD") or ""
if LEGACY_PANEL and _admin_email and _admin_password:
    USERS_DB[_admin_email] = {
        "email": _admin_email,
        "hashed_password": bcrypt.hashpw(_admin_password.encode("utf-8"), bcrypt.gensalt()),
        "name": "Administrador Nexis",
    }

app = FastAPI(title="Nexis Remote Control Server", version="1.1.0")

if LEGACY_PANEL:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="api/login")

# Pydantic Schemas
class LoginRequest(BaseModel):
    email: str
    password: str

class Token(BaseModel):
    access_token: str
    token_type: str

class UserProfile(BaseModel):
    email: str
    name: str

class DeviceInfo(BaseModel):
    device_id: str
    model: str
    android_version: str
    battery_level: int
    ram_usage: str
    status: str  # online, offline
    is_zebra: bool

# Armazenamento de Conexões WebSocket
class ConnectionManager:
    def __init__(self):
        # device_id -> WebSocket
        self.active_devices: Dict[str, WebSocket] = {}
        # client_id -> WebSocket
        self.active_clients: Dict[str, WebSocket] = {}
        # client_id -> usuário do Gestor (vazio = painel antigo)
        self.client_users: Dict[str, str] = {}
        # device_id -> clientes que pediram a tela desse coletor
        self.viewers: Dict[str, Set[str]] = {}
        # Informações de telemetria dos dispositivos
        self.device_registry: Dict[str, Dict] = {}

    async def connect_device(self, device_id: str, websocket: WebSocket):
        await websocket.accept()
        old = self.active_devices.get(device_id)
        if old is not None and old is not websocket:
            try:
                await old.close(code=4000)
            except Exception:
                pass
        self.active_devices[device_id] = websocket
        now = datetime.utcnow().isoformat() + "Z"
        if device_id not in self.device_registry:
            self.device_registry[device_id] = {
                "device_id": device_id,
                "model": "Dispositivo Desconhecido",
                "android_version": "Desconhecido",
                "agent_version": "",
                "battery_level": 100,
                "ram_usage": "N/A",
                "serial": "",
                "mac": "",
                "ip": "",
                "status": "online",
                "is_zebra": "zebra" in device_id.lower(),
                "connected_at": now,
                "last_seen": now,
            }
        else:
            self.device_registry[device_id].update(
                {"status": "online", "connected_at": now, "last_seen": now}
            )

        logger.info(f"Dispositivo conectado: {device_id}")
        await self.broadcast_device_list()
        if self.viewers.get(device_id):
            # Alguém estava assistindo quando o link caiu: pede a tela de novo.
            try:
                await websocket.send_text(json.dumps({"type": "start_mirroring", "data": {"silent": True}}))
            except Exception:
                pass

    def disconnect_device(self, device_id: str, websocket: Optional[WebSocket] = None):
        current = self.active_devices.get(device_id)
        if websocket is not None and current is not websocket:
            return
        if device_id in self.active_devices:
            del self.active_devices[device_id]
        if device_id in self.device_registry:
            self.device_registry[device_id]["status"] = "offline"
        # Quem está assistindo continua na lista: o coletor costuma reconectar em segundos
        # (ex.: a tela de permissão de captura) e, sem isso, a imagem parava de chegar
        # enquanto os toques seguiam funcionando. A lista limpa quando o painel sai.
        logger.info(f"Dispositivo desconectado: {device_id}")

    def touch_device(self, device_id: str):
        if device_id in self.device_registry:
            self.device_registry[device_id]["last_seen"] = datetime.utcnow().isoformat() + "Z"

    async def connect_client(self, client_id: str, websocket: WebSocket, username: str = ""):
        await websocket.accept()
        self.active_clients[client_id] = websocket
        self.client_users[client_id] = username
        logger.info(f"Cliente de suporte conectado: {client_id} ({username or 'painel antigo'})")

    def disconnect_client(self, client_id: str):
        if client_id in self.active_clients:
            del self.active_clients[client_id]
        self.client_users.pop(client_id, None)
        for device_id in list(self.viewers):
            self.viewers[device_id].discard(client_id)
            if not self.viewers[device_id]:
                del self.viewers[device_id]
        logger.info(f"Cliente de suporte desconectado: {client_id}")

    def add_viewer(self, device_id: str, client_id: str):
        self.viewers.setdefault(device_id, set()).add(client_id)

    def remove_viewer(self, device_id: str, client_id: str) -> bool:
        """Remove o cliente da tela. Retorna True se ninguém mais está olhando."""
        watchers = self.viewers.get(device_id)
        if not watchers:
            return True
        watchers.discard(client_id)
        if watchers:
            return False
        del self.viewers[device_id]
        return True

    async def send_frame(self, device_id: str, jpeg: bytes):
        """Manda o quadro só para quem pediu a tela desse coletor.

        Gestor recebe binário: 1 byte com o tamanho do device_id, o device_id e o JPEG.
        O painel antigo recebe o JSON com base64 de sempre.
        """
        watchers = self.viewers.get(device_id)
        if not watchers:
            return
        dev = device_id.encode("utf-8")[:255]
        binary = bytes([len(dev)]) + dev + jpeg
        legacy_msg = None
        for client_id in list(watchers):
            ws = self.active_clients.get(client_id)
            if ws is None:
                continue
            try:
                if self.client_users.get(client_id):
                    await ws.send_bytes(binary)
                else:
                    if legacy_msg is None:
                        legacy_msg = {
                            "type": "screen_frame",
                            "device_id": device_id,
                            "image": base64.b64encode(jpeg).decode("ascii"),
                        }
                    await ws.send_json(legacy_msg)
            except Exception:
                pass

    async def broadcast_json(self, msg: dict):
        for client in list(self.active_clients.values()):
            try:
                await client.send_json(msg)
            except Exception:
                pass

    async def broadcast_device_list(self):
        devices = list(self.device_registry.values())
        msg = {
            "type": "device_list",
            "devices": devices
        }
        for client in list(self.active_clients.values()):
            try:
                await client.send_json(msg)
            except Exception:
                pass

    async def send_to_device(self, device_id: str, message: dict):
        ws = self.active_devices.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_json(message)
            return True
        except Exception as e:
            logger.warning(f"Falha ao enviar para {device_id}: {e}")
            return False

    async def send_to_client(self, client_id: str, message: dict):
        ws = self.active_clients.get(client_id)
        if ws is None:
            return False
        try:
            await ws.send_json(message)
            return True
        except Exception:
            return False

manager = ConnectionManager()

# --- Utilitários de Assinatura de APK para Provisionamento DPC ---
APK_SIGNATURE_CACHE = {}

def find_tools():
    # 0. Tenta encontrar no PATH do sistema primeiro
    apksigner_in_path = shutil.which("apksigner")
    if apksigner_in_path:
        return "apksigner", apksigner_in_path
        
    keytool_in_path = shutil.which("keytool")
    if keytool_in_path:
        return "keytool", keytool_in_path

    # 1. Procurar build-tools do Android SDK nos caminhos padroes
    local_appdata = os.environ.get("LOCALAPPDATA", "")
    sdk_paths = []
    if local_appdata:
        sdk_paths.append(os.path.join(local_appdata, "Android", "Sdk", "build-tools", "*", "apksigner.bat"))
    sdk_paths.append(r"C:\Users\*\AppData\Local\Android\Sdk\build-tools\*\apksigner.bat")
    
    for path_pattern in sdk_paths:
        matches = glob.glob(path_pattern)
        if matches:
            matches.sort()
            return "apksigner", matches[-1]
            
    # 2. Procurar keytool nos JDKs instalados
    jdk_paths = [
        r"C:\Program Files\Java\jdk*\bin\keytool.exe",
        r"C:\Program Files\Microsoft\jdk*\bin\keytool.exe",
        r"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
        r"C:\Program Files\Android\Android Studio\jre\bin\keytool.exe",
        r"C:\Program Files\Android\Android Studio\jre\jre\bin\keytool.exe"
    ]
    for path_pattern in jdk_paths:
        matches = glob.glob(path_pattern)
        if matches:
            matches.sort()
            return "keytool", matches[-1]
            
    return None, None

def get_apk_signature_checksum(apk_path: str) -> Optional[str]:
    if not os.path.exists(apk_path):
        return None

    sidecar_path = apk_path + ".checksum"
    if os.path.exists(sidecar_path):
        try:
            with open(sidecar_path, "r", encoding="utf-8") as f:
                sidecar = f.read().strip()
                if sidecar:
                    logger.info(f"Checksum lido do sidecar para {apk_path}")
                    return sidecar
        except Exception as e:
            logger.warning(f"Falha ao ler sidecar de checksum: {e}")
        
    stat = os.stat(apk_path)
    cache_key = (apk_path, stat.st_mtime, stat.st_size)
    if cache_key in APK_SIGNATURE_CACHE:
        return APK_SIGNATURE_CACHE[cache_key]
        
    tool_type, tool_path = find_tools()
    if not tool_path:
        logger.warning("Nenhuma ferramenta (apksigner/keytool) encontrada para extrair assinatura do APK.")
        return None
        
    checksum = None
    try:
        if tool_type == "apksigner":
            cmd = [tool_path, "verify", "--print-certs", apk_path]
            res = subprocess.run(cmd, capture_output=True, text=True, shell=False)
            if res.returncode != 0:
                logger.error(f"apksigner falhou: {res.stderr or res.stdout}")
            for line in res.stdout.splitlines():
                if "SHA-256 digest:" in line:
                    sha256_hex = line.split("SHA-256 digest:")[1].strip()
                    sha256_bytes = bytes.fromhex(sha256_hex)
                    checksum = base64.urlsafe_b64encode(sha256_bytes).decode('utf-8').rstrip('=')
                    break
        elif tool_type == "keytool":
            cmd = [tool_path, "-printcert", "-jarfile", apk_path]
            res = subprocess.run(cmd, capture_output=True, text=True, shell=False)
            if res.returncode != 0:
                logger.error(f"keytool falhou: {res.stderr or res.stdout}")
            for line in res.stdout.splitlines():
                if "SHA256:" in line or "SHA-256:" in line:
                    parts = line.split(":")
                    sha256_hex = "".join(parts[1:]).strip().replace(" ", "").replace(":", "")
                    sha256_bytes = bytes.fromhex(sha256_hex)
                    checksum = base64.urlsafe_b64encode(sha256_bytes).decode('utf-8').rstrip('=')
                    break
    except Exception as e:
        logger.error(f"Erro ao extrair assinatura do APK {apk_path}: {e}")
        
    if checksum:
        logger.info(f"Assinatura extraída com sucesso para {apk_path}: {checksum}")
        APK_SIGNATURE_CACHE[cache_key] = checksum
        
    return checksum

def get_apk_file_checksum(apk_path: str) -> Optional[str]:
    if not os.path.exists(apk_path):
        return None
    try:
        import hashlib
        with open(apk_path, "rb") as f:
            data = f.read()
            sha256_bytes = hashlib.sha256(data).digest()
            checksum = base64.urlsafe_b64encode(sha256_bytes).decode('utf-8').rstrip('=')
            logger.info(f"Checksum de arquivo gerado com sucesso para {apk_path}: {checksum}")
            return checksum
    except Exception as e:
        logger.error(f"Erro ao calcular checksum do arquivo APK {apk_path}: {e}")
        return None

# Funções Auxiliares de Segurança
def create_access_token(data: dict, expires_delta: Optional[timedelta] = None):
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=15)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt

def get_current_user(token: str = Depends(oauth2_scheme)):
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Credenciais inválidas ou token expirado",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        email: str = payload.get("sub")
        if email is None or email not in USERS_DB:
            raise credentials_exception
        return USERS_DB[email]
    except jwt.PyJWTError:
        raise credentials_exception


def _b64url_decode(part: str) -> bytes:
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


def verify_gestor_ticket(ticket: str) -> Optional[dict]:
    """Ticket curto emitido pelo Gestor: base64url(json) + "." + base64url(hmac_sha256)."""
    if not GESTOR_TOKEN or not ticket or "." not in ticket:
        return None
    body, sig = ticket.rsplit(".", 1)
    expected = hmac.new(GESTOR_TOKEN.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
    try:
        if not hmac.compare_digest(_b64url_decode(sig), expected):
            return None
        payload = json.loads(_b64url_decode(body))
    except Exception:
        return None
    now = time.time()
    if not isinstance(payload, dict) or float(payload.get("exp") or 0) < now:
        return None
    cid = payload.get("cid")
    if not payload.get("sub") or not cid:
        return None
    for old_cid, exp in list(_USED_TICKETS.items()):
        if exp < now:
            del _USED_TICKETS[old_cid]
    if cid in _USED_TICKETS:
        return None
    _USED_TICKETS[cid] = float(payload["exp"])
    return payload


# Ticket vale uma vez: client_id -> expiração.
_USED_TICKETS: Dict[str, float] = {}


def require_gestor(x_nexis_token: str = Header(default="")):
    if not GESTOR_TOKEN or not hmac.compare_digest(x_nexis_token.encode("utf-8"), GESTOR_TOKEN.encode("utf-8")):
        raise HTTPException(status_code=401, detail="token interno inválido")
    return True


# Comandos que o navegador pode mandar direto pelo WebSocket (sessão de tela).
CLIENT_WS_COMMANDS = {"click", "swipe", "key", "text", "start_mirroring", "stop_mirroring", "request_location"}
# Comandos que só passam pelo Gestor (/internal), que confere permissão e registra quem pediu.
INTERNAL_COMMANDS = {
    "lock_device", "clear_password", "wipe_device", "toast", "install_app",
    "request_location", "enable_location", "start_monitoring", "stop_monitoring", "set_location_interval",
    "stop_mirroring", "set_kiosk", "set_inventory", "list_apps", "wifi_scan",
}


class InternalCommand(BaseModel):
    type: str
    data: dict = {}
    requested_by: str = ""


# --- API interna (só o Gestor, pela própria VM) ---

@app.get("/internal/health")
async def internal_health(_: bool = Depends(require_gestor)):
    return {
        "ok": True,
        "devices_online": len(manager.active_devices),
        "clients": len(manager.active_clients),
        "device_token_required": bool(DEVICE_TOKEN),
    }


@app.get("/internal/devices")
async def internal_devices(_: bool = Depends(require_gestor)):
    devices = []
    for dev in manager.device_registry.values():
        item = dict(dev)
        item["status"] = "online" if dev.get("device_id") in manager.active_devices else "offline"
        item["viewers"] = len(manager.viewers.get(dev["device_id"], ()))
        devices.append(item)
    devices.sort(key=lambda d: (d.get("status") != "online", d.get("device_id", "")))
    return {"devices": devices}


@app.post("/internal/devices/{device_id}/command")
async def internal_command(device_id: str, cmd: InternalCommand, _: bool = Depends(require_gestor)):
    if cmd.type not in INTERNAL_COMMANDS:
        raise HTTPException(status_code=400, detail="comando não permitido")
    if device_id not in manager.active_devices:
        raise HTTPException(status_code=409, detail="coletor offline")
    logger.info(f"Comando {cmd.type} para {device_id} pedido por {cmd.requested_by or '?'}")
    if cmd.type == "install_app":
        apk_name = os.path.basename(str(cmd.data.get("apk_name") or ""))
        if not apk_name.endswith(".apk") or not os.path.exists(os.path.join(APKS_DIR, apk_name)):
            raise HTTPException(status_code=404, detail="APK não encontrado")
        message = {"type": "install_app", "url": f"/apks/{apk_name}"}
    else:
        message = {"type": cmd.type, "data": cmd.data}
    if cmd.type == "stop_mirroring":
        manager.viewers.pop(device_id, None)
    if not await manager.send_to_device(device_id, message):
        raise HTTPException(status_code=409, detail="coletor não respondeu")
    return {"ok": True}


@app.get("/internal/devices/{device_id}/location-history")
async def internal_location_history(device_id: str, _: bool = Depends(require_gestor)):
    return {"records": _filtered_location_history(device_id)}


class CalibrationRequest(BaseModel):
    latitude: float
    longitude: float
    label: str = ""
    requested_by: str = ""


# Pedido de calibração aguardando a leitura do Wi-Fi do coletor (device_id -> pedido).
PENDING_CALIBRATION: Dict[str, dict] = {}


@app.post("/internal/devices/{device_id}/calibrate")
async def internal_calibrate(device_id: str, req: CalibrationRequest, _: bool = Depends(require_gestor)):
    """Marca o ponto real do coletor; ele lê o Wi-Fi e a leitura vira um ponto de calibração."""
    if not (-90 <= req.latitude <= 90 and -180 <= req.longitude <= 180):
        raise HTTPException(status_code=400, detail="posição inválida")
    if device_id not in manager.active_devices:
        raise HTTPException(status_code=409, detail="coletor offline")
    token = secrets.token_hex(6)
    PENDING_CALIBRATION[device_id] = {
        "req": token,
        "latitude": float(req.latitude),
        "longitude": float(req.longitude),
        "label": str(req.label or "")[:80],
        "requested_by": str(req.requested_by or "")[:80],
        "at": time.time(),
    }
    if not await manager.send_to_device(device_id, {"type": "wifi_scan", "data": {"req": token}}):
        PENDING_CALIBRATION.pop(device_id, None)
        raise HTTPException(status_code=409, detail="coletor não respondeu")
    logger.info("Calibração pedida para %s por %s", device_id, req.requested_by or "?")
    return {"ok": True, "req": token}


@app.get("/internal/calibration")
async def internal_calibration_list(_: bool = Depends(require_gestor)):
    return {"anchors": [{k: v for k, v in a.items() if k != "aps"} | {"redes": len(a.get("aps") or {})} for a in load_anchors()]}


@app.delete("/internal/calibration/{anchor_id}")
async def internal_calibration_delete(anchor_id: str, _: bool = Depends(require_gestor)):
    anchors = load_anchors()
    left = [a for a in anchors if a.get("id") != anchor_id]
    if len(left) == len(anchors):
        raise HTTPException(status_code=404, detail="ponto não encontrado")
    save_anchors(left)
    return {"ok": True}


def _safe_apk_name(name: str) -> str:
    base = os.path.basename(name or "")
    if not base or base != (name or "") or not base.lower().endswith(".apk"):
        return ""
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
    if base.startswith(".") or any(ch not in allowed for ch in base):
        return ""
    return base


def _apk_meta(path: str) -> dict:
    """Lê pacote e versionName do manifesto binário. Falha vira campo vazio."""
    import re
    import struct
    import zipfile

    empty = {"version": "", "package": ""}
    try:
        data = zipfile.ZipFile(path).read("AndroidManifest.xml")
    except Exception:
        return empty
    if len(data) < 36 or data[:4] != b"\x03\x00\x08\x00":
        return empty
    try:
        off = 8
        _ctype, header, _size = struct.unpack_from("<HHI", data, off)
        count, _styles, flags, start, _ystart = struct.unpack_from("<IIIII", data, off + 8)
        if header < 28 or count > 20000:
            return empty
        base = off + start
        offsets = struct.unpack_from("<" + "I" * count, data, off + header)
        strings = []
        utf8 = flags & 1
        for rel in offsets:
            pos = base + rel
            if pos >= len(data):
                continue
            if utf8:
                n = data[pos]
                pos += 1
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | data[pos]
                    pos += 1
                blen = data[pos]
                pos += 1
                if blen & 0x80:
                    blen = ((blen & 0x7F) << 8) | data[pos]
                    pos += 1
                strings.append(data[pos:pos + blen].decode("utf-8", "replace"))
            else:
                n = struct.unpack_from("<H", data, pos)[0]
                pos += 2
                if n & 0x8000:
                    n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                strings.append(data[pos:pos + n * 2].decode("utf-16-le", "replace"))
    except Exception:
        return empty
    versions = [s for s in strings if re.fullmatch(r"\d+\.\d+\.\d+", s)]
    ids = [s for s in strings if re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}", s)]
    ids.sort(key=len)
    package = ""
    for item in ids:
        if any(other.startswith(item + ".") for other in ids):
            package = item
            break
    if not package and ids:
        package = ids[0]
    return {"version": versions[-1] if versions else "", "package": package}


@app.get("/internal/apks")
async def internal_apks(_: bool = Depends(require_gestor)):
    apks = []
    for f in sorted(os.listdir(APKS_DIR)):
        if f.endswith(".apk"):
            fp = os.path.join(APKS_DIR, f)
            meta = _apk_meta(fp)
            apks.append({
                "name": f,
                "size_bytes": os.path.getsize(fp),
                "version": meta["version"],
                "package": meta["package"],
            })
    return {"apks": apks}


@app.post("/internal/apks/upload")
async def internal_upload_apk(file: UploadFile = File(...), _: bool = Depends(require_gestor)):
    name = _safe_apk_name(file.filename or "")
    if not name:
        raise HTTPException(status_code=400, detail="envie um arquivo .apk com nome simples")
    dest = os.path.join(APKS_DIR, name)
    limit = 80 * 1024 * 1024
    size = 0
    try:
        with open(dest, "wb") as out:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise HTTPException(status_code=400, detail="APK acima de 80 MB")
                out.write(chunk)
    except HTTPException:
        try:
            os.remove(dest)
        except OSError:
            pass
        raise
    logger.info(f"APK recebido do Gestor: {name} ({size} bytes)")
    return {"ok": True, "name": name, "size_bytes": size}


# --- API REST Endpoints (painel antigo) ---

@app.post("/api/login", response_model=Token)
async def login(data: LoginRequest):
    user = USERS_DB.get(data.email)
    if not user:
        raise HTTPException(status_code=400, detail="E-mail ou senha incorretos")
    
    # Verifica hash da senha
    if not bcrypt.checkpw(data.password.encode('utf-8'), user["hashed_password"]):
        raise HTTPException(status_code=400, detail="E-mail ou senha incorretos")
    
    access_token_expires = timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    access_token = create_access_token(
        data={"sub": user["email"]}, expires_delta=access_token_expires
    )
    return {"access_token": access_token, "token_type": "bearer"}

@app.get("/api/me", response_model=UserProfile)
async def get_me(current_user: dict = Depends(get_current_user)):
    return {"email": current_user["email"], "name": current_user["name"]}

@app.get("/api/devices", response_model=List[DeviceInfo])
async def list_devices(current_user: dict = Depends(get_current_user)):
    return list(manager.device_registry.values())

@app.post("/api/apks/upload")
async def upload_apk(
    file: UploadFile = File(...),
    description: str = Form(""),
    current_user: dict = Depends(get_current_user)
):
    if not file.filename.endswith('.apk'):
        raise HTTPException(status_code=400, detail="Apenas arquivos .apk são permitidos")
    
    file_path = os.path.join(APKS_DIR, file.filename)
    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
    
    logger.info(f"APK salvo: {file.filename} por {current_user['email']}")
    return {"filename": file.filename, "size": os.path.getsize(file_path), "description": description}

@app.get("/api/apks")
async def list_apks(current_user: dict = Depends(get_current_user)):
    apks = []
    if os.path.exists(APKS_DIR):
        for f in os.listdir(APKS_DIR):
            if f.endswith('.apk'):
                fp = os.path.join(APKS_DIR, f)
                apks.append({
                    "name": f,
                    "size": f"{os.path.getsize(fp) / (1024*1024):.2f} MB",
                    "path": f"/apks/{f}"
                })
    return apks

@app.get("/api/dpc-info")
async def get_dpc_info(host: Optional[str] = None, use_package_hash: bool = False):
    apk_path = os.path.join(APKS_DIR, "app-debug.apk")

    if not os.path.exists(apk_path) and os.path.exists(APKS_DIR):
        for f in os.listdir(APKS_DIR):
            if f.endswith('.apk'):
                apk_path = os.path.join(APKS_DIR, f)
                break

    if not os.path.exists(apk_path):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="APK nao encontrado em backend/apks/. No Android Studio: Build > Select Build Variant > provision, depois Build > Build APK(s)."
        )

    # O coletor baixa o APK na configuração, pela borda 5087. HTTP exige o checksum do arquivo.
    download_url = (
        os.getenv("NEXIS_APK_PUBLIC_URL") or "http://10.70.2.5:5087/nexis/apks/app-debug.apk"
    ).strip()

    file_checksum = get_apk_file_checksum(apk_path)
    sig_checksum = get_apk_signature_checksum(apk_path)
    dpc_payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.nexis.remoteagent/com.nexis.remoteagent.DeviceAdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": download_url,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    }
    if file_checksum:
        dpc_payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = file_checksum
    else:
        logger.warning("Checksum de arquivo do APK ausente.")
    if sig_checksum:
        dpc_payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum
    else:
        logger.warning("Checksum da assinatura ausente. O provisionamento pode falhar no celular.")

    shown = sig_checksum if not use_package_hash else file_checksum
    return {
        "provisioning": dpc_payload,
        "meta": {
            "apk_name": os.path.basename(apk_path),
            "apk_size_bytes": os.path.getsize(apk_path),
            "checksum_present": bool(file_checksum and sig_checksum),
            "checksum": shown,
            "file_checksum": file_checksum,
            "signature_checksum": sig_checksum,
            "checksum_type": "file_and_signature",
            "download_url": download_url,
            "ready": bool(file_checksum and sig_checksum),
        }
    }

HISTORY_FILE = os.path.join(BASE_DIR, "location_history.json")

def load_location_history() -> dict:
    import json
    if not os.path.exists(HISTORY_FILE):
        return {}
    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}

def save_location_history(history: dict):
    import json
    try:
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history, f, indent=2, ensure_ascii=False)
    except Exception as e:
        logger.error(f"Erro ao salvar historico de localizacao: {e}")

def lookup_wifi_point(aps: list) -> Optional[dict]:
    ranked = sorted(
        [ap for ap in aps if isinstance(ap, dict)],
        key=lambda ap: int(ap.get("rssi") or -100),
        reverse=True,
    )
    mac = re.compile(r"(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    for ap in ranked[:6]:
        bssid = str(ap.get("bssid") or "").strip()
        if not mac.match(bssid):
            continue
        url = "https://api.mylnikov.org/geolocation/wifi?v=1.1&data=" + urllib.parse.quote(bssid)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "NexisColetores"})
            with urllib.request.urlopen(req, timeout=4) as resp:
                body = json.loads(resp.read().decode("utf-8", "replace"))
            if int(body.get("result") or 0) != 200:
                continue
            found = body.get("data") or {}
            return {
                "latitude": float(found["lat"]),
                "longitude": float(found["lon"]),
                "accuracy": float(found.get("range") or 150),
                "provider": "wifi",
            }
        except Exception as exc:
            logger.info("Geolocalização Wi-Fi sem resultado: %s", exc.__class__.__name__)
    return None


WIFI_CACHE_FILE = os.path.join(BASE_DIR, "location_wifi_cache.json")
_BSSID = re.compile(r"(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")


def load_wifi_cache() -> dict:
    if not os.path.exists(WIFI_CACHE_FILE):
        return {}
    try:
        with open(WIFI_CACHE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_wifi_cache(cache: dict):
    try:
        with open(WIFI_CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
    except Exception as e:
        logger.error("Erro ao salvar cache de Wi-Fi: %s", e)


def remember_wifi_fix(bssid: str, lat, lon, accuracy):
    mac = str(bssid or "").strip().upper()
    if not _BSSID.match(mac):
        return
    try:
        lat_f = float(lat)
        lon_f = float(lon)
        acc = float(accuracy or 150)
    except (TypeError, ValueError):
        return
    if not (-90 <= lat_f <= 90 and -180 <= lon_f <= 180):
        return
    if acc > 300:
        return
    cache = load_wifi_cache()
    cache[mac] = {
        "latitude": lat_f,
        "longitude": lon_f,
        "accuracy": max(acc, 80),
        "at": datetime.utcnow().isoformat() + "Z",
    }
    if len(cache) > 200:
        ordered = sorted(cache.items(), key=lambda item: str((item[1] or {}).get("at") or ""))
        cache = dict(ordered[-200:])
    save_wifi_cache(cache)


def lookup_cached_wifi(aps: list) -> Optional[dict]:
    cache = load_wifi_cache()
    if not cache:
        return None
    cutoff = datetime.utcnow() - timedelta(days=14)
    ranked = sorted(
        [ap for ap in aps if isinstance(ap, dict)],
        key=lambda ap: int(ap.get("rssi") or -100),
        reverse=True,
    )
    for ap in ranked[:8]:
        mac = str(ap.get("bssid") or "").strip().upper()
        found = cache.get(mac)
        if not isinstance(found, dict):
            continue
        try:
            seen = datetime.fromisoformat(str(found.get("at") or "").replace("Z", ""))
        except Exception:
            seen = datetime.utcnow()
        if seen < cutoff:
            continue
        return {
            "latitude": float(found["latitude"]),
            "longitude": float(found["longitude"]),
            "accuracy": float(found.get("accuracy") or 120),
            "provider": "wifi",
        }
    return None


ANCHORS_FILE = os.path.join(BASE_DIR, "location_wifi_anchors.json")
_MISSING_DBM = -95.0


def load_anchors() -> list:
    if not os.path.exists(ANCHORS_FILE):
        return []
    try:
        with open(ANCHORS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def save_anchors(anchors: list):
    try:
        with open(ANCHORS_FILE, "w", encoding="utf-8") as f:
            json.dump(anchors[-500:], f, ensure_ascii=False)
    except Exception as e:
        logger.error("Erro ao salvar pontos de calibração: %s", e)


def _ap_map(aps) -> dict:
    """{BSSID: dBm} das redes vistas (aceita lista [{bssid,rssi}] ou dict)."""
    out = {}
    items = aps.items() if isinstance(aps, dict) else [((a or {}).get("bssid"), (a or {}).get("rssi")) for a in (aps or []) if isinstance(a, dict)]
    for bssid, rssi in items:
        mac = str(bssid or "").strip().upper()
        if not _BSSID.match(mac):
            continue
        try:
            dbm = float(rssi)
        except (TypeError, ValueError):
            continue
        if -120 < dbm < 0:
            out[mac] = max(out.get(mac, -999.0), dbm)
    return out


def fingerprint_point(aps) -> Optional[dict]:
    """
    Estima a posição comparando o Wi-Fi visto agora com os pontos de calibração
    (k vizinhos mais parecidos, média ponderada). Só responde com 2+ redes em comum.
    """
    seen = _ap_map(aps)
    if not seen:
        return None
    scored = []
    for a in load_anchors():
        ref = _ap_map(a.get("aps") or {})
        common = set(seen) & set(ref)
        if len(common) < min(2, len(seen)):
            continue
        keys = set(seen) | set(ref)
        dist = (sum((seen.get(k, _MISSING_DBM) - ref.get(k, _MISSING_DBM)) ** 2 for k in keys) / len(keys)) ** 0.5
        scored.append((dist, a))
    if not scored:
        return None
    scored.sort(key=lambda x: x[0])
    near = scored[:3]
    weights = [1.0 / (d + 1.0) for d, _ in near]
    tot = sum(weights)
    lat = sum(w * float(a["latitude"]) for w, (_, a) in zip(weights, near)) / tot
    lon = sum(w * float(a["longitude"]) for w, (_, a) in zip(weights, near)) / tot
    best = near[0][0]
    return {
        "latitude": lat,
        "longitude": lon,
        "accuracy": round(max(6.0, min(60.0, 5.0 + best * 1.2)), 1),
        "provider": "calibrado",
    }


def _wifi_extra(data: dict) -> dict:
    """Sinal Wi-Fi guardado junto da posição (dBm da rede conectada e o ponto de acesso)."""
    out = {}
    try:
        rssi = int(data.get("rssi"))
        if -120 < rssi < 0:
            out["rssi"] = rssi
    except (TypeError, ValueError):
        pass
    bssid = str(data.get("bssid") or "").strip().upper()
    if _BSSID.match(bssid):
        out["bssid"] = bssid
    return out


def add_location_record(device_id: str, data: dict):
    history = load_location_history()
    if device_id not in history:
        history[device_id] = []
    
    new_record = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "latitude": data.get("latitude"),
        "longitude": data.get("longitude"),
        "accuracy": data.get("accuracy"),
        "provider": data.get("provider")
    }
    new_record.update(_wifi_extra(data))
    if data.get("raw_provider"):
        new_record["raw_provider"] = data.get("raw_provider")
        new_record["raw_accuracy"] = data.get("raw_accuracy")
    history[device_id].append(new_record)
    
    # Filtrar registros antigos (> 5 dias)
    cutoff = datetime.utcnow() - timedelta(days=5)
    filtered_records = []
    for rec in history[device_id]:
        try:
            rec_time = datetime.fromisoformat(rec["timestamp"].replace("Z", ""))
            if rec_time >= cutoff:
                filtered_records.append(rec)
        except Exception:
            filtered_records.append(rec)
            
    history[device_id] = filtered_records
    save_location_history(history)
    return new_record

def _filtered_location_history(device_id: str) -> list:
    records = load_location_history().get(device_id, [])
    cutoff = datetime.utcnow() - timedelta(days=5)
    filtered = []
    for rec in records:
        try:
            rec_time = datetime.fromisoformat(rec["timestamp"].replace("Z", ""))
            if rec_time >= cutoff:
                filtered.append(rec)
        except Exception:
            filtered.append(rec)
    return filtered


@app.get("/api/device/{device_id}/location-history")
async def get_device_location_history(device_id: str, current_user: dict = Depends(get_current_user)):
    try:
        return _filtered_location_history(device_id)
    except Exception as e:
        logger.exception("Erro ao obter historico de localizacao")
        raise HTTPException(status_code=500, detail=str(e))


# Rota para baixar os APKs
app.mount("/apks", StaticFiles(directory=APKS_DIR), name="apks")


# --- WebSocket Endpoints para Sinalização e Controle ---

def _device_token_ok(websocket: WebSocket) -> bool:
    if not DEVICE_TOKEN:
        return True
    sent = websocket.headers.get("x-nexis-device-token") or websocket.query_params.get("token") or ""
    return hmac.compare_digest(sent.encode("utf-8"), DEVICE_TOKEN.encode("utf-8"))


@app.websocket("/ws/device/{device_id}")
async def websocket_device(websocket: WebSocket, device_id: str):
    if not _device_token_ok(websocket):
        logger.warning(f"Coletor {device_id} recusado: token ausente ou inválido")
        await websocket.close(code=4401)
        return
    await manager.connect_device(device_id, websocket)
    try:
        while True:
            raw = await websocket.receive()
            if raw.get("type") == "websocket.disconnect":
                break
            manager.touch_device(device_id)

            # Agente novo: quadro de tela como JPEG binário.
            if raw.get("bytes") is not None:
                await manager.send_frame(device_id, raw["bytes"])
                continue

            try:
                data = json.loads(raw.get("text") or "{}")
            except ValueError:
                continue
            if not isinstance(data, dict):
                continue
            msg_type = data.get("type")

            # Telemetria recebida do dispositivo
            if msg_type == "telemetry":
                if device_id in manager.device_registry:
                    fields = {
                        "battery_level": data.get("battery", 100),
                        "charging": bool(data.get("charging")),
                        "ram_usage": data.get("ram", "N/A"),
                        "android_version": data.get("android_version", "Desconhecido"),
                        "model": data.get("model", "Zebra/Android"),
                        "serial": str(data.get("serial") or "")[:80],
                        "mac": str(data.get("mac") or "")[:32],
                        "ip": str(data.get("ip") or "")[:64],
                    }
                    agent_version = str(data.get("agent_version") or "").strip()[:20]
                    if agent_version:
                        fields["agent_version"] = agent_version
                    # Agente 1.0.37+: diz se é Device Owner. Sem isso o quiosque não aplica.
                    if "device_owner" in data:
                        fields["device_owner"] = bool(data.get("device_owner"))
                    wifi = data.get("wifi") if isinstance(data.get("wifi"), dict) else None
                    if wifi:
                        extra = _wifi_extra(wifi)
                        fields["wifi_rssi"] = extra.get("rssi")
                        fields["wifi_bssid"] = extra.get("bssid", "")
                        fields["wifi_ssid"] = str(wifi.get("ssid") or "")[:64]
                        try:
                            fields["wifi_freq"] = int(wifi.get("freq") or 0)
                            fields["wifi_link"] = int(wifi.get("link") or 0)
                        except (TypeError, ValueError):
                            pass
                    manager.device_registry[device_id].update(fields)
                await manager.broadcast_device_list()

            # Repasse de sinalização WebRTC (do celular para o suporte)
            elif msg_type in ["offer", "answer", "ice_candidate"]:
                target_client = data.get("target_client")
                if target_client:
                    await manager.send_to_client(target_client, {
                        "device_id": device_id,
                        "type": msg_type,
                        "data": data.get("data")
                    })

            elif msg_type == "apk_installed_status":
                await manager.broadcast_json({
                    "type": "notification",
                    "device_id": device_id,
                    "status": "success" if data.get("status") == "ok" else "error",
                    "message": data.get("message")
                })

            # Agente antigo: quadro em base64 dentro do JSON.
            elif msg_type == "screen_frame":
                image = data.get("image")
                if image and manager.viewers.get(device_id):
                    try:
                        await manager.send_frame(device_id, base64.b64decode(image))
                    except (ValueError, TypeError):
                        pass

            elif msg_type == "kiosk_state":
                if device_id in manager.device_registry:
                    mode = str(data.get("mode") or "off")
                    if mode not in ("off", "user", "admin"):
                        mode = "off"
                    packages = data.get("packages") if isinstance(data.get("packages"), list) else []
                    manager.device_registry[device_id]["kiosk_mode"] = mode
                    manager.device_registry[device_id]["kiosk_packages"] = [
                        str(item)[:120] for item in packages[:40]
                    ]
                    if "inventory" in data:
                        manager.device_registry[device_id]["inventory"] = bool(data.get("inventory"))
                    if "device_owner" in data:
                        manager.device_registry[device_id]["device_owner"] = bool(data.get("device_owner"))

            elif msg_type == "installed_apps":
                if device_id in manager.device_registry:
                    apps = data.get("apps") if isinstance(data.get("apps"), list) else []
                    clean = []
                    for item in apps[:80]:
                        if not isinstance(item, dict):
                            continue
                        pkg = str(item.get("package") or "")[:120]
                        label = str(item.get("label") or pkg)[:80]
                        if pkg:
                            clean.append({"package": pkg, "label": label})
                    manager.device_registry[device_id]["installed_apps"] = clean

            elif msg_type == "mirror_reset":
                await manager.broadcast_json({
                    "type": "mirror_reset",
                    "device_id": device_id,
                })

            elif msg_type == "device_log":
                await manager.broadcast_json({
                    "type": "device_log",
                    "device_id": device_id,
                    "status": data.get("status", "info"),
                    "message": data.get("message", "")
                })

            elif msg_type == "location_update":
                loc_data = data.get("data") if isinstance(data.get("data"), dict) else data
                if str(loc_data.get("provider") or "") != "wifi":
                    remember_wifi_fix(
                        loc_data.get("bssid"),
                        loc_data.get("latitude"),
                        loc_data.get("longitude"),
                        loc_data.get("accuracy"),
                    )
                try:
                    raw_acc = float(loc_data.get("accuracy") or 999)
                except (TypeError, ValueError):
                    raw_acc = 999.0
                if str(loc_data.get("provider") or "") != "gps" and raw_acc > 15 and loc_data.get("aps"):
                    cal = fingerprint_point(loc_data.get("aps"))
                    if cal and cal["accuracy"] < raw_acc:
                        cal.update(_wifi_extra(loc_data))
                        cal["raw_provider"] = loc_data.get("provider")
                        cal["raw_accuracy"] = raw_acc
                        loc_data = cal
                new_rec = add_location_record(device_id, loc_data)
                await manager.broadcast_json({
                    "type": "location_update",
                    "device_id": device_id,
                    "data": new_rec
                })

            elif msg_type == "wifi_scan":
                payload = data.get("data") if isinstance(data.get("data"), dict) else data
                pend = PENDING_CALIBRATION.get(device_id)
                aps = _ap_map(payload.get("aps") or [])
                if pend and pend.get("req") == payload.get("req") and time.time() - pend["at"] < 120:
                    PENDING_CALIBRATION.pop(device_id, None)
                    if len(aps) < 1:
                        msg, level = "Calibração não salva: o coletor não viu nenhuma rede Wi-Fi.", "warn"
                    else:
                        anchors = load_anchors()
                        anchors.append({
                            "id": secrets.token_hex(6),
                            "latitude": pend["latitude"],
                            "longitude": pend["longitude"],
                            "label": pend.get("label") or "",
                            "device_id": device_id,
                            "requested_by": pend.get("requested_by") or "",
                            "at": datetime.utcnow().isoformat() + "Z",
                            "aps": aps,
                        })
                        save_anchors(anchors)
                        msg, level = f"Ponto de calibração salvo ({len(aps)} rede(s) Wi-Fi).", "info"
                    await manager.broadcast_json({"type": "device_log", "device_id": device_id, "status": level, "message": msg})
                    await manager.broadcast_json({"type": "calibration_saved", "device_id": device_id, "ok": level == "info"})

            elif msg_type == "wifi_location":
                payload = data.get("data") if isinstance(data.get("data"), dict) else data
                aps = payload.get("aps") if isinstance(payload.get("aps"), list) else []
                point = fingerprint_point(aps)
                if point is None:
                    point = await asyncio.to_thread(lookup_wifi_point, aps[:8])
                if point is None:
                    point = lookup_cached_wifi(aps[:8])
                if point:
                    strongest = max(_ap_map(aps).values(), default=None)
                    if strongest is not None:
                        point["rssi"] = int(strongest)
                    new_rec = add_location_record(device_id, point)
                    await manager.broadcast_json({
                        "type": "location_update",
                        "device_id": device_id,
                        "data": new_rec
                    })
                    await manager.broadcast_json({
                        "type": "device_log",
                        "device_id": device_id,
                        "status": "info",
                        "message": "Ponto estimado pelo Wi-Fi" + (" (calibrado)." if point.get("provider") == "calibrado" else ".")
                    })
                else:
                    await manager.broadcast_json({
                        "type": "device_log",
                        "device_id": device_id,
                        "status": "warn",
                        "message": "Sem GPS. As redes Wi-Fi vistas não têm ponto de calibração nem estão num mapa público."
                    })

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"Erro no WebSocket do dispositivo {device_id}: {str(e)}")
    manager.disconnect_device(device_id, websocket)
    await manager.broadcast_device_list()

@app.websocket("/ws/client/{client_id}")
async def websocket_client(websocket: WebSocket, client_id: str):
    username = ""
    ticket = websocket.query_params.get("ticket") or ""
    if ticket:
        payload = verify_gestor_ticket(ticket)
        if not payload or payload.get("cid") != client_id:
            await websocket.close(code=4401)
            return
        username = str(payload["sub"])
    elif not LEGACY_PANEL:
        await websocket.close(code=4401)
        return

    await manager.connect_client(client_id, websocket, username)
    await manager.broadcast_device_list()
    try:
        while True:
            data = await websocket.receive_json()
            if not isinstance(data, dict):
                continue
            target_device = data.get("target_device")
            msg_type = data.get("type")
            if not target_device:
                continue

            if msg_type in ["offer", "answer", "ice_candidate"]:
                await manager.send_to_device(target_device, {
                    "target_client": client_id,
                    "type": msg_type,
                    "data": data.get("data")
                })
                continue

            if msg_type not in CLIENT_WS_COMMANDS:
                # Painel antigo ainda manda comandos administrativos por aqui.
                if not (LEGACY_PANEL and not username and msg_type in INTERNAL_COMMANDS):
                    continue

            if msg_type == "start_mirroring":
                manager.add_viewer(target_device, client_id)
                logger.info(f"Tela de {target_device} aberta por {username or client_id}")
            elif msg_type == "stop_mirroring":
                if not manager.remove_viewer(target_device, client_id):
                    continue

            if msg_type == "install_app":
                apk_name = os.path.basename(str(data.get("apk_name") or ""))
                await manager.send_to_device(target_device, {
                    "target_client": client_id,
                    "type": "install_app",
                    "url": f"/apks/{apk_name}"
                })
                continue

            sent = await manager.send_to_device(target_device, {
                "target_client": client_id,
                "type": msg_type,
                "data": data
            })
            if not sent:
                if msg_type == "start_mirroring":
                    manager.remove_viewer(target_device, client_id)
                await manager.send_to_client(client_id, {
                    "type": "notification",
                    "status": "error",
                    "device_id": target_device,
                    "message": "O coletor não está no servidor. No aparelho, feche o Nexis por completo e abra de novo.",
                })

    except WebSocketDisconnect:
        pass
    except RuntimeError as e:
        if "not connected" not in str(e):
            logger.error(f"Erro no WebSocket do cliente {client_id}: {str(e)}")
    except Exception as e:
        logger.error(f"Erro no WebSocket do cliente {client_id}: {str(e)}")
    watched = [d for d, w in manager.viewers.items() if client_id in w]
    manager.disconnect_client(client_id)
    for device_id in watched:
        if not manager.viewers.get(device_id):
            try:
                await manager.send_to_device(device_id, {"type": "stop_mirroring", "data": {}})
            except Exception:
                pass


if LEGACY_PANEL:
    @app.get("/")
    async def serve_dashboard():
        index_path = os.path.join(STATIC_DIR, "index.html")
        if os.path.exists(index_path):
            from fastapi.responses import FileResponse
            return FileResponse(index_path)
        return {"message": "Nexis Remote Control Server está rodando! Painel Web não encontrado em /static."}

    app.mount("/", StaticFiles(directory=STATIC_DIR), name="static")
else:
    @app.get("/")
    async def root():
        return {"ok": True, "service": "nexis-coletores"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=os.getenv("NEXIS_BIND_HOST", "127.0.0.1"),
        port=int(os.getenv("NEXIS_PORT", "6061")),
        reload=_env_flag("NEXIS_RELOAD"),
        reload_dirs=[BASE_DIR],
    )
