// com.example.familyapp.adapter/InventoryAdapter.kt

package com.example.familyapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.InventoryListItem
// 🆕 确保导入新的 Firestore 兼容数据模型
import com.example.familyapp.InventoryItemFirestore
import com.example.familyapp.R
import com.example.familyapp.databinding.ItemInventoryBinding
import com.example.familyapp.databinding.ItemInventoryHeaderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// 适配器现在接收 List<InventoryListItem>，并使用 InventoryItemFirestore
class InventoryAdapter(
    private val items: List<InventoryListItem>,
    // 🆕 回调函数的参数类型必须是 InventoryItemFirestore
    private val onItemClick: (InventoryItemFirestore) -> Unit = {},
    private val onItemLongClick: (InventoryItemFirestore) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // 定义视图类型常量
    private val VIEW_TYPE_HEADER = 0
    private val VIEW_TYPE_ITEM = 1

    // =========================================================================
    // 视图类型和大小
    // =========================================================================

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is InventoryListItem.Header -> VIEW_TYPE_HEADER
            is InventoryListItem.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun getItemCount(): Int = items.size

    // =========================================================================
    // ViewHolder 创建
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
            // 确保穷举
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
    }

    // =========================================================================
    // ViewHolder 绑定
    // =========================================================================

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is InventoryListItem.Header -> (holder as HeaderViewHolder).bind(item)
            // 🆕 注意：这里 item.item 是 InventoryItemFirestore 类型
            is InventoryListItem.Item -> (holder as ItemViewHolder).bind(item.item, onItemClick, onItemLongClick)
            // 确保穷举
            else -> throw IllegalArgumentException("Unknown item type in adapter: $item")
        }
    }

    // =========================================================================
    // ViewHolder 实现
    // =========================================================================

    // 头部视图 (类别名称)
    inner class HeaderViewHolder(private val binding: ItemInventoryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(header: InventoryListItem.Header) {
            binding.tvHeaderTitle.text = header.categoryName
        }
    }

    // 物品项视图
    inner class ItemViewHolder(private val binding: ItemInventoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: InventoryItemFirestore, // ⬅️ 关键修改：绑定新的 Firestore 数据模型
            onItemClick: (InventoryItemFirestore) -> Unit,
            onItemLongClick: (InventoryItemFirestore) -> Unit
        ) {
            binding.apply {
                tvItemName.text = item.name
                tvQuantity.text = item.quantity.toString()
                tvLocation.text = item.location ?: "无位置"
                tvOwner.text = item.ownerName ?: "公共物品"

                // --- 过期日期和颜色标记逻辑 ---
                if (item.expiredDate != null && item.category == "食品") {
                    tvExpiredDate.visibility = View.VISIBLE
                    tvExpiredDate.text = "过期日: ${item.expiredDate}"
                    val daysRemaining = calculateDaysRemaining(item.expiredDate!!)

                    // 设置背景颜色
                    when {
                        daysRemaining == null -> { /* 忽略 */ }
                        daysRemaining <= 7 -> { // 7天内过期或已过期
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

                // 绑定点击事件 (传递 InventoryItemFirestore 对象)
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
                val expiryDate = format.parse(dateStr)
                val currentDate = Date()

                if (expiryDate != null) {
                    val diff = expiryDate.time - currentDate.time
                    // 返回距离今天的天数，向下取整
                    (diff / (24 * 60 * 60 * 1000)).toLong()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}