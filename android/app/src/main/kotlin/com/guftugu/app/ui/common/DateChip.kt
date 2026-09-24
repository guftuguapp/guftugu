package com.guftugu.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.guftugu.app.ui.theme.GoldEdge
import com.guftugu.app.ui.theme.GoldLight
import com.guftugu.app.ui.theme.GuftuguTheme

private val ChipShape = RoundedCornerShape(50)

/**
 * Day separator in the chat: a parchment pill with a crisp, even double gold edge (dark outer line,
 * light inner line — the same all the way round), flanked by fine gold rules ending in diamonds.
 */
@Composable
fun DateChip(label: String, modifier: Modifier = Modifier) {
    val craft = GuftuguTheme.craft
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rule(leftSide = true)
        Box(
            Modifier
                .clip(ChipShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.25.dp, GoldEdge, ChipShape)
                .padding(2.dp)
                .border(0.75.dp, if (craft.isDark) GoldEdge.copy(alpha = 0.5f) else GoldLight, ChipShape)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Rule(leftSide = false)
    }
}

/** A short gold rule with a diamond at its outer end. */
@Composable
private fun Rule(leftSide: Boolean) {
    Canvas(Modifier.width(56.dp).height(10.dp)) {
        val y = size.height / 2
        val d = 3.dp.toPx()
        val gap = 6.dp.toPx()
        val (start, end) = if (leftSide) d * 2 to size.width - gap else gap to size.width - d * 2
        drawLine(GoldEdge, Offset(start, y), Offset(end, y), strokeWidth = 1.2.dp.toPx())
        val cx = if (leftSide) d else size.width - d
        val diamond = Path().apply { moveTo(cx, y - d); lineTo(cx + d, y); lineTo(cx, y + d); lineTo(cx - d, y); close() }
        drawPath(diamond, GoldEdge)
    }
}

@Preview(showBackground = true)
@Composable
private fun DateChipPreview() {
    GuftuguTheme { DateChip("Today") }
}
