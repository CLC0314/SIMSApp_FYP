// com.example.familyapp/FamilySelectionActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.Family // 🆕 确保导入 Family 数据类
import com.example.familyapp.databinding.ActivityFamilySelectionBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
        db = Firebase.firestore // 使用 KTX 简化初始化

        // 确保创建家庭所需的输入框可见
        // 假设您的布局中有这两个输入框
        // 隐藏加入家庭的代码输入，只在点击加入家庭按钮时显示
        binding.etFamilyCode.visibility = View.GONE

        // 检查用户是否已登录或已加入家庭
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        checkIfUserHasFamily(currentUser.uid)

        // 绑定按钮事件
        binding.btnCreateFamily.setOnClickListener {
            // 🆕 调用更新后的函数，处理创建家庭所需的所有输入
            createNewFamily(currentUser.uid)
        }

        // 加入家庭逻辑（保持不变，但增加代码输入框的可见性切换）
        binding.btnJoinFamily.setOnClickListener {
            if (binding.etFamilyCode.visibility == View.GONE) {
                binding.etFamilyCode.visibility = View.VISIBLE
                binding.btnCreateFamily.visibility = View.GONE
                binding.btnJoinFamily.text = "确认加入"
            } else {
                joinExistingFamily(currentUser.uid, binding.etFamilyCode.text.toString().trim())
            }
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

    // 1. 创建新家庭逻辑 (已修改以处理名称和限制)
    private fun createNewFamily(userId: String) {
        // 1. 获取输入并验证
        val familyName = binding.etFamilyName.text.toString().trim()
        val limitText = binding.etMemberLimit.text.toString().trim()

        if (familyName.isEmpty()) {
            Toast.makeText(this, "家庭名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val memberLimit = limitText.toIntOrNull()
        if (memberLimit == null || memberLimit < 2) {
            Toast.makeText(this, "请输入有效的人数限制 (至少2人)", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 生成 Family Code 和 ID
        val familyCode = generateFamilyCode()
        val familyRef = db.collection("families").document() // 让 Firestore 自动生成 ID

        // 3. 使用 Family 数据类创建对象
        val newFamily = Family(
            familyId = familyRef.id, // 使用 Firestore 自动生成的 ID
            name = familyName, // ✅ 存储家庭名称
            creatorId = userId,
            members = listOf(userId), // 默认加入创建者
            memberLimit = memberLimit // ✅ 存储人数限制
        )

        // 4. 批处理操作：1. 创建家庭文档；2. 更新用户文档
        val batch = db.batch()
        val userRef = db.collection("users").document(userId)

        // 使用 set(familyRef, newFamily) 存储 Family 数据类
        batch.set(familyRef, newFamily)

        // 存储 familyCode 在文档中，方便查询和使用
        batch.update(userRef, "familyId", familyRef.id)

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(this, "家庭创建成功! Family Code: $familyCode", Toast.LENGTH_LONG).show()
                navigateToMainActivity()
            }
            .addOnFailureListener { e ->
                Log.e("FamilySelection", "创建家庭失败: ", e)
                Toast.makeText(this, "创建家庭失败，请重试。", Toast.LENGTH_LONG).show()
            }
    }

    // 2. 加入现有家庭逻辑 (保持不变)
    private fun joinExistingFamily(userId: String, code: String) {
        if (code.length != 6) {
            Toast.makeText(this, "家庭代码必须是6位", Toast.LENGTH_SHORT).show()
            return
        }

        // 查找是否有匹配的 Family Code
        db.collection("families")
            .whereEqualTo("familyId", code.uppercase()) // 假设 familyId 就是 code
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