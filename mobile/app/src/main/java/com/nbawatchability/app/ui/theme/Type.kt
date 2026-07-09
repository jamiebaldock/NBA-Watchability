package com.nbawatchability.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bold, condensed-feeling weights so team matchups read as the dominant
// element on a card, with tier badge and hook stepping down in emphasis.
val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
)
