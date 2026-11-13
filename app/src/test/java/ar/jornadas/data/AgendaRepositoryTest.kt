package ar.jornadas.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgendaRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<Archivo>()
    private val names = mutableListOf<String>()
    private val ficha = FichaRemota("11", "Compose", "Android", 1, "09:00", "Sur", "Ana", "Resumen")

    private fun abrir(nombre: String? = null): Archivo {
        val builder = if (nombre == null) Room.inMemoryDatabaseBuilder(context, Archivo::class.java)
            else Room.databaseBuilder(context, Archivo::class.java, nombre)
        return builder.addMigrations(Archivo.MIGRATION_1_2).build().also { databases.add(it) }
    }

    private fun repo(db: Archivo, respuesta: suspend () -> List<FichaRemota>) =
        AgendaRepository(object : ProgramaApi {
            override suspend fun consultar() = respuesta()
        }, db.cartelera())

    private suspend fun falla(bloque: suspend () -> Unit) {
        try {
            bloque()
            fail("Expected failed synchronization")
        } catch (_: IOException) {
            // Expected service failure.
        }
    }

    @After
    fun cerrar() {
        databases.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun failurePreservesCachedDetailsAndFavorites() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val online = repo(db) { listOf(ficha) }
        online.sincronizar()
        online.cambiarFavorita("11")
        val anterior = online.programa.first()
        val offline = repo(db) { throw IOException("Offline") }

        falla { offline.sincronizar() }

        assertEquals(anterior, offline.programa.first())
        assertTrue(anterior.valido)
        assertEquals("Resumen", anterior.charlas.single().resumen)
        assertTrue(anterior.charlas.single().favorita)
    }

    @Test
    fun neverDownloadedFailureIsDifferentFromSuccessfulEmptyEdition() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val offline = repo(db) { throw IOException("Offline") }
        assertEquals(ProgramaGuardado(emptyList(), false), offline.programa.first())

        falla { offline.sincronizar() }
        assertEquals(ProgramaGuardado(emptyList(), false), offline.programa.first())

        repo(db) { emptyList() }.sincronizar()
        assertEquals(ProgramaGuardado(emptyList(), true), offline.programa.first())
    }

    @Test
    fun successfulEmptyEditionClearsPreviousTalksAndSurvivesOfflineReopen() = runBlocking(Dispatchers.IO) {
        val nombre = "pedido41-empty.db".also { names.add(it) }
        val db = abrir(nombre)
        val online = repo(db) { listOf(ficha) }
        online.sincronizar()
        online.cambiarFavorita("11")
        repo(db) { emptyList() }.sincronizar()
        assertEquals(ProgramaGuardado(emptyList(), true), online.programa.first())
        db.close()

        val offline = repo(abrir(nombre)) { throw IOException("Offline") }
        assertEquals(ProgramaGuardado(emptyList(), true), offline.programa.first())
        falla { offline.sincronizar() }
        assertEquals(ProgramaGuardado(emptyList(), true), offline.programa.first())
    }

    @Test
    fun updatedTalkPreservesFavoriteByIdAndReplacesDetails() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val repository = repo(db) { listOf(ficha) }
        repository.sincronizar()
        repository.cambiarFavorita("11")

        repo(db) { listOf(ficha.copy(title = "Compose en produccion avanzada", room = "Sala Puerto")) }
            .sincronizar()

        assertEquals(ProgramaGuardado(listOf(Charla("11", "Compose en produccion avanzada", "Android", 1,
            "09:00", "Sala Puerto", "Ana", "Resumen", true)), true), repository.programa.first())
    }

    @Test
    fun nonemptySnapshotRemovesMissingTalksAndPreservesSurvivingFavorites() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val vigente = ficha.copy(id = "23")
        val repository = repo(db) { listOf(ficha, vigente) }
        repository.sincronizar()
        repository.cambiarFavorita("11")
        repository.cambiarFavorita("23")

        repo(db) { listOf(vigente, ficha.copy(id = "90")) }.sincronizar()

        val programa = repository.programa.first()
        assertTrue(programa.valido)
        assertEquals(setOf("23", "90"), programa.charlas.map { it.id }.toSet())
        assertEquals(listOf("23"), programa.charlas.filter { it.favorita }.map { it.id })
    }

    @Test
    fun removedTalkReturnsWithoutFavorite() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val repository = repo(db) { listOf(ficha) }
        repository.sincronizar()
        repository.cambiarFavorita("11")
        repo(db) { listOf(ficha.copy(id = "90")) }.sincronizar()

        repository.sincronizar()

        assertEquals(listOf("11"), repository.programa.first().charlas.map { it.id })
        assertFalse(repository.programa.first().charlas.single().favorita)
    }

    @Test
    fun favoriteAddedOrRemovedWhileNetworkIsPendingIsRespected() = runBlocking(Dispatchers.IO) {
        for (marcada in listOf(false, true)) {
            val db = abrir()
            val repository = repo(db) { listOf(ficha) }
            repository.sincronizar()
            if (marcada) repository.cambiarFavorita("11")
            val iniciada = CompletableDeferred<Unit>()
            val respuesta = CompletableDeferred<List<FichaRemota>>()
            val pendiente = repo(db) {
                iniciada.complete(Unit)
                respuesta.await()
            }
            val descarga = launch { pendiente.sincronizar() }

            iniciada.await()
            repository.cambiarFavorita("11")
            assertEquals(!marcada, repository.programa.first().charlas.single().favorita)
            respuesta.complete(listOf(ficha.copy(room = "Sala Puerto")))
            descarga.join()

            val charla = repository.programa.first().charlas.single()
            assertEquals(!marcada, charla.favorita)
            assertEquals("Sala Puerto", charla.sala)
        }
    }

    @Test
    fun validityWriteFailureRollsBackNonemptySnapshotAndPreservesFavorites() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val repository = repo(db) { listOf(ficha) }
        repository.sincronizar()
        repository.cambiarFavorita("11")
        val anterior = repository.programa.first()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_validity BEFORE INSERT ON edicion_guardada
            BEGIN SELECT RAISE(ABORT, 'test failure'); END""")

        try {
            repo(db) { listOf(ficha.copy(id = "90")) }.sincronizar()
            fail("Expected failed validity write")
        } catch (_: android.database.sqlite.SQLiteException) {
            // A failed replacement must restore the previous talks, favorites and validity.
        }
        assertEquals(anterior, repository.programa.first())
    }

    private fun crearVersion1(nombre: String, conCharla: Boolean) {
        names.add(nombre)
        context.openOrCreateDatabase(nombre, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE charlas (id TEXT NOT NULL PRIMARY KEY, titulo TEXT NOT NULL,
                tema TEXT NOT NULL, dia INTEGER NOT NULL, hora TEXT NOT NULL, sala TEXT NOT NULL,
                ponente TEXT NOT NULL, resumen TEXT NOT NULL, favorita INTEGER NOT NULL)""")
            if (conCharla) db.execSQL("INSERT INTO charlas VALUES ('11', 'Compose', 'Android', 1, '09:00', 'Sur', 'Ana', 'Resumen', 1)")
            db.version = 1
        }
    }

    @Test
    fun migrationPreservesVersion1RecordsAndFavoritesAndMarksEditionValid() = runBlocking(Dispatchers.IO) {
        val nombre = "pedido41-v1.db"
        crearVersion1(nombre, true)
        val offline = repo(abrir(nombre)) { throw IOException("Offline") }

        val migrado = offline.programa.first()
        assertEquals(ProgramaGuardado(listOf(Charla("11", "Compose", "Android", 1,
            "09:00", "Sur", "Ana", "Resumen", true)), true), migrado)
        falla { offline.sincronizar() }
        assertEquals(migrado, offline.programa.first())
    }

    @Test
    fun migrationDoesNotMarkEmptyVersion1DatabaseValid() = runBlocking(Dispatchers.IO) {
        val nombre = "pedido41-v1-empty.db"
        crearVersion1(nombre, false)
        val repository = repo(abrir(nombre)) { emptyList() }
        assertEquals(ProgramaGuardado(emptyList(), false), repository.programa.first())
    }

    @Test
    fun validityWriteFailureRollsBackInsertedTalks() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val repository = repo(db) { listOf(ficha) }
        assertEquals(ProgramaGuardado(emptyList(), false), repository.programa.first())
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_validity BEFORE INSERT ON edicion_guardada
            BEGIN SELECT RAISE(ABORT, 'test failure'); END""")

        try {
            repository.sincronizar()
            fail("Expected failed validity write")
        } catch (_: android.database.sqlite.SQLiteException) {
            // The transaction must roll back the talks as well as the marker.
        }
        assertEquals(ProgramaGuardado(emptyList(), false), repository.programa.first())
    }

    @Test
    fun validityWriteFailureRollsBackEmptyEditionAndPreservesFavorites() = runBlocking(Dispatchers.IO) {
        val db = abrir()
        val repository = repo(db) { listOf(ficha) }
        repository.sincronizar()
        repository.cambiarFavorita("11")
        val anterior = repository.programa.first()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_validity BEFORE INSERT ON edicion_guardada
            BEGIN SELECT RAISE(ABORT, 'test failure'); END""")

        try {
            repo(db) { emptyList() }.sincronizar()
            fail("Expected failed validity write")
        } catch (_: android.database.sqlite.SQLiteException) {
            // A failed empty commit must not delete the saved edition.
        }
        assertEquals(anterior, repository.programa.first())
    }
}
