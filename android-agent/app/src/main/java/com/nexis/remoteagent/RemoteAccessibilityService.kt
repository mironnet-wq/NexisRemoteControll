package com.nexis.remoteagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NexisAccessibility"
        
        // Mantém referência estática ativa para que o Foreground Service possa enviar comandos
        var instance: RemoteAccessibilityService? = null
            private set
            
        // Controla se deve auto-aceitar o prompt de captura de tela
        var autoClickMediaProjection = false
            set(value) {
                field = value
                if (value) {
                    instance?.startActiveCheckLoop()
                }
            }

        // Controla se deve auto-ativar os dados móveis nas configurações
        var autoEnableMobileData = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Serviço de Acessibilidade Nexis Conectado")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        checkLoopRunnable?.let { checkLoopHandler?.removeCallbacks(it) }
        Log.i(TAG, "Serviço de Acessibilidade Nexis Desvinculado")
        return super.onUnbind(intent)
    }

    private var checkLoopHandler: Handler? = null
    private var checkLoopRunnable: Runnable? = null
    private var checkLoopCounter = 0

    fun startActiveCheckLoop() {
        Log.i(TAG, "Iniciando loop ativo de busca pelo prompt de MediaProjection...")
        if (checkLoopHandler == null) {
            checkLoopHandler = Handler(Looper.getMainLooper())
        }
        
        checkLoopRunnable?.let { checkLoopHandler?.removeCallbacks(it) }
        checkLoopCounter = 0
        
        checkLoopRunnable = object : Runnable {
            override fun run() {
                if (!autoClickMediaProjection) {
                    Log.d(TAG, "Loop ativo encerrado: autoClickMediaProjection é false.")
                    return
                }
                
                checkLoopCounter++
                if (checkLoopCounter > 30) { // Tenta por 15 segundos (30 * 500ms)
                    Log.w(TAG, "Loop ativo encerrado por timeout de 15s sem encontrar o prompt.")
                    autoClickMediaProjection = false
                    return
                }
                
                val clicked = checkAndClickMediaProjectionPrompt()
                if (clicked) {
                    Log.i(TAG, "Prompt encontrado e clicado com sucesso no loop ativo (tentativa $checkLoopCounter).")
                    autoClickMediaProjection = false
                } else {
                    checkLoopHandler?.postDelayed(this, 500L)
                }
            }
        }
        checkLoopHandler?.post(checkLoopRunnable!!)
    }

    private fun checkAndClickMediaProjectionPrompt(): Boolean {
        val nodesToSearch = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            nodesToSearch.add(activeRoot)
        }
        
        val windowsList = windows
        if (windowsList != null) {
            for (window in windowsList) {
                val root = window.root
                if (root != null && root != activeRoot) {
                    nodesToSearch.add(root)
                }
            }
        }
        
        if (nodesToSearch.isEmpty()) return false
        
        val acceptTexts = listOf(
            "Iniciar agora", 
            "Iniciar", 
            "Start now", 
            "Start", 
            "Permitir", 
            "Allow"
        )
        
        for (rootNode in nodesToSearch) {
            for (text in acceptTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (nodes != null && nodes.isNotEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                Log.i(TAG, "Prompt de captura de tela auto-aceito (clique no nó: '${node.text}')")
                                return true
                            }
                        } else {
                            var parent = node.parent
                            while (parent != null) {
                                if (parent.isClickable) {
                                    if (parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                        Log.i(TAG, "Prompt de captura de tela auto-aceito (clique no pai de: '${node.text}')")
                                        return true
                                    }
                                }
                                parent = parent.parent
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val eventType = event.eventType
        val eventPackage = event.packageName?.toString() ?: ""
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Coleta todas as janelas interativas disponíveis para busca, garantindo que
            // encontre prompts mesmo na tela de bloqueio (onde rootInActiveWindow pode ser nulo)
            val nodesToSearch = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                nodesToSearch.add(activeRoot)
            }
            
            val windowsList = windows
            if (windowsList != null) {
                for (window in windowsList) {
                    val root = window.root
                    if (root != null && root != activeRoot) {
                        nodesToSearch.add(root)
                    }
                }
            }
            
            if (nodesToSearch.isEmpty()) return
            
            // 1. Auto-aceitar o prompt de captura de tela (MediaProjection) se a flag estiver ativa
            if (autoClickMediaProjection) {
                val acceptTexts = listOf(
                    "Iniciar agora", 
                    "Iniciar", 
                    "Start now", 
                    "Start", 
                    "Permitir", 
                    "Allow"
                )
                for (rootNode in nodesToSearch) {
                    for (text in acceptTexts) {
                        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                        if (nodes != null && nodes.isNotEmpty()) {
                            for (node in nodes) {
                                if (node.isClickable) {
                                    if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                        Log.i(TAG, "Prompt de captura de tela auto-aceito (clique no nó: '${node.text}')")
                                        autoClickMediaProjection = false
                                        return
                                    }
                                } else {
                                    var parent = node.parent
                                    while (parent != null) {
                                        if (parent.isClickable) {
                                            if (parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                                Log.i(TAG, "Prompt de captura de tela auto-aceito (clique no pai de: '${node.text}')")
                                                autoClickMediaProjection = false
                                                return
                                            }
                                        }
                                        parent = parent.parent
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 2. Auto-aceitar diálogos de ativação de GPS / Precisão de Localização do Google
            if (eventPackage == "com.google.android.gms" || eventPackage == "android" || eventPackage == "com.android.settings") {
                val gpsAcceptTexts = listOf(
                    "Ativar",
                    "OK",
                    "Concordo",
                    "Aceito",
                    "Agree",
                    "I agree",
                    "Yes",
                    "Permitir",
                    "Allow"
                )
                for (rootNode in nodesToSearch) {
                    for (text in gpsAcceptTexts) {
                        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                        if (nodes != null && nodes.isNotEmpty()) {
                            for (node in nodes) {
                                val nodeText = node.text?.toString() ?: ""
                                if (nodeText.equals(text, ignoreCase = true)) {
                                    if (node.isClickable) {
                                        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                            Log.i(TAG, "Diálogo de ativação de GPS/Precisão auto-aceito: '$nodeText'")
                                            return
                                        }
                                    } else {
                                        var parent = node.parent
                                        while (parent != null) {
                                            if (parent.isClickable) {
                                                if (parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                                                    Log.i(TAG, "Diálogo de ativação de GPS/Precisão auto-aceito via pai de: '$nodeText'")
                                                    return
                                                }
                                            }
                                            parent = parent.parent
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 3. Auto-ativar Dados Móveis se a flag estiver ativa e estiver nas configurações de rede
            if (autoEnableMobileData && (
                eventPackage == "com.android.settings" ||
                eventPackage == "com.google.android.networkstack" ||
                eventPackage.contains("settings") ||
                eventPackage.contains("motorola")
            )) {
                val dataTexts = listOf(
                    "Dados móveis",
                    "Dados móvel",
                    "Dados moveis",
                    "Mobile data",
                    "Dados de celular",
                    "Cellular data",
                    "Internet móvel",
                    "Dados de rede móvel",
                    "Usar dados",
                    "Dados celular"
                )
                
                for (rootNode in nodesToSearch) {
                    for (text in dataTexts) {
                        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                        if (nodes != null && nodes.isNotEmpty()) {
                            for (node in nodes) {
                                val switchNode = findSwitchInNodeOrSiblings(node)
                                if (switchNode != null) {
                                    if (!switchNode.isChecked) {
                                        Log.i(TAG, "Encontrado switch de dados móveis desativado. Clicando...")
                                        
                                        val clicked = if (switchNode.isClickable) {
                                            switchNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        } else {
                                            var parentClickable = switchNode.parent
                                            var clickSuccess = false
                                            while (parentClickable != null) {
                                                if (parentClickable.isClickable) {
                                                    clickSuccess = parentClickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                                    break
                                                }
                                                parentClickable = parentClickable.parent
                                            }
                                            clickSuccess
                                        }
                                        
                                        if (clicked) {
                                            Log.i(TAG, "Switch de dados móveis clicado com sucesso. Fechando tela em 1.5s...")
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                performGlobalAction(GLOBAL_ACTION_BACK)
                                                autoEnableMobileData = false
                                            }, 1500)
                                            return
                                        }
                                    } else {
                                        Log.i(TAG, "Dados móveis já estão ativados. Fechando tela...")
                                        performGlobalAction(GLOBAL_ACTION_BACK)
                                        autoEnableMobileData = false
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Auxiliar recursivo para encontrar um switch em um nó ou seus irmãos.
     */
    private fun findSwitchInNodeOrSiblings(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        
        // 1. Verificar se o próprio nó é um switch
        if (node.className?.toString()?.contains("Switch") == true || 
            node.className?.toString()?.contains("ToggleButton") == true ||
            node.className?.toString()?.contains("CheckBox") == true) {
            return node
        }
        
        // 2. Verificar os filhos recursivamente
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findSwitchInNodeOrSiblings(child)
            if (found != null) return found
        }
        
        // 3. Subir para o pai e verificar todos os irmãos
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i)
                if (sibling != null && sibling != node) {
                    if (sibling.className?.toString()?.contains("Switch") == true ||
                        sibling.className?.toString()?.contains("ToggleButton") == true) {
                        return sibling
                    }
                    val foundInSibling = findSwitchInNodeOrSiblings(sibling)
                    if (foundInSibling != null) return foundInSibling
                }
            }
        }
        
        return null
    }

    /**
     * Abre as configurações de rede do celular de forma a acionar a auto-ativação
     */
    fun triggerMobileDataActivation() {
        autoEnableMobileData = true
        val intents = listOf(
            android.content.Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.i(TAG, "Abrindo configurações de rede: ${intent.action}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao abrir ${intent.action}: ${e.message}")
            }
        }
        autoEnableMobileData = false
        Log.e(TAG, "Falha ao abrir qualquer tela de dados moveis.")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de Acessibilidade Interrompido")
    }

    /**
     * Injeta um clique simulado em coordenadas relativas (porcentagem 0.0 a 1.0)
     */
    fun injectClick(xPct: Float, yPct: Float): Boolean {
        val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        
        val x = xPct * realMetrics.widthPixels
        val y = yPct * realMetrics.heightPixels
        
        Log.d(TAG, "Injetando clique em X: $x, Y: $y (Relativo: $xPct, $yPct) [Resolution: ${realMetrics.widthPixels}x${realMetrics.heightPixels}]")

        val path = Path()
        path.moveTo(x, y)

        val gestureBuilder = GestureDescription.Builder()
        // Duração curta para simular um toque rápido (tap)
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))

        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Clique executado com sucesso")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Clique cancelado pelo sistema")
            }
        }, null)
    }

    /**
     * Injeta um gesto de arrastar/deslizar (swipe) de um ponto ao outro
     */
    fun injectSwipe(x1Pct: Float, y1Pct: Float, x2Pct: Float, y2Pct: Float, durationMs: Long = 300): Boolean {
        val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        
        val startX = x1Pct * realMetrics.widthPixels
        val startY = y1Pct * realMetrics.heightPixels
        val endX = x2Pct * realMetrics.widthPixels
        val endY = y2Pct * realMetrics.heightPixels

        Log.d(TAG, "Injetando swipe de ($startX, $startY) até ($endX, $endY) [Resolution: ${realMetrics.widthPixels}x${realMetrics.heightPixels}]")

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe executado com sucesso")
            }
        }, null)
    }

    /**
     * Executa ações físicas globais (Home, Back, Recents)
     */
    fun performAndroidAction(actionCode: String): Boolean {
        val action = when (actionCode.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        }
        Log.d(TAG, "Executando ação física global: $actionCode")
        return performGlobalAction(action)
    }
}
