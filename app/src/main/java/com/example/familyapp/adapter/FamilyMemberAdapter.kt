// com.example.familyapp.adapter/FamilyMemberAdapter.kt

package com.example.familyapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.familyapp.data.FamilyMemberFirestore // 🆕 导入新的数据模型
import com.example.familyapp.databinding.ItemFamilyMemberBinding

class FamilyMemberAdapter(
    // 🆕 适配器现在接收 FamilyMemberFirestore 列表
    private val members: List<FamilyMemberFirestore>,
    private val onItemClick: (FamilyMemberFirestore) -> Unit
) : RecyclerView.Adapter<FamilyMemberAdapter.MemberViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemFamilyMemberBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MemberViewHolder(binding)
    }

    override fun getItemCount(): Int = members.size

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(members[position])
    }

    inner class MemberViewHolder(private val binding: ItemFamilyMemberBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // 🆕 绑定方法使用 FamilyMemberFirestore
        fun bind(member: FamilyMemberFirestore) {
            binding.tvMemberName.text = member.name
            binding.tvMemberRole.text = member.role.uppercase()

            binding.root.setOnClickListener {
                onItemClick(member)
            }
        }
    }
}