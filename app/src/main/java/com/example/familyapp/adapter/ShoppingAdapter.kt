package com.example.familyapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.data.ShoppingItem
import com.example.familyapp.databinding.ItemShoppingBinding
import com.google.firebase.firestore.FirebaseFirestore

class ShoppingAdapter(
    private var items: List<ShoppingItem>,
    private val db: FirebaseFirestore
) : RecyclerView.Adapter<ShoppingAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemShoppingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShoppingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            tvName.text = item.name
            tvDetail.text = "${item.quantity} ${item.unit}"
            cbBought.isChecked = item.isChecked

            // 点击 Checkbox 实时更新 Firestore
            cbBought.setOnCheckedChangeListener { _, isChecked ->
                if (item.id.isNotEmpty()) {
                    db.collection("shopping_lists").document(item.id)
                        .update("isChecked", isChecked)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<ShoppingItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}