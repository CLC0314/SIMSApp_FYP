package com.example.familyapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.data.ShoppingItem
import com.example.familyapp.databinding.ItemShoppingBinding
import java.text.SimpleDateFormat
import java.util.*

class ShoppingAdapter(
    private var items: MutableList<ShoppingItem>,
    private val onCheckedChange: (String, Boolean) -> Unit,
    private val onQuantityChange: (String, Int) -> Unit,
    private val onDateClick: (ShoppingItem) -> Unit,
    private val onCategoryClick: (ShoppingItem) -> Unit
) : RecyclerView.Adapter<ShoppingAdapter.ShoppingViewHolder>() {

    fun updateData(newItems: List<ShoppingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getCheckedItems(): List<ShoppingItem> = items.filter { it.isChecked }
    fun getItemAt(position: Int): ShoppingItem = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShoppingViewHolder {
        val binding = ItemShoppingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ShoppingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShoppingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ShoppingViewHolder(private val binding: ItemShoppingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShoppingItem) {
            binding.apply {
                // 🟢 匹配你的 XML ID: tvName
                tvName.text = item.name

                // 🟢 匹配你的 XML ID: tvDetail (显示 数量 + 单位)
                tvDetail.text = "${item.quantity} ${item.unit}"

                // 🟢 匹配你的 XML ID: tvCategoryTag
                tvCategoryTag.text = item.category

                // 🟢 匹配你的 XML ID: cbBought
                // 注意：先移除监听再设置值，防止循环触发
                cbBought.setOnCheckedChangeListener(null)
                cbBought.isChecked = item.isChecked
                cbBought.setOnCheckedChangeListener { _, isChecked ->
                    onCheckedChange(item.id, isChecked)
                }

                // 🟢 匹配新增的 btnPlus / btnMinus
                btnPlus.setOnClickListener { onQuantityChange(item.id, item.quantity + 1) }
                btnMinus.setOnClickListener {
                    if (item.quantity > 1) onQuantityChange(item.id, item.quantity - 1)
                }

                // 🟢 匹配你的 XML ID: btnSetExpiry

                tvCategoryTag.setOnClickListener { onCategoryClick(item) }
            }
        }
    }
}