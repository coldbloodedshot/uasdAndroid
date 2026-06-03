package com.uasd.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class StudentListViewModel(
    private val repository: GradeRepository,
    private val nrc: String,
    private val codigoMateria: String
) : ViewModel() {

    private val _estudiantes = MutableLiveData<List<GradableEstudiante>>(emptyList())
    val estudiantes: LiveData<List<GradableEstudiante>> get() = _estudiantes

    private val _evaluaciones = MutableLiveData<List<Evaluacion>>(emptyList())
    val evaluaciones: LiveData<List<Evaluacion>> get() = _evaluaciones

    private val _isLoading = MutableLiveData<Boolean>(true)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _selectedEvalName = MutableLiveData<String>("--- Acumulado Total ---")
    val selectedEvalName: LiveData<String> get() = _selectedEvalName

    private val _ordenLista = MutableLiveData(OrdenListaEstudiantes.NOMBRE)
    val ordenLista: LiveData<OrdenListaEstudiantes> get() = _ordenLista

    val listaAlumnosOriginal = mutableListOf<Estudiante>()
    private val todasLasNotas = mutableMapOf<String, MutableMap<String, NotaRegistro>>()
    private val observacionesMap = mutableMapOf<String, String>()
    private var currentQuery = ""

    private var estudiantesListener: ValueEventListener? = null
    private var evaluacionesListener: ValueEventListener? = null
    private var notasListener: ValueEventListener? = null
    private var observacionesListener: ValueEventListener? = null

    init {
        cargarDatos()
    }

    fun setSelectedEvalName(name: String) {
        _selectedEvalName.value = name
        actualizarLista()
    }

    fun setSearchQuery(query: String) {
        currentQuery = query
        actualizarLista()
    }

    fun setOrdenLista(orden: OrdenListaEstudiantes) {
        if (_ordenLista.value == orden) return
        _ordenLista.value = orden
        actualizarLista()
    }

    fun alternarOrdenLista() {
        val siguiente = if (_ordenLista.value == OrdenListaEstudiantes.NOMBRE) {
            OrdenListaEstudiantes.FECHA_EDICION
        } else {
            OrdenListaEstudiantes.NOMBRE
        }
        setOrdenLista(siguiente)
    }

    private fun cargarDatos() {
        _isLoading.value = true

        estudiantesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                listaAlumnosOriginal.clear()
                for (s in snapshot.children) {
                    s.getValue(Estudiante::class.java)?.let { listaAlumnosOriginal.add(it) }
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        repository.getEstudiantesRef(nrc).addValueEventListener(estudiantesListener!!)

        evaluacionesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tempEval = mutableListOf<Evaluacion>()
                for (s in snapshot.children) {
                    s.getValue(Evaluacion::class.java)?.let { tempEval.add(it) }
                }
                tempEval.sortBy { it.nombre }
                _evaluaciones.value = tempEval
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        repository.getEvaluacionesRef(nrc).addValueEventListener(evaluacionesListener!!)

        notasListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                todasLasNotas.clear()
                for (evalSnapshot in snapshot.children) {
                    val evalId = evalSnapshot.key ?: continue
                    val notasMap = mutableMapOf<String, NotaRegistro>()
                    for (notaSnapshot in evalSnapshot.children) {
                        val matricula = notaSnapshot.key ?: continue
                        NotaRegistro.fromSnapshot(notaSnapshot)?.let { notasMap[matricula] = it }
                    }
                    todasLasNotas[evalId] = notasMap
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        repository.getNotasRef(nrc).addValueEventListener(notasListener!!)

        observacionesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                observacionesMap.clear()
                for (s in snapshot.children) {
                    val mat = s.key ?: continue
                    val nota = s.getValue(String::class.java) ?: ""
                    observacionesMap[mat] = nota
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        repository.getObservacionesRef(nrc).addValueEventListener(observacionesListener!!)
    }

    fun actualizarLista() {
        val seleccion = _selectedEvalName.value ?: "--- Acumulado Total ---"
        val listaMostrable = mutableListOf<GradableEstudiante>()

        val alumnosFiltrados = if (currentQuery.isEmpty()) {
            listaAlumnosOriginal
        } else {
            listaAlumnosOriginal.filter {
                it.nombre.contains(currentQuery, ignoreCase = true) ||
                    it.matricula.contains(currentQuery)
            }
        }

        if (seleccion == "--- Acumulado Total ---") {
            alumnosFiltrados.forEach { est ->
                var total = 0.0
                var hasAnyGrade = false
                todasLasNotas.values.forEach { notasMap ->
                    val registro = notasMap[est.matricula]
                    if (registro != null) {
                        total += registro.nota
                        hasAnyGrade = true
                    }
                }
                val displayedNota = if (hasAnyGrade) total else -1.0
                listaMostrable.add(
                    GradableEstudiante(
                        est.matricula,
                        est.nombre,
                        displayedNota,
                        true,
                        observacionesMap[est.matricula]
                    )
                )
            }
            _estudiantes.value = ordenarLista(listaMostrable)
        } else {
            val evalActual = _evaluaciones.value?.find { getEvalDisplayName(it) == seleccion }
            val notasActuales = todasLasNotas[evalActual?.id] ?: emptyMap()

            alumnosFiltrados.forEach { est ->
                val registro = notasActuales[est.matricula]
                val nota = registro?.nota ?: -1.0
                val updatedAt = registro?.updatedAt ?: 0L
                listaMostrable.add(
                    GradableEstudiante(
                        est.matricula,
                        est.nombre,
                        nota,
                        false,
                        observacionesMap[est.matricula],
                        notaUpdatedAt = updatedAt
                    )
                )
            }
            _estudiantes.value = asignarOrdenCaptura(ordenarLista(listaMostrable))
        }
        _isLoading.value = false
    }

    private fun ordenarLista(lista: List<GradableEstudiante>): List<GradableEstudiante> {
        return when (_ordenLista.value) {
            OrdenListaEstudiantes.FECHA_EDICION -> lista.sortedWith(
                compareBy<GradableEstudiante>(
                    { alumno ->
                        when {
                            alumno.nota <= -1 -> 2
                            alumno.notaUpdatedAt > 0 -> 0
                            else -> 1
                        }
                    },
                    { it.notaUpdatedAt },
                    { it.nombre }
                )
            )
            else -> lista.sortedBy { it.nombre }
        }
    }

    private fun asignarOrdenCaptura(lista: List<GradableEstudiante>): List<GradableEstudiante> {
        if (_ordenLista.value != OrdenListaEstudiantes.FECHA_EDICION) {
            return lista.map { it.copy(ordenCaptura = 0) }
        }
        var orden = 0
        return lista.map { alumno ->
            if (alumno.nota > -1) {
                orden++
                alumno.copy(ordenCaptura = orden)
            } else {
                alumno.copy(ordenCaptura = 0)
            }
        }
    }

    private fun getEvalDisplayName(eval: Evaluacion): String {
        return if (eval.esExtra == 1) eval.nombre else "${eval.nombre} (${eval.valor} pts)"
    }

    fun getNotaEstudiante(evalId: String, matricula: String): Double {
        return todasLasNotas[evalId]?.get(matricula)?.nota ?: -1.0
    }

    fun guardarNota(alumno: GradableEstudiante, nota: Double?) {
        val seleccion = _selectedEvalName.value ?: return
        val evalActual = _evaluaciones.value?.find { getEvalDisplayName(it) == seleccion } ?: return

        val nuevaNota = if (evalActual.esExtra == 1) {
            val notaActual = todasLasNotas[evalActual.id]?.get(alumno.matricula)?.nota ?: 0.0
            if (nota != null) notaActual + nota else null
        } else {
            nota
        }

        if (evalActual.esExtra == 1 || nuevaNota == null || nuevaNota <= evalActual.valor) {
            repository.actualizarNotaConNombreEval(
                nrc, codigoMateria, evalActual.id, evalActual.nombre, alumno.matricula, nuevaNota
            )
        }
    }

    fun guardarObservacion(alumno: GradableEstudiante, obs: String?) {
        repository.guardarObservacion(nrc, alumno.matricula, obs)
    }

    fun crearEvaluacion(nombre: String, valor: Int, esExtra: Int, replicar: Boolean) {
        val ref = repository.getEvaluacionesRef(nrc)
        val id = ref.push().key ?: ""
        val eval = Evaluacion(id, nombre, valor, esExtra)
        repository.crearEvaluacion(nrc, eval)

        if (replicar) {
            repository.replicarEvaluacionEnOtrasSecciones(nrc, codigoMateria, nombre, valor)
        }
    }

    fun eliminarEvaluacion() {
        val seleccion = _selectedEvalName.value ?: return
        val evalActual = _evaluaciones.value?.find { getEvalDisplayName(it) == seleccion } ?: return
        repository.eliminarEvaluacion(nrc, codigoMateria, evalActual, listaAlumnosOriginal)
    }

    fun renombrarEvaluacion(nuevoNombre: String) {
        val seleccion = _selectedEvalName.value ?: return
        val evalActual = _evaluaciones.value?.find { getEvalDisplayName(it) == seleccion } ?: return
        val trimmed = nuevoNombre.trim()
        if (trimmed.isEmpty() || trimmed.equals(evalActual.nombre, ignoreCase = true)) return

        repository.actualizarNombreEvaluacion(nrc, codigoMateria, evalActual.id, trimmed)
        _selectedEvalName.value = getEvalDisplayName(evalActual.copy(nombre = trimmed))
    }

    fun gestionarPuntosExtra(onSelectionRequested: (String) -> Unit) {
        val extraEval = _evaluaciones.value?.find { it.esExtra == 1 }
        if (extraEval != null) {
            onSelectionRequested(getEvalDisplayName(extraEval))
        } else {
            val ref = repository.getEvaluacionesRef(nrc)
            val id = ref.push().key ?: ""
            val eval = Evaluacion(id, "PUNTOS EXTRA", 100, 1)
            repository.crearEvaluacion(nrc, eval)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val pathEstudiantes = repository.getEstudiantesRef(nrc)
        val pathEvaluaciones = repository.getEvaluacionesRef(nrc)
        val pathNotas = repository.getNotasRef(nrc)
        val pathObservaciones = repository.getObservacionesRef(nrc)

        estudiantesListener?.let { pathEstudiantes.removeEventListener(it) }
        evaluacionesListener?.let { pathEvaluaciones.removeEventListener(it) }
        notasListener?.let { pathNotas.removeEventListener(it) }
        observacionesListener?.let { pathObservaciones.removeEventListener(it) }
    }
}
