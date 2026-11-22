package com.wprd.calling.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wprd.calling.R
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.model.User
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.databinding.FragmentContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment(R.layout.fragment_contacts) {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CallListAdapter
    private var registeredUsers: Map<String, User> = emptyMap()
    private var deviceContacts: List<CallListRow> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContactsBinding.bind(view)
        adapter = CallListAdapter(
            onVoiceCall = { CallStarter.start(requireContext(), it, "VOICE") },
            onVideoCall = { CallStarter.start(requireContext(), it, "VIDEO") }
        )
        binding.recyclerViewContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewContacts.adapter = adapter

        FirebaseRepository().observeRegisteredUsers { users ->
            registeredUsers = users
            render()
        }
        loadDeviceContacts()
    }

    private fun loadDeviceContacts() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is requested in PhoneAuthActivity; show registered users only.
            render()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            deviceContacts = withContext(Dispatchers.IO) { queryDeviceContacts() }
            render()
        }
    }

    private fun queryDeviceContacts(): List<CallListRow> {
        val rows = LinkedHashMap<String, CallListRow>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                val key = ProfileSyncManager.normalizePhoneKey(number)
                if (key.length < 10) continue
                // Match Firebase keys which may carry the 91 country prefix.
                val matchedKey = when {
                    registeredUsers.containsKey(key) -> key
                    registeredUsers.containsKey("91$key") -> "91$key"
                    key.startsWith("91") && registeredUsers.containsKey(key.removePrefix("91")) ->
                        key.removePrefix("91")
                    else -> key
                }
                rows.putIfAbsent(matchedKey, CallListRow(
                    uid = matchedKey, name = name, phone = number,
                    status = number, registered = registeredUsers.containsKey(matchedKey)
                ))
            }
        }
        return rows.values.toList()
    }

    private fun render() {
        if (_binding == null) return
        val rows = if (deviceContacts.isNotEmpty()) {
            deviceContacts.map { row ->
                val user = registeredUsers[row.uid]
                row.copy(
                    registered = user != null || row.registered,
                    status = when {
                        user?.isOnline == true -> "Online • ${row.phone}"
                        user != null -> "On WPRD • ${row.phone}"
                        else -> row.phone
                    }
                )
            }
        } else {
            // No device contacts (or no permission): show all registered WPRD users.
            registeredUsers.values.map { user ->
                CallListRow(
                    uid = user.uid, name = user.name.ifBlank { user.phone }, phone = user.phone,
                    status = if (user.isOnline) "Online" else "On WPRD",
                    registered = true
                )
            }
        }
        adapter.submit(rows.sortedBy { it.name.lowercase() })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
