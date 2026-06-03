package com.uasd.main

import com.google.firebase.database.DataSnapshot
import java.io.Serializable

data class Materia(
    val nombre: String = "",
    val idLibroGoogle: String = ""
) : Serializable

data class Seccion(
    val nrc: String = "",
    val codigoMateria: String = "",
    val nombreMateria: String = "", // Agregado para facilitar la UI
    val claveSeccion: String = "",
    val creditos: Double = 0.0,
    val periodo: String = "",
    val conextras: Int = 1,
    val conparticipacion: Int = 0,
    val linkLista: String = "",
    val updatedAt: Long = 0,
    var encuentros: List<Encuentro>? = null // Agregado para resaltar clase actual
) : Serializable

data class Estudiante(
    val matricula: String = "",
    val nombre: String = "",
    val id_estudiante: String = ""
) : Serializable

data class Encuentro(
    val hora: String = "",
    val dias: String = "",
    val aula: String = ""
) : Serializable

data class Evaluacion(
    val id: String = "",
    val nombre: String = "",
    val valor: Int = 0,
    val esExtra: Int = 0
) : Serializable

/** Nota almacenada en Firebase: `notas/{evalId}/{matricula}` */
data class NotaRegistro(
    val nota: Double = 0.0,
    val updatedAt: Long = 0L
) {
    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): NotaRegistro? {
            // Firebase throws DatabaseException (not null) when the stored value
            // is a primitive (Long/Double) but we ask for a complex object.
            // Wrap in try/catch so we can fall back to legacy numeric format.
            try {
                snapshot.getValue(NotaRegistro::class.java)?.let { return it }
            } catch (_: Exception) { /* fall through to numeric fallbacks */ }
            snapshot.getValue(Double::class.java)?.let { return NotaRegistro(it, 0L) }
            (snapshot.value as? Number)?.toDouble()?.let { return NotaRegistro(it, 0L) }
            return null
        }
    }
}

enum class OrdenListaEstudiantes {
    NOMBRE,
    FECHA_EDICION
}

data class GradableEstudiante(
    val matricula: String = "",
    val nombre: String = "",
    var nota: Double = 0.0,
    var esTotal: Boolean = false, // Para saber si estamos mostrando el acumulado
    var observacion: String? = null, // Texto de la nota adhesiva
    var dictationSuggestedGrade: Double? = null, // Nota sugerida por voz
    var notaUpdatedAt: Long = 0L,
    var ordenCaptura: Int = 0 // Posición en el orden de captura (1 = primero calificado)
) : Serializable

data class SeccionEncuentro(
    val nrc: String = "",
    val nombreMateria: String = "",
    val hora: String = "",
    val aula: String = "",
    val seccion: Seccion? = null
) : Serializable

data class AsistenciaSesion(
    val id: String = "",
    val nombre: String = "",
    val fecha: String = ""
) : Serializable
