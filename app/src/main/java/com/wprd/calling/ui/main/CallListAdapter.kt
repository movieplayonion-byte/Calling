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
        val visibility = if (row.showActions) View.VISIBLE else View.GONE
        holder.binding.btnItemVoiceCall.visibility = visibility
        holder.binding.btnItemVideoCall.visibility = visibility
        holder.binding.btnItemVoiceCall.setOnClickListener { onVoiceCall(row) }
        holder.binding.btnItemVideoCall.setOnClickListener { onVideoCall(row) }
        holder.itemView.setOnClickListener { onVoiceCall(row) }
    }
}
