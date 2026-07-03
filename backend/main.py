import os
import shutil
import logging
import base64
import glob
import subprocess
from typing import Dict, List, Optional
from datetime import datetime, timedelta
import bcrypt
import jwt
from fastapi import FastAPI, Depends, HTTPException, status, WebSocket, WebSocketDisconnect, UploadFile, File, Form
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
DEFAULT_SERVER_HOST = os.getenv("NEXIS_SERVER_HOST", "172.16.0.100:6061")

# Configurações de Segurança
SECRET_KEY = "nexis_super_secure_development_secret_key"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 120

# Dados de usuário Mock (Para Demonstração)
# Senha padrão: admin123
PASSWORD_SALT = bcrypt.gensalt()
HASHED_PASSWORD = bcrypt.hashpw("admin123".encode('utf-8'), PASSWORD_SALT)

USERS_DB = {
    "admin@nexis.com": {
        "email": "admin@nexis.com",
        "hashed_password": HASHED_PASSWORD,
        "name": "Administrador Nexis"
    }
}

app = FastAPI(title="Nexis Remote Control Server", version="1.0.0")

# Habilitar CORS para desenvolvimento local
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
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
        # Informações de telemetria dos dispositivos
        self.device_registry: Dict[str, Dict] = {
            "zebra_mc3300_demo": {
                "device_id": "zebra_mc3300_demo",
                "model": "Zebra MC3300x",
                "android_version": "10.0 (API 29)",
                "battery_level": 84,
                "ram_usage": "1.8 GB / 4.0 GB",
                "status": "offline",
                "is_zebra": True
            },
            "pixel_8_demo": {
                "device_id": "pixel_8_demo",
                "model": "Google Pixel 8",
                "android_version": "14.0 (API 34)",
                "battery_level": 92,
                "ram_usage": "3.5 GB / 8.0 GB",
                "status": "offline",
                "is_zebra": False
            }
        }

    async def connect_device(self, device_id: str, websocket: WebSocket):
        await websocket.accept()
        self.active_devices[device_id] = websocket
        if device_id not in self.device_registry:
            self.device_registry[device_id] = {
                "device_id": device_id,
                "model": "Dispositivo Desconhecido",
                "android_version": "Desconhecido",
                "battery_level": 100,
                "ram_usage": "N/A",
                "status": "online",
                "is_zebra": "zebra" in device_id.lower()
            }
        else:
            self.device_registry[device_id]["status"] = "online"
        
        logger.info(f"Dispositivo conectado: {device_id}")
        await self.broadcast_device_list()

    def disconnect_device(self, device_id: str):
        if device_id in self.active_devices:
            del self.active_devices[device_id]
        if device_id in self.device_registry:
            self.device_registry[device_id]["status"] = "offline"
        logger.info(f"Dispositivo desconectado: {device_id}")

    async def connect_client(self, client_id: str, websocket: WebSocket):
        await websocket.accept()
        self.active_clients[client_id] = websocket
        logger.info(f"Cliente de suporte conectado: {client_id}")

    def disconnect_client(self, client_id: str):
        if client_id in self.active_clients:
            del self.active_clients[client_id]
        logger.info(f"Cliente de suporte desconectado: {client_id}")

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
        if device_id in self.active_devices:
            await self.active_devices[device_id].send_json(message)
            return True
        return False

    async def send_to_client(self, client_id: str, message: dict):
        if client_id in self.active_clients:
            await self.active_clients[client_id].send_json(message)
            return True
        return False

manager = ConnectionManager()

# --- Utilitários de Assinatura de APK para Provisionamento DPC ---
APK_SIGNATURE_CACHE = {}

def find_tools():
    # 1. Procurar build-tools do Android SDK
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

