package com.uasd.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EstudianteAdapter(
    var estudiantes: List<GradableEstudiante>,
    var isDictationMode: Boolean = false,
    private val onGradeClick: (GradableEstudiante) -> Unit,
    private val onNoteClick: (GradableEstudiante) -> Unit,
    private val onDictationApproveClick: (GradableEstudiante, Double?) -> Unit
) : RecyclerView.Adapter<EstudianteAdapter.ViewHolder>() {

    var highlightedStudentId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMatricula: TextView = view.findViewById(R.id.tvMatricula)
        val tvNombre: TextView = view.findViewById(R.id.tvNombre)
        val tvNota: TextView = view.findViewById(R.id.tvNota)
        val ivObservacion: android.widget.ImageView = view.findViewById(R.id.ivObservacion)
        val tvObservacionPreview: TextView = view.findViewById(R.id.tvObservacionPreview)
        val layoutDictationApproval: LinearLayout = view.findViewById(R.id.layoutDictationApproval)
        val etDictationGrade: EditText = view.findViewById(R.id.etDictationGrade)
        val btnApproveDictation: Button = view.findViewById(R.id.btnApproveDictation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_estudiante, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = estudiantes[position]
        
        // Background coloring with highlight support
        val color = if (item.matricula == highlightedStudentId) {
            android.graphics.Color.parseColor("#E0F7FA") // Cyan muy claro para resaltar
        } else if (position % 2 == 0) {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.white)
        } else {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.light_gray)
        }
        holder.itemView.setBackgroundColor(color)
        
        holder.tvMatricula.text = item.matricula
        holder.tvNombre.text = item.nombre
        
        // Setup Icon and Preview Observation
        if (item.observacion != null && item.observacion!!.isNotEmpty()) {
            holder.ivObservacion.setColorFilter(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.purple_500))
            holder.tvObservacionPreview.visibility = View.VISIBLE
            holder.tvObservacionPreview.text = item.observacion
        } else {
            holder.ivObservacion.setColorFilter(android.graphics.Color.parseColor("#888888"))
            holder.tvObservacionPreview.visibility = View.GONE
        }
        holder.ivObservacion.setOnClickListener { onNoteClick(item) }

        // Ocultar layout de dictado (ya no se usa confirmación manual)
        holder.layoutDictationApproval.visibility = View.GONE

        // Displaying grade or special text
        if (item.esTotal) {
            holder.tvNota.text = if (item.nota > -1) String.format("%.1f", item.nota) else "-"
            holder.tvNota.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.purple_700))
            holder.tvNota.setOnClickListener { onGradeClick(item) }
        } else {
            holder.tvNota.text = if (item.nota > -1) String.format("%.1f", item.nota) else "-"
            holder.tvNota.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.uasd_blue))
            holder.tvNota.setOnClickListener { onGradeClick(item) }
        }
    }

    override fun getItemCount() = estudiantes.size

    fun updateData(newEstudiantes: List<GradableEstudiante>) {
        estudiantes = newEstudiantes
        notifyDataSetChanged()
    }
}
