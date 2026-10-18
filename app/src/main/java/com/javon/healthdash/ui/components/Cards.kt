package com.javon.healthdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.MealResponses
import com.javon.healthdash.calc.Trend
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme

/** Generic surface card (radius xl, padding lg). */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.xl)
            .background(Theme.colors.bgSurface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Dimens.spacingLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        content = content,
    )
}

/** Rows separated by 1dp subtle lines inside one rounded container (Figma "group" pattern). */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(Shapes.xl).background(Theme.colors.borderSubtle),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Theme.type.headingH3, color = Theme.colors.textPrimary)
        if (action != null) {
            Text(
                action, style = Theme.type.labelM, color = Theme.colors.textAccent,
                modifier = if (onAction != null) Modifier.clickable(onClick = onAction).padding(Dimens.spacingXs) else Modifier,
            )
        }
    }
}

@Composable
fun TrendArrow(trend: Trend, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val (deg, color) = when (trend) {
        Trend.RisingFast -> 0f to c.statusVeryHigh
        Trend.Rising -> 45f to c.statusHigh
        Trend.Steady -> 90f to c.statusInRange
        Trend.Falling -> 135f to c.statusHigh
        Trend.FallingFast -> 180f to c.statusLow
    }
    Icon(AppIcons.arrowUp, trend.name, tint = color, modifier = modifier.size(20.dp).rotate(deg))
}

/** Figma: Stat Card. Pass value = null to render the "Not enough data" variant. */
@Composable
fun StatCard(
    title: String,
    icon: ImageVector,
    value: String?,
    unit: String,
    footnote: String,
    modifier: Modifier = Modifier,
    trend: Trend? = null,
) {
    val c = Theme.colors
    Column(
        modifier.clip(Shapes.xl).background(c.bgSurface).padding(Dimens.spacingLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.iconSecondary, modifier = Modifier.size(Dimens.iconSm))
            Text(title, style = Theme.type.labelM, color = c.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.Bottom) {
            Text(value ?: "—", style = Theme.type.metricL, color = c.textPrimary)
            if (unit.isNotEmpty()) Text(unit, style = Theme.type.bodyS, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
            if (trend != null && value != null) TrendArrow(trend, Modifier.padding(bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
            if (value == null) Icon(AppIcons.info, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
            Text(footnote, style = Theme.type.caption, color = c.textTertiary)
        }
    }
}

/** Figma: Score Badge. */
@Composable
fun ScoreBadge(score: Int, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val c = Theme.colors
    val ring = when (MealResponses.grade(score)) {
        MealResponses.Grade.Good -> c.statusInRange
        MealResponses.Grade.Fair -> c.statusHigh
        MealResponses.Grade.Poor -> c.statusVeryHigh
    }
    Box(
        modifier.size(size).clip(CircleShape).background(c.bgSurfaceRaised).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("$score", style = Theme.type.labelL, color = c.textPrimary) }
}

/** Figma: List Row. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = true,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.bgSurface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> Box(Modifier.size(40.dp).clip(Shapes.md).background(c.bgNeutral), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.iconPrimary, modifier = Modifier.size(Dimens.iconMd))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
            Text(title, style = Theme.type.bodyL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Theme.type.bodyS, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = Theme.type.labelL, color = c.textSecondary)
        trailingContent?.invoke(this)
        if (chevron && onClick != null) Icon(AppIcons.chevronRight, null, tint = c.iconSecondary, modifier = Modifier.size(Dimens.iconMd))
    }
}

enum class NoticeType { Gentle, Info, Prompt, AI }

/** Figma: Notice. Gentle = under-fueling flag, never an alarm. */
@Composable
fun Notice(
    type: NoticeType,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Theme.colors
    val (bg, fg, icon) = when (type) {
        NoticeType.Gentle -> Triple(c.bgNotice, c.textNotice, AppIcons.info)
        NoticeType.Info -> Triple(c.bgSurfaceRaised, c.textSecondary, AppIcons.info)
        NoticeType.Prompt -> Triple(c.bgAccentSubtle, c.textAccent, AppIcons.meal)
        NoticeType.AI -> Triple(c.bgAi, c.textAi, AppIcons.ai)
    }
    Row(
        modifier.fillMaxWidth().clip(Shapes.lg).background(bg).padding(Dimens.spacingLg),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(Dimens.iconMd))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
            Text(title, style = Theme.type.labelL, color = c.textPrimary)
            Text(body, style = Theme.type.bodyM, color = c.textSecondary)
            if (action != null) {
                Row(
                    Modifier.then(if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier).padding(vertical = Dimens.spacingXxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXxs),
                ) {
                    Text(action, style = Theme.type.labelM, color = fg)
                    Icon(AppIcons.chevronRight, null, tint = fg, modifier = Modifier.size(Dimens.iconSm))
                }
            }
        }
    }
}

/** Figma: Insight Row — every statement links to the data behind it (SPEC rule 8). */
@Composable
fun InsightRow(statement: String, trace: String, modifier: Modifier = Modifier, onTrace: (() -> Unit)? = null) {
    val c = Theme.colors
    Row(
        modifier.fillMaxWidth().background(c.bgSurface).height(IntrinsicSize.Min)
            .padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(Shapes.full).background(c.borderAccent))
        Text(statement, style = Theme.type.bodyM, color = c.textPrimary, modifier = Modifier.weight(1f))
        Row(
            Modifier.then(if (onTrace != null) Modifier.clickable(onClick = onTrace) else Modifier).padding(Dimens.spacingXxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXxs),
        ) {
            Icon(AppIcons.link, null, tint = c.textAccent, modifier = Modifier.size(14.dp))
            Text(trace, style = Theme.type.labelS, color = c.textAccent)
        }
    }
}

enum class SyncStatus { Synced, Delayed, NotConnected }

/** Figma: Sync Status Row. */
@Composable
fun SyncStatusRow(source: String, status: SyncStatus, detail: String, app: String, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val dot = when (status) {
        SyncStatus.Synced -> c.statusInRange
        SyncStatus.Delayed -> c.statusVeryHigh
        SyncStatus.NotConnected -> c.statusLow
    }
    Row(
        modifier.fillMaxWidth().background(c.bgSurface).padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
            Text(source, style = Theme.type.bodyL, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(detail, style = Theme.type.bodyS, color = c.textSecondary)
            }
        }
        Text(app, style = Theme.type.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
    }
}

/** Figma: Empty State — dashed card used whenever data is insufficient. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.xl)
            .background(c.bgSurface)
            .drawBehind {
                drawRoundRect(
                    color = c.borderSubtle,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = CornerRadius(Dimens.radiusXl.toPx()),
                )
            }
            .padding(Dimens.spacing3xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
    ) {
        Icon(AppIcons.info, null, tint = c.iconSecondary, modifier = Modifier.size(Dimens.iconLg))
        Text(title, style = Theme.type.headingH3, color = c.textPrimary)
        Text(body, style = Theme.type.bodyM, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}

/** Grid of small metric tiles (sunken background), used on detail screens. */
@Composable
fun MetricTiles(items: List<Pair<String, String>>, columns: Int = 3, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                rowItems.forEach { (label, value) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(Shapes.lg).background(c.bgSurfaceSunken).padding(Dimens.spacingMd),
                        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs),
                    ) {
                        Text(value, style = Theme.type.metricM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, style = Theme.type.caption, color = c.textSecondary)
                    }
                }
                repeat(columns - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ThinDivider() = HorizontalDivider(thickness = 1.dp, color = Theme.colors.borderSubtle)
