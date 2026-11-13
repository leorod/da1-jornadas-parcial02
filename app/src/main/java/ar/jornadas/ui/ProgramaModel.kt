package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import ar.jornadas.data.ProgramaGuardado
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProgramaModel(app: Application, private val estado: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = Servicios.obtener(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val programa = repo.programa.stateIn<ProgramaGuardado?>(viewModelScope, SharingStarted.Eagerly, null)
    val dia = estado.getStateFlow("dia", 0)
    val tema = estado.getStateFlow("tema", "Todos")
    private val descargas = servicios.descargas
    val aviso = descargas.estado.map { if (it.fallo) "No se pudo consultar el programa" else "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            if (descargas.estado.value.fallo) "No se pudo consultar el programa" else "")
    val cargando = descargas.estado.map { it.cargando }
        .stateIn(viewModelScope, SharingStarted.Eagerly, descargas.estado.value.cargando)

    fun seleccionarDia(dia: Int) { estado["dia"] = dia }
    fun seleccionarTema(tema: String) { estado["tema"] = tema }

    fun cargarAutomaticamente() { descargas.cargarAutomaticamente() }
    fun recargar() { descargas.recargar() }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
