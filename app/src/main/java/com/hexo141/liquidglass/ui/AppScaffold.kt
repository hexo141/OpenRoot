package com.hexo141.liquidglass.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.liquidglass.R
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Vertical space the floating bar needs, so page content never hides underneath it. */
val BottomBarSpace = 88.dp

/**
 * Root of the main experience: one wallpaper, one backdrop and the floating glass tab bar.
 *
 * The wallpaper is drawn through `layerBackdrop`, so every glass surface inside the three pages
 * refracts the same image, and the bar refracts the page content that sits over it.
 */
@Composable
fun AppScaffold(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    home: @Composable (Backdrop) -> Unit,
    log: @Composable (Backdrop) -> Unit,
    about: @Composable (Backdrop) -> Unit,
    vivo: @Composable (Backdrop) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberLayerBackdrop()

    // Reload the image when the configuration changes, or landscape keeps the portrait wallpaper.
    val configuration = LocalConfiguration.current

    // A short fade makes the page swap read as one surface instead of a cut.
    val contentAlpha = remember { Animatable(1f) }
    LaunchedEffect(selectedTab) {
        contentAlpha.snapTo(0.55f)
        contentAlpha.animateTo(1f, tween(180))
    }

    Box(modifier.fillMaxSize()) {
        key(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
            Image(
                painter = painterResource(R.drawable.bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha.value }
        ) {
            when {
                selectedTab == 0 -> home(backdrop)
                selectedTab == 1 -> vivo(backdrop)
                selectedTab == 2 -> log(backdrop)
                else -> about(backdrop)
            }
        }

        GlassBottomBar(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = 4,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        ) {
            GlassBottomTab({ onTabSelected(0) }) {
                TabIcon(TabIconKind.Home)
                TabLabel(R.string.tab_home)
            }
            GlassBottomTab({ onTabSelected(1) }) {
                TabIcon(TabIconKind.Vivo)
                TabLabel(R.string.tab_vivo)
            }
            GlassBottomTab({ onTabSelected(2) }) {
                TabIcon(TabIconKind.Log)
                TabLabel(R.string.tab_log)
            }
            GlassBottomTab({ onTabSelected(3) }) {
                TabIcon(TabIconKind.About)
                TabLabel(R.string.tab_about)
            }
        }
    }
}

@Composable
private fun TabLabel(text: Int) {
    BasicText(
        text = stringResource(text),
        style = TextStyle(
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

private enum class TabIconKind { Home, Log, About, Vivo }

/** Small line icons, drawn here so the app needs no icon dependency. */
@Composable
private fun TabIcon(kind: TabIconKind) {
    val color = Color.White.copy(alpha = 0.9f)
    Canvas(Modifier.size(22.dp)) {
        withTransform({ scale(size.minDimension / 24f, size.minDimension / 24f, Offset.Zero) }) {
            val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (kind) {
                TabIconKind.Home -> {
                    val house = Path().apply {
                        moveTo(4f, 11f)
                        lineTo(12f, 4f)
                        lineTo(20f, 11f)
                        lineTo(20f, 20f)
                        lineTo(14.5f, 20f)
                        lineTo(14.5f, 14.5f)
                        lineTo(9.5f, 14.5f)
                        lineTo(9.5f, 20f)
                        lineTo(4f, 20f)
                        close()
                    }
                    drawPath(house, color = color, style = stroke)
                }
                TabIconKind.Log -> {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(5f, 3.5f),
                        size = Size(14f, 17f),
                        cornerRadius = CornerRadius(3.5f),
                        style = stroke
                    )
                    listOf(9f, 12.5f, 16f).forEach { y ->
                        drawLine(
                            color = color,
                            start = Offset(8.5f, y),
                            end = Offset(15.5f, y),
                            strokeWidth = 1.9f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                TabIconKind.About -> {
                    drawCircle(color = color, radius = 8.4f, center = Offset(12f, 12f), style = stroke)
                    drawCircle(color = color, radius = 1.15f, center = Offset(12f, 7.9f))
                    drawLine(
                        color = color,
                        start = Offset(12f, 11.2f),
                        end = Offset(12f, 16.4f),
                        strokeWidth = 1.9f,
                        cap = StrokeCap.Round
                    )
                }
                TabIconKind.Vivo -> {
                    // USB trident: the OTG cable that reaches the second phone.
                    drawLine(
                        color = color,
                        start = Offset(12f, 20f),
                        end = Offset(12f, 4.5f),
                        strokeWidth = 1.9f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = color,
                        start = Offset(12f, 11f),
                        end = Offset(6.5f, 7.5f),
                        strokeWidth = 1.9f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = color,
                        start = Offset(12f, 15f),
                        end = Offset(17.5f, 11.5f),
                        strokeWidth = 1.9f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = color, radius = 1.7f, center = Offset(12f, 3.8f))
                    drawCircle(color = color, radius = 1.4f, center = Offset(5.6f, 6.9f))
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(16.1f, 9.7f),
                        size = Size(2.8f, 2.8f),
                        cornerRadius = CornerRadius(0.5f)
                    )
                }
            }
        }
    }
}