# --- API REST Endpoints ---

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
async def get_dpc_info(host: Optional[str] = None):
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

    checksum = get_apk_signature_checksum(apk_path)
    server_host = host or DEFAULT_SERVER_HOST
    download_url = f"http://{server_host}/apks/{os.path.basename(apk_path)}"

    dpc_payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.nexis.remoteagent/com.nexis.remoteagent.DeviceAdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": download_url,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True
    }

    if checksum:
        dpc_payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = checksum
    else:
        logger.warning("Checksum da assinatura ausente. O provisionamento pode falhar no celular.")

    return {
        "provisioning": dpc_payload,
        "meta": {
            "apk_name": os.path.basename(apk_path),
            "apk_size_bytes": os.path.getsize(apk_path),
            "checksum_present": checksum is not None,
            "checksum": checksum,
            "download_url": download_url,
            "ready": checksum is not None,
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

@app.get("/api/device/{device_id}/location-history")
async def get_device_location_history(device_id: str, current_user: dict = Depends(get_current_user)):
    try:
        history = load_location_history()
        records = history.get(device_id, [])
        
        # Se for um dispositivo demo e o histórico estiver vazio, gerar um trajeto histórico fictício para demonstração
        if not records and device_id.endswith("_demo"):
            import random
            now = datetime.utcnow()
            
            # Gerar 4 pontos sequenciais conectando uma rota simulada
            records = []
            base_lat = -23.550520
            base_lng = -46.633308
            for i in range(4, 0, -1):
                records.append({
                    "timestamp": (now - timedelta(days=i, hours=random.randint(1, 5))).isoformat() + "Z",
                    "latitude": base_lat + (4 - i) * 0.005 + random.uniform(-0.001, 0.001),
                    "longitude": base_lng + (4 - i) * 0.005 + random.uniform(-0.001, 0.001),
                    "accuracy": random.uniform(8.0, 20.0),
                    "provider": random.choice(["gps", "network"])
                })
            history[device_id] = records
            save_location_history(history)
        
        # Filtrar no momento da leitura também para garantir a limpeza de 5 dias
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
    except Exception as e:
        logger.exception("Erro ao obter historico de localizacao")
        raise HTTPException(status_code=500, detail=str(e))


# Rota para baixar os APKs
app.mount("/apks", StaticFiles(directory=APKS_DIR), name="apks")


# --- WebSocket Endpoints para Sinalização e Controle ---

@app.websocket("/ws/device/{device_id}")
async def websocket_device(websocket: WebSocket, device_id: str):
    await manager.connect_device(device_id, websocket)
    try:
        while True:
            # Recebe mensagens do celular
            data = await websocket.receive_json()
            msg_type = data.get("type")
            
            # Telemetria recebida do dispositivo
            if msg_type == "telemetry":
                if device_id in manager.device_registry:
                    manager.device_registry[device_id].update({
                        "battery_level": data.get("battery", 100),
                        "ram_usage": data.get("ram", "N/A"),
                        "android_version": data.get("android_version", "Desconhecido"),
                        "model": data.get("model", "Zebra/Android")
                    })
                await manager.broadcast_device_list()
                
            # Repasse de sinalização WebRTC (do celular para o suporte)
            elif msg_type in ["offer", "answer", "ice_candidate"]:
                target_client = data.get("target_client")
                if target_client:
                    # Envia a mensagem do dispositivo para o navegador do analista
                    await manager.send_to_client(target_client, {
                        "device_id": device_id,
                        "type": msg_type,
                        "data": data.get("data")
                    })
                    
            elif msg_type == "apk_installed_status":
                target_client = data.get("target_client")
                if target_client:
                    await manager.send_to_client(target_client, {
                        "type": "notification",
                        "status": "success" if data.get("status") == "ok" else "error",
                        "message": data.get("message")
                    })
                    
            elif msg_type == "screen_frame":
                # Broadcast the screen frame to all support clients
                msg = {
                    "type": "screen_frame",
                    "device_id": device_id,
                    "image": data.get("image")
                }
                for client in list(manager.active_clients.values()):
                    try:
                        await client.send_json(msg)
                    except Exception:
                        pass

            elif msg_type == "device_log":
                # Broadcast the device log message to support clients
                msg = {
                    "type": "device_log",
                    "device_id": device_id,
                    "status": data.get("status", "info"),
                    "message": data.get("message", "")
                }
                for client in list(manager.active_clients.values()):
                    try:
                        await client.send_json(msg)
                    except Exception:
                        pass

            elif msg_type == "location_update":
                loc_data = data.get("data") if isinstance(data.get("data"), dict) else data
                new_rec = add_location_record(device_id, loc_data)
                msg = {
                    "type": "location_update",
                    "device_id": device_id,
                    "data": new_rec
                }
                for client in list(manager.active_clients.values()):
                    try:
                        await client.send_json(msg)
                    except Exception:
                        pass

    except WebSocketDisconnect:
        manager.disconnect_device(device_id)
        await manager.broadcast_device_list()
    except Exception as e:
        logger.error(f"Erro no WebSocket do dispositivo {device_id}: {str(e)}")
        manager.disconnect_device(device_id)
        await manager.broadcast_device_list()

@app.websocket("/ws/client/{client_id}")
async def websocket_client(websocket: WebSocket, client_id: str):
    await manager.connect_client(client_id, websocket)
    # Envia lista de dispositivos atual assim que conectar
    await manager.broadcast_device_list()
    try:
        while True:
            # Recebe mensagens do painel web
            data = await websocket.receive_json()
            target_device = data.get("target_device")
            msg_type = data.get("type")
            
            if not target_device:
                continue

            # Se for uma mensagem de simulação direta ou controle real
            # Repasse de sinalização WebRTC para o celular
            if msg_type in ["offer", "answer", "ice_candidate"]:
                await manager.send_to_device(target_device, {
                    "target_client": client_id,
                    "type": msg_type,
                    "data": data.get("data")
                })
            
            # Comandos de controle de tela (Acessibilidade)
            elif msg_type in ["click", "swipe", "key"]:
                logger.info(f"Comando de tela enviado para {target_device}: {data}")
                await manager.send_to_device(target_device, {
                    "type": msg_type,
                    "data": data
                })
                
            # Comandos de mídia (Câmera / Microfone)
            elif msg_type in ["camera_control", "audio_control"]:
                logger.info(f"Comando de mídia enviado para {target_device}: {data}")
                await manager.send_to_device(target_device, {
                    "type": msg_type,
                    "data": data
                })
                
            # Comando de Instalação Silenciosa
            elif msg_type == "install_app":
                logger.info(f"Comando de instalação enviado para {target_device}: {data}")
                # Envia URL do APK hospedado no servidor
                await manager.send_to_device(target_device, {
                    "target_client": client_id,
                    "type": "install_app",
                    "url": f"/apks/{data.get('apk_name')}"
                })
                
            # Comandos Administrativos (DPC e Toast) e Localizacao
            elif msg_type in ["lock_device", "clear_password", "wipe_device", "toast", "start_mirroring", "stop_mirroring", "request_location", "start_monitoring", "stop_monitoring", "set_location_interval"]:
                logger.info(f"Comando corporativo/localizacao enviado para {target_device}: {data}")
                
                # Simulação para dispositivos de demonstração (demo)
                if target_device.endswith("_demo") and msg_type == "request_location":
                    import asyncio
                    import random
                    
                    # Gerar ponto aleatório ao redor de São Paulo
                    lat = -23.550520 + random.uniform(-0.02, 0.02)
                    lng = -46.633308 + random.uniform(-0.02, 0.02)
                    accuracy = random.uniform(5.0, 15.0)
                    provider = random.choice(["gps", "network"])
                    
                    async def send_mock_location():
                        await asyncio.sleep(0.8)
                        loc_data = {
                            "latitude": lat,
                            "longitude": lng,
                            "accuracy": accuracy,
                            "provider": provider
                        }
                        new_rec = add_location_record(target_device, loc_data)
                        msg = {
                            "type": "location_update",
                            "device_id": target_device,
                            "data": new_rec
                        }
                        await manager.send_to_client(client_id, msg)
                        
                    asyncio.create_task(send_mock_location())
                else:
                    await manager.send_to_device(target_device, {
                        "target_client": client_id,
                        "type": msg_type,
                        "data": data
                    })

                
    except WebSocketDisconnect:
        manager.disconnect_client(client_id)
    except Exception as e:
        logger.error(f"Erro no WebSocket do cliente {client_id}: {str(e)}")
        manager.disconnect_client(client_id)

# Servir static files no final para as rotas da web dashboard
# Se index.html existir em static, ele será servido na raiz "/"
@app.get("/")
async def serve_dashboard():
    index_path = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_path):
        from fastapi.responses import FileResponse
        return FileResponse(index_path)
    return {"message": "Nexis Remote Control Server está rodando! Painel Web não encontrado em /static."}

app.mount("/", StaticFiles(directory=STATIC_DIR), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=6061, reload=True, reload_dirs=[BASE_DIR])
