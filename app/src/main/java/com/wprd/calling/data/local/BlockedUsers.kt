package com.wprd.calling.data.local

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class BlockedUser(
    val uid: String,
    val name: String,
    val phone: String,
    val blockedAt: Long = 0L
)

/**
 * Blocked users, stored per account at RTDB users/<myKey>/blocked/<blockedKey>.
 * A local SharedPreferences cache (keyed by my phone key, so accounts on a
 * shared phone never mix) lets the incoming-call listener and the FCM service
 * decide instantly — even offline — whether a caller should ring.
 */
object BlockedUsersManager {

    private const val PREFS_NAME = "wprd_blocked_users"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun blockedRef(myKey: String) =
        FirebaseDatabase.getInstance().reference
            .child("users").child(myKey).child("blocked")

    fun getBlockedKeysLocal(context: Context, myKey: String): Set<String> {
        if (myKey.isBlank() || myKey == "unknown_user") return emptySet()
        return prefs(context).getStringSet("keys_$myKey", emptySet())?.toSet() ?: emptySet()
    }

    fun isBlockedLocal(context: Context, myKey: String, otherKey: String): Boolean =
        getBlockedKeysLocal(context, myKey).contains(otherKey)

    private fun saveLocalCache(context: Context, myKey: String, users: Collection<BlockedUser>) {
        val editor = prefs(context).edit()
        editor.putStringSet("keys_$myKey", users.map { it.uid }.toSet())
        for (user in users) {
            editor.putString("name_${myKey}_${user.uid}", user.name)
            editor.putString("phone_${myKey}_${user.uid}", user.phone)
        }
        editor.apply()
    }

    /** Live list of blocked users; also refreshes the local cache on every update. */
    fun observeBlocked(
        context: Context,
        myKey: String,
        onUpdate: (List<BlockedUser>) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val users = snap.children.mapNotNull { child ->
                    val uid = child.key ?: return@mapNotNull null
                    BlockedUser(
                        uid = uid,
                        name = child.child("name").getValue(String::class.java) ?: "",
                        phone = child.child("phone").getValue(String::class.java) ?: "",
                        blockedAt = child.child("blockedAt").getValue(Long::class.java) ?: 0L
                    )
                }.sortedBy { it.name.lowercase() }
                saveLocalCache(context, myKey, users)
                onUpdate(users)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        blockedRef(myKey).addValueEventListener(listener)
        return listener
    }

    fun block(context: Context, myKey: String, user: BlockedUser) {
        blockedRef(myKey).child(user.uid).setValue(
            hashMapOf<String, Any>(
                "name" to user.name,
                "phone" to user.phone,
                "blockedAt" to System.currentTimeMillis()
            )
        )
        // Optimistic local cache update so calls stop ringing immediately.
        val current = getBlockedKeysLocal(context, myKey).toMutableSet()
        current.add(user.uid)
        prefs(context).edit()
            .putStringSet("keys_$myKey", current)
            .putString("name_${myKey}_${user.uid}", user.name)
            .putString("phone_${myKey}_${user.uid}", user.phone)
            .apply()
    }

    fun unblock(context: Context, myKey: String, blockedUid: String) {
        blockedRef(myKey).child(blockedUid).removeValue()
        val current = getBlockedKeysLocal(context, myKey).toMutableSet()
        current.remove(blockedUid)
        prefs(context).edit()
            .putStringSet("keys_$myKey", current)
            .remove("name_${myKey}_$blockedUid")
            .remove("phone_${myKey}_$blockedUid")
            .apply()
    }
}
