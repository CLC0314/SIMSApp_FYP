package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log // 必须导入 Log
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
    private var isProcessing = false
    private fun performRegistration() {
        if (isProcessing) return
        Toast.makeText(this, "注册按钮被点击了！", Toast.LENGTH_SHORT).show()
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        // 验证输入... (保持您的验证逻辑不变)
        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            binding.tvError.text = "所有字段都必须填写"; binding.tvError.visibility = View.VISIBLE
            return
        }

        // 🔴 关键修复 1：禁用按钮，防止点击轰炸导致卡死
        binding.btnRegister.isEnabled = false
        binding.tvError.text = "正在注册，请稍候..."
        binding.tvError.visibility = View.VISIBLE
        Log.d("REGISTER_FLOW", "开始发起 Auth 请求: $email")

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    if (firebaseUser != null) {
                        Log.d("REGISTER_FLOW", "Auth 成功, UID: ${firebaseUser.uid}")
                        saveUserToFirestore(firebaseUser.uid, name, email)
                    }
                } else {
                    isProcessing = false // 解锁
                    // 🔴 关键修复 2：失败时务必恢复按钮点击，否则用户无法重试
                    binding.btnRegister.isEnabled = true
                    val errorMsg = task.exception?.message ?: "未知错误"
                    Log.e("REGISTER_FLOW", "Auth 失败: $errorMsg")
                    binding.tvError.text = "注册失败: $errorMsg"
                    Toast.makeText(baseContext, "注册失败: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun saveUserToFirestore(userId: String, name: String, email: String) {
        val user = hashMapOf(
            "userId" to userId,
            "name" to name,
            "email" to email,
            "familyId" to null,
            "createdAt" to Timestamp.now()
        )

        Log.d("REGISTER_FLOW", "正在写入 Firestore...")

        firestore.collection("users").document(userId)
            .set(user)
            .addOnSuccessListener {
                Log.d("REGISTER_FLOW", "Firestore 写入成功")
                Toast.makeText(baseContext, "账号注册成功！", Toast.LENGTH_SHORT).show()
                navigateToFamilySelection()
            }
            .addOnFailureListener { e ->
                // 🔴 关键修复 3：写入失败也需恢复按钮
                binding.btnRegister.isEnabled = true
                Log.e("REGISTER_FLOW", "Firestore 写入失败: ${e.message}")
                Toast.makeText(baseContext, "数据存储失败: ${e.message}", Toast.LENGTH_LONG).show()
                auth.currentUser?.delete()
            }
    }

    private fun navigateToFamilySelection() {
        val intent = Intent(this, FamilySelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}