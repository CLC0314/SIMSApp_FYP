// com.example.familyapp/RegisterActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.view.View // 确保导入 View
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

        // 1. 初始化 Firebase 服务
        auth = Firebase.auth
        firestore = FirebaseFirestore.getInstance()

        // 2. 注册按钮监听
        binding.btnRegister.setOnClickListener {
            performRegistration()
        }

        // 3. 返回登录页
        binding.tvLogin.setOnClickListener {
            finish() // 简单返回到 LoginActivity
        }
    }

    private fun performRegistration() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()
        val errorTextView = binding.tvError

        errorTextView.visibility = View.GONE

        // 验证输入
        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            errorTextView.text = "所有字段都必须填写"
            errorTextView.visibility = View.VISIBLE
            return
        }
        if (password.length < 6) {
            errorTextView.text = "密码长度必须至少为6位"
            errorTextView.visibility = View.VISIBLE
            return
        }
        if (password != confirmPassword) {
            errorTextView.text = "两次输入的密码不匹配"
            errorTextView.visibility = View.VISIBLE
            return
        }

        // 注册 Firebase Auth 用户
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    if (firebaseUser != null) {
                        // 注册成功，将用户信息存储到 Firestore
                        saveUserToFirestore(firebaseUser.uid, name, email)
                    }
                } else {
                    // 注册失败
                    Toast.makeText(baseContext, "注册失败: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun saveUserToFirestore(userId: String, name: String, email: String) {
        val user = hashMapOf(
            "userId" to userId,
            "name" to name,
            "email" to email,
            "familyId" to null, // 初始为空，待 FamilySelectionActivity 设置
            "createdAt" to Timestamp.now()
        )

        firestore.collection("users").document(userId)
            .set(user)
            .addOnSuccessListener {
                Toast.makeText(baseContext, "账号注册成功！", Toast.LENGTH_SHORT).show()
                navigateToFamilySelection()
            }
            .addOnFailureListener { e ->
                Toast.makeText(baseContext, "数据存储失败: $e", Toast.LENGTH_LONG).show()
                // 数据库存储失败，删除刚刚创建的 Auth 用户
                auth.currentUser?.delete()
            }
    }

    private fun navigateToFamilySelection() {
        // 跳转到 FamilySelectionActivity
        val intent = Intent(this, FamilySelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}