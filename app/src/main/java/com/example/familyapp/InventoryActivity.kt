// com.example.familyapp/InventoryActivity.kt
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
import com.example.familyapp.adapter.InventoryAdapter
import com.example.familyapp.data.Family
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
        inventoryAdapter = InventoryAdapter(
            this,
            mutableListOf<InventoryListItem>(),
            onQuantityAdd = { item -> adjustItemQuantity(item, 1) },      // 数量 +1
            onQuantitySubtract = { item -> adjustItemQuantity(item, -1) } // 数量 -1
        )
        binding.recyclerViewInventory.apply {
            // 修复 4: 确保 ID 在 activity_inventory.xml 中正确存在
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = inventoryAdapter
        }

        loadUserDataAndInventory(currentUser.uid)

        binding.fabAddItem.setOnClickListener {
            // 🔴 确保 familyId 已加载
            if (currentFamilyId.isNullOrEmpty()) {
                Toast.makeText(this, "Family data is still loading or not set. Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener // 阻止跳转
            }

            val intent = Intent(this, AddItemActivity::class.java).apply {
                putExtra("FAMILY_ID", currentFamilyId) // currentFamilyId 现在保证不为 null
            }
            startActivity(intent)
        }
    }
    override fun onStart() {
        super.onStart()
        // 当用户从 AddItemActivity 返回，或者重新打开 App 时
        // 只要 currentFamilyId 已经拿到，就重新挂载 Firestore 监听器
        currentFamilyId?.let { familyId ->
            listenForInventoryChanges(familyId)
            listenForFamilyChanges(familyId)
            Log.d("InventoryActivity", "Firestore listeners restarted in onStart")
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
                if (e != null) return@addSnapshotListener

                if (snapshots != null) {
                    // 🔴 修改点：手动提取并赋值 Document ID
                    val rawInventoryList = snapshots.map { doc ->
                        val item = doc.toObject(InventoryItemFirestore::class.java)
                        item.id = doc.id // 关键：手动把 Firestore 的文档名赋给 id 变量
                        item
                    }

                    val groupedList = processInventoryData(rawInventoryList)
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
    private fun adjustItemQuantity(item: InventoryItemFirestore, change: Int) {
        if (item.id.isEmpty()) {
            Toast.makeText(this, "Error: Item ID is missing.", Toast.LENGTH_SHORT).show()
            return
        }

        val itemRef = db.collection("inventory").document(item.id)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)

            // 🔴 关键安全检查：确保文档存在
            if (!snapshot.exists()) {
                throw Exception("Item not found in database.")
            }

            // 🔴 关键安全检查：确保 'quantity' 字段存在且可解析
            val currentQuantity = snapshot.getLong("quantity")?.toInt()
                ?: throw Exception("Quantity field is missing or invalid.")

            val newQuantity = currentQuantity + change

            // 3. 检查数量是否有效
            if (newQuantity < 0) {
                // 数量不能是负数 (理论上我们已经阻止了，但为了安全再次检查)
                throw Exception("Quantity cannot be negative.")
            }

            // 4. 更新文档
            if (newQuantity == 0) {
                // 数量为 0 时，删除物品
                transaction.delete(itemRef)
            } else {
                // 更新数量
                transaction.update(itemRef, "quantity", newQuantity)
            }

            null // 事务成功
        }
            .addOnSuccessListener {
                // ... (Toast 提示保持不变) ...
            }
            .addOnFailureListener { e ->
                Log.e("InventoryActivity", "Transaction failed (Quantity Adjustment): ", e)
                // 🔴 改进提示：向用户显示更清晰的错误
                Toast.makeText(this, "Failed to adjust quantity: ${e.message}", Toast.LENGTH_LONG).show()
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