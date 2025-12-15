// com.example.familyapp/InventoryActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager

// 修复 1: 确保 Adapter 导入路径正确 (根据您的文件结构)
import com.example.familyapp.data.InventoryAdapter

import com.example.familyapp.data.Family
// 修复 2: 导入正确的数据模型 (替换旧的 InventoryItem)
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.databinding.ActivityInventoryBinding

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var inventoryAdapter: InventoryAdapter
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    // Firestore Listeners
    private var familyListener: ListenerRegistration? = null
    private var inventoryListener: ListenerRegistration? = null

    // State variables
    private var currentFamilyId: String? = null
    private var currentUserName: String? = null
    // 存储家庭成员名字的映射，用于 UID 到 Name 的转换
    private var memberNameMap = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.toolbar)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 修复 3: 初始化 Adapter 时，必须明确指定列表类型
        inventoryAdapter = InventoryAdapter(this, mutableListOf<InventoryListItem>())
        binding.recyclerViewInventory.apply {
            // 修复 4: 确保 ID 在 activity_inventory.xml 中正确存在
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = inventoryAdapter
        }

        loadUserDataAndInventory(currentUser.uid)

        binding.fabAddItem.setOnClickListener {
            Toast.makeText(this, "Functionality to add item coming soon!", Toast.LENGTH_SHORT).show()
        }
    }

    // ===============================================
    // 数据加载和监听
    // ===============================================

    private fun loadUserDataAndInventory(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                currentFamilyId = document.getString("familyId")
                currentUserName = document.getString("name")

                if (currentFamilyId.isNullOrEmpty()) {
                    Toast.makeText(this, "Please join a family first.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    listenForFamilyChanges(currentFamilyId!!)

                    // 新增：先获取所有成员的 UID-Name 映射，再加载库存
                    fetchMemberNames(currentFamilyId!!)
                }
            }
            .addOnFailureListener { e ->
                Log.e("InventoryActivity", "Failed to load user data: ", e)
                Toast.makeText(this, "Failed to load data. Check network.", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * 获取家庭的所有成员的 UID -> Name 映射，用于库存物品显示。
     */
    private fun fetchMemberNames(familyId: String) {
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { familyDoc ->
                val memberUids = familyDoc.get("members") as? List<String> ?: emptyList()
                if (memberUids.isEmpty()) {
                    listenForInventoryChanges(familyId) // 没有成员，直接加载库存
                    return@addOnSuccessListener
                }

                // 批量获取成员的 name 字段
                db.collection("users")
                    .whereIn(FieldPath.documentId(), memberUids)
                    .get()
                    .addOnSuccessListener { userSnapshots ->
                        memberNameMap.clear()
                        for (doc in userSnapshots) {
                            val name = doc.getString("name")
                            if (name != null) {
                                memberNameMap[doc.id] = name
                            }
                        }
                        // 成员名字获取完毕后，加载库存
                        listenForInventoryChanges(familyId)
                    }
                    .addOnFailureListener { e ->
                        Log.e("InventoryActivity", "Failed to fetch member names: ", e)
                        listenForInventoryChanges(familyId) // 即使失败也要尝试加载库存
                    }
            }
    }

    private fun listenForFamilyChanges(familyId: String) {
        familyListener?.remove()

        familyListener = db.collection("families").document(familyId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener

                if (snapshot != null && snapshot.exists()) {
                    val family = snapshot.toObject(Family::class.java)
                    if (family != null) {
                        supportActionBar?.title = family.name
                        binding.toolbar.subtitle = "User: $currentUserName"
                    }
                }
            }
    }

    /**
     * 实时监听库存列表变化
     */
    private fun listenForInventoryChanges(familyId: String) {
        inventoryListener?.remove()

        inventoryListener = db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            .orderBy("category")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("InventoryActivity", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    // 修复 5: 必须使用 InventoryItemFirestore
                    val rawInventoryList = snapshots.toObjects(InventoryItemFirestore::class.java)

                    // 核心步骤：处理分组和 UID-Name 转换
                    val groupedList = processInventoryData(rawInventoryList)

                    // 修复 6: updateData 现在接收正确的分组列表类型
                    inventoryAdapter.updateData(groupedList)
                }
            }
    }

    /**
     * 关键逻辑：将扁平的 InventoryItemFirestore 列表
     * 转换为带有 Header 的 InventoryListItem 列表，并注入 Owner Name。
     */
    private fun processInventoryData(rawList: List<InventoryItemFirestore>): List<InventoryListItem> {
        if (rawList.isEmpty()) return emptyList()

        val groupedList = mutableListOf<InventoryListItem>()
        var currentCategory: String? = null

        // 1. 注入 Owner Name
        val processedList = rawList.map { item ->
            val ownerName = memberNameMap[item.ownerId] ?: item.ownerName // 尝试从 map 中获取
            item.copy(ownerName = ownerName)
        }

        // 2. 遍历并分组 (由于 Firestore 已经按 Category 排序，这很简单)
        for (item in processedList) {
            if (item.category != currentCategory) {
                // 插入新的 Header
                currentCategory = item.category
                val headerTitle = if (item.category.isEmpty()) "Uncategorized" else item.category.uppercase()
                groupedList.add(InventoryListItem.Header(headerTitle))
            }
            // 插入 Item
            groupedList.add(InventoryListItem.Item(item))
        }

        return groupedList
    }


    // ===============================================
    // 菜单和退出登录
    // ===============================================

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.inventory_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_members -> {
                // TODO: (P2) 实现查看成员的逻辑
                Toast.makeText(this, "View Family Members functionality here.", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun logout() {
        familyListener?.remove()
        inventoryListener?.remove()
        auth.signOut()

        Toast.makeText(this, "Logged out successfully.", Toast.LENGTH_SHORT).show()

        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        familyListener?.remove()
        inventoryListener?.remove()
    }
}