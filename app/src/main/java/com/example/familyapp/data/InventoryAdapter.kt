package com.example.familyapp.data

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.InventoryListItem
import com.example.familyapp.R
import com.example.familyapp.databinding.ItemInventoryBinding
import com.example.familyapp.databinding.ItemInventoryHeaderBinding
import com.example.familyapp.data.InventoryItemFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 修复 1: 适配器必须使用可变列表 (MutableList)
class InventoryAdapter(
    private val context: Context,
    private var items: MutableList<InventoryListItem>, // 修复：使用 MutableList
    private val onItemClick: (InventoryItemFirestore) -> Unit = {},
    private val onItemLongClick: (InventoryItemFirestore) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_HEADER = 0
    private val VIEW_TYPE_ITEM = 1

    // =========================================================================
    // 核心修复：更新数据方法 (移动到类级别)
    // =========================================================================

    /**
     * 接收新的列表数据并更新适配器内容。
     * @param newItems 包含 Header 和 Item 的混合列表
     */
    fun updateData(newItems: List<InventoryListItem>) {
        // 使用 DiffUtil 会更高效，但简单起见，我们直接清除并添加
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    // =========================================================================
    // 视图类型和大小 (保持不变)
    // =========================================================================

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is InventoryListItem.Header -> VIEW_TYPE_HEADER
            is InventoryListItem.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun getItemCount(): Int = items.size

    // =========================================================================
    // ViewHolder 创建 (保持不变)
    // =========================================================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemInventoryHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            VIEW_TYPE_ITEM -> {
                val binding = ItemInventoryBinding.inflate(inflater, parent, false)
                ItemViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
    }

    // =========================================================================
    // ViewHolder 绑定 (保持不变)
    // =========================================================================

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is InventoryListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is InventoryListItem.Item -> (holder as ItemViewHolder).bind(item.item, onItemClick, onItemLongClick)
        }
    }

    // =========================================================================
    // ViewHolder 实现 (ItemViewHolder 中删除错误的 updateData)
    // =========================================================================

    inner class HeaderViewHolder(private val binding: ItemInventoryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(header: InventoryListItem.Header) {
            binding.tvHeaderTitle.text = header.categoryName
        }
    }

    inner class ItemViewHolder(private val binding: ItemInventoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: InventoryItemFirestore,
            onItemClick: (InventoryItemFirestore) -> Unit,
            onItemLongClick: (InventoryItemFirestore) -> Unit
        ) {
            binding.apply {
                tvItemName.text = item.name
                // 修复 2: 显示单位，例如 "5 件"
                tvQuantity.text = "${item.quantity} ${item.unit}"

                tvLocation.text = "Location: ${item.location ?: "None"}"
                tvOwner.text = "Owner: ${item.ownerName ?: "Public"}"

                // --- 过期日期和颜色标记逻辑 ---
                if (item.expiryDate != null && item.category.uppercase() == "FOOD") { // 使用大写比较更安全
                    tvExpiredDate.visibility = View.VISIBLE
                    tvExpiredDate.text = "Expires: ${item.expiryDate}"
                    val daysRemaining = calculateDaysRemaining(item.expiryDate!!)

                    // 设置背景颜色
                    when {
                        daysRemaining == null -> { /* 忽略 */ }
                        daysRemaining <= 0 -> { // 已过期
                            root.setBackgroundResource(R.drawable.bg_list_item_expired)
                        }
                        daysRemaining <= 7 -> { // 7天内过期
                            root.setBackgroundResource(R.drawable.bg_list_item_expired)
                        }
                        daysRemaining <= 30 -> { // 30天内过期
                            root.setBackgroundResource(R.drawable.bg_list_item_warning)
                        }
                        else -> {
                            root.setBackgroundResource(R.drawable.bg_list_item_normal)
                        }
                    }

                } else {
                    tvExpiredDate.visibility = View.GONE
                    root.setBackgroundResource(R.drawable.bg_list_item_normal)
                }

                // 绑定点击事件
                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
            }
        }

        // 辅助函数：计算剩余天数
        private fun calculateDaysRemaining(dateStr: String): Long? {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                // 注意：这里需要设置日期解析为非严格模式，或使用 Calendar 来精确处理
                val expiryDate = format.parse(dateStr)
                val currentDate = Date()

                if (expiryDate != null) {
                    val diff = expiryDate.time - currentDate.time
                    // 返回距离今天的天数
                    (diff / (24 * 60 * 60 * 1000)).toLong()
                } else {
                    null
                }
            } catch (e: Exception) {
                // Log the exception if needed
                null
            }
        }
    }
}