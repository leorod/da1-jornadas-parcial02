package ar.jornadas

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SesionDescargasTest {
    @Test
    fun repeatedAutomaticRequestsExecuteOnlyOnce() = runTest {
        var llamadas = 0
        val sesion = SesionDescargas(backgroundScope) { llamadas++ }

        repeat(5) { sesion.cargarAutomaticamente() }
        assertTrue(sesion.estado.value.cargando)
        assertEquals(0, llamadas)
        runCurrent()
        repeat(5) { sesion.cargarAutomaticamente() }
        runCurrent()

        assertEquals(1, llamadas)
        assertEquals(EstadoDescarga(), sesion.estado.value)
    }

    @Test
    fun rapidExplicitRequestsReserveBeforeSchedulerAndBusyTapsNeverQueue() = runTest {
        var llamadas = 0
        val respuesta = CompletableDeferred<Unit>()
        val sesion = SesionDescargas(backgroundScope) {
            llamadas++
            respuesta.await()
        }

        sesion.recargar()
        sesion.recargar()
        runCurrent()
        repeat(5) { sesion.recargar() }
        runCurrent()
        assertEquals(1, llamadas)
        respuesta.complete(Unit)
        runCurrent()
        assertEquals(1, llamadas)
        assertFalse(sesion.estado.value.cargando)

        sesion.recargar()
        runCurrent()
        assertEquals(2, llamadas)
    }

    @Test
    fun automaticThenManualOverlapDoesNotRepeatAfterCompletion() = runTest {
        var llamadas = 0
        val respuesta = CompletableDeferred<Unit>()
        val sesion = SesionDescargas(backgroundScope) { llamadas++; respuesta.await() }

        sesion.cargarAutomaticamente()
        sesion.recargar()
        runCurrent()
        respuesta.complete(Unit)
        runCurrent()
        sesion.cargarAutomaticamente()
        runCurrent()

        assertEquals(1, llamadas)
    }

    @Test
    fun automaticDuringManualIsConsumedEvenBeforeSchedulerRuns() = runTest {
        var llamadas = 0
        val sesion = SesionDescargas(backgroundScope) { llamadas++ }

        sesion.recargar()
        sesion.cargarAutomaticamente()
        runCurrent()
        sesion.cargarAutomaticamente()
        runCurrent()
        assertEquals(1, llamadas)

        sesion.recargar()
        runCurrent()
        assertEquals(2, llamadas)
    }

    @Test
    fun automaticFailureStaysConsumedAndExplicitRetryClearsFailure() = runTest {
        var llamadas = 0
        val sesion = SesionDescargas(backgroundScope) {
            llamadas++
            if (llamadas == 1) throw IOException("Sin conexion")
        }

        sesion.cargarAutomaticamente()
        runCurrent()
        assertEquals(EstadoDescarga(fallo = true), sesion.estado.value)
        sesion.cargarAutomaticamente()
        runCurrent()
        assertEquals(1, llamadas)
        assertTrue(sesion.estado.value.fallo)

        sesion.recargar()
        assertEquals(EstadoDescarga(cargando = true), sesion.estado.value)
        runCurrent()
        assertEquals(2, llamadas)
        assertEquals(EstadoDescarga(), sesion.estado.value)
        sesion.recargar()
        runCurrent()
        assertEquals(3, llamadas)
    }

    @Test
    fun explicitFailureAllowsAnotherExplicitRequest() = runTest {
        var llamadas = 0
        val sesion = SesionDescargas(backgroundScope) { llamadas++; throw IOException() }

        repeat(2) {
            sesion.recargar()
            runCurrent()
            assertEquals(EstadoDescarga(fallo = true), sesion.estado.value)
        }
        assertEquals(2, llamadas)
    }

    @Test
    fun freshSessionAllowsAutomaticAndStartsWithFreshCounter() = runTest {
        var primero = 0
        var segundo = 0
        val anterior = SesionDescargas(backgroundScope) { primero++ }
        anterior.cargarAutomaticamente()
        runCurrent()
        val nueva = SesionDescargas(backgroundScope) { segundo++ }
        assertEquals(0, segundo)
        assertEquals(EstadoDescarga(), nueva.estado.value)
        nueva.cargarAutomaticamente()
        runCurrent()

        assertEquals(1, primero)
        assertEquals(1, segundo)
    }

    @Test
    fun immediateCompletionDoesNotLeaveAReservedJob() = runTest {
        var llamadas = 0
        val sesion = SesionDescargas(CoroutineScope(backgroundScope.coroutineContext +
            UnconfinedTestDispatcher(testScheduler))) { llamadas++ }

        repeat(3) {
            sesion.recargar()
            assertEquals(EstadoDescarga(), sesion.estado.value)
        }
        assertEquals(3, llamadas)
    }

    @Test
    fun cancellationPropagatesWithoutNetworkFailureAndAllowsRetry() = runTest {
        var llamadas = 0
        val cancelacion = CancellationException("Cancelada")
        val sesion = SesionDescargas(backgroundScope) {
            llamadas++
            if (llamadas == 1) throw cancelacion
        }

        sesion.recargar()
        runCurrent()
        assertEquals(EstadoDescarga(), sesion.estado.value)
        sesion.recargar()
        runCurrent()
        assertEquals(2, llamadas)
    }

    @Test
    fun scopeCancellationBeforeStartAndDuringWaitReleasesReservation() = runTest {
        for (comenzar in listOf(false, true)) {
            var llamadas = 0
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val sesion = SesionDescargas(scope) { llamadas++; CompletableDeferred<Unit>().await() }
            sesion.recargar()
            if (comenzar) runCurrent()
            scope.cancel()
            runCurrent()

            assertEquals(if (comenzar) 1 else 0, llamadas)
            assertEquals(EstadoDescarga(), sesion.estado.value)
            sesion.recargar()
            runCurrent()
            assertEquals(EstadoDescarga(), sesion.estado.value)
        }
    }

    @Test
    fun unexpectedDatabaseFailureIsNotMaskedAsNetworkFailure() = runTest {
        val error = IllegalStateException("Fallo de base")
        var recibido: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, causa -> recibido = causa })
        val sesion = SesionDescargas(scope) { throw error }

        try {
            sesion.recargar()
            runCurrent()
            assertSame(error, recibido)
            assertEquals(EstadoDescarga(), sesion.estado.value)
        } finally {
            scope.cancel()
        }
    }
}
