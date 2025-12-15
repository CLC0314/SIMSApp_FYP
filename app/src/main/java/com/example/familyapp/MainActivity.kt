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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    // 用于保存 Firestore 监听器，以便在Activity销毁时取消
    private var familyListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 确保用户已登录
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 2. 获取用户的 familyId，并开始监听家庭信息
        getUserFamilyId(currentUser.uid)
    }

    private fun getUserFamilyId(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val familyId = document.getString("familyId")

                if (familyId.isNullOrEmpty()) {
                    // 如果用户没有 familyId，但却跳到了 MainActivity，说明逻辑错误或数据不一致。
                    // 强制跳转回选择家庭界面。
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    // 成功获取 familyId，开始监听家庭详情
                    listenForFamilyChanges(familyId)
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "获取用户家庭ID失败: ", e)
                Toast.makeText(this, "数据加载失败，请检查网络", Toast.LENGTH_LONG).show()
                // 可以考虑登出或重试
            }
    }

    /**
     * 使用实时监听器（Snapshot Listener）获取家庭的最新信息。
     */
    private fun listenForFamilyChanges(familyId: String) {
        // 取消任何现有的监听器
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
                        // 更新 UI
                        updateFamilyUI(family)
                    }
                } else {
                    // 家庭可能已被删除
                    binding.tvFamilyName.text = "家庭已解散或不存在"
                    Toast.makeText(this, "你所属的家庭已被删除", Toast.LENGTH_LONG).show()
                    // 强制用户重新选择家庭
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                }
            }
    }

    /**
     * 根据获取到的 Family 对象更新界面的 TextView。
     */
    private fun updateFamilyUI(family: Family) {
        binding.tvFamilyName.text = family.name

        // 🚨 注意：这里我们只显示了用户ID (UID)，下一步我们会用UID去查询用户的名字
        val memberListText = family.members.joinToString(separator = "\n") { memberId ->
            "UID: $memberId"
        }
        binding.tvMemberList.text = memberListText

        Log.d("MainActivity", "家庭信息更新: ${family.name}, 成员数: ${family.members.size}")
    }

    /**
     * 退出Activity时，确保取消Firestore的实时监听，避免内存泄露。
     */
    override fun onDestroy() {
        super.onDestroy()
        familyListener?.remove()
    }
}