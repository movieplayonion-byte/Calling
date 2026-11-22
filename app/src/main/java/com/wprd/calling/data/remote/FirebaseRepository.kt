package com.wprd.calling.data.remote

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wprd.calling.data.model.User

/** Firebase helpers: presence + the list of registered WPRD users (node "users"). */
class FirebaseRepository {

    private val db = FirebaseDatabase.getInstance().reference

    fun setPresence(myUid: String, online: Boolean) {
        db.child("users").child(myUid).child("isOnline").setValue(online)
        if (online) {
            db.child("users").child(myUid).child("isOnline")
                .onDisconnect().setValue(false)
        } else {
            db.child("users").child(myUid).child("lastSeen").setValue(System.currentTimeMillis())
        }
    }

    /** Registered users keyed by phone-digits uid, matching ProfileSyncManager keys. */
    fun observeRegisteredUsers(onUpdate: (Map<String, User>) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val map = mutableMapOf<String, User>()
                for (child in snap.children) {
                    val key = child.key ?: continue
                    val name = child.child("name").getValue(String::class.java) ?: ""
                    val phone = child.child("phone").getValue(String::class.java) ?: ""
                    if (name.isBlank() && phone.isBlank()) continue
                    map[key] = User(
                        uid = key,
                        name = name,
                        phone = phone,
                        isOnline = child.child("isOnline").getValue(Boolean::class.java) ?: false,
                        lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                    )
                }
                onUpdate(map)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("users").addValueEventListener(listener)
        return listener
    }

    fun saveFcmToken(myUid: String, token: String) {
        db.child("users").child(myUid).child("fcmToken").setValue(token)
    }
}
