package dev.jtiisto.wellnesssync.feature.capture.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellnesssync.feature.capture.domain.TachogramScale
import dev.jtiisto.wellnesssync.feature.capture.domain.model.BeatPoint

private const val WINDOW_MS = 10_000L
private const val GAP_BREAK_MS = 3_000L

/**
 * Live RR tachogram: instantaneous HR per beat as a stepped line over a
 * scrolling 10-second window with monitor-style grid lines.
 */
@Composable
fun TachogramChart(
    points: List<BeatPoint>,
    modifier: Modifier = Modifier,
) {
    // Frame-driven "now" keeps the trace scrolling smoothly between beats.
    // Reading it inside the Canvas draw lambda invalidates only the draw phase.
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nowMs = SystemClock.elapsedRealtime() }
        }
    }

    var yRange by remember { mutableStateOf(TachogramScale.DEFAULT_RANGE) }
    LaunchedEffect(points) {
        yRange = TachogramScale.computeYRange(points, yRange)
    }

    val traceColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = "Live HR (bpm) — 10 s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clipToBounds(),
            ) {
                val w = size.width
                val h = size.height
                val windowStart = nowMs - WINDOW_MS
                val range = yRange

                fun xFor(t: Long): Float = w * (t - windowStart) / WINDOW_MS.toFloat()
                fun yFor(hr: Float): Float = h * (1f - (hr - range.minBpm) / range.spanBpm)

                // Horizontal grid + bpm labels every 20 bpm
                var hr = range.minBpm
                while (hr <= range.maxBpm) {
                    val y = yFor(hr)
                    drawLine(
                        color = gridColor.copy(alpha = 0.15f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                    if (hr > range.minBpm && hr < range.maxBpm) {
                        val label = textMeasurer.measure(AnnotatedString(hr.toInt().toString()), labelStyle)
                        drawText(
                            textLayoutResult = label,
                            topLeft = Offset(w - label.size.width - 4.dp.toPx(), y - label.size.height - 2f),
                        )
                    }
                    hr += TachogramScale.STEP_BPM
                }

                // Vertical gridline on every whole second, scrolling with time
                var t = ((windowStart + 999) / 1000) * 1000
                while (t <= nowMs) {
                    val x = xFor(t)
                    drawLine(
                        color = gridColor.copy(alpha = 0.15f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f,
                    )
                    t += 1000L
                }

                // Stepped trace; gaps > GAP_BREAK_MS break the line instead of
                // drawing a false connecting segment
                if (points.isNotEmpty()) {
                    val path = Path()
                    var prev: BeatPoint? = null
                    for (p in points) {
                        val x = xFor(p.timeMs)
                        val y = yFor(p.hrBpm)
                        val pr = prev
                        if (pr == null || p.timeMs - pr.timeMs > GAP_BREAK_MS) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, yFor(pr.hrBpm))
                            path.lineTo(x, y)
                        }
                        prev = p
                    }
                    prev?.let { last ->
                        if (nowMs - last.timeMs <= GAP_BREAK_MS) {
                            path.lineTo(w, yFor(last.hrBpm))
                        }
                    }
                    drawPath(
                        path = path,
                        color = traceColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
            if (points.isEmpty()) {
                Text(
                    text = "Waiting for heartbeats…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
