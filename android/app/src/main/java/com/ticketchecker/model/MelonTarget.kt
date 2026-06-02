package com.ticketchecker.model

data class MelonTarget(
    val prodId: String,
    val scheduleNo: String,
    val pocCode: String = "",
    val seatId: String = "",
    val seatGradeName: String = "",
    val playDate: String = "",
    val volume: String = "1",
    val selectedGradeVolume: String = "1",
    val cookies: Map<String, String>,
    val name: String = ""
)
