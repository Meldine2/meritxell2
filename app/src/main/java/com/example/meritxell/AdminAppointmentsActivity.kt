package com.example.meritxell

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AdminAppointmentsActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var appointmentsLayout: LinearLayout
    private lateinit var backButton: ImageView

    private val appointmentDocIds = mutableMapOf<Appointment, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_appointments)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        supportActionBar?.hide()

        backButton = findViewById(R.id.btnBack)
        appointmentsLayout = findViewById(R.id.appointmentsLayout)

        backButton.setOnClickListener { finish() }

        loadAppointments()
    }

    private fun loadAppointments() {
        db.collection("appointments")
            .get()
            .addOnSuccessListener { documents ->
                appointmentDocIds.clear()
                appointmentsLayout.removeAllViews()

                if (documents.isEmpty) {
                    Toast.makeText(this, "No appointments available", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                for (document in documents) {
                    val appointment = document.toObject(Appointment::class.java)
                    appointmentDocIds[appointment] = document.id
                    displayAppointment(appointment)
                    Log.d("AdminAppointments", "Loaded: $appointment")
                }
            }
            .addOnFailureListener { e ->
                Log.e("AdminAppointments", "Error loading appointments: ${e.message}")
                Toast.makeText(this, "Failed to load appointments", Toast.LENGTH_SHORT).show()
            }
    }

    private fun displayAppointment(appointment: Appointment) {
        val appointmentView = layoutInflater.inflate(R.layout.appointment_item, null)

        val appointmentDetails: TextView = appointmentView.findViewById(R.id.appointmentDetails)
        val appointmentStatus: TextView = appointmentView.findViewById(R.id.appointmentStatus)
        val checkboxAccept: CheckBox = appointmentView.findViewById(R.id.checkboxAccept)
        val btnCancel: TextView = appointmentView.findViewById(R.id.btnCancel)
        val usernameTextView: TextView = appointmentView.findViewById(R.id.usernameTextView)

        appointmentDetails.text = "${appointment.appointmentType} on ${appointment.date} at ${appointment.time}"
        appointmentStatus.text = "Status: ${appointment.status}"
        checkboxAccept.isChecked = appointment.status == "accepted"

        // Use username from appointment document if available
        val username = appointment.username ?: "Unknown User"
        usernameTextView.text = username

        usernameTextView.setOnClickListener {
            val intent = Intent(this, UserProfileActivity::class.java)
            intent.putExtra("userId", appointment.userId)
            startActivity(intent)
        }

        checkboxAccept.setOnCheckedChangeListener { _, isChecked ->
            val newStatus = if (isChecked) "accepted" else "cancelled"
            updateAppointmentStatus(appointment, newStatus, appointmentView)
        }

        btnCancel.setOnClickListener {
            updateAppointmentStatus(appointment, "cancelled", appointmentView)
        }

        appointmentsLayout.addView(appointmentView)
    }

    private fun updateAppointmentStatus(appointment: Appointment, status: String, appointmentView: View? = null) {
        val docId = appointmentDocIds[appointment]
        if (docId == null) {
            Toast.makeText(this, "Appointment not found", Toast.LENGTH_SHORT).show()
            Log.e("AdminAppointments", "Missing docId for appointment")
            return
        }

        val appointmentRef = db.collection("appointments").document(docId)

        if (status == "cancelled") {
            appointmentRef.delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Appointment cancelled", Toast.LENGTH_SHORT).show()
                    appointmentView?.let { appointmentsLayout.removeView(it) }
                    appointmentDocIds.remove(appointment)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error cancelling: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            appointmentRef.update("status", status)
                .addOnSuccessListener {
                    Toast.makeText(this, "Status updated to $status", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error updating status: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
