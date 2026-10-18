package com.javon.healthdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme

enum class ButtonStyle { Primary, Secondary, Ghost }
enum class ButtonSize { Medium, Small }

/** Figma: Button (Style × Size × State). Pill shape; Primary is the one orange action per screen. */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Theme.colors
    val bg = when (style) {
        ButtonStyle.Primary -> c.bgAccent
        ButtonStyle.Secondary -> c.bgNeutral
        ButtonStyle.Ghost -> Color.Transparent
    }
    val fg = if (style == ButtonStyle.Primary) c.textOnAccent else c.textPrimary
    val height = if (size == ButtonSize.Medium) Dimens.buttonMd else Dimens.buttonSm
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(height)
            .clip(Shapes.full)
            .background(bg)
            .then(if (style == ButtonStyle.Ghost) Modifier.border(Dimens.strokeHairline, c.borderStrong, Shapes.full) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (size == ButtonSize.Medium) Dimens.spacing2xl else Dimens.spacingLg),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(if (size == ButtonSize.Medium) Dimens.iconMd else Dimens.iconSm))
        Text(label, style = if (size == ButtonSize.Medium) Theme.type.labelL else Theme.type.labelM, color = fg)
    }
}

enum class IconButtonStyle { Filled, Tonal, Ghost }

/** Figma: Icon Button — 48dp circle. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = IconButtonStyle.Ghost,
    size: androidx.compose.ui.unit.Dp = Dimens.touchTarget,
    iconSize: androidx.compose.ui.unit.Dp = Dimens.iconLg,
) {
    val c = Theme.colors
    val bg = when (style) {
        IconButtonStyle.Filled -> c.bgAccent
        IconButtonStyle.Tonal -> c.bgNeutral
        IconButtonStyle.Ghost -> Color.Transparent
    }
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (style == IconButtonStyle.Filled) c.textOnAccent else c.iconPrimary, modifier = Modifier.size(iconSize))
    }
}

enum class TagType { AI, Derived, InRange, High, VeryHigh, Low, Neutral, Brand }

/** Figma: Tag. AI marks any AI-derived content (SPEC rule 9). */
@Composable
fun Tag(label: String, type: TagType, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val (bg, fg) = when (type) {
        TagType.AI -> c.bgAi to c.textAi
        TagType.Derived, TagType.Neutral -> c.bgNeutral to c.textSecondary
        TagType.InRange -> c.bgSuccessSubtle to c.textPrimary
        TagType.High, TagType.VeryHigh, TagType.Low -> c.bgSurfaceRaised to c.textPrimary
        TagType.Brand -> c.bgAccentSubtle to c.textAccent
    }
    val dot = when (type) {
        TagType.InRange -> c.statusInRange
        TagType.High -> c.statusHigh
        TagType.VeryHigh -> c.statusVeryHigh
        TagType.Low -> c.statusLow
        else -> null
    }
    Row(
        modifier.clip(Shapes.full).background(bg).padding(horizontal = Dimens.spacingSm, vertical = Dimens.spacingXxs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        if (type == TagType.AI) Icon(AppIcons.ai, null, tint = fg, modifier = Modifier.size(12.dp))
        Text(label, style = Theme.type.labelS, color = fg)
    }
}

/** Figma: Filter Chip. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier
            .height(32.dp)
            .clip(Shapes.full)
            .background(if (selected) c.bgInverse else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(Dimens.strokeHairline, c.borderSubtle, Shapes.full))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(AppIcons.check, null, tint = c.textInverse, modifier = Modifier.size(Dimens.iconSm))
        Text(label, style = Theme.type.labelM, color = if (selected) c.textInverse else c.textSecondary)
    }
}

/** Figma: Segmented Control (Segment instances). */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier.clip(Shapes.full).background(c.bgSurfaceSunken).padding(Dimens.spacingXxs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXxs),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .height(32.dp)
                    .widthIn(min = 64.dp)
                    .clip(Shapes.full)
                    .background(if (sel) c.bgSurfaceRaised else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = Dimens.spacingLg),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Theme.type.labelM, color = if (sel) c.textPrimary else c.textSecondary)
            }
        }
    }
}

/** Figma: Toggle. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Box(
        modifier
            .width(52.dp).height(32.dp)
            .clip(Shapes.full)
            .background(if (on) c.bgAccent else c.bgNeutral)
            .clickable { onChange(!on) }
            .padding(horizontal = Dimens.spacingXs),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) c.textOnAccent else c.textSecondary))
    }
}

/** Figma: Stepper (portion adjuster). */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier.clip(Shapes.full).background(c.bgSurfaceSunken).padding(Dimens.spacingXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(AppIcons.remove, "Less", onMinus, style = IconButtonStyle.Tonal, size = 36.dp, iconSize = 18.dp)
        Text(value, style = Theme.type.labelL, color = c.textPrimary)
        AppIconButton(AppIcons.add, "More", onPlus, style = IconButtonStyle.Tonal, size = 36.dp, iconSize = 18.dp)
    }
}

/** Figma: Text Field (Default / Focused / Error). */
@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    helper: String? = null,
    error: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
) {
    val c = Theme.colors
    var focused by remember { mutableStateOf(false) }
    val border = when {
        error != null -> c.statusLow
        focused -> c.borderFocus
        else -> c.borderSubtle
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        Text(label, style = Theme.type.labelM, color = c.textSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.buttonMd)
                .clip(Shapes.md)
                .background(c.bgSurfaceSunken)
                .border(if (focused || error != null) Dimens.strokeFocus else Dimens.strokeHairline, border, Shapes.md)
                .padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = Theme.type.bodyL, color = c.textTertiary)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = Theme.type.bodyL.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.borderFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (suffix != null) Text(suffix, style = Theme.type.bodyM, color = c.textSecondary)
        }
        val note = error ?: helper
        if (note != null) Text(note, style = Theme.type.caption, color = if (error != null) c.textDanger else c.textTertiary)
    }
}

@Composable
fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        for (i in 1..total) {
            Box(
                Modifier.width(if (i == current) 24.dp else 8.dp).height(4.dp).clip(Shapes.full)
                    .background(if (i <= current) c.bgAccent else c.bgNeutral),
            )
        }
    }
}

@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))
