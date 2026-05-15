package com.uasd.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class StudentListActivity : AppCompatActivity() {

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

        val fragment = StudentListFragment.newInstance(
            s.nrc, s.codigoMateria, s.claveSeccion, s.nombreMateria,
            prevEvalName, wasDictating
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
            if (i == index) {
                btn?.alpha = 1.0f
                btn?.setStrokeWidth(4)
            } else {
                btn?.alpha = 0.6f
                btn?.setStrokeWidth(0)
            }
        }
    }

    // Menu delegation
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_student_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // El fragmento decide si mostrar o no las opciones basándose en su estado (spinner selection)
        // Pero como no podemos acceder fácilmente al spinner del fragmento desde aquí de forma síncrona
        // Simplemente dejamos que el fragmento maneje el click o lo delegamos.
        // En este caso, para simplificar, dejaremos que el Activity maneje el click llamando al fragmento.
        
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val fragment = getCurrentFragment() ?: return super.onOptionsItemSelected(item)
        
        return when (item.itemId) {
            android.R.id.home -> {
                onSupportNavigateUp()
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
