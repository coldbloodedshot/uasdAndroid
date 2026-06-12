package com.uasd.main
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import org.vosk.android.RecognitionListener as VoskRecognitionListener

class StudentListFragment : Fragment() {

    companion object {
        private const val ARG_NRC = "nrc"
        private const val ARG_CODIGO_MATERIA = "codigo_materia"
        private const val ARG_SECCION = "seccion"
        private const val ARG_MATERIA = "materia"
        private const val ARG_PREV_EVAL_NAME = "prev_eval_name"
        private const val ARG_WANT_DICTATION = "want_dictation"
        private const val ARG_PREV_QUERY = "prev_query"

        fun newInstance(nrc: String, codigoMateria: String, seccion: String, materia: String, prevEvalName: String? = null, wantDictation: Boolean = false, prevQuery: String? = null): StudentListFragment {
            val fragment = StudentListFragment()
            val args = Bundle()
            args.putString(ARG_NRC, nrc)
            args.putString(ARG_CODIGO_MATERIA, codigoMateria)
            args.putString(ARG_SECCION, seccion)
            args.putString(ARG_MATERIA, materia)
            args.putString(ARG_PREV_EVAL_NAME, prevEvalName)
            args.putBoolean(ARG_WANT_DICTATION, wantDictation)
            args.putString(ARG_PREV_QUERY, prevQuery)
            fragment.arguments = args
            return fragment
        }
    }

    private val RC_AUDIO_PERM = 1001

    private lateinit var database: DatabaseReference
    private lateinit var adapter: EstudianteAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerEvaluaciones: Spinner
    private lateinit var etSearch: EditText
    private lateinit var ivClearSearch: ImageView
    private lateinit var btnDictado: ImageButton
    private lateinit var btnToggleOrden: ImageButton
    
    private lateinit var viewModel: StudentListViewModel
    private lateinit var repository: GradeRepository
    private val savedMatchesThisSession = mutableSetOf<String>()
    
    private lateinit var fabMain: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabAttendance: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabNewEval: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabExtraPoints: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    private lateinit var layoutFabAttendance: android.widget.LinearLayout
    private lateinit var layoutFabNewEval: android.widget.LinearLayout
    private lateinit var layoutFabExtra: android.widget.LinearLayout
    private lateinit var fabMenuContainer: android.widget.LinearLayout
    
    private var isFabOpen = false
    
    private var nrc: String? = null
    private var codigoMateria: String? = null
    private var seccion: String? = null
    private var materia: String? = null
    private var prevEvalName: String? = null
    private var wantDictation: Boolean = false
    private var currentQuery: String = ""
    private var pendingSelectEvalId: String? = null
    
    private lateinit var dictationManager: DictationManager
    
    val isDictationMode: Boolean
        get() = if (this::dictationManager.isInitialized) dictationManager.isDictationMode else false
        
    private var searchJob: Job? = null
    
    private val PREFS_NAME = "uasd_prefs"
    private val KEY_LAST_EXTRA = "last_extra_points"
    private val KEY_DICTATION_MODE = "dictation_mode"
    private val MODE_VOSK = "vosk"
    private val MODE_NATIVE = "native"
    private val MODE_GEMINI = "gemini"
    
    private val listaAlumnosOriginal = mutableListOf<Estudiante>()
    private val evaluaciones = mutableListOf<Evaluacion>()
    private val todasLasNotas = mutableMapOf<String, MutableMap<String, Double>>()
    private val observacionesMap = mutableMapOf<String, String>()
    
    private val ACUMULADO_TEXT = "--- Acumulado Total ---"

    interface OnSearchListener {
        fun onEmptySearch(query: String)
        fun onSearchCleared()
    }
    var searchListener: OnSearchListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnSearchListener) {
            searchListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        searchListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            nrc = it.getString(ARG_NRC)
            codigoMateria = it.getString(ARG_CODIGO_MATERIA)
            seccion = it.getString(ARG_SECCION)
            materia = it.getString(ARG_MATERIA)
            prevEvalName = it.getString(ARG_PREV_EVAL_NAME)
            wantDictation = it.getBoolean(ARG_WANT_DICTATION)
            val prevQ = it.getString(ARG_PREV_QUERY)
            if (prevQ != null && currentQuery.isEmpty()) {
                currentQuery = prevQ
            }
        }
        
        savedInstanceState?.let {
            currentQuery = it.getString("currentQuery", "")
            pendingSelectEvalId = it.getString("selectedEvalId")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_student_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerViewStudents)
        spinnerEvaluaciones = view.findViewById(R.id.spinnerEvaluaciones)
        etSearch = view.findViewById(R.id.etSearch)
        ivClearSearch = view.findViewById(R.id.ivClearSearch)
        btnDictado = view.findViewById(R.id.btnDictado)

        ivClearSearch.setOnClickListener {
            etSearch.text.clear()
        }

        dictationManager = DictationManager(requireContext(), object : DictationManager.DictationCallback {
            override fun onGradeRecognized(matricula: String, nombre: String, grade: Double) {
                val sessionKey = "${matricula}_${grade}"
                if (!savedMatchesThisSession.contains(sessionKey)) {
                    val gradable = GradableEstudiante(matricula, nombre)
                    guardarNotaDictado(gradable, grade)
                    resaltarYDesplazarHacia(matricula)
                    savedMatchesThisSession.add(sessionKey)
                }
            }
            override fun onBulkGradesRecognized(updates: List<DictationManager.GradeUpdate>) {
                var count = 0
                for (update in updates) {
                    val est = listaAlumnosOriginal.find { it.matricula == update.id }
                    if (est != null) {
                        val gradable = GradableEstudiante(est.matricula, est.nombre)
                        guardarNotaDictado(gradable, update.grade)
                        count++
                    }
                }
                if (count > 0 && isAdded) {
                    Toast.makeText(requireContext(), "Gemini actualizó $count notas", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onStatusChanged(statusText: String, color: Int) {
                adapter.isDictationMode = true
                (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = statusText
                btnDictado.setColorFilter(color)
            }
            override fun onVoskModelLoaded() {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Modelo de voz listo", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onDictationStopped() {
                adapter.isDictationMode = false
                updateActionBarTitle()
                btnDictado.clearColorFilter()
                actualizarLista()
            }
            override fun onError(errorMsg: String) {
                if (isAdded) {
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            }
            override fun onSoundSuccess() {
                emitirSonidoExito()
            }
            override fun onSoundError() {
                emitirSonidoError()
            }
        })

        btnDictado.setOnClickListener {
            val mode = dictationManager.getDictationMode()
            if (mode != MODE_GEMINI) {
                if (dictationManager.isDictationMode) {
                    dictationManager.stopDictation()
                } else {
                    val seleccion = spinnerEvaluaciones.selectedItem?.toString()
                    if (seleccion == null || seleccion == ACUMULADO_TEXT) {
                        Toast.makeText(requireContext(), "Selecciona una evaluación primero", Toast.LENGTH_SHORT).show()
                    } else if (requireActivity().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.GET_ACCOUNTS), RC_AUDIO_PERM)
                    } else {
                        dictationManager.startDictation(listaAlumnosOriginal)
                    }
                }
            }
        }

        btnDictado.setOnTouchListener { v, event ->
            val mode = dictationManager.getDictationMode()
            if (mode == MODE_GEMINI) {
                val seleccion = spinnerEvaluaciones.selectedItem?.toString()
                if (seleccion == null || seleccion == ACUMULADO_TEXT) {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        Toast.makeText(requireContext(), "Selecciona una evaluación primero", Toast.LENGTH_SHORT).show()
                    }
                    return@setOnTouchListener false
                }
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (requireActivity().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.GET_ACCOUNTS), RC_AUDIO_PERM)
                        } else {
                            dictationManager.startDictation(listaAlumnosOriginal)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dictationManager.stopDictation()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        
        adapter = EstudianteAdapter(emptyList(), false, false, { alumno ->
            if (spinnerEvaluaciones.selectedItem?.toString() != ACUMULADO_TEXT) {
                mostrarDialogoNota(alumno)
            } else {
                mostrarDetalleNotas(alumno)
            }
        }, { alumno ->
            mostrarDialogoObservacion(alumno)
        }, { alumno, grade ->
            guardarNotaDictado(alumno, grade)
        })
        recyclerView.adapter = adapter

        recyclerView.clipToPadding = false
        val extraPadding = (120 * resources.displayMetrics.density).toInt()
        recyclerView.setPadding(recyclerView.paddingLeft, recyclerView.paddingTop, recyclerView.paddingRight, extraPadding)

        fabMain = view.findViewById(R.id.fabMain)
        fabAttendance = view.findViewById(R.id.fabAttendance)
        fabNewEval = view.findViewById(R.id.fabNewEval)
        fabExtraPoints = view.findViewById(R.id.fabExtraPoints)
        
        layoutFabAttendance = view.findViewById(R.id.fabActionAttendance)
        layoutFabNewEval = view.findViewById(R.id.fabActionNewEval)
        layoutFabExtra = view.findViewById(R.id.fabActionExtra)
        fabMenuContainer = view.findViewById(R.id.fabMenuContainer)

        fabMain.setOnClickListener { toggleFabMenu() }
        
        fabAttendance.setOnClickListener {
            closeFabMenu()
            val intent = Intent(requireContext(), AttendanceActivity::class.java).apply {
                putExtra("NRC", nrc)
                putExtra("MATERIA", materia)
            }
            startActivity(intent)
        }
        
        fabNewEval.setOnClickListener { 
            closeFabMenu()
            mostrarDialogoNuevaEvaluacion() 
        }
        
        fabExtraPoints.setOnClickListener { 
            closeFabMenu()
            gestionarPuntosExtra() 
        }

        btnToggleOrden = view.findViewById(R.id.btnToggleOrden)
        btnToggleOrden.setOnClickListener {
            viewModel.alternarOrdenLista()
        }

        if (nrc != null) {
            database = FirebaseDatabase.getInstance().reference
            repository = GradeRepository(database)
            viewModel = StudentListViewModel(repository, nrc!!, codigoMateria ?: "")
            
            viewModel.estudiantes.observe(viewLifecycleOwner) { lista ->
                adapter.updateData(lista)
                
                // Keep local lists synchronized for helper dialogs and voice recognizers
                listaAlumnosOriginal.clear()
                lista.forEach { 
                    listaAlumnosOriginal.add(Estudiante(it.matricula, it.nombre))
                }
                
                // If there's a pending highlighted student, scroll to them now that the list has been updated and reordered
                adapter.highlightedStudentId?.let { matricula ->
                    val index = lista.indexOfFirst { it.matricula == matricula }
                    if (index != -1) {
                        recyclerView.post {
                            recyclerView.smoothScrollToPosition(index)
                        }
                    }
                }
            }
            
            viewModel.ordenLista.observe(viewLifecycleOwner) { orden ->
                val imageRes = if (orden == OrdenListaEstudiantes.FECHA_EDICION) {
                    android.R.drawable.ic_menu_sort_alphabetically
                } else {
                    android.R.drawable.ic_menu_sort_by_size
                }
                btnToggleOrden.setImageResource(imageRes)
            }

            viewModel.evaluaciones.observe(viewLifecycleOwner) { evals ->
                evaluaciones.clear()
                evaluaciones.addAll(evals)

                val nombresEval = mutableListOf(ACUMULADO_TEXT)
                evals.forEach { nombresEval.add(getEvalDisplayName(it)) }
                
                val currentContext = context ?: return@observe
                val spinnerAdapter = ArrayAdapter(currentContext, android.R.layout.simple_spinner_item, nombresEval)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerEvaluaciones.adapter = spinnerAdapter

                val seleccionActual = spinnerEvaluaciones.selectedItem?.toString()
                
                if (pendingSelectEvalId != null) {
                    val targetEval = evaluaciones.find { it.id == pendingSelectEvalId }
                    if (targetEval != null) {
                        val displayName = getEvalDisplayName(targetEval)
                        val pos = nombresEval.indexOf(displayName)
                        if (pos != -1) {
                            spinnerEvaluaciones.setSelection(pos)
                            pendingSelectEvalId = null
                        }
                    }
                } else if (prevEvalName != null) {
                    val match = evaluaciones.find { it.nombre.trim().equals(prevEvalName?.trim(), ignoreCase = true) }
                    if (match != null) {
                        val displayName = getEvalDisplayName(match)
                        val pos = nombresEval.indexOf(displayName)
                        if (pos != -1) {
                            spinnerEvaluaciones.setSelection(pos)
                            
                            if (wantDictation) {
                                spinnerEvaluaciones.post {
                                    if (isAdded && !dictationManager.isDictationMode) {
                                        if (requireActivity().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            dictationManager.startDictation(listaAlumnosOriginal)
                                        }
                                    }
                                }
                            }
                        }
                        prevEvalName = null
                        wantDictation = false
                    } else if (evaluaciones.isNotEmpty()) {
                        prevEvalName = null
                        wantDictation = false
                    }
                } else if (seleccionActual == null || seleccionActual == ACUMULADO_TEXT) {
                    val targetEval = if (pendingSelectEvalId != null) {
                        evaluaciones.find { it.id == pendingSelectEvalId }
                    } else {
                        evaluaciones.find { it.esExtra == 1 }
                    }
                    
                    if (targetEval != null) {
                        val displayName = getEvalDisplayName(targetEval)
                        val pos = nombresEval.indexOf(displayName)
                        if (pos != -1) {
                            spinnerEvaluaciones.setSelection(pos)
                            pendingSelectEvalId = null
                        }
                    }
                }
            }
            // Bind ViewModel's internal structures to local maps so helper dialogs have local data access
            viewModel.estudiantes.observe(viewLifecycleOwner) {
                // Keep todasLasNotas and observacionesMap synchronized for helper detail dialogs
                todasLasNotas.clear()
                observacionesMap.clear()
                viewModel.evaluaciones.value?.forEach { eval ->
                    val map = mutableMapOf<String, Double>()
                    it.forEach { gradable ->
                        if (gradable.nota > -1) {
                            map[gradable.matricula] = gradable.nota
                        }
                        gradable.observacion?.let { obs ->
                            observacionesMap[gradable.matricula] = obs
                        }
                    }
                    todasLasNotas[eval.id] = map
                }
            }

            // Data loading is triggered automatically by ViewModel's init block.
            
            spinnerEvaluaciones.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val sel = spinnerEvaluaciones.selectedItem?.toString() ?: ACUMULADO_TEXT
                    viewModel.setSelectedEvalName(sel)
                    
                    // Show or hide sort button based on selection (only active for individual evaluations, not total cumulative)
                    if (sel == ACUMULADO_TEXT) {
                        btnToggleOrden.visibility = View.GONE
                        adapter.mostrarOrdenCaptura = false
                    } else {
                        btnToggleOrden.visibility = View.VISIBLE
                        viewModel.ordenLista.value?.let {
                            adapter.mostrarOrdenCaptura = (it == OrdenListaEstudiantes.FECHA_EDICION)
                        }
                    }
                    
                    actualizarLista()
                    activity?.invalidateOptionsMenu()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString() ?: ""
                    ivClearSearch.visibility = if (currentQuery.isEmpty()) View.GONE else View.VISIBLE
                    viewModel.setSearchQuery(currentQuery)
                    actualizarLista()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            if (currentQuery.isNotEmpty()) {
                etSearch.setText(currentQuery)
                ivClearSearch.visibility = View.VISIBLE
            }
        }
        
        // Vosk model initialization is now loaded lazily inside iniciarFlujoDictado / empezarDictadoVosk
        // to prevent UI freezing and heavy memory load when switching sections.
    }

    fun getSelectedEvalName(): String? {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return null
        if (seleccion == ACUMULADO_TEXT) return null
        return evaluaciones.find { getEvalDisplayName(it) == seleccion }?.nombre
    }

    fun getCurrentSearchQuery(): String = currentQuery

    private fun getEvalDisplayName(eval: Evaluacion): String {
        return if (eval.esExtra == 1) eval.nombre else "${eval.nombre} (${eval.valor} pts)"
    }


    fun handleBackPress(): Boolean {
        if (this::dictationManager.isInitialized && dictationManager.isDictationMode) {
            dictationManager.stopDictation()
            return true
        }
        return false
    }

    // Database listeners are managed inside the StudentListViewModel to prevent leaks and double fetches.

    // Note: LiveData observers are cleaned up by viewLifecycleOwner automatically.

    private fun actualizarLista() {
        if (!isAdded) return
        val listSize = viewModel.estudiantes.value?.size ?: 0
        searchJob?.cancel()
        if (currentQuery.isNotEmpty() && listSize == 0) {
            searchJob = CoroutineScope(Dispatchers.Main).launch {
                delay(300)
                searchListener?.onEmptySearch(currentQuery)
            }
        } else {
            searchListener?.onSearchCleared()
        }
    }

    fun mostrarDialogoEliminarEvaluacion() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("¿Eliminar evaluación?")
            .setMessage("Estás a punto de borrar '${evalActual.nombre}'.\n\n¡ATENCION! Se borrarán permanentemente todas las notas registradas para esta evaluación. Esta acción no se puede deshacer.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("ELIMINAR") { _, _ ->
                val ref = database.child("seccion_detalles").child(nrc!!)
                ref.child("evaluaciones").child(evalActual.id).removeValue()
                ref.child("notas").child(evalActual.id).removeValue()
                
                // Limpiar también en los records individuales de los estudiantes
                val currentCodigo = codigoMateria
                if (currentCodigo != null) {
                    listaAlumnosOriginal.forEach { est ->
                        database.child("estudiante_records").child(est.matricula)
                            .child(currentCodigo).child(evalActual.id).removeValue()
                    }
                }
                
                Toast.makeText(requireContext(), "Evaluación eliminada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun mostrarDialogoPonerEnBlanco() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("¿Poner en blanco?")
            .setMessage("Se borrarán todas las notas de '${evalActual.nombre}', pero la evaluación se conservará. Esta acción no se puede deshacer.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("PONER EN BLANCO") { _, _ ->
                val ref = database.child("seccion_detalles").child(nrc!!)
                ref.child("notas").child(evalActual.id).removeValue()

                val currentCodigo = codigoMateria
                if (currentCodigo != null) {
                    listaAlumnosOriginal.forEach { est ->
                        database.child("estudiante_records").child(est.matricula)
                            .child(currentCodigo).child(evalActual.id).removeValue()
                    }
                }

                Toast.makeText(requireContext(), "Notas eliminadas — evaluación conservada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun gestionarPuntosExtra() {
        val extraEval = evaluaciones.find { it.esExtra == 1 }
        if (extraEval != null) {
            val displayName = getEvalDisplayName(extraEval)
            for (i in 0 until spinnerEvaluaciones.adapter.count) {
                if (spinnerEvaluaciones.adapter.getItem(i).toString() == displayName) {
                    spinnerEvaluaciones.setSelection(i)
                    break
                }
            }
        } else {
            val ref = database.child("seccion_detalles").child(nrc!!).child("evaluaciones")
            val id = ref.push().key ?: ""
            pendingSelectEvalId = id
            val eval = Evaluacion(id, "PUNTOS EXTRA", 100, 1)
            database.child("seccion_detalles").child(nrc!!).child("evaluaciones").child(id).setValue(eval)
            // No es estrictamente necesario inicializar el record aquí ya que no hay nota aún,
            // pero si se quisiera se podría hacer.
        }
    }

    private fun mostrarDialogoNuevaEvaluacion() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_evaluacion, null)
        val etNombre = view.findViewById<EditText>(R.id.etNombreEval)
        val etValor = view.findViewById<EditText>(R.id.etValorEval)
        val cbCrearOtrasSecciones = view.findViewById<CheckBox>(R.id.cbCrearOtrasSecciones)

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Evaluación")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = etNombre.text.toString().trim()
                val valor = etValor.text.toString().toIntOrNull() ?: 0
                val currentNrc = nrc
                val currentCodigoMateria = codigoMateria
                if (nombre.isNotEmpty() && valor > 0 && currentNrc != null) {
                    val id = database.child("seccion_detalles").child(currentNrc).child("evaluaciones").push().key ?: ""
                    pendingSelectEvalId = id
                    val eval = Evaluacion(id, nombre, valor, 0)
                    database.child("seccion_detalles").child(currentNrc).child("evaluaciones").child(id).setValue(eval)

                    if (cbCrearOtrasSecciones.isChecked) {
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
                                                    Log.e("StudentListFragment", "Error checking evaluations for section $otherNrc: ${error.message}")
                                                }
                                            })
                                    }
                                }
                            }
                            override fun onCancelled(error: DatabaseError) {
                                Log.e("StudentListFragment", "Error fetching sections: ${error.message}")
                            }
                        })
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoObservacion(alumno: GradableEstudiante) {
        val etNote = EditText(requireContext())
        etNote.hint = "Escribe una notita aquí..."
        etNote.setText(alumno.observacion ?: "")
        etNote.setPadding(60, 40, 60, 40)

        AlertDialog.Builder(requireContext())
            .setTitle(alumno.nombre)
            .setView(etNote)
            .setPositiveButton("Guardar") { _, _ ->
                val text = etNote.text.toString().trim()
                val ref = database.child("seccion_detalles").child(nrc!!).child("observaciones").child(alumno.matricula)
                if (text.isEmpty()) {
                    ref.removeValue()
                } else {
                    ref.setValue(text)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoNota(alumno: GradableEstudiante) {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return
        
        val etNota = EditText(requireContext())
        etNota.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        
        val notaActual = if (alumno.nota > -1) alumno.nota else 0.0
        if (alumno.nota > -1) etNota.setText(alumno.nota.toString())
        
        etNota.setPadding(60, 40, 60, 40)

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(alumno.nombre)
            .setView(etNota)
            .setNegativeButton("Cancelar", null)

        if (evalActual.esExtra == 1) {
            builder.setTitle("${alumno.nombre} (Ptos Extra)")
            etNota.hint = "Cantidad a sumar (ej: 0.5)"
            
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastExtra = prefs.getFloat(KEY_LAST_EXTRA, 0.0f)
            if (lastExtra > 0) {
                etNota.setText(lastExtra.toString())
                etNota.selectAll()
            } else {
                etNota.setText("")
            }
            
            builder.setPositiveButton("Sumar") { _, _ ->
                val incremento = etNota.text.toString().toDoubleOrNull() ?: 0.0
                if (incremento > 0) {
                    prefs.edit().putFloat(KEY_LAST_EXTRA, incremento.toFloat()).apply()
                }
                val nuevaNota = notaActual + incremento
                actualizarNotaEnFirebase(evalActual, alumno.matricula, nuevaNota)
            }
            builder.setNeutralButton("Corregir") { _, _ ->
                val nuevaNota = etNota.text.toString().toDoubleOrNull() ?: 0.0
                AlertDialog.Builder(requireContext())
                    .setTitle("¿Sobrescribir puntos?")
                    .setMessage("Vas a poner $nuevaNota como el total de puntos extra para este estudiante. ¿Estás seguro?")
                    .setPositiveButton("SÍ, SOBRESCRIBIR") { _, _ ->
                        actualizarNotaEnFirebase(evalActual, alumno.matricula, nuevaNota)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            builder.setMessage("Puntos actuales: $notaActual")
        } else {
            builder.setPositiveButton("Guardar") { _, _ ->
                val input = etNota.text.toString().trim()
                val ref = database.child("seccion_detalles").child(nrc!!)
                        .child("notas").child(evalActual.id).child(alumno.matricula)
                
                if (input.isEmpty()) {
                    actualizarNotaEnFirebase(evalActual, alumno.matricula, null)
                } else {
                    val nota = input.toDoubleOrNull() ?: 0.0
                    if (nota <= evalActual.valor) {
                        actualizarNotaEnFirebase(evalActual, alumno.matricula, nota)
                    } else {
                        Toast.makeText(requireContext(), "Error: La nota no puede superar el valor máximo", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            builder.setNeutralButton("100 %") { _, _ ->
                actualizarNotaEnFirebase(evalActual, alumno.matricula, evalActual.valor.toDouble())
            }
        }
        
        builder.show()
    }

    private fun mostrarDetalleNotas(alumno: GradableEstudiante) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        var totalAcumulado = 0.0
        evaluaciones.forEach { eval ->
            val nota = todasLasNotas[eval.id]?.get(alumno.matricula) ?: 0.0
            totalAcumulado += nota

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
            }

            val tvNombre = TextView(context).apply {
                text = eval.nombre
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 16f
            }

            val tvNota = TextView(context).apply {
                text = if (eval.esExtra == 1) "+$nota" else "$nota / ${eval.valor}"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            row.addView(tvNombre)
            row.addView(tvNota)
            layout.addView(row)
        }

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 20, 0, 20)
            }
            setBackgroundColor(android.graphics.Color.GRAY)
        }
        layout.addView(divider)

        val totalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvTotalLabel = TextView(context).apply {
            text = "TOTAL ACUMULADO"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvTotalValor = TextView(context).apply {
            text = String.format("%.2f", totalAcumulado)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#1B5E20"))
        }

        totalRow.addView(tvTotalLabel)
        totalRow.addView(tvTotalValor)
        layout.addView(totalRow)

        val scrollView = ScrollView(context).apply {
            addView(layout)
        }

        AlertDialog.Builder(context)
            .setTitle(alumno.nombre)
            .setView(scrollView)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    fun mostrarEstadisticas() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return
        
        val notasMap = todasLasNotas[evalActual.id] ?: emptyMap<String, Double>()
        
        // Filtrar para considerar solo alumnos que pertenecen oficialmente a esta sección
        val matriculasDeEstaSeccion = listaAlumnosOriginal.map { it.matricula }.toSet()
        val notas = notasMap.filter { matriculasDeEstaSeccion.contains(it.key) }
                           .values.filter { it >= 0 }

        if (notas.isEmpty()) {
            Toast.makeText(requireContext(), "No hay notas registradas para esta evaluación", Toast.LENGTH_SHORT).show()
            return
        }

        val count = notas.size
        val sum = notas.sum()
        val average = sum / count
        val max = notas.maxOrNull() ?: 0.0
        val min = notas.minOrNull() ?: 0.0
        val deviation = if (count > 0) notas.map { Math.abs(it - average) }.sum() / count else 0.0

        val sb = StringBuilder()
        sb.append("Estudiantes evaluados: $count\n\n")
        sb.append("Nota Promedio: ${String.format("%.2f", average)}\n")
        sb.append("Desviación Promedio: ${String.format("%.2f", deviation)}\n\n")
        sb.append("Nota Máxima: ${String.format("%.1f", max)}\n")
        sb.append("Nota Mínima: ${String.format("%.1f", min)}")

        AlertDialog.Builder(requireContext())
            .setTitle("Estadísticas: ${evalActual.nombre}")
            .setMessage(sb.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    fun mostrarConfiguracionDictado() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getString(KEY_DICTATION_MODE, MODE_VOSK)
        
        val options = arrayOf(
            "Vosk (Offline - Más preciso)", 
            "Android Nativo (Online - Rápido)",
            "Gemini AI (Inteligente - Hold to talk)"
        )
        val checkedItem = when(currentMode) {
            MODE_VOSK -> 0
            MODE_NATIVE -> 1
            MODE_GEMINI -> 2
            else -> 0
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Motor de Dictado")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newMode = when(which) {
                    0 -> MODE_VOSK
                    1 -> MODE_NATIVE
                    2 -> MODE_GEMINI
                    else -> MODE_VOSK
                }
                prefs.edit().putString(KEY_DICTATION_MODE, newMode).apply()
                Toast.makeText(requireContext(), "Motor cambiado a: ${options[which]}", Toast.LENGTH_SHORT).show()
                
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    fun isGeminiCorrectionEnabled(): Boolean =
        if (this::dictationManager.isInitialized) dictationManager.isGeminiCorrectionEnabled() else true

    fun toggleGeminiCorrection() {
        if (!this::dictationManager.isInitialized) return
        val nuevoEstado = !dictationManager.isGeminiCorrectionEnabled()
        dictationManager.setGeminiCorrectionEnabled(nuevoEstado)
        val msg = if (nuevoEstado) "Corrección Gemini activada" else "Corrección Gemini desactivada"
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateActionBarTitle() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = if (materia != null) {
            if (!seccion.isNullOrEmpty()) "$materia - $seccion" else materia
        } else {
            "Lista de Estudiantes"
        }
    }

    private fun emitirSonidoError() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        } catch (e: Exception) {}
    }

    private fun emitirSonidoExito() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneG.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {}
    }

    private fun resaltarYDesplazarHacia(matricula: String) {
        val posicion = adapter.estudiantes.indexOfFirst { it.matricula == matricula }
        if (posicion != -1) {
            adapter.highlightedStudentId = matricula
            adapter.notifyItemChanged(posicion)
            
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(1500)
                if (isAdded && adapter.highlightedStudentId == matricula) {
                    adapter.highlightedStudentId = null
                    val index = adapter.estudiantes.indexOfFirst { it.matricula == matricula }
                    if (index != -1) {
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun guardarNotaDictado(alumno: GradableEstudiante, nota: Double?) {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        if (nota == null) {
            actualizarNotaEnFirebase(evalActual, alumno.matricula, null)
            actualizarLista()
            return
        }

        val nuevaNota = if (evalActual.esExtra == 1) {
            val notaActual = todasLasNotas[evalActual.id]?.get(alumno.matricula) ?: 0.0
            notaActual + nota
        } else {
            nota
        }

        if (evalActual.esExtra == 1 || nuevaNota <= evalActual.valor) {
            actualizarNotaEnFirebase(evalActual, alumno.matricula, nuevaNota)
            emitirSonidoExito()
            val msg = if (evalActual.esExtra == 1) "Puntos sumados a ${alumno.nombre}" else "Nota asignada a ${alumno.nombre}"
            if (isAdded) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            
            actualizarLista()
        } else {
            Toast.makeText(requireContext(), "Error: La nota supera el valor máximo (${evalActual.valor})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actualizarNotaEnFirebase(eval: Evaluacion, matricula: String, nota: Double?) {
        val currentNrc = nrc ?: return
        val currentCodigo = codigoMateria ?: return
        repository.actualizarNotaConNombreEval(currentNrc, currentCodigo, eval.id, eval.nombre, matricula, nota)
    }

    private fun toggleFabMenu() {
        if (!isFabOpen) {
            showFabMenu()
        } else {
            closeFabMenu()
        }
    }

    private fun showFabMenu() {
        isFabOpen = true
        fabMain.animate().rotation(45f)
        fabMenuContainer.visibility = View.VISIBLE
        fabMenuContainer.alpha = 0f
        fabMenuContainer.translationY = 50f
        fabMenuContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
    }

    private fun closeFabMenu() {
        isFabOpen = false
        fabMain.animate().rotation(0f)
        fabMenuContainer.animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(200)
            .withEndAction { fabMenuContainer.visibility = View.GONE }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::dictationManager.isInitialized) {
            dictationManager.release()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {}

    fun tieneEvaluacionIndividualSeleccionada(): Boolean {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return false
        return seleccion != ACUMULADO_TEXT && evaluaciones.any { getEvalDisplayName(it) == seleccion && it.esExtra == 0 }
    }

    fun mostrarDialogoEditarNombreEvaluacion() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        val etNombre = EditText(requireContext()).apply {
            setText(evalActual.nombre)
            setPadding(60, 40, 60, 40)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Editar nombre de evaluación")
            .setView(etNombre)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevoNombre = etNombre.text.toString().trim()
                if (nuevoNombre.isNotEmpty()) {
                    repository.actualizarNombreEvaluacion(nrc!!, codigoMateria, evalActual.id, nuevoNombre)
                    Toast.makeText(requireContext(), "Nombre actualizado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentQuery", currentQuery)
        
        val seleccion = spinnerEvaluaciones.selectedItem?.toString()
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion }
        outState.putString("selectedEvalId", evalActual?.id)
    }
}
