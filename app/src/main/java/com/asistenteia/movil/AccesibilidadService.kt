package com.asistenteia.movil

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Solo sirve para las «acciones globales» del sistema (inicio, atrás, recientes, notificaciones, bloquear,
 * captura). No lee el contenido de la pantalla: en su configuración no tiene permiso para ello.
 */
class AccesibilidadService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instancia === this) instancia = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun accion(nombre: String): String {
        val (global, texto) = when (nombre) {
            "inicio" -> GLOBAL_ACTION_HOME to "Inicio."
            "atras" -> GLOBAL_ACTION_BACK to "Atrás."
            "recientes" -> GLOBAL_ACTION_RECENTS to "Aplicaciones recientes."
            "notificaciones" -> GLOBAL_ACTION_NOTIFICATIONS to "Notificaciones abiertas."
            "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS to "Ajustes rápidos abiertos."
            "bloquear" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN to "Pantalla bloqueada." else return "Tu Android es demasiado antiguo para bloquear la pantalla así."
            "captura" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT to "Captura hecha." else return "Tu Android es demasiado antiguo para hacer capturas así."
            else -> return "No conozco esa acción del sistema."
        }
        return if (performGlobalAction(global)) texto else "Android no me dejó hacerlo."
    }

    companion object {
        @Volatile var instancia: AccesibilidadService? = null
    }
}
