package com.uasd.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SeccionAdapter(
    private var secciones: List<Seccion>,
    private val onSectionClick: (Seccion) -> Unit
) : RecyclerView.Adapter<SeccionAdapter.SeccionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeccionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_seccion, parent, false)
        return SeccionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeccionViewHolder, position: Int) {
        val seccion = secciones[position]
        
        // Determinar color de fondo
        // LINE 23: Highlight color used here
        val activeColor = androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.uasd_active_highlight)
        val zebraColor = if (position % 2 == 0) {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.white)
        } else {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.light_gray)
        }

        if (esClaseAhora(seccion)) {
            holder.itemView.setBackgroundColor(activeColor)
            // Podríamos agregar un borde o icono aquí si fuera necesario
        } else {
            holder.itemView.setBackgroundColor(zebraColor)
        }
        
        holder.bind(seccion, onSectionClick)
    }

    private fun esClaseAhora(seccion: Seccion): Boolean {
        val encuentros = seccion.encuentros ?: return false
        val calendar = java.util.Calendar.getInstance()
        
        // Obtener código de día actual
        val diaHoy = when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "L"
            java.util.Calendar.TUESDAY -> "M"
            java.util.Calendar.WEDNESDAY -> "I"
            java.util.Calendar.THURSDAY -> "J"
            java.util.Calendar.FRIDAY -> "V"
            java.util.Calendar.SATURDAY -> "S"
            java.util.Calendar.SUNDAY -> "D"
            else -> ""
        }

        val nowTime = String.format("%02d:%02d", calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE))

        for (enc in encuentros) {
            if (enc.dias.contains(diaHoy)) {
                try {
                    // "7:00 PM - 9:50 PM"
                    val parts = enc.hora.split(" - ")
                    if (parts.size == 2) {
                        val start = convertTo24h(parts[0])
                        val end = convertTo24h(parts[1])
                        if (nowTime >= start && nowTime <= end) return true
                    }
                } catch (e: Exception) { /* ignore parse errors */ }
            }
        }
        return false
    }

    private fun convertTo24h(timeStr: String): String {
        val sdf12 = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        val sdf24 = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val date = sdf12.parse(timeStr.trim())
        return sdf24.format(date)
    }

    override fun getItemCount(): Int = secciones.size

    fun updateData(newSecciones: List<Seccion>) {
        secciones = newSecciones
        notifyDataSetChanged()
    }

    class SeccionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNombre: TextView = itemView.findViewById(R.id.tvNombre)
        private val tvProfesor: TextView = itemView.findViewById(R.id.tvProfesor)

        fun bind(seccion: Seccion, onClick: (Seccion) -> Unit) {
            tvNombre.text = seccion.nombreMateria
            tvProfesor.text = "NRC: ${seccion.nrc} - Sección: ${seccion.claveSeccion}"
            
            itemView.setOnClickListener { onClick(seccion) }
        }
    }
}
