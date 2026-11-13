package ar.jornadas

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EstadoDescarga(val cargando: Boolean = false, val fallo: Boolean = false)

class SesionDescargas(private val scope: CoroutineScope, private val descargar: suspend () -> Unit) {
    private var automaticaConsumida = false
    private var enCurso: Job? = null
    private val mutableEstado = MutableStateFlow(EstadoDescarga())
    val estado = mutableEstado.asStateFlow()

    fun cargarAutomaticamente() { solicitar(automatica = true) }
    fun recargar() { solicitar(automatica = false) }

    private fun solicitar(automatica: Boolean) {
        val trabajo = synchronized(this) {
            if (automatica) {
                if (automaticaConsumida) return
                automaticaConsumida = true
            }
            if (enCurso != null) return
            var fallo = false
            // Reserve before starting, including with an immediate dispatcher.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    descargar()
                } catch (e: IOException) {
                    synchronized(this@SesionDescargas) { fallo = true }
                }
            }
            enCurso = job
            mutableEstado.value = EstadoDescarga(cargando = true)
            // Also runs when cancellation prevents the coroutine body from starting.
            job.invokeOnCompletion {
                synchronized(this) {
                    if (enCurso === job) {
                        enCurso = null
                        mutableEstado.value = EstadoDescarga(fallo = fallo)
                    }
                }
            }
            job
        }
        trabajo.start()
    }
}
