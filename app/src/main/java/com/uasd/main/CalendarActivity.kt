package com.uasd.main

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class CalendarActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var adapter: EncuentroAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var daySelectorContainer: LinearLayout
    
    private val diasSemana = listOf("L", "M", "I", "J", "V", "S", "D")
    private val diasNombres = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
    private var diaSeleccionado = "L"
    
    private val todosLosEncuentros = mutableListOf<SeccionEncuentroFull>()
    private val todasLasSecciones = mutableListOf<Seccion>()

    data class SeccionEncuentroFull(
        val nrc: String,
        val seccion: Seccion,
        val encuentro: Encuentro
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        
        supportActionBar?.title = "Calendario Docente"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        daySelectorContainer = findViewById(R.id.daySelectorContainer)
        recyclerView = findViewById(R.id.recyclerViewCalendar)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Seleccionar día de hoy por defecto
        diaSeleccionado = obtenerDiaHoy()

        adapter = EncuentroAdapter(emptyList()) { seccionEncuentro ->
            val seccion = seccionEncuentro.seccion ?: return@EncuentroAdapter
            val sameSubjectSections = todasLasSecciones.filter { it.nombreMateria == seccion.nombreMateria }.sortedBy { it.nombreMateria }
            val selectedIndex = sameSubjectSections.indexOf(seccion)
            
            if (selectedIndex != -1) {
                val intent = android.content.Intent(this, StudentListActivity::class.java).apply {
                    putExtra("SECCIONES_LIST", ArrayList(sameSubjectSections))
                    putExtra("SELECTED_INDEX", selectedIndex)
                }
                startActivity(intent)
            }
        }
        recyclerView.adapter = adapter

        setupDaySelector()
        
        database = FirebaseDatabase.getInstance().reference
        cargarTodosLosEncuentros()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupDaySelector() {
        for (i in diasSemana.indices) {
            val btn = Button(this)
            val diaCode = diasSemana[i]
            btn.text = diaCode
            btn.layoutParams = LinearLayout.LayoutParams(
                120, // width in pixels approx
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 8, 8, 8) }
            
            btn.setOnClickListener {
                diaSeleccionado = diaCode
                actualizarBotonSeleccionado()
                filtrarEncuentros()
            }
            btn.tag = diaCode
            daySelectorContainer.addView(btn)
        }
        actualizarBotonSeleccionado()
    }

    private fun actualizarBotonSeleccionado() {
        for (i in 0 until daySelectorContainer.childCount) {
            val btn = daySelectorContainer.getChildAt(i) as Button
            if (btn.tag == diaSeleccionado) {
                btn.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.uasd_blue))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.light_gray))
                btn.setTextColor(Color.BLACK)
            }
        }
    }

    private fun cargarTodosLosEncuentros() {
        // Primero obtenemos las secciones
        database.child("secciones").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val seccionesMap = mutableMapOf<String, Seccion>()
                todasLasSecciones.clear()
                for (postSnapshot in snapshot.children) {
                    val seccion = postSnapshot.getValue(Seccion::class.java)
                    if (seccion != null && seccion.nrc.isNotEmpty()) {
                        seccionesMap[seccion.nrc] = seccion
                        todasLasSecciones.add(seccion)
                    }
                }
                
                // Ahora cargamos los detalles (encuentros)
                cargarDetallesEncuentros(seccionesMap)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@CalendarActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun cargarDetallesEncuentros(seccionesMap: Map<String, Seccion>) {
        database.child("seccion_detalles").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                todosLosEncuentros.clear()
                for (seccionSnapshot in snapshot.children) {
                    val nrc = seccionSnapshot.key ?: continue
                    val seccion = seccionesMap[nrc] ?: continue
                    
                    val encuentrosSnapshot = seccionSnapshot.child("encuentros")
                    for (encSnapshot in encuentrosSnapshot.children) {
                        val enc = encSnapshot.getValue(Encuentro::class.java)
                        if (enc != null) {
                            todosLosEncuentros.add(SeccionEncuentroFull(nrc, seccion, enc))
                        }
                    }
                }
                filtrarEncuentros()
            }

            override fun onCancelled(error: DatabaseError) { /* ignore */ }
        })
    }

    private fun filtrarEncuentros() {
        val listaFiltrada = todosLosEncuentros.filter { it.encuentro.dias.contains(diaSeleccionado) }
            .map { SeccionEncuentro(it.nrc, it.seccion.nombreMateria, it.encuentro.hora, it.encuentro.aula, it.seccion) }
            .sortedBy { parseHoraToMinutes(it.hora) }
            
        adapter.updateData(listaFiltrada)
        
        // Actualizar subtitulo con el nombre del día
        val nombreDia = diasNombres[diasSemana.indexOf(diaSeleccionado)]
        supportActionBar?.subtitle = "Clases para el $nombreDia"
    }

    private fun obtenerDiaHoy(): String {
        val calendar = java.util.Calendar.getInstance()
        return when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "L"
            java.util.Calendar.TUESDAY -> "M"
            java.util.Calendar.WEDNESDAY -> "I"
            java.util.Calendar.THURSDAY -> "J"
            java.util.Calendar.FRIDAY -> "V"
            java.util.Calendar.SATURDAY -> "S"
            else -> "D"
        }
    }

    private fun parseHoraToMinutes(horaString: String): Int {
        try {
            val lowerHora = horaString.lowercase()
            val parts = lowerHora.split("-")
            val startPart = parts[0].trim()
            
            val timeParts = startPart.split(":")
            if (timeParts.size >= 2) {
                var hour = timeParts[0].filter { it.isDigit() }.toIntOrNull() ?: 0
                val minutes = timeParts[1].filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
                
                val isPm = lowerHora.contains("pm") || lowerHora.contains("p.m")
                val isAm = lowerHora.contains("am") || lowerHora.contains("a.m")
                
                if (hour in 1..6) {
                    // Clases con hora 1 a 6 son de la tarde (1 PM a 6 PM)
                    hour += 12
                } else if (hour < 12) {
                    if (startPart.contains("pm") || startPart.contains("p.m")) {
                        hour += 12
                    } else if (isPm && !isAm) {
                        // Si dice PM al final pero no tiene AM, entonces el inicio también es PM (ej: "7:00 - 9:00 PM")
                        hour += 12
                    }
                }
                
                return hour * 60 + minutes
            }
        } catch (e: Exception) {
            // Ignorar y devolver un valor alto para que quede al final si falla
        }
        return Int.MAX_VALUE
    }
}
