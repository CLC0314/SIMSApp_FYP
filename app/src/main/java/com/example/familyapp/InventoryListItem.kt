// com.example.familyapp/InventoryListItem.kt

package com.example.familyapp

import com.example.familyapp.data.InventoryItemFirestore

// 密封类：定义列表中的两种类型
sealed class InventoryListItem {
    data class Header(val categoryName: String) : InventoryListItem()
    data class Item(val item: InventoryItemFirestore) : InventoryListItem()
}