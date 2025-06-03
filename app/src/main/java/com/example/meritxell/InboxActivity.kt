package com.example.meritxell

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.meritxell.ChatActivity

class InboxActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var searchView: SearchView
    private lateinit var usersContainer: LinearLayout

    private var currentUserRole: String = "user"
    private val allUsersList = mutableListOf<User>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        searchView = findViewById(R.id.searchView)
        usersContainer = findViewById(R.id.usersContainer)

        loadCurrentUserRole { role ->
            currentUserRole = role
            if (role == "admin") {
                searchView.visibility = View.VISIBLE
                loadAllUsers()
                setupSearch()
            } else {
                searchView.visibility = View.GONE
                loadChatsForUser()
            }
        }
    }

    private fun loadCurrentUserRole(callback: (String) -> Unit) {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    callback(doc.getString("role") ?: "user")
                } else {
                    callback("user")
                }
            }
            .addOnFailureListener {
                callback("user")
            }
    }

    private fun loadAllUsers() {
        db.collection("users").get()
            .addOnSuccessListener { snapshot ->
                allUsersList.clear()
                for (doc in snapshot.documents) {
                    val role = doc.getString("role") ?: "user"
                    if (role != "admin") {
                        val username = doc.getString("username") ?: "Unknown"
                        val user = User(doc.id, username)
                        allUsersList.add(user)
                    }
                }
                displayUsers(allUsersList)
            }
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterUsers(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterUsers(newText)
                return true
            }
        })
    }

    private fun filterUsers(query: String?) {
        val filtered = if (query.isNullOrBlank()) {
            allUsersList
        } else {
            // Corrected line: Use safe call '?.contains' and Elvis operator '?: false'
            allUsersList.filter { it.username?.contains(query, ignoreCase = true) ?: false }
        }
        displayUsers(filtered)
    }

    private fun displayUsers(users: List<User>) {
        usersContainer.removeAllViews()
        for (user in users) {
            val userView = layoutInflater.inflate(R.layout.user_item_layout, usersContainer, false)
            val tvName = userView.findViewById<TextView>(R.id.tvUserName)
            tvName.text = user.username
            userView.setOnClickListener {
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("chatUserId", user.uid)
                intent.putExtra("chatUserName", user.username)
                startActivity(intent)
            }
            usersContainer.addView(userView)
        }
    }

    private fun loadChatsForUser() {
        val currentUserId = auth.currentUser?.uid ?: return

        db.collection("chats")
            .whereArrayContains("participants", currentUserId)
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val chatUsers = mutableListOf<User>()
                val userIdToLastMessage = mutableMapOf<String, String>()

                for (doc in snapshot.documents) {
                    val participants = doc.get("participants") as? List<String> ?: continue
                    val otherUserId = participants.firstOrNull { it != currentUserId } ?: continue
                    val lastMessage = doc.getString("lastMessage") ?: ""
                    userIdToLastMessage[otherUserId] = lastMessage
                }

                if (userIdToLastMessage.isEmpty()) {
                    displayUsers(emptyList())
                    return@addOnSuccessListener
                }

                db.collection("users")
                    .whereIn(FieldPath.documentId(), userIdToLastMessage.keys.toList())
                    .get()
                    .addOnSuccessListener { userSnapshot ->
                        for (userDoc in userSnapshot.documents) {
                            val uid = userDoc.id
                            val username = userDoc.getString("username") ?: "Unknown"
                            val user = User(uid, username)
                            chatUsers.add(user)
                        }

                        usersContainer.removeAllViews()
                        for (user in chatUsers) {
                            val userView = layoutInflater.inflate(R.layout.user_item_layout, usersContainer, false)
                            val tvName = userView.findViewById<TextView>(R.id.tvUserName)
                            val lastMsg = userIdToLastMessage[user.uid] ?: ""
                            tvName.text = "${user.username}\n$lastMsg" // Using user.username here is fine because it will be 'Unknown' if null
                            userView.setOnClickListener {
                                val intent = Intent(this, ChatActivity::class.java)
                                intent.putExtra("chatUserId", user.uid)
                                intent.putExtra("chatUserName", user.username)
                                startActivity(intent)
                            }
                            usersContainer.addView(userView)
                        }
                    }
            }
            .addOnFailureListener {
                displayUsers(emptyList())
            }
    }
}