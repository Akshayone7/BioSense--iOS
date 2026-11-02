package com.biosense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    sub: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp)
    ) {
        Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}
