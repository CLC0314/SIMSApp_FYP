package com.example.familyapp.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.R
import com.example.familyapp.data.InventoryItemFirestore
import com.example.familyapp.data.InventoryListItem
import com.example.familyapp.databinding.ItemInventoryBinding
import com.example.familyapp.databinding.ItemInventoryHeaderBinding
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class InventoryAdapter(
    private val context: Context,
    private var items: MutableList<InventoryListItem>,
    private val onItemClick: (InventoryItemFirestore) -> Unit,
    private val onItemLongClick: (InventoryItemFirestore) -> Unit,
    private val onQuantityAdd: (InventoryItemFirestore) -> Unit,
    private val onQuantitySubtract: (InventoryItemFirestore) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_HEADER = 0
    private val VIEW_TYPE_ITEM = 1
    private val auth = FirebaseAuth.getInstance()
    // 🟢 核心修正：这是全量数据的“金库”，只由 Firebase 更新
    private var allItemsFull: List<InventoryListItem> = mutableListOf()
    private var userColorMap: Map<String, String> = emptyMap()

    // 🟢 2. 添加一个方法，让 Activity 可以把最新的颜色表传进来
    fun updateUserColors(newColors: Map<String, String>) {
        this.userColorMap = newColors
        notifyDataSetChanged()
    }
    // 🟢 核心修正：当 Firebase 监听到数据变化时调用此方法
    fun setAllItems(newItems: List<InventoryListItem>) {
        allItemsFull = ArrayList(newItems)
        updateDisplayList(newItems)
    }

    // 🟢 核心修正：当 Chip 或搜索过滤时调用此方法（不破坏金库）
    fun updateData(newDisplayItems: List<InventoryListItem>) {
        updateDisplayList(newDisplayItems)
    }

    private fun updateDisplayList(newItems: List<InventoryListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
    fun filterMultiDimension(
        query: String,
        filterType: String,
        categories: Set<String>,
        currentUid: String?,
        onResult: (List<InventoryItemFirestore>) -> Unit
    ) {
        // 🟢 修正 1：从“金库” allItemsFull 中提取原始 InventoryItemFirestore 数据
        val allRawItems = allItemsFull.filterIsInstance<InventoryListItem.Item>().map { it.item }

        val filtered = allRawItems.filter { item ->
            // 1. 检查搜索词 (Name search)
            val matchesQuery = item.name.contains(query, ignoreCase = true)

            // 2. 检查分类 (Categories - 多选逻辑)
            val matchesCategory = if (categories.isEmpty()) true
            else categories.contains(item.category)

            // 3. 检查状态标签 (Status Tags)
            val matchesType = when (filterType) {
                "PERSONAL" -> item.ownerId == currentUid
                "PUBLIC" -> item.ownerId == "PUBLIC"
                "PENDING" -> item.pendingSetup
                // 🟢 修正 2：Low Stock 的逻辑要严谨
                "LOW_STOCK" -> {
                    val threshold = item.minThreshold ?: 0
                    item.quantity <= threshold && item.quantity > 0
                }
                "EXPIRY" -> item.expiryDate > 0 // 兼容你之前的过滤类型
                else -> true // "ALL"
            }

            matchesQuery && matchesCategory && matchesType
        }
        onResult(filtered)
    }
    fun filter(query: String, filterType: String, currentUid: String?, onResult: (List<InventoryItemFirestore>) -> Unit) {
        val lowerCaseQuery = query.lowercase(Locale.getDefault())

        // 从“金库” allItemsFull 中提取原始数据进行筛选
        val allRawItems = allItemsFull.filterIsInstance<InventoryListItem.Item>().map { it.item }

        val filteredRawList = allRawItems.filter { item ->
            val matchesQuery = item.name.lowercase().contains(lowerCaseQuery) ||
                    item.category.lowercase().contains(lowerCaseQuery)

            val matchesFilter = when (filterType) {
                "PERSONAL" -> item.ownerId == currentUid
                "PUBLIC"   -> item.ownerId == "PUBLIC"
                "EXPIRY"   -> item.expiryDate > 0
                "PENDING"  -> item.pendingSetup == true
                else -> true
            }
            matchesQuery && matchesFilter
        }

        onResult(filteredRawList)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is InventoryListItem.Header -> VIEW_TYPE_HEADER
        is InventoryListItem.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val binding = ItemInventoryHeaderBinding.inflate(LayoutInflater.from(context), parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemInventoryBinding.inflate(LayoutInflater.from(context), parent, false)
            ItemViewHolder(binding)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is InventoryListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is InventoryListItem.Item -> (holder as ItemViewHolder).bind(
                item.item,
                onItemClick,
                onItemLongClick,
                onQuantityAdd,
                onQuantitySubtract
            )
        }
    }

    inner class HeaderViewHolder(private val binding: ItemInventoryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: InventoryListItem.Header) {
            val title = header.title
            binding.tvHeaderTitle.text = title

            // 🟢 动态染色逻辑
            when {
                // 1. 低库存警报 (Low Stock)
                title.contains("LOW STOCK", ignoreCase = true) -> {
                    binding.llHeaderBg.setBackgroundColor(Color.parseColor("#FFEBEE")) // 浅红背景
                    binding.tvHeaderTitle.setTextColor(Color.parseColor("#D32F2F"))   // 深红文字
                    binding.ivHeaderIcon.setImageResource(android.R.drawable.ic_dialog_alert) // 警示图标
                    binding.ivHeaderIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                }

                // 2. 待处理状态 (Pending)
                title.contains("PENDING", ignoreCase = true) -> {
                    binding.llHeaderBg.setBackgroundColor(Color.parseColor("#E8F5E9")) // 浅绿背景
                    binding.tvHeaderTitle.setTextColor(Color.parseColor("#2E7D32"))   // 深绿文字
                    binding.ivHeaderIcon.setImageResource(android.R.drawable.ic_input_add) // 加号图标
                    binding.ivHeaderIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                }

                // 3. 普通分类 (Category)
                else -> {
                    binding.llHeaderBg.setBackgroundColor(Color.parseColor("#E0E0E0")) // 浅灰背景
                    binding.tvHeaderTitle.setTextColor(Color.parseColor("#424242"))   // 深灰文字
                    binding.ivHeaderIcon.setImageResource(android.R.drawable.ic_menu_info_details) // 默认信息图标
                    binding.ivHeaderIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#424242"))
                }
            }
        }
    }

    inner class ItemViewHolder(private val binding: ItemInventoryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(
                item: InventoryItemFirestore,
                onItemClick: (InventoryItemFirestore) -> Unit,
                onItemLongClick: (InventoryItemFirestore) -> Unit,
                onQuantityAdd: (InventoryItemFirestore) -> Unit,
                onQuantitySubtract: (InventoryItemFirestore) -> Unit
            ) {
                binding.apply {
                    // 1. 基础文字信息
                    tvItemName.text = item.name
                    tvQuantity.text = "${item.quantity} ${item.unit}"
                    tvLocation.text = "Location: ${item.location.ifEmpty { "None" }}"
                    tvOwner.text = "Owner: ${item.ownerName}"

                    // 🟢 2. 归属颜色条 (固定在左侧，不随状态改变)
                    val currentUid = auth.currentUser?.uid

                    val indicatorColor = when {
                        item.ownerId == "PUBLIC" -> Color.parseColor("#BDBDBD") // 公共始终灰色
                        userColorMap.containsKey(item.ownerId) -> {
                            // 如果在颜色表里找到了这个人的颜色，就用它
                            Color.parseColor(userColorMap[item.ownerId])
                        }
                        item.ownerId == currentUid -> Color.parseColor("#2196F3") // 备选默认蓝
                        else -> Color.parseColor("#9C27B0") // 备选默认紫
                    }
                    viewColorIndicator.setBackgroundColor(indicatorColor)

                    // 🟢 3. 状态判定 (Pending vs Active)
                    // 在 bind 方法内部
                    binding.apply {
                        val cardRoot = root as com.google.android.material.card.MaterialCardView
                        val myId = auth.currentUser?.uid

                        // 1. 获取基础数据
                        val expiryTimestamp = item.expiryDate
                        val days = if (expiryTimestamp > 0) calculateDaysRemaining(expiryTimestamp) else Long.MAX_VALUE

                        // 🟢 关键：使用你设定的 minThreshold，如果没有设定，默认值为 0 (即不预警)
                        val threshold = item.minThreshold ?: 0
                        val isLowStock = item.quantity <= threshold && item.quantity > 0

                        // 2. 优先级判定：过期(红/黄) > 低库存(紫) > 正常(白)
                        when {
                            // --- 状态 1：Pending (绿色) ---
                            item.pendingSetup -> {
                                applyCardStyle(cardRoot, "#E8F5E9", "#2E7D32", 3)
                                tvExpiredDate.text = "📝 Missing Info (Tap to setup)"
                                tvExpiredDate.setTextColor(Color.parseColor("#2E7D32"))
                            }

                            // --- 状态 2：极其紧急/已过期 (红色) ---
                            days <= 7 -> {
                                applyCardStyle(cardRoot, "#FFEBEE", "#D32F2F", 4)
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiryTimestamp))
                                tvExpiredDate.text = "⚠️ ${if(days < 0) "Expired" else "Urgent"}: $dateStr"
                                tvExpiredDate.setTextColor(Color.parseColor("#D32F2F"))
                            }

                            // --- 状态 3：即将过期 (黄色) ---
                            days <= 30 -> {
                                applyCardStyle(cardRoot, "#FFFDE7", "#FBC02D", 3)
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiryTimestamp))
                                tvExpiredDate.text = "⏳ Expiring soon: $dateStr"
                                tvExpiredDate.setTextColor(Color.parseColor("#FBC02D"))
                            }

                            // --- 状态 4：低库存预警 (紫色) ---
                            // 💡 只有在不急着过期的情况下，才显示紫色低库存
                            isLowStock -> {
                                applyCardStyle(cardRoot, "#F3E5F5", "#7B1FA2", 3) // 极浅紫背景，深紫边框
                                tvExpiredDate.text = "📦 Low Stock: Only ${item.quantity} ${item.unit} left"
                                tvExpiredDate.setTextColor(Color.parseColor("#7B1FA2"))
                            }

                            // --- 状态 5：正常 (白色) ---
                            else -> {
                                applyCardStyle(cardRoot, "#FFFFFF", "#E0E0E0", 1)
                                tvExpiredDate.setTextColor(Color.GRAY)
                                if (expiryTimestamp > 0) {
                                    tvExpiredDate.text = "Expires: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiryTimestamp))}"
                                } else {
                                    tvExpiredDate.text = "" // 无日期不显示
                                }
                            }
                        }

                        // 统一控制 tvExpiredDate 的显示
                        tvExpiredDate.visibility = if (tvExpiredDate.text.isEmpty()) View.GONE else View.VISIBLE
                    }
                    // 4. 事件绑定
                    btnAdd.setOnClickListener { onQuantityAdd(item) }
                    btnSubtract.setOnClickListener { onQuantitySubtract(item) }
                    root.setOnClickListener { onItemClick(item) }
                    root.setOnLongClickListener {
                        onItemLongClick(item)
                        true
                    }
                }
            }
        }

        private fun calculateDaysRemaining(expiryTimestamp: Long): Long {
            val currentTime = System.currentTimeMillis()
            val diff = expiryTimestamp - currentTime
            return diff / (1000 * 60 * 60 * 24)
        }
    // 🛠️ 辅助函数：抽取重复的样式代码，让代码更整洁
    private fun applyCardStyle(card: com.google.android.material.card.MaterialCardView, bgColor: String, strokeColor: String, strokeWidth: Int) {
        card.setCardBackgroundColor(Color.parseColor(bgColor))
        card.strokeColor = Color.parseColor(strokeColor)
        card.strokeWidth = strokeWidth
    }
    }