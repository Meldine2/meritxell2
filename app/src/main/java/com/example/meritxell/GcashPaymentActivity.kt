package com.example.meritxell

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GcashPaymentActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storageRef: StorageReference

    private lateinit var ivReceiptPreview: ImageView
    private lateinit var btnChooseReceipt: Button
    private lateinit var btnSubmitReceipt: Button
    private lateinit var progressBar: ProgressBar

    private var selectedImageUri: Uri? = null
    private var donationAmount: String? = null
    private var donationType: String? = null // New variable to store donation type
    private var currentUsername: String? = null

    // ActivityResultLauncher for picking images from gallery
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            selectedImageUri = data?.data
            ivReceiptPreview.setImageURI(selectedImageUri)
            btnSubmitReceipt.isEnabled = selectedImageUri != null // Enable submit button if image is selected
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gcash_payment)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storageRef = FirebaseStorage.getInstance().reference

        supportActionBar?.hide()

        // Get donation amount passed from previous activity
        donationAmount = intent.getStringExtra("amount")
        // --- ADDED THIS LINE TO RECEIVE DONATION TYPE ---
        donationType = intent.getStringExtra("donationType")
        // --------------------------------------------------

        if (donationAmount == null || donationType == null) { // Check both amount and type
            Toast.makeText(this, "Donation details not received.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize views
        ivReceiptPreview = findViewById(R.id.ivReceiptPreview)
        btnChooseReceipt = findViewById(R.id.btnChooseReceipt)
        btnSubmitReceipt = findViewById(R.id.btnSubmitReceipt)
        progressBar = findViewById(R.id.progressBar)

        btnSubmitReceipt.isEnabled = false // Disable submit button initially

        // Set up back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Set up Choose Receipt button
        btnChooseReceipt.setOnClickListener {
            openImageChooser()
        }

        // Set up Submit Receipt button
        btnSubmitReceipt.setOnClickListener {
            if (selectedImageUri != null) {
                uploadReceipt()
            } else {
                Toast.makeText(this, "Please select a receipt image first.", Toast.LENGTH_SHORT).show()
            }
        }

        // Fetch username when the activity starts
        fetchUsername()
    }

    private fun openImageChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        pickImageLauncher.launch(intent)
    }

    private fun fetchUsername() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            firestore.collection("users").document(userId)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        currentUsername = document.getString("username")
                        if (currentUsername == null) {
                            Toast.makeText(this, "Username not found in profile.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "User profile not found.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to fetch username: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadReceipt() {
        // Now also check if donationType is not null
        if (selectedImageUri == null || currentUsername == null || donationAmount == null || donationType == null) {
            Toast.makeText(this, "Missing information. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnChooseReceipt.isEnabled = false
        btnSubmitReceipt.isEnabled = false

        val fileName = "${System.currentTimeMillis()}_${auth.currentUser?.uid}"
        val imageRef = storageRef.child("receipts/$fileName.jpg")

        imageRef.putFile(selectedImageUri!!)
            .addOnProgressListener { taskSnapshot ->
                val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                progressBar.progress = progress
            }
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { uri ->
                    val receiptUrl = uri.toString()
                    saveReceiptToFirestore(receiptUrl)
                }
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                btnChooseReceipt.isEnabled = true
                btnSubmitReceipt.isEnabled = true
                Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun saveReceiptToFirestore(receiptUrl: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val receiptData = hashMapOf(
            "userId" to auth.currentUser?.uid,
            "username" to currentUsername,
            "amount" to donationAmount,
            "donationType" to donationType, // --- ADDED THIS LINE TO INCLUDE DONATION TYPE ---
            "receiptUrl" to receiptUrl,
            "timestamp" to timestamp,
            "status" to "pending"
        )

        firestore.collection("donations")
            .add(receiptData)
            .addOnSuccessListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Thank you for your generous donation! We truly appreciate your support.", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                btnChooseReceipt.isEnabled = true
                btnSubmitReceipt.isEnabled = true
                Toast.makeText(this, "Failed to save receipt details: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}