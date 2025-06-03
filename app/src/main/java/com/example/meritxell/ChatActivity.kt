package com.example.meritxell

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

data class Message(
    val senderId: String = "",
    val receiverId: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class ChatActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var scrollView: ScrollView

    private val dbRef = FirebaseDatabase.getInstance().reference
    private lateinit var chatWithUserId: String
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatWithUserId = intent.getStringExtra("chatUserId") ?: ""
        val receiverName = intent.getStringExtra("chatUserName") ?: "Chat"
        supportActionBar?.title = receiverName

        if (chatWithUserId.isEmpty() || currentUserId.isEmpty()) {
            finish()
            return
        }

        messagesContainer = findViewById(R.id.messagesContainer)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.messageScroll)

        sendButton.setOnClickListener {
            val messageText = messageInput.text.toString().trim()
            if (messageText.isNotEmpty()) {
                sendMessage(messageText)
                messageInput.text.clear()
            }
        }

        listenForMessages()
    }

    private fun getChatRoomId(user1: String, user2: String): String {
        return if (user1 < user2) "${user1}_$user2" else "${user2}_$user1"
    }

    private fun sendMessage(text: String) {
        val chatId = getChatRoomId(currentUserId, chatWithUserId)
        val message = Message(
            senderId = currentUserId,
            receiverId = chatWithUserId,
            message = text,
            timestamp = System.currentTimeMillis()
        )

        val chatRef = dbRef.child("chats").child(chatId)

        // Store participants
        chatRef.child("participants").child(currentUserId).setValue(true)
        chatRef.child("participants").child(chatWithUserId).setValue(true)

        // Store message under 'messages' node
        chatRef.child("messages").push().setValue(message)
            .addOnSuccessListener {
                Log.d("ChatDebug", "Message sent to $chatId")
            }
            .addOnFailureListener {
                Log.e("ChatDebug", "Failed to send message", it)
            }
    }

    private fun listenForMessages() {
        val chatId = getChatRoomId(currentUserId, chatWithUserId)
        val messagesRef = dbRef.child("chats").child(chatId).child("messages")

        Log.d("ChatDebug", "Listening on chatId: $chatId")

        messagesRef.orderByChild("timestamp").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val msg = snapshot.getValue(Message::class.java)
                msg?.let {
                    Log.d("ChatDebug", "Message received: ${it.message}")
                    if (it.message.isNotEmpty()) {
                        displayMessage(it)
                        scrollToBottom()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatDebug", "Listener cancelled", error.toException())
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
        })
    }

    private fun displayMessage(message: Message) {
        val textView = TextView(this).apply {
            text = message.message
            setPadding(40, 20, 40, 20)
            textSize = 16f

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                bottomMargin = 12
                gravity = if (message.senderId == currentUserId) Gravity.END else Gravity.START
            }

            background = if (message.senderId == currentUserId) {
                resources.getDrawable(R.drawable.bg_message_sent, null)
            } else {
                resources.getDrawable(R.drawable.bg_message_received, null)
            }
        }

        messagesContainer.addView(textView)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
