// com.example.familyapp/MainActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.familyapp.data.Family
import com.example.familyapp.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath // 🚨 新增导入：用于按文档ID查询
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private var familyListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        getUserFamilyId(currentUser.uid)

        // 绑定退出登录按钮事件
        binding.btnLogout.setOnClickListener {
            logout()
        }
    }

    private fun getUserFamilyId(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")

                if (familyId.isNullOrEmpty()) {
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    listenForFamilyChanges(familyId)
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "获取用户家庭ID失败: ", e)
                Toast.makeText(this, "数据加载失败，请检查网络", Toast.LENGTH_LONG).show()
            }
    }

    private fun listenForFamilyChanges(familyId: String) {
        familyListener?.remove()

        familyListener = db.collection("families").document(familyId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("MainActivity", "监听家庭数据失败", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val family = snapshot.toObject(Family::class.java)
                    if (family != null) {
                        updateFamilyUI(family)
                    }
                } else {
                    binding.tvFamilyName.text = "家庭已解散或不存在"
                    Toast.makeText(this, "你所属的家庭已被删除", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                }
            }
    }

    /**
     * 根据获取到的 Family 对象更新界面的 TextView，并获取成员姓名。
     */
    private fun updateFamilyUI(family: Family) {
        binding.tvFamilyName.text = family.name

        // 调用新函数，将 UID 列表转换为姓名列表
        fetchMemberNames(family.members)

        Log.d("MainActivity", "家庭信息更新: ${family.name}, 成员数: ${family.members.size}")
    }

    /**
     * 关键逻辑：查询 'users' 集合，将 UID 列表转换为对应的用户名。
     */
    private fun fetchMemberNames(memberUids: List<String>) {
        if (memberUids.isEmpty()) {
            binding.tvMemberList.text = "家庭中没有其他成员"
            return
        }

        // Firestore 的 whereIn() 限制最多 10 个查询项，如果家庭成员超过 10 人，需要分批查询。
        // 假设您的家庭成员数量不会立即超过 10 人。
        db.collection("users")
            // 使用 FieldPath.documentId() 通过文档 ID (UID) 进行查询
            .whereIn(FieldPath.documentId(), memberUids)
            .get()
            .addOnSuccessListener { documents ->
                val names = mutableListOf<String>()
                // 遍历查询结果，提取 'name' 字段
                for (document in documents) {
                    // 我们在注册时存储了 name 字段
                    val name = document.getString("name") ?: "未知成员 (${document.id})"
                    names.add(name)
                }

                // 将所有姓名用换行符连接并显示
                val memberListText = names.joinToString(separator = "\n")
                binding.tvMemberList.text = memberListText
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "获取成员姓名失败", e)
                binding.tvMemberList.text = "获取成员姓名失败"
            }
    }

    /**
     * 实现退出登录功能，并跳转到登录页面。
     */
    private fun logout() {
        familyListener?.remove() // 停止 Firestore 监听，防止内存泄漏
        auth.signOut() // Firebase 退出登录

        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()

        // 跳转到登录页面并清除 Activity 栈
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        familyListener?.remove()
    }
}