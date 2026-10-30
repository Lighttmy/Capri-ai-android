package com.asistenteia.movil

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Objetos compartidos por los servicios y la pantalla principal. */
object Nucleo {
    @Volatile private var ajustesI: Ajustes? = null
    @Volatile private var herrI: Herramientas? = null
    @Volatile private var cerebroI: Cerebro? = null

    val hilos: ExecutorService = Executors.newCachedThreadPool()
    val principal = Handler(Looper.getMainLooper())

    fun ajustes(ctx: Context): Ajustes = ajustesI ?: synchronized(this) { ajustesI ?: Ajustes(ctx.applicationContext).also { ajustesI = it } }

    fun herramientas(ctx: Context): Herramientas =
        herrI ?: synchronized(this) { herrI ?: Herramientas(ctx.applicationContext, ajustes(ctx)).also { herrI = it } }

    fun cerebro(ctx: Context): Cerebro =
        cerebroI ?: synchronized(this) { cerebroI ?: Cerebro(ctx.applicationContext, ajustes(ctx), herramientas(ctx)).also { cerebroI = it } }
}
