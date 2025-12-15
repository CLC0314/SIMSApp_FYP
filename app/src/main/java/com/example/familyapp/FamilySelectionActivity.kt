// com.example.familyapp/FamilySelectionActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.databinding.ActivityFamilySelectionBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

class FamilySelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilySelectionBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilySelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 检查用户是否已登录或已加入家庭
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 🆕 如果用户已经有 familyId，直接跳转到主页 (需在 Firestore 中检查)
        checkIfUserHasFamily(currentUser.uid)

        // 绑定按钮事件
        binding.btnCreateFamily.setOnClickListener {
            createNewFamily(currentUser.uid)
        }
        binding.btnJoinFamily.setOnClickListener {
            joinExistingFamily(currentUser.uid, binding.etFamilyCode.text.toString().trim())
        }
    }

    // 检查 Firestore 中用户的 familyId 字段
    private fun checkIfUserHasFamily(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")
                if (familyId != null && familyId.isNotEmpty()) {
                    // 已加入家庭，跳转到主页
                    navigateToMainActivity()
                }
                // 否则留在 FamilySelectionActivity
            }
            .addOnFailureListener {
                Toast.makeText(this, "无法加载用户信息，请检查网络", Toast.LENGTH_LONG).show()
            }
    }

    // 1. 创建新家庭逻辑
    private fun createNewFamily(userId: String) {
        // 生成一个随机的6位Family Code (例如 A1B2C3)
        val familyCode = generateFamilyCode()
        val familyId = UUID.randomUUID().toString() // 生成唯一的 Family ID

        val family = hashMapOf(
            "familyId" to familyId,
            "code" to familyCode,
            "ownerId" to userId,
            "createdAt" to com.google.firebase.Timestamp.now()
        )

        // 批处理操作：1. 创建家庭文档；2. 更新用户文档
        val batch = db.batch()
        val familyRef = db.collection("families").document(familyId)
        val userRef = db.collection("users").document(userId)

        batch.set(familyRef, family)
        batch.update(userRef, "familyId", familyId) // 将用户链接到新家庭

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(this, "家庭创建成功! Family Code: $familyCode", Toast.LENGTH_LONG).show()
                navigateToMainActivity()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "创建家庭失败: $e", Toast.LENGTH_LONG).show()
            }
    }

    // 2. 加入现有家庭逻辑
    private fun joinExistingFamily(userId: String, code: String) {
        if (code.length != 6) {
            Toast.makeText(this, "家庭代码必须是6位", Toast.LENGTH_SHORT).show()
            return
        }

        // 查找是否有匹配的 Family Code
        db.collection("families")
            .whereEqualTo("code", code.uppercase())
            .limit(1)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val familyId = querySnapshot.documents[0].getString("familyId")
                    if (familyId != null) {
                        // 找到家庭，更新用户文档
                        db.collection("users").document(userId)
                            .update("familyId", familyId)
                            .addOnSuccessListener {
                                Toast.makeText(this, "成功加入家庭!", Toast.LENGTH_SHORT).show()
                                navigateToMainActivity()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "更新用户信息失败: $e", Toast.LENGTH_LONG).show()
                            }
                    }
                } else {
                    Toast.makeText(this, "家庭代码无效，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "查询失败: $e", Toast.LENGTH_LONG).show()
            }
    }

    // 辅助函数：生成一个6位随机代码
    private fun generateFamilyCode(): String {
        val charPool : List<Char> = ('A'..'Z') + ('0'..'9')
        return (1..6)
            .map { charPool.random() }
            .joinToString("")
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}