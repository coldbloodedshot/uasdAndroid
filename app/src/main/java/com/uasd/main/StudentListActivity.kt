package com.uasd.main

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.io.File
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent

class StudentListActivity : AppCompatActivity() {

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
    
    private val savedMatchesThisSession = mutableSetOf<String>() // Para evitar duplicados en onPartialResults
    
    // Speed Dial FABs
    private lateinit var fabMain: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabAttendance: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabNewEval: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabExtraPoints: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    private lateinit var layoutFabAttendance: android.widget.LinearLayout
    private lateinit var layoutFabNewEval: android.widget.LinearLayout
    private lateinit var layoutFabExtra: android.widget.LinearLayout
    
    private var isFabOpen = false
    
    private var nrc: String? = null
    private var codigoMateria: String? = null
    private var seccion: String? = null
    private var currentQuery: String = ""
    private var pendingSelectEvalId: String? = null
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isDictationMode = false
    private var currentDictationMatchId: String? = null
    private var currentDictationSuggestedGrade: Double? = null
    
    private val PREFS_NAME = "uasd_prefs"
    private val KEY_LAST_EXTRA = "last_extra_points"
    private val listaAlumnosOriginal = mutableListOf<Estudiante>()
    private val evaluaciones = mutableListOf<Evaluacion>()
    private val todasLasNotas = mutableMapOf<String, MutableMap<String, Double>>() // evalId -> { matricula -> nota }
    private val observacionesMap = mutableMapOf<String, String>() // matricula -> nota literal
    
    private val ACUMULADO_TEXT = "--- Acumulado Total ---"

    private fun getEvalDisplayName(eval: Evaluacion): String {
        return if (eval.esExtra == 1) eval.nombre else "${eval.nombre} (${eval.valor} pts)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_list)

        nrc = intent.getStringExtra("NRC")
        codigoMateria = intent.getStringExtra("CODIGO_MATERIA")
        seccion = intent.getStringExtra("SECCION")
        val nombreMat = intent.getStringExtra("MATERIA")
        
        supportActionBar?.title = nombreMat ?: "Lista de Estudiantes"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerViewStudents)
        spinnerEvaluaciones = findViewById(R.id.spinnerEvaluaciones)
        etSearch = findViewById<EditText>(R.id.etSearch)
        ivClearSearch = findViewById(R.id.ivClearSearch)
        btnDictado = findViewById(R.id.btnDictado)

        ivClearSearch.setOnClickListener {
            etSearch.text.clear()
        }

