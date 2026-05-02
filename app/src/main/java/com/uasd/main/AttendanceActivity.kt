package com.uasd.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class AttendanceActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var adapter: AttendanceAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerSessions: Spinner
    private lateinit var fabAddSession: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    private var nrc: String? = null
    private val listaAlumnos = mutableListOf<Estudiante>()
    private val sesiones = mutableListOf<AsistenciaSesion>()
    private val datosAsistenciaActual = mutableMapOf<String, Boolean>() // matricula -> true (for current session)
    private val todasAsistenciasMap = mutableMapOf<String, MutableMap<String, Boolean>>() // sessionId -> { matricula -> true }
    
    private val RESUMEN_TEXT = "--- Resumen de Asistencias ---"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)

        nrc = intent.getStringExtra("NRC")
        val nombreMat = intent.getStringExtra("MATERIA")
        
        supportActionBar?.title = "Asistencias: " + (nombreMat ?: "")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerViewAttendance)
        spinnerSessions = findViewById(R.id.spinnerSessions)
        fabAddSession = findViewById(R.id.fabAddSession)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        
        adapter = AttendanceAdapter(emptyList()) { alumno, presente ->
            guardarAsistencia(alumno, presente)
        }
        recyclerView.adapter = adapter

        // Fallback: Force padding and clipToPadding via code
        recyclerView.clipToPadding = false
        val extraPadding = (120 * resources.displayMetrics.density).toInt()
        recyclerView.setPadding(recyclerView.paddingLeft, recyclerView.paddingTop, recyclerView.paddingRight, extraPadding)

        if (nrc != null) {
            database = FirebaseDatabase.getInstance().reference
            cargarDatos()
            
            fabAddSession.setOnClickListener { mostrarDialogoNuevaSesion() }
            
            spinnerSessions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    actualizarUI()
                    invalidateOptionsMenu()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_attendance, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
        val deleteItem = menu?.findItem(R.id.action_delete_session)
        val seleccion = if (::spinnerSessions.isInitialized) spinnerSessions.selectedItem?.toString() else RESUMEN_TEXT
        deleteItem?.isVisible = (seleccion != RESUMEN_TEXT && seleccion != null)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_session -> {
                mostrarDialogoEliminarSesion()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun cargarDatos() {
        val ref = database.child("seccion_detalles").child(nrc!!)
        
        // 1. Alumnos
        ref.child("estudiantes").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                listaAlumnos.clear()
                for (s in snapshot.children) {
                    s.getValue(Estudiante::class.java)?.let { listaAlumnos.add(it) }
                }
                actualizarUI()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // 2. Sesiones
        ref.child("asistencias").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                sesiones.clear()
                val nombres = mutableListOf(RESUMEN_TEXT)
                for (s in snapshot.children) {
                    s.getValue(AsistenciaSesion::class.java)?.let { 
                        sesiones.add(it)
                        nombres.add(it.nombre + " (" + it.fecha + ")")
                    }
                }
                val spinnerAdapter = ArrayAdapter(this@AttendanceActivity, android.R.layout.simple_spinner_item, nombres)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerSessions.adapter = spinnerAdapter
                
                actualizarUI()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // 3. Todos los datos de asistencia (para resumen)
        ref.child("asistencia_datos").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                todasAsistenciasMap.clear()
                for (sesionSnap in snapshot.children) {
                    val sId = sesionSnap.key ?: continue
                    val innerMap = mutableMapOf<String, Boolean>()
                    for (estSnap in sesionSnap.children) {
                        innerMap[estSnap.key!!] = true
                    }
                    todasAsistenciasMap[sId] = innerMap
                }
                actualizarUI()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun actualizarUI() {
        val seleccion = spinnerSessions.selectedItem?.toString() ?: RESUMEN_TEXT
        val listaADapter = mutableListOf<AttendanceAdapter.AttendanceModel>()
        
        if (seleccion == RESUMEN_TEXT) {
            val totalSesiones = sesiones.size
            
            listaAlumnos.forEach { est ->
                var presentes = 0
                todasAsistenciasMap.values.forEach { if (it[est.matricula] == true) presentes++ }
                listaADapter.add(AttendanceAdapter.AttendanceModel(
                    estudiante = est,
                    resumen = "$presentes / $totalSesiones"
                ))
            }
            
            findViewById<TextView>(R.id.tvTotalPresentes).text = "Total Sesiones: $totalSesiones"
            
        } else {
            val sesion = obtenerSesionSeleccionada()
            if (sesion != null) {
                val mapActual = todasAsistenciasMap[sesion.id] ?: emptyMap<String, Boolean>()
                
                listaAlumnos.forEach { est ->
                    listaADapter.add(AttendanceAdapter.AttendanceModel(
                        estudiante = est,
                        presente = mapActual[est.matricula] == true
                    ))
                }
                
                val presentes = mapActual.size
                findViewById<TextView>(R.id.tvTotalPresentes).text = "Presentes: $presentes / ${listaAlumnos.size}"
            }
        }
        
        adapter.updateData(listaADapter.sortedBy { it.estudiante.nombre })
    }

    private fun mostrarDialogoNuevaSesion() {
        val etNombre = EditText(this)
        val fechaHoy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        etNombre.hint = "Nombre opcional (ej: Clase 1)"
        
        AlertDialog.Builder(this)
            .setTitle("Nueva Sesión de Asistencia")
            .setMessage("Fecha: $fechaHoy")
            .setView(etNombre)
            .setPositiveButton("Crear") { _, _ ->
                val nombre = etNombre.text.toString().ifEmpty { fechaHoy }
                val ref = database.child("seccion_detalles").child(nrc!!).child("asistencias").push()
                val id = ref.key ?: ""
                val sesion = AsistenciaSesion(id, nombre, fechaHoy)
                ref.setValue(sesion)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoEliminarSesion() {
        val sesion = obtenerSesionSeleccionada() ?: return
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar sesión?")
            .setMessage("Se borrará la lista de '${sesion.nombre}' del ${sesion.fecha} permanentemente.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Eliminar") { _, _ ->
                val ref = database.child("seccion_detalles").child(nrc!!)
                ref.child("asistencias").child(sesion.id).removeValue()
                ref.child("asistencia_datos").child(sesion.id).removeValue()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun guardarAsistencia(alumno: Estudiante, presente: Boolean) {
        val sesion = obtenerSesionSeleccionada() ?: return
        val ref = database.child("seccion_detalles").child(nrc!!)
            .child("asistencia_datos").child(sesion.id).child(alumno.matricula)
            
        if (presente) {
            ref.setValue(true)
        } else {
            ref.removeValue()
        }
    }

    private fun obtenerSesionSeleccionada(): AsistenciaSesion? {
        val idx = spinnerSessions.selectedItemPosition
        if (idx <= 0) return null // Index 0 is RESUMEN_TEXT
        val sessionIdx = idx - 1
        return if (sessionIdx >= 0 && sessionIdx < sesiones.size) sesiones[sessionIdx] else null
    }
}
