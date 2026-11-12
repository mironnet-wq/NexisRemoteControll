package com.nexis.remoteagent

import android.Manifest
 import android.app.Notification
 import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import android.view.Surface
import android.net.Network
import okhttp3.*
import okio.ByteString.Companion.toByteString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RemoteControlService : Service() {

    companion object {
        private const val TAG = "NexisControlService"
        private const val NOTIFICATION_ID = 8821
        private const val CHANNEL_ID = "NexisControlChannel"
        
        // O coletor fala com o Gestor pela borda 5087. O caminho /nexis chega no serviço local.
        private val SERVER_URLS = listOf(
            "ws://10.70.2.5:5087/nexis"
        )

        const val ACTION_CAPTURE_READY = "com.nexis.remoteagent.CAPTURE_READY"

        @Volatile
        var instance: RemoteControlService? = null
            private set
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var cpuLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var inventoryScreenLock: android.os.PowerManager.WakeLock? = null
    private val inventoryHandler = Handler(Looper.getMainLooper())
    private val inventoryTick = object : Runnable {
        override fun run() {
            if (!KioskPolicy.inventory(this@RemoteControlService)) return
            KioskPolicy.applyInventory(this@RemoteControlService)
            inventoryHandler.postDelayed(this, 60_000)
        }
    }
    private var screenReceiver: BroadcastReceiver? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var deviceId: String = ""
    private var reconnectDelayMs = 5000L
    private var currentUrlIndex = 0
    private var telemetryHandler: Handler? = null
    private var telemetryRunnable: Runnable? = null
    private lateinit var packageInstallHelper: PackageInstallHelper
    private var isMirroringActive = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // --- MODO DE MONITORAMENTO DE CONECTIVIDADE ---
    // Quando ativo, o agente reativa GPS, Wi-Fi e Dados Moveis automaticamente
    // se forem desligados, mesmo sem conexao ativa com o servidor.
    private var isMonitoringActive = false
    private var connectivityReceiver: BroadcastReceiver? = null
    private var monitoringWatchdogTimer: Timer? = null
    // Tempo da ultima mensagem recebida do servidor (para detectar desconexao)
    private val lastServerMessageMs = AtomicLong(System.currentTimeMillis())
    // Timeout: se nao houver mensagem em 30s e monitoramento ativo, tentar restaurar
    private val WATCHDOG_INTERVAL_MS = 10_000L
    private val RECONNECT_RESTORE_TIMEOUT_MS = 30_000L

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var isContinuousLocationActive = false
    private var mapLocationActive = false
    private var oemWifiMac = ""
    private var macNoted = false
    private var macMisses = 0
    private var factoryMacBlocked = false
    private var zebraMacGrantStarted = false
    private var adminDeadlineMs = 0L
    private val adminHandler = Handler(Looper.getMainLooper())
    private val adminReturn = Runnable {
        if (KioskPolicy.mode(this) != "admin") return@Runnable
        adminDeadlineMs = 0L
        sendDeviceLog("info", "Modo admin encerrou. Voltando ao quiosque.")
        KioskPolicy.setLocal(this, "user", KioskPolicy.packages(this))
    }
    private var lastLocationSentMs = 0L
    private var lastMobileDataRestoreMs = 0L
    private var lastGpsRestoreLogMs = 0L
    private var locationMinIntervalMs = 300_000L // 5 min entre envios automaticos
    private var sawFreshLocation = false
    private var lastWifiLocateMs = 0L
    private var wifiLocateReceiver: BroadcastReceiver? = null
    private var locationMinDistanceM = 50f       // 50m minimo
    private val LOCATION_ON_DEMAND_MIN_MS = 30_000L  // 30s entre pedidos sob demanda
    private val MOBILE_DATA_RESTORE_COOLDOWN_MS = 60_000L

    // Variáveis de Captura de Tela (Espelhamento)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenReader: ImageReader? = null
    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var screenThread: android.os.HandlerThread? = null
    private var screenHandler: Handler? = null
    private var lastFrameTime = 0L
    private var lastFrameHash = 0
    private var pendingResultCode: Int = -1
    private var pendingProjectionData: Intent? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var h264Frames = 0
    private var displayRetries = 0
    private var mirrorGeneration = 0
    private var ignoreProjectionStopUntil = 0L
    private var mirrorNeedsSettle = false
    private var lastCodecConfig: ByteArray? = null
    private var stoppingCapture = false
    private var diagnosticLog: String = "Nenhum log de inicialização"
    private var lastPolicySummary: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        packageInstallHelper = PackageInstallHelper(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
        // Libera a leitura da série ANTES de montar o ID. Antes vinha depois: na
        // primeira inicialização a série falhava, o ID caía no ANDROID_ID e mudava
        // no reinício seguinte.
        grantLocationIfOwner()

        // ID dinamico baseado no fabricante + modelo do celular
        // Formato: motorola_motoe20_A1B2C3 (seguro em todas as versoes Android)
        val manufacturer = Build.MANUFACTURER.lowercase().replace(" ", "_").take(12)
        val model = Build.MODEL.lowercase().replace(" ", "").replace("-", "").take(12)
        // A série INTEIRA identifica o aparelho. Antes eram só os 6 primeiros dígitos, que
        // nos Zebra são o lote de fabricação: dois MC33 do mesmo lote ficavam com o mesmo
        // ID, brigavam pela conexão e um sobrescrevia o cadastro do outro no Gestor.
        // Sem série legível, usa o ANDROID_ID completo (16 hex, único por aparelho).
        val serial = try {
            val full = deviceSerial().lowercase().filter { it.isLetterOrDigit() }
            if (full.length >= 6) full
            else android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                ?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty().ifEmpty { "000000" }
        } catch (e: Exception) { "000000" }
        deviceId = "${manufacturer}_${model}_${serial.take(40)}"
        
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        setupResetPasswordToken()
        NexisTyper.recover(this)
        grantLocationIfOwner()
        applyEnterprisePoliciesOnBoot()
        connectToServer()
        startTelemetryLoop()
        watchScreen()
        listenForResetScan()
        configureResetScan()
        scheduleNextReconnectAlarm(60_000L)
        initLocationManager()
        Handler(Looper.getMainLooper()).postDelayed({ requestZebraWifiMac() }, 3_000)
        Handler(Looper.getMainLooper()).postDelayed({ startMapLocation() }, 15_000)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!mapLocationActive) startMapLocation()
        }, 60_000)

        // Restaurar monitoramento de conectividade se estivesse ativo
        val prefs = getSharedPreferences("nexis_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("monitoring_active", false)) {
            Log.i(TAG, "Restaurando watchdog de conectividade persistente...")
            startConnectivityMonitoring()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.action == "ensure_link") {
                // A tela do app abre a cada pedido de captura. Derrubar um link que está
                // funcionando fazia o servidor esquecer quem assistia: o primeiro
                // espelhamento ficava sem imagem (os toques ainda funcionavam).
                if (webSocket != null) {
                    Log.i(TAG, "Aplicativo aberto. Link já ativo, mantido.")
                    sendTelemetry()
                    return START_STICKY
                }
                Log.i(TAG, "Aplicativo aberto. Sem link: conectando.")
                reconnectDelayMs = 1000L
                connectToServer()
                return START_STICKY
            }
            if (intent.action == "reconnect_alarm") {
                Log.i(TAG, "Alarme de reconexão recebido.")
                if (webSocket == null) {
                    Log.i(TAG, "WebSocket desconectado. Iniciando tentativa de conexão via alarme...")
                    connectToServer()
                } else {
                    sendTelemetry()
                }
                scheduleNextReconnectAlarm(60_000L)
                return START_STICKY
            }
            
            val hasData = intent.hasExtra("projection_data")
            val resultCode = intent.getIntExtra("projection_result_code", -999)
            diagnosticLog = "onStartCommand: hasData = $hasData, code = $resultCode"
            
            val projectionData = intent.getParcelableExtra<android.os.Parcelable>("projection_data") as? Intent
            if (projectionData != null && resultCode == android.app.Activity.RESULT_OK) {
                pendingResultCode = resultCode
                pendingProjectionData = projectionData
                if (webSocket != null) {
                    startScreenCapture(resultCode, projectionData)
                }
            }
        } else {
            diagnosticLog = "onStartCommand: intent é nulo"
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        scanReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiLocateReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiLocateReceiver = null
        releaseLinkAwake()
        releaseInventoryScreen()
        unregisterNetworkCallback()
        stopConnectivityMonitoring()
        stopTelemetryLoop()
        val projection = mediaProjection
        mediaProjection = null
        projectionCallback = null
        stopScreenCapture(release = true)
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        stopContinuousLocationTracking()
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (_: Exception) {
        }
        mapLocationActive = false
        adminHandler.removeCallbacks(adminReturn)
        webSocket?.close(1000, "Serviço destruído")
        webSocket = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nexis Remote Support",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação ativa para suporte corporativo em background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexis Remote Support Ativo")
            .setContentText("O analista de TI está conectado a este dispositivo.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Abre o link com o Nexis. Fecha o anterior antes e ignora avisos de links antigos:
     * o servidor fecha a conexão velha (4000) quando chega a nova, e esse aviso
     * apagava a referência da conexão nova — o coletor reconectava a cada 10–30 s.
     */
    private fun connectToServer() {
        val http = client ?: OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
            .also { client = it }

        webSocket?.let { old ->
            webSocket = null
            runCatching { old.cancel() }
        }

        val baseUrl = SERVER_URLS[currentUrlIndex]
        Log.i(TAG, "Tentando conectar ao servidor WebSocket: $baseUrl")

        val request = Request.Builder()
            .url("$baseUrl/ws/device/${Uri.encode(deviceId)}")
            .build()

        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            /** Aviso de uma conexão que já foi trocada por outra: não mexe em nada. */
            private fun stale(ws: WebSocket) = this@RemoteControlService.webSocket !== ws

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stale(webSocket)) {
                    runCatching { webSocket.cancel() }
                    return
                }
                Log.i(TAG, "Conectado ao servidor de suporte Nexis via $baseUrl.")
                this@RemoteControlService.webSocket = webSocket
                reconnectDelayMs = 5000L // Resetar delay após conectar
                sendTelemetry()
                sendDeviceLog("info", "Conectado ao suporte com sucesso via $baseUrl.")
                sendDeviceLog("info", "Agente ${BuildConfig.VERSION_NAME}.")
                KioskPolicy.report(this@RemoteControlService)
                lastPolicySummary?.let { summary ->
                    sendDeviceLog("info", "[POLICY] $summary")
                }
                
                // Logar o estado inicial para diagnosticar se os dados de tela foram recebidos
                sendDeviceLog("info", "Dados de tela recebidos no Service: pendingData = ${pendingProjectionData != null}, pendingCode = $pendingResultCode")
                sendDeviceLog("info", "Logs detalhados de inicialização: $diagnosticLog")
                
                // Se houver espelhamento pendente, inicia agora que o WebSocket está pronto
                val data = pendingProjectionData
                val code = pendingResultCode
                if (data != null && code != -1 && mediaProjection == null) {
                    startScreenCapture(code, data)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastServerMessageMs.set(System.currentTimeMillis())
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale(webSocket)) return
                Log.e(TAG, "Falha na conexão com o servidor em $baseUrl: ${t.message}. Rotacionando...")
                this@RemoteControlService.webSocket = null
                lastServerMessageMs.set(0L)
                
                // Rotaciona a URL para tentar a próxima
                currentUrlIndex = (currentUrlIndex + 1) % SERVER_URLS.size
                val nextUrl = SERVER_URLS[currentUrlIndex]
                Log.i(TAG, "Próxima tentativa será em: $nextUrl")
                
                val currentDelay = reconnectDelayMs
                
                // Reconexão imediata via Handler
                Handler(Looper.getMainLooper()).postDelayed({ 
                    if (this@RemoteControlService.webSocket == null) {
                        connectToServer() 
                    }
                }, currentDelay)
                
                // Alarme de reconexão persistente para acordar a CPU
                scheduleNextReconnectAlarm(currentDelay)
                
                // Incrementar delay exponencialmente até 3 minutos
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(180_000L)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (stale(webSocket)) return
                Log.w(TAG, "Conexão encerrando pelo servidor: $reason")
                this@RemoteControlService.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stale(webSocket)) return
                Log.w(TAG, "Conexão fechada: $reason. Rotacionando...")
                this@RemoteControlService.webSocket = null
                lastServerMessageMs.set(0L)
                
                // Rotaciona a URL
                currentUrlIndex = (currentUrlIndex + 1) % SERVER_URLS.size
                
                // Se fechada inesperadamente, agendar alarme de reconexão rápido
                scheduleNextReconnectAlarm(5000L)
            }
        })
    }

    private fun handleServerMessage(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val type = obj.optString("type")
            val targetClient = obj.optString("target_client")
            val data = obj.optJSONObject("data") ?: JSONObject()

            Log.i(TAG, "Mensagem recebida do servidor. Tipo: $type")

            when (type) {
                "click" -> {
                    if (isMirroringActive) {
                        wakeUpScreen()
                        val x = data.optDouble("x", 0.5).toFloat()
                        val y = data.optDouble("y", 0.5).toFloat()
                        RemoteAccessibilityService.instance?.injectClick(x, y)
                    } else {
                        Log.i(TAG, "Ignorando clique: espelhamento de tela inativo.")
                    }
                }
                "swipe" -> {
                    if (isMirroringActive) {
                        wakeUpScreen()
                        val x1 = data.optDouble("x1", 0.2).toFloat()
                        val y1 = data.optDouble("y1", 0.2).toFloat()
                        val x2 = data.optDouble("x2", 0.8).toFloat()
                        val y2 = data.optDouble("y2", 0.8).toFloat()
                        val duration = data.optLong("duration", 300)
                        RemoteAccessibilityService.instance?.injectSwipe(x1, y1, x2, y2, duration)
                    } else {
                        Log.i(TAG, "Ignorando swipe: espelhamento de tela inativo.")
                    }
                }
                "key" -> {
                    wakeUpScreen()
                    val code = data.optString("code")
                    RemoteAccessibilityService.instance?.performAndroidAction(code)
                }
                "text" -> {
                    wakeUpScreen()
                    val value = data.optString("text")
                    RemoteAccessibilityService.instance?.typeText(value)
                }
                "install_app" -> {
                    val apkUrl = obj.optString("url")
                    val currentBaseUrl = SERVER_URLS[currentUrlIndex]
                    val httpBaseUrl = currentBaseUrl
                        .replace("wss://", "https://")
                        .replace("ws://", "http://")
                    downloadAndInstallApk("$httpBaseUrl$apkUrl", targetClient)
                }
                "request_location", "enable_location" -> {
                    Log.i(TAG, "Comando de localização recebido: $type")
                    grantLocationIfOwner()
                    EnterpriseConnectivityPolicy.reinforceConnectivity(this, enforceLocation = true)
                    requestSingleLocation()
                }
                "set_location_interval" -> {
                    val interval = data.optLong("interval", 300000L)
                    val distance = data.optDouble("distance", 50.0).toFloat()
                    Log.i(TAG, "Comando de alteração de intervalo de localização recebido: interval=$interval, distance=$distance")
                    updateLocationInterval(interval, distance)
                }
                "toast" -> {
                    val message = data.optString("message")
                    Log.i(TAG, "Comando de Toast recebido: $message")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }
                "lock_device" -> {
                    Log.i(TAG, "Comando de Bloqueio de Tela recebido.")
                    lockDevice()
                }
                "clear_password" -> {
                    Log.i(TAG, "Comando de Limpar Senha recebido.")
                    clearDevicePassword()
                }
                "wipe_device" -> {
                    Log.i(TAG, "Comando de Reset de Fábrica (Wipe) recebido.")
                    wipeDevice()
                }
                "start_mirroring" -> {
                    Handler(Looper.getMainLooper()).post { beginMirroring(data) }
                }
                "stop_mirroring" -> {
                    Log.i(TAG, "Comando de Parar Espelhamento recebido.")
                    stopScreenCapture()
                }
                "start_monitoring" -> {
                    Log.i(TAG, "Comando start_monitoring recebido.")
                    startConnectivityMonitoring()
                }
                "stop_monitoring" -> {
                    Log.i(TAG, "Comando stop_monitoring recebido.")
                    stopConnectivityMonitoring()
                }
                "set_kiosk" -> {
                    Log.i(TAG, "Comando set_kiosk recebido.")
                    Handler(Looper.getMainLooper()).post {
                        KioskPolicy.applyCommand(this, data)
                    }
                }
                "list_apps" -> {
                    Log.i(TAG, "Comando list_apps recebido.")
                    KioskPolicy.sendInstalledApps(this)
                }
                "set_inventory" -> {
                    val enabled = data.optBoolean("enabled", false)
                    Log.i(TAG, "Comando set_inventory recebido: $enabled")
                    Handler(Looper.getMainLooper()).post {
                        KioskPolicy.setInventory(this, enabled)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao analisar comando do servidor: ${e.message}", e)
        }
    }

    /**
     * Efetua o download do arquivo APK do servidor e chama a instalação silenciosa
     */
    private fun downloadAndInstallApk(url: String, targetClient: String) {
        Log.i(TAG, "Iniciando download do APK para instalação silenciosa: $url")
        val request = Request.Builder().url(url).build()

        client?.newCall(request)?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Falha ao baixar APK: ${e.message}")
                sendInstallStatus(targetClient, "error", "Falha de rede ao baixar APK: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    sendInstallStatus(targetClient, "error", "Erro HTTP no download do APK: ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    sendInstallStatus(targetClient, "error", "Conteúdo do APK vazio")
                    return
                }

                try {
                    val tempApkFile = File(cacheDir, "temp_remote_install.apk")
                    val outputStream = FileOutputStream(tempApkFile)
                    outputStream.write(body.bytes())
                    outputStream.close()

                    Log.i(TAG, "Download concluído. Executando instalação silenciosa...")
                    
                    packageInstallHelper.installPackageSilently(tempApkFile, object : PackageInstallHelper.InstallCallback {
                        override fun onSuccess() {
                            sendInstallStatus(targetClient, "ok", "Instalação silenciosa executada com sucesso!")
                            tempApkFile.delete()
                        }

                        override fun onFailure(message: String) {
                            sendInstallStatus(targetClient, "error", "Erro na instalação DPC: $message")
                            tempApkFile.delete()
                        }
                    })

                } catch (e: Exception) {
                    Log.e(TAG, "Erro de gravação no APK temporário: ${e.message}", e)
                    sendInstallStatus(targetClient, "error", "Exceção local de gravação: ${e.message}")
                }
            }
        })
    }

    private fun sendInstallStatus(targetClient: String, status: String, message: String) {
        val payload = JSONObject().apply {
            put("type", "apk_installed_status")
            put("target_client", targetClient)
            put("status", status)
            put("message", message)
        }
        sendOrReconnect(payload.toString())
    }

    fun sendJson(payload: String) {
        sendOrReconnect(payload)
    }

    fun notifyKiosk(label: String) {
        sendDeviceLog("info", "Modo do coletor: $label.")
    }

    /** Sem Device Owner o quiosque, a instalação silenciosa e o Wi-Fi gerenciado não funcionam. */
    fun isDeviceOwner(): Boolean = try {
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
            .isDeviceOwnerApp(packageName)
    } catch (e: Exception) {
        false
    }

    fun kioskLog(text: String) {
        sendDeviceLog("warn", text)
    }

    /** Dados da tela "Informações do coletor" (engrenagem do quiosque). */
    fun infoSnapshot(): Map<String, String> = linkedMapOf(
        "serial" to runCatching { deviceSerial() }.getOrDefault(""),
        "mac" to runCatching { deviceMac() }.getOrDefault(""),
        "ip" to runCatching { deviceIp() }.getOrDefault(""),
        "device_id" to deviceId,
        "server" to SERVER_URLS.getOrElse(currentUrlIndex) { SERVER_URLS.first() },
        "online" to (webSocket != null).toString()
    )

    private fun sendOrReconnect(payload: String) {
        val ws = webSocket
        if (ws != null && ws.send(payload)) return
        Log.w(TAG, "Link do coletor morto. Abrindo de novo.")
        runCatching { webSocket?.cancel() }
        webSocket = null
        reconnectDelayMs = 1000L
        connectToServer()
    }

    // --- TELEMETRIA EM LOOP ---
    private fun startTelemetryLoop() {
        telemetryHandler = Handler(Looper.getMainLooper())
        telemetryRunnable = object : Runnable {
            override fun run() {
                sendTelemetry()
                telemetryHandler?.postDelayed(this, 10000) // Envia a cada 10 segundos
            }
        }
        telemetryHandler?.post(telemetryRunnable!!)
    }

    private fun stopTelemetryLoop() {
        telemetryRunnable?.let { telemetryHandler?.removeCallbacks(it) }
    }

    private fun sendTelemetry() {
        try {
            val battery = batterySnapshot()
            val ramInfo = getRamUsage()
            
            val payload = JSONObject().apply {
                put("type", "telemetry")
                put("battery", battery.first)
                put("charging", battery.second)
                put("ram", ramInfo)
                put("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("model", Build.MODEL)
                put("agent_version", BuildConfig.VERSION_NAME)
                put("device_owner", isDeviceOwner())
                put("serial", deviceSerial())
                put("mac", deviceMac())
                put("ip", deviceIp())
            }
            sendOrReconnect(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar telemetria: ${e.message}")
        }
    }

    private fun cleanSerial(raw: String?): String {
        val serial = raw?.trim().orEmpty()
        if (serial.isEmpty() || serial.equals(Build.UNKNOWN, true) || serial.equals("unknown", true)) return ""
        return serial
    }

    private fun deviceSerial(): String {
        val found = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                if (dpm.isDeviceOwnerApp(packageName)) {
                    found.add(cleanSerial(Build.getSerial()))
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    found.add(cleanSerial(Build.SERIAL))
                }
            } else {
                @Suppress("DEPRECATION")
                found.add(cleanSerial(Build.SERIAL))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Série do sistema: ${e.message}")
        }
        listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "ro.vendor.serialno",
            "persist.sys.serialno",
            "ril.serialnumber"
        ).forEach { found.add(cleanSerial(systemProperty(it))) }
        return found.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun normalizeMac(value: String?): String {
        var mac = value?.trim()?.uppercase()?.replace('-', ':').orEmpty()
        if (mac.matches(Regex("^[0-9A-F]{12}$"))) {
            mac = mac.chunked(2).joinToString(":")
        }
        if (!mac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) return ""
        if (mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return ""
        return mac
    }

    private fun noteMac(source: String, mac: String): String {
        if (mac.isNotEmpty() && !macNoted) {
            macNoted = true
            Log.i(TAG, "MAC fixo $mac ($source)")
            sendDeviceLog("info", "MAC fixo obtido ($source).")
        }
        return mac
    }

    /** MAC de fábrica. O bit local ligado indica MAC aleatório, que não entra no controle de acesso. */
    private fun factoryMac(value: String?): String {
        val mac = normalizeMac(value)
        if (mac.isEmpty()) return ""
        val first = mac.substring(0, 2).toIntOrNull(16) ?: return ""
        if (first and 0x02 != 0) return ""
        return mac
    }

    /**
     * API oficial do Android para o Device Owner ler o MAC de fábrica do Wi-Fi.
     * No Android 10+ (ex.: MC3300x com Android 14) o wlan0 usa MAC aleatório e as
     * outras fontes ficam vazias; esta continua devolvendo o MAC real.
     */
    private fun ownerWifiMac(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return ""
            val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            factoryMac(dpm.getWifiMacAddress(admin))
        } catch (e: Exception) {
            Log.w(TAG, "MAC pelo DPC: ${e.message}")
            ""
        }
    }

    private fun deviceMac(): String {
        val owner = ownerWifiMac()
        if (owner.isNotEmpty()) return noteMac("dpc", owner)
        val boot = bootWifiMac()
        if (boot.isNotEmpty()) return noteMac("fábrica", boot)
        val fromLink = macFromIpLink()
        if (fromLink.isNotEmpty()) return noteMac("wlan0", fromLink)
        val iface = interfaceMac()
        if (iface.isNotEmpty()) return noteMac("interface", iface)
        val sys = factoryMac(try {
            java.io.File("/sys/class/net/wlan0/address").readText()
        } catch (e: Exception) {
            ""
        })
        if (sys.isNotEmpty()) return noteMac("sistema", sys)
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val current = factoryMac(wifi.connectionInfo?.macAddress)
            if (current.isNotEmpty()) return noteMac("wifi", current)
        } catch (e: Exception) {
            Log.w(TAG, "MAC do Wi-Fi: ${e.message}")
        }
        val factory = factoryWifiMac()
        if (factory.isNotEmpty()) return noteMac("fábrica", factory)
        val zebra = readOemWifiMac()
        if (zebra.isNotEmpty()) {
            oemWifiMac = zebra
            return noteMac("zebra", zebra)
        }
        if (factoryMac(oemWifiMac).isNotEmpty()) return noteMac("zebra", oemWifiMac)
        for (key in listOf(
            "ro.boot.device.wifi_mac",
            "ro.boot.wifimacaddr",
            "ro.boot.wifi.mac",
            "ro.wlan.mac",
            "ro.vendor.wifi.macaddr",
            "persist.vendor.wifi.mac",
            "persist.vendor.radio.wifimac",
            "persist.sys.wifi.mac"
        )) {
            val mac = factoryMac(systemProperty(key))
            if (mac.isNotEmpty()) return noteMac("propriedade", mac)
        }
        macMisses += 1
        if (macMisses == 4) sendDeviceLog("warn", "MAC fixo ainda vazio neste Android.")
        return ""
    }

    private fun bootWifiMac(): String {
        val direct = factoryMac(systemProperty("ro.boot.device.wifi_mac"))
        if (direct.isNotEmpty()) return direct
        return try {
            val proc = ProcessBuilder("/system/bin/getprop", "ro.boot.device.wifi_mac")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            factoryMac(text)
        } catch (e: Exception) {
            ""
        }
    }

    private fun macFromIpLink(): String {
        return try {
            val proc = ProcessBuilder("/system/bin/ip", "link", "show", "wlan0")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val found = Regex("link/ether ([0-9a-fA-F:]{17})").find(text)
            factoryMac(found?.groupValues?.get(1))
        } catch (e: Exception) {
            ""
        }
    }

    private fun interfaceMac(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val item = interfaces.nextElement()
                val name = item.name?.lowercase().orEmpty()
                if (name != "wlan0") continue
                var bytes = item.hardwareAddress
                if (bytes == null) {
                    try {
                        val field = item.javaClass.getDeclaredField("hardwareAddr")
                        field.isAccessible = true
                        bytes = field.get(item) as? ByteArray
                    } catch (_: Exception) {
                    }
                }
                if (bytes == null || bytes.isEmpty()) continue
                val mac = factoryMac(bytes.joinToString(":") { b -> "%02X".format(b.toInt() and 0xff) })
                if (mac.isNotEmpty()) return mac
            }
        } catch (e: Exception) {
            Log.w(TAG, "MAC da interface: ${e.message}")
        }
        return ""
    }

    private fun factoryWifiMac(): String {
        if (factoryMacBlocked || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ""
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifi.javaClass.getMethod("getFactoryMacAddresses")
            val values = method.invoke(wifi) as? Array<*>
            values?.firstNotNullOfOrNull { factoryMac(it?.toString()).ifEmpty { null } }.orEmpty()
        } catch (e: Exception) {
            factoryMacBlocked = true
            Log.w(TAG, "MAC de fábrica: ${e.message}")
            ""
        }
    }

    private fun macFromLinkLocal(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return ""
            for (link in props.linkAddresses) {
                val addr = link.address
                if (addr is java.net.Inet6Address && addr.isLinkLocalAddress) {
                    val mac = eui64ToMac(addr.address)
                    if (mac.isNotEmpty()) return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MAC da rede: ${e.message}")
        }
        return ""
    }

    private fun eui64ToMac(bytes: ByteArray): String {
        if (bytes.size != 16) return ""
        val id = bytes.copyOfRange(8, 16)
        if (id[3] != 0xff.toByte() || id[4] != 0xfe.toByte()) return ""
        val mac = byteArrayOf(
            (id[0].toInt() xor 0x02).toByte(),
            id[1],
            id[2],
            id[5],
            id[6],
            id[7]
        )
        return normalizeMac(mac.joinToString(":") { b -> "%02X".format(b.toInt() and 0xff) })
    }

    private fun readOemWifiMac(): String {
        val uris = listOf(
            "content://oem_info/oem.zebra.secure/wifi_mac"
        )
        for (uri in uris) {
            try {
                contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    for (i in 0 until cursor.columnCount) {
                        val mac = factoryMac(try {
                            cursor.getString(i)
                        } catch (e: Exception) {
                            ""
                        })
                        if (mac.isNotEmpty()) return mac
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OEMInfo: ${e.message}")
            }
        }
        return ""
    }

    /** No Android 10+ a Zebra só entrega o MAC de fábrica depois desta permissão. */
    private fun requestZebraWifiMac() {
        if (zebraMacGrantStarted || oemWifiMac.isNotEmpty()) return
        zebraMacGrantStarted = true
        val direct = readOemWifiMac()
        if (direct.isNotEmpty()) {
            oemWifiMac = direct
            return
        }
        try {
            val listenerClass = Class.forName("com.symbol.emdk.EMDKManager\$EMDKListener")
            val emdkClass = Class.forName("com.symbol.emdk.EMDKManager")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader ?: javaClass.classLoader,
                arrayOf(listenerClass),
                java.lang.reflect.InvocationHandler { _, method, args ->
                    if (method.name == "onOpened") {
                        val manager = args?.firstOrNull()
                        if (manager != null) Thread { grantZebraMac(manager) }.start()
                    }
                    if (method.name == "hashCode") 0 else null
                }
            )
            emdkClass.getMethod("getEMDKManager", Context::class.java, listenerClass)
                .invoke(null, applicationContext, proxy)
        } catch (e: Exception) {
            Log.w(TAG, "EMDK: ${e.message}")
        }
    }

    private fun grantZebraMac(emdkManager: Any) {
        try {
            val feature = enumConstant("com.symbol.emdk.EMDKManager\$FEATURE_TYPE", "PROFILE") ?: return
            val profileManager = emdkManager.javaClass
                .getMethod("getInstance", feature.javaClass)
                .invoke(emdkManager, feature) ?: return
            val flag = enumConstant("com.symbol.emdk.ProfileManager\$PROFILE_FLAG", "SET") ?: return
            val xml = zebraMacPermissionXml()
            val process = profileManager.javaClass.methods.firstOrNull { method ->
                method.name == "processProfile" && method.parameterTypes.size == 3
            }
            val result = if (process != null) {
                process.invoke(profileManager, "NexisWifiMac", flag, arrayOf(xml))
            } else {
                null
            }
            if (process != null) {
                try {
                    process.invoke(profileManager, "NexisGps", flag, arrayOf(zebraGpsXml()))
                } catch (e: Exception) {
                    Log.w(TAG, "GPS Zebra: ${e.message}")
                }
            }
            val mac = readOemWifiMac()
            if (mac.isNotEmpty()) {
                oemWifiMac = mac
                sendDeviceLog("info", "MAC obtido (zebra).")
                macNoted = true
            } else {
                val status = result?.toString()?.take(160).orEmpty()
                sendDeviceLog("warn", "MAC Zebra sem valor. ${status}".trim())
            }
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            sendDeviceLog("warn", "MAC Zebra: ${cause.message?.take(160) ?: cause.javaClass.simpleName}")
        } finally {
            try {
                emdkManager.javaClass.getMethod("release").invoke(emdkManager)
            } catch (_: Exception) {
            }
        }
    }

    private fun enumConstant(className: String, name: String): Any? {
        return try {
            Class.forName(className).enumConstants?.firstOrNull { (it as Enum<*>).name == name }
        } catch (e: Exception) {
            null
        }
    }

    private fun zebraMacPermissionXml(): String {
        val signature = callerCertBase64()
        return """
            <wap-provisioningdoc>
              <characteristic type="Profile">
                <parm name="ProfileName" value="NexisWifiMac"/>
                <characteristic version="8.3" type="AccessMgr">
                  <parm name="OperationMode" value="0"/>
                  <parm name="ServiceAccessAction" value="4"/>
                  <parm name="ServiceIdentifier" value="content://oem_info/oem.zebra.secure/wifi_mac"/>
                  <parm name="CallerPackageName" value="$packageName"/>
                  <parm name="CallerSignature" value="$signature"/>
                </characteristic>
              </characteristic>
            </wap-provisioningdoc>
        """.trimIndent()
    }

    private fun zebraGpsXml(): String {
        return """
            <wap-provisioningdoc>
              <characteristic type="Profile">
                <parm name="ProfileName" value="NexisGps"/>
                <characteristic version="4.3" type="LocationMgr">
                  <parm name="GPSState" value="1"/>
                </characteristic>
              </characteristic>
            </wap-provisioningdoc>
        """.trimIndent()
    }

    @Suppress("DEPRECATION")
    private fun callerCertBase64(): String {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val raw = signatures?.firstOrNull()?.toByteArray() ?: return ""
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    private fun deviceIp(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            val addr = props?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            val host = addr?.hostAddress?.substringBefore('%').orEmpty()
            if (host.isNotEmpty() && host != "0.0.0.0") return host
        } catch (e: Exception) {
            Log.w(TAG, "IP da rede ativa: ${e.message}")
        }
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) "" else String.format(
                "%d.%d.%d.%d",
                raw and 0xff,
                raw shr 8 and 0xff,
                raw shr 16 and 0xff,
                raw shr 24 and 0xff
            )
        } catch (e: Exception) {
            ""
        }
    }

    private fun systemProperty(key: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun batterySnapshot(): Pair<Int, Boolean> {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0
        return (if (level < 0) 0 else level) to charging
    }

    private fun getRamUsage(): String {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val totalGb = memInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        val availableGb = memInfo.availMem.toDouble() / (1024 * 1024 * 1024)
        val usedGb = totalGb - availableGb
        
        return String.format("%.1f GB / %.1f GB", usedGb, totalGb)
    }

    // --- LOCALIZAÇÃO SOB DEMANDA (sem rastreamento contínuo no boot) ---

    private fun initLocationManager() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Log.i(TAG, "LocationManager inicializado. GPS sob demanda (use request_location ou start_monitoring).")
    }

    private fun ensureLocationListener(): LocationListener {
        if (locationListener == null) {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    sendLocationUpdateThrottled(location, force = false)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
        }
        return locationListener!!
    }

    /** Rastreamento periodico — apenas com monitoramento de conectividade ativo. */
    private fun startContinuousLocationTracking() {
        if (isContinuousLocationActive) return
        if (!hasLocationPermission()) {
            sendDeviceLog("warn", "Rastreamento continuo ignorado: sem permissao de localizacao.")
            return
        }

        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    locationMinIntervalMs,
                    locationMinDistanceM,
                    ensureLocationListener()
                )
                isContinuousLocationActive = true
                Log.i(TAG, "Rastreamento continuo iniciado (monitoramento ativo, GPS ${locationMinIntervalMs/1000}s).")
                sendDeviceLog("info", "Rastreamento continuo iniciado (monitoramento ativo, ${locationMinIntervalMs/1000}s).")
            } else {
                sendDeviceLog("warn", "GPS desativado. Rastreamento continuo aguardando GPS.")
            }
        } catch (e: SecurityException) {
            sendDeviceLog("error", "Erro de seguranca ao iniciar rastreamento continuo: ${e.message}")
        } catch (e: Exception) {
            sendDeviceLog("error", "Erro ao iniciar rastreamento continuo: ${e.message}")
        }
    }

    private fun stopContinuousLocationTracking() {
        if (!isContinuousLocationActive) return
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao parar rastreamento continuo: ${e.message}")
        }
        isContinuousLocationActive = false
        Log.i(TAG, "Rastreamento continuo parado.")
    }

    private fun updateLocationInterval(interval: Long, distance: Float) {
        locationMinIntervalMs = interval.coerceAtLeast(10_000L)
        locationMinDistanceM = distance
        startMapLocation()
        sendDeviceLog("info", "Intervalo do mapa: ${locationMinIntervalMs / 1000}s / ${distance}m")
    }

    private fun startMapLocation() {
        grantLocationIfOwner()
        EnterpriseConnectivityPolicy.reinforceConnectivity(this, enforceLocation = true)
        if (isMc33()) forceHighAccuracy()
        if (!hasLocationPermission()) {
            sendDeviceLog("warn", "Sem permissão de localização para o mapa.")
            return
        }
        val cached = newestKnownLocation()
        if (cached != null) {
            sendLocationUpdateThrottled(cached, force = true)
        }
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
            mapLocationActive = false
            isContinuousLocationActive = false
            subscribeMapProviders(ensureLocationListener())
            mapLocationActive = true
            if (isMc33()) {
                Handler(Looper.getMainLooper()).postDelayed({
                    forceHighAccuracy()
                    val listener = locationListener
                    if (listener != null) subscribeMapProviders(listener)
                }, 2_000)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!sawFreshLocation) sendWifiObservation()
                }, 20_000)
            }
        } catch (e: Exception) {
            sendDeviceLog("error", "Mapa: ${e.message}")
        }
    }

    private fun isMc33(): Boolean {
        val model = Build.MODEL ?: ""
        val device = Build.DEVICE ?: ""
        val product = Build.PRODUCT ?: ""
        return listOf(model, device, product).any { it.contains("MC33", ignoreCase = true) }
    }

    private fun forceHighAccuracy() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, "3")
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(admin, "location_providers_allowed", "gps,network")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                Settings.Secure.putInt(
                    contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alta precisão: ${e.message}")
        }
    }

    private fun subscribeMapProviders(listener: LocationListener) {
        val manager = locationManager ?: return
        val names = linkedSetOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        try {
            manager.allProviders?.let { names.addAll(it) }
        } catch (_: Exception) {
        }
        val interval = if (isMc33()) 5_000L else locationMinIntervalMs
        val distance = if (isMc33()) 0f else locationMinDistanceM
        var asked = 0
        for (name in names) {
            try {
                manager.requestLocationUpdates(name, interval, distance, listener, Looper.getMainLooper())
                asked++
            } catch (e: Exception) {
                Log.w(TAG, "Provedor $name: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val coarse = android.location.Criteria().apply {
                    accuracy = android.location.Criteria.ACCURACY_COARSE
                    isCostAllowed = true
                    powerRequirement = android.location.Criteria.POWER_LOW
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(coarse, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "Pedido único de rede: ${e.message}")
            }
        }
        val gps = try { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val net = try { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        sendDeviceLog(
            "info",
            "Mapa pediu $asked provedor(es). GPS ${if (gps) "ligado" else "desligado"}, rede ${if (net) "ligada" else "desligada"}."
        )
    }

    private fun sendWifiObservation() {
        if (!isMc33()) return
        val now = System.currentTimeMillis()
        if (now - lastWifiLocateMs < 10 * 60 * 1000) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cached = readWifiAps(wifi)
        if (cached.length() > 0) {
            lastWifiLocateMs = now
            postWifiAps(cached)
            return
        }
        wifiLocateReceiver?.let { runCatching { unregisterReceiver(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { unregisterReceiver(this) }
                if (wifiLocateReceiver === this) wifiLocateReceiver = null
                val aps = readWifiAps(wifi)
                if (aps.length() == 0) {
                    sendDeviceLog("warn", "MC33 sem GPS e sem redes Wi-Fi para estimar o ponto.")
                    return
                }
                lastWifiLocateMs = System.currentTimeMillis()
                postWifiAps(aps)
            }
        }
        wifiLocateReceiver = receiver
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("DEPRECATION")
        val started = wifi.startScan()
        if (!started) {
            runCatching { unregisterReceiver(receiver) }
            wifiLocateReceiver = null
            sendDeviceLog("warn", "MC33 não conseguiu varrer o Wi-Fi.")
        }
    }

    private fun readWifiAps(wifi: WifiManager): JSONArray {
        val list = JSONArray()
        val seen = HashSet<String>()
        fun add(bssid: String?, rssi: Int) {
            val mac = bssid?.uppercase() ?: return
            if (mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return
            if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) return
            if (!seen.add(mac)) return
            list.put(JSONObject().apply {
                put("bssid", mac)
                put("rssi", rssi)
            })
        }
        try {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            add(info?.bssid, info?.rssi ?: -60)
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            wifi.scanResults?.sortedByDescending { it.level }?.take(8)?.forEach { add(it.BSSID, it.level) }
        } catch (_: Exception) {
        }
        return list
    }

    private fun postWifiAps(aps: JSONArray) {
        val payload = JSONObject().apply {
            put("type", "wifi_location")
            put("data", JSONObject().apply { put("aps", aps) })
        }
        sendOrReconnect(payload.toString())
        sendDeviceLog("info", "MC33 sem GPS. Estimando o ponto por ${aps.length()} rede(s) Wi-Fi.")
    }

    fun syncAdminTimer() {
        adminHandler.removeCallbacks(adminReturn)
        if (KioskPolicy.mode(this) != "admin") {
            adminDeadlineMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (adminDeadlineMs <= now) {
            adminDeadlineMs = now + 120_000L
            sendDeviceLog("info", "Modo admin por 2 minutos.")
        }
        adminHandler.postDelayed(adminReturn, adminDeadlineMs - now)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private var locationGrantApplied = false

    private fun grantLocationIfOwner() {
        if (locationGrantApplied) return
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val granted = android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        perms.forEach { perm ->
            try {
                if (dpm.getPermissionGrantState(admin, packageName, perm) == granted) return@forEach
                dpm.setPermissionGrantState(admin, packageName, perm, granted)
            } catch (e: Exception) {
                Log.w(TAG, "Permissão de localização: ${e.message}")
            }
        }
        locationGrantApplied = true
    }

    private fun newestKnownLocation(): Location? {
        val manager = locationManager ?: return null
        val names = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        val maxAgeMs = 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        for (name in names) {
            val loc = try {
                manager.getLastKnownLocation(name)
            } catch (e: Exception) {
                null
            } ?: continue
            if (now - loc.time > maxAgeMs) continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    private fun requestSingleLocation() {
        if (!hasLocationPermission()) {
            sendDeviceLog("error", "Sem permissão de localização para atualização sob demanda.")
            return
        }
        if (isMc33()) forceHighAccuracy()

        try {
            val cached = newestKnownLocation()
            if (cached != null) {
                sendLocationUpdateThrottled(cached, force = true)
                sendDeviceLog("info", "Posição em cache enviada (${cached.provider}).")
            }

            var freshSent = false
            val tempListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    sendLocationUpdateThrottled(location, force = true)
                    freshSent = true
                    sendDeviceLog("info", "Posição nova obtida (${location.provider}).")
                    try {
                        locationManager?.removeUpdates(this)
                    } catch (_: Exception) {
                    }
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }

            val gpsOn = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val netOn = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!gpsOn && !netOn && cached == null && !isMc33()) {
                sendDeviceLog("warn", "Localização desligada no coletor.")
                return
            }
            try {
                if (isMc33()) {
                    subscribeMapProviders(tempListener)
                } else {
                    if (gpsOn) {
                        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, tempListener, Looper.getMainLooper())
                    }
                    if (netOn) {
                        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, tempListener, Looper.getMainLooper())
                    }
                }
            } catch (e: Exception) {
                sendDeviceLog("warn", "Não foi possível pedir a localização: ${e.message}")
                if (isMc33()) sendWifiObservation()
                return
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    locationManager?.removeUpdates(tempListener)
                    if (!freshSent && cached == null) {
                        if (isMc33()) sendWifiObservation()
                        else sendDeviceLog("warn", "Sem sinal de GPS nem posição de rede.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no timeout de localizacao: ${e.message}")
                }
            }, 20_000)
        } catch (e: SecurityException) {
            sendDeviceLog("error", "Erro de segurança ao ler localização sob demanda: ${e.message}")
        } catch (e: Exception) {
            sendDeviceLog("error", "Erro ao ler localização sob demanda: ${e.message}")
        }
    }

    private fun sendLocationUpdateThrottled(location: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        val minInterval = if (force) LOCATION_ON_DEMAND_MIN_MS else locationMinIntervalMs
        if (!force && now - lastLocationSentMs < minInterval) {
            return
        }
        lastLocationSentMs = now
        sendLocationUpdate(location)
    }

    private fun sendLocationUpdate(location: Location) {
        try {
            val locObj = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy.toDouble())
                put("provider", location.provider)
                val bssid = connectedBssid()
                if (bssid.isNotEmpty()) put("bssid", bssid)
            }
            
            val payload = JSONObject().apply {
                put("type", "location_update")
                put("data", locObj)
            }
            
            sendOrReconnect(payload.toString())
            if (System.currentTimeMillis() - location.time < 10 * 60 * 1000) sawFreshLocation = true
            Log.i(TAG, "Localização enviada: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar localização: ${e.message}")
        }
    }

    private fun beginMirroring(data: JSONObject) {
        val silent = data.optBoolean("silent", true)
        val refresh = data.optBoolean("refresh", false)
        Log.i(TAG, "Comando de Iniciar Espelhamento recebido. Silencioso: $silent")
        RemoteAccessibilityService.autoClickMediaProjection = silent
        AccessibilitySetup.enable(this)
        if (mediaProjection != null && virtualDisplay != null && !refresh) {
            isMirroringActive = true
            wakeUpScreen()
            scheduleKeyframe(80)
            scheduleKeyframe(500)
            sendDeviceLog("info", "Espelhamento retomado, sem nova confirmação.")
            return
        }
        if (mediaProjection != null) {
            wakeUpScreen()
            sendDeviceLog("info", "Abrindo a tela de novo, sem pedir confirmação.")
            openMirrorDisplay()
            return
        }
        if (RemoteAccessibilityService.instance == null) {
            sendDeviceLog("warn", "A confirmação pode aparecer no coletor. A acessibilidade do Nexis não está ligada.")
        }
        wakeUpScreen()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("action", "start_capture")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        sendDeviceLog("info", "Pedindo a tela do coletor.")
    }

    // --- ESPELHAMENTO DE TELA REAL (MEDIAPROJECTION) ---

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { startScreenCapture(resultCode, data) }
            return
        }
        try {
            Log.i(TAG, "Iniciando captura de tela MediaProjection...")
            sendDeviceLog("info", "Iniciando MediaProjection...")
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                sendDeviceLog("error", "MediaProjection obtido é nulo.")
                isMirroringActive = false
                return
            }
            pendingProjectionData = null
            pendingResultCode = -1
            mediaProjection = projection
            projectionCallback = null
            sendDeviceLog("info", "MediaProjection obtido com sucesso.")
            ensureProjectionCallback()
            mirrorNeedsSettle = true
            openMirrorDisplay()
            sendBroadcast(Intent(ACTION_CAPTURE_READY).setPackage(packageName))
            Handler(Looper.getMainLooper()).postDelayed({ settleMirror() }, 800)
        } catch (e: Exception) {
            isMirroringActive = false
            Log.e(TAG, "Erro crítico ao iniciar captura de tela: ${e.message}", e)
            sendDeviceLog("error", "Erro crítico ao iniciar captura: ${e.message}")
        }
    }

    private fun openMirrorDisplay() {
        try {
            ignoreProjectionStopUntil = System.currentTimeMillis() + 2500
            releaseMirrorSurface()
            isMirroringActive = true
            lastFrameHash = 0
            h264Frames = 0
            displayRetries = 0

            val point = android.graphics.Point()
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            val metrics = resources.displayMetrics
            val rawW = if (point.x >= 160) point.x else metrics.widthPixels
            val rawH = if (point.y >= 160) point.y else metrics.heightPixels
            val width = (rawW - rawW % 2).coerceAtLeast(160)
            val height = (rawH - rawH % 2).coerceAtLeast(120)
            val density = metrics.densityDpi

            val encW = (width - width % 16).coerceAtLeast(160)
            val encH = (height - height % 16).coerceAtLeast(160)

            screenThread = android.os.HandlerThread("ScreenCaptureThread").apply { start() }
            screenHandler = Handler(screenThread!!.looper)
            ensureProjectionCallback()

            if (startH264Capture(encW, encH, density)) {
                return
            }
            releaseEncoder()
            sendDeviceLog("error", "H.264 indisponível. Seguindo em JPEG.")
            screenReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            screenReader?.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < 80) {
                        image = reader.acquireLatestImage()
                        image?.close()
                        return@setOnImageAvailableListener
                    }
                    lastFrameTime = now

                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowWidth = rowStride / pixelStride

                        val bitmap = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()
                        image = null

                        val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()

                        val longEdge = maxOf(cleanBitmap.width, cleanBitmap.height)
                        val jpegBitmap = if (longEdge <= 720) {
                            cleanBitmap
                        } else {
                            val factor = 720f / longEdge
                            val outW = (cleanBitmap.width * factor).toInt().coerceAtLeast(2) / 2 * 2
                            val outH = (cleanBitmap.height * factor).toInt().coerceAtLeast(2) / 2 * 2
                            Bitmap.createScaledBitmap(cleanBitmap, outW, outH, true)
                        }
                        val bos = ByteArrayOutputStream()
                        jpegBitmap.compress(Bitmap.CompressFormat.JPEG, 68, bos)
                        if (jpegBitmap !== cleanBitmap) jpegBitmap.recycle()
                        cleanBitmap.recycle()
                        val bytes = bos.toByteArray()
                        val hash = bytes.contentHashCode()
                        if (hash == lastFrameHash) {
                            return@setOnImageAvailableListener
                        }
                        lastFrameHash = hash
                        sendScreenFrame(bytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar frame de tela: ${e.message}", e)
                    sendDeviceLog("error", "Erro ao processar frame de tela: ${e.message}")
                    image?.close()
                }
            }, screenHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "NexisScreenMirror",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenReader?.surface,
                null, null
            )
            Log.i(TAG, "VirtualDisplay de captura de tela criado. Tamanho: ${width}x${height}")
            sendDeviceLog("info", "VirtualDisplay de captura criado (${width}x${height})")
        } catch (e: Exception) {
            isMirroringActive = false
            Log.e(TAG, "Erro crítico ao iniciar captura de tela: ${e.message}", e)
            sendDeviceLog("error", "Erro crítico ao iniciar captura: ${e.message}")
        }
    }

    /** A primeira captura nasce com a tela de permissão na frente. Uma troca, com o quiosque visível, entrega a imagem. */
    private fun settleMirror() {
        if (!mirrorNeedsSettle) return
        mirrorNeedsSettle = false
        if (mediaProjection == null || !isMirroringActive) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (mediaProjection == null || !isMirroringActive) return@postDelayed
            sendMirrorReset()
            openMirrorDisplay()
        }, 350)
    }

    private fun sendMirrorReset() {
        try {
            sendOrReconnect(JSONObject().apply { put("type", "mirror_reset") }.toString())
        } catch (_: Exception) {
        }
    }

    private fun connectedBssid(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mac = wifi.connectionInfo?.bssid?.uppercase() ?: ""
            if (mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") "" else mac
        } catch (e: Exception) {
            ""
        }
    }

    private fun stopScreenCapture(release: Boolean = false) {
        if (stoppingCapture) return
        stoppingCapture = true
        Log.i(TAG, "Desligando captura de tela MediaProjection...")
        isMirroringActive = false
        NexisTyper.restore(this)
        lastFrameHash = 0
        try {
            if (release) {
                ignoreProjectionStopUntil = System.currentTimeMillis() + 1500
                releaseMirrorSurface()
            }
            sendDeviceLog("info", "Espelhamento de tela desligado.")
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao finalizar captura de tela: ${e.message}")
            sendDeviceLog("error", "Erro ao finalizar captura: ${e.message}")
        } finally {
            stoppingCapture = false
        }
    }

    private fun releaseMirrorSurface() {
        try {
            screenReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        releaseEncoder()
        try {
            screenReader?.close()
        } catch (_: Exception) {
        }
        screenReader = null
        try {
            screenThread?.quitSafely()
        } catch (_: Exception) {
        }
        screenThread = null
        screenHandler = null
    }

    private fun ensureProjectionCallback() {
        val projection = mediaProjection ?: return
        if (projectionCallback != null) return
        val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (System.currentTimeMillis() < ignoreProjectionStopUntil) {
                Log.i(TAG, "Captura refeita. A permissão de tela continua.")
                return
            }
            Log.i(TAG, "O Android encerrou a captura de tela.")
            sendDeviceLog("info", "O Android encerrou a captura de tela.")
            mediaProjection = null
            projectionCallback = null
            stopScreenCapture(release = true)
        }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback
    }

    private fun sendScreenFrame(jpeg: ByteArray) {
        webSocket?.send(jpeg.toByteString(0, jpeg.size))
    }

    private fun startH264Capture(width: Int, height: Int, density: Int, warm: Boolean = true): Boolean {
        val generation = ++mirrorGeneration
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && isMirroringActive) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (config && !annexBHasType(data, 5)) {
                            lastCodecConfig = data
                            sendH264(0x03, info.presentationTimeUs, data)
                        } else {
                            if (annexBHasType(data, 7)) lastCodecConfig = data
                            val cached = lastCodecConfig
                            if (cached != null && !annexBHasType(data, 7)) {
                                sendH264(0x03, info.presentationTimeUs, cached)
                            }
                            val kind = if (key || annexBHasType(data, 5)) 0x01 else 0x02
                            sendH264(kind, info.presentationTimeUs, data)
                            h264Frames++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao enviar H.264: ${e.message}")
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder H.264: ${e.message}")
                sendDeviceLog("error", "Encoder H.264: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Formato H.264: $format")
            }
        }

        val codec = openAvcEncoder(width, height, callback) ?: return false
        return try {
            videoEncoder = codec
            encoderSurface = codec.createInputSurface()
            codec.start()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "NexisScreenMirror",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null, screenHandler
            )
            if (virtualDisplay == null) {
                releaseEncoder()
                return false
            }
            scheduleKeyframe(400)
            scheduleKeyframe(1200)
            Log.i(TAG, "H.264 ativo ${width}x${height}")
            sendDeviceLog("info", "Transmissão H.264 ${width}x${height}.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir H.264: ${e.message}", e)
            releaseEncoder()
            false
        }
    }

    private fun openAvcEncoder(width: Int, height: Int, callback: MediaCodec.Callback): MediaCodec? {
        val attempts = arrayOf(true, false)
        var lastError: Exception? = null
        for (extras in attempts) {
            val codec = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: Exception) {
                lastError = e
                break
            }
            try {
                codec.setCallback(callback, screenHandler)
                codec.configure(avcFormat(width, height, extras), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: Exception) {
                lastError = e
                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
        Log.e(TAG, "Encoder AVC recusou a configuração: ${lastError?.message}")
        return null
    }

    private fun avcFormat(width: Int, height: Int, extras: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 400_000L)
        if (extras) {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                format.setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
        }
        return format
    }

    private fun releaseEncoder() {
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null
        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null
    }

    private fun scheduleKeyframe(delayMs: Long) {
        screenHandler?.postDelayed({
            if (!isMirroringActive) return@postDelayed
            val config = lastCodecConfig
            if (config != null) sendH264(0x03, 0, config)
            try {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                videoEncoder?.setParameters(params)
            } catch (_: Exception) {
            }
        }, delayMs)
    }

    private fun annexBHasType(data: ByteArray, nalType: Int): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            val start = when {
                i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (start == 0) {
                i++
                continue
            }
            val nal = i + start
            if (nal < data.size && (data[nal].toInt() and 0x1F) == nalType) return true
            i = nal + 1
        }
        return false
    }

    private fun sendH264(kind: Int, ptsUs: Long, nal: ByteArray) {
        val socket = webSocket ?: return
        val out = ByteArray(10 + nal.size)
        out[0] = 0x68
        out[1] = kind.toByte()
        var pts = ptsUs
        for (i in 7 downTo 0) {
            out[2 + i] = (pts and 0xFF).toByte()
            pts = pts ushr 8
        }
        System.arraycopy(nal, 0, out, 10, nal.size)
        socket.send(out.toByteString(0, out.size))
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "Nexis:WakeUpScreen"
                )
                wakeLock.acquire(8_000)
                Log.i(TAG, "WakeLock adquirido. Tela ligada remotamente.")
                sendDeviceLog("info", "Tela ligada remotamente por ação do analista.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ligar a tela com WakeLock: ${e.message}")
        }
    }

    private fun lockDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                sendDeviceLog("info", "Dispositivo bloqueado remotamente com sucesso.")
            } else {
                sendDeviceLog("error", "Falha ao bloquear: App não é administrador ativo.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao bloquear dispositivo: ${e.message}", e)
            sendDeviceLog("error", "Erro ao bloquear dispositivo: ${e.message}")
        }
    }

    private fun clearDevicePassword() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val token = "nexis_super_secure_reset_token_32_bytes_long".toByteArray()
                    val isActive = dpm.isResetPasswordTokenActive(adminComponent)
                    sendDeviceLog("info", "Status do Token DPC: isResetPasswordTokenActive = $isActive")
                    
                    if (!isActive) {
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                        if (!km.isKeyguardLocked) {
                            sendDeviceLog("info", "Dispositivo desbloqueado. Tentando re-registrar token DPC...")
                            val successSet = dpm.setResetPasswordToken(adminComponent, token)
                            sendDeviceLog("info", "Resultado do re-registro do token: $successSet")
                        } else {
                            sendDeviceLog("error", "Aviso: O token DPC não está ativo e o celular está bloqueado. É necessário desbloquear o aparelho pelo menos uma vez após definir a senha para ativar o token.")
                        }
                    }

                    try {
                        val success = dpm.resetPasswordWithToken(adminComponent, null, token, 0)
                        if (success) {
                            sendDeviceLog("info", "Senha do dispositivo limpa com sucesso (via token DPC).")
                        } else {
                            sendDeviceLog("error", "Falha ao limpar senha: O token DPC não pôde ser aplicado pelo sistema.")
                        }
                    } catch (npe: NullPointerException) {
                        Log.e(TAG, "NPE ao limpar senha", npe)
                        sendDeviceLog("error", "Erro ao limpar senha (NPE): O token DPC está inativo/nulo no sistema. Desbloqueie o celular uma vez após a alteração da senha para que o Android ative o token.")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    dpm.resetPassword("", 0)
                    sendDeviceLog("info", "Senha do dispositivo limpa com sucesso (legacy API).")
                }
            } else {
                sendDeviceLog("error", "Falha ao limpar senha: App não é administrador ativo.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar senha: ${e.message}", e)
            sendDeviceLog("error", "Erro ao limpar senha: ${e.message}")
        }
    }

    private fun wipeDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                sendDeviceLog("info", "Executando Reset de Fábrica (Wipe Data) remoto...")
                dpm.wipeData(0)
            } else {
                sendDeviceLog("error", "Falha ao resetar: App não é Device Owner.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar Reset de Fábrica: ${e.message}", e)
            sendDeviceLog("error", "Erro ao resetar dispositivo: ${e.message}")
        }
    }

    // =========================================================
    // MODO DE MONITORAMENTO DE CONECTIVIDADE (WATCHDOG)
    // =========================================================

    /**
     * Ativa o monitoramento de conectividade.
     * Se o celular perder conexao com o servidor por mais de 90s,
     * o watchdog tenta restaurar todas as conectividades.
     */
    private fun startConnectivityMonitoring() {
        if (isMonitoringActive) {
            sendDeviceLog("info", "[MONITOR] Monitoramento ja esta ativo.")
            return
        }
        isMonitoringActive = true
        getSharedPreferences("nexis_prefs", Context.MODE_PRIVATE).edit().putBoolean("monitoring_active", true).apply()
        lastServerMessageMs.set(System.currentTimeMillis())

        // 1. BroadcastReceiver: reage em tempo real a mudancas de WiFi
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!isMonitoringActive) return
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN
                        )
                        if (state == WifiManager.WIFI_STATE_DISABLED) {
                            Log.w(TAG, "[MONITOR] WiFi desligado detectado! Reagendando reativacao...")
                            sendDeviceLog("warn", "[MONITOR] Wi-Fi desligado detectado! Reativando em 5s...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isMonitoringActive) restoreWifi()
                            }, 5_000L)
                        }
                    }
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val net = cm.activeNetwork
                        val caps = cm.getNetworkCapabilities(net)
                        val hasAny = caps != null && (
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        )
                        if (!hasAny) {
                            Log.w(TAG, "[MONITOR] Sem conectividade detectada via BroadcastReceiver!")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isMonitoringActive) checkAndRestoreConnectivity()
                            }, 8_000L)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(connectivityReceiver, filter)

        // 2. Timer periodico: verifica GPS + WiFi + Dados a cada 30s
        //    Alem disso, se o servidor nao responder em 90s, tenta restaurar tudo
        monitoringWatchdogTimer = Timer("NexisConnectWatchdog", true)
        monitoringWatchdogTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isMonitoringActive) return

                val silenceDuration = System.currentTimeMillis() - lastServerMessageMs.get()
                if (silenceDuration >= RECONNECT_RESTORE_TIMEOUT_MS) {
                    Log.w(TAG, "[MONITOR] Sem resposta do servidor ha ${silenceDuration/1000}s. Tentando restaurar conectividade...")
                    sendDeviceLog("warn", "[MONITOR] Sem contato com servidor ha ${silenceDuration/1000}s. Restaurando conexoes...")
                    checkAndRestoreConnectivity()
                } else {
                    // Verificacao periodica normal mesmo com servidor conectado
                    checkAndRestoreConnectivity(silent = true)
                }
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS)

        sendDeviceLog("info", "[MONITOR] Modo de monitoramento ATIVADO. GPS, Wi-Fi e Dados serao restaurados automaticamente.")
        startContinuousLocationTracking()
        Log.i(TAG, "[MONITOR] Watchdog de conectividade iniciado.")
    }

    /**
     * Desativa o monitoramento de conectividade.
     * Para o timer e desregistra o BroadcastReceiver.
     */
    private fun stopConnectivityMonitoring() {
        isMonitoringActive = false
        getSharedPreferences("nexis_prefs", Context.MODE_PRIVATE).edit().putBoolean("monitoring_active", false).apply()
        monitoringWatchdogTimer?.cancel()
        monitoringWatchdogTimer = null
        connectivityReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { /* ignora se ja desregistrado */ }
        }
        connectivityReceiver = null
        stopContinuousLocationTracking()
        sendDeviceLog("info", "[MONITOR] Modo de monitoramento DESATIVADO.")
        Log.i(TAG, "[MONITOR] Watchdog de conectividade parado.")
    }

    /**
     * Verifica o estado atual de GPS, WiFi e Dados Moveis.
     * Se algum estiver desligado, reativa usando DevicePolicyManager.
     * @param silent Se true, nao loga mensagens quando tudo esta ok.
     */
    private fun checkAndRestoreConnectivity(silent: Boolean = false) {
        val reinforceResult = EnterpriseConnectivityPolicy.reinforceConnectivity(
            this,
            enforceLocation = isMonitoringActive
        )

        if (reinforceResult.gpsRestored) {
            val now = System.currentTimeMillis()
            if (now - lastGpsRestoreLogMs >= 10 * 60 * 1000) {
                lastGpsRestoreLogMs = now
                sendDeviceLog("info", "Localização estava desligada. Religada.")
            }
        }
        if (reinforceResult.wifiRestored) {
            sendDeviceLog("info", "[MONITOR] Wi-Fi estava DESLIGADO. Reativado via politica DPC.")
        }
        if (reinforceResult.mobileDataRestored) {
            sendDeviceLog("info", "[MONITOR] Dados Moveis reativados via DPC.")
        }

        // Fallback Accessibility — Motorola nao responde a setGlobalSetting
        if (EnterpriseConnectivityPolicy.allowsMobileDataRestore(this) &&
            !EnterpriseConnectivityPolicy.isMobileDataEnabled(this)) {
            val now = System.currentTimeMillis()
            if (now - lastMobileDataRestoreMs >= MOBILE_DATA_RESTORE_COOLDOWN_MS) {
                lastMobileDataRestoreMs = now
                val accInstance = RemoteAccessibilityService.instance
                if (accInstance != null) {
                    wakeUpScreen()
                    accInstance.triggerMobileDataActivation()
                    if (!silent) {
                        sendDeviceLog("info", "[MONITOR] Dados Moveis OFF — fallback via Acessibilidade.")
                    }
                } else if (!silent) {
                    sendDeviceLog("error", "[MONITOR] Dados Moveis OFF — Acessibilidade inativa.")
                }
            }
        }

        if (!silent && !reinforceResult.anyRestored) {
            Log.d(TAG, "[MONITOR] Todas as conexoes estao ativas. Nenhuma restauracao necessaria.")
        }
    }

    private fun restoreWifi(logIfOk: Boolean = true): Boolean {
        val restored = EnterpriseConnectivityPolicy.reinforceConnectivity(this, enforceLocation = false).wifiRestored
        if (restored && logIfOk) {
            sendDeviceLog("info", "[MONITOR] Wi-Fi reativado via politica DPC.")
        }
        return restored
    }

    private fun applyEnterprisePoliciesOnBoot() {
        val result = EnterpriseConnectivityPolicy.apply(this)
        lastPolicySummary = EnterpriseConnectivityPolicy.formatSummary(result)
        Log.i(TAG, "[POLICY] $lastPolicySummary")
        KioskPolicy.engageUserOnBoot(this)
    }

    private fun setupResetPasswordToken() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val token = "nexis_super_secure_reset_token_32_bytes_long".toByteArray()
                    val success = dpm.setResetPasswordToken(adminComponent, token)
                    if (success) {
                        Log.i(TAG, "Token de reset de senha configurado em background no início do serviço.")
                    } else {
                        Log.w(TAG, "Não foi possível definir o token de reset em background.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao definir token de reset em background: ${e.message}")
        }
    }

    private fun sendDeviceLog(status: String, message: String) {
        try {
            val payload = JSONObject().apply {
                put("type", "device_log")
                put("status", status)
                put("message", message)
            }
            sendOrReconnect(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar log para o servidor: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "Rede disponível detectada via NetworkCallback. Verificando conexão...")
                    if (webSocket == null) {
                        Log.i(TAG, "WebSocket nulo. Forçando tentativa de conexão imediata...")
                        reconnectDelayMs = 5000L
                        connectToServer()
                    }
                }
            }
            try {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
                Log.i(TAG, "NetworkCallback padrão registrado com sucesso.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao registrar NetworkCallback: ${e.message}")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                Log.i(TAG, "NetworkCallback padrão desregistrado.")
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao desregistrar NetworkCallback: ${e.message}")
            }
        }
    }

    private fun watchScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) holdLinkAwake()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> holdLinkAwake()
                    Intent.ACTION_SCREEN_ON -> releaseLinkAwake()
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
    }

    fun onInventoryChanged() {
        inventoryHandler.removeCallbacks(inventoryTick)
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (KioskPolicy.inventory(this)) {
            holdLinkAwake()
            holdInventoryScreen()
            inventoryHandler.postDelayed(inventoryTick, 60_000)
        } else {
            releaseInventoryScreen()
            if (!pm.isInteractive) holdLinkAwake() else releaseLinkAwakeBody()
        }
    }

    private fun holdInventoryScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (inventoryScreenLock == null) {
                @Suppress("DEPRECATION")
                inventoryScreenLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Nexis:InventoryScreen"
                )
                inventoryScreenLock?.setReferenceCounted(false)
            }
            val lock = inventoryScreenLock ?: return
            if (lock.isHeld) lock.release()
            lock.acquire(60L * 60L * 1000L)
            Log.i(TAG, "Inventário ligou a tela por 1 hora.")
            sendDeviceLog("info", "Inventário: tela ligada por 1 hora.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ligar a tela do inventário: ${e.message}")
            sendDeviceLog("error", "Não foi possível ligar a tela do inventário: ${e.message}")
        }
    }

    private fun releaseInventoryScreen() {
        try {
            if (inventoryScreenLock?.isHeld == true) inventoryScreenLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun holdLinkAwake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (cpuLock == null) {
                cpuLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Nexis:Link")
                cpuLock?.setReferenceCounted(false)
            }
            if (cpuLock?.isHeld != true) cpuLock?.acquire()
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nexis:Wifi")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            Log.i(TAG, "Link mantido com a tela apagada.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao segurar o link: ${e.message}")
        }
    }

    private fun releaseLinkAwake() {
        if (KioskPolicy.inventory(this)) return
        releaseLinkAwakeBody()
    }

    private fun releaseLinkAwakeBody() {
        runCatching { if (cpuLock?.isHeld == true) cpuLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun listenForResetScan() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val data = intent?.getStringExtra("com.symbol.datawedge.data_string")?.trim().orEmpty()
                if (data == "NEXIS-RESET") {
                    Log.i(TAG, "QR de reset lido no coletor.")
                    sendDeviceLog("info", "QR de reset lido. Apagando o coletor.")
                    wipeDevice()
                }
            }
        }
        scanReceiver = receiver
        registerReceiver(receiver, IntentFilter("com.nexis.remoteagent.SCAN"))
    }

    private fun configureResetScan() {
        try {
            val params = android.os.Bundle()
            params.putString("intent_output_enabled", "true")
            params.putString("intent_action", "com.nexis.remoteagent.SCAN")
            params.putString("intent_category", Intent.CATEGORY_DEFAULT)
            params.putInt("intent_delivery", 2)
            val plugin = android.os.Bundle()
            plugin.putString("PLUGIN_NAME", "INTENT")
            plugin.putString("RESET_CONFIG", "false")
            plugin.putBundle("PARAM_LIST", params)
            val profile = android.os.Bundle()
            profile.putString("PROFILE_NAME", "Profile0")
            profile.putString("CONFIG_MODE", "UPDATE")
            profile.putBundle("PLUGIN_CONFIG", plugin)
            val intent = Intent("com.symbol.datawedge.api.ACTION")
            intent.putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "DataWedge não aceitou o perfil de reset: ${e.message}")
        }
    }

    private fun scheduleNextReconnectAlarm(delayMs: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val intent = Intent(this, RemoteControlService::class.java).apply {
            action = "reconnect_alarm"
        }
        val pendingIntent = android.app.PendingIntent.getService(
            this,
            9912,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        } else {
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        }
        Log.i(TAG, "Alarme de reconexão persistente agendado para daqui a ${delayMs/1000}s")
    }
}
