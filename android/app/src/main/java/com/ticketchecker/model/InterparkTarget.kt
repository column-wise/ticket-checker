package com.ticketchecker.model

data class InterparkTarget(
    val goodsCode: String,
    val placeCode: String,
    val bizCode: String,
    val playSeq: String,
    val sessionId: String,
    val cookies: Map<String, String>,
    val goodsName: String = "",
    val playDate: String = "",
    val watchGrades: List<String> = emptyList()
)
