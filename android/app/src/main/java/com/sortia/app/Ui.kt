package com.sortia.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object P {
    val Bg = Color(0xFF0A1024)
    val Card = Color(0xFF141B33)
    val Card2 = Color(0xFF1D2650)
    val Teal = Color(0xFF2CE5B7)
    val Blue = Color(0xFF4C8DFF)
    val Violet = Color(0xFF8A6BFF)
    val Pink = Color(0xFFFF6FA5)
    val Amber = Color(0xFFFFC857)
    val Green = Color(0xFF7BE06A)
    val Text = Color(0xFFF1F4FF)
    val Muted = Color(0xFF9AA5CE)
    val Err = Color(0xFFFF6B8A)
    val Ink = Color(0xFF06112A)
    val Grad: Brush = Brush.horizontalGradient(listOf(Teal, Blue, Violet))
}

fun Cat.color(): Color = when (this) {
    Cat.IMAGES -> P.Pink
    Cat.VIDEOS -> P.Violet
    Cat.AUDIO -> P.Amber
    Cat.DOCUMENTS -> P.Blue
    Cat.ARCHIVES -> P.Teal
    Cat.APPS -> P.Green
    Cat.OTHER -> Color(0xFF8C97BD)
}

@Composable
fun SortiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = P.Teal,
            onPrimary = P.Ink,
            background = P.Bg,
            surface = P.Card,
            onSurface = P.Text,
            onBackground = P.Text,
        ),
        content = content,
    )
}

@Composable
fun SCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(P.Card, RoundedCornerShape(22.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fill: Brush = if (enabled) P.Grad else SolidColor(Color(0xFF2A3256))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) P.Ink else P.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(P.Card2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) P.Teal else P.Card2)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (selected) P.Ink else P.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = P.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = P.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = P.Teal, checkedThumbColor = P.Ink),
        )
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
fun Bar(fraction: Float, color: Color = P.Teal, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).background(Color(0xFF232C57), CircleShape)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, color = P.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)
}

@Composable
fun CatRow(cat: Cat, count: Int, bytes: Long, fraction: Float) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(cat.color())
            Spacer(Modifier.width(10.dp))
            Text(cat.label, color = P.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("$count  |  ${formatBytes(bytes)}", color = P.Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Bar(fraction, cat.color())
    }
}
