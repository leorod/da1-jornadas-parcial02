package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import ar.jornadas.Servicios
import ar.jornadas.SesionDescargas
import ar.jornadas.data.Archivo
import ar.jornadas.data.FichaRemota
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProgramaModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val models = mutableListOf<ProgramaModel>()

    private fun modelo(estado: SavedStateHandle = SavedStateHandle()) =
        ProgramaModel(app, estado).also {
            store.put("modelo-${models.size}", it)
            models.add(it)
        }

    @After
    fun cerrar() {
        store.clear()
        models.take(1).forEach {
            val servicios = ReflectionHelpers.getField<Servicios>(it, "servicios")
            ReflectionHelpers.getField<Archivo>(servicios, "archivo").close()
        }
        ReflectionHelpers.setStaticField(Servicios::class.java, "instancia", null)
        app.deleteDatabase("cartelera.db")
    }

    @Test
    fun emptyStateDefaultsToBothDaysAndAllThemes() {
        val model = modelo()

        assertEquals(0, model.dia.value)
        assertEquals("Todos", model.tema.value)
    }

    @Test
    fun selectionsWriteSavedStateAndUpdateFlows() {
        val estado = SavedStateHandle()
        val model = modelo(estado)

        model.seleccionarDia(2)
        model.seleccionarTema("Datos")

        assertEquals(2, estado.get<Int>("dia"))
        assertEquals("Datos", estado.get<String>("tema"))
        assertEquals(2, model.dia.value)
        assertEquals("Datos", model.tema.value)

        model.seleccionarDia(0)
        model.seleccionarTema("Todos")

        assertEquals(0, estado.get<Int>("dia"))
        assertEquals("Todos", estado.get<String>("tema"))
        assertEquals(0, model.dia.value)
        assertEquals("Todos", model.tema.value)
    }

    @Test
    fun restoredStateInFreshModelKeepsBothFiltersAndMatchingFixtureIds() {
        val estado = SavedStateHandle()
        val original = modelo(estado)
        original.seleccionarDia(2)
        original.seleccionarTema("Datos")
        val guardado = estado.keys().associateWith { estado.get<Any?>(it) }
        store.clear()

        val restaurado = modelo(SavedStateHandle(guardado))

        assertEquals(2, restaurado.dia.value)
        assertEquals("Datos", restaurado.tema.value)
        val fichas = app.assets.open("programa-1.json").bufferedReader().use {
            Gson().fromJson(it, Array<FichaRemota>::class.java).toList()
        }
        val filtradas = fichas.filter {
            (restaurado.dia.value == 0 || it.day == restaurado.dia.value) &&
                (restaurado.tema.value == "Todos" || it.track == restaurado.tema.value)
        }
        assertEquals(listOf("23", "24"), filtradas.map { it.id })
        assertEquals(0, restaurado.mesa.llamadas.value)
    }

    @Test
    fun newTaskDoesNotInheritAnotherModelsFilters() {
        val anterior = modelo()
        anterior.seleccionarDia(2)
        anterior.seleccionarTema("Datos")

        val nuevo = modelo()

        assertSame(anterior.mesa, nuevo.mesa)
        assertSame(Servicios.obtener(app), Servicios.obtener(app))
        assertEquals(0, nuevo.mesa.llamadas.value)
        assertEquals(0, nuevo.dia.value)
        assertEquals("Todos", nuevo.tema.value)
        assertEquals(2, anterior.dia.value)
        assertEquals("Datos", anterior.tema.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun modelsShareAdmissionAndFiltersStayEditableWithoutOwningDownload() = runTest {
        var llamadas = 0
        val respuesta = CompletableDeferred<Unit>()
        val sesion = SesionDescargas(backgroundScope) { llamadas++; respuesta.await() }
        ReflectionHelpers.setField(Servicios.obtener(app), "descargas", sesion)
        val original = modelo()
        original.cargarAutomaticamente()
        runCurrent()

        original.seleccionarDia(2)
        original.seleccionarTema("Datos")
        assertEquals(2, original.dia.value)
        assertEquals("Datos", original.tema.value)
        store.clear()
        val nuevo = modelo()
        assertSame(original.mesa, nuevo.mesa)
        nuevo.cargarAutomaticamente()
        nuevo.recargar()
        runCurrent()
        assertTrue(sesion.estado.value.cargando)
        assertEquals(1, llamadas)

        respuesta.complete(Unit)
        runCurrent()
        assertFalse(sesion.estado.value.cargando)
        assertEquals(1, llamadas)
        nuevo.recargar()
        runCurrent()
        assertEquals(2, llamadas)
        assertEquals(0, nuevo.mesa.llamadas.value)
    }
}
