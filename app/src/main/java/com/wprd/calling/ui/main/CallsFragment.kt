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

class CallsFragment : Fragment(R.layout.fragment_calls) {

    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CallListAdapter

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
                    adapter.submit(records.map { record ->
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
                    })
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
