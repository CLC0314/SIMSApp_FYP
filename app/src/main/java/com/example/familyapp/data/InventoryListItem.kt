package com.example.familyapp.data

// 密封类：定义列表中的两种类型
sealed class InventoryListItem {
    data class Header(val title: String) : InventoryListItem() // 🟢 统一改为 title
    data class Item(val item: InventoryItemFirestore) : InventoryListItem()
}