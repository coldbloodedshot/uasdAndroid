package com.uasd.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class StudentListActivity : AppCompatActivity(), StudentListFragment.OnSearchListener {

    private val searchQueries = mutableMapOf<Int, ValueEventListener>()
    private val database = FirebaseDatabase.getInstance().reference

    private lateinit var containerSecciones: LinearLayout
    private lateinit var secciones: List<Seccion>
    private var selectedIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_list)

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        secciones = intent.getSerializableExtra("SECCIONES_LIST") as? List<Seccion> ?: emptyList()
        
        if (savedInstanceState != null) {
            selectedIndex = savedInstanceState.getInt("selectedIndex", 0)
        } else {
            selectedIndex = intent.getIntExtra("SELECTED_INDEX", 0)
        }

        if (secciones.isEmpty()) {
            finish()
            return
        }

        containerSecciones = findViewById(R.id.containerSecciones)
        
        setupSectionButtons()
        
        if (savedInstanceState == null) {
            loadSection(selectedIndex)
        } else {
            // If already restored, just update title and buttons
            val s = secciones.getOrNull(selectedIndex)
            if (s != null) {
                supportActionBar?.title = "${s.nombreMateria} - ${s.claveSeccion}"
                highlightButton(selectedIndex)
            }
        }
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selectedIndex", selectedIndex)
    }

    private fun setupSectionButtons() {
        containerSecciones.removeAllViews()
        secciones.forEachIndexed { index, seccion ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = seccion.claveSeccion
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(24, 8, 24, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener {
                    if (selectedIndex != index) {
                        loadSection(index)
                    }
                }
            }
            containerSecciones.addView(button)
        }
    }

    private fun loadSection(index: Int) {
        selectedIndex = index
        val s = secciones[index]
        
        // Update Title
        supportActionBar?.title = "${s.nombreMateria} - ${s.claveSeccion}"
        
        // Highlight active button
        highlightButton(index)

        // Load Fragment with state carry-over
        val currentFrag = getCurrentFragment()
        val prevEvalName = currentFrag?.getSelectedEvalName()
        val wasDictating = currentFrag?.isDictationMode == true
        val prevQuery = currentFrag?.getCurrentSearchQuery()

        val fragment = StudentListFragment.newInstance(
            s.nrc, s.codigoMateria, s.claveSeccion, s.nombreMateria,
            prevEvalName, wasDictating, prevQuery
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, "current_fragment")
            .commit()
            
        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        val fragment = getCurrentFragment()
        if (fragment?.handleBackPress() == true) return true
        finish()
        return true
    }

    override fun onBackPressed() {
        val fragment = getCurrentFragment()
        if (fragment?.handleBackPress() == true) return
        super.onBackPressed()
    }

    private fun getCurrentFragment(): StudentListFragment? {
        return supportFragmentManager.findFragmentByTag("current_fragment") as? StudentListFragment
    }

    private fun highlightButton(index: Int) {
        for (i in 0 until containerSecciones.childCount) {
            val btn = containerSecciones.getChildAt(i) as? MaterialButton
            btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            if (i == index) {
                btn?.alpha = 1.0f
                btn?.setStrokeWidth(4)
            } else {
                btn?.alpha = 0.6f
                btn?.setStrokeWidth(0)
            }
        }
    }

    override fun onEmptySearch(query: String) {
        clearSearchListeners()
        secciones.forEachIndexed { index, seccion ->
            if (index != selectedIndex) {
                val ref = database.child("seccion_detalles").child(seccion.nrc).child("estudiantes")
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        var found = false
                        for (s in snapshot.children) {
                            val est = s.getValue(Estudiante::class.java)
                            if (est != null && (est.nombre.contains(query, ignoreCase = true) || est.matricula.contains(query))) {
                                found = true
                                break
                            }
                        }
                        
                        val btn = containerSecciones.getChildAt(index) as? MaterialButton
                        if (found) {
                            btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))
                        } else {
                            btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                }
                searchQueries[index] = listener
                ref.addListenerForSingleValueEvent(listener)
            }
        }
    }

    override fun onSearchCleared() {
        clearSearchHighlights()
    }

    private fun clearSearchListeners() {
        searchQueries.clear()
    }

    private fun clearSearchHighlights() {
        for (i in 0 until containerSecciones.childCount) {
            if (i != selectedIndex) {
                val btn = containerSecciones.getChildAt(i) as? MaterialButton
                btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    // Menu delegation
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_student_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val mostrarOpcionesEval = getCurrentFragment()?.tieneEvaluacionIndividualSeleccionada() == true
        menu?.findItem(R.id.action_edit_eval_name)?.isVisible = mostrarOpcionesEval
        menu?.findItem(R.id.action_delete_eval)?.isVisible = mostrarOpcionesEval
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val fragment = getCurrentFragment() ?: return super.onOptionsItemSelected(item)
        
        return when (item.itemId) {
            android.R.id.home -> {
                onSupportNavigateUp()
                true
            }
            R.id.action_edit_eval_name -> {
                fragment.mostrarDialogoEditarNombreEvaluacion()
                true
            }
            R.id.action_delete_eval -> {
                fragment.mostrarDialogoEliminarEvaluacion()
                true
            }
            R.id.action_stats -> {
                fragment.mostrarEstadisticas()
                true
            }
            R.id.action_dictation_settings -> {
                fragment.mostrarConfiguracionDictado()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
