package ar.jornadas.data

import kotlinx.coroutines.flow.map

class AgendaRepository(private val api: ProgramaApi, private val dao: CarteleraDao) {
    val programa = dao.observar().map { filas ->
        ProgramaGuardado(filas.mapNotNull { it.charla }, filas.first().valido)
    }

    suspend fun sincronizar() {
        val fichas = api.consultar().map {
            Charla(it.id, it.title, it.track, it.day, it.time, it.room, it.speaker, it.summary)
        }
        dao.guardarEdicion(fichas)
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
