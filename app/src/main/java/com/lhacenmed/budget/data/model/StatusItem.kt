package com.lhacenmed.budget.data.model

import android.net.Uri

enum class StatusSource { WHATSAPP, WHATSAPP_BUSINESS }

data class StatusItem(
    val uri:     Uri,
    val name:    String,
    val isVideo: Boolean,
    val source:  StatusSource
)
