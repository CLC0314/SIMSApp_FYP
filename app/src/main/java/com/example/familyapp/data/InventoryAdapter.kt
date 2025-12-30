package com.example.familyapp.adapter

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
import java.util.*

class InventoryAdapter(
    private val context: Context,
    private var items: MutableList<InventoryListItem>,
    private val onItemClick: (InventoryItemFirestore) -> Unit,
    private val onItemLongClick: (InventoryItemFirestore) -> Unit,
    private val onQuantityAdd: (InventoryItemFirestore) -> Unit,
    private val onQuantitySubtract: (InventoryItemFirestore) -> Unit
    // 🔴 移除了构造函数里的 allItemsFull，改写在类体内
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_HEADER = 0
    private val VIEW_TYPE_ITEM = 1

    // 🟢 1. 正确的位置：全量数据备份
    private var allItemsFull: List<InventoryListItem> = mutableListOf()

    // 🟢 2. 正确的位置：更新数据
    fun updateData(newItems: List<InventoryListItem>) {
        allItemsFull = ArrayList(newItems)
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
    fun filter(query: String, filterType: String, currentUid: String?) {
        val lowerCaseQuery = query.lowercase(Locale.getDefault())

        val filteredList = allItemsFull.filter { listItem ->
            if (listItem is InventoryListItem.Item) {
                val item = listItem.item

                // 1. 文字匹配
                val matchesQuery = item.name.lowercase().contains(lowerCaseQuery) ||
                        item.category.lowercase().contains(lowerCaseQuery)

                // 2. 状态匹配 (新增 Personal 逻辑)
                val matchesFilter = when (filterType) {
                    "PERSONAL" -> item.ownerId == currentUid  // 只看号主的
                    "PUBLIC" -> item.ownerId == "PUBLIC"     // 只看公共的
                    "EXPIRY" -> (item.expiryDate ?: 0L) > 0  // 只看有日期的
                    else -> true                             // "ALL"
                }

                matchesQuery && matchesFilter
            } else {
                false // 过滤时不带原有的 Header，稍后重新生成
            }
        }

        items.clear()
        items.addAll(filteredList)
        notifyDataSetChanged()
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
            binding.tvHeaderTitle.text = header.categoryName
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
                tvItemName.text = item.name
                tvQuantity.text = "${item.quantity} ${item.unit}"
                tvLocation.text = "Location: ${item.location ?: "None"}"
                tvOwner.text = "Owner: ${item.ownerName ?: "Public"}"

                btnAdd.setOnClickListener { onQuantityAdd(item) }
                btnSubtract.setOnClickListener { onQuantitySubtract(item) }
                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }

                val expiryTimestamp = item.expiryDate ?: 0L
                if (expiryTimestamp > 0) {
                    tvExpiredDate.visibility = View.VISIBLE
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    tvExpiredDate.text = "Expires: ${sdf.format(Date(expiryTimestamp))}"

                    val days = calculateDaysRemaining(expiryTimestamp)
                    val backgroundRes = when {
                        days < 0 -> R.drawable.bg_list_item_expired
                        days <= 7 -> R.drawable.bg_list_item_expired
                        days <= 30 -> R.drawable.bg_list_item_warning
                        else -> R.drawable.bg_list_item_normal
                    }
                    root.setBackgroundResource(backgroundRes)
                } else {
                    tvExpiredDate.visibility = View.GONE
                    root.setBackgroundResource(R.drawable.bg_list_item_normal)
                }
            }
        }

        private fun calculateDaysRemaining(expiryTimestamp: Long): Long {
            val currentTime = System.currentTimeMillis()
            val diff = expiryTimestamp - currentTime
            return diff / (1000 * 60 * 60 * 24)
        }
    }
}