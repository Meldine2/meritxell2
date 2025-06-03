package com.example.meritxell

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class ToysDonationActivity : AppCompatActivity() {

    private lateinit var fullNameEditText: EditText
    private lateinit var emailEditText: EditText
    private lateinit var phoneEditText: EditText
    private lateinit var address1EditText: EditText
    private lateinit var address2EditText: EditText
    private lateinit var cityEditText: EditText
    private lateinit var stateEditText: EditText
    private lateinit var zipEditText: EditText
    private lateinit var toysTypeEditText: EditText
    private lateinit var submitButton: Button
    private lateinit var backButton: ImageView

    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toys_donation)

        supportActionBar?.title = null
        supportActionBar?.hide()

        fullNameEditText = findViewById(R.id.fullNameEditText)
        emailEditText = findViewById(R.id.emailEditText)
        phoneEditText = findViewById(R.id.phoneEditText)
        address1EditText = findViewById(R.id.address1EditText)
        address2EditText = findViewById(R.id.address2EditText)
        cityEditText = findViewById(R.id.cityEditText)
        stateEditText = findViewById(R.id.stateEditText)
        zipEditText = findViewById(R.id.zipEditText)
        toysTypeEditText = findViewById(R.id.toysTypeEditText)
        submitButton = findViewById(R.id.submitButton)
        backButton = findViewById(R.id.btnBack)

        submitButton.setOnClickListener {
            submitForm()
        }

        backButton.setOnClickListener {
            val intent = Intent(this, UserDonationHubActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }

    private fun submitForm() {
        val fullName = fullNameEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val phone = phoneEditText.text.toString().trim()
        val address1 = address1EditText.text.toString().trim()
        val address2 = address2EditText.text.toString().trim()
        val city = cityEditText.text.toString().trim()
        val state = stateEditText.text.toString().trim()
        val zip = zipEditText.text.toString().trim()
        val toysType = toysTypeEditText.text.toString().trim()

        if (!validateInputs(fullName, email, phone, address1, city, state, zip, toysType)) {
            return
        }

        val docId = "${fullName.replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}"

        val userData = hashMapOf(
            "fullName" to fullName,
            "email" to email,
            "phone" to phone,
            "address1" to address1,
            "address2" to address2,
            "city" to city,
            "state" to state,
            "zip" to zip,
            "toysType" to toysType,
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("ToysDonation")
            .document(docId)
            .set(userData)
            .addOnSuccessListener {
                Toast.makeText(this, "Data submitted successfully!", Toast.LENGTH_LONG).show()
                clearForm()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit data: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun validateInputs(
        fullName: String,
        email: String,
        phone: String,
        address1: String,
        city: String,
        state: String,
        zip: String,
        toysType: String
    ): Boolean {
        if (fullName.length < 3) {
            fullNameEditText.error = "Please enter a valid full name (min 3 characters)"
            fullNameEditText.requestFocus()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email"
            emailEditText.requestFocus()
            return false
        }

        if (!phone.matches(Regex("^09\\d{9}$"))) {
            phoneEditText.error = "Please enter a valid Philippine phone number (e.g. 09xxxxxxxxx)"
            phoneEditText.requestFocus()
            return false
        }

        if (address1.isEmpty()) {
            address1EditText.error = "Street address is required"
            address1EditText.requestFocus()
            return false
        }

        if (city.isEmpty()) {
            cityEditText.error = "City is required"
            cityEditText.requestFocus()
            return false
        }

        if (state.isEmpty()) {
            stateEditText.error = "State/Province is required"
            stateEditText.requestFocus()
            return false
        }

        if (!zip.matches(Regex("^\\d{4,6}$"))) {
            zipEditText.error = "Please enter a valid zip code"
            zipEditText.requestFocus()
            return false
        }

        if (toysType.length < 3) {
            toysTypeEditText.error = "Please enter at least 3 characters describing the toys type"
            toysTypeEditText.requestFocus()
            return false
        }

        return true
    }

    private fun clearForm() {
        fullNameEditText.text.clear()
        emailEditText.text.clear()
        phoneEditText.text.clear()
        address1EditText.text.clear()
        address2EditText.text.clear()
        cityEditText.text.clear()
        stateEditText.text.clear()
        zipEditText.text.clear()
        toysTypeEditText.text.clear()
    }
}
