package com.hexo141.liquidglass.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.liquidglass.vivo.VivoPayload
import com.hexo141.liquidglass.vivo.VivoStage
import com.hexo141.liquidglass.vivo.VivoTarget
import com.hexo141.liquidglass.vivo.VivoUiState
import com.hexo141.liquidglass.vivo.VivoWiredController
import com.hexo141.liquidglass.vivo.progressFraction
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/**
 * USB-OTG host page for a second vivo/iQOO phone.
 *
 * The [VivoWiredController] is owned by the activity, so switching to the log tab does not stop a
 * running flow; the page only renders the controller state.
 */
@Composable
fun VivoWiredScreen(
    backdrop: Backdrop,
    controllerProvider: () -> VivoWiredController,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarSpace + 16.dp)
        ) {
            VivoHeader()
            Spacer(Modifier.height(16.dp))
            BasicText(
                text = "The USB host transport requires Android 8.0 or newer.",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    shadow = vivoShadow,
                )
            )
        }
        return
    }

    val controller = remember { controllerProvider() }
    val surfaceColor =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Color.Black.copy(alpha = 0.3f)
        else Color.Unspecified

    var pickerVisible by remember { mutableStateOf(false) }
    val pageScroll = rememberScrollState()

    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            controller.selectLocal(uri, controller.localPayloadName(uri))
        }
    }

    LaunchedEffect(controller) {
        controller.connect()
    }

    val state = controller.state
    val progress = state.progressFraction()

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(pageScroll)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarSpace + 16.dp)
        ) {
            VivoHeader()
            Spacer(Modifier.height(14.dp))
            TargetCard(
                backdrop = backdrop,
                state = state,
                surfaceColor = surfaceColor,
                onConnect = { controller.connect(force = state.connected) },
            )
            Spacer(Modifier.height(10.dp))
            PayloadCard(
                backdrop = backdrop,
                state = state,
                surfaceColor = surfaceColor,
                onPickBundled = { pickerVisible = true },
                onPickLocal = { pickLocal.launch(arrayOf("*/*")) },
            )
            Spacer(Modifier.height(14.dp))
            LiquidGlassButton(
                onClick = { if (state.running) controller.stop() else controller.start() },
                backdrop = backdrop,
                tint = if (state.running) Color.Unspecified else VivoBlue,
                surfaceColor = surfaceColor,
                height = 50.dp,
                horizontalPadding = 22.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    text = if (state.running) "Stop" else "Start",
                    style = vivoButtonText,
                )
            }
            if (state.running || state.stage == VivoStage.Done) {
                Spacer(Modifier.height(10.dp))
                VivoProgressBar(progress)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = state.status,
                style = TextStyle(
                    color = when {
                        state.success -> VivoGreen
                        state.stage == VivoStage.Failed -> VivoRed
                        else -> Color.White.copy(alpha = 0.78f)
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    shadow = vivoShadow,
                )
            )
        }

        if (pickerVisible) {
            PayloadPicker(
                backdrop = backdrop,
                payloads = state.payloads,
                target = state.target,
                selected = state.selectedPayload,
                surfaceColor = surfaceColor,
                onSelect = {
                    controller.selectPayload(it)
                    pickerVisible = false
                },
                onDismiss = { pickerVisible = false },
            )
        }
    }
}

@Composable
private fun VivoHeader() {
    BasicText(
        text = "vivo / iQOO",
        style = TextStyle(
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = vivoShadow,
        )
    )
}

@Composable
private fun TargetCard(
    backdrop: Backdrop,
    state: VivoUiState,
    surfaceColor: Color,
    onConnect: () -> Unit,
) {
    VivoGlassCard(backdrop, surfaceColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Target")
            Spacer(Modifier.weight(1f))
            PillButton(
                text = if (state.connected) "Reconnect" else "Connect",
                enabled = !state.connecting && !state.running,
                onClick = onConnect,
            )
        }
        Spacer(Modifier.height(8.dp))
        val target = state.target
        if (target != null) {
            InfoLine("Model", target.identity)
            InfoLine("System", target.system)
            InfoLine("Kernel", target.kernel)
        }
    }
}

