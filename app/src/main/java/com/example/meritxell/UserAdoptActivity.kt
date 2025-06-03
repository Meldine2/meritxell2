package com.example.meritxell

import android.app.Activity
import android.graphics.Color
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration

class UserAdoptActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var username: String = "Unknown User"

    private var progressStatus: MutableMap<String, String> = mutableMapOf()

    private lateinit var stepCircles: List<ImageView>
    private lateinit var stepIndicators: List<TextView>
    private lateinit var stepStatusIcons: List<ImageView>

    private var progressListener: ListenerRegistration? = null

    companion object {
        private const val TAG = "UserAdoptActivity"
    }

    private val stepActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val user = auth.currentUser

            if (result.resultCode == Activity.RESULT_OK) {
                // If a step activity returns OK, simply refresh the UI
                // No specific "pending_review" or "rejected" status handling here
                Log.d(TAG, "Step activity returned OK. Refreshing UI to reflect potential changes.")
            } else {
                Log.d(TAG, "Step activity returned with result code: ${result.resultCode}. No action taken, refreshing UI.")
            }

            if (user != null) {
                setupAdoptionProgressListener(user.uid) // Always refresh to get latest status from Firestore
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_adopt)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        supportActionBar?.hide()

        bindViews()

        val backButton: ImageView = findViewById(R.id.btnBack)
        backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        checkProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressListener?.remove()
    }

    private fun bindViews() {
        stepCircles = listOf(
            findViewById(R.id.step1Circle), findViewById(R.id.step2Circle),
            findViewById(R.id.step3Circle), findViewById(R.id.step4Circle),
            findViewById(R.id.step5Circle), findViewById(R.id.step6Circle),
            findViewById(R.id.step7Circle), findViewById(R.id.step8Circle),
            findViewById(R.id.step9Circle), findViewById(R.id.step10Circle)
        )

        stepIndicators = listOf(
            findViewById(R.id.step1LockedIndicator), findViewById(R.id.step2LockedIndicator),
            findViewById(R.id.step3LockedIndicator), findViewById(R.id.step4LockedIndicator),
            findViewById(R.id.step5LockedIndicator), findViewById(R.id.step6LockedIndicator),
            findViewById(R.id.step7LockedIndicator), findViewById(R.id.step8LockedIndicator),
            findViewById(R.id.step9LockedIndicator), findViewById(R.id.step10LockedIndicator)
        )

        stepStatusIcons = listOf(
            findViewById(R.id.step1StatusIcon), findViewById(R.id.step2StatusIcon),
            findViewById(R.id.step3StatusIcon), findViewById(R.id.step4StatusIcon),
            findViewById(R.id.step5StatusIcon), findViewById(R.id.step6StatusIcon),
            findViewById(R.id.step7StatusIcon), findViewById(R.id.step8StatusIcon),
            findViewById(R.id.step9StatusIcon), findViewById(R.id.step10StatusIcon)
        )
    }

    private fun checkProgress() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "User is not authenticated. Please log in.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        firestore.collection("users")
            .document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    username = document.getString("username") ?: "Unknown User"
                    Log.d(TAG, "Fetched username: $username")
                } else {
                    Log.w(TAG, "User document not found for UID: ${user.uid}. Using default username.")
                    username = "Unknown User"
                }
                setupAdoptionProgressListener(user.uid)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting user data: ${exception.message}", exception)
                Toast.makeText(this, "Error loading user data.", Toast.LENGTH_SHORT).show()
                setupAdoptionProgressListener(user.uid)
            }
    }

    private fun setupAdoptionProgressListener(userId: String) {
        progressListener?.remove() // Detach any existing listener

        progressListener = firestore.collection("adoption_progress")
            .document(userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    Toast.makeText(this, "Error getting real-time progress updates.", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val progressData = snapshot.get("adopt_progress") as? Map<String, String>
                    if (progressData != null) {
                        updateProgressTracking(progressData)
                    } else {
                        Log.d(TAG, "No 'adopt_progress' map found in snapshot. Showing confirmation dialog.")
                        showConfirmationDialog()
                    }
                } else {
                    Log.d(TAG, "Adoption progress document not found. Showing confirmation dialog.")
                    showConfirmationDialog()
                }
            }
    }

    private fun showConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Start Adoption Process")
            .setMessage("Are you sure you want to begin the adoption process?")
            .setPositiveButton("Yes") { _, _ -> saveInitialProgressToFirestore() }
            .setNegativeButton("No") { _, _ -> onBackPressedDispatcher.onBackPressed() }
            .setCancelable(false)
            .show()
    }

    private fun saveInitialProgressToFirestore() {
        val initialProgressMap = mapOf(
            "step1" to "complete",      // Assuming Step 1 is immediately complete
            "step2" to "in_progress",   // Step 2 is the first active step
            "step3" to "locked",
            "step4" to "locked",
            "step5" to "locked",
            "step6" to "locked",
            "step7" to "locked",
            "step8" to "locked",
            "step9" to "locked",
            "step10" to "locked"
        )

        val userProgressData = mapOf(
            "adopt_progress" to initialProgressMap,
            "username" to username,
            "timestamp" to FieldValue.serverTimestamp()
        )

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "User is not authenticated. Cannot save initial progress.", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("adoption_progress").document(user.uid)
            .set(userProgressData)
            .addOnSuccessListener {
                Log.d(TAG, "Initial progress started and saved successfully!")
                Toast.makeText(this, "Adoption process started!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start initial progress: ${e.message}", e)
                Toast.makeText(this, "Failed to start progress: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateProgressTracking(progress: Map<String, String>) {
        progressStatus.clear()
        progressStatus.putAll(progress)

        for (i in 0 until 10) {
            val stepKey = "step${i + 1}"
            // Default to "locked" if status is not found or not "complete" or "in_progress"
            val status = progressStatus[stepKey] ?: "locked"

            if (i < stepCircles.size && i < stepIndicators.size && i < stepStatusIcons.size) {
                Log.d(TAG, "Updating UI for Step $stepKey status: $status")

                when (status) {
                    "complete" -> {
                        stepCircles[i].setImageResource(R.drawable.circle_filled) // Should be YELLOW
                        stepIndicators[i].text = "Complete"
                        stepIndicators[i].setTextColor(Color.parseColor("#Ffff00"))
                        stepStatusIcons[i].setImageResource(R.drawable.ic_status_complete)
                        stepStatusIcons[i].visibility = View.VISIBLE
                    }
                    "in_progress" -> {
                        stepCircles[i].setImageResource(R.drawable.circle_in_progress) // Should be BLACK
                        stepIndicators[i].text = "In Progress"
                        stepIndicators[i].setTextColor(Color.parseColor("#000000"))
                        stepStatusIcons[i].setImageResource(R.drawable.ic_status_in_progress)
                        stepStatusIcons[i].visibility = View.VISIBLE
                    }
                    "locked" -> { // All other or unknown states default to locked
                        stepCircles[i].setImageResource(R.drawable.circle_locked) // Should be RED
                        stepIndicators[i].text = "Locked"
                        stepIndicators[i].setTextColor(Color.parseColor("#FF0000"))
                        stepStatusIcons[i].setImageResource(R.drawable.ic_status_locked)
                        stepStatusIcons[i].visibility = View.VISIBLE
                    }
                    else -> { // Fallback for any unexpected statuses, defaults to locked
                        Log.w(TAG, "Unexpected status '$status' for $stepKey. Defaulting to locked appearance.")
                        stepCircles[i].setImageResource(R.drawable.circle_locked)
                        stepIndicators[i].text = "Locked"
                        stepIndicators[i].setTextColor(Color.parseColor("#FF0000"))
                        stepStatusIcons[i].setImageResource(R.drawable.ic_status_locked)
                        stepStatusIcons[i].visibility = View.VISIBLE
                    }
                }
            } else {
                Log.e(TAG, "Index $i out of bounds for step UI lists. Check bindViews() and XML.")
            }
        }
        setUpStepClickListeners()
    }

    private fun setUpStepClickListeners() {
        val layouts = mutableListOf<View>()
        for (i in 1..10) {
            val layoutId = resources.getIdentifier("step${i}Layout", "id", packageName)
            findViewById<View>(layoutId)?.let { layouts.add(it) } ?: Log.e(TAG, "Layout for step$i not found in XML!")
        }

        for (i in 0 until layouts.size) {
            val stepNum = i + 1
            layouts[i].setOnClickListener {
                val status = progressStatus["step$stepNum"]
                // Only allow navigation if complete or in_progress
                if (status == "complete" || status == "in_progress") {
                    navigateToStep(stepNum)
                } else {
                    Toast.makeText(this, "This step is locked. Please complete previous steps.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToStep(step: Int) {
        val intent = when (step) {
            1 -> Intent(this, Step1Activity::class.java)
            2 -> Intent(this, Step2Activity::class.java)
            3 -> Intent(this, Step3Activity::class.java)
            4 -> Intent(this, Step4Activity::class.java)
            5 -> Intent(this, Step5Activity::class.java)
            6 -> Intent(this, Step6Activity::class.java)
            7 -> Intent(this, Step7Activity::class.java)
            8 -> Intent(this, Step8Activity::class.java)
            9 -> Intent(this, Step9Activity::class.java)
            10 -> Intent(this, Step10Activity::class.java)
            else -> {
                Log.w(TAG, "Attempted to navigate to unknown step: $step")
                Toast.makeText(this, "Invalid step selected.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        intent.putExtra("stepNumber", step)
        intent.putExtra("userId", auth.currentUser?.uid)
        intent.putExtra("username", username)
        stepActivityResultLauncher.launch(intent)
    }

    fun updateStepStatus(stepKey: String, newStatus: String) {
        val user = auth.currentUser
        if (user != null) {
            // Ensure newStatus is one of the allowed states
            if (newStatus == "complete" || newStatus == "in_progress" || newStatus == "locked") {
                val updatePath = "adopt_progress.$stepKey"
                firestore.collection("adoption_progress").document(user.uid)
                    .update(updatePath, newStatus)
                    .addOnSuccessListener {
                        Log.d(TAG, "Step $stepKey status manually updated to $newStatus in Firestore.")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update step $stepKey status: ${e.message}", e)
                        Toast.makeText(this, "Failed to update step status. Check Firestore rules or admin rights.", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Log.w(TAG, "Attempted to update $stepKey with invalid status: $newStatus. Only 'complete', 'in_progress', 'locked' are allowed.")
                Toast.makeText(this, "Invalid status update attempt.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}