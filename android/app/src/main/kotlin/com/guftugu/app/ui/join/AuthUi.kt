package com.guftugu.app.ui.join

import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.auth.userMessage
import com.guftugu.app.di.AppGraph
import com.guftugu.app.ui.theme.Gold
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GoldLight
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.LogoMedallion

/*
 * Small helpers shared by the Join / Enroll / Unlock / Devices / Link screens.
 * Everything draws in a single pass (no blur, no shadows > 2dp) per DESIGN.md.
 */

/** ViewModel bound to the app's [AppGraph]: `val vm: FooViewModel = graphViewModel { FooViewModel(it) }`. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = GuftuguApp.graph(LocalContext.current)
    val factory = remember(graph) { viewModelFactory { initializer { create(graph) } } }
    return viewModel(key = key, factory = factory)
}

/** BiometricPrompt needs the hosting [FragmentActivity]; Compose only hands us a Context. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** A message a ViewModel can emit without holding a Context. */
sealed class UiMessage {
    data class Res(@StringRes val id: Int) : UiMessage()
    data class Auth(val error: AuthError) : UiMessage()
    data class Text(val text: String) : UiMessage()
}

@Composable
fun UiMessage.text(): String = when (this) {
    is UiMessage.Res -> stringResource(id)
    is UiMessage.Auth -> error.userMessage(LocalContext.current)
    is UiMessage.Text -> text
}

/**
 * Warm inline banner for errors and hints — gold-mist parchment with a deep-gold left rule.
 * Never a dialog (DESIGN.md "Join a server"); appears instantly (no motion budget spent here).
 */
@Composable
fun WarmBanner(
    message: String?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    if (message == null) return
    val shape = RoundedCornerShape(14.dp)
    val rule = GuftuguTheme.craft.goldDeep
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, shape)
            .drawBehind {
                val w = 3.dp.toPx()
                drawRoundRect(rule, topLeft = Offset.Zero, size = Size(w, size.height), cornerRadius = CornerRadius(w, w))
            }
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = rule, modifier = Modifier.size(18.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp),
        )
        if (action != null) action()
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dismiss), tint = rule, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The logo medallion sitting in a soft gold glow ring (one radial-gradient circle, drawn once). */
@Composable
fun GlowMedallion(size: Dp, modifier: Modifier = Modifier, glow: Dp = 28.dp) {
    val dark = GuftuguTheme.craft.isDark
    // A soft golden halo plus a static sunburst of fine rays behind the square gold frame.
    val halo = remember(dark) {
        Brush.radialGradient(
            0f to (if (dark) Gold.copy(alpha = 0.35f) else GoldLight.copy(alpha = 0.95f)),
            0.55f to (if (dark) Gold.copy(alpha = 0.18f) else GoldLight.copy(alpha = 0.55f)),
            1f to Color.Transparent,
        )
    }
    val ray = if (dark) Gold.copy(alpha = 0.10f) else Gold.copy(alpha = 0.22f)
    Box(
        modifier
            .size(size + glow * 2)
            .drawBehind {
                val c = center
                val r = this.size.minDimension / 2f * 1.18f
                drawCircle(halo, radius = r)
                val n = 28
                for (i in 0 until n) {
                    val a = (i * 2.0 * Math.PI / n).toFloat()
                    val w = (Math.PI / n * 0.45).toFloat()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + r * kotlin.math.cos(a - w), c.y + r * kotlin.math.sin(a - w))
                        lineTo(c.x + r * kotlin.math.cos(a + w), c.y + r * kotlin.math.sin(a + w))
                        close()
                    }
                    drawPath(path, ray)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LogoMedallion(size = size)
    }
}

/** Outlined field in the craft style: 16dp corners, gold hairline on focus. */
@Composable
fun CraftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) } },
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GoldDeep,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = GoldDeep,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** Password field with a show/hide eye. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    CraftTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }, onNext = { onImeAction() }, onGo = { onImeAction() }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (visible) R.string.enroll_hide_password else R.string.enroll_show_password),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Small gold dot used as a status marker (online, "this phone"). */
@Composable
fun GoldDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(GoldDeep, CircleShape))
}

/** Text-only secondary action in the craft palette. */
@Composable
fun CraftTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
