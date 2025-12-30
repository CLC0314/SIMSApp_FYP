package com.example.familyapp

import android.content.Intent
import android.os.Bundle
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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Locale
class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var inventoryAdapter: InventoryAdapter
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private var familyListener: ListenerRegistration? = null
    private var inventoryListener: ListenerRegistration? = null

    private var currentFamilyId: String? = null
    private var currentUserName: String? = null
    private var memberNameMap = mutableMapOf<String, String>()
    private var currentSearchQuery = ""
    private var currentFilterType = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 3. 初始化 Adapter
        inventoryAdapter = InventoryAdapter(
            this,
            mutableListOf(),
            onItemClick = { item ->
                val intent = Intent(this, ItemDetailActivity::class.java).apply {
                    putExtra("ITEM_ID", item.id)
                }
                startActivity(intent)
            },
            onItemLongClick = { /* 长按逻辑 */ },
            onQuantityAdd = { item -> adjustItemQuantity(item, 1) },
            onQuantitySubtract = { item -> adjustItemQuantity(item, -1) }
        )

        binding.recyclerViewInventory.apply {
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = inventoryAdapter
        }

        // 5. 🔴 联动搜索与过滤：ChipGroup 监听
        binding.chipGroupFilters.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId == -1) {
                // 如果用户尝试取消选中的 Tag，我们强制选回 "All"
                group.check(R.id.chipAll)
                return@setOnCheckedChangeListener
            }

            currentFilterType = when (checkedId) {
                R.id.chipPersonal -> "PERSONAL"
                R.id.chipPublic -> "PUBLIC"
                R.id.chipHasExpiry -> "EXPIRY"
                else -> "ALL" // 对应 chipAll
            }

            // 触发联动搜索
            inventoryAdapter.filter(currentSearchQuery, currentFilterType, auth.currentUser?.uid)
        }

        // 6. 🔴 联动搜索与过滤：SearchView 监听
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus() // 搜索时收起键盘
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                // 实时联动：过滤时也带着当前的 Chip 状态
                inventoryAdapter.filter(currentSearchQuery, currentFilterType, auth.currentUser?.uid)
                return true
            }
        })

        loadUserDataAndInventory(currentUser.uid)

        binding.fabAddItem.setOnClickListener {
            if (currentFamilyId.isNullOrEmpty()) {
                Toast.makeText(this, "Family data is still loading...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, AddItemActivity::class.java).apply {
                putExtra("FAMILY_ID", currentFamilyId)
            }
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        currentFamilyId?.let {
            listenForInventoryChanges(it)
            listenForFamilyChanges(it)
        }
    }

    private fun loadUserDataAndInventory(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                currentFamilyId = document.getString("familyId")
                currentUserName = document.getString("name")

                if (currentFamilyId.isNullOrEmpty()) {
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    listenForFamilyChanges(currentFamilyId!!)
                    fetchMemberNames(currentFamilyId!!)
                }
            }
    }

    private fun fetchMemberNames(familyId: String) {
        db.collection("families").document(familyId).get()
            .addOnSuccessListener { familyDoc ->
                val memberUids = familyDoc.get("members") as? List<String> ?: emptyList()
                if (memberUids.isEmpty()) {
                    listenForInventoryChanges(familyId)
                    return@addOnSuccessListener
                }

                db.collection("users")
                    .whereIn(FieldPath.documentId(), memberUids)
                    .get()
                    .addOnSuccessListener { userSnapshots ->
                        memberNameMap.clear()
                        for (doc in userSnapshots) {
                            doc.getString("name")?.let { memberNameMap[doc.id] = it }
                        }
                        listenForInventoryChanges(familyId)
                    }
            }
    }

    private fun listenForFamilyChanges(familyId: String) {
        familyListener?.remove()
        familyListener = db.collection("families").document(familyId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val family = snapshot.toObject(Family::class.java)
                    supportActionBar?.title = family?.name
                    binding.toolbar.subtitle = "User: $currentUserName"
                }
            }
    }

    private fun listenForInventoryChanges(familyId: String) {
        inventoryListener?.remove()
        inventoryListener = db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            .orderBy("category")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    val rawInventoryList = snapshots.map { doc ->
                        val item = doc.toObject(InventoryItemFirestore::class.java)
                        item.id = doc.id
                        item
                    }
                    inventoryAdapter.updateData(processInventoryData(rawInventoryList))
                }
            }
    }

    private fun processInventoryData(rawList: List<InventoryItemFirestore>): List<InventoryListItem> {
        val groupedList = mutableListOf<InventoryListItem>()
        val currentTime = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        // 1. 将原始数据按优先级排序
        val sortedList = rawList.map { item ->
            // 自动映射 Owner 名字 (保持你之前的逻辑)
            item.copy(ownerName = memberNameMap[item.ownerId] ?: item.ownerName)
        }.sortedWith(compareBy<InventoryItemFirestore> { item ->
            val expiry = item.expiryDate ?: 0L
            val daysLeft = if (expiry > 0) (expiry - currentTime) / oneDayMs else Long.MAX_VALUE

            when {
                item.quantity <= 0 || (expiry > 0 && daysLeft < 0) -> 0 // 已耗尽或已过期
                daysLeft <= 7 -> 1 // 一周内过期
                else -> 2 // 正常
            }
        }.thenBy { it.category }) // 同一优先级内按分类排

        // 2. 转换成带 Header 的列表格式
        var currentCategory: String? = null

        // 如果有紧急物品，我们可以加一个特殊的 Header
        var showedEmergencyHeader = false

        for (item in sortedList) {
            val expiry = item.expiryDate ?: 0L
            val daysLeft = if (expiry > 0) (expiry - currentTime) / oneDayMs else 999

            // 逻辑：如果是优先级 0 或 1 的物品，统一放在 "URGENT / EXPIRED"
            if (daysLeft <= 7 || item.quantity <= 0) {
                if (!showedEmergencyHeader) {
                    groupedList.add(InventoryListItem.Header("⚠️ URGENT / EXPIRED"))
                    showedEmergencyHeader = true
                    currentCategory = "URGENT"
                }
            } else if (item.category != currentCategory) {
                currentCategory = item.category
                groupedList.add(InventoryListItem.Header(item.category.ifEmpty { "Uncategorized" }.uppercase()))
            }

            groupedList.add(InventoryListItem.Item(item))
        }
        return groupedList
    }

    private fun adjustItemQuantity(item: InventoryItemFirestore, change: Int) {
        if (item.id.isEmpty()) return
        val itemRef = db.collection("inventory").document(item.id)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)
            if (!snapshot.exists()) return@runTransaction null

            val currentQuantity = snapshot.getLong("quantity")?.toInt() ?: 0
            val newQuantity = currentQuantity + change

            if (newQuantity <= 0) {
                transaction.delete(itemRef)
            } else {
                transaction.update(itemRef, "quantity", newQuantity)
            }
            null
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.inventory_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                logout()
                true
            }
            R.id.action_add_category -> {
                showAddCategoryDialog()
                true
            }
            R.id.action_shopping_list -> {
                val intent = Intent(this, ShoppingListActivity::class.java).apply {
                    putExtra("FAMILY_ID", currentFamilyId)
                }
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddCategoryDialog() {
        val familyId = currentFamilyId ?: return
        val editText = android.widget.EditText(this).apply {
            hint = "例如：露营装备、摄影器材"
            setPadding(60, 40, 60, 40)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("添加新分类")
            .setView(editText)
            .setPositiveButton("添加") { _, _ ->
                // 转换为首字母大写，防止重复
                val newCat = editText.text.toString().trim().lowercase()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                if (newCat.isNotEmpty()) {
                    db.collection("families").document(familyId)
                        .update("customCategories", com.google.firebase.firestore.FieldValue.arrayUnion(newCat))
                        .addOnSuccessListener {
                            Toast.makeText(this, "分类 '$newCat' 已添加", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun logout() {
        auth.signOut()
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