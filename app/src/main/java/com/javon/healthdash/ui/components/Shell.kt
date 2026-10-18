package com.javon.healthdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", AppIcons.home),
    A1c("A1c", AppIcons.lab),
    Meals("Meals", AppIcons.meal),
    Training("Training", AppIcons.run),
    More("More", AppIcons.more),
}

/** Figma: Bottom Nav. */
@Composable
fun BottomNav(active: Tab, onSelect: (Tab) -> Unit) {
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().background(c.bgSurface).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = c.borderSubtle)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingSm, vertical = Dimens.spacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tab.entries.forEach { tab ->
                val sel = tab == active
                Column(
                    Modifier.width(72.dp).clip(Shapes.lg).clickable { onSelect(tab) }.padding(vertical = Dimens.spacingXs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                ) {
                    Box(
                        Modifier.width(56.dp).height(32.dp).clip(Shapes.full).background(if (sel) c.bgAccentSubtle else c.bgSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, tab.label, tint = if (sel) c.iconAccent else c.iconSecondary, modifier = Modifier.size(22.dp))
                    }
                    Text(tab.label, style = Theme.type.labelS, color = if (sel) c.textPrimary else c.textSecondary)
                }
            }
        }
    }
}

/** Figma: Top App Bar, Type=Large (top-level tabs). */
@Composable
fun LargeTopBar(title: String, overline: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(overline, style = Theme.type.labelM, color = Theme.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) { actions() }
        }
        Text(title, style = Theme.type.displayM, color = Theme.colors.textPrimary)
    }
}

/** Figma: Top App Bar, Type=Small (sub-screens). */
@Composable
fun SmallTopBar(title: String, onBack: (() -> Unit)?, action: ImageVector? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingXs, vertical = Dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AppIconButton(AppIcons.arrowBack, "Back", onBack)
        } else {
            Box(Modifier.width(Dimens.spacingMd))
        }
        Text(title, style = Theme.type.headingH3, color = Theme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) AppIconButton(action, null, onAction, iconSize = 22.dp)
    }
}

/**
 * Screen frame: status bar inset, top bar, scrolling content with 16dp gutters, optional footer
 * (pinned actions) and bottom nav.
 */
@Composable
fun ScreenFrame(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Theme.colors.bgCanvas)) {
        Column(Modifier.statusBarsPadding()) { topBar() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = Dimens.spacingLg, end = Dimens.spacingLg, top = Dimens.spacingSm, bottom = Dimens.spacing3xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingLg),
            content = content,
        )
        if (footer != null) {
            Column(
                Modifier.fillMaxWidth().then(if (bottomBar == null) Modifier.navigationBarsPadding() else Modifier)
                    .padding(start = Dimens.spacingLg, end = Dimens.spacingLg, top = Dimens.spacingMd, bottom = Dimens.spacing2xl),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
                content = footer,
            )
        }
        bottomBar?.invoke()
    }
}

@Composable
fun Heading(title: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        Text(title, style = Theme.type.headingH1, color = Theme.colors.textPrimary)
        if (sub != null) Text(sub, style = Theme.type.bodyM, color = Theme.colors.textSecondary)
    }
}

@Composable
fun Caption(text: String) = Text(text, style = Theme.type.caption, color = Theme.colors.textTertiary)

@Composable
fun PhotoBox(modifier: Modifier = Modifier) {
    Box(modifier.clip(Shapes.lg).background(Theme.colors.bgNeutral), contentAlignment = Alignment.Center) {
        Icon(AppIcons.image, null, tint = Theme.colors.iconSecondary, modifier = Modifier.size(28.dp))
    }
}
