// com.example.familyapp/InventoryListUtils.kt

package com.example.familyapp

/**
 * 密封类：表示库存列表中的两种项类型：分组头部或实际物品。
 */
sealed class InventoryListItem {
    data class Header(val categoryName: String) : InventoryListItem()
    // 🆕 将 InventoryItem 替换为 InventoryItemFirestore
    data class Item(val item: InventoryItemFirestore) : InventoryListItem()
}

/**
 * 辅助函数：将排序后的物品列表转换为带分组头部的列表。
 */
fun createGroupedListFirestore(items: List<InventoryItemFirestore>): List<InventoryListItem> {
    if (items.isEmpty()) return emptyList()

    // 1. 确保列表已按类别排序
    val sortedItems = items.sortedBy { it.category }

    val groupedList = mutableListOf<InventoryListItem>()
    var currentCategory: String? = null

    sortedItems.forEach { item ->
        if (item.category != currentCategory) {
            // 添加新的头部
            groupedList.add(InventoryListItem.Header(item.category))
            currentCategory = item.category
        }
        // 添加物品项
        groupedList.add(InventoryListItem.Item(item))
    }

    return groupedList
}