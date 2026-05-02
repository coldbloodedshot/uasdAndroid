package com.uasd.main

data class Materia(
    val nombre: String = "",
    val idLibroGoogle: String = ""
)

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
)

data class Estudiante(
    val matricula: String = "",
    val nombre: String = "",
    val id_estudiante: String = ""
)

data class Encuentro(
    val hora: String = "",
    val dias: String = "",
    val aula: String = ""
)

data class Evaluacion(
    val id: String = "",
    val nombre: String = "",
    val valor: Int = 0,
    val esExtra: Int = 0
)

data class GradableEstudiante(
    val matricula: String = "",
    val nombre: String = "",
    var nota: Double = 0.0,
    var esTotal: Boolean = false, // Para saber si estamos mostrando el acumulado
    var observacion: String? = null // Texto de la nota adhesiva
)

data class SeccionEncuentro(
    val nrc: String = "",
    val nombreMateria: String = "",
    val hora: String = "",
    val aula: String = ""
)

data class AsistenciaSesion(
    val id: String = "",
    val nombre: String = "",
    val fecha: String = ""
)
