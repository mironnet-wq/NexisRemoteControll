package com.nexis.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Tela inicial do modo usuário e o ajuste do modo admin no próprio coletor. */
class KioskActivity : AppCompatActivity() {

    private var watch: BroadcastReceiver? = null
    private var entering = false
    private var codeField: EditText? = null
    private var screen = "home"

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val field = codeField
        if (field == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && !field.hasFocus()) field.requestFocus()
        if (super.dispatchKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            val text = field.text
            if (!text.isNullOrEmpty()) text.delete(text.length - 1, text.length)
            return true
        }
        val digit = when (event.keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
            else -> 0.toChar()
        }
        if (digit != 0.toChar()) {
            field.text?.append(digit)
            return true
        }
        val ch = event.unicodeChar
        if (ch >= 32) {
            field.text?.append(ch.toChar())
            return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowTyping()
        watch = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                render()
            }
        }
        @Suppress("DEPRECATION")
        registerReceiver(watch, IntentFilter(KioskPolicy.ACTION_CHANGED))
        if (intent.getStringExtra("gate") == "password" && KioskPolicy.mode(this) != "admin") {
            showPassword()
        } else {
            render()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("gate") == "password" && KioskPolicy.mode(this) != "admin") {
            showPassword()
        }
    }

    override fun onDestroy() {
        infoHandler.removeCallbacks(infoTick)
        watch?.let { runCatching { unregisterReceiver(it) } }
        watch = null
        super.onDestroy()
    }

    override fun onPause() {
        infoHandler.removeCallbacks(infoTick)
        hideIme()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        runCatching { stopLockTask() }
        if (KioskPolicy.mode(this) == "user") KioskPolicy.holdRadios(this)
        if (screen == "info") {
            infoHandler.removeCallbacks(infoTick)
            infoHandler.post(infoTick)
        }
    }

    private fun render() {
        if (entering) return
        val mode = KioskPolicy.mode(this)
        // Aviso de mudança (Wi-Fi, inventário...) não tira o usuário do menu nem das informações.
        if ((screen == "info" || screen == "menu") && mode != "off") return
        codeField = null
        if (mode == "off") {
            runCatching { stopLockTask() }
            finish()
            return
        }
        if (mode == "admin") showAdmin() else showHome()
    }

    private fun showHome() {
        stopInfoRefresh()
        screen = "home"
        codeField = null
        hideIme()
        val root = column(dp(8))
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = title("Tela do coletor")
        heading.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val gear = TextView(this).apply {
            text = "\u2699"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(4), dp(4))
            contentDescription = "Opções"
            setOnClickListener { showGearMenu() }
        }
        head.addView(heading)
        head.addView(gear)
        root.addView(head)
        val chosen = KioskPolicy.packages(this).toSet()
        val apps = KioskPolicy.launchers(this).filter { it.packageName in chosen }
        if (apps.isEmpty()) {
            root.addView(hint("Nenhum aplicativo na tela inicial."))
        } else {
            val grid = GridLayout(this).apply {
                columnCount = 4
                alignmentMode = GridLayout.ALIGN_BOUNDS
            }
            val cellW = (resources.displayMetrics.widthPixels - dp(16)) / 4
            val cellH = (resources.displayMetrics.heightPixels - dp(56)) / 5
            for (app in apps) {
                grid.addView(appButton(app.packageName, app.label, cellW, cellH))
            }
            root.addView(grid)
        }
        setContentView(scroll(root))
    }

    /** Engrenagem da tela do coletor: informações do aparelho ou entrada no modo admin. */
    private fun showGearMenu() {
        stopInfoRefresh()
        codeField = null
        hideIme()
        screen = "menu"
        val root = column(dp(16))
        root.addView(title("Opções"))
        val info = button("Informações do coletor")
        info.setOnClickListener { showDeviceInfo() }
        val admin = button("Modo admin")
        admin.setOnClickListener { showPassword() }
        val back = outlineButton("Voltar")
        back.setOnClickListener { backToStart() }
        root.addView(info)
        root.addView(admin)
        root.addView(back)
        setContentView(scroll(root))
    }

    private val infoHandler = Handler(Looper.getMainLooper())
    private val infoTick = object : Runnable {
        override fun run() {
            if (screen != "info") return
            fillDeviceInfo()
            infoHandler.postDelayed(this, 3000)
        }
    }
    private var infoBox: LinearLayout? = null

    private fun stopInfoRefresh() {
        infoHandler.removeCallbacks(infoTick)
        infoBox = null
    }

    /** IP, MAC, Wi-Fi, sinal e dados do coletor. Atualiza a cada 3 s enquanto aberta. */
    private fun showDeviceInfo() {
        stopInfoRefresh()
        codeField = null
        hideIme()
        screen = "info"
        val root = column(dp(16))
        root.addView(title("Informações do coletor"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoBox = box
        root.addView(box)
        val refresh = button("Atualizar")
        refresh.setOnClickListener { fillDeviceInfo() }
        val back = outlineButton("Voltar")
        back.setOnClickListener {
            stopInfoRefresh()
            showGearMenu()
        }
        root.addView(refresh)
        root.addView(back)
        setContentView(scroll(root))
        fillDeviceInfo()
        infoHandler.postDelayed(infoTick, 3000)
    }

    private fun fillDeviceInfo() {
        val box = infoBox ?: return
        box.removeAllViews()
        val svc = RemoteControlService.instance?.infoSnapshot().orEmpty()
        val wifi = DeviceInfo.wifi(this)

        section(box, "Rede")
        row(box, "IP", svc["ip"].orEmpty().ifEmpty { wifi.ip }.ifEmpty { "sem IP" })
        row(box, "MAC", svc["mac"].orEmpty().ifEmpty { "não disponível" })
        row(box, "Gateway", wifi.gateway.ifEmpty { "—" })

        section(box, "Wi-Fi")
        if (!wifi.connected) {
            row(box, "Status", "Desconectado", Color.parseColor("#F87171"))
        } else {
            row(box, "Rede (SSID)", wifi.ssid.ifEmpty { "oculta / sem permissão" })
            row(box, "Ponto de acesso", wifi.bssid.ifEmpty { "—" })
            signalRow(box, wifi.rssi)
            row(box, "Velocidade", if (wifi.linkMbps > 0) "${wifi.linkMbps} Mbps" else "—")
            row(box, "Frequência", wifi.bandLabel())
        }

        section(box, "Coletor")
        row(box, "Modelo", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        row(box, "Série", svc["serial"].orEmpty().ifEmpty { "não disponível" })
        row(box, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        row(box, "Bateria", DeviceInfo.battery(this))
        row(box, "App Nexis", BuildConfig.VERSION_NAME)
        row(box, "ID no Nexis", svc["device_id"].orEmpty().ifEmpty { "—" })
        val online = svc["online"] == "true"
        row(
            box,
            "Servidor",
            if (RemoteControlService.instance == null) "serviço parado" else if (online) "conectado" else "reconectando…",
            Color.parseColor(if (online) "#4ADE80" else "#FBBF24")
        )
        row(box, "Modo", when (KioskPolicy.mode(this)) {
            "user" -> "usuário (quiosque)"
            "admin" -> "admin"
            else -> "desligado"
        })
    }

    private fun section(box: LinearLayout, text: String) {
        box.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun row(box: LinearLayout, label: String, value: String, valueColor: Int = Color.WHITE) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val lab = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f)
        }
        val v = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(valueColor)
            typeface = Typeface.DEFAULT_BOLD
            setTextIsSelectable(false)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f)
        }
        line.addView(lab)
        line.addView(v)
        box.addView(line)
    }

    /** Sinal em dBm, rótulo e barra de 0 a 100%. */
    private fun signalRow(box: LinearLayout, rssi: Int) {
        val q = DeviceInfo.quality(rssi)
        row(box, "Sinal", "${q.label} · $rssi dBm · ${q.percent}%", q.color)
        val track = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                bottomMargin = dp(6)
            }
        }
        val fill = android.view.View(this).apply {
            setBackgroundColor(q.color)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, q.percent.toFloat())
        }
        val rest = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - q.percent).toFloat())
        }
        track.addView(fill)
        track.addView(rest)
        box.addView(track)
    }

    /** Volta para a tela do modo atual (home do quiosque ou painel admin). */
    private fun backToStart() {
        stopInfoRefresh()
        entering = false
        codeField = null
        when (KioskPolicy.mode(this)) {
            "admin" -> showAdmin()
            "off" -> finish()
            else -> showHome()
        }
    }

    @Deprecated("Back do sistema: sub-telas voltam; a tela inicial do quiosque não sai.")
    override fun onBackPressed() {
        when (screen) {
            "info" -> showGearMenu()
            "menu", "password" -> backToStart()
            "editor", "pass_change" -> showAdmin()
            "admin" -> { /* fica no painel admin */ }
            "home" -> { /* quiosque não sai pelo Voltar */ }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun showAdmin() {
        stopInfoRefresh()
        screen = "admin"
        codeField = null
        hideIme()
        val root = column(dp(16))
        root.addView(title("Modo admin"))
        root.addView(hint("Volta sozinho para o modo usuário em 2 minutos."))
        val info = button("Informações do coletor")
        info.setOnClickListener { showDeviceInfo() }
        root.addView(info)
        val edit = button("Editar apps da home")
        edit.setOnClickListener { showAppEditor() }
        val pass = button("Trocar senha")
        pass.setOnClickListener { showPasswordChange() }
        val logout = button("Admin Logout")
        logout.setOnClickListener {
            KioskPolicy.setLocal(this, "user", KioskPolicy.packages(this))
        }
        root.addView(edit)
        root.addView(pass)
        root.addView(logout)
        setContentView(scroll(root))
    }

    private fun showAppEditor() {
        screen = "editor"
        val root = column(dp(16))
        root.addView(title("Apps da home"))
        root.addView(hint("Marque o que deve aparecer na tela inicial."))
        val selected = KioskPolicy.packages(this).toSet()
        val checks = mutableListOf<CheckBox>()
        for (app in KioskPolicy.launchers(this)) {
            val box = CheckBox(this).apply {
                text = app.label
                isChecked = app.packageName in selected
                setTextColor(Color.WHITE)
                tag = app.packageName
            }
            checks.add(box)
            root.addView(box)
        }
        val save = button("Salvar")
        save.setOnClickListener {
            val picked = checks.filter { it.isChecked }.map { it.tag as String }
            KioskPolicy.setLocal(this, "admin", picked)
            toast("Tela inicial salva.")
            showAdmin()
        }
        val back = button("Voltar")
        back.setOnClickListener { showAdmin() }
        root.addView(save)
        root.addView(back)
        setContentView(scroll(root))
    }

    private fun showPassword() {
        stopInfoRefresh()
        screen = "password"
        entering = true
        val root = column(dp(20))
        root.addView(title("Modo admin"))
        root.addView(hint("Digite o código neste campo. O teclado numérico do coletor também vale."))
        val input = passwordField()
        codeField = input
        val enter = button("Entrar")
        val submit = {
            if (KioskPolicy.passwordOk(this, input.text?.toString().orEmpty())) {
                entering = false
                hideKeyboard(input)
                KioskPolicy.setLocal(this, "admin", KioskPolicy.packages(this))
                showAdmin()
            } else {
                toast("Senha incorreta.")
            }
        }
        enter.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (enter && event.action != android.view.KeyEvent.ACTION_UP) return@setOnEditorActionListener true
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) submit()
            actionId == EditorInfo.IME_ACTION_DONE || enter
        }
        val cancel = button("Cancelar")
        cancel.setOnClickListener {
            hideKeyboard(input)
            backToStart()
        }
        root.addView(input)
        root.addView(enter)
        root.addView(cancel)
        setContentView(scroll(root))
        focusField(input)
    }

    private fun showPasswordChange() {
        screen = "pass_change"
        val root = column(dp(20))
        root.addView(title("Trocar senha"))
        root.addView(hint("A nova senha fica neste coletor."))
        val input = passwordField()
        codeField = input
        val save = button("Salvar")
        save.setOnClickListener {
            if (KioskPolicy.savePassword(this, input.text?.toString().orEmpty())) {
                hideKeyboard(input)
                toast("Senha salva neste coletor.")
                showAdmin()
            } else {
                toast("Use entre 4 e 64 caracteres.")
            }
        }
        val back = button("Voltar")
        back.setOnClickListener {
            hideKeyboard(input)
            showAdmin()
        }
        root.addView(input)
        root.addView(save)
        root.addView(back)
        setContentView(scroll(root))
        focusField(input)
    }

    private fun passwordField(): EditText {
        return EditText(this).apply {
            hint = ""
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#0F172A"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 22f
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = object : PasswordTransformationMethod() {
                override fun getTransformation(source: CharSequence, view: android.view.View): CharSequence {
                    return object : CharSequence {
                        override val length: Int get() = source.length
                        override fun get(index: Int): Char = '\u2022'
                        override fun subSequence(startIndex: Int, endIndex: Int) = this
                    }
                }
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(14), dp(12), dp(14))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            params.bottomMargin = dp(8)
            layoutParams = params
            setOnClickListener { openKeyboard(this) }
            setOnFocusChangeListener { view, focused ->
                if (focused) openKeyboard(view as EditText)
            }
        }
    }

    private fun allowTyping() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
        runCatching {
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setSecureSetting(admin, "show_ime_with_hard_keyboard", "1")
            }
        }
        val app = android.os.Bundle()
        app.putString("PACKAGE_NAME", packageName)
        app.putStringArray("ACTIVITY_LIST", arrayOf("com.nexis.remoteagent.KioskActivity"))
        val keys = android.os.Bundle()
        keys.putString("keystroke_output_enabled", "true")
        val plugin = android.os.Bundle()
        plugin.putString("PLUGIN_NAME", "KEYSTROKE")
        plugin.putString("RESET_CONFIG", "true")
        plugin.putBundle("PARAM_LIST", keys)
        val profile = android.os.Bundle()
        profile.putString("PROFILE_NAME", "NexisKiosk")
        profile.putString("PROFILE_ENABLED", "true")
        profile.putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
        profile.putParcelableArray("APP_LIST", arrayOf(app))
        profile.putBundle("PLUGIN_CONFIG", plugin)
        val intent = Intent("com.symbol.datawedge.api.ACTION")
        intent.putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
        sendBroadcast(intent)
    }

    private fun openKeyboard(input: EditText) {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun focusField(input: EditText) {
        input.post { openKeyboard(input) }
    }

    private fun hideKeyboard(input: EditText) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun appButton(packageName: String, label: String, cellW: Int, cellH: Int): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            val params = GridLayout.LayoutParams()
            params.width = cellW
            params.height = cellH
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1)
            params.setMargins(0, dp(2), 0, dp(2))
            layoutParams = params
            setOnClickListener {
                val launch = KioskPolicy.launchIntent(this@KioskActivity, packageName)
                if (launch == null) toast("Aplicativo indisponível.")
                else startActivity(launch)
            }
        }
        val icon = ImageView(this).apply {
            setImageDrawable(iconOf(packageName))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val name = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        cell.addView(icon)
        cell.addView(name)
        return cell
    }

    private fun iconOf(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    private fun scroll(child: LinearLayout): ScrollView {
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(child)
        }
    }

    private fun column(pad: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(12))
        }
    }

    private fun hint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun button(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6750A4"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            layoutParams = params
        }
    }

    private fun outlineButton(text: String): Button {
        return button(text).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#CBD5E1"))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
