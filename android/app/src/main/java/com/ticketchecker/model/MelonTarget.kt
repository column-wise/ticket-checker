package com.ticketchecker.model

data class MelonTarget(
    val prodId: String,
    val scheduleNo: String,
    val seatId: String,
    val volume: String = "1",
    val selectedGradeVolume: String = "1",
    val cookies: Map<String, String>,
    val name: String = ""
)