        btnDictado.setOnClickListener {
            iniciarFlujoDictado()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        
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

        // Fallback: Force padding and clipToPadding via code
        recyclerView.clipToPadding = false
        val extraPadding = (120 * resources.displayMetrics.density).toInt()
        recyclerView.setPadding(recyclerView.paddingLeft, recyclerView.paddingTop, recyclerView.paddingRight, extraPadding)

        // Setup Speed Dial
        fabMain = findViewById(R.id.fabMain)
        fabAttendance = findViewById(R.id.fabAttendance)
        fabNewEval = findViewById(R.id.fabNewEval)
        fabExtraPoints = findViewById(R.id.fabExtraPoints)
        
        layoutFabAttendance = findViewById(R.id.fabActionAttendance)
        layoutFabNewEval = findViewById(R.id.fabActionNewEval)
        layoutFabExtra = findViewById(R.id.fabActionExtra)

        fabMain.setOnClickListener { toggleFabMenu() }
        
        fabAttendance.setOnClickListener {
            closeFabMenu()
            val intent = android.content.Intent(this, AttendanceActivity::class.java).apply {
                putExtra("NRC", nrc)
                putExtra("MATERIA", supportActionBar?.title?.toString())
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
                    // Invalidate options menu to show/hide delete option based on selection
                    invalidateOptionsMenu()
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
        } else {
            Toast.makeText(this, "Error: NRC no proporcionado", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isDictationMode && currentDictationMatchId != null) {
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
            actualizarLista()
            return true
        } else if (isDictationMode) {
            detenerDictado()
            return true
        }
        finish()
        return true
    }

    override fun onBackPressed() {
        if (isDictationMode && currentDictationMatchId != null) {
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
            actualizarLista()
        } else if (isDictationMode) {
            detenerDictado()
        } else {
            super.onBackPressed()
        }
    }

    private fun cargarDatos() {
        val pathDetalles = database.child("seccion_detalles").child(nrc!!)
        
        // 1. Escuchar Alumnos
        pathDetalles.child("estudiantes").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                listaAlumnosOriginal.clear()
                for (s in snapshot.children) {
                    s.getValue(Estudiante::class.java)?.let { listaAlumnosOriginal.add(it) }
                }
                actualizarLista()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // 2. Escuchar Evaluaciones
        pathDetalles.child("evaluaciones").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                evaluaciones.clear()
                val nombresEval = mutableListOf(ACUMULADO_TEXT)
                for (s in snapshot.children) {
                    s.getValue(Evaluacion::class.java)?.let { 
                        evaluaciones.add(it)
                        nombresEval.add(getEvalDisplayName(it))
                    }
                }
                val spinnerAdapter = ArrayAdapter(this@StudentListActivity, android.R.layout.simple_spinner_item, nombresEval)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerEvaluaciones.adapter = spinnerAdapter

                // Auto-seleccionar si acabamos de crear una evaluación o Puntos Extra
                val seleccionActual = spinnerEvaluaciones.selectedItem?.toString()
                
                if (pendingSelectEvalId != null) {
                    val targetEval = evaluaciones.find { it.id == pendingSelectEvalId }
                    if (targetEval != null) {
                        val displayName = getEvalDisplayName(targetEval)
                        val pos = nombresEval.indexOf(displayName)
                        if (pos != -1) {
                            spinnerEvaluaciones.setSelection(pos)
                            pendingSelectEvalId = null // Reset after selection
                        }
                    }
                } else if (seleccionActual == null || seleccionActual == ACUMULADO_TEXT) {
                    val extraEval = evaluaciones.find { it.esExtra == 1 }
                    if (extraEval != null) {
                        val displayName = getEvalDisplayName(extraEval)
                        val pos = nombresEval.indexOf(displayName)
                        if (pos != -1) spinnerEvaluaciones.setSelection(pos)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // 3. Escuchar Notas
        pathDetalles.child("notas").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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
        })

        // 4. Escuchar Observaciones (Notitas)
        pathDetalles.child("observaciones").addValueEventListener(object : ValueEventListener {
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
        })
    }

    private fun actualizarLista() {
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
            // Obtener ID de evaluación seleccionada
            val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion }
            val notasActuales = todasLasNotas[evalActual?.id] ?: emptyMap<String, Double>()
            
            alumnosFiltrados.forEach { est ->
                val nota = notasActuales[est.matricula] ?: -1.0 // -1 significa sin nota
                val gradable = GradableEstudiante(est.matricula, est.nombre, nota, false, observacionesMap[est.matricula])
                listaMostrable.add(gradable)
            }
        }
        
        adapter.updateData(listaMostrable.sortedBy { it.nombre })
    }

    private fun mostrarDialogoEliminarEvaluacion() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return

        AlertDialog.Builder(this)
            .setTitle("¿Eliminar evaluación?")
            .setMessage("Estás a punto de borrar '${evalActual.nombre}'.\n\n¡ATENCION! Se borrarán permanentemente todas las notas registradas para esta evaluación. Esta acción no se puede deshacer.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("ELIMINAR") { _, _ ->
                val ref = database.child("seccion_detalles").child(nrc!!)
                
                // Borrar evaluación y sus notas
                ref.child("evaluaciones").child(evalActual.id).removeValue()
                ref.child("notas").child(evalActual.id).removeValue()
                
                Toast.makeText(this, "Evaluación eliminada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun gestionarPuntosExtra() {
        val extraEval = evaluaciones.find { it.esExtra == 1 }
        if (extraEval != null) {
            // Si ya existe, simplemente seleccionarla
            val displayName = getEvalDisplayName(extraEval)
            for (i in 0 until spinnerEvaluaciones.adapter.count) {
                if (spinnerEvaluaciones.adapter.getItem(i).toString() == displayName) {
                    spinnerEvaluaciones.setSelection(i)
                    break
                }
            }
        } else {
            // Si no existe, crearla y seleccionarla
            val ref = database.child("seccion_detalles").child(nrc!!).child("evaluaciones")
            val id = ref.push().key ?: ""
            pendingSelectEvalId = id // Marcar para selección automática
            val eval = Evaluacion(id, "PUNTOS EXTRA", 100, 1) // Valor alto predeterminado
            ref.child(id).setValue(eval)
        }
    }

    private fun mostrarDialogoNuevaEvaluacion() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_evaluacion, null)
        val etNombre = view.findViewById<EditText>(R.id.etNombreEval)
        val etValor = view.findViewById<EditText>(R.id.etValorEval)