@Composable
private fun PayloadCard(
    backdrop: Backdrop,
    state: VivoUiState,
    surfaceColor: Color,
    onPickBundled: () -> Unit,
    onPickLocal: () -> Unit,
) {
    val payload = state.selectedPayload
    val localName = state.localName
    val target = state.target
    VivoGlassCard(backdrop, surfaceColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Payload")
            Spacer(Modifier.weight(1f))
            if (payload != null) {
                when {
                    target == null -> Unit
                    payload.matches(target) -> StatusTag("match", VivoGreen)
                    else -> StatusTag("mismatch", VivoAmber)
                }
            } else if (localName != null) {
                StatusTag("local", VivoBlue)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            payload != null -> {
                BasicText(
                    text = payload.displayName.ifBlank { payload.library },
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        shadow = vivoShadow,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                BasicText(
                    text = buildPayloadDetail(payload),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        shadow = vivoShadow,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            localName != null -> {
                BasicText(
                    text = localName,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        shadow = vivoShadow,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            else -> {
                BasicText(
                    text = "Not selected",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.5.sp,
                        shadow = vivoShadow,
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            PillButton(
                text = "Bundled",
                enabled = !state.running,
                onClick = onPickBundled,
            )
            Spacer(Modifier.width(10.dp))
            PillButton(
                text = "Local",
                enabled = !state.running,
                onClick = onPickLocal,
            )
        }
    }
}

private fun buildPayloadDetail(payload: VivoPayload): String = buildString {
    payload.kernelVersion?.let { append("kernel ").append(it) }
    if (payload.kernelUnknown) {
        if (isNotEmpty()) append(" · ")
        append("kernel unknown")
    }
    if (payload.buildId.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(payload.buildId)
    }
    if (isNotEmpty()) append(" · ")
    append(payload.library)
}

@Composable
private fun VivoProgressBar(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(VivoBlue)
        )
    }
}

@Composable
private fun PayloadPicker(
    backdrop: Backdrop,
    payloads: List<VivoPayload>,
    target: VivoTarget?,
    selected: VivoPayload?,
    surfaceColor: Color,
    onSelect: (VivoPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(payloads, target) {
        payloads.sortedWith(
            compareByDescending<VivoPayload> { target != null && it.matches(target) }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.kernelVersion.orEmpty() }
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = BottomBarSpace)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardHeight = minOf(560.dp, maxHeight - 16.dp)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = cardHeight)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(24.dp) },
                        effects = {
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(10f.dp.toPx(), 22f.dp.toPx())
                        },
                        onDrawSurface = {
                            if (surfaceColor.isSpecified) drawRect(surfaceColor)
                            drawRect(Color.Black.copy(alpha = 0.84f))
                            drawRect(Color.White.copy(alpha = 0.05f))
                        }
                    )
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                BasicText(
                    text = "Select payload",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = vivoShadow,
                    )
                )
                if (target != null) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        text = "${target.identity} · ${target.kernel}",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                            shadow = vivoShadow,
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = (cardHeight - 150.dp).coerceAtLeast(120.dp))
                        .verticalScroll(rememberScrollState())
                ) {
                    sorted.forEach { payload ->
                        val matches = target != null && payload.matches(target)
                        val isSelected = payload == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) VivoBlue.copy(alpha = 0.22f) else Color.Transparent
                                )
                                .clickable { onSelect(payload) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    text = payload.displayName.ifBlank { payload.library },
                                    style = TextStyle(
                                        color = Color.White.copy(alpha = 0.95f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        shadow = vivoShadow,
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                BasicText(
                                    text = buildPayloadDetail(payload),
                                    style = TextStyle(
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 9.5.sp,
                                        lineHeight = 13.sp,
                                        shadow = vivoShadow,
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (matches) {
                                    Spacer(Modifier.height(2.dp))
                                    BasicText(
                                        text = "match",
                                        style = TextStyle(
                                            color = VivoGreen,
                                            fontSize = 9.5.sp,
                                            shadow = vivoShadow,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PillButton(text = "Close", enabled = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun VivoGlassCard(
    backdrop: Backdrop,
    surfaceColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(24.dp) },
                effects = {
                    vibrancy()
                    blur(4f.dp.toPx())
                    lens(10f.dp.toPx(), 22f.dp.toPx())
                },
                onDrawSurface = {
                    if (surfaceColor.isSpecified) drawRect(surfaceColor)
                    drawRect(Color.Black.copy(alpha = 0.14f))
                    drawRect(Color.White.copy(alpha = 0.06f))
                }
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = vivoShadow,
        )
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.5.sp,
                shadow = vivoShadow,
            ),
            modifier = Modifier.width(54.dp),
        )
        BasicText(
            text = value,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                shadow = vivoShadow,
            ),
        )
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = color,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                shadow = vivoShadow,
            )
        )
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .border(
                1.dp,
                Color.White.copy(alpha = if (enabled) 0.2f else 0.08f),
                RoundedCornerShape(percent = 50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                shadow = vivoShadow,
            )
        )
    }
}


private val vivoButtonText = TextStyle(
    color = Color.White,
    fontSize = 15.sp,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )
)

private val vivoShadow = Shadow(
    color = Color.Black.copy(alpha = 0.45f),
    offset = Offset(0f, 1f),
    blurRadius = 6f,
)

private val VivoBlue = Color(0xFF0A84FF)

private val VivoGreen = Color(0xFF4ADE80)

private val VivoAmber = Color(0xFFFBBF24)

private val VivoRed = Color(0xFFF87171)
