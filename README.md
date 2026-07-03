# Nexis Remote Control

Sistema corporativo para **acessar, controlar e monitorar remotamente** smartphones Android. Composto por um **servidor central** (API + dashboard web) e um **agent MDM** instalado nos celulares como **Device Owner** (administrador do dispositivo).

Repositório: https://github.com/mironnet-wq/NexisRemoteControll

---

## Visão geral

```
┌─────────────────┐     WebSocket      ┌──────────────────┐
│  Operador (web) │ ◄────────────────► │  Backend FastAPI │
│  Dashboard      │     REST / JWT       │  Porta 6061      │
└─────────────────┘                      └────────┬─────────┘
                                                  │ WebSocket
                                                  ▼
                                         ┌──────────────────┐
                                         │  Nexis Agent     │
                                         │  (Android DPC)   │
                                         └──────────────────┘
```

O operador acessa o dashboard no navegador, seleciona um device e envia comandos (espelhamento, toques, localização, lock, etc.). O backend **repassa** os comandos via WebSocket ao agent conectado. O agent executa no celular e envia telemetria, logs, frames de tela e coordenadas GPS de volta.

---

## Componentes

### Backend (`backend/`)

| Arquivo / pasta | Função |
|-----------------|--------|
| `main.py` | API FastAPI, autenticação JWT, relay WebSocket, histórico de GPS, hosting de APK |
| `static/` | Dashboard web (HTML/CSS/JS) — console do operador |
| `apks/` | APKs do agent servidos para provisionamento QR e instalação remota |
| `requirements.txt` | Dependências Python |

**Endpoints principais**

| Método | Rota | Descrição |
|--------|------|-----------|
| POST | `/api/login` | Login (retorna JWT) |
| GET | `/api/devices` | Lista devices registrados |
| GET | `/api/dpc-info` | Payload JSON para QR de provisionamento |
| GET | `/api/device/{id}/location-history` | Histórico GPS (5 dias) |
| WS | `/ws/client/{id}` | Canal do operador (dashboard) |
| WS | `/ws/device/{id}` | Canal do agent Android |

### Agent Android (`android-agent/`)

| Classe | Função |
|--------|--------|
| `RemoteControlService` | Serviço principal: WebSocket, telemetria, espelhamento, comandos |
| `RemoteAccessibilityService` | Injeção de toques/gestos; fallback para religar dados móveis |
| `DeviceAdminReceiver` | Device Owner / DPC; políticas e provisionamento |
| `EnterpriseConnectivityPolicy` | User Restrictions + reforço Wi-Fi/dados/GPS |
| `MainActivity` | UI de status e permissões |
| `PackageInstallHelper` | Instalação silenciosa de APK (Device Owner) |
| `AutostartReceiver` | Inicia o serviço no boot |

---

## Funcionalidades

### Controle remoto
- Espelhamento de tela (MediaProjection + JPEG via WebSocket)
- Toques e gestos (Accessibility Service)
- Teclas Android (Home, Back, etc.)
- Toast remoto

### MDM / Device Owner
- Provisionamento via **QR Code** (factory reset + 6 toques na Welcome)
- Lock de tela, wipe (factory reset), limpar senha
- Instalação silenciosa de APKs hospedados no servidor
- User Restrictions: bloqueio de alteração de Wi-Fi e configurações de rede

### Conectividade (watchdog)
- Comando `start_monitoring` / `stop_monitoring` no dashboard
- **Wi-Fi**: reativa automaticamente quando desligado
- **Dados móveis**: DPC + fallback via Accessibility (Motorola e similares)
- **GPS**: reforço apenas com monitoramento ativo

### Localização
- **Sob demanda**: comando `request_location` (sem rastreamento contínuo no boot)
- **Contínuo**: apenas com monitoramento ativo (padrão 5 min / 50 m)
- Configurável via `set_location_interval` (intervalo em ms, distância em m)
- Histórico no servidor por 5 dias

---

## Requisitos

### Servidor
- Windows ou Linux
- Python 3.10+
- Porta **6061** liberada no firewall (TCP)
- Rede acessível pelos celulares (mesma LAN ou rota configurada)

### Compilação do agent
- Android Studio (JDK 11+)
- Android SDK (compileSdk 34)

### Celulares
- Android 8.0+ (API 26)
- Factory reset para provisionamento Device Owner
- Wi-Fi corporativo acessível ao IP do servidor

---

## Implantação em outro local

Checklist para instalar o sistema em um **novo servidor / nova rede**.

### 1. Clonar o repositório

```powershell
git clone https://github.com/mironnet-wq/NexisRemoteControll.git
cd NexisRemoteControll
```

### 2. Descobrir o IP do servidor

No PC que será o servidor, anote o IP na rede local (ex.: `192.168.1.50`).

```powershell
ipconfig
```

O endereço completo será: **`192.168.1.50:6061`** (substitua pelo seu IP).

### 3. Configurar o IP em 3 pontos

