package com.wprd.calling.ui.main

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wprd.calling.R
import com.wprd.calling.data.local.AppDatabase
import com.wprd.calling.databinding.FragmentCallsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallsFragment : Fragment(R.layout.fragment_calls), SearchableTab {

    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CallListAdapter
    private var allRows: List<CallListRow> = emptyList()
    private var searchQuery: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCallsBinding.bind(view)
        adapter = CallListAdapter(
            onVoiceCall = { CallStarter.start(requireContext(), it, "VOICE") },
            onVideoCall = { CallStarter.start(requireContext(), it, "VIDEO") }
        )
        binding.recyclerViewCalls.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewCalls.adapter = adapter

        val timeFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.getInstance(requireContext()).callDao()
                .getAllCallRecords().collectLatest { records ->
                    allRows = records.map { record ->
                        val label = when (record.direction) {
                            "MISSED" -> "Missed"
                            "INCOMING" -> "Incoming"
                            else -> "Outgoing"
                        }
                        val type = if (record.callType == "VIDEO") "Video" else "Voice"
                        val duration = if (record.durationSeconds > 0)
                            " • %d:%02d".format(record.durationSeconds / 60, record.durationSeconds % 60)
                        else ""
                        CallListRow(
                            uid = record.peerUid,
                            name = record.peerName,
                            phone = record.peerPhone,
                            status = "$label $type • ${timeFormat.format(Date(record.timestamp))}$duration",
                            registered = true
                        )
                    }
                    render()
                }
        }
    }

    override fun applySearch(query: String) {
        searchQuery = query
        if (_binding != null) render()
    }

    private fun render() {
        val filtered = filterRows(allRows, searchQuery)
        adapter.submit(filtered)
        when {
            allRows.isEmpty() -> {
                binding.tvEmptyCalls.text =
                    "No calls yet\nJab aap call karenge ya aayegi,\nyahan dikhegi."
                binding.tvEmptyCalls.visibility = View.VISIBLE
            }
            filtered.isEmpty() -> {
                binding.tvEmptyCalls.text = "Koi call nahi mili — \"$searchQuery\""
                binding.tvEmptyCalls.visibility = View.VISIBLE
            }
            else -> binding.tvEmptyCalls.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Name ya number (sirf digits) se match. */
        fun filterRows(rows: List<CallListRow>, query: String): List<CallListRow> {
            val q = query.trim()
            if (q.isEmpty()) return rows
            val digits = q.filter { it.isDigit() }
            return rows.filter { row ->
                row.name.contains(q, ignoreCase = true) ||
                    (digits.isNotEmpty() && row.phone.filter { it.isDigit() }.contains(digits))
            }
        }
    }
}
