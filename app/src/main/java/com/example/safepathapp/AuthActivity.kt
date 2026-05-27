package com.example.safepathapp

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private   lateinit var etConfirmPassword: EditText
    private lateinit var btnPrimary: Button
    private lateinit var tvToggle: TextView
    private lateinit var tvForgotPassword: TextView
    private lateinit var progressBar: ProgressBar

    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)



        // Bind UI
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnPrimary = findViewById(R.id.btnPrimary)
        tvToggle = findViewById(R.id.tvToggle)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        progressBar = findViewById(R.id.progressBar)

        etConfirmPassword.visibility = View.GONE

        btnPrimary.setOnClickListener {
            if (isLoginMode) loginUser() else signupUser()
        }

        tvToggle.setOnClickListener { toggleMode() }

        tvForgotPassword.setOnClickListener { sendPasswordReset() }
    }

    private fun toggleMode() {
        isLoginMode = !isLoginMode

        if (isLoginMode) {
            etConfirmPassword.visibility = View.GONE
            btnPrimary.text = "Login"
            tvToggle.text = "Don't have an account? Sign up"
            tvForgotPassword.visibility = View.VISIBLE
        } else {
            etConfirmPassword.visibility = View.VISIBLE
            btnPrimary.text = "Signup"
            tvToggle.text = "Already have an account? Login"
            tvForgotPassword.visibility = View.GONE
        }
    }

    private fun loginUser() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        // Dummy login validation (no Firebase)
        progressBar.visibility = View.GONE

        Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun signupUser() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val confirmPassword = etConfirmPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        // Dummy signup (no Firebase)
        progressBar.visibility = View.GONE

        Toast.makeText(this, "Signup successful", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun sendPasswordReset() {
        val email = etEmail.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(this, "Please enter your email first", Toast.LENGTH_SHORT).show()
            return
        }

        // Dummy password reset action
        Toast.makeText(this, "Password reset link would be sent to: $email", Toast.LENGTH_LONG).show()
    }
}
