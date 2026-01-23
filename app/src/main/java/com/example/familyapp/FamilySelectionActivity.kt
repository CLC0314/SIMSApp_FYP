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

    // 🟢 关键：引入变量记录当前是“创建”还是“加入”模式
    private var isJoinMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilySelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = Firebase.firestore

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 1. 初始化 UI：默认显示创建家庭，隐藏加入码输入框
        resetToCreateMode()

        // 2. 检查用户是否已有家庭
        checkIfUserHasFamily(currentUser.uid)

        // 3. 创建家庭按钮
        binding.btnCreateFamily.setOnClickListener {
            createNewFamily(currentUser.uid)
        }

        // 4. 加入家庭按钮（兼具切换 UI 和 确认功能）
        binding.btnJoinFamily.setOnClickListener {
            if (!isJoinMode) {
                // 如果当前在创建模式，点击后切换到加入模式
                switchToJoinMode()
            } else {
                // 如果已经在加入模式，点击后执行加入逻辑
                val code = binding.etFamilyCode.text.toString().trim()
                joinExistingFamily(currentUser.uid, code)
            }
        }
    }

    private fun resetToCreateMode() {
        isJoinMode = false
        binding.tvCreateHeader.visibility = View.VISIBLE
        binding.tilFamilyCode.visibility = View.GONE
        binding.etFamilyName.visibility = View.VISIBLE
        binding.etMemberLimit.visibility = View.VISIBLE
        binding.btnCreateFamily.visibility = View.VISIBLE
        binding.divider.visibility = View.VISIBLE // 显示分割线
        binding.btnJoinFamily.text = "Join Existing Family"
    }

    private fun switchToJoinMode() {
        isJoinMode = true
        binding.tvCreateHeader.visibility = View.GONE
        binding.tilFamilyCode.visibility = View.VISIBLE
        binding.etFamilyName.visibility = View.GONE
        binding.etMemberLimit.visibility = View.GONE
        binding.btnCreateFamily.visibility = View.GONE
        binding.divider.visibility = View.GONE // 隐藏分割线
        binding.btnJoinFamily.text = "Confirm Join"
    }

    // 🟢 解决问题 1：重写返回键逻辑，让用户能退出“加入模式”
    override fun onBackPressed() {
        if (isJoinMode) {
            resetToCreateMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun checkIfUserHasFamily(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")
                if (!familyId.isNullOrEmpty()) {
                    navigateToMainActivity()
                }
            }
    }

    private fun createNewFamily(userId: String) {
        val familyName = binding.etFamilyName.text.toString().trim()
        val limitText = binding.etMemberLimit.text.toString().trim()

        if (familyName.isEmpty()) {
            Toast.makeText(this, "Family name required", Toast.LENGTH_SHORT).show()
            return
        }
        val memberLimit = limitText.toIntOrNull() ?: 5

        val familyCode = generateFamilyCode()
        val familyRef = db.collection("families").document()

        val newFamily = Family(
            familyId = familyRef.id,
            code = familyCode,
            name = familyName,
            creatorId = userId,
            members = listOf(userId),
            memberLimit = memberLimit
        )

        val batch = db.batch()
        batch.set(familyRef, newFamily)
        batch.update(db.collection("users").document(userId), "familyId", familyRef.id)

        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Family Created! Code: $familyCode", Toast.LENGTH_LONG).show()
            navigateToMainActivity()
        }
    }

    // 🟢 解决问题 2：加入家庭逻辑
    private fun joinExistingFamily(userId: String, code: String) {
        val familyCode = code.uppercase()
        if (familyCode.length != 6) {
            Toast.makeText(this, "Please enter 6-digit code", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnJoinFamily.isEnabled = false // 防止重复点击

        db.collection("families")
            .whereEqualTo("code", familyCode)
            .limit(1)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot.isEmpty) {
                    Toast.makeText(this, "Family not found", Toast.LENGTH_SHORT).show()
                    binding.btnJoinFamily.isEnabled = true
                    return@addOnSuccessListener
                }

                val familyDoc = querySnapshot.documents[0]
                val familyId = familyDoc.id
                val members = familyDoc.get("members") as? List<String> ?: emptyList()
                val limit = familyDoc.getLong("memberLimit")?.toInt() ?: 0
                val familyName = familyDoc.getString("name") ?: "Family"

                if (members.contains(userId)) {
                    Toast.makeText(this, "Already a member", Toast.LENGTH_SHORT).show()
                    navigateToMainActivity()
                    return@addOnSuccessListener
                }

                if (members.size >= limit) {
                    Toast.makeText(this, "Family is full", Toast.LENGTH_SHORT).show()
                    binding.btnJoinFamily.isEnabled = true
                    return@addOnSuccessListener
                }

                // 执行加入事务
                val batch = db.batch()
                batch.update(db.collection("users").document(userId), "familyId", familyId)
                batch.update(db.collection("families").document(familyId), "members", FieldValue.arrayUnion(userId))

                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "Welcome to $familyName!", Toast.LENGTH_LONG).show()
                    navigateToMainActivity()
                }.addOnFailureListener {
                    binding.btnJoinFamily.isEnabled = true
                }
            }
            .addOnFailureListener {
                binding.btnJoinFamily.isEnabled = true
                Toast.makeText(this, "Network Error", Toast.LENGTH_SHORT).show()
            }
    }

    private fun generateFamilyCode(): String {
        val charPool : List<Char> = ('A'..'Z') + ('0'..'9')
        return (1..6).map { charPool.random() }.joinToString("")
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, InventoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}