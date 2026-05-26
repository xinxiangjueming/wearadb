package com.wearadb.data.model

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val permissions: String = "",
    val lastModified: String = ""
)
