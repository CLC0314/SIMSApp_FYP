package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.Timestamp

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth
        firestore = FirebaseFirestore.getInstance()

        binding.btnRegister.setOnClickListener {
            performRegistration()
        }

        binding.tvLogin.setOnClickListener {
            finish()
        }
    }

    // 执行注册逻辑，包含输入验证与防止重复提交处理
    private fun performRegistration() {
        if (isProcessing) return

        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            binding.tvError.text = "All fields are required."
            binding.tvError.visibility = View.VISIBLE
            return
        }

        if (password != confirmPassword) {
            binding.tvError.text = "Passwords do not match."
            binding.tvError.visibility = View.VISIBLE
            return
        }

        isProcessing = true
        binding.btnRegister.isEnabled = false
        binding.tvError.text = "Registering, please wait..."
        binding.tvError.visibility = View.VISIBLE

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    if (firebaseUser != null) {
                        saveUserToFirestore(firebaseUser.uid, name, email)
                    }
                } else {
                    isProcessing = false
                    binding.btnRegister.isEnabled = true
                    val errorMsg = task.exception?.message ?: "Unknown error"
                    binding.tvError.text = "Registration failed: $errorMsg"
                    Toast.makeText(baseContext, "Registration failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
    }

    // 将新注册的用户信息保存至 Firestore
    private fun saveUserToFirestore(userId: String, name: String, email: String) {
        val user = hashMapOf(
            "userId" to userId,
            "name" to name,
            "email" to email,
            "familyId" to null,
            "createdAt" to Timestamp.now()
        )

        firestore.collection("users").document(userId)
            .set(user)
            .addOnSuccessListener {
                Toast.makeText(baseContext, "Account created successfully!", Toast.LENGTH_SHORT).show()
                navigateToFamilySelection()
            }
            .addOnFailureListener { e ->
                isProcessing = false
                binding.btnRegister.isEnabled = true
                Toast.makeText(baseContext, "Database error: ${e.message}", Toast.LENGTH_LONG).show()
                auth.currentUser?.delete()
            }
    }

    // 注册成功后跳转至家庭选择页面
    private fun navigateToFamilySelection() {
        val intent = Intent(this, FamilySelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}