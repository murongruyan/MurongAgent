package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object GitHubColors {
    val LightBackground = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFD0D7DE)
    val LightText = Color(0xFF24292F)
    val LightMutedText = Color(0xFF57606A)
    val LightPrimary = Color(0xFF2DA44E)
    val LightAccent = Color(0xFF0969DA)
    val LightDanger = Color(0xFFCF222E)
    val LightPurple = Color(0xFF8250DF)

    val DarkBackground = Color(0xFF0D1117)
    val DarkSurface = Color(0xFF161B22)
    val DarkBorder = Color(0xFF30363D)
    val DarkText = Color(0xFFC9D1D9)
    val DarkMutedText = Color(0xFF8B949E)
    val DarkPrimary = Color(0xFF238636)
    val DarkAccent = Color(0xFF58A6FF)
    val DarkDanger = Color(0xFFF85149)
    val DarkPurple = Color(0xFFA371F7)
}

@Composable
internal fun rememberGitHubColors(): GitHubColorPalette {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        GitHubColorPalette(
            background = GitHubColors.DarkBackground,
            surface = GitHubColors.DarkSurface,
            border = GitHubColors.DarkBorder,
            text = GitHubColors.DarkText,
            mutedText = GitHubColors.DarkMutedText,
            primary = GitHubColors.DarkPrimary,
            accent = GitHubColors.DarkAccent,
            danger = GitHubColors.DarkDanger,
            purple = GitHubColors.DarkPurple
        )
    } else {
        GitHubColorPalette(
            background = GitHubColors.LightBackground,
            surface = GitHubColors.LightBackground, // GitHub light cards are white
            border = GitHubColors.LightBorder,
            text = GitHubColors.LightText,
            mutedText = GitHubColors.LightMutedText,
            primary = GitHubColors.LightPrimary,
            accent = GitHubColors.LightAccent,
            danger = GitHubColors.LightDanger,
            purple = GitHubColors.LightPurple
        )
    }
}

data class GitHubColorPalette(
    val background: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val mutedText: Color,
    val primary: Color,
    val accent: Color,
    val danger: Color,
    val purple: Color
)

@Composable
internal fun GitHubCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = rememberGitHubColors()
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = colors.surface,
            contentColor = colors.text
        ),
        border = BorderStroke(1.dp, colors.border)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
internal fun GitHubLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .androidx.compose.foundation.border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .androidx.compose.foundation.background(color.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .androidx.compose.foundation.layout.padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
