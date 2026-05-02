package com.uasd.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AttendanceAdapter(
    private var data: List<AttendanceModel>,
    private val onAttendanceChanged: (Estudiante, Boolean) -> Unit
) : RecyclerView.Adapter<AttendanceAdapter.ViewHolder>() {

    data class AttendanceModel(
        val estudiante: Estudiante,
        val presente: Boolean = false,
        val resumen: String? = null // if not null, we are in summary mode
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMatricula: TextView = view.findViewById(R.id.tvMatricula)
        val tvNombre: TextView = view.findViewById(R.id.tvNombre)
        val cbPresente: CheckBox = view.findViewById(R.id.cbPresente)
        val tvResumen: TextView = view.findViewById(R.id.tvResumen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance_student, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]
        
        // Zebra stripes
        val color = if (position % 2 == 0) {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.white)
        } else {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.light_gray)
        }
        holder.itemView.setBackgroundColor(color)
        
        holder.tvMatricula.text = item.estudiante.matricula
        holder.tvNombre.text = item.estudiante.nombre
        
        if (item.resumen != null) {
            holder.cbPresente.visibility = View.GONE
            holder.tvResumen.visibility = View.VISIBLE
            holder.tvResumen.text = item.resumen
        } else {
            holder.cbPresente.visibility = View.VISIBLE
            holder.tvResumen.visibility = View.GONE
            
            // Remove listener before setting checked state to avoid triggers
            holder.cbPresente.setOnCheckedChangeListener(null)
            holder.cbPresente.isChecked = item.presente
            
            holder.cbPresente.setOnCheckedChangeListener { _, isChecked ->
                onAttendanceChanged(item.estudiante, isChecked)
            }
        }
    }

    override fun getItemCount() = data.size

    fun updateData(newData: List<AttendanceModel>) {
        data = newData
        notifyDataSetChanged()
    }
}
