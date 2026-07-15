package com.opengym.core.domain.model

data class Tag(
    val id: Long = 0,
    val name: String,
    val color: String? = null,
    val isSystem: Boolean = false,
)
