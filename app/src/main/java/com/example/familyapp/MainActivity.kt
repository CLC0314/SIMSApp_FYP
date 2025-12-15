// com.example.familyapp/MainActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.familyapp.adapter.FamilyMemberAdapter
import com.example.familyapp.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.ktx.Firebase
import android.content.Context.CLIPBOARD_SERVICE
import java.text.SimpleDateFormat
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FamilyMemberAdapter

    // ❌ 移除 SQLite 引用，例如 FamilyDatabaseHelper
    // private lateinit var databaseHelper: FamilyDatabaseHelper

    // 🆕 Firebase 相关的实例和变量
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var familyListener: ListenerRegistration? = null
    private var currentFamilyId: String? = null
    private var currentUserName: String? = null
    private var currentFamilyCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🆕 初始化 Firebase
        auth = Firebase.auth
        db = FirebaseFirestore.getInstance()

        setupUI()
        setupNavigation()

        // 检查用户登录状态
        val userId = auth.currentUser?.uid
        if (userId == null) {
            // 如果未登录，跳转回 LoginActivity
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 1. 获取当前用户的 Family ID 和 Name
        getUserProfile(userId) { familyId, name ->
            currentUserName = name
            if (familyId != null) {
                currentFamilyId = familyId
                // 2. 只有拿到 familyId 后才开始监听家庭成员数据
                setupFamilyListener(familyId)
                // 3. 获取家庭 Code 并显示
                getFamilyCode(familyId)
            } else {
                binding.toolbar.subtitle = "未加入家庭"
                Toast.makeText(this, "您尚未加入家庭，请先设置家庭共享。", Toast.LENGTH_LONG).show()
                // 跳转到 FamilySelectionActivity
                startActivity(Intent(this, FamilySelectionActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止监听，避免内存泄漏
        familyListener?.remove()
    }

    // 🆕 获取用户的 Family ID 和 Name
    private fun getUserProfile(userId: String, callback: (String?, String?) -> Unit) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")
                val name = document.getString("name")
                callback(familyId, name)
            }
            .addOnFailureListener {
                Toast.makeText(this, "获取用户信息失败", Toast.LENGTH_LONG).show()
                callback(null, null)
            }
    }

    // 🆕 获取当前家庭的 Family Code
    private fun getFamilyCode(familyId: String) {
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { document ->
                val code = document.getString("code")
                if (code != null) {
                    currentFamilyCode = code
                    binding.tvFamilyCode.text = "家庭代码: $code"
                    binding.tvFamilyCode.visibility = android.view.View.VISIBLE
                }
            }
            .addOnFailureListener {
                binding.tvFamilyCode.text = "家庭代码加载失败"
            }
    }

    // 🆕 实时监听 Firestore 家庭成员数据
    private fun setupFamilyListener(familyId: String) {
        // 监听 Firestore 中 familyId 匹配的用户文档
        familyListener = db.collection("users")
            .whereEqualTo("familyId", familyId)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    binding.toolbar.subtitle = "成员加载失败"
                    Toast.makeText(this, "成员监听失败: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val memberList = mutableListOf<FamilyMemberFirestore>()
                    for (doc in snapshots.documents) {
                        val member = doc.toObject(FamilyMemberFirestore::class.java)
                        member?.let {
                            it.userId = doc.id // 存储用户的 UID
                            memberList.add(it)
                        }
                    }

                    // 更新 UI
                    adapter = FamilyMemberAdapter(
                        memberList,
                        onItemClick = { member -> showMemberDetails(member) }
                    )
                    binding.recyclerView.adapter = adapter
                    binding.toolbar.subtitle = "共有 ${memberList.size} 位家庭成员"
                }
            }
    }

    // =========================================================================
    // UI 和导航
    // =========================================================================

    private fun setupUI() {
        binding.toolbar.title = "家庭成员"
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        // 🆕 添加退出登录按钮 (如果之前没有的话)
        binding.btnLogout.setOnClickListener {
            showLogoutConfirmation()
        }

        // 🆕 Family Code 复制功能
        binding.tvFamilyCode.setOnClickListener {
            if (currentFamilyCode != null) {
                copyCodeToClipboard(currentFamilyCode!!)
            }
        }
    }

    private fun setupNavigation() {
        // 导航栏逻辑保持不变
        binding.btnInventory.setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
            finish()
        }
        binding.btnFamily.setOnClickListener {
            binding.btnFamily.setBackgroundColor(0xFFE3F2FD.toInt())
            binding.btnInventory.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        binding.btnFamily.setBackgroundColor(0xFFE3F2FD.toInt())
        binding.btnInventory.setBackgroundColor(0xFFFFFFFF.toInt())
    }

    // 成员详情对话框 (使用新的 FamilyMemberFirestore 模型)
    private fun showMemberDetails(member: FamilyMemberFirestore) {
        val detailMessage = """
        姓名: ${member.name}
        邮箱: ${member.email}
        角色: ${member.role}
        加入日期: ${member.createdAt?.let { SimpleDateFormat("yyyy-MM-dd").format(it) } ?: "未知"}
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("${member.name} 的详情")
            .setMessage(detailMessage)
            .setPositiveButton("关闭", null)
            .show()
    }

    // 🆕 退出登录功能
    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出当前的账号吗？")
            .setPositiveButton("退出") { _, _ ->
                auth.signOut() // Firebase 退出
                // 跳转到登录页面并清除 Activity 栈
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 🆕 复制 Family Code 到剪贴板
    private fun copyCodeToClipboard(code: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Family Code", code)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "家庭代码已复制到剪贴板！", Toast.LENGTH_SHORT).show()
    }
}