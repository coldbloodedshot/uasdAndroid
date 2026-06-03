package com.uasd.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EncuentroAdapter(
    private var encuentros: List<SeccionEncuentro>,
    private val onEncuentroClick: (SeccionEncuentro) -> Unit
) : RecyclerView.Adapter<EncuentroAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMateria: TextView = view.findViewById(R.id.tvMateriaCalendario)
        val tvHora: TextView = view.findViewById(R.id.tvHoraCalendario)
        val tvAula: TextView = view.findViewById(R.id.tvAulaCalendario)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_encuentro_diario, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = encuentros[position]
        holder.tvMateria.text = item.nombreMateria
        holder.tvHora.text = item.hora
        holder.tvAula.text = item.aula
        
        // Efecto cebra
        val color = if (position % 2 == 0) {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.white)
        } else {
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.light_gray)
        }
        holder.itemView.setBackgroundColor(color)

        holder.itemView.setOnClickListener {
            onEncuentroClick(item)
        }
    }

    override fun getItemCount() = encuentros.size

    fun updateData(newEncuentros: List<SeccionEncuentro>) {
        encuentros = newEncuentros
        notifyDataSetChanged()
    }
}
