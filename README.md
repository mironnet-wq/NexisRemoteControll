# Nexis Remote Control

Sistema de controle e monitoramento remoto de dispositivos Android com servidor web e agent MDM.

## Componentes

- **backend/** — API FastAPI, dashboard web e relay WebSocket (porta 6061)
- **android-agent/** — App Android (Device Owner / DPC) instalado nos celulares

## Requisitos

- Python 3.10+
- Android Studio (JDK 11+) para compilar o agent
- Dispositivo Android 8+ (API 26)

## Servidor

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py
```

Dashboard: `http://<IP-SERVIDOR>:6061`  
Login padrão: `admin@nexis.com` / `admin123`

Variável opcional:

```powershell
$env:NEXIS_SERVER_HOST = "172.16.0.100:6061"
```

## Agent Android

1. Abra `android-agent/` no Android Studio
2. Build Variant: `provision` (recomendado) ou `debug`
3. Build APK — copia automaticamente para `backend/apks/`
4. Provisionamento: factory reset → 6 toques na tela Welcome → escanear QR no dashboard

Configure o IP do servidor em `RemoteControlService.kt` (`SERVER_HOST`).

## Funcionalidades

- Espelhamento de tela e controle remoto (Accessibility)
- Device Owner: lock, wipe, install silencioso de APK
- Monitoramento de conectividade (Wi-Fi, dados móveis, GPS)
- Localização sob demanda (comando `request_location`)
- Histórico de localização (5 dias)

## Estrutura

```
NexisRemoteControll/
├── backend/
│   ├── main.py
│   ├── static/          # Dashboard
│   └── apks/            # APKs para provisionamento
└── android-agent/
    └── app/src/main/java/com/nexis/remoteagent/
```