        AlertDialog.Builder(this)
            .setTitle("Nueva Evaluación")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = etNombre.text.toString()
                val valor = etValor.text.toString().toIntOrNull() ?: 0
                if (nombre.isNotEmpty() && valor > 0) {
                    val id = database.child("seccion_detalles").child(nrc!!).child("evaluaciones").push().key ?: ""
                    pendingSelectEvalId = id // Marcar para selección automática
                    val eval = Evaluacion(id, nombre, valor, 0)
                    database.child("seccion_detalles").child(nrc!!).child("evaluaciones").child(id).setValue(eval)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoObservacion(alumno: GradableEstudiante) {
        val etNote = EditText(this)
        etNote.hint = "Escribe una notita aquí..."
        etNote.setText(alumno.observacion ?: "")
        etNote.setPadding(60, 40, 60, 40)

        AlertDialog.Builder(this)
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
        
        val etNota = EditText(this)
        etNota.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        
        val notaActual = if (alumno.nota > -1) alumno.nota else 0.0
        if (alumno.nota > -1) etNota.setText(alumno.nota.toString())
        
        etNota.setPadding(60, 40, 60, 40)

        val builder = AlertDialog.Builder(this)
            .setTitle(alumno.nombre)
            .setView(etNota)
            .setNegativeButton("Cancelar", null)

        if (evalActual.esExtra == 1) {
            builder.setTitle("${alumno.nombre} (Ptos Extra)")
            etNota.hint = "Cantidad a sumar (ej: 0.5)"
            
            val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
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
                AlertDialog.Builder(this)
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
                        Toast.makeText(this, "Error: La nota no puede superar el valor máximo", Toast.LENGTH_SHORT).show()
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
        val context = this
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

        // Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 20, 0, 20)
            }
            setBackgroundColor(android.graphics.Color.GRAY)
        }
        layout.addView(divider)

        // Total Row
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
            setTextColor(android.graphics.Color.parseColor("#1B5E20")) // Dark green
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

    private fun mostrarEstadisticas() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString() ?: return
        val evalActual = evaluaciones.find { getEvalDisplayName(it) == seleccion } ?: return
        
        val notasMap = todasLasNotas[evalActual.id] ?: emptyMap<String, Double>()
        val notas = notasMap.values.filter { it >= 0 }

        if (notas.isEmpty()) {
            Toast.makeText(this, "No hay notas registradas para esta evaluación", Toast.LENGTH_SHORT).show()
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

        AlertDialog.Builder(this)
            .setTitle("Estadísticas: ${evalActual.nombre}")
            .setMessage(sb.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // Menu Logic
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_student_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
        val deleteItem = menu?.findItem(R.id.action_delete_eval)
        val statsItem = menu?.findItem(R.id.action_stats)
        
        // Solo mostrar botón "Eliminar", "Dictado" y "Estadísticas" si NO estamos en "Acumulado Total"
        val seleccion = if (::spinnerEvaluaciones.isInitialized) spinnerEvaluaciones.selectedItem?.toString() else ACUMULADO_TEXT
        val isNotTotal = (seleccion != ACUMULADO_TEXT && seleccion != null)
        
        deleteItem?.isVisible = isNotTotal
        statsItem?.isVisible = isNotTotal
        
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_eval -> {
                mostrarDialogoEliminarEvaluacion()
                true
            }
            R.id.action_stats -> {
                mostrarEstadisticas()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun iniciarFlujoDictado() {
        val seleccion = spinnerEvaluaciones.selectedItem?.toString()
        if (seleccion == null || seleccion == ACUMULADO_TEXT) {
            Toast.makeText(this, "Selecciona una evaluación primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check permissions
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.GET_ACCOUNTS), 1001)
            return
        }

        if (isDictationMode) {
            detenerDictado()
        } else {
            empezarDictado()
        }
    }

    private fun empezarDictado() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "El reconocimiento de voz no está disponible o requiere configuración en tu dispositivo.", Toast.LENGTH_LONG).show()
            Log.e("DictadoVivo", "SpeechRecognizer no está disponible en el dispositivo.")
            return
        }

        isDictationMode = true
        adapter.isDictationMode = true
        supportActionBar?.title = "DICTADO EN VIVO (Escuchando...)"
        btnDictado.setColorFilter(android.graphics.Color.RED) // Cambiar a rojo cuando está activo
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-DO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("DictadoVivo", "onReadyForSpeech")
                savedMatchesThisSession.clear()
            }
            override fun onBeginningOfSpeech() {
                Log.d("DictadoVivo", "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("DictadoVivo", "onEndOfSpeech")
            }
            
            override fun onError(error: Int) {
                Log.e("DictadoVivo", "onError code: $error")
                if (isDictationMode) {
                    // Si hubo un error (ej. silencio), reiniciar el escucha para que sea continuo
                    speechRecognizer?.startListening(intent)
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("DictadoVivo", "onResults matches: $matches")
                if (!matches.isNullOrEmpty()) {
                    val texto = matches[0]
                    // Solo procesamos si el texto termina en un número (calificación o matrícula con nota)
                    if (Regex(".*\\b\\d+([.,]\\d+)?\\s*$").matches(texto)) {
                        procesarTextoDictado(texto)
                    }
                }
                if (isDictationMode) {
                    speechRecognizer?.startListening(intent)
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val texto = matches[0]
                    Log.d("DictadoVivo", "onPartialResults: $texto")
                    // Solo procesamos si el texto actual termina en un número
                    if (Regex(".*\\b\\d+([.,]\\d+)?\\s*$").matches(texto)) {
                        procesarTextoDictado(texto)
                    }
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        speechRecognizer?.startListening(intent)
        actualizarLista()
        Toast.makeText(this, "Modo dictado activado. Habla el nombre del estudiante.", Toast.LENGTH_SHORT).show()
    }

    private fun detenerDictado() {
        isDictationMode = false
        adapter.isDictationMode = false
        val nombreMat = intent.getStringExtra("MATERIA")
        supportActionBar?.title = nombreMat ?: "Lista de Estudiantes"
        btnDictado.clearColorFilter() // Volver al color original
        
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        currentDictationMatchId = null
        currentDictationSuggestedGrade = null
        actualizarLista()
        Toast.makeText(this, "Modo dictado desactivado", Toast.LENGTH_SHORT).show()
    }

    private fun procesarTextoDictado(texto: String) {
        if (texto.isEmpty()) return
        
        // Regex para extraer múltiples bloques de [Nombre/ID] [Nota]
        // [^\d]+ -> Cualquier cosa que no sea número (Nombres)
        // | \d{7,10} -> O números largos (Matrículas)
        // \s+ -> Espacio
        // \d{1,3} -> Número corto (Calificación)
        val blockRegex = Regex("([^\\d]+|[\\d]{7,10})\\s+\\b(\\d{1,3}([.,]\\d+)?)\\b")
        val matches = blockRegex.findAll(texto)
        
        for (m in matches) {
            val query = m.groupValues[1].trim()
            val numStr = m.groupValues[2].replace(',', '.')
            val grade = numStr.toDoubleOrNull() ?: continue
            
            if (query.isEmpty()) continue

            // Buscar el estudiante
            val namesList = listaAlumnosOriginal.map { it.nombre }
            val matchResult = FuzzyMatcher.findBestMatch(query, namesList, "name")
            
            val idsList = listaAlumnosOriginal.map { it.matricula }
            val idMatchResult = FuzzyMatcher.findBestMatch(query, idsList, "id")
            
            var bestStudentId: String? = null
            var bestScore = 0.0

            if (matchResult != null && matchResult.nivelCoincidencia >= 0.4 && matchResult.nivelDiscriminacion >= 0.2) {
                bestStudentId = listaAlumnosOriginal[matchResult.index].matricula
                bestScore = matchResult.nivelCoincidencia
            }

            if (idMatchResult != null && idMatchResult.nivelCoincidencia >= 0.4 && idMatchResult.nivelDiscriminacion >= 0.2) {
                if (idMatchResult.nivelCoincidencia > bestScore) {
                    bestStudentId = listaAlumnosOriginal[idMatchResult.index].matricula
                }
            }

            if (bestStudentId != null) {
                // Evitar procesar el mismo bloque (estudiante + nota) varias veces en la misma sesión de habla
                val sessionKey = "${bestStudentId}_${grade}"
                if (!savedMatchesThisSession.contains(sessionKey)) {
                    val studentObj = listaAlumnosOriginal.find { it.matricula == bestStudentId }
                    if (studentObj != null) {
                        Log.d("DictadoVivo", "Bloque detectado: $query -> $grade. Auto-guardando: $bestStudentId")
                        val gradable = GradableEstudiante(studentObj.matricula, studentObj.nombre, 0.0, false, null)
                        guardarNotaDictado(gradable, grade)
                        resaltarYDesplazarHacia(bestStudentId)
                        savedMatchesThisSession.add(sessionKey)
                    }
                }
            }
        }
    }

    private fun resaltarYDesplazarHacia(matricula: String) {
        val posicion = adapter.estudiantes.indexOfFirst { it.matricula == matricula }
        if (posicion != -1) {
            recyclerView.smoothScrollToPosition(posicion)
            adapter.highlightedStudentId = matricula
            adapter.notifyItemChanged(posicion)
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.delay(1500)
                if (adapter.highlightedStudentId == matricula) {
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
            val msg = if (evalActual.esExtra == 1) "Puntos sumados a ${alumno.nombre}" else "Nota asignada a ${alumno.nombre}"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            
            currentDictationMatchId = null
            currentDictationSuggestedGrade = null
            actualizarLista()
        } else {
            Toast.makeText(this, "Error: La nota supera el valor máximo (${evalActual.valor})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarDialogoGrabacion() {
        // Simplified Logic without custom layout for now to save steps, using programatic view
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 50, 50, 50)
        layout.gravity = android.view.Gravity.CENTER

        val statusText = TextView(this)
        statusText.text = "Presiona GRABAR para iniciar"
        statusText.textSize = 18f
        statusText.gravity = android.view.Gravity.CENTER
        layout.addView(statusText)

        val recorder = AudioRecorder(this)
        var isRecording = false
        var fileToUpload: File? = null

        val builder = AlertDialog.Builder(this)
            .setTitle("Dictado de Notas")
            .setView(layout)
            .setPositiveButton("Enviar") { _, _ -> 
                if (fileToUpload != null) {
                    pendingAudioFile = fileToUpload
                    requestDriveSignIn()
                }
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("GRABAR") { _, _ -> } // Override later

        val dialog = builder.create()
        dialog.show()

        val btnAction = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        btnAction.setOnClickListener {
            if (!isRecording) {
                // Generar nombre de archivo: CODIGO_MATERIA_SECCION_EVAL
                val codigo = codigoMateria ?: "MAT"
                val sec = seccion ?: "SEC"
                val evalName = spinnerEvaluaciones.selectedItem?.toString() ?: "Eval"
                
                // Limpiar nombre
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
            .requestScopes(Scope(DriveScopes.DRIVE)) // Full access
            .build()
        val client = GoogleSignIn.getClient(this, signInOptions)
        
        // Force sign out to reset permissions and prompts
        client.signOut().addOnCompleteListener {
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }
    }

    private fun uploadAudioToDrive(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount, file: File) {
        // Create custom layout for progress dialog
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setPadding(50, 50, 50, 50)
        layout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        val progressBar = ProgressBar(this)
        progressBar.isIndeterminate = true
        layout.addView(progressBar)
        
        val tvMessage = TextView(this)
        tvMessage.text = "Subiendo audio a Drive..."
        tvMessage.textSize = 16f
        tvMessage.setPadding(30, 0, 0, 0)
        layout.addView(tvMessage)

        val loadingDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
            
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val driveService = DriveServiceHelper.getDriveService(this@StudentListActivity, account)
                
                // 1. Crear/Buscar carpeta "UASD Audios"
                val folderId = driveService.createFolderIfNotExist("UASD_Audios_Notas")
                Log.d("Upload", "Folder ID: $folderId")
                
                // 2. Subir archivo
                val fileId = driveService.uploadFile(file, "audio/mp4", folderId)
                Log.d("Upload", "File ID: $fileId")
                
                withContext(Dispatchers.Main) {
                   tvMessage.text = "Audio subido. Conectando con IA..."
                }

                // 3. Trigger Apps Script
                triggerAppsScript(fileId)

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@StudentListActivity, "¡Proceso terminado!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@StudentListActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("Upload", "Error", e)
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
            // Increased timeout for AI processing (transcription + fuzzy matching can take time)
            conn.connectTimeout = 90000  // 90 segundos
            conn.readTimeout = 90000     // 90 segundos

            // Find Evaluation ID
            val selectedName = spinnerEvaluaciones.selectedItem?.toString() ?: ""
            val evalActual = evaluaciones.find { getEvalDisplayName(it) == selectedName }
            val evalIdToSend = evalActual?.id ?: selectedName

            val jsonParam = JSONObject()
            jsonParam.put("fileId", fileId)
            jsonParam.put("evalColumn", evalIdToSend) 
            jsonParam.put("nrc", nrc)

            Log.d("AppsScript", "Sending: $jsonParam")

            val os = OutputStreamWriter(conn.outputStream)
            os.write(jsonParam.toString())
            os.flush()
            os.close()

            val responseCode = conn.responseCode
            Log.d("AppsScript", "Response Code: $responseCode")
            
            // Read response manually to ensure connection completes
            val responseText = if (responseCode in 200..299 || responseCode == 302) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
            }
            Log.d("AppsScript", "Response: $responseText")

            if (responseCode != 200 && responseCode != 302) {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(this@StudentListActivity, "Script falló: $responseCode", Toast.LENGTH_LONG).show()
                 }
            } else {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(this@StudentListActivity, "¡Notas procesadas! Actualizando...", Toast.LENGTH_SHORT).show()
                 }
            }

        } catch (e: java.net.SocketTimeoutException) {
            Log.w("AppsScript", "Timeout - pero el proceso continúa en segundo plano", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@StudentListActivity, "Procesando en segundo plano. Las notas se actualizarán pronto.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("AppsScript", "Connection Error", e)
             withContext(Dispatchers.Main) {
                 Toast.makeText(this@StudentListActivity, "Error de red: ${e.message}", Toast.LENGTH_LONG).show()
             }
        }
    }

    // Speed Dial Logic
    private lateinit var fabMenuContainer: android.widget.LinearLayout

    private fun toggleFabMenu() {
        if (!::fabMenuContainer.isInitialized) fabMenuContainer = findViewById(R.id.fabMenuContainer)
        if (!isFabOpen) {
            showFabMenu()
        } else {
            closeFabMenu()
        }
    }

    private fun showFabMenu() {
        isFabOpen = true
        fabMain.animate().rotation(45f)
        
        if (!::fabMenuContainer.isInitialized) fabMenuContainer = findViewById(R.id.fabMenuContainer)
        fabMenuContainer.visibility = View.VISIBLE
        fabMenuContainer.alpha = 0f
        fabMenuContainer.translationY = 50f
        fabMenuContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setListener(null)
    }

    private fun closeFabMenu() {
        isFabOpen = false
        fabMain.animate().rotation(0f)
        
        if (!::fabMenuContainer.isInitialized) fabMenuContainer = findViewById(R.id.fabMenuContainer)
        fabMenuContainer.animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(200)
            .withEndAction { fabMenuContainer.visibility = View.GONE }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO_PERM && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mostrarDialogoGrabacion()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == AppCompatActivity.RESULT_OK && data != null) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                task.addOnSuccessListener { account ->
                    if (pendingAudioFile != null) {
                        uploadAudioToDrive(account, pendingAudioFile!!)
                    }
                }.addOnFailureListener {
                    Toast.makeText(this, "Error de autenticación: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
