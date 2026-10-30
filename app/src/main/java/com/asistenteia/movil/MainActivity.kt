package com.asistenteia.movil

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pantalla única de configuración, construida por código (sin XML de diseño). */
class MainActivity : Activity() {
    private lateinit var aj: Ajustes
    private lateinit var raiz: LinearLayout
    private lateinit var estadoPermisos: LinearLayout
    private var swActivo: Switch? = null
    private var txtModelo: TextView? = null
    private val PROVEEDORES = listOf(
        "movil" to "IA en el propio móvil (sin clave ni internet)",
        "local" to "Sin IA: solo órdenes habituales",
        "gratis" to "Chat gratis online, sin clave (experimental)",
        "ollama" to "Ollama en tu PC (sin clave)",
        "claude" to "Claude (necesita clave de API)",
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        aj = Nucleo.ajustes(this)
        title = "Asistente IA"
        val sv = ScrollView(this)
        raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        sv.addView(raiz)
        setContentView(sv)
        construir()
        intent?.getStringExtra("probar")?.let { /* reservado */ }
    }

    override fun onResume() {
        super.onResume()
        pintarPermisos()
        swActivo?.isChecked = aj.activo
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(20), 0, dp(6))
        raiz.addView(this)
    }

    private fun nota(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.GRAY); raiz.addView(this) }

    private fun campo(etiqueta: String, valor: String, secreto: Boolean = false, guardar: (String) -> Unit): EditText {
        nota(etiqueta)
        val e = EditText(this).apply {
            setText(valor); setSingleLine(true)
            inputType = if (secreto) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            setOnFocusChangeListener { _, f -> if (!f) guardar(text.toString()) }
        }
        raiz.addView(e)
        return e
    }

    private fun boton(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() }; raiz.addView(this) }

    private fun construir() {
        raiz.removeAllViews()
        titulo("Asistente IA")
        swActivo = Switch(this).apply {
            text = "Escuchar la frase de activación"; isChecked = aj.activo
            setOnCheckedChangeListener { v, on ->
                if (!v.isPressed) return@setOnCheckedChangeListener
                if (on) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        v.isChecked = false; pedirMicro(); return@setOnCheckedChangeListener
                    }
                    guardarTodo(); AsistenteService.iniciar(this@MainActivity)
                } else AsistenteService.parar(this@MainActivity)
            }
            raiz.addView(this)
        }

        titulo("Permisos")
        nota("Pulsa cada uno que quieras usar. Lo que no concedas simplemente no funcionará.")
        estadoPermisos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(estadoPermisos)

        titulo("Cerebro")
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PROVEEDORES.map { it.second })
        sp.setSelection(PROVEEDORES.indexOfFirst { it.first == aj.proveedor }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { aj.proveedor = PROVEEDORES[pos].first }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        raiz.addView(sp)
        nota("«Sin IA» no necesita nada: entiende las órdenes habituales y funciona sin internet. Los otros modos añaden conversación libre y redactar respuestas a mensajes.")
        nota("IA en el móvil: descarga una vez un modelo pequeño (.task o .litertlm de MediaPipe/LiteRT, p. ej. Qwen2.5 0.5B o Gemma 3 1B de la comunidad «litert-community» en Hugging Face). Después funciona sin internet y sin clave. Necesita un móvil con 4 GB de RAM o más.")
        txtModelo = TextView(this).apply { raiz.addView(this) }
        pintarModelo()
        val fam = Spinner(this)
        val fams = listOf("chatml" to "Plantilla: Qwen / ChatML", "gemma" to "Plantilla: Gemma", "llama" to "Plantilla: Llama 3")
        fam.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fams.map { it.second })
        fam.setSelection(fams.indexOfFirst { it.first == aj.familiaModelo }.coerceAtLeast(0))
        fam.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { aj.familiaModelo = fams[pos].first }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        raiz.addView(fam)
        val urlCampo = campo("Dirección https del modelo (Hugging Face: enlace «resolve/main/…task»)", aj.urlModelo) { aj.urlModelo = it }
        boton("Descargar modelo desde la dirección") {
            val u = urlCampo.text.toString().trim(); aj.urlModelo = u
            if (u.isEmpty()) { Toast.makeText(this, "Pega una dirección https", Toast.LENGTH_SHORT).show(); return@boton }
            if (u.contains("gemma", true)) aj.familiaModelo = "gemma" else if (u.contains("llama", true)) aj.familiaModelo = "llama" else aj.familiaModelo = "chatml"
            txtModelo?.text = "Descargando…"
            Nucleo.hilos.execute {
                val r = runCatching { IaMovil.descargar(this, u) { h, t -> runOnUiThread { txtModelo?.text = "Descargando… ${h / 1_000_000} MB" + if (t > 0) " de ${t / 1_000_000} MB" else "" } } }
                runOnUiThread { pintarModelo(r.exceptionOrNull()?.message) }
            }
        }
        boton("Elegir un archivo de modelo del móvil") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 77)
        }
        boton("Borrar el modelo") { IaMovil.borrar(this); pintarModelo() }
        campo("Clave de la API de Claude (solo si eliges Claude)", aj.claveApi, true) { aj.claveApi = it }
        campo("Modelo de Claude", aj.modeloClaude) { aj.modeloClaude = it }
        campo("Dirección de Ollama (IP del PC en tu wifi)", aj.urlOllama) { aj.urlOllama = it }
        campo("Modelo de Ollama", aj.modeloOllama) { aj.modeloOllama = it }

        titulo("Voz")
        campo("Frase de activación", aj.frase) { if (it.isNotBlank()) aj.frase = it }
        Switch(this).apply {
            text = "Silenciar los pitidos del micrófono"; isChecked = aj.silenciarPitidos
            setOnCheckedChangeListener { _, on -> aj.silenciarPitidos = on }
            raiz.addView(this)
        }

        titulo("Sobre ti")
        campo("Tu nombre", aj.nombre) { aj.nombre = it }
        campo("Cómo quieres que te llame", aj.apodo) { aj.apodo = it }
        campo("Tus gustos (opcional)", aj.gustos) { aj.gustos = it }

        titulo("Responder mensajes por ti")
        nota("Por seguridad todo empieza en «No». «Borrador»: te llega una notificación y tú pulsas Enviar. «Automático»: contesta solo (máx. 3 por hora y conversación; nunca si habla de dinero, claves o urgencias).")
        for ((paq, nombre) in APPS_MENSAJERIA) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.addView(TextView(this).apply { text = nombre }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val s = Spinner(this)
            val opc = listOf("No", "Borrador", "Automático")
            s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opc)
            s.setSelection(aj.modoDe(paq).ordinal)
            s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { aj.ponerModo(paq, ModoRespuesta.values()[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            fila.addView(s)
            raiz.addView(fila)
        }
        Switch(this).apply {
            text = "Permitir también grupos"; isChecked = aj.permitirGrupos
            setOnCheckedChangeListener { _, on -> aj.permitirGrupos = on }
            raiz.addView(this)
        }
        Switch(this).apply {
            text = "Avisar de que responde una IA «(Respuesta automática)»"; isChecked = aj.avisarIA
            setOnCheckedChangeListener { _, on -> aj.avisarIA = on }
            raiz.addView(this)
        }
        campo("Estilo de tus respuestas", aj.estiloRespuesta) { aj.estiloRespuesta = it }

        titulo("Probar")
        boton("Ver la cara") {
            if (!Settings.canDrawOverlays(this)) { pedirOverlay(); return@boton }
            guardarTodo(); AsistenteService.iniciar(this, AsistenteService.ACCION_ROSTRO)
        }
        val prueba = campo("Escribe una orden (sin hablar)", "") {}
        boton("Enviar orden") {
            val t = prueba.text.toString().trim()
            if (t.isEmpty()) return@boton
            guardarTodo()
            AsistenteService.iniciar(this, AsistenteService.ACCION_ORDEN, t)
            Toast.makeText(this, "Enviado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pintarModelo(error: String? = null) {
        val f = IaMovil.archivoModelo(this)
        txtModelo?.text = if (error != null) "⚠️ $error" else if (IaMovil.instalado(this)) "✅ Modelo instalado (${f.length() / 1_000_000} MB)" else "⬜ Sin modelo instalado"
    }

    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        super.onActivityResult(c, r, d)
        if (c != 77 || r != RESULT_OK) return
        val uri = d?.data ?: return
        val nombre = uri.lastPathSegment.orEmpty()
        if (nombre.contains("gemma", true)) aj.familiaModelo = "gemma" else if (nombre.contains("llama", true)) aj.familiaModelo = "llama" else aj.familiaModelo = "chatml"
        txtModelo?.text = "Copiando el modelo…"
        Nucleo.hilos.execute {
            val e = runCatching { IaMovil.importar(this, uri, {}, { n -> runOnUiThread { txtModelo?.text = "Copiando… ${n / 1_000_000} MB" } }) }
            runOnUiThread { pintarModelo(e.exceptionOrNull()?.message) }
        }
    }

    private fun guardarTodo() { currentFocus?.clearFocus() }

    // ---- permisos

    private fun fila(texto: String, ok: Boolean, accion: () -> Unit) {
        val bt = Button(this).apply {
            isAllCaps = false
            text = (if (ok) "✅ " else "⬜ ") + texto
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { accion() }
        }
        estadoPermisos.addView(bt)
    }

    private fun pintarPermisos() {
        if (!::estadoPermisos.isInitialized) return
        estadoPermisos.removeAllViews()
        fun tiene(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        fila("Micrófono", tiene(Manifest.permission.RECORD_AUDIO)) { pedirMicro() }
        fila("Mostrar la cara sobre otras apps", Settings.canDrawOverlays(this)) { pedirOverlay() }
        if (Build.VERSION.SDK_INT >= 33) fila("Notificaciones", tiene(Manifest.permission.POST_NOTIFICATIONS)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
        fila("Cambiar el brillo", Settings.System.canWrite(this)) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        val escuchaNotif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().contains(packageName)
        fila("Leer y contestar mensajes (acceso a notificaciones)", escuchaNotif) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(packageName)
        fila("Acciones del sistema: inicio, atrás, bloquear… (accesibilidad)", acc) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        fila("Contactos y SMS (llamar y enviar SMS por nombre)", tiene(Manifest.permission.READ_CONTACTS) && tiene(Manifest.permission.SEND_SMS)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS), 3)
        }
        val ahorro = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        fila("Sin ahorro de batería (para que siga escuchando)", ahorro) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun pedirMicro() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    private fun pedirOverlay() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        pintarPermisos()
    }
}
