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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
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
import android.net.Network
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
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
        
        // Configurações de Conexão (Substituir com IP do servidor real)
        private const val SERVER_HOST = "172.16.0.100:6061"
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var deviceId: String = ""
    private var reconnectDelayMs = 5000L
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
    private var lastLocationSentMs = 0L
    private var lastMobileDataRestoreMs = 0L
    private val LOCATION_MIN_INTERVAL_MS = 300_000L // 5 min entre envios automaticos
    private val LOCATION_ON_DEMAND_MIN_MS = 30_000L  // 30s entre pedidos sob demanda
    private val MOBILE_DATA_RESTORE_COOLDOWN_MS = 60_000L

    // Variáveis de Captura de Tela (Espelhamento)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenReader: ImageReader? = null
    private var screenThread: android.os.HandlerThread? = null
    private var screenHandler: Handler? = null
    private var lastFrameTime = 0L
    private var pendingResultCode: Int = -1
    private var pendingProjectionData: Intent? = null
    private var diagnosticLog: String = "Nenhum log de inicialização"
    private var lastPolicySummary: String? = null

    override fun onCreate() {
        super.onCreate()
        packageInstallHelper = PackageInstallHelper(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
        
        // ID dinamico baseado no fabricante + modelo do celular
        // Formato: motorola_motoe20_A1B2C3 (seguro em todas as versoes Android)
        val manufacturer = Build.MANUFACTURER.lowercase().replace(" ", "_").take(12)
        val model = Build.MODEL.lowercase().replace(" ", "").replace("-", "").take(12)
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: SERIAL requer READ_PHONE_STATE - usa ANDROID_ID como fallback seguro
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)?.take(6) ?: "000000"
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL.take(6).ifEmpty { "000000" }
            }
        } catch (e: Exception) { "000000" }
        deviceId = "${manufacturer}_${model}_${serial}"
        
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
        applyEnterprisePoliciesOnBoot()
        connectToServer()
        startTelemetryLoop()
        initLocationManager()

        // Restaurar monitoramento de conectividade se estivesse ativo
        val prefs = getSharedPreferences("nexis_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("monitoring_active", false)) {
            Log.i(TAG, "Restaurando watchdog de conectividade persistente...")
            startConnectivityMonitoring()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.action == "reconnect_alarm") {
                Log.i(TAG, "Alarme de reconexão recebido.")
                if (webSocket == null) {
                    Log.i(TAG, "WebSocket desconectado. Iniciando tentativa de conexão via alarme...")
                    connectToServer()
                }
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
        unregisterNetworkCallback()
        stopConnectivityMonitoring()
        stopTelemetryLoop()
        stopScreenCapture()
        stopContinuousLocationTracking()
        webSocket?.close(1000, "Serviço destruído")
        webSocket = null
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

    private fun connectToServer() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$SERVER_HOST/ws/device/$deviceId")
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Conectado ao servidor de suporte Nexis.")
                this@RemoteControlService.webSocket = webSocket
                reconnectDelayMs = 5000L // Resetar delay após conectar
                sendTelemetry()
                sendDeviceLog("info", "Conectado ao suporte com sucesso.")
                lastPolicySummary?.let { summary ->
                    sendDeviceLog("info", "[POLICY] $summary")
                }
                
                // Logar o estado inicial para diagnosticar se os dados de tela foram recebidos
                sendDeviceLog("info", "Dados de tela recebidos no Service: pendingData = ${pendingProjectionData != null}, pendingCode = $pendingResultCode")
                sendDeviceLog("info", "Logs detalhados de inicialização: $diagnosticLog")
                
                // Se houver espelhamento pendente, inicia agora que o WebSocket está pronto
                val data = pendingProjectionData
                val code = pendingResultCode
                if (data != null && code != -1) {
                    startScreenCapture(code, data)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastServerMessageMs.set(System.currentTimeMillis())
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Falha na conexão com o servidor WebSocket: ${t.message}. Reconectando em ${reconnectDelayMs/1000}s...", t)
                this@RemoteControlService.webSocket = null
                lastServerMessageMs.set(0L)
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
                Log.w(TAG, "Conexão encerrando pelo servidor: $reason")
                this@RemoteControlService.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Conexão fechada: $reason")
                this@RemoteControlService.webSocket = null
                lastServerMessageMs.set(0L)
                
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
                "install_app" -> {
                    val apkUrl = obj.optString("url")
                    downloadAndInstallApk("http://$SERVER_HOST$apkUrl", targetClient)
                }
                "request_location" -> {
                    Log.i(TAG, "Comando de requisição de localização recebido.")
                    requestSingleLocation()
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
                    val silent = data.optBoolean("silent", true)
                    Log.i(TAG, "Comando de Iniciar Espelhamento recebido. Silencioso: $silent")
                    RemoteAccessibilityService.autoClickMediaProjection = silent
                    
                    wakeUpScreen()
                    
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("action", "start_capture")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    sendDeviceLog("info", "Iniciando fluxo de captura de tela remoto (Silencioso: $silent)...")
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
        webSocket?.send(payload.toString())
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
            val batteryLevel = getBatteryLevel()
            val ramInfo = getRamUsage()
            
            val payload = JSONObject().apply {
                put("type", "telemetry")
                put("battery", batteryLevel)
                put("ram", ramInfo)
                put("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("model", Build.MODEL)
            }
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar telemetria: ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter)
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
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
                    LOCATION_MIN_INTERVAL_MS,
                    100f,
                    ensureLocationListener()
                )
                isContinuousLocationActive = true
                Log.i(TAG, "Rastreamento continuo iniciado (monitoramento ativo, GPS 5 min).")
                sendDeviceLog("info", "Rastreamento continuo iniciado (monitoramento ativo).")
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

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSingleLocation() {
        if (!hasLocationPermission()) {
            sendDeviceLog("error", "Sem permissão de localização para atualização sob demanda.")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLocationSentMs < LOCATION_ON_DEMAND_MIN_MS) {
            sendDeviceLog("info", "Localização solicitada recentemente. Ignorando pedido duplicado.")
            return
        }

        try {
            val gpsLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val maxAgeMs = 180_000L

            if (gpsLoc != null && (now - gpsLoc.time) < maxAgeMs) {
                sendLocationUpdateThrottled(gpsLoc, force = true)
                sendDeviceLog("info", "Localização em cache enviada sob demanda.")
                return
            }

            var locationSent = false
            val tempListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    sendLocationUpdateThrottled(location, force = true)
                    locationSent = true
                    sendDeviceLog("info", "Localização em tempo real obtida com sucesso.")
                    locationManager?.removeUpdates(this)
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10_000L,
                    10f,
                    tempListener,
                    Looper.getMainLooper()
                )
            } else {
                sendDeviceLog("warn", "GPS desativado. Ative a localização ou ligue o monitoramento.")
                return
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    locationManager?.removeUpdates(tempListener)
                    if (!locationSent) {
                        sendDeviceLog("warn", "GPS sem sinal. Nenhuma coordenada enviada.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no timeout de localizacao: ${e.message}")
                }
            }, 15_000)
        } catch (e: SecurityException) {
            sendDeviceLog("error", "Erro de segurança ao ler localização sob demanda: ${e.message}")
        } catch (e: Exception) {
            sendDeviceLog("error", "Erro ao ler localização sob demanda: ${e.message}")
        }
    }

    private fun sendLocationUpdateThrottled(location: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        val minInterval = if (force) LOCATION_ON_DEMAND_MIN_MS else LOCATION_MIN_INTERVAL_MS
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
            }
            
            val payload = JSONObject().apply {
                put("type", "location_update")
                put("data", locObj)
            }
            
            webSocket?.send(payload.toString())
            Log.i(TAG, "Localização enviada: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar localização: ${e.message}")
        }
    }

    // --- ESPELHAMENTO DE TELA REAL (MEDIAPROJECTION) ---

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            isMirroringActive = true
            Log.i(TAG, "Iniciando captura de tela MediaProjection...")
            sendDeviceLog("info", "Iniciando MediaProjection...")
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                sendDeviceLog("error", "MediaProjection obtido é nulo.")
                isMirroringActive = false
                return
            }
            sendDeviceLog("info", "MediaProjection obtido com sucesso.")
            
            val metrics = resources.displayMetrics
            val scale = 0.7f // Aumentar escala para melhor definição visual na rede local
            val width = (metrics.widthPixels * scale).toInt()
            val height = (metrics.heightPixels * scale).toInt()
            val density = metrics.densityDpi

            screenThread = android.os.HandlerThread("ScreenCaptureThread").apply { start() }
            screenHandler = Handler(screenThread!!.looper)

            screenReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            screenReader?.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < 250) {
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

                        val bos = ByteArrayOutputStream()
                        cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                        cleanBitmap.recycle()
                        val bytes = bos.toByteArray()

                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        sendScreenFrame(base64)
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

    private fun stopScreenCapture() {
        Log.i(TAG, "Desligando captura de tela MediaProjection...")
        isMirroringActive = false
        try {
            screenReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null
            screenReader?.close()
            screenReader = null
            screenThread?.quitSafely()
            screenThread = null
            screenHandler = null
            sendDeviceLog("info", "Espelhamento de tela desligado.")
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao finalizar captura de tela: ${e.message}")
            sendDeviceLog("error", "Erro ao finalizar captura: ${e.message}")
        }
    }

    private fun sendScreenFrame(base64Image: String) {
        val payload = JSONObject().apply {
            put("type", "screen_frame")
            put("image", base64Image)
        }
        webSocket?.send(payload.toString())
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
                wakeLock.acquire(3000)
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
            sendDeviceLog("info", "[MONITOR] GPS estava DESLIGADO. Reativado (monitoramento ativo).")
        }
        if (reinforceResult.wifiRestored) {
            sendDeviceLog("info", "[MONITOR] Wi-Fi estava DESLIGADO. Reativado via politica DPC.")
        }
        if (reinforceResult.mobileDataRestored) {
            sendDeviceLog("info", "[MONITOR] Dados Moveis reativados via DPC.")
        }

        // Fallback Accessibility — Motorola nao responde a setGlobalSetting
        if (!EnterpriseConnectivityPolicy.isMobileDataEnabled(this)) {
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
            webSocket?.send(payload.toString())
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
