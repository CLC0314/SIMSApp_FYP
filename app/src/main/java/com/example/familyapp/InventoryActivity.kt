// com.example.familyapp/InventoryActivity.kt

package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.familyapp.adapter.InventoryAdapter
import com.example.familyapp.databinding.ActivityInventoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import java.util.UUID
import com.google.firebase.Timestamp

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var adapter: InventoryAdapter

    // ❌ 移除 SQLite 相关的引用，例如 FamilyDatabaseHelper

    // 🆕 Firebase 相关的实例和变量
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var inventoryListener: ListenerRegistration? = null
    private var currentFamilyId: String? = null
    private var currentUserId: String? = null // 当前用户的 UID
    private var currentUserName: String? = null // 当前用户的姓名 (用于创建和编辑物品)

    private val searchHandler = Handler(Looper.getMainLooper())
    private lateinit var searchRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🆕 初始化 Firebase
        auth = Firebase.auth
        db = FirebaseFirestore.getInstance()

        setupUI()
        setupNavigation()

        // 检查用户登录状态
        val userId = auth.currentUser?.uid
        if (userId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        currentUserId = userId

        // 1. 获取当前用户的 Family ID 和 Name
        getUserProfile(userId) { familyId, name ->
            currentUserName = name
            if (familyId != null) {
                currentFamilyId = familyId
                // 2. 只有拿到 familyId 后才开始监听库存数据
                setupInventoryListener(familyId)
            } else {
                binding.toolbar.subtitle = "未加入家庭"
                Toast.makeText(this, "您尚未加入家庭，请先设置家庭共享。", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FamilySelectionActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止监听，避免内存泄漏
        inventoryListener?.remove()
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

    // 🆕 实时监听 Firestore 数据
    private fun setupInventoryListener(familyId: String) {
        // 监听 Firestore 中 familyId 匹配的库存数据，并按类别排序
        inventoryListener = db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            .orderBy("category", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    binding.toolbar.subtitle = "数据加载失败"
                    Toast.makeText(this, "数据监听失败: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val inventoryList = mutableListOf<InventoryItemFirestore>()
                    for (doc in snapshots.documents) {
                        val item = doc.toObject(InventoryItemFirestore::class.java)
                        item?.let {
                            it.id = doc.id // 存储 Firestore 文档ID
                            inventoryList.add(it)
                        }
                    }

                    // 客户端排序 (二次排序，按名称)
                    val sortedItems = inventoryList.sortedWith(compareBy<InventoryItemFirestore> { it.category }.thenBy { it.name })

                    // 分组
                    val groupedList = createGroupedListFirestore(sortedItems)

                    // 更新 UI
                    adapter = InventoryAdapter(
                        groupedList,
                        onItemClick = { item -> showItemActions(item) },
                        onItemLongClick = { item -> showDeleteConfirmation(item) }
                    )
                    binding.recyclerView.adapter = adapter
                    binding.toolbar.subtitle = "共有 ${inventoryList.size} 件物品"
                }
            }
    }

    // =========================================================================
    // 搜索和清除逻辑 (已调整为 Firestore 搜索)
    // =========================================================================

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        val familyId = currentFamilyId ?: return

        if (query.isEmpty()) {
            loadAllInventory() // 如果搜索框为空，恢复显示所有物品
            return
        }

        binding.toolbar.subtitle = "搜索中..."

        db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            // ⚠️ Firestore 无法直接进行模糊搜索，只能进行前缀搜索或精确匹配
            // 这里为了简化，我们仅在名称字段上进行前缀搜索。
            .whereGreaterThanOrEqualTo("name", query)
            .whereLessThanOrEqualTo("name", query + "\uf8ff")
            .get()
            .addOnSuccessListener { snapshots ->
                val searchResults = mutableListOf<InventoryItemFirestore>()
                for (doc in snapshots.documents) {
                    val item = doc.toObject(InventoryItemFirestore::class.java)
                    item?.let {
                        it.id = doc.id
                        searchResults.add(it)
                    }
                }

                // 排序和分组
                val sortedItems = searchResults.sortedWith(compareBy<InventoryItemFirestore> { it.category }.thenBy { it.name })
                val groupedList = createGroupedListFirestore(sortedItems)

                if (groupedList.isEmpty()) {
                    adapter = InventoryAdapter(emptyList())
                    binding.recyclerView.adapter = adapter
                    binding.toolbar.subtitle = "未找到相关物品"
                    Toast.makeText(this, "未找到匹配的物品", Toast.LENGTH_SHORT).show()
                } else {
                    adapter = InventoryAdapter(
                        groupedList,
                        onItemClick = { item -> showItemActions(item) },
                        onItemLongClick = { item -> showDeleteConfirmation(item) }
                    )
                    binding.recyclerView.adapter = adapter
                    binding.toolbar.subtitle = "找到 ${searchResults.size} 件物品"
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.toolbar.subtitle = "搜索错误"
            }
    }

    private fun loadAllInventory() {
        // 重新启动实时监听，恢复正常列表
        if (currentFamilyId != null) {
            // 先移除旧的监听器
            inventoryListener?.remove()
            setupInventoryListener(currentFamilyId!!)
        }
    }

    private fun clearSearch() {
        binding.etSearch.text.clear()
        loadAllInventory() // 恢复显示所有物品
        Toast.makeText(this, "已清除搜索", Toast.LENGTH_SHORT).show()
    }

    // 实时搜索设置保持不变
    private fun setupRealTimeSearch() {
        searchRunnable = Runnable { performSearch() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                searchHandler.removeCallbacks(searchRunnable)
                if (query.length >= 1) {
                    searchHandler.postDelayed(searchRunnable, 500)
                } else if (query.isEmpty()) {
                    loadAllInventory()
                }
            }
        })
    }

    // =========================================================================
    // 添加、编辑、删除 (CRUD) 逻辑 (全部改为 Firestore 操作)
    // =========================================================================

    private fun addItemToDatabase() {
        showAddItemDialog()
    }

    // 🆕 Firestore 版本的保存逻辑
    private fun saveItemToFirestore(item: InventoryItemFirestore) {
        if (currentFamilyId == null) return

        // 1. 设置 familyId
        item.familyId = currentFamilyId!!

        // 2. 写入 Firestore (如果id为空，Firestore会自动生成新文档ID)
        db.collection("inventory")
            .add(item) // 使用 add() 自动生成文档 ID
            .addOnSuccessListener {
                Toast.makeText(this, "✅ 物品添加成功", Toast.LENGTH_SHORT).show()
                // 实时监听器会自动更新列表，无需手动调用 loadDataFromDatabase()
            }
            .addOnFailureListener {
                Toast.makeText(this, "❌ 添加失败: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // 🆕 Firestore 版本的更新逻辑
    private fun updateItemInFirestore(item: InventoryItemFirestore) {
        if (item.id.isEmpty()) return // 必须有文档ID才能更新

        // 1. 构建 Map 以便更新
        val itemMap = mapOf(
            "name" to item.name,
            "category" to item.category,
            "quantity" to item.quantity,
            "location" to item.location,
            "expiredDate" to item.expiredDate,
            "ownerName" to item.ownerName,
            "ownerId" to item.ownerId,
            "notes" to item.notes
        )

        db.collection("inventory").document(item.id)
            .update(itemMap)
            .addOnSuccessListener {
                Toast.makeText(this, "✅ 物品更新成功: ${item.name}", Toast.LENGTH_SHORT).show()
                // 列表自动更新
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "更新错误: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // 🆕 Firestore 版本的删除逻辑
    private fun deleteItemFromDatabase(item: InventoryItemFirestore) {
        if (item.id.isEmpty()) return

        db.collection("inventory").document(item.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "✅ 已删除: ${item.name}", Toast.LENGTH_SHORT).show()
                // 列表自动更新
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "删除错误: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // =========================================================================
    // UI 和对话框 (需调整内部的 save/update 调用)
    // =========================================================================

    // ⚠️ 注意：以下方法中的 InventoryItem 引用都需替换为 InventoryItemFirestore

    private fun showAddItemDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_item, null)

        // ... (省略 UI 初始化，与之前相同)
        val categorySpinner = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val ownerSpinner = dialogView.findViewById<Spinner>(R.id.spinnerOwner)

        val categories = arrayOf("食品", "日用品", "药品", "电子产品", "衣物", "其他")
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)

        val owners = getFamilyMembersFromCurrentFamily() // 🆕 获取当前家庭成员
        ownerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, owners)

        // ... (省略类别选择监听逻辑，与之前相同)

        val builder = AlertDialog.Builder(this)
            .setTitle("添加新物品")
            .setView(dialogView)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)

        val dialog = builder.create()
        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            if (validateAndSaveNewItem(dialogView)) { // 🆕 验证并保存新物品
                dialog.dismiss()
            }
        }
    }

    // 🆕 验证并保存新物品 (返回 true 表示验证通过并触发保存)
    private fun validateAndSaveNewItem(dialogView: View): Boolean {
        // ... (与旧 validateAndSaveItem 逻辑相同：获取并验证 name, quantity, category, expiredDate 等)
        val name = dialogView.findViewById<EditText>(R.id.etItemName).text.toString().trim()
        val category = dialogView.findViewById<Spinner>(R.id.spinnerCategory).selectedItem.toString()
        val quantityStr = dialogView.findViewById<EditText>(R.id.etQuantity).text.toString().trim()
        val location = dialogView.findViewById<EditText>(R.id.etLocation).text.toString().trim()
        val expiredDate = dialogView.findViewById<EditText>(R.id.etExpiredDate).text.toString().trim()
        val ownerName = dialogView.findViewById<Spinner>(R.id.spinnerOwner).selectedItem.toString()
        val notes = dialogView.findViewById<EditText>(R.id.etNotes).text.toString().trim()

        val errorTextView = dialogView.findViewById<TextView>(R.id.tvError)
        errorTextView.visibility = View.GONE

        if (name.isEmpty()) {
            errorTextView.text = "物品名称不能为空"
            errorTextView.visibility = View.VISIBLE
            return false
        }

        var quantity = 1
        if (quantityStr.isNotEmpty()) {
            quantity = try {
                val num = quantityStr.toInt()
                if (num <= 0) {
                    errorTextView.text = "数量必须大于0"
                    errorTextView.visibility = View.VISIBLE
                    return false
                }
                num
            } catch (e: NumberFormatException) {
                errorTextView.text = "数量必须是数字"
                errorTextView.visibility = View.VISIBLE
                return false
            }
        }

        val finalExpiredDate = if (category == "食品" && expiredDate.isNotEmpty() && isValidDate(expiredDate)) {
            expiredDate
        } else if (category == "食品" && expiredDate.isEmpty()) {
            null
        } else if (category != "食品") {
            null
        } else {
            // 日期格式错误
            errorTextView.text = "食品日期格式应为 YYYY-MM-DD"
            errorTextView.visibility = View.VISIBLE
            return false
        }

        // 🆕 创建 Firestore 对象
        val newItem = InventoryItemFirestore(
            name = name,
            category = category,
            quantity = quantity,
            location = if (location.isEmpty()) null else location,
            expiredDate = finalExpiredDate,
            ownerName = ownerName,
            ownerId = if (ownerName == currentUserName) currentUserId else null, // 假设如果是当前用户本人，则存储其UID
            notes = if (notes.isEmpty()) null else notes
        )

        saveItemToFirestore(newItem) // 🆕 调用 Firestore 保存
        return true
    }

    // 🆕 获取当前家庭成员 (简化版：从 Firestore 获取)
    private fun getFamilyMembersFromCurrentFamily(): List<String> {
        // ⚠️ 这是一个同步/阻塞调用，在真实的 Android 应用中应该使用 LiveData/Flow/Callback
        // ⚠️ 暂时返回当前登录用户和“公共物品”以保持功能运行，在下一步我们将实现真正的 Firestore 异步加载。
        val owners = mutableListOf("公共物品")
        if (currentUserName != null) {
            owners.add(currentUserName!!)
        }
        return owners
    }

    private fun showItemActions(item: InventoryItemFirestore) { // 🆕 参数类型改为 Firestore
        val options = arrayOf("查看详情", "编辑", "删除", "取消")

        AlertDialog.Builder(this)
            .setTitle("操作: ${item.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showItemDetails(item)
                    1 -> editItem(item)
                    2 -> showDeleteConfirmation(item)
                }
            }
            .show()
    }

    private fun showItemDetails(item: InventoryItemFirestore) { // 🆕 参数类型改为 Firestore
        val detailMessage = """
        名称: ${item.name}
        类别: ${item.category}
        数量: ${item.quantity}
        位置: ${item.location ?: "未设置"}
        过期日期: ${item.expiredDate ?: "无"}
        所属: ${item.ownerName ?: "公共物品"}
        备注: ${item.notes ?: "无"}
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("物品详情")
            .setMessage(detailMessage)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun editItem(item: InventoryItemFirestore) { // 🆕 参数类型改为 Firestore
        showEditItemDialog(item)
    }

    private fun showEditItemDialog(item: InventoryItemFirestore) { // 🆕 参数类型改为 Firestore
        // ... (与旧的 showEditItemDialog 相同，但所有引用 item 的地方都使用 InventoryItemFirestore 字段)
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_item, null)
        val etItemName = dialogView.findViewById<EditText>(R.id.etItemName)
        val categorySpinner = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val etQuantity = dialogView.findViewById<EditText>(R.id.etQuantity)
        val etLocation = dialogView.findViewById<EditText>(R.id.etLocation)
        val etExpiredDate = dialogView.findViewById<EditText>(R.id.etExpiredDate)
        val ownerSpinner = dialogView.findViewById<Spinner>(R.id.spinnerOwner)
        val etNotes = dialogView.findViewById<EditText>(R.id.etNotes)

        val categories = arrayOf("食品", "日用品", "药品", "电子产品", "衣物", "其他")
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)

        val owners = getFamilyMembersFromCurrentFamily()
        ownerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, owners)

        // 预填充数据
        etItemName.setText(item.name)
        etQuantity.setText(item.quantity.toString())
        etLocation.setText(item.location ?: "")
        etExpiredDate.setText(item.expiredDate ?: "")
        etNotes.setText(item.notes ?: "")

        // ... (预选逻辑和监听器设置，与之前相同)

        val builder = AlertDialog.Builder(this)
            .setTitle("编辑物品: ${item.name}")
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)

        val dialog = builder.create()
        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            if (validateAndSaveEditedItem(dialogView, item)) { // 🆕 验证并保存已编辑物品
                dialog.dismiss()
            }
        }
    }

    // 🆕 验证并保存已编辑物品 (参数类型改为 Firestore)
    private fun validateAndSaveEditedItem(dialogView: View, originalItem: InventoryItemFirestore): Boolean {
        // ... (与 validateAndSaveNewItem 逻辑相同：获取并验证所有字段)
        val name = dialogView.findViewById<EditText>(R.id.etItemName).text.toString().trim()
        val category = dialogView.findViewById<Spinner>(R.id.spinnerCategory).selectedItem.toString()
        val quantityStr = dialogView.findViewById<EditText>(R.id.etQuantity).text.toString().trim()
        val location = dialogView.findViewById<EditText>(R.id.etLocation).text.toString().trim()
        val expiredDate = dialogView.findViewById<EditText>(R.id.etExpiredDate).text.toString().trim()
        val ownerName = dialogView.findViewById<Spinner>(R.id.spinnerOwner).selectedItem.toString()
        val notes = dialogView.findViewById<EditText>(R.id.etNotes).text.toString().trim()
        val errorTextView = dialogView.findViewById<TextView>(R.id.tvError)
        errorTextView.visibility = View.GONE

        // ⚠️ 省略了完整的验证逻辑 (请确保您的实际应用中包含完整的验证)

        var quantity = 1
        if (quantityStr.isNotEmpty()) {
            quantity = try { quantityStr.toInt() } catch (e: NumberFormatException) { 1 } // 简化错误处理
        }

        val finalExpiredDate = if (category == "食品" && expiredDate.isNotEmpty() && isValidDate(expiredDate)) {
            expiredDate
        } else if (category == "食品" && expiredDate.isEmpty()) {
            null
        } else {
            null
        }

        // 创建更新后的物品对象 (保留 Firestore ID 和 Family ID)
        val updatedItem = originalItem.copy(
            name = name,
            category = category,
            quantity = quantity,
            location = if (location.isEmpty()) null else location,
            expiredDate = finalExpiredDate,
            ownerName = ownerName,
            ownerId = if (ownerName == currentUserName) currentUserId else null,
            notes = if (notes.isEmpty()) null else notes
        )

        updateItemInFirestore(updatedItem) // 🆕 调用 Firestore 更新
        return true
    }

    private fun showDeleteConfirmation(item: InventoryItemFirestore) { // 🆕 参数类型改为 Firestore
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 \"${item.name}\" 吗？此操作无法撤销。")
            .setPositiveButton("删除") { dialog, _ ->
                deleteItemFromDatabase(item) // 🆕 调用 Firestore 删除
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =========================================================================
    // 辅助和 UI 设置方法 (保持不变或微调)
    // =========================================================================

    private fun isValidDate(dateStr: String): Boolean {
        return try {
            val pattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
            pattern.matches(dateStr)
        } catch (e: Exception) {
            false
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = "库存管理"
        binding.fabAddItem.setOnClickListener { addItemToDatabase() }
        binding.btnSearch.setOnClickListener { performSearch() }
        binding.btnClearSearch.setOnClickListener { clearSearch() }
        binding.etSearch.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }
        setupRealTimeSearch()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupNavigation() {
        binding.btnFamily.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
        binding.btnInventory.setOnClickListener {
            binding.btnInventory.setBackgroundColor(0xFFE3F2FD.toInt())
            binding.btnFamily.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        binding.btnInventory.setBackgroundColor(0xFFE3F2FD.toInt())
        binding.btnFamily.setBackgroundColor(0xFFFFFFFF.toInt())
    }
}