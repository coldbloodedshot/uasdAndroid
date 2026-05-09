package com.uasd.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

class StudentListActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var secciones: List<Seccion>
    private var selectedIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_list)

        secciones = intent.getSerializableExtra("SECCIONES_LIST") as? List<Seccion> ?: emptyList()
        selectedIndex = intent.getIntExtra("SELECTED_INDEX", 0)

        if (secciones.isEmpty()) {
            finish()
            return
        }

        viewPager = findViewById(R.id.viewPager)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = secciones.size
            override fun createFragment(position: Int): Fragment {
                val s = secciones[position]
                return StudentListFragment.newInstance(s.nrc, s.codigoMateria, s.claveSeccion, s.nombreMateria)
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateTitle(position)
                invalidateOptionsMenu()
            }
        })

        viewPager.setCurrentItem(selectedIndex, false)
        updateTitle(selectedIndex)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun updateTitle(position: Int) {
        val s = secciones[position]
        supportActionBar?.title = "${s.nombreMateria} - ${s.claveSeccion}"
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
        return supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}") as? StudentListFragment
    }

    // Menu delegation
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_student_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val fragment = getCurrentFragment()
        val deleteItem = menu?.findItem(R.id.action_delete_eval)
        val statsItem = menu?.findItem(R.id.action_stats)

        
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
