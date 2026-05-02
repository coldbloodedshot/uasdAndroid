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

    data class SeccionEncuentroFull(
        val nrc: String,
        val nombreMateria: String,
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

        adapter = EncuentroAdapter(emptyList())
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
        // Primero obtenemos las secciones para saber sus nombres y NRCs
        database.child("secciones").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val seccionesMap = mutableMapOf<String, String>()
                for (postSnapshot in snapshot.children) {
                    val nrc = postSnapshot.child("nrc").getValue(String::class.java)
                    val nombre = postSnapshot.child("nombreMateria").getValue(String::class.java)
                    if (nrc != null && nombre != null) {
                        seccionesMap[nrc] = nombre
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

    private fun cargarDetallesEncuentros(seccionesMap: Map<String, String>) {
        database.child("seccion_detalles").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                todosLosEncuentros.clear()
                for (seccionSnapshot in snapshot.children) {
                    val nrc = seccionSnapshot.key ?: continue
                    val nombreMateria = seccionesMap[nrc] ?: "Materia Desconocida"
                    
                    val encuentrosSnapshot = seccionSnapshot.child("encuentros")
                    for (encSnapshot in encuentrosSnapshot.children) {
                        val enc = encSnapshot.getValue(Encuentro::class.java)
                        if (enc != null) {
                            todosLosEncuentros.add(SeccionEncuentroFull(nrc, nombreMateria, enc))
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
            .map { SeccionEncuentro(it.nrc, it.nombreMateria, it.encuentro.hora, it.encuentro.aula) }
            .sortedBy { it.hora }
            
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
}