| # | Arquivo | O que alterar |
|---|---------|---------------|
| 1 | `android-agent/.../RemoteControlService.kt` | `SERVER_HOST = "SEU_IP:6061"` |
| 2 | `backend/main.py` | `DEFAULT_SERVER_HOST` ou env `NEXIS_SERVER_HOST` |
| 3 | `backend/static/app.js` | `DEFAULT_SERVER_HOST` (fallback quando dashboard aberto em localhost) |

**Recomendado (backend)** — variável de ambiente, sem editar código:

```powershell
$env:NEXIS_SERVER_HOST = "192.168.1.50:6061"
```

> **Importante:** sempre acesse o dashboard pelo IP real (`http://192.168.1.50:6061`), não por `localhost`, para o QR de provisionamento gerar URLs corretas para os celulares.

### 4. Subir o servidor

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py
```

O servidor escuta em `0.0.0.0:6061` (todas as interfaces).

**Teste:** abra `http://SEU_IP:6061` no navegador.

### 5. Firewall (Windows)

```powershell
New-NetFirewallRule -DisplayName "Nexis Remote Control" -Direction Inbound -Protocol TCP -LocalPort 6061 -Action Allow
```

### 6. Compilar o agent Android

1. Abra `android-agent/` no Android Studio
2. Altere `SERVER_HOST` em `RemoteControlService.kt` (passo 3)
3. **Build Variants** → selecione `provision` (recomendado) ou `debug`
4. **Build → Build APK(s)**

O APK é copiado para `backend/apks/app-debug.apk` (+ arquivo `.checksum`).

**Verifique:**
- `http://SEU_IP:6061/apks/app-debug.apk` — deve baixar o APK
- Dashboard → **Ver QR Code** → status **Pronto: SIM**

### 7. Provisionar celulares

1. **Factory reset** no aparelho
2. Na tela **Welcome**, toque **6 vezes** → modo QR
3. Conecte Wi-Fi (mesma rede do servidor)
4. Escaneie o QR do dashboard (`http://SEU_IP:6061`)
5. Aguarde instalação automática do agent como **Device Owner**

### 8. Pós-provisionamento (no celular)

Abra o app **Nexis Agent** e confirme:

| Item | Onde |
|------|------|
| Device Owner | Tela do app → "ATIVADO (DPC)" |
| Acessibilidade | Configurações → Acessibilidade → Nexis |
| Localização | Permissão concedida (não precisa "sempre" para modo sob demanda) |

### 9. Operar

1. Dashboard → login (`admin@nexis.com` / `admin123` — **altere em produção**)
2. Selecione o device na barra lateral
3. Inicie sessão remota, espelhamento, monitoramento, etc.

---

## Variáveis e configuração

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `NEXIS_SERVER_HOST` | `172.16.0.100:6061` | Host usado no QR e URLs de APK |
| Porta Uvicorn | `6061` | Em `main.py` (última linha) |

Credenciais padrão (mock, em memória):

- E-mail: `admin@nexis.com`
- Senha: `admin123`

---

## Comandos WebSocket (operador → device)

| Comando | Descrição |
|---------|-----------|
| `start_mirroring` / `stop_mirroring` | Espelhamento de tela |
| `click`, `swipe`, `key` | Controle remoto |
| `request_location` | GPS pontual |
| `set_location_interval` | `{ interval: ms, distance: metros }` |
| `start_monitoring` / `stop_monitoring` | Watchdog Wi-Fi/dados/GPS |
| `lock_device`, `wipe_device`, `clear_password` | DPC |
| `install_app` | Instalar APK do servidor |
| `toast` | Mensagem no celular |

---

## Estrutura do projeto

```
NexisRemoteControll/
├── backend/
│   ├── main.py                 # Servidor
│   ├── requirements.txt
│   ├── apks/                   # APKs (gerados, não versionados)
│   └── static/
│       ├── index.html          # Dashboard
│       ├── app.js
│       └── styles.css
├── android-agent/
│   ├── app/
│   │   ├── build.gradle        # Variants debug / provision / release
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/nexis/remoteagent/
│   ├── gradlew.bat
│   └── settings.gradle
├── .gitignore
└── README.md
```

---

## Segurança (produção)

Antes de usar em ambiente real, considere:

- Alterar `SECRET_KEY` e credenciais em `main.py`
- Autenticar WebSockets (hoje abertos)
- Usar **HTTPS/WSS** (reverse proxy nginx/Caddy)
- Não expor porta 6061 na internet pública sem TLS
- Assinar APK release (não usar debug em produção)
- Remover devices demo e fallbacks de teste

---

## Solução de problemas

| Problema | Solução |
|----------|---------|
| QR provisionamento falha | Verifique APK em `backend/apks/`, checksum **Pronto: SIM**, mesmo Wi-Fi |
| Device não aparece | Confirme `SERVER_HOST` no agent, firewall 6061, WebSocket conectado |
| Dados móveis não religam | Ative **Acessibilidade**; Motorola exige fallback UI |
| Notificações GPS frequentes | Normal com monitoramento ON; use sob demanda sem monitoramento |
| Run Android Studio falha | `adb shell am force-stop com.nexis.remoteagent` |

---

## Licença

Uso interno / corporativo Nexis. Ajuste conforme política da sua organização.
