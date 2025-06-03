package com.example.meritxell

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class Step1Activity : AppCompatActivity() {

    private lateinit var btnBack: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step1)

        supportActionBar?.title = null
        supportActionBar?.hide()

        // Find the Back button by its ID
        btnBack = findViewById(R.id.btnBack)

        // Set up back button functionality
        btnBack.setOnClickListener {
            onBackPressed()  // Navigate back to the previous screen
        }
    }
}
