package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.Family
import com.example.familyapp.databinding.ActivityFamilySelectionBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class FamilySelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilySelectionBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilySelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = Firebase.firestore

        // 确保创建家庭所需的输入框可见
        // 假设您的布局中有这两个输入框
        // 默认隐藏加入家庭的代码输入，只在点击加入家庭按钮时显示
        binding.etFamilyCode.visibility = View.GONE

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        checkIfUserHasFamily(currentUser.uid)

        // 绑定按钮事件
        binding.btnCreateFamily.setOnClickListener {
            createNewFamily(currentUser.uid)
        }

        // 加入家庭逻辑（处理代码输入框的可见性切换）
        binding.btnJoinFamily.setOnClickListener {
            if (binding.etFamilyCode.visibility == View.GONE) {
                binding.etFamilyCode.visibility = View.VISIBLE
                binding.btnCreateFamily.visibility = View.GONE
                binding.etFamilyName.visibility = View.GONE
                binding.etMemberLimit.visibility = View.GONE
                binding.btnJoinFamily.text = "确认加入"
            } else {
                joinExistingFamily(currentUser.uid, binding.etFamilyCode.text.toString().trim())
            }
        }
    }

    private fun checkIfUserHasFamily(userId: String) {
        // ... (保持不变)
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")
                if (familyId != null && familyId.isNotEmpty()) {
                    navigateToMainActivity()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "无法加载用户信息，请检查网络", Toast.LENGTH_LONG).show()
            }
    }

    // 1. 创建新家庭逻辑 (已更新以保存 familyCode)
    private fun createNewFamily(userId: String) {
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

        val familyCode = generateFamilyCode()
        val familyRef = db.collection("families").document()

        val newFamily = Family(
            familyId = familyRef.id,
            code = familyCode, // ✅ 现在保存了短代码
            name = familyName,
            creatorId = userId,
            members = listOf(userId),
            memberLimit = memberLimit
        )

        val batch = db.batch()
        val userRef = db.collection("users").document(userId)

        batch.set(familyRef, newFamily)
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

    // 2. 加入现有家庭逻辑 (已重写：实现查询、限制和双文档更新)
    private fun joinExistingFamily(userId: String, code: String) {
        val familyCode = code.uppercase()
        if (familyCode.length != 6) {
            Toast.makeText(this, "家庭代码必须是6位", Toast.LENGTH_SHORT).show()
            return
        }

        // 查找是否有匹配的 Family Code
        db.collection("families")
            .whereEqualTo("code", familyCode)
            .limit(1)
            .get()
            .addOnSuccessListener { querySnapshot ->

                // 4. 不存在提醒
                if (querySnapshot.isEmpty) {
                    Toast.makeText(this, "家庭代码无效，家庭不存在", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val familyDocument = querySnapshot.documents[0]
                val family = familyDocument.toObject(Family::class.java)

                if (family == null) {
                    Toast.makeText(this, "家庭数据异常，请联系管理员", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 检查用户是否已在家庭中，或人数是否已满
                if (family.members.contains(userId)) {
                    Toast.makeText(this, "你已经是 ${family.name} 家庭的成员", Toast.LENGTH_SHORT).show()
                    navigateToMainActivity()
                    return@addOnSuccessListener
                }
                if (family.members.size >= family.memberLimit) {
                    Toast.makeText(this, "家庭人数已满 (${family.memberLimit}人限制)", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 执行批量写入操作：更新用户 familyId 和 家庭 members 列表
                val batch = db.batch()
                val userRef = db.collection("users").document(userId)
                val familyRef = db.collection("families").document(family.familyId)

                // 更新用户文档：设置 familyId
                batch.update(userRef, "familyId", family.familyId)

                // 更新家庭文档：使用 FieldValue.arrayUnion 安全地添加新成员
                batch.update(familyRef, "members", FieldValue.arrayUnion(userId))

                batch.commit()
                    .addOnSuccessListener {
                        // 3. 成功提醒：包含家庭名称
                        Toast.makeText(this, "你已成功加入 ${family.name} 家庭", Toast.LENGTH_LONG).show()
                        navigateToMainActivity()
                    }
                    .addOnFailureListener { e ->
                        Log.e("FamilySelection", "加入家庭失败: ", e)
                        Toast.makeText(this, "加入家庭失败，请重试。", Toast.LENGTH_LONG).show()
                    }

            }
            .addOnFailureListener { e ->
                Log.e("FamilySelection", "查询家庭失败: ", e)
                Toast.makeText(this, "查询失败，请检查网络。", Toast.LENGTH_LONG).show()
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
        val intent = Intent(this, InventoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}