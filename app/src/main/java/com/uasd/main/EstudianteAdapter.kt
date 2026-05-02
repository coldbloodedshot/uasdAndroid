package com.uasd.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EstudianteAdapter(
    private var estudiantes: List<GradableEstudiante>,
    private val onGradeClick: (GradableEstudiante) -> Unit,
    private val onNoteClick: (GradableEstudiante) -> Unit
) : RecyclerView.Adapter<EstudianteAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMatricula: TextView = view.findViewById(R.id.tvMatricula)
        val tvNombre: TextView = view.findViewById(R.id.tvNombre)
        val tvNota: TextView = view.findViewById(R.id.tvNota)
        val ivObservacion: android.widget.ImageView = view.findViewById(R.id.ivObservacion)
        val tvObservacionPreview: TextView = view.findViewById(R.id.tvObservacionPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_estudiante, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = estudiantes[position]
        
        // Zebra stripes
        val color = if (position % 2 == 0) {
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
