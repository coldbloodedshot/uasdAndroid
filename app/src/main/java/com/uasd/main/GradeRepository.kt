package com.uasd.main

import android.util.Log
import com.google.firebase.database.*

class GradeRepository(private val database: DatabaseReference) {

    fun getEstudiantesRef(nrc: String): DatabaseReference {
        return database.child("seccion_detalles").child(nrc).child("estudiantes")
    }

    fun getEvaluacionesRef(nrc: String): DatabaseReference {
        return database.child("seccion_detalles").child(nrc).child("evaluaciones")
    }

    fun getNotasRef(nrc: String): DatabaseReference {
        return database.child("seccion_detalles").child(nrc).child("notas")
    }

    fun getObservacionesRef(nrc: String): DatabaseReference {
        return database.child("seccion_detalles").child(nrc).child("observaciones")
    }

    fun actualizarNota(nrc: String, codigoMateria: String, evalId: String, matricula: String, nota: Double?) {
        actualizarNotaConNombreEval(nrc, codigoMateria, evalId, "", matricula, nota)
    }

    fun actualizarNotaConNombreEval(nrc: String, codigoMateria: String, evalId: String, evalNombre: String, matricula: String, nota: Double?) {
        val refTradicional = database.child("seccion_detalles").child(nrc)
            .child("notas").child(evalId).child(matricula)

        val refRecord = database.child("estudiante_records").child(matricula)
            .child(codigoMateria).child(evalId)

        if (nota == null) {
            refTradicional.removeValue()
            refRecord.removeValue()
        } else {
            // Use ServerValue.TIMESTAMP so the server clock is used, not the device clock.
            // Must be written as a Map — ServerValue sentinels don't work inside data classes.
            val notaMap = mapOf(
                "nota" to nota,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            refTradicional.setValue(notaMap)
            val recordMap = mutableMapOf<String, Any>(
                "nota" to nota,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            if (evalNombre.isNotEmpty()) {
                recordMap["nombre"] = evalNombre
            }
            refRecord.setValue(recordMap)
        }
    }

    fun guardarObservacion(nrc: String, matricula: String, observacion: String?) {
        val ref = database.child("seccion_detalles").child(nrc).child("observaciones").child(matricula)
        if (observacion.isNullOrEmpty()) {
            ref.removeValue()
        } else {
            ref.setValue(observacion)
        }
    }

    fun crearEvaluacion(nrc: String, eval: Evaluacion) {
        database.child("seccion_detalles").child(nrc).child("evaluaciones").child(eval.id).setValue(eval)
    }

    fun actualizarNombreEvaluacion(nrc: String, codigoMateria: String?, evalId: String, nuevoNombre: String) {
        database.child("seccion_detalles").child(nrc).child("evaluaciones").child(evalId)
            .child("nombre").setValue(nuevoNombre)

        if (codigoMateria == null) return

        database.child("seccion_detalles").child(nrc).child("notas").child(evalId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (notaSnapshot in snapshot.children) {
                        val matricula = notaSnapshot.key ?: continue
                        database.child("estudiante_records").child(matricula)
                            .child(codigoMateria).child(evalId).child("nombre").setValue(nuevoNombre)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("GradeRepository", "Error updating eval name in records: ${error.message}")
                }
            })
    }

    fun eliminarEvaluacion(nrc: String, codigoMateria: String?, eval: Evaluacion, estudiantes: List<Estudiante>) {
        val ref = database.child("seccion_detalles").child(nrc)
        ref.child("evaluaciones").child(eval.id).removeValue()
        ref.child("notas").child(eval.id).removeValue()
        
        if (codigoMateria != null) {
            estudiantes.forEach { est ->
                database.child("estudiante_records").child(est.matricula)
                    .child(codigoMateria).child(eval.id).removeValue()
            }
        }
    }

    fun replicarEvaluacionEnOtrasSecciones(currentNrc: String, currentCodigoMateria: String, nombre: String, valor: Int) {
        database.child("secciones").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshotSecciones: DataSnapshot) {
                for (seccionSnapshot in snapshotSecciones.children) {
                    val seccion = seccionSnapshot.getValue(Seccion::class.java) ?: continue
                    val otherNrc = seccion.nrc
                    val otherCodigoMateria = seccion.codigoMateria
                    if (otherNrc.isNotEmpty() && otherNrc != currentNrc && otherCodigoMateria == currentCodigoMateria) {
                        database.child("seccion_detalles").child(otherNrc).child("evaluaciones")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshotEvaluaciones: DataSnapshot) {
                                    var alreadyExists = false
                                    for (evalSnapshot in snapshotEvaluaciones.children) {
                                        val existingEval = evalSnapshot.getValue(Evaluacion::class.java) ?: continue
                                        if (existingEval.nombre.trim().equals(nombre, ignoreCase = true)) {
                                            alreadyExists = true
                                            break
                                        }
                                    }
                                    if (!alreadyExists) {
                                        val newId = database.child("seccion_detalles").child(otherNrc).child("evaluaciones").push().key ?: ""
                                        val newEval = Evaluacion(newId, nombre, valor, 0)
                                        database.child("seccion_detalles").child(otherNrc).child("evaluaciones").child(newId).setValue(newEval)
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    Log.e("GradeRepository", "Error checking evaluations: ${error.message}")
                                }
                            })
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("GradeRepository", "Error fetching sections: ${error.message}")
            }
        })
    }
}
