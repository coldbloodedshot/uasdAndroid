package com.uasd.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uasd.main.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var adapter: SeccionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private var seccionesListener: ValueEventListener? = null
    private var seccionesRef: DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar Firebase
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        // Setup UI
        recyclerView = findViewById(R.id.recyclerViewSections)
        tvEmpty = findViewById(R.id.textViewEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SeccionAdapter(emptyList()) { seccion ->
            val sameSubjectSections = adapter.getData().filter { it.nombreMateria == seccion.nombreMateria }
            val selectedIndex = sameSubjectSections.indexOf(seccion)
            
            val intent = android.content.Intent(this, StudentListActivity::class.java).apply {
                putExtra("SECCIONES_LIST", ArrayList(sameSubjectSections))
                putExtra("SELECTED_INDEX", selectedIndex)
            }
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabCalendar).setOnClickListener {
            startActivity(android.content.Intent(this, CalendarActivity::class.java))
        }

        // Autenticar y cargar datos
        autenticarAnonymously()
    }

    private fun autenticarAnonymously() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    escucharSecciones()
                } else {
                    Toast.makeText(this, "Error de autenticación: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            escucharSecciones()
        }
    }

    private fun escucharSecciones() {
        seccionesRef = database.child("secciones")
        val detallesRef = database.child("seccion_detalles")

        // Escuchar cambios en secciones
        seccionesListener = object : ValueEventListener {
            override fun onDataChange( snapshotSecciones: DataSnapshot) {
                // Escuchar detalles una vez cada vez que cambian las secciones
                detallesRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshotDetalles: DataSnapshot) {
                        val listaSecciones = mutableListOf<Seccion>()
                        for (postSnapshot in snapshotSecciones.children) {
                            val seccion = postSnapshot.getValue(Seccion::class.java)
                            if (seccion != null) {
                                // Buscar encuentros en detalles
                                val nrc = seccion.nrc
                                val encuentrosList = mutableListOf<Encuentro>()
                                val encuentrosSnapshot = snapshotDetalles.child(nrc).child("encuentros")
                                for (encSnapshot in encuentrosSnapshot.children) {
                                    val enc = encSnapshot.getValue(Encuentro::class.java)
                                    if (enc != null) encuentrosList.add(enc)
                                }
                                seccion.encuentros = encuentrosList
                                listaSecciones.add(seccion)
                            }
                        }
                        
                        actualizarUI(listaSecciones)
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "Error al cargar datos: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        seccionesRef?.addValueEventListener(seccionesListener!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        seccionesListener?.let { seccionesRef?.removeEventListener(it) }
    }

    private fun actualizarUI(listaSecciones: List<Seccion>) {
        if (listaSecciones.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            supportActionBar?.title = getString(R.string.app_name)
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            supportActionBar?.title = listaSecciones[0].periodo
            adapter.updateData(listaSecciones.sortedBy { it.nombreMateria })
        }
    }
}
