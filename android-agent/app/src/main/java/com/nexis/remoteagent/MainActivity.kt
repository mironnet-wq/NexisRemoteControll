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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var txtAccessibility: TextView
    private lateinit var txtDeviceOwner: TextView
    private lateinit var txtLocationPermission: TextView
    private lateinit var btnStartService: Button

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
            moveTaskToBack(true)
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
        createActionButton(rootLayout, "Configurar Acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        createActionButton(rootLayout, "Permissões de Localização") {
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

        setContentView(rootLayout)
        
        // Auto-iniciar conexão de suporte em background para registrar como Online no servidor
        val autoStartIntent = Intent(this, RemoteControlService::class.java)
        try {
            ContextCompat.startForegroundService(this, autoStartIntent)
            Toast.makeText(this, "Conectando ao console de suporte...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("NexisMainActivity", "Erro ao auto-iniciar serviço: ${e.message}")
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getStringExtra("action") == "start_capture") {
            startSupportService()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
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
            startMediaProjection.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            android.util.Log.e("NexisMainActivity", "Erro ao iniciar MediaProjection intent: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }
}
