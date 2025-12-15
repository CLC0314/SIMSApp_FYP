// com.example.familyapp/LoginActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 Firebase Auth
        auth = Firebase.auth

        // 2. 检查用户是否已登录
        if (auth.currentUser != null) {
            // 如果已登录，直接跳转到家庭选择页面
            navigateToFamilySelection()
            return
        }

        // 3. 登录按钮监听
        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        // 4. 跳转到注册页
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
        }
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "邮箱和密码不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // 登录成功
                    Toast.makeText(baseContext, "登录成功。", Toast.LENGTH_SHORT).show()
                    navigateToFamilySelection()
                } else {
                    // 登录失败
                    Toast.makeText(baseContext, "登录失败: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    // 登录成功后跳转到家庭选择/创建页面
    private fun navigateToFamilySelection() {
        // 跳转到 FamilySelectionActivity，实现 Family Code 逻辑
        val intent = Intent(this, FamilySelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}