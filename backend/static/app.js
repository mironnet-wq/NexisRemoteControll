// Nexis Remote Control Application Logic
document.addEventListener("DOMContentLoaded", () => {
    // Inicializar Ícones Lucide
    lucide.createIcons();

    // --- ESTADO DA APLICAÇÃO ---
    let token = localStorage.getItem("nexis_token") || null;
    let socket = null;
    let clientId = "support_agent_" + Math.random().toString(36).substring(2, 9);
    let activeDevice = null;
    let sessionTimer = null;
    let sessionSeconds = 0;


    // --- ELEMENTOS DOM ---
    // Auth Views
    const loginContainer = document.getElementById("login-container");
    const dashboardContainer = document.getElementById("dashboard-container");
    const loginForm = document.getElementById("login-form");
    const loginError = document.getElementById("login-error");
    const errorText = document.getElementById("error-text");
    const loginBtn = document.getElementById("login-btn");
    const logoutBtn = document.getElementById("logout-btn");
    
    // Sidebar & Profile
    const userNameEl = document.getElementById("user-name");
    const userRoleEl = document.getElementById("user-role");
    const avatarInitialsEl = document.getElementById("avatar-initials");
    const devicesListEl = document.getElementById("devices-list");
    const refreshDevicesBtn = document.getElementById("refresh-devices");
    const serverStatusDot = document.getElementById("server-status-dot");
    const serverStatusText = document.getElementById("server-status-text");

    // Views
    const welcomeView = document.getElementById("welcome-view");
    const controlView = document.getElementById("control-view");

    // Headings & Active Device details
    const activeDeviceName = document.getElementById("active-device-name");
    const activeDeviceId = document.getElementById("active-device-id");
    const durationTimerEl = document.getElementById("duration-timer");
    const btnTerminateSession = document.getElementById("btn-terminate-session");
    const deviceTypeIcon = document.getElementById("device-type-icon");

    // Telemetry Panel
    const telemetryBattery = document.getElementById("telemetry-battery");
    const batteryProgressBar = document.getElementById("battery-progress");
    const telemetryRam = document.getElementById("telemetry-ram");
    const infoModel = document.getElementById("info-model");
    const infoOs = document.getElementById("info-os");
    const infoZebraStatus = document.getElementById("info-zebra-status");

    // Quick Actions
    const btnQuickScreenshot = document.getElementById("btn-quick-screenshot");
    const btnQuickToast = document.getElementById("btn-quick-toast");
    const btnQuickLocationSilent = document.getElementById("btn-quick-location-silent");
    const btnQuickLock = document.getElementById("btn-quick-lock");
    const btnQuickClearPass = document.getElementById("btn-quick-clear-pass");
    const btnQuickWipe = document.getElementById("btn-quick-wipe");
    const btnStartMirrorSilent = document.getElementById("btn-start-mirror-silent");
    const btnStartMirrorPrompt = document.getElementById("btn-start-mirror-prompt");
    const btnStopMirror = document.getElementById("btn-stop-mirror");

    // Monitoramento de Conectividade
    const btnToggleMonitoring = document.getElementById("btn-toggle-monitoring");

    // Modais
    const qrModal = document.getElementById("qr-modal");
    const barcodeModal = document.getElementById("barcode-modal");
    const btnShowQr = document.getElementById("btn-show-qr");
    const btnShowBarcode = document.getElementById("btn-show-barcode");
    const btnCloseQr = document.getElementById("btn-close-qr");
    const btnCloseBarcode = document.getElementById("btn-close-barcode");
    const qrCodeDetails = document.getElementById("qr-code-details");

    // Tabs
    const tabBtns = document.querySelectorAll(".tab-btn");
    const tabPanels = document.querySelectorAll(".tab-panel");

    // Geolocalização (Location)
    const btnRequestLocation = document.getElementById("btn-request-location");
    const locationHistoryItems = document.getElementById("location-history-items");
    const mapLoadingEl = document.getElementById("map-loading");
    let leafletMap = null;
    let mapMarkers = [];
    let routePolyline = null;

    // App Installer (MDM)
    const uploadApkForm = document.getElementById("upload-apk-form");
    const fileDropArea = document.getElementById("file-drop-area");
    const apkFileInput = document.getElementById("apk-file-input");
    const btnUploadApk = document.getElementById("btn-upload-apk");
    const apkFilesList = document.getElementById("apk-files-list");

    // Logs Panel
    const logsContainer = document.getElementById("logs-container-stream");
    const btnClearLogs = document.getElementById("btn-clear-logs");

    // Simulated OS UI Elements
    const phoneScreenTouch = document.getElementById("phone-screen-touch");
    const realScreenMirrorImg = document.getElementById("real-screen-mirror-img");
    const simMicDot = document.getElementById("sim-mic-dot");
    const simCamDot = document.getElementById("sim-cam-dot");
    const simViewHome = document.getElementById("sim-view-home");
    const simViewSettings = document.getElementById("sim-view-settings");
    const simViewCamera = document.getElementById("sim-view-camera");
    
    // Android Simulation Soft Keys
    const navBtnBack = document.getElementById("nav-btn-back");
    const navBtnHome = document.getElementById("nav-btn-home");
    const navBtnRecents = document.getElementById("nav-btn-recents");
    const physBack = document.getElementById("phys-back");
    const physHome = document.getElementById("phys-home");
    const physRecents = document.getElementById("phys-recents");

    // Direcional D-Pad para Gestos de Arrastar (Swipe)
    const dpadUp = document.getElementById("dpad-up");
    const dpadDown = document.getElementById("dpad-down");
    const dpadLeft = document.getElementById("dpad-left");
    const dpadRight = document.getElementById("dpad-right");

    // --- INICIALIZAÇÃO DE VISIBILIDADE ---
    if (token) {
        showDashboard();
    } else {
        showLogin();
    }

    // --- GERADOR DE QR CODE DINÂMICO (PROVISIONAMENTO DPC) ---
    const qrCodeImage = document.getElementById("qr-code-image");
    const DEFAULT_SERVER_HOST = "10.70.100.251:6061";

    async function updateDpcProvisioningQR() {
        const serverHost = (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1")
            ? DEFAULT_SERVER_HOST
            : `${window.location.hostname}:${window.location.port || 6061}`;
            
        const chkUsePackageHash = document.getElementById("chk-use-package-hash");
        const usePackageHash = chkUsePackageHash ? chkUsePackageHash.checked : false;

        try {
            if (qrCodeDetails) {
                qrCodeDetails.textContent = "Carregando configuração do servidor...";
            }
            
            // Fazer requisição para buscar a configuração atual e o checksum do APK (assinatura ou arquivo)
            const response = await fetch(`/api/dpc-info?host=${serverHost}&use_package_hash=${usePackageHash}`);
            if (!response.ok) {
                throw new Error("Falha ao obter dados de DPC do servidor");
            }
            
            const payload = await response.json();
            const qrJson = payload.provisioning || payload;
            const meta = payload.meta || null;

            if (qrCodeDetails) {
                let details = JSON.stringify(qrJson, null, 2);
                if (meta) {
                    details += "\n\n--- Status ---\n";
                    details += `APK: ${meta.apk_name} (${(meta.apk_size_bytes / 1024 / 1024).toFixed(2)} MB)\n`;
                    details += `Tipo de Checksum: ${meta.checksum_type === 'file_hash' ? 'SHA-256 do Arquivo APK' : 'SHA-256 da Assinatura'}\n`;
                    details += `Checksum: ${meta.checksum_present ? meta.checksum : "AUSENTE - provisionamento vai falhar"}\n`;
                    details += `Pronto: ${meta.ready ? "SIM" : "NAO"}\n`;
                    if (!meta.ready) {
                        details += "\nGere o APK com variant 'provision' no Android Studio e reinicie o servidor.";
                    }
                }
                qrCodeDetails.textContent = details;
            }
            
            const qrData = encodeURIComponent(JSON.stringify(qrJson));
            if (qrCodeImage) {
                qrCodeImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=300x300&margin=20&data=${qrData}`;
            }
            
            logMessage(meta?.ready
                ? `[DPC] QR Code pronto para provisionamento (${meta.checksum_type === 'file_hash' ? 'Hash do Arquivo' : 'Hash da Assinatura'}).`
                : "[DPC] ATENCAO: checksum ausente — gere o APK 'provision' antes de escanear.", meta?.ready ? "info" : "error");
        } catch (e) {
            console.error("Erro ao atualizar QR Code DPC:", e);
            if (qrCodeDetails) {
                qrCodeDetails.textContent = `Erro: ${e.message}\nUsando fallback básico sem checksum.`;
            }
            // Fallback básico
            const qrJsonFallback = {
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.nexis.remoteagent/com.nexis.remoteagent.DeviceAdminReceiver",
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": `http://${serverHost}/apks/app-debug.apk`
            };
            const qrDataFallback = encodeURIComponent(JSON.stringify(qrJsonFallback));
            if (qrCodeImage) {
                qrCodeImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=300x300&margin=20&data=${qrDataFallback}`;
            }
        }
    }

    // Configurar listener para mudança no tipo de checksum
    const chkUsePackageHash = document.getElementById("chk-use-package-hash");
    if (chkUsePackageHash) {
        chkUsePackageHash.addEventListener("change", () => {
            updateDpcProvisioningQR();
        });
    }

    // Inicializar na carga inicial
    updateDpcProvisioningQR();

    // --- SISTEMA DE LOGS ---
    function logMessage(text, type = "system") {
        const timestamp = new Date().toLocaleTimeString();
        const line = document.createElement("div");
        line.className = `log-line ${type}`;
        line.innerHTML = `<span>[${timestamp}]</span> <strong>${text}</strong>`;
        logsContainer.appendChild(line);
        logsContainer.scrollTop = logsContainer.scrollHeight;
    }

    btnClearLogs.addEventListener("click", () => {
        logsContainer.innerHTML = "";
        logMessage("Console de Logs limpo.");
    });

    // --- AUTENTICAÇÃO ---
    loginForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        loginBtn.disabled = true;
        loginBtn.querySelector("span").textContent = "Verificando...";
        loginError.style.display = "none";

        const email = document.getElementById("email").value;
        const password = document.getElementById("password").value;

        try {
            const response = await fetch("/api/login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ email, password })
            });

            if (!response.ok) {
                const errData = await response.json();
                throw new Error(errData.detail || "Falha na autenticação");
            }

            const data = await response.json();
            token = data.access_token;
            localStorage.setItem("nexis_token", token);
            
            logMessage("Autenticação realizada com sucesso.", "system");
            showDashboard();
        } catch (err) {
            errorText.textContent = err.message;
            loginError.style.display = "flex";
            logMessage(`Erro de login: ${err.message}`, "error");
        } finally {
            loginBtn.disabled = false;
            loginBtn.querySelector("span").textContent = "Entrar no Sistema";
        }
    });

    logoutBtn.addEventListener("click", () => {
        localStorage.removeItem("nexis_token");
        token = null;
        if (socket) {
            socket.close();
        }
        stopActiveSession();
        showLogin();
        logMessage("Sessão finalizada pelo usuário.", "system");
    });

    function showLogin() {
        loginContainer.style.display = "flex";
        dashboardContainer.style.display = "none";
    }

    async function showDashboard() {
        loginContainer.style.display = "none";
        dashboardContainer.style.display = "flex";
        
        // Obter Perfil
        try {
            const res = await fetch("/api/me", {
                headers: { "Authorization": `Bearer ${token}` }
            });
            if (res.status === 401) {
                // Token Expirou
                logoutBtn.click();
                return;
            }
            const user = await res.json();
            userNameEl.textContent = user.name;
            avatarInitialsEl.textContent = user.name.split(" ").map(n => n[0]).join("").substring(0, 2).toUpperCase();
            
            // Conectar WebSocket de Sinalização
            connectWebSocket();
            // Buscar APKs hospedados
            loadApkFiles();
        } catch (e) {
            logMessage("Erro ao buscar dados do perfil: " + e.message, "error");
        }
    }

    // --- CONEXÃO WEBSOCKET ---
    function connectWebSocket() {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}/ws/client/${clientId}`;
        
        serverStatusDot.className = "status-indicator offline";
        serverStatusText.textContent = "Conectando ao servidor...";
        
        socket = new WebSocket(wsUrl);

        socket.onopen = () => {
            serverStatusDot.className = "status-indicator online";
            serverStatusText.textContent = "Conectado ao Servidor";
            logMessage("Canal de controle WebSocket estabelecido com o servidor central.", "system");
            fetchDevices();
        };

        socket.onmessage = (event) => {
            const message = JSON.parse(event.data);
            
            if (message.type === "device_list") {
                renderDevicesList(message.devices);
            }
            
            else if (message.type === "screen_frame") {
                console.log("[SCREEN_FRAME] Recebido frame do dispositivo:", message.device_id);
                if (activeDevice && message.device_id === activeDevice.device_id) {
                    if (realScreenMirrorImg.style.display === "none") {
                        console.log("[SCREEN_FRAME] Ativando visualização de tela real e ocultando simulador.");
                        realScreenMirrorImg.style.display = "block";
                        document.getElementById("mock-os-ui").style.display = "none";
                    }
                    realScreenMirrorImg.src = "data:image/jpeg;base64," + message.image;
                } else {
                    console.log("[SCREEN_FRAME] Ignorando frame de dispositivo inativo (ativo:", activeDevice ? activeDevice.device_id : "nenhum", ")");
                }
            }
            
            else if (message.type === "device_log") {
                logMessage(`[CELULAR] ${message.message}`, message.status === "error" ? "error" : "system");
            }
            
            // Sinalização WebRTC repassada do dispositivo
            else if (message.type === "offer" || message.type === "answer" || message.type === "ice_candidate") {
                logMessage(`[WebRTC SI] Sinalização recebida (${message.type}) do dispositivo ${message.device_id}`, "recv");
                // Aqui entraria a API WebRTC real conectando ao vídeo elemento do DOM se houvesse o agente nativo rodando
            }
            
            else if (message.type === "notification") {
                logMessage(`[NOTIFICAÇÃO DO DISPOSITIVO]: ${message.message}`, message.status === "success" ? "recv" : "error");
                alert(message.message);
            }
            
            else if (message.type === "location_update") {
                if (activeDevice && message.device_id === activeDevice.device_id) {
                    handleLiveLocationUpdate(message.data);
                }
            }
        };

        socket.onclose = () => {
            serverStatusDot.className = "status-indicator offline";
            serverStatusText.textContent = "Conexão perdida. Reconectando...";
            logMessage("Conexão WebSocket perdida com o servidor. Reconectando em 5 segundos...", "error");
            setTimeout(connectWebSocket, 5000);
        };
    }

    // Enviar comandos WebSocket
    function sendCommand(type, payload = {}) {
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            logMessage("Erro: WebSocket não está conectado. Comando cancelado.", "error");
            return;
        }
        if (!activeDevice) return;

        const data = {
            target_device: activeDevice.device_id,
            type: type,
            ...payload
        };

        socket.send(JSON.stringify(data));
        logMessage(`[WS ENVIADO] TYPE: ${type} | PAYLOAD: ${JSON.stringify(payload)}`, "sent");
    }

    // --- LISTA DE DISPOSITIVOS ---
    async function fetchDevices() {
        try {
            const response = await fetch("/api/devices", {
                headers: { "Authorization": `Bearer ${token}` }
            });
            const devices = await response.json();
            renderDevicesList(devices);
        } catch (e) {
            logMessage("Erro ao carregar dispositivos: " + e.message, "error");
        }
    }

    refreshDevicesBtn.addEventListener("click", () => {
        fetchDevices();
        logMessage("Lista de dispositivos atualizada manualmente.");
    });

    function renderDevicesList(devices) {
        devicesListEl.innerHTML = "";
        
        if (devices.length === 0) {
            devicesListEl.innerHTML = '<div class="empty-apks">Nenhum celular registrado.</div>';
            return;
        }

        devices.forEach(dev => {
            const devItem = document.createElement("div");
            devItem.className = `device-item ${dev.status === "online" ? "online" : "offline"}`;
            if (activeDevice && activeDevice.device_id === dev.device_id) {
                devItem.classList.add("active");
            }

            devItem.innerHTML = `
                <div class="device-icon-wrap">
                    <i data-lucide="${dev.is_zebra ? 'barcode' : 'smartphone'}"></i>
                    <span class="status-dot"></span>
                </div>
                <div class="device-meta">
                    <h5>${dev.model}</h5>
                    <span>ID: ${dev.device_id} (${dev.status})</span>
                    ${dev.is_zebra ? '<span class="device-badge-zebra">ZEBRA MX</span>' : ''}
                </div>
            `;

            devItem.addEventListener("click", () => {
                startDeviceSession(dev);
            });

            devicesListEl.appendChild(devItem);
        });

        lucide.createIcons();
    }

    // --- SESSÃO DE CONTROLE ATIVA ---
    function startDeviceSession(device) {
        // Se clicar no mesmo dispositivo, não faz nada
        if (activeDevice && activeDevice.device_id === device.device_id) return;

        // Limpar sessões anteriores
        stopActiveSession();

        activeDevice = device;
        logMessage(`Iniciando sessão de suporte remoto assistida com ${device.model} (ID: ${device.device_id})...`, "system");

        // Atualizar lista para marcar como ativo no UI
        const deviceItems = document.querySelectorAll(".device-item");
        fetchDevices(); // Atualiza a lista geral para manter estado atualizado

        // Mostrar tela de controle
        welcomeView.style.display = "none";
        controlView.style.display = "flex";

        // Preencher dados do cabeçalho e telemetria
        activeDeviceName.textContent = device.model;
        activeDeviceId.textContent = `ID: ${device.device_id}`;
        
        telemetryBattery.textContent = `${device.battery_level}%`;
        batteryProgressBar.style.width = `${device.battery_level}%`;
        telemetryRam.textContent = device.ram_usage;
        infoModel.textContent = device.model;
        infoOs.textContent = device.android_version;

        if (device.is_zebra) {
            deviceTypeIcon.setAttribute("data-lucide", "barcode");
            infoZebraStatus.innerHTML = '<i data-lucide="check-circle-2"></i> Habilitado (v11.5)';
            infoZebraStatus.className = "text-success";
            // Ajustar o frame físico do telefone para coletor Zebra se aplicável
            document.getElementById("phone-frame-container").style.borderRadius = "20px";
        } else {
            deviceTypeIcon.setAttribute("data-lucide", "smartphone");
            infoZebraStatus.innerHTML = '<i data-lucide="x-circle"></i> Não disponível';
            infoZebraStatus.className = "text-muted";
            document.getElementById("phone-frame-container").style.borderRadius = "40px";
        }

        // Timer de Sessão
        sessionSeconds = 0;
        durationTimerEl.textContent = "00:00:00";
        sessionTimer = setInterval(() => {
            sessionSeconds++;
            const hrs = String(Math.floor(sessionSeconds / 3600)).padStart(2, "0");
            const mins = String(Math.floor((sessionSeconds % 3600) / 60)).padStart(2, "0");
            const secs = String(sessionSeconds % 60).padStart(2, "0");
            durationTimerEl.textContent = `${hrs}:${mins}:${secs}`;
        }, 1000);

        // Resetar interface do simulador
        resetSimulatorUI();

        // Enviar sinal de conexão pelo WS
        sendCommand("connect_session");
        
        // Inicializar mapa e buscar histórico se a aba ativa for a de localização
        const activeTab = document.querySelector(".tab-btn.active");
        if (activeTab && activeTab.getAttribute("data-tab") === "multimedia") {
            setTimeout(() => {
                initLocationMap();
                fetchLocationHistory(device.device_id);
            }, 300);
        } else {
            // Caso contrário, apenas preparar a lista informando que o dispositivo está pronto
            if (locationHistoryItems) {
                locationHistoryItems.innerHTML = `
                    <div class="empty-history">
                        <i data-lucide="map-pin"></i>
                        <span>Dispositivo conectado. Mude para a aba de Localização para rastrear.</span>
                    </div>
                `;
                lucide.createIcons();
            }
        }

        
        lucide.createIcons();
    }

    function stopActiveSession() {
        if (sessionTimer) {
            clearInterval(sessionTimer);
            sessionTimer = null;
        }
        activeDevice = null;
        
        if (realScreenMirrorImg) {
            realScreenMirrorImg.style.display = "none";
            realScreenMirrorImg.src = "";
        }
        document.getElementById("mock-os-ui").style.display = "flex";

        // Limpar visualização do mapa e histórico
        clearMapAndHistory();

        welcomeView.style.display = "flex";
        controlView.style.display = "none";
    }

    btnTerminateSession.addEventListener("click", () => {
        if (confirm("Deseja realmente encerrar a sessão de suporte atual?")) {
            sendCommand("disconnect_session");
            logMessage("Sessão de suporte encerrada pelo analista.");
            stopActiveSession();
            fetchDevices();
        }
    });

    // --- SIMULADOR ANDROID INTEGRADADO (FRONT-END DEMO) ---
    function resetSimulatorUI() {
        simViewHome.style.display = "flex";
        simViewSettings.style.display = "none";
        simViewCamera.style.display = "none";
        simCamDot.style.display = "none";
        simMicDot.style.display = "none";
        if (realScreenMirrorImg) {
            realScreenMirrorImg.style.display = "none";
            realScreenMirrorImg.src = "";
        }
        document.getElementById("mock-os-ui").style.display = "flex";
    }

    // Cliques na tela do celular simulado
    phoneScreenTouch.addEventListener("click", (e) => {
        const rect = phoneScreenTouch.getBoundingClientRect();
        const x = (e.clientX - rect.left) / rect.width;
        const y = (e.clientY - rect.top) / rect.height;

        const isRealMirroringActive = realScreenMirrorImg && realScreenMirrorImg.style.display === "block";

        if (isRealMirroringActive) {
            // Envia o clique via WebSocket (Acessibilidade) apenas se o espelhamento real estiver ativo
            sendCommand("click", { x: parseFloat(x.toFixed(3)), y: parseFloat(y.toFixed(3)) });
        } else {
            // Simulação Visual Local de Cliques no OS Mock (Apenas se não for tela real)
            if (simViewHome.style.display === "flex") {
                // Coordenadas aproximadas dos botões de aplicativos no home screen
                // Configurações: X entre 10% e 35%, Y entre 20% e 40%
                if (x >= 0.1 && x <= 0.35 && y >= 0.2 && y <= 0.4) {
                    openSimApp("settings");
                }
                // Câmera: X entre 38% e 62%, Y entre 20% e 40%
                else if (x >= 0.38 && x <= 0.62 && y >= 0.2 && y <= 0.4) {
                    openSimApp("camera");
                }
                // Navegador: X entre 65% e 90%, Y entre 20% e 40%
                else if (x >= 0.65 && x <= 0.90 && y >= 0.2 && y <= 0.4) {
                    alert("Navegador simulado aberto. Acessando portal corporativo.");
                }
            }
        }
    });

    function openSimApp(appName) {
        simViewHome.style.display = "none";
        if (appName === "settings") {
            simViewSettings.style.display = "flex";
            logMessage("[SIMULADOR] App 'Configurações' aberto.");
        } else if (appName === "camera") {
            simViewCamera.style.display = "flex";
            logMessage("[SIMULADOR] App 'Câmera' aberto.");
        }
    }

    // Botões de Voltar dos Apps Simulado
    document.querySelectorAll(".btn-app-back").forEach(btn => {
        btn.addEventListener("click", (e) => {
            e.stopPropagation(); // Evita disparar clique na tela
            resetSimulatorUI();
        });
    });

    // Barra de Navegação Física e Soft Keys
    function handleAndroidKey(key) {
        const isRealMirroringActive = realScreenMirrorImg && realScreenMirrorImg.style.display === "block";
        if (isRealMirroringActive) {
            sendCommand("key", { code: key });
        } else {
            if (key === "home") {
                resetSimulatorUI();
                logMessage("[SIMULADOR] Evento: HOME KEY pressionado.");
            } else if (key === "back") {
                if (simViewSettings.style.display === "flex" || simViewCamera.style.display === "flex") {
                    resetSimulatorUI();
                }
                logMessage("[SIMULADOR] Evento: BACK KEY pressionado.");
            } else if (key === "recents") {
                logMessage("[SIMULADOR] Evento: RECENTS KEY pressionado.");
                alert("Gerenciador de Aplicativos Recentes do Android (Simulação)");
            }
        }
    }

    navBtnBack.addEventListener("click", (e) => { e.stopPropagation(); handleAndroidKey("back"); });
    navBtnHome.addEventListener("click", (e) => { e.stopPropagation(); handleAndroidKey("home"); });
    navBtnRecents.addEventListener("click", (e) => { e.stopPropagation(); handleAndroidKey("recents"); });
    
    physBack.addEventListener("click", () => handleAndroidKey("back"));
    physHome.addEventListener("click", () => handleAndroidKey("home"));
    physRecents.addEventListener("click", () => handleAndroidKey("recents"));

    // --- CONTROLES DE DESLIZAR (SWIPE - D-PAD) ---
    function handleDpadClick(direction) {
        const isRealMirroringActive = realScreenMirrorImg && realScreenMirrorImg.style.display === "block";
        
        let x1, y1, x2, y2;
        let directionLabel = "";
        
        switch (direction) {
            case "up":
                // Arrastar para Cima (Scroll Down) -> O dedo arrasta de baixo para cima
                x1 = 0.5; y1 = 0.8;
                x2 = 0.5; y2 = 0.2;
                directionLabel = "Arrastar para Cima (Scroll Down)";
                break;
            case "down":
                // Arrastar para Baixo (Scroll Up) -> O dedo arrasta de cima para baixo
                x1 = 0.5; y1 = 0.2;
                x2 = 0.5; y2 = 0.8;
                directionLabel = "Arrastar para Baixo (Scroll Up)";
                break;
            case "left":
                // Arrastar para a Esquerda -> O dedo arrasta da direita para a esquerda
                x1 = 0.8; y1 = 0.5;
                x2 = 0.2; y2 = 0.5;
                directionLabel = "Arrastar para a Esquerda";
                break;
            case "right":
                // Arrastar para a Direita -> O dedo arrasta da esquerda para a direita
                x1 = 0.2; y1 = 0.5;
                x2 = 0.8; y2 = 0.5;
                directionLabel = "Arrastar para a Direita";
                break;
        }

        if (isRealMirroringActive) {
            sendCommand("swipe", { x1: x1, y1: y1, x2: x2, y2: y2, duration: 400 });
        } else {
            logMessage(`[SIMULADOR] Evento: Swipe - ${directionLabel}`);
            
            // Simulação de scroll visual local nas configurações
            if (simViewSettings.style.display === "flex") {
                const body = simViewSettings.querySelector(".app-window-body");
                if (body) {
                    if (direction === "up") {
                        body.scrollBy({ top: 120, behavior: 'smooth' });
                    } else if (direction === "down") {
                        body.scrollBy({ top: -120, behavior: 'smooth' });
                    }
                }
            }
        }
    }

    if (dpadUp) dpadUp.addEventListener("click", () => handleDpadClick("up"));
    if (dpadDown) dpadDown.addEventListener("click", () => handleDpadClick("down"));
    if (dpadLeft) dpadLeft.addEventListener("click", () => handleDpadClick("left"));
    if (dpadRight) dpadRight.addEventListener("click", () => handleDpadClick("right"));

    // --- ABA TELEMETRIA / AÇÕES RÁPIDAS ---
    btnQuickScreenshot.addEventListener("click", () => {
        sendCommand("screenshot");
        logMessage("Solicitação de Screenshot enviada para o dispositivo.");
        alert("Screenshot tirado! O arquivo foi armazenado na pasta de auditoria do suporte.");
    });

    btnQuickToast.addEventListener("click", () => {
        const text = prompt("Digite a mensagem a ser exibida na tela do celular:", "Suporte Nexis está ativo.");
        if (text) {
            sendCommand("toast", { message: text });
            logMessage(`Comando de Toast enviado: "${text}"`);
        }
    });

    if (btnQuickLocationSilent) {
        btnQuickLocationSilent.addEventListener("click", () => {
            logMessage("[MAPA] Solicitando localização silenciosa em segundo plano...", "sent");
            sendCommand("request_location");
        });
    }

    btnQuickLock.addEventListener("click", () => {
        if (confirm("Bloquear a tela do dispositivo corporativo imediatamente?")) {
            sendCommand("lock_device");
            logMessage("Enviado comando de bloqueio de tela administrativo.");
        }
    });

    btnQuickClearPass.addEventListener("click", () => {
        if (confirm("Remover a senha de bloqueio de tela do dispositivo corporativo imediatamente?")) {
            sendCommand("clear_password");
            logMessage("Enviado comando para limpar senha de tela.");
        }
    });

    btnQuickWipe.addEventListener("click", () => {
        if (confirm("ATENÇÃO: Isso irá apagar todos os dados e resetar o celular de fábrica imediatamente. Deseja continuar?")) {
            if (confirm("Por favor, confirme mais uma vez: deseja resetar de fábrica o dispositivo?")) {
                sendCommand("wipe_device");
                logMessage("Enviado comando de Reset de Fábrica (Wipe) administrativo.", "error");
            }
        }
    });

    btnStartMirrorSilent.addEventListener("click", () => {
        sendCommand("start_mirroring", { silent: true });
        logMessage("Enviado comando para iniciar espelhamento de tela silencioso.");
    });

    // ---------------------------------------------------------------
    // CELULAR EM MONITORAMENTO
    // ---------------------------------------------------------------
    let isMonitoringActive = false;

    function updateMonitoringUI(active) {
        const btnIcon = document.getElementById("btn-monitoring-icon");
        const btnLabel = document.getElementById("btn-monitoring-label");
        const rowEl = document.querySelector(".monitoring-row");
        const iconWrap = document.getElementById("monitoring-icon-wrap");
        const sideIcon = document.getElementById("monitoring-icon");

        if (active) {
            btnToggleMonitoring.classList.add("monitoring-active");
            btnLabel.textContent = "Monitorando";
            rowEl?.classList.add("is-active");
            iconWrap?.classList.add("active");
            // Troca ícone: shield-off → shield-check
            if (btnIcon) { btnIcon.setAttribute("data-lucide", "shield-check"); }
            if (sideIcon) { sideIcon.setAttribute("data-lucide", "shield-check"); }
        } else {
            btnToggleMonitoring.classList.remove("monitoring-active");
            btnLabel.textContent = "Sem monitoramento";
            rowEl?.classList.remove("is-active");
            iconWrap?.classList.remove("active");
            if (btnIcon) { btnIcon.setAttribute("data-lucide", "shield-off"); }
            if (sideIcon) { sideIcon.setAttribute("data-lucide", "shield-off"); }
        }
        lucide.createIcons();
    }

    if (btnToggleMonitoring) {
        btnToggleMonitoring.addEventListener("click", () => {
            isMonitoringActive = !isMonitoringActive;
            const cmd = isMonitoringActive ? "start_monitoring" : "stop_monitoring";
            sendCommand(cmd);
            updateMonitoringUI(isMonitoringActive);
            if (isMonitoringActive) {
                logMessage("[MONITOR] Modo de monitoramento de conectividade ATIVADO no dispositivo. GPS, Wi-Fi e Dados serão reativados automaticamente se desligados.", "sent");
            } else {
                logMessage("[MONITOR] Modo de monitoramento de conectividade DESATIVADO.", "system");
            }
        });
    }

    btnStartMirrorPrompt.addEventListener("click", () => {
        sendCommand("start_mirroring", { silent: false });
        logMessage("Enviado comando para iniciar espelhamento de tela com confirmação.");
    });

    btnStopMirror.addEventListener("click", () => {
        sendCommand("stop_mirroring");
        logMessage("Enviado comando para parar espelhamento de tela.");
        if (realScreenMirrorImg) {
            realScreenMirrorImg.style.display = "none";
            realScreenMirrorImg.src = "";
        }
        document.getElementById("mock-os-ui").style.display = "flex";
    });

    // --- SISTEMA DE LOCALIZAÇÃO GEOGRÁFICA (MAPS) ---
    function initLocationMap() {
        if (leafletMap) {
            // Se o mapa já existir, apenas redimensionamos para garantir a renderização correta
            setTimeout(() => {
                leafletMap.invalidateSize();
            }, 100);
            return;
        }

        // Ocultar overlay de carregamento
        if (mapLoadingEl) {
            mapLoadingEl.style.display = "none";
        }

        // Criar o mapa Leaflet centrado no Brasil por padrão
        leafletMap = L.map("location-map").setView([-15.7801, -47.9292], 4);

        // Definir camadas de mapa base
        const osm = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        });

        const dark = L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
            maxZoom: 20,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>'
        });

        const satellite = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", {
            maxZoom: 19,
            attribution: 'Tiles &copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EGP, and the GIS User Community'
        });

        // Adicionar a camada escura premium como padrão
        dark.addTo(leafletMap);

        // Adicionar controle de camadas no canto superior direito
        const baseMaps = {
            "Escuro (CartoDB)": dark,
            "Padrão (OSM)": osm,
            "Satélite (Esri)": satellite
        };
        L.control.layers(baseMaps).addTo(leafletMap);
    }

    function clearMapAndHistory() {
        // Remover marcadores
        mapMarkers.forEach(marker => {
            if (leafletMap) {
                leafletMap.removeLayer(marker);
            }
        });
        mapMarkers = [];

        // Remover polilinha
        if (routePolyline && leafletMap) {
            leafletMap.removeLayer(routePolyline);
        }
        routePolyline = null;

        // Resetar lista de histórico na UI
        if (locationHistoryItems) {
            locationHistoryItems.innerHTML = `
                <div class="empty-history">
                    <i data-lucide="history"></i>
                    <span>Aguardando conexão com o dispositivo...</span>
                </div>
            `;
            lucide.createIcons();
        }

        // Mostrar overlay de carregamento se o mapa for reinicializado
        if (mapLoadingEl) {
            mapLoadingEl.style.display = "flex";
        }
    }

    async function fetchLocationHistory(deviceId) {
        try {
            if (locationHistoryItems) {
                locationHistoryItems.innerHTML = `
                    <div class="empty-history">
                        <div class="spinner"></div>
                        <span>Carregando histórico de localização...</span>
                    </div>
                `;
                lucide.createIcons();
            }
            logMessage(`[MAPA] Carregando histórico de localização do dispositivo ${deviceId}...`, "system");
            const response = await fetch(`/api/device/${deviceId}/location-history`, {
                headers: { "Authorization": `Bearer ${token}` }
            });
            if (!response.ok) {
                const errText = await response.text();
                throw new Error(`Erro HTTP ${response.status}: ${errText || response.statusText}`);
            }
            const records = await response.json();
            renderLocationHistory(records);
        } catch (e) {
            logMessage("Erro ao buscar histórico de localização: " + e.message, "error");
            if (locationHistoryItems) {
                locationHistoryItems.innerHTML = `
                    <div class="empty-history">
                        <i data-lucide="alert-triangle"></i>
                        <span>Erro ao carregar histórico. Tente novamente.</span>
                    </div>
                `;
                lucide.createIcons();
            }
        }
    }

    function renderLocationHistory(records) {
        if (!leafletMap) return;
        
        // Limpar antes de renderizar
        mapMarkers.forEach(marker => leafletMap.removeLayer(marker));
        mapMarkers = [];
        if (routePolyline) leafletMap.removeLayer(routePolyline);
        routePolyline = null;

        if (mapLoadingEl) {
            mapLoadingEl.style.display = "none";
        }

        if (!locationHistoryItems) return;
        locationHistoryItems.innerHTML = "";

        if (!records || records.length === 0) {
            locationHistoryItems.innerHTML = `
                <div class="empty-history">
                    <i data-lucide="map-pin"></i>
                    <span>Nenhum registro de localização nos últimos 5 dias.</span>
                </div>
            `;
            lucide.createIcons();
            leafletMap.setView([-15.7801, -47.9292], 4);
            return;
        }

        const latlngs = [];
        
        // Ordenar do mais novo para o mais antigo para a lista lateral
        const sortedRecordsForList = [...records].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
        // Ordenar do mais antigo para o mais novo para desenhar a rota polilinha
        const sortedRecordsForRoute = [...records].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));

        // Adicionar marcadores e coletar coordenadas para a rota
        sortedRecordsForRoute.forEach((rec, idx) => {
            const lat = rec.latitude;
            const lng = rec.longitude;
            latlngs.push([lat, lng]);

            const dateStr = new Date(rec.timestamp).toLocaleString("pt-BR");
            
            // Criar ícone customizado com número da ordem
            const numberIcon = L.divIcon({
                className: 'custom-div-icon',
                html: `<div class='history-marker-badge'>${idx + 1}</div>`,
                iconSize: [24, 24],
                iconAnchor: [12, 12]
            });

            const marker = L.marker([lat, lng], { icon: numberIcon }).addTo(leafletMap);
            marker.bindPopup(`
                <div class="custom-popup-box">
                    <h4>Ponto #${idx + 1}</h4>
                    <p><strong>Data:</strong> ${dateStr}</p>
                    <p><strong>Provedor:</strong> <span class="provider-badge">${rec.provider || "N/A"}</span></p>
                    <p><strong>Precisão:</strong> ${rec.accuracy ? rec.accuracy.toFixed(1) + "m" : "N/A"}</p>
                    <p><strong>Coords:</strong> ${lat.toFixed(6)}, ${lng.toFixed(6)}</p>
                </div>
            `);
            mapMarkers.push(marker);
        });

        // Desenhar polilinha de rota ligando os pontos
        if (latlngs.length > 1) {
            routePolyline = L.polyline(latlngs, {
                color: "#6366f1",
                weight: 4,
                opacity: 0.8,
                dashArray: "5, 10"
            }).addTo(leafletMap);
        }

        // Preencher painel de histórico lateral
        sortedRecordsForList.forEach((rec) => {
            // Achar o índice correspondente no array ordenado cronologicamente (1-based)
            const chronologicalIndex = sortedRecordsForRoute.findIndex(r => r.timestamp === rec.timestamp) + 1;
            const dateStr = new Date(rec.timestamp).toLocaleTimeString("pt-BR") + " - " + new Date(rec.timestamp).toLocaleDateString("pt-BR", {day: 'numeric', month: 'short'});
            
            const item = document.createElement("div");
            item.className = "history-item";
            item.innerHTML = `
                <div class="history-marker-badge">${chronologicalIndex}</div>
                <div class="history-info">
                    <span class="history-time">${dateStr}</span>
                    <span class="history-coords">${rec.latitude.toFixed(6)}, ${rec.longitude.toFixed(6)}</span>
                    <div class="history-meta">
                        <span>${rec.provider || "N/A"}</span>
                        <span>•</span>
                        <span>±${rec.accuracy ? rec.accuracy.toFixed(0) + "m" : "N/A"}</span>
                    </div>
                </div>
            `;

            // Clique na linha do histórico foca o marcador no mapa
            item.addEventListener("click", () => {
                const marker = mapMarkers[chronologicalIndex - 1];
                if (marker && leafletMap) {
                    leafletMap.setView([rec.latitude, rec.longitude], 16);
                    marker.openPopup();
                }
                
                // Destacar item selecionado
                document.querySelectorAll(".history-item").forEach(el => el.classList.remove("active"));
                item.classList.add("active");
            });

            locationHistoryItems.appendChild(item);
        });

        // Focar e centralizar no último ponto de localização registrado
        const lastRec = sortedRecordsForRoute[sortedRecordsForRoute.length - 1];
        if (leafletMap && lastRec) {
            leafletMap.setView([lastRec.latitude, lastRec.longitude], 15);
            // Abrir popup do último ponto
            mapMarkers[mapMarkers.length - 1].openPopup();
        }
    }

    function handleLiveLocationUpdate(data) {
        logMessage(`[MAPA] Recebida atualização de localização em tempo real: ${data.latitude.toFixed(6)}, ${data.longitude.toFixed(6)} (Precisão: ${data.accuracy ? data.accuracy.toFixed(1) + "m" : "N/A"})`, "recv");
        
        // Adicionar novo ponto ao mapa e recarregar histórico
        if (activeDevice) {
            fetchLocationHistory(activeDevice.device_id);
        }
    }

    // Vincular clique no botão "Localizar Agora"
    if (btnRequestLocation) {
        btnRequestLocation.addEventListener("click", () => {
            logMessage("[MAPA] Solicitando localização em tempo real para o dispositivo...", "sent");
            if (locationHistoryItems && (!mapMarkers || mapMarkers.length === 0)) {
                locationHistoryItems.innerHTML = `
                    <div class="empty-history">
                        <div class="spinner"></div>
                        <span>Solicitando localização em tempo real ao GPS...</span>
                    </div>
                `;
                lucide.createIcons();
            }
            sendCommand("request_location");
        });
    }

    // Vincular alteração no seletor de intervalo de GPS
    const selectGpsInterval = document.getElementById("select-gps-interval");
    if (selectGpsInterval) {
        selectGpsInterval.addEventListener("change", (e) => {
            const val = e.target.value;
            const parts = val.split("-");
            if (parts.length === 2) {
                const interval = parseInt(parts[0]);
                const distance = parseFloat(parts[1]);
                logMessage(`[MAPA] Alterando intervalo de GPS remoto: ${interval / 1000}s / ${distance}m...`, "sent");
                sendCommand("set_location_interval", {
                    interval: interval,
                    distance: distance
                });
            }
        });
    }

    // --- ABA INSTALADOR: MDM SILENT INSTALL ---
    async function loadApkFiles() {
        try {
            const response = await fetch("/api/apks", {
                headers: { "Authorization": `Bearer ${token}` }
            });
            const apks = await response.json();
            renderApksList(apks);
        } catch (e) {
            logMessage("Erro ao listar APKs: " + e.message, "error");
        }
    }

    function renderApksList(apks) {
        apkFilesList.innerHTML = "";
        if (apks.length === 0) {
            apkFilesList.innerHTML = '<div class="empty-apks">Nenhum APK hospedado no servidor.</div>';
            return;
        }

        apks.forEach(apk => {
            const item = document.createElement("div");
            item.className = "apk-item";
            item.innerHTML = `
                <div class="apk-info">
                    <strong>${apk.name}</strong>
                    <span>Tamanho: ${apk.size}</span>
                </div>
                <button class="btn-primary btn-sm btn-install-apk" data-name="${apk.name}">
                    <i data-lucide="download-cloud"></i> Instalar Silencioso
                </button>
            `;

            item.querySelector(".btn-install-apk").addEventListener("click", () => {
                if (confirm(`Deseja enviar comando para instalar silenciosamente o APK "${apk.name}" no dispositivo?`)) {
                    sendCommand("install_app", { apk_name: apk.name });
                    logMessage(`[MDM] Disparada instalação silenciosa de "${apk.name}". Aguardando broadcast de status...`, "system");
                }
            });

            apkFilesList.appendChild(item);
        });

        lucide.createIcons();
    }

    // Upload de APK
    fileDropArea.addEventListener("click", () => {
        apkFileInput.click();
    });

    fileDropArea.addEventListener("dragover", (e) => {
        e.preventDefault();
        fileDropArea.classList.add("dragover");
    });

    fileDropArea.addEventListener("dragleave", () => {
        fileDropArea.classList.remove("dragover");
    });

    fileDropArea.addEventListener("drop", (e) => {
        e.preventDefault();
        fileDropArea.classList.remove("dragover");
        if (e.dataTransfer.files.length > 0) {
            handleApkFileSelection(e.dataTransfer.files[0]);
        }
    });

    apkFileInput.addEventListener("change", () => {
        if (apkFileInput.files.length > 0) {
            handleApkFileSelection(apkFileInput.files[0]);
        }
    });

    function handleApkFileSelection(file) {
        if (!file.name.endsWith('.apk')) {
            alert("Apenas arquivos no formato .apk são aceitos.");
            return;
        }
        fileDropArea.querySelector(".file-msg").textContent = `Selecionado: ${file.name} (${(file.size / (1024*1024)).toFixed(2)} MB)`;
        btnUploadApk.disabled = false;
    }

    uploadApkForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const file = apkFileInput.files[0] || fileDropArea.files;
        if (!file) return;

        btnUploadApk.disabled = true;
        btnUploadApk.innerHTML = '<div class="spinner"></div> Enviando...';

        const formData = new FormData();
        formData.append("file", file);
        formData.append("description", `Upload via Web Console em ${new Date().toLocaleDateString()}`);

        try {
            const response = await fetch("/api/apks/upload", {
                method: "POST",
                headers: { "Authorization": `Bearer ${token}` },
                body: formData
            });

            if (!response.ok) throw new Error("Erro no upload do APK");

            logMessage(`[MDM] APK "${file.name}" enviado com sucesso para o repositório corporativo.`, "system");
            uploadApkForm.reset();
            fileDropArea.querySelector(".file-msg").textContent = "Arraste o arquivo APK ou clique para selecionar";
            loadApkFiles();
        } catch (err) {
            logMessage(`Falha no upload do APK: ${err.message}`, "error");
        } finally {
            btnUploadApk.innerHTML = '<i data-lucide="upload"></i> Upload de APK';
        }
    });

    // --- SELETOR DE ABAS ---
    tabBtns.forEach(btn => {
        btn.addEventListener("click", () => {
            tabBtns.forEach(b => b.classList.remove("active"));
            tabPanels.forEach(p => p.classList.remove("active"));

            btn.classList.add("active");
            const tabId = btn.getAttribute("data-tab");
            document.getElementById(`tab-${tabId}`).classList.add("active");

            // Se mudou para a aba de localização, inicializa e corrige tamanho do mapa Leaflet
            if (tabId === "multimedia") {
                initLocationMap();
                if (leafletMap) {
                    setTimeout(() => {
                        leafletMap.invalidateSize();
                    }, 150);
                }
                if (activeDevice) {
                    fetchLocationHistory(activeDevice.device_id);
                }
            }
        });
    });

    // --- CONTROLES DOS MODAIS ---
    btnShowQr.addEventListener("click", () => {
        qrModal.style.display = "flex";
        updateDpcProvisioningQR();
    });
    btnCloseQr.addEventListener("click", () => qrModal.style.display = "none");
    btnShowBarcode.addEventListener("click", () => barcodeModal.style.display = "flex");
    btnCloseBarcode.addEventListener("click", () => barcodeModal.style.display = "none");

    // Fechar ao clicar fora do card
    window.addEventListener("click", (e) => {
        if (e.target === qrModal) qrModal.style.display = "none";
        if (e.target === barcodeModal) barcodeModal.style.display = "none";
    });
});
