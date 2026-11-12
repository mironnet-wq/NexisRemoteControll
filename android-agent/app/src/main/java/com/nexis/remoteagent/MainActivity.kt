package com.nexis.remoteagent

import android.Manifest
import android.os.Build
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var txtAccessibility: TextView
    private lateinit var txtDeviceOwner: TextView
    private lateinit var txtLocationPermission: TextView
    private lateinit var btnStartService: Button
    private var a11yPrompted = false
    private var captureArmed = false
    private var activityResumed = false
    private var captureReadyRegistered = false
    private val captureReady = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            window.decorView.post {
                if (!isFinishing) moveTaskToBack(true)
            }
        }
    }

    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, RemoteControlService::class.java).apply {
                putExtra("projection_data", result.data)
                putExtra("projection_result_code", result.resultCode)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Conexão de suporte e espelhamento iniciados", Toast.LENGTH_SHORT).show()
            btnStartService.text = "Suporte Ativo (Reconectar)"
            btnStartService.setBackgroundColor(Color.parseColor("#10B981")) // Emerald Green
            window.decorView.postDelayed({
                if (!isFinishing) moveTaskToBack(true)
            }, 3000)
        } else {
            Toast.makeText(this, "Permissão de tela recusada. Iniciando suporte básico.", Toast.LENGTH_SHORT).show()
            val serviceIntent = Intent(this, RemoteControlService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            btnStartService.text = "Suporte Ativo (Sem Tela)"
            btnStartService.setBackgroundColor(Color.parseColor("#F59E0B")) // Amber
            moveTaskToBack(true)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permitir mostrar sobre a tela de bloqueio e acender a tela
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        
        // Criar layout programaticamente para evitar dependências de XML complexas
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A")) // Slate Dark background
        }

        // Título Principal
        val titleText = TextView(this).apply {
            text = "NEXIS AGENT"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        rootLayout.addView(titleText)

        val versionText = TextView(this).apply {
            text = "Versão ${BuildConfig.VERSION_NAME}"
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(versionText)

        // Subtítulo
        val subTitleText = TextView(this).apply {
            text = "Console de Suporte Corporativo Android"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 50)
        }
        rootLayout.addView(subTitleText)

        // Card de Permissões
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#1E293B"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 40)
            layoutParams = params
        }

        val cardTitle = TextView(this).apply {
            text = "Status de Configuração"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        }
        cardLayout.addView(cardTitle)

        // Configurar as linhas de status
        txtAccessibility = createStatusRow(cardLayout, "Serviço de Acessibilidade")
        txtDeviceOwner = createStatusRow(cardLayout, "Modo Device Owner (MDM)")
        txtLocationPermission = createStatusRow(cardLayout, "Acesso à Localização")

        rootLayout.addView(cardLayout)

        // Botões de Configuração Rápida
        createActionButton(rootLayout, "Entrar no quiosque") {
            KioskPolicy.setLocal(this, "user", KioskPolicy.packages(this))
        }

        createActionButton(rootLayout, "Modo admin") {
            startActivity(Intent(this, KioskActivity::class.java).putExtra("gate", "password"))
        }

        createActionButton(rootLayout, "Configurar Acessibilidade") {
            openAccessibilitySettings()
        }

        createActionButton(rootLayout, "Fornecer localização") {
            requestLocationPermission()
        }

        btnStartService = Button(this).apply {
            text = "Iniciar Conexão de Suporte"
            setBackgroundColor(Color.parseColor("#4F46E5")) // Indigo
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(30, 30, 30, 30)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 40, 0, 0)
            layoutParams = params
            setOnClickListener {
                startSupportService()
            }
        }
        rootLayout.addView(btnStartService)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(rootLayout)
        }
        setContentView(scroll)
        
        // Auto-iniciar conexão de suporte em background para registrar como Online no servidor
        val autoStartIntent = Intent(this, RemoteControlService::class.java).apply {
            action = "ensure_link"
        }
        try {
            ContextCompat.startForegroundService(this, autoStartIntent)
            Toast.makeText(this, "Conectando ao console de suporte...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("NexisMainActivity", "Erro ao auto-iniciar serviço: ${e.message}")
        }

        handleIntent(intent)
        registerCaptureReady()
        grantLocationIfOwner()
        enableAccessibilityIfOwner()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission()
        }
        window.decorView.postDelayed({
            if (isFinishing || a11yPrompted || RemoteAccessibilityService.instance != null) return@postDelayed
            if (KioskPolicy.mode(this) == "user") return@postDelayed
            a11yPrompted = true
            AlertDialog.Builder(this)
                .setTitle("Acessibilidade")
                .setMessage("O toque remoto ainda não ligou a acessibilidade. Abra a configuração e ative o serviço Nexis.")
                .setPositiveButton("Abrir configurações") { _, _ ->
                    openAccessibilitySettings()
                }
                .setNegativeButton("Depois", null)
                .show()
        }, 1500)
    }

    /**
     * No modo usuário as Configurações ficam ocultas pelo DPC: abrir daria erro e fecharia o app.
     * Também não é para o operador chegar nelas por aqui.
     */
    private fun openAccessibilitySettings() {
        if (KioskPolicy.mode(this) == "user") {
            Toast.makeText(this, "Entre no modo admin para mexer nas configurações.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Configurações indisponíveis neste modo.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (captureReadyRegistered) {
            try {
                unregisterReceiver(captureReady)
            } catch (_: Exception) {
            }
            captureReadyRegistered = false
        }
        super.onDestroy()
    }

    private fun registerCaptureReady() {
        if (captureReadyRegistered) return
        val filter = IntentFilter(RemoteControlService.ACTION_CAPTURE_READY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReady, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureReady, filter)
        }
        captureReadyRegistered = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getStringExtra("action") == "start_capture") {
            if (activityResumed) startSupportService()
            else captureArmed = true
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        updatePermissionStatus()
        if (captureArmed) {
            captureArmed = false
            startSupportService()
        }
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun createStatusRow(parent: LinearLayout, title: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            layoutParams = params
        }

        val nameText = TextView(this).apply {
            text = "$title: "
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        row.addView(nameText)

        val statusText = TextView(this).apply {
            text = "Verificando..."
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(statusText)
        parent.addView(row)

        return statusText
    }

    private fun createActionButton(parent: LinearLayout, textStr: String, action: () -> Unit): Button {
        val btn = Button(this).apply {
            text = textStr
            setBackgroundColor(Color.parseColor("#334155")) // Slate
            setTextColor(Color.WHITE)
            textSize = 13f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 10, 0, 10)
            layoutParams = params
            setOnClickListener { action() }
        }
        parent.addView(btn)
        return btn
    }

    private fun updatePermissionStatus() {
        // Acessibilidade
        val isAccessibilityEnabled = RemoteAccessibilityService.instance != null
        txtAccessibility.text = if (isAccessibilityEnabled) "ATIVADO" else "DESATIVADO"
        txtAccessibility.setTextColor(if (isAccessibilityEnabled) Color.GREEN else Color.RED)

        // Device Owner
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        txtDeviceOwner.text = if (isDeviceOwner) "ATIVADO (DPC)" else "INATIVO (Sideload)"
        txtDeviceOwner.setTextColor(if (isDeviceOwner) Color.GREEN else Color.YELLOW)

        // Acesso à Localização (sob demanda — não exige background)
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            txtLocationPermission.text = "AUTORIZADO"
            txtLocationPermission.setTextColor(Color.GREEN)
        } else if (hasCoarse) {
            txtLocationPermission.text = "AUTORIZADO (Aproximado)"
            txtLocationPermission.setTextColor(Color.YELLOW)
        } else {
            txtLocationPermission.text = "NEGADO"
            txtLocationPermission.setTextColor(Color.RED)
        }
    }

    private fun grantLocationIfOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, DeviceAdminReceiver::class.java)
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach { perm ->
            try {
                if (dpm.getPermissionGrantState(admin, packageName, perm) == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) return@forEach
                dpm.setPermissionGrantState(admin, packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) {
                android.util.Log.w("NexisMainActivity", "Localização não concedida pelo dono: ${e.message}")
            }
        }
    }

    private fun enableAccessibilityIfOwner() {
        AccessibilitySetup.enable(this)
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                103
            )
        } else {
            Toast.makeText(this, "Permissões de localização já concedidas.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSupportService() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (keyguardManager.isKeyguardSecure) {
                    android.util.Log.i("NexisMainActivity", "Keyguard is secure (has password/PIN). Launching MediaProjection directly above lockscreen.")
                    launchMediaProjection()
                } else {
                    keyguardManager.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            super.onDismissSucceeded()
                            android.util.Log.i("NexisMainActivity", "Keyguard dismiss succeeded. Starting screen capture.")
                            launchMediaProjection()
                        }
                        override fun onDismissCancelled() {
                            super.onDismissCancelled()
                            android.util.Log.w("NexisMainActivity", "Keyguard dismiss cancelled. Launching anyway.")
                            launchMediaProjection()
                        }
                        override fun onDismissError() {
                            super.onDismissError()
                            android.util.Log.e("NexisMainActivity", "Keyguard dismiss error. Launching anyway.")
                            launchMediaProjection()
                        }
                    })
                }
            } else {
                launchMediaProjection()
            }
        } else {
            launchMediaProjection()
        }
    }

    private fun launchMediaProjection() {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startMediaProjection.launch(screenCaptureIntent(mpManager))
        } catch (e: Exception) {
            android.util.Log.e("NexisMainActivity", "Erro ao iniciar MediaProjection intent: ${e.message}")
        }
    }

    /** No Android 14 o padrão é gravar só o app. Sem isto a tela remota abre vazia. */
    private fun screenCaptureIntent(manager: android.media.projection.MediaProjectionManager): Intent {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val configClass = Class.forName("android.media.projection.MediaProjectionConfig")
                val config = configClass.getMethod("createConfigForDefaultDisplay").invoke(null)
                val method = manager.javaClass.getMethod("createScreenCaptureIntent", configClass)
                val intent = method.invoke(manager, config) as? Intent
                if (intent != null) return intent
            } catch (e: Exception) {
                android.util.Log.w("NexisMainActivity", "Tela inteira: ${e.message}")
            }
        }
        return manager.createScreenCaptureIntent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }
}
