package com.lhacenmed.budget.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@Entity(tableName = "cached_spending_items")
data class SpendingItem(
    @PrimaryKey
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String = "",
    val date: String,
    val shopper: String,
    val name: String,
    val quantity: String? = null,
    val price: Float,
    val description: String? = null,
    @SerialName("created_at")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val createdAt: String = ""
)
