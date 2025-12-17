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

    fun updateData(newItems: List<InventoryListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is InventoryListItem.Header -> VIEW_TYPE_HEADER
            is InventoryListItem.Item -> VIEW_TYPE_ITEM
        }
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

                // 按钮事件
                btnAdd.setOnClickListener { onQuantityAdd(item) }
                btnSubtract.setOnClickListener { onQuantitySubtract(item) }
                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }

                // 过期逻辑
                if (item.expiryDate != null && item.category.equals("FOOD", ignoreCase = true)) {
                    tvExpiredDate.visibility = View.VISIBLE
                    tvExpiredDate.text = "Expires: ${item.expiryDate}"
                    val days = calculateDaysRemaining(item.expiryDate!!)

                    val backgroundRes = when {
                        days == null -> R.drawable.bg_list_item_normal
                        days <= 7 -> R.drawable.bg_list_item_expired // 红色
                        days <= 30 -> R.drawable.bg_list_item_warning // 黄色
                        else -> R.drawable.bg_list_item_normal
                    }
                    root.setBackgroundResource(backgroundRes)
                } else {
                    tvExpiredDate.visibility = View.GONE
                    root.setBackgroundResource(R.drawable.bg_list_item_normal)
                }
            }
        }

        private fun calculateDaysRemaining(dateStr: String): Long? {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val expiryDate = format.parse(dateStr)
                expiryDate?.let {
                    val diff = it.time - System.currentTimeMillis()
                    diff / (24 * 60 * 60 * 1000)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}