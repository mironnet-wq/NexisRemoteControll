package com.nexis.remoteagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
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

        // Android 14: só confirma depois de "tela inteira" ficar selecionada.
        if (!prepareEntireScreen(nodesToSearch)) return false
        if (clickProjectionStart(nodesToSearch)) return true
        return clickSystemStart(nodesToSearch)
    }

    private val singleAppLabels = listOf(
        "Compartilhar um app", "Um único app", "Um app", "Um aplicativo", "Compartilhar um aplicativo",
        "A single app", "Single app", "Share one app", "One app"
    )

    private val entireScreenLabels = listOf(
        "Compartilhar a tela inteira", "A tela inteira", "Tela inteira", "Share entire screen", "Entire screen"
    )

    private fun nodesWith(roots: List<AccessibilityNodeInfo>, labels: List<String>): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            for (label in labels) {
                val found = root.findAccessibilityNodeInfosByText(label) ?: continue
                for (node in found) {
                    val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                    if (text.contains(label, ignoreCase = true) && out.none { it == node }) out.add(node)
                }
            }
        }
        return out
    }

    /**
     * A janela de captura do Android 14 tem um seletor "um app" / "tela inteira".
     * Com "um app" selecionado, "Iniciar" abre uma segunda tela de escolha de app e a
     * captura fica sem imagem. Aqui: lista aberta → escolhe "tela inteira"; seletor em
     * "um app" → abre a lista. Só devolve true quando pode clicar em Iniciar.
     */
    private fun prepareEntireScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        val single = nodesWith(roots, singleAppLabels)
        val entire = nodesWith(roots, entireScreenLabels)
        if (single.isNotEmpty() && entire.isNotEmpty()) {
            // Lista aberta: as duas opções aparecem juntas.
            for (node in entire) if (clickNode(node)) {
                Log.i(TAG, "Captura: opção tela inteira escolhida.")
                return false
            }
            return false
        }
        if (single.isNotEmpty()) {
            for (node in single) if (clickNode(node)) {
                Log.i(TAG, "Captura: abrindo o seletor para trocar 'um app' por 'tela inteira'.")
                return false
            }
            return false
        }
        return true
    }

    private fun chooseEntireScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        val labels = listOf(
            "Compartilhar a tela inteira",
            "Share entire screen",
            "Tela inteira",
            "Entire screen",
            "A tela inteira"
        )
        for (root in roots) {
            for (label in labels) {
                val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                for (node in nodes) {
                    val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                    if (!text.contains(label, ignoreCase = true)) continue
                    if (clickNode(node)) {
                        Log.i(TAG, "Tela inteira escolhida: '$text'")
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun clickProjectionStart(roots: List<AccessibilityNodeInfo>): Boolean {
        val labels = listOf(
            "Iniciar agora",
            "Começar agora",
            "Start now",
            "Começar",
            "Iniciar",
            "Start"
        )
        for (root in roots) {
            for (label in labels) {
                val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                for (node in nodes) {
                    val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                    if (!text.equals(label, ignoreCase = true)) continue
                    if (clickNode(node)) {
                        Log.i(TAG, "Prompt de captura aceito: '$text'")
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) return true
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 5) {
            if (parent.isClickable && parent.isEnabled &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            parent = parent.parent
            hops++
        }
        return false
    }

    private fun clickSystemStart(roots: List<AccessibilityNodeInfo>): Boolean {
        val hints = listOf("tela", "screen", "gravar", "transmitir", "compartilh", "record", "cast")
        for (root in roots) {
            val aboutCapture = hints.any { hint ->
                root.findAccessibilityNodeInfosByText(hint)?.isNotEmpty() == true
            }
            if (!aboutCapture) continue
            val buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1") ?: continue
            for (node in buttons) {
                val text = (node.text?.toString() ?: "").trim()
                if (text.equals("Cancelar", true) || text.equals("Cancel", true)) continue
                if (clickNode(node)) {
                    Log.i(TAG, "Prompt de captura aceito pelo botão do sistema: '$text'")
                    return true
                }
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val eventType = event.eventType
        val eventPackage = event.packageName?.toString() ?: ""

        // Modo usuário: launcher do sistema ou app fora da lista volta para a tela do coletor.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            KioskPolicy.guardForeground(this, eventPackage)
        }

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
            if (autoClickMediaProjection && prepareEntireScreen(nodesToSearch)) {
                if (clickProjectionStart(nodesToSearch) || clickSystemStart(nodesToSearch)) {
                    autoClickMediaProjection = false
                    return
                }
            }
            
            // 2. Só o diálogo de GPS. Não clicar em Permitir/OK nas configurações:
            // isso reabria a mesma tela de acessibilidade e impedia o Voltar.
            if ((eventPackage == "com.google.android.gms" || eventPackage == "android") &&
                nodesToSearch.any { windowMentionsLocation(it) }
            ) {
                val gpsAcceptTexts = listOf(
                    "Ativar",
                    "Concordo",
                    "Aceito",
                    "Agree",
                    "I agree"
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

    private fun windowMentionsLocation(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val hints = listOf("localiza", "gps", "location", "precis")
        for (hint in hints) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            if (nodes != null && nodes.isNotEmpty()) return true
        }
        return false
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
        when (actionCode.lowercase()) {
            "back" -> return performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> return performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> return performGlobalAction(GLOBAL_ACTION_RECENTS)
            "backspace" -> {
                NexisTyper.enqueue(this, { it.backspace() }) { replaceFocused(::backspaceText) }
                return true
            }
            "enter" -> {
                NexisTyper.enqueue(this, { it.enter() }) { pressEnter() }
                return true
            }
            "keyboard_show" -> return setKeyboard(true)
            "keyboard_hide" -> return setKeyboard(false)
            else -> return false
        }
    }

    fun typeText(value: String): Boolean {
        if (value.isEmpty()) return false
        NexisTyper.enqueue(this, { it.commit(value) }) { replaceFocused { text, start, end -> insertText(text, start, end, value) } }
        return true
    }

    private fun insertText(text: String, start: Int, end: Int, value: String): String {
        val base = if (text.equals("Senha", ignoreCase = true)) "" else text
        if (base.isEmpty()) return value
        val from = start.coerceIn(0, base.length)
        val to = end.coerceIn(from, base.length)
        return base.substring(0, from) + value + base.substring(to)
    }

    private fun backspaceText(text: String, start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from != to) return text.removeRange(from, to)
        if (from == 0) return text
        return text.removeRange(from - 1, from)
    }

    private fun replaceFocused(change: (String, Int, Int) -> String): Boolean {
        val node = focusedEditable() ?: return false
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val text = if (showingHint) "" else node.text?.toString().orEmpty()
        var start = if (showingHint) 0 else node.textSelectionStart
        var end = if (showingHint) 0 else node.textSelectionEnd
        if (start < 0 || end < 0) {
            start = text.length
            end = text.length
        }
        val next = change(text, start, end)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val cursor = Bundle()
            cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
            cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor)
        }
        node.recycle()
        return ok
    }

    private fun pressEnter(): Boolean {
        val node = focusedEditable() ?: return false
        val ok = if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(0x00200000)
        } else {
            false
        }
        if (!ok) {
            val text = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text + "\n")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        node.recycle()
        return true
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        focused?.recycle()
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun setKeyboard(show: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        softKeyboardController.showMode = if (show) {
            AccessibilityService.SHOW_MODE_AUTO
        } else {
            AccessibilityService.SHOW_MODE_HIDDEN
        }
        if (show) {
            allowImeWithHardKeyboard()
            // Sem campo focado o Android não abre o teclado: foca o primeiro campo da tela.
            val root = rootInActiveWindow
            val node = focusedEditable() ?: root?.let { anyEditable(it) }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
            } else {
                Log.i(TAG, "Teclado: nenhum campo de texto na tela.")
            }
        }
        return true
    }

    /** Primeiro campo editável visível, mesmo sem foco. */
    private fun anyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = anyEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /** MC33 tem teclado físico: sem esta opção o Android esconde o teclado da tela. */
    private fun allowImeWithHardKeyboard() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
            dpm.setSecureSetting(admin, "show_ime_with_hard_keyboard", "1")
        } catch (e: Exception) {
            Log.w(TAG, "Teclado com teclado físico: ${e.message}")
        }
    }
}
