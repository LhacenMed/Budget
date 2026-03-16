package com.lhacenmed.budget.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "cached_contributions")
data class BudgetContribution(
    @PrimaryKey
    val id: String = "",
    val contributor: String,
    val amount: Float,
    @SerialName("created_at")
    val createdAt: String = ""
)
