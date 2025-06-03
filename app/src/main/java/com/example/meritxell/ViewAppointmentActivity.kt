package com.example.meritxell

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth

class ViewAppointmentActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var tvAppointmentType: TextView
    private lateinit var tvAppointmentDate: TextView
    private lateinit var tvAppointmentTime: TextView
    private lateinit var tvAppointmentStatus: TextView
    private lateinit var btnCancelAppointment: Button

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_appointment)

        // Initialize Firebase Firestore and Auth
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        supportActionBar?.title = null
        supportActionBar?.hide()

        // Initialize views
        btnBack = findViewById(R.id.btnBack)
        tvAppointmentType = findViewById(R.id.tvAppointmentType)
        tvAppointmentDate = findViewById(R.id.tvAppointmentDate)
        tvAppointmentTime = findViewById(R.id.tvAppointmentTime)
        tvAppointmentStatus = findViewById(R.id.tvAppointmentStatus)
        btnCancelAppointment = findViewById(R.id.btnCancelAppointment)

        // Set up the Back button to go back to the previous screen (AppointmentFragment)
        btnBack.setOnClickListener {
            onBackPressed()  // This will return to the previous screen (AppointmentFragment)
        }

        // Load appointment details for the current user
        loadAppointmentDetails()

        // Set up Cancel Appointment button listener
        btnCancelAppointment.setOnClickListener {
            cancelAppointment()
        }
    }

    private fun loadAppointmentDetails() {
        // Get the current user's UID
        val userId = auth.currentUser?.uid
        if (userId != null) {
            // Fetch the appointment details from Firestore
            db.collection("appointments")
                .whereEqualTo("userId", userId)
                .get()
                .addOnSuccessListener { result ->
                    if (!result.isEmpty) {
                        val appointment = result.documents[0]  // Assuming there's only one appointment per user

                        // Set the appointment details to the TextViews
                        tvAppointmentType.text = "Appointment Type: ${appointment.getString("appointmentType")}"
                        tvAppointmentDate.text = "Date: ${appointment.getString("date")}"
                        tvAppointmentTime.text = "Time: ${appointment.getString("time")}"
                        tvAppointmentStatus.text = "Status: ${appointment.getString("status")}" // Display the appointment status (Accepted or Pending)
                    } else {
                        Toast.makeText(this, "No appointment found", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error loading appointment details: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelAppointment() {
        // Get the current user's UID
        val userId = auth.currentUser?.uid
        if (userId != null) {
            // Fetch the appointment details from Firestore
            db.collection("appointments")
                .whereEqualTo("userId", userId)
                .get()
                .addOnSuccessListener { result ->
                    if (!result.isEmpty) {
                        val appointment = result.documents[0]  // Assuming there's only one appointment per user
                        val appointmentRef = appointment.reference

                        // Delete the appointment document
                        appointmentRef.delete()
                            .addOnSuccessListener {
                                Toast.makeText(this, "Appointment cancelled and deleted", Toast.LENGTH_SHORT).show()
                                // After deletion, finish the activity and return to the previous screen
                                finish()  // Close the current activity and return to the previous one
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Error deleting appointment: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(this, "No appointment found to cancel", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error retrieving appointment details: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
        }
    }
}
