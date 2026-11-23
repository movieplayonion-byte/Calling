package com.wprd.calling.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wprd.calling.databinding.ItemCallContactBinding

/** One row for both Contacts and Call-history lists (same item layout). */
data class CallListRow(
    val uid: String,
    val name: String,
    val phone: String,
    val status: String,
    val registered: Boolean = true,
    val showActions: Boolean = true
)

class CallListAdapter(
    private val onVoiceCall: (CallListRow) -> Unit,
    private val onVideoCall: (CallListRow) -> Unit
) : RecyclerView.Adapter<CallListAdapter.RowHolder>() {

    private val rows = mutableListOf<CallListRow>()

    fun submit(newRows: List<CallListRow>) {
        rows.clear(); rows.addAll(newRows); notifyDataSetChanged()
    }

    inner class RowHolder(val binding: ItemCallContactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ItemCallContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RowHolder(binding)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.binding.tvItemName.text = row.name
        holder.binding.tvItemStatus.text = row.status
        // Mockup-style avatar: coloured circle with the contact's initial.
        // Colour is picked deterministically from the name so a contact
        // always gets the same colour.
        holder.binding.tvItemInitial.text =
            row.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val palette = intArrayOf(
            0xFF3D348B.toInt(), 0xFF7B2D8B.toInt(), 0xFF0E7C86.toInt(),
            0xFFB23A48.toInt(), 0xFF2E6F4E.toInt(), 0xFF8A6D1F.toInt(),
            0xFF46529E.toInt(), 0xFF904E95.toInt()
        )
        val color = palette[(row.name.hashCode() and Int.MAX_VALUE) % palette.size]
        holder.binding.ivItemAvatar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(color)
        val visibility = if (row.showActions) View.VISIBLE else View.GONE
        holder.binding.btnItemVoiceCall.visibility = visibility
        holder.binding.btnItemVideoCall.visibility = visibility
        holder.binding.btnItemVoiceCall.setOnClickListener { onVoiceCall(row) }
        holder.binding.btnItemVideoCall.setOnClickListener { onVideoCall(row) }
        holder.itemView.setOnClickListener { onVoiceCall(row) }
    }
}
