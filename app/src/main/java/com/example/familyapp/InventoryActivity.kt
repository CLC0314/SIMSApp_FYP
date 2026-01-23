package com.example.familyapp

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.familyapp.adapter.InventoryAdapter
import com.example.familyapp.data.Family
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.data.InventoryListItem
import com.example.familyapp.data.ShoppingItem
import com.example.familyapp.databinding.ActivityInventoryBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Locale
import android.graphics.Color

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var inventoryAdapter: InventoryAdapter
    private val auth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private var familyListener: ListenerRegistration? = null
    private var inventoryListener: ListenerRegistration? = null
    private var alertListener: ListenerRegistration? = null

    private var currentFamilyId: String? = null
    private var currentUserName: String? = null
    private var memberNameMap = mutableMapOf<String, String>()
    private var currentSearchQuery = ""
    private var currentFilterType = "ALL"
    private val selectedCategories = mutableSetOf<String>() // 存储多选的 Category
    // 初始化页面，检查登录状态并设置底部导航
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Family Name" // 或者固定标题
        binding.toolbar.setTitleTextColor(Color.WHITE)
        binding.btnScan.setOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 2001)
        }
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.bottomNav.selectedItemId = R.id.nav_inventory

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_shopping -> {
                    val intent = Intent(this, ShoppingListActivity::class.java)
                    intent.putExtra("FAMILY_ID", currentFamilyId)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_family -> {
                    val intent = Intent(this, FamilyMemberActivity::class.java)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_inventory -> true
                else -> false
            }
        }

        setupRecyclerView()
        setupFilters()
        loadUserDataAndInventory(currentUser.uid)

        binding.fabAddItem.setOnClickListener {
            if (currentFamilyId.isNullOrEmpty()) {
                Toast.makeText(this, "Family data loading...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, AddItemActivity::class.java).apply {
                putExtra("FAMILY_ID", currentFamilyId)
                putExtra("OWNER_ID_HINT", "PUBLIC")
            }
            startActivity(intent)
        }
    }

    // 配置 RecyclerView 及其点击/长按和增减数量的交互逻辑
    private fun setupRecyclerView() {
        inventoryAdapter = InventoryAdapter(
            items = mutableListOf(),
            context = this,
            onItemClick = { item ->
                val intent = Intent(this, ItemDetailActivity::class.java).apply {
                    putExtra("ITEM_ID", item.id)
                    putExtra("ITEM_NAME", item.name)
                    putExtra("OWNER_ID", item.ownerId)
                    putExtra("FAMILY_ID", currentFamilyId)
                }
                startActivity(intent)
            },
            onQuantityAdd = { item ->
                if (item.expiryDate > 0) {
                    val intent = Intent(this, AddItemActivity::class.java).apply {
                        putExtra("NAME_HINT", item.name)
                        putExtra("CATEGORY_HINT", item.category)
                        putExtra("UNIT_HINT", item.unit)
                        putExtra("FAMILY_ID", currentFamilyId)
                        putExtra("OWNER_ID_HINT", item.ownerId)
                        putExtra("OWNER_NAME_HINT", item.ownerName)
                    }
                    startActivity(intent)
                } else {
                    db.collection("inventory").document(item.id)
                        .update("quantity", FieldValue.increment(1))
                        .addOnSuccessListener { checkOwnerTotalAndAlert(item.name, item.ownerId) }
                }
            },
            // 修改 setupRecyclerView 中的 onQuantitySubtract
            onQuantitySubtract = { item ->
                val currentUid = auth.currentUser?.uid
                // 🔒 只有公共物品或自己的物品能点减号
                if (item.ownerId == "PUBLIC" || item.ownerId == currentUid) {
                    adjustQuantityFIFO(item.name, item.ownerId)
                } else {
                    Toast.makeText(this@InventoryActivity, "Permission Denied: This is ${item.ownerName}'s", Toast.LENGTH_SHORT).show()
                }
            },
            onItemLongClick = { /* 可选逻辑 */ }
        )

        binding.recyclerViewInventory.apply {
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = inventoryAdapter
        }
    }

    // 处理扫描结果回调
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("SCAN_RESULT")
            if (barcode != null) {
                onBarcodeScanned(barcode)
            }
        }
    }

    // 实现 FIFO（先进先出）逻辑的库存扣减事务
    private fun adjustQuantityFIFO(itemName: String, targetOwnerId: String) {
        val fid = currentFamilyId ?: return
        val currentUid = auth.currentUser?.uid ?: return

        // 权限拦截
        if (targetOwnerId != currentUid && targetOwnerId != "PUBLIC") {
            Toast.makeText(this, "Private item. Permission denied.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.runTransaction { transaction ->
                    // 🟢 修复点：直接在 Query 中加入 ownerId 限制
                    val query = db.collection("inventory")
                        .whereEqualTo("familyId", fid)
                        .whereEqualTo("name", itemName)
                        .whereEqualTo("ownerId", targetOwnerId) // 🔒 锁死归属，只查当前点击的这个人的货
                        .whereEqualTo("pendingSetup", false)
                        .get()

                    val snapshots = com.google.android.gms.tasks.Tasks.await(query)

                    // 找到该归属下最早过期的批次
                    val targetBatch = snapshots.documents.mapNotNull {
                        it.toObject(InventoryItemFirestore::class.java)?.apply { id = it.id }
                    }.minByOrNull { if (it.expiryDate > 0) it.expiryDate else Long.MAX_VALUE }

                    if (targetBatch != null) {
                        val docRef = db.collection("inventory").document(targetBatch.id)
                        val currentQty = targetBatch.quantity

                        if (currentQty <= 1) {
                            transaction.delete(docRef)
                        } else {
                            transaction.update(docRef, "quantity", currentQty - 1)
                        }
                        targetBatch.ownerId // 返回被扣减物品的归属ID用于后续预警检查
                    } else {
                        null
                    }
                }.addOnSuccessListener { ownerId ->
                    if (ownerId != null) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@InventoryActivity, "Stock reduced.", Toast.LENGTH_SHORT).show()
                            // 🟢 这里的检查也会精准锁定该 ownerId 的总量
                            checkOwnerTotalAndAlert(itemName, ownerId as String)
                        }
                    } else {
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@InventoryActivity, "No available stock for this owner.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InventoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 检查物品总量并根据阈值触发低库存预警
    private fun checkOwnerTotalAndAlert(itemName: String, ownerId: String) {
        val fid = currentFamilyId ?: return
        db.collection("inventory")
            .whereEqualTo("familyId", fid)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .addOnSuccessListener { snapshots ->
                if (snapshots.isEmpty) {
                    // 如果东西全删光了，记得把警报也删了
                    db.collection("alerts").document("${itemName.lowercase().trim()}_$ownerId").delete()
                    return@addOnSuccessListener
                }

                val batches = snapshots.toObjects(InventoryItemFirestore::class.java)
                val totalQty = batches.sumOf { it.quantity }

                // 🟢 修复点：从所有批次中找出一个有效的阈值，而不是只看第一个
                val threshold = batches.mapNotNull { it.minThreshold }.firstOrNull { it > 0 }

                val alertDocId = "${itemName.lowercase().trim()}_$ownerId"
                val alertRef = db.collection("alerts").document(alertDocId)

                // 🟢 逻辑优化：有阈值且数量不足 -> 报警；有阈值但数量够了 -> 删除报警
                if (threshold != null) {
                    if (totalQty <= threshold && totalQty > 0) {
                        val alertData = hashMapOf(
                            "itemName" to itemName,
                            "ownerId" to ownerId,
                            "familyId" to fid,
                            "status" to "PENDING",
                            "currentTotal" to totalQty,
                            "threshold" to threshold,
                            "unit" to (batches.firstOrNull { it.unit.isNotEmpty() }?.unit ?: "Pcs"),
                            "ignoredBy" to emptyList<String>()
                        )
                        alertRef.set(alertData)
                    } else if (totalQty > threshold || totalQty <= 0) {
                        // 如果补货了或者彻底没了（数量为0时根据需求可选是否保留，通常建议删除）
                        alertRef.delete()
                    }
                } else {
                    // 如果用户取消了阈值设置，也删掉警报
                    alertRef.delete()
                }
            }
    }

    // 监听家庭范围内的低库存预警
    // 1. 声明一个集合来记录已经处理过的 Alert ID，防止重复弹窗
    private val activeAlertIds = mutableSetOf<String>()
    private fun listenForFamilyAlerts(familyId: String) {
        alertListener?.remove()
        val currentUid = auth.currentUser?.uid ?: return

        alertListener = db.collection("alerts")
            .whereEqualTo("familyId", familyId)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    android.util.Log.e("ALERT_DEBUG", "Error: ${e.message}")
                    return@addSnapshotListener
                }

                // 🟢 暴力测试：只要进来了就打印
                android.util.Log.d("ALERT_DEBUG", "Snapshots received: ${snapshots?.size()}")

                snapshots?.documents?.forEach { alertDoc ->
                    val ownerId = alertDoc.getString("ownerId") ?: ""
                    val currentUidNow = auth.currentUser?.uid // 实时获取

                    if (ownerId == "PUBLIC" || ownerId == currentUidNow) {
                        android.util.Log.d("ALERT_DEBUG", "Condition met for: ${alertDoc.getString("itemName")}")
                        showLowStockSnackbar(alertDoc)
                    }
                }
            }
    }
    // 显示底部低库存提醒条
    private fun showLowStockSnackbar(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val itemName = doc.getString("itemName") ?: "Item"
        val docId = doc.id

        // 1. 这种方式能确保 Snackbar 始终显示在 BottomNav 上方
        // 使用 binding.root 确保它在 CoordinatorLayout 体系内
        val snackbar = Snackbar.make(binding.root, "⚠️ $itemName is running low!", Snackbar.LENGTH_INDEFINITE)

        // 2. 设置锚点（这一行是解决“看不见”的关键）
        snackbar.setAnchorView(binding.bottomNav)

        // 3. 样式加固：增加背景色区别于背景
        snackbar.setBackgroundTint(android.graphics.Color.parseColor("#323232"))
        snackbar.setTextColor(android.graphics.Color.WHITE)

        snackbar.setAction("Add List") {
            // 点击后立即从 activeAlertIds 移除，防止逻辑锁死
            activeAlertIds.remove(docId)
            db.collection("alerts").document(docId).update("status", "ADDED")
            quickAddToShoppingList(doc)
        }

        // 4. 只有在当前没有这个 ID 的警报时才显示
        if (!activeAlertIds.contains(docId)) {
            activeAlertIds.add(docId)
            snackbar.show()
            android.util.Log.d("ALERT_DEBUG", "🟢 Snackbar SHOWN for $itemName")
        }
    }



    // 快速将预警物品添加至购物清单
    private fun quickAddToShoppingList(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val itemName = doc.getString("itemName") ?: ""
        val ownerId = doc.getString("ownerId") ?: "PUBLIC"

        // 🟢 修复点：优先从实时维护的 memberNameMap 中获取名字
        val ownerName = if (ownerId == "PUBLIC") "Public" else (memberNameMap[ownerId] ?: "Member")
        val unit = doc.getString("unit") ?: "Pcs"

        val shoppingItem = ShoppingItem(
            name = itemName,
            quantity = 1,
            unit = unit,
            category = "General",
            familyId = currentFamilyId ?: "",
            inventoryId = "",
            ownerId = ownerId,
            ownerName = ownerName, // 🟢 这样结算回来就一定是正确的名字
            isChecked = false
        )

        db.collection("shopping_lists")
            .whereEqualTo("familyId", currentFamilyId)
            .whereEqualTo("name", itemName)
            .whereEqualTo("ownerId", ownerId)
            .whereEqualTo("isChecked", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snaps ->
                if (!snaps.isEmpty) {
                    snaps.documents[0].reference.update("quantity", FieldValue.increment(1))
                } else {
                    db.collection("shopping_lists").add(shoppingItem)
                }
            }
    }

    // 处理条码扫描成功后的匹配逻辑
    private fun onBarcodeScanned(barcode: String) {
        val fid = currentFamilyId ?: return
        db.collection("families").document(fid)
            .collection("barcode_library").document(barcode).get()
            .addOnSuccessListener { doc ->
                val itemName = doc.getString("name")
                if (itemName != null) {
                    // 需求 2：跳到结果页显示所有 Ownership 详情
                    val intent = Intent(this, BarcodeResultActivity::class.java).apply {
                        putExtra("ITEM_NAME", itemName)
                        putExtra("FAMILY_ID", fid)
                    }
                    startActivity(intent)
                } else {
                    // 需求 4：不引导 Add，直接显示没有记录
                    Toast.makeText(this, "No item record found for this barcode.", Toast.LENGTH_LONG).show()
                }
            }
    }
    private fun loadCategories(familyId: String) {
        // 1. 定义初始分类 (根据 Requirement 5 保持英文)
        val presets = mutableListOf(
            "Fresh Food", "Pantry", "Frozen", "Beverages",
            "Snacks", "Spices", "Cleaning", "Medical",
            "Toiletries", "Others"
        )

        db.collection("families").document(familyId).get().addOnSuccessListener { snapshot ->
            val customs = snapshot.get("customCategories") as? List<String> ?: emptyList()
            // 合并预设和自定义分类，去重并排序
            val allCategories = (presets + customs).distinct().sorted()

            // 2. 🟢 关键：清除旧的 Chip，防止数据刷新时分类栏无限堆叠
            binding.chipGroupCategories.removeAllViews()

            for (catName in allCategories) {
                // 3. 创建美化的 Material Chip
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = catName
                    isCheckable = true
                    isClickable = true

                    // 应用我们在 XML 讨论中提到的颜色选择器
                    setChipBackgroundColorResource(R.color.chip_background_selector)
                    setTextColor(resources.getColorStateList(R.color.chip_text_selector, null))

                    // 现代扁平化设计：去除边框，设置适当的内边距
                    chipStrokeWidth = 0f

                    // 4. 🟢 响应点击：更新多维过滤器的分类集合 (Set)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedCategories.add(catName)
                        } else {
                            selectedCategories.remove(catName)
                        }

                        // 只要点击了分类，就触发全局的多维过滤逻辑
                        performFilteredUpdate()
                    }
                }

                // 5. 将生成的 Chip 添加到第一行布局中
                binding.chipGroupCategories.addView(chip)
            }
        }
    }
    // 加载用户信息及相关的家庭、预警和颜色监听
    private fun loadUserDataAndInventory(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                currentFamilyId = document.getString("familyId")
                currentUserName = document.getString("name")

                if (currentFamilyId.isNullOrEmpty()) {
                    startActivity(Intent(this, FamilySelectionActivity::class.java))
                    finish()
                } else {
                    val fid = currentFamilyId!!
                    listenForFamilyChanges(fid)
                    fetchMemberNames(fid)
                    listenForFamilyAlerts(fid)
                    listenToFamilyColors(fid)
                    loadCategories(fid)
                }
            }
    }

    private var refreshJob: Runnable? = null
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 监听库存集合的变化并触发异步 UI 刷新
    private fun listenForInventoryChanges(familyId: String) {
        inventoryListener = db.collection("inventory")
            .whereEqualTo("familyId", familyId)
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    refreshJob?.let { refreshHandler.removeCallbacks(it) }

                    lifecycleScope.launch(Dispatchers.Default) {
                        val rawList = snapshots.map { doc ->
                            doc.toObject(InventoryItemFirestore::class.java).apply { id = doc.id }
                        }
                        val grouped = processInventoryData(rawList)

                        withContext(Dispatchers.Main) {
                            inventoryAdapter.setAllItems(grouped)
                        }
                    }
                }
            }
    }

    // 辅助函数：格式化时间戳
    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return "N/A"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // 处理原始库存数据，包括分组、聚合批次以及根据过期/低库存状态排序
    private fun processInventoryData(rawList: List<InventoryItemFirestore>): List<InventoryListItem> {
        val groupedList = mutableListOf<InventoryListItem>()
        val currentTime = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        val pendingItems = rawList.filter { it.pendingSetup }
        if (pendingItems.isNotEmpty()) {
            groupedList.add(InventoryListItem.Header("📝 PENDING SETUP"))
            pendingItems.sortedByDescending { it.id }.forEach {
                groupedList.add(InventoryListItem.Item(it))
            }
        }

        val activeItems = rawList.filter { !it.pendingSetup }
        val itemGroups = activeItems.groupBy { "${it.name.lowercase().trim()}_${it.ownerId}" }
        val collapsedList = mutableListOf<InventoryItemFirestore>()

        itemGroups.forEach { (_, batches) ->
            val totalQty = batches.sumOf { it.quantity }
            val representative = batches.minByOrNull { if (it.expiryDate > 0) it.expiryDate else Long.MAX_VALUE }
                ?: batches.first()

            collapsedList.add(representative.copy(
                quantity = totalQty,
                notes = if (batches.size > 1) "Closest Expiry: ${formatDate(representative.expiryDate)}" else ""
            ))
        }

        fun getUrgentStatus(item: InventoryItemFirestore): Int {
            val expiry = item.expiryDate
            val daysLeft = if (expiry > 0) (expiry - currentTime) / oneDayMs else Long.MAX_VALUE
            val isOutOfStock = item.quantity <= 0
            val isExpired = expiry > 0 && daysLeft < 0
            val isExpiringSoon = expiry > 0 && daysLeft <= 7

            val isLowStock = item.minThreshold?.let { threshold ->
                item.quantity <= threshold && item.quantity > 0
            } ?: false

            return when {
                isOutOfStock || isExpired -> 0
                isExpiringSoon -> 1
                isLowStock -> 2
                else -> 3
            }
        }

        val sortedItems = collapsedList.sortedWith(
            compareBy<InventoryItemFirestore> { getUrgentStatus(it) }
                .thenBy { it.category }
                .thenBy { it.name }
        )

        var showedEmergencyHeader = false
        var currentCategory: String? = null

        for (item in sortedItems) {
            val status = getUrgentStatus(item)
            if (status < 3) {
                if (!showedEmergencyHeader) {
                    groupedList.add(InventoryListItem.Header("⚠️ URGENT / LOW STOCK"))
                    showedEmergencyHeader = true
                    currentCategory = "URGENT"
                }
            } else {
                val itemCat = item.category.ifEmpty { "OTHERS" }.uppercase()
                if (itemCat != currentCategory) {
                    currentCategory = itemCat
                    groupedList.add(InventoryListItem.Header(itemCat))
                }
            }
            groupedList.add(InventoryListItem.Item(item))
        }
        return groupedList
    }

    // 设置过滤器 Chip 组与搜索框监听
    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId == -1) { group.check(R.id.chipAll); return@setOnCheckedChangeListener }
            currentFilterType = when (checkedId) {
                R.id.chipPersonal -> "PERSONAL"
                R.id.chipPublic -> "PUBLIC"
                R.id.chipLowStock -> "LOW_STOCK" // 对应新增加的 Chip
                R.id.chipPending -> "PENDING"
                else -> "ALL"
            }
            performFilteredUpdate()
        }

        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean { binding.searchView.clearFocus(); return true }
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                performFilteredUpdate()
                return true
            }
        })
    }

    // 执行过滤操作并重新生成分组数据
    private fun performFilteredUpdate() {
        // 调用 adapter 的过滤逻辑，把所有维度的条件都传进去
        inventoryAdapter.filterMultiDimension(
            query = currentSearchQuery,
            filterType = currentFilterType,
            categories = selectedCategories,
            currentUid = auth.currentUser?.uid
        ) { rawFilteredList ->
            // 处理分组逻辑（保持你原来的 processInventoryData 不变）
            val finalGroupedData = processInventoryData(rawFilteredList)
            inventoryAdapter.updateData(finalGroupedData)
        }
    }

    private val memberColors = mutableMapOf<String, String>()

    // 实时监听家庭成员的颜色设置
    private fun listenToFamilyColors(familyId: String) {
        db.collection("users").whereEqualTo("familyId", familyId)
            .addSnapshotListener { snapshots, _ ->
                memberColors.clear()
                snapshots?.forEach { doc ->
                    val uid = doc.id
                    val color = doc.getString("userColor") ?: "#2196F3"
                    memberColors[uid] = color
                }
                inventoryAdapter.updateUserColors(memberColors)
            }
    }

    // 获取并维护成员 ID 与名称的映射关系
    private fun fetchMemberNames(familyId: String) {
        db.collection("families").document(familyId).get().addOnSuccessListener { familyDoc ->
            val memberUids = familyDoc.get("members") as? List<String> ?: emptyList()
            if (memberUids.isEmpty()) { listenForInventoryChanges(familyId); return@addOnSuccessListener }
            db.collection("users").whereIn(FieldPath.documentId(), memberUids).get()
                .addOnSuccessListener { userSnaps ->
                    memberNameMap.clear()
                    for (doc in userSnaps) doc.getString("name")?.let { memberNameMap[doc.id] = it }
                    listenForInventoryChanges(familyId)
                }
        }
    }

    // 监听家庭基本信息（如名称）的变化
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

    // 显示添加自定义分类的对话框
    private fun showAddCategoryDialog() {
        val familyId = currentFamilyId ?: return
        val editText = android.widget.EditText(this).apply { hint = "e.g. Camping Gear"; setPadding(60, 40, 60, 40) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add Category").setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val newCat = editText.text.toString().trim().lowercase().replaceFirstChar { it.uppercase() }
                if (newCat.isNotEmpty()) db.collection("families").document(familyId)
                    .update("customCategories", FieldValue.arrayUnion(newCat))
            }.setNegativeButton("Cancel", null).show()
    }

    // 执行登出操作并返回登录页面
    private fun logout() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // 在 Activity 销毁时移除所有监听器以防内存泄漏
    override fun onDestroy() {
        super.onDestroy()
        familyListener?.remove()
        inventoryListener?.remove()
        alertListener?.remove()
    }

    // 在页面恢复时确保底部导航选状态正确
    override fun onResume() {
        super.onResume()
        binding.bottomNav.selectedItemId = R.id.nav_inventory
    }
}