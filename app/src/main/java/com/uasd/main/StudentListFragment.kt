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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.firebase.database.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.vosk.android.RecognitionListener as VoskRecognitionListener

class StudentListFragment : Fragment() {

    companion object {
        private const val ARG_NRC = "nrc"
        private const val ARG_CODIGO_MATERIA = "codigo_materia"
        private const val ARG_SECCION = "seccion"
        private const val ARG_MATERIA = "materia"

        fun newInstance(nrc: String, codigoMateria: String, seccion: String, materia: String): StudentListFragment {
            val fragment = StudentListFragment()
            val args = Bundle()
            args.putString(ARG_NRC, nrc)
            args.putString(ARG_CODIGO_MATERIA, codigoMateria)
            args.putString(ARG_SECCION, seccion)
            args.putString(ARG_MATERIA, materia)
            fragment.arguments = args
            return fragment
        }
    }

    private val RC_SIGN_IN = 100
    private val RC_AUDIO_PERM = 1001
    private var pendingAudioFile: File? = null

    private lateinit var database: DatabaseReference
    private lateinit var adapter: EstudianteAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerEvaluaciones: Spinner
    private lateinit var etSearch: EditText
    private lateinit var ivClearSearch: ImageView
    private lateinit var btnDictado: ImageButton
    
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
    private var currentQuery: String = ""
    private var pendingSelectEvalId: String? = null
    
    private var estudiantesListener: ValueEventListener? = null
    private var evaluacionesListener: ValueEventListener? = null
    private var notasListener: ValueEventListener? = null
    private var observacionesListener: ValueEventListener? = null
    
    private var voskModel: Model? = null
    private var voskSpeechService: SpeechService? = null
    private var nativeSpeechRecognizer: SpeechRecognizer? = null
    private var audioRecorder: AudioRecorder? = null
    private var generativeModel: GenerativeModel? = null
    var isDictationMode = false
        private set
    private var isVoskLoading = false
    private var currentDictationMatchId: String? = null
    private var currentDictationSuggestedGrade: Double? = null
    private var geminiStartTime: Long = 0
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            nrc = it.getString(ARG_NRC)
            codigoMateria = it.getString(ARG_CODIGO_MATERIA)
            seccion = it.getString(ARG_SECCION)
            materia = it.getString(ARG_MATERIA)
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

        btnDictado.setOnClickListener {
            val mode = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DICTATION_MODE, MODE_VOSK)
            if (mode != MODE_GEMINI) {
                iniciarFlujoDictado()
            }
        }

        btnDictado.setOnTouchListener { v, event ->
            val mode = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DICTATION_MODE, MODE_VOSK)
            if (mode == MODE_GEMINI) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        empezarDictadoGemini()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        detenerDictadoGemini()
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
        
        adapter = EstudianteAdapter(emptyList(), false, { alumno ->
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

        if (nrc != null) {
            database = FirebaseDatabase.getInstance().reference
            cargarDatos()
            
            spinnerEvaluaciones.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
                    actualizarLista()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            if (currentQuery.isNotEmpty()) {
                etSearch.setText(currentQuery)
                ivClearSearch.visibility = View.VISIBLE
            }
        }
        
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DICTATION_MODE, MODE_VOSK) == MODE_VOSK) {
            initVoskModel()
        }
    }

    private fun getEvalDisplayName(eval: Evaluacion): String {
        return if (eval.esExtra == 1) eval.nombre else "${eval.nombre} (${eval.valor} pts)"
    }

    private fun initVoskModel() {
        if (voskModel != null || isVoskLoading) return
        
        isVoskLoading = true
        StorageService.unpack(requireContext(), "model-es", "model",
            { model: Model ->
                voskModel = model
                isVoskLoading = false
                Log.d("VoskDictado", "Modelo cargado exitosamente")
                activity?.runOnUiThread {
                    Toast.makeText(context, "Modelo de voz listo", Toast.LENGTH_SHORT).show()
                }
            },
            { exception: Exception ->
                isVoskLoading = false
                Log.e("VoskDictado", "Error cargando modelo: ${exception.message}")
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error cargando modelo: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    fun handleBackPress(): Boolean {
        if (isDictationMode && currentDictationMatchId != null) {
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
            actualizarLista()
            return true
        } else if (isDictationMode) {
            detenerDictado()
            return true
        }
        return false
    }

    private fun cargarDatos() {
        val pathDetalles = database.child("seccion_detalles").child(nrc!!)
        
        removeListeners() // Limpiar si ya había alguno

        estudiantesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                listaAlumnosOriginal.clear()
                for (s in snapshot.children) {
                    s.getValue(Estudiante::class.java)?.let { listaAlumnosOriginal.add(it) }
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        pathDetalles.child("estudiantes").addValueEventListener(estudiantesListener!!)

        evaluacionesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                evaluaciones.clear()
                val nombresEval = mutableListOf(ACUMULADO_TEXT)
                for (s in snapshot.children) {
                    s.getValue(Evaluacion::class.java)?.let { 
                        evaluaciones.add(it)
                        nombresEval.add(getEvalDisplayName(it))
                    }
                }
                
                val currentContext = context ?: return
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
            override fun onCancelled(error: DatabaseError) {}
        }
        pathDetalles.child("evaluaciones").addValueEventListener(evaluacionesListener!!)

        notasListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                todasLasNotas.clear()
                for (evalSnapshot in snapshot.children) {
                    val evalId = evalSnapshot.key ?: continue
                    val notasMap = mutableMapOf<String, Double>()
                    for (notaSnapshot in evalSnapshot.children) {
                        val matricula = notaSnapshot.key ?: continue
                        val valor = notaSnapshot.getValue(Double::class.java) ?: 0.0
                        notasMap[matricula] = valor
                    }
                    todasLasNotas[evalId] = notasMap
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        pathDetalles.child("notas").addValueEventListener(notasListener!!)

        observacionesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
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
        pathDetalles.child("observaciones").addValueEventListener(observacionesListener!!)
    }

    private fun removeListeners() {
        if (nrc == null) return
        val pathDetalles = database.child("seccion_detalles").child(nrc!!)
        estudiantesListener?.let { pathDetalles.child("estudiantes").removeEventListener(it) }
        evaluacionesListener?.let { pathDetalles.child("evaluaciones").removeEventListener(it) }
        notasListener?.let { pathDetalles.child("notas").removeEventListener(it) }
        observacionesListener?.let { pathDetalles.child("observaciones").removeEventListener(it) }
        
        estudiantesListener = null
        evaluacionesListener = null
        notasListener = null
        observacionesListener = null
    }

    private fun actualizarLista() {
        if (!isAdded) return
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val listaMostrable = mutableListOf<GradableEstudiante>()
        
        val alumnosFiltrados = if (currentQuery.isEmpty()) {
            listaAlumnosOriginal
        } else {
            listaAlumnosOriginal.filter { 
                it.nombre.contains(currentQuery, ignoreCase = true) || 
                it.matricula.contains(currentQuery)
            }
        }

        if (seleccion == ACUMULADO_TEXT) {
            alumnosFiltrados.forEach { est ->
                var total = 0.0
                var hasAnyGrade = false
                todasLasNotas.values.forEach { notasMap ->
                    if (notasMap.containsKey(est.matricula)) {
                        total += (notasMap[est.matricula] ?: 0.0)
                        hasAnyGrade = true
                    }
                }
                val displayedNota = if (hasAnyGrade) total else -1.0
                listaMostrable.add(GradableEstudiante(est.matricula, est.nombre, displayedNota, true, observacionesMap[est.matricula]))
            }
        } else {
            val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion }
            val notasActuales = todasLasNotas[evalActual?.id] ?: emptyMap<String, Double>()
            
            alumnosFiltrados.forEach { est ->
                val nota = notasActuales[est.matricula] ?: -1.0
                val gradable = GradableEstudiante(est.matricula, est.nombre, nota, false, observacionesMap[est.matricula])
                listaMostrable.add(gradable)
            }
        }
        
        adapter.updateData(listaMostrable.sortedBy { it.nombre })
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
                Toast.makeText(requireContext(), "Evaluación eliminada", Toast.LENGTH_SHORT).show()
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
            ref.child(id).setValue(eval)
        }
    }

    private fun mostrarDialogoNuevaEvaluacion() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_evaluacion, null)
        val etNombre = view.findViewById<EditText>(R.id.etNombreEval)
        val etValor = view.findViewById<EditText>(R.id.etValorEval)

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Evaluación")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = etNombre.text.toString()
                val valor = etValor.text.toString().toIntOrNull() ?: 0
                val currentNrc = nrc
                if (nombre.isNotEmpty() && valor > 0 && currentNrc != null) {
                    val id = database.child("seccion_detalles").child(currentNrc).child("evaluaciones").push().key ?: ""
                    pendingSelectEvalId = id
                    val eval = Evaluacion(id, nombre, valor, 0)
                    database.child("seccion_detalles").child(currentNrc).child("evaluaciones").child(id).setValue(eval)
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
                database.child("seccion_detalles").child(nrc!!)
                    .child("notas").child(evalActual.id).child(alumno.matricula).setValue(nuevaNota)
            }
            builder.setNeutralButton("Corregir") { _, _ ->
                val nuevaNota = etNota.text.toString().toDoubleOrNull() ?: 0.0
                AlertDialog.Builder(requireContext())
                    .setTitle("¿Sobrescribir puntos?")
                    .setMessage("Vas a poner $nuevaNota como el total de puntos extra para este estudiante. ¿Estás seguro?")
                    .setPositiveButton("SÍ, SOBRESCRIBIR") { _, _ ->
                        database.child("seccion_detalles").child(nrc!!)
                            .child("notas").child(evalActual.id).child(alumno.matricula).setValue(nuevaNota)
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
                    ref.removeValue()
                } else {
                    val nota = input.toDoubleOrNull() ?: 0.0
                    if (nota <= evalActual.valor) {
                        ref.setValue(nota)
                    } else {
                        Toast.makeText(requireContext(), "Error: La nota no puede superar el valor máximo", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            builder.setNeutralButton("100 %") { _, _ ->
                database.child("seccion_detalles").child(nrc!!)
                    .child("notas").child(evalActual.id).child(alumno.matricula).setValue(evalActual.valor.toDouble())
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
        val notas = notasMap.values.filter { it >= 0 }

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
                
                if (newMode == MODE_VOSK && voskModel == null) {
                    initVoskModel()
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun iniciarFlujoDictado() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString()
        if (seleccion == null || seleccion == ACUMULADO_TEXT) {
            Toast.makeText(requireContext(), "Selecciona una evaluación primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (requireActivity().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.GET_ACCOUNTS), RC_AUDIO_PERM)
            return
        }

        if (isDictationMode) {
            detenerDictado()
        } else {
            empezarDictado()
        }
    }

    private var voskFinalBuffer = StringBuilder()

    private fun empezarDictado() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_DICTATION_MODE, MODE_VOSK)
        
        if (mode == MODE_VOSK) {
            empezarDictadoVosk()
        } else {
            empezarDictadoNative()
        }
    }

    private fun empezarDictadoNative() {
        if (nativeSpeechRecognizer != null) return
        
        isDictationMode = true
        adapter.isDictationMode = true
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = "DICTADO NATIVO (Escuchando...)"
        btnDictado.setColorFilter(android.graphics.Color.RED)
        
        // Limpiar instancia previa si existe para evitar el error 10 (Too many requests)
        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        
        var isFirstReady = true
        nativeSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isFirstReady) {
                    Toast.makeText(requireContext(), "Dictado nativo listo. Habla ahora.", Toast.LENGTH_SHORT).show()
                    isFirstReady = false
                }
            }
            override fun onBeginningOfSpeech() {
                Log.d("DictadoNativo", "Inicio de habla detectado")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("DictadoNativo", "Fin de habla detectado")
            }
            override fun onError(error: Int) {
                Log.e("DictadoNativo", "Error de dictado: $error")
                val errorMsg = when(error) {
                    1 -> "Network timeout"
                    2 -> "Network error"
                    3 -> "Audio error"
                    4 -> "Server error"
                    5 -> "Client error"
                    6 -> "Speech timeout"
                    7 -> "No match"
                    8 -> "Busy"
                    9 -> "Insufficient permissions"
                    10 -> "Too many requests"
                    11 -> "Server disconnected"
                    12 -> "Language not supported"
                    13 -> "Language unavailable"
                    else -> "Unknown error ($error)"
                }
                
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error Dictado: $errorMsg", Toast.LENGTH_SHORT).show()
                }

                if (isDictationMode && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH || error == 7)) {
                    // Añadir un pequeño retraso para evitar el error 10 (Too many requests)
                    view?.postDelayed({
                        if (isDictationMode) nativeSpeechRecognizer?.startListening(intent)
                    }, 500)
                } else {
                    detenerDictado()
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    procesarTextoDictado(matches[0], true)
                }
                if (isDictationMode) {
                    nativeSpeechRecognizer?.startListening(intent)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    procesarTextoDictado(matches[0], false)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        nativeSpeechRecognizer?.startListening(intent)
        actualizarLista()
    }

    private fun empezarDictadoGemini() {
        if (audioRecorder == null) audioRecorder = AudioRecorder(requireContext())
        
        isDictationMode = true
        adapter.isDictationMode = true
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = "GEMINI (Grabando...)"
        btnDictado.setColorFilter(android.graphics.Color.BLUE)
        
        geminiStartTime = System.currentTimeMillis()
        audioRecorder?.startRecording("gemini_audio")
        actualizarLista()
    }

    private fun detenerDictadoGemini() {
        isDictationMode = false
        adapter.isDictationMode = false
        updateActionBarTitle()
        btnDictado.clearColorFilter()
        
        val duration = System.currentTimeMillis() - geminiStartTime
        val audioFile = audioRecorder?.stopRecording()
        
        if (duration > 500) {
            if (audioFile != null && audioFile.exists()) {
                procesarAudioConGemini(audioFile)
            }
        } else {
            // Ignorar grabaciones demasiado cortas para ahorrar cuota
            Log.d("GeminiDictado", "Grabación ignorada por ser demasiado corta ($duration ms)")
        }
        
        actualizarLista()
    }

    private fun procesarAudioConGemini(audioFile: File) {
        val GEMINI_API_KEY = "AIzaSyCqE6brL3uq0mP3YVgRc9QmGDUvgHzJNaY"
        
        if (generativeModel == null) {
            generativeModel = GenerativeModel(
                modelName = "gemini-flash-latest",
                apiKey = GEMINI_API_KEY
            )
        }

        val alumnosContext = listaAlumnosOriginal.joinToString("\n") { 
            "${it.matricula}: ${it.nombre}" 
        }

        val systemPrompt = """
            Eres un asistente de calificación para la UASD. 
            Recibirás un audio y una lista de estudiantes. 
            Tu objetivo es identificar qué nota se le asigna a qué estudiante.
            
            REGLAS:
            1. Solo devuelve un objeto JSON válido.
            2. Formato: {"updates": [{"id": "ID_ESTUDIANTE", "grade": NOTA_NUMERICA}], "errors": [{"query": "texto", "grade": NOTA}]}
            3. Si no escuchas una nota clara para alguien, ignóralo.
            4. Si escuchas algo que parece un nombre y nota pero NO está en la lista, ponlo en "errors".
            5. La lista de estudiantes es:
            $alumnosContext
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputAudio = FileInputStream(audioFile).use { it.readBytes() }
                
                val content = content {
                    text(systemPrompt)
                    blob("audio/mpeg", inputAudio)
                    text("Procesa este audio y devuelve las actualizaciones de notas.")
                }

                val response = generativeModel?.generateContent(content)
                val responseText = response?.text ?: ""
                
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(responseText)
                val jsonString = jsonMatch?.value ?: responseText
                
                withContext(Dispatchers.Main) {
                    aplicarActualizacionesGemini(jsonString)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Error Gemini: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun aplicarActualizacionesGemini(jsonString: String) {
        try {
            val obj = JSONObject(jsonString)
            
            if (obj.has("errors")) {
                val errors = obj.getJSONArray("errors")
                if (errors.length() > 0) {
                    emitirSonidoError()
                    val firstErr = errors.getJSONObject(0)
                    if (isAdded) {
                        Toast.makeText(requireContext(), "No coincidencia: ${firstErr.optString("query")}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val updates = obj.getJSONArray("updates")
            var count = 0
            
            for (i in 0 until updates.length()) {
                val update = updates.getJSONObject(i)
                val studentId = update.getString("id")
                val grade = update.getDouble("grade")
                
                val est = listaAlumnosOriginal.find { it.matricula == studentId }
                if (est != null) {
                    val gradable = GradableEstudiante(est.matricula, est.nombre)
                    guardarNotaDictado(gradable, grade)
                    count++
                }
            }
            
            if (count > 0 && isAdded) {
                Toast.makeText(requireContext(), "Gemini actualizó $count notas", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    private fun empezarDictadoVosk() {
        if (voskModel == null) {
            if (isVoskLoading) {
                Toast.makeText(requireContext(), "El modelo aún se está cargando...", Toast.LENGTH_SHORT).show()
            } else {
                initVoskModel()
            }
            return
        }

        isDictationMode = true
        adapter.isDictationMode = true
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = "DICTADO VOSK (Escuchando...)"
        btnDictado.setColorFilter(android.graphics.Color.RED)
        voskFinalBuffer.setLength(0)
        
        try {
            val nombres = listaAlumnosOriginal.flatMap { 
                it.nombre.lowercase().replace(",", "").replace(".", "").split(" ") 
            }.distinct().filter { it.length > 2 }
            
            val nombresCompletos = listaAlumnosOriginal.map { it.nombre.lowercase() }
            val matriculas = listaAlumnosOriginal.map { it.matricula }
            val numerosDigitos = (0..100).map { it.toString() }
            val numerosPalabras = listOf(
                "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
                "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
                "veintiuno", "veintidós", "veintitres", "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve", "treinta",
                "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa", "cien", "punto", "coma"
            )
            
            val grammarList = (nombres + nombresCompletos + matriculas + numerosDigitos + numerosPalabras + listOf("[unk]")).distinct()
            val grammarJson = org.json.JSONArray(grammarList).toString()
            
            val rec = Recognizer(voskModel, 16000.0f, grammarJson)
            voskSpeechService = SpeechService(rec, 16000.0f)
            voskSpeechService?.startListening(object : VoskRecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("partial")
                    if (text.isNotEmpty()) {
                        val currentTotal = if (voskFinalBuffer.isEmpty()) text else "${voskFinalBuffer} $text"
                        procesarTextoDictado(currentTotal, false)
                    }
                }

                override fun onResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("text")
                    if (text.isNotEmpty()) {
                        if (voskFinalBuffer.isNotEmpty()) voskFinalBuffer.append(" ")
                        voskFinalBuffer.append(text)
                        procesarTextoDictado(voskFinalBuffer.toString(), true)
                    }
                }

                override fun onFinalResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("text")
                    if (text.isNotEmpty()) {
                        if (voskFinalBuffer.isNotEmpty()) voskFinalBuffer.append(" ")
                        voskFinalBuffer.append(text)
                        procesarTextoDictado(voskFinalBuffer.toString(), true)
                    }
                }

                override fun onError(exception: Exception) {}
                override fun onTimeout() {}
            })
            
            actualizarLista()
        } catch (e: Exception) {
            detenerDictado()
        }
    }

    private fun detenerDictado() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_DICTATION_MODE, MODE_VOSK)
        
        if (mode == MODE_VOSK) {
            detenerDictadoVosk()
        } else {
            detenerDictadoNative()
        }
    }

    private fun detenerDictadoNative() {
        isDictationMode = false
        adapter.isDictationMode = false
        updateActionBarTitle()
        btnDictado.clearColorFilter()
        
        nativeSpeechRecognizer?.stopListening()
        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = null
        
        savedMatchesThisSession.clear()
        actualizarLista()
    }

    private fun detenerDictadoVosk() {
        isDictationMode = false
        adapter.isDictationMode = false
        updateActionBarTitle()
        btnDictado.clearColorFilter()
        
        voskSpeechService?.stop()
        voskSpeechService = null
        
        currentDictationMatchId = null
        currentDictationSuggestedGrade = null
        actualizarLista()
    }

    private fun updateActionBarTitle() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = if (materia != null) {
            if (!seccion.isNullOrEmpty()) "$materia - $seccion" else materia
        } else {
            "Lista de Estudiantes"
        }
    }

    private fun procesarTextoDictado(texto: String, esFinal: Boolean) {
        if (texto.isEmpty()) return
        
        val wordToNum = mapOf(
            "cero" to 0, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
            "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
            "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciséis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
            "veintiuno" to 21, "veintidós" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25,
            "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30,
            "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90, "cien" to 100
        )

        val numWordsRegex = wordToNum.keys.joinToString("|")
        val namePattern = "(?:(?!\\b(?:$numWordsRegex|\\d+)\\b).)+"
        val blockRegex = Regex("($namePattern)\\s+\\b(\\d{1,3}([.,]\\d+)?|$numWordsRegex)\\b", RegexOption.IGNORE_CASE)
        val matches = blockRegex.findAll(texto).toList()
        
        var lastMatchEnd = -1
        
        for (m in matches) {
            val query = m.groupValues[1].trim()
            val gradeStr = m.groupValues[2].lowercase()
            
            // Si el nombre es demasiado corto, probablemente es ruido, lo ignoramos incluso como error
            if (query.length < 3) {
                lastMatchEnd = m.range.last
                continue
            }

            val grade: Double = if (gradeStr.contains(Regex("\\d"))) {
                gradeStr.replace(',', '.').toDoubleOrNull() ?: continue
            } else {
                wordToNum[gradeStr]?.toDouble() ?: continue
            }
            
            val namesList = listaAlumnosOriginal.map { it.nombre }
            val matchResult = FuzzyMatcher.findBestMatch(query, namesList, "name")
            
            val idsList = listaAlumnosOriginal.map { it.matricula }
            val idMatchResult = FuzzyMatcher.findBestMatch(query, idsList, "id")
            
            var bestStudentId: String? = null
            var bestScore = 0.0

            if (matchResult != null) {
                if (matchResult.nivelCoincidencia >= 0.5) {
                    bestStudentId = listaAlumnosOriginal[matchResult.index].matricula
                    bestScore = matchResult.nivelCoincidencia
                }
            }

            if (idMatchResult != null) {
                if (idMatchResult.nivelCoincidencia >= 0.8) {
                    if (idMatchResult.nivelCoincidencia > bestScore) {
                        bestStudentId = listaAlumnosOriginal[idMatchResult.index].matricula
                    }
                }
            }

            if (bestStudentId != null) {
                val sessionKey = "${bestStudentId}_${grade}"
                if (!savedMatchesThisSession.contains(sessionKey)) {
                    val studentObj = listaAlumnosOriginal.find { it.matricula == bestStudentId }
                    if (studentObj != null) {
                        val gradable = GradableEstudiante(studentObj.matricula, studentObj.nombre)
                        guardarNotaDictado(gradable, grade)
                        resaltarYDesplazarHacia(bestStudentId)
                        savedMatchesThisSession.add(sessionKey)
                    }
                }
                lastMatchEnd = m.range.last
            } else {
                if (esFinal) emitirSonidoError()
                lastMatchEnd = m.range.last
            }
        }
        
        if (lastMatchEnd != -1 && isDictationMode) {
            val rest = if (lastMatchEnd + 1 < voskFinalBuffer.length) {
                voskFinalBuffer.substring(lastMatchEnd + 1)
            } else ""
            voskFinalBuffer.setLength(0)
            voskFinalBuffer.append(rest.trim())
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
            recyclerView.smoothScrollToPosition(posicion)
            adapter.highlightedStudentId = matricula
            adapter.notifyItemChanged(posicion)
            
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(1500)
                if (isAdded && adapter.highlightedStudentId == matricula) {
                    adapter.highlightedStudentId = null
                    adapter.notifyItemChanged(posicion)
                }
            }
        }
    }

    private fun guardarNotaDictado(alumno: GradableEstudiante, nota: Double?) {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        val ref = database.child("seccion_detalles").child(nrc!!)
            .child("notas").child(evalActual.id).child(alumno.matricula)

        if (nota == null) {
            ref.removeValue()
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
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
            ref.setValue(nuevaNota)
            emitirSonidoExito()
            val msg = if (evalActual.esExtra == 1) "Puntos sumados a ${alumno.nombre}" else "Nota asignada a ${alumno.nombre}"
            if (isAdded) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
            actualizarLista()
        } else {
            Toast.makeText(requireContext(), "Error: La nota supera el valor máximo (${evalActual.valor})", Toast.LENGTH_SHORT).show()
        }
    }

    fun mostrarDialogoGrabacion() {
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 50, 50, 50)
        layout.gravity = android.view.Gravity.CENTER

        val statusText = TextView(requireContext())
        statusText.text = "Presiona GRABAR para iniciar"
        statusText.textSize = 18f
        statusText.gravity = android.view.Gravity.CENTER
        layout.addView(statusText)

        val recorder = AudioRecorder(requireContext())
        var isRecording = false
        var fileToUpload: File? = null

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Dictado de Notas")
            .setView(layout)
            .setPositiveButton("Enviar") { _, _ -> 
                if (fileToUpload != null) {
                    pendingAudioFile = fileToUpload
                    requestDriveSignIn()
                }
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("GRABAR") { _, _ -> }

        val dialog = builder.create()
        dialog.show()

        val btnAction = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        btnAction.setOnClickListener {
            if (!isRecording) {
                val codigo = codigoMateria ?: "MAT"
                val sec = seccion ?: "SEC"
                val evalName = spinnerEvaluaciones.selectedItem?.toString() ?: "Eval"
                val safeName = "${codigo}_${sec}_${evalName}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
                
                if (recorder.startRecording(safeName)) {
                    isRecording = true
                    statusText.text = "GRABANDO... (Hable ahora)"
                    statusText.setTextColor(android.graphics.Color.RED)
                    btnAction.text = "DETENER"
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                }
            } else {
                fileToUpload = recorder.stopRecording()
                isRecording = false
                statusText.text = "Grabación finalizada.\nListo para enviar."
                statusText.setTextColor(android.graphics.Color.BLACK)
                btnAction.text = "GRABAR DE NUEVO"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    private fun requestDriveSignIn() {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        val client = GoogleSignIn.getClient(requireActivity(), signInOptions)
        client.signOut().addOnCompleteListener {
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }
    }

    private fun uploadAudioToDrive(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, file: File) {
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setPadding(50, 50, 50, 50)
        layout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        val progressBar = ProgressBar(requireContext())
        progressBar.isIndeterminate = true
        layout.addView(progressBar)
        
        val tvMessage = TextView(requireContext())
        tvMessage.text = "Subiendo audio a Drive..."
        tvMessage.textSize = 16f
        tvMessage.setPadding(30, 0, 0, 0)
        layout.addView(tvMessage)

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(layout)
            .setCancelable(false)
            .create()
            
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val driveService = DriveServiceHelper.getDriveService(requireContext(), account)
                val folderId = driveService.createFolderIfNotExist("UASD_Audios_Notas")
                val fileId = driveService.uploadFile(file, "audio/mp4", folderId)
                
                withContext(Dispatchers.Main) {
                   tvMessage.text = "Audio subido. Conectando con IA..."
                }

                triggerAppsScript(fileId)

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(requireContext(), "¡Proceso terminado!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun triggerAppsScript(fileId: String) {
        val scriptUrl = "https://script.google.com/macros/s/AKfycby3fFmVkTDTBSM9ETL6LypetMhoeQGiNyJTSNeZ5lwkh4RmuWlfPBEHzPggGtcwGg0D/exec"
        
        try {
            val url = URL(scriptUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 90000
            conn.readTimeout = 90000

            val selectedName = spinnerEvaluaciones.selectedItem?.toString() ?: ""
            val evalActual = evaluaciones.find { getEvalDisplayName(it) == selectedName }
            val evalIdToSend = evalActual?.id ?: selectedName

            val jsonParam = JSONObject()
            jsonParam.put("fileId", fileId)
            jsonParam.put("evalColumn", evalIdToSend) 
            jsonParam.put("nrc", nrc)

            val os = OutputStreamWriter(conn.outputStream)
            os.write(jsonParam.toString())
            os.flush()
            os.close()

            val responseCode = conn.responseCode
            if (responseCode != 200 && responseCode != 302) {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(requireContext(), "Script falló: $responseCode", Toast.LENGTH_LONG).show()
                 }
            } else {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(requireContext(), "¡Notas procesadas! Actualizando...", Toast.LENGTH_SHORT).show()
                 }
            }

        } catch (e: Exception) {
             withContext(Dispatchers.Main) {
                 Toast.makeText(requireContext(), "Error de red: ${e.message}", Toast.LENGTH_LONG).show()
             }
        }
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
        removeListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        voskSpeechService?.stop()
        voskSpeechService = null
        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == RC_AUDIO_PERM && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mostrarDialogoGrabacion()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentQuery", currentQuery)
        
        val seleccion = spinnerEvaluaciones.selectedItem?.toString()
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion }
        outState.putString("selectedEvalId", evalActual?.id)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == AppCompatActivity.RESULT_OK && data != null) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                task.addOnSuccessListener { account ->
                    if (pendingAudioFile != null) {
                        uploadAudioToDrive(account, pendingAudioFile!!)
                    }
                }
            }
        }
    }
}
