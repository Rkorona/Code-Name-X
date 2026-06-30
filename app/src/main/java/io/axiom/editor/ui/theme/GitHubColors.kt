package io.axiom.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class GitHubColorScheme(
    val background: Color,
    val card: Color,
    val cardExpanded: Color,
    val expandedBorder: Color,
    val input: Color,
    val actionBtn: Color,
    val commitWrap: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textMutedLight: Color,
    val accentBlue: Color,
    val accentBlueLight: Color,
    val accentRed: Color,
    val accentGreen: Color,
    val accentBlueAlpha: Color,
    val accentRedAlpha: Color,
    val accentBlueAlpha2: Color,
    val placeholder: Color,
    val loginBorder: Color,
)

private val LightGitHubColorScheme = GitHubColorScheme(
    background       = GhLightBackground,
    card             = GhLightCard,
    cardExpanded     = GhLightCardExpanded,
    expandedBorder   = GhLightExpandedBorder,
    input            = GhLightInput,
    actionBtn        = GhLightActionBtn,
    commitWrap       = GhLightCommitWrap,
    textPrimary      = GhLightTextPrimary,
    textSecondary    = GhLightTextSecondary,
    textMuted        = GhLightTextMuted,
    textMutedLight   = GhLightTextMutedLight,
    accentBlue       = GhLightAccentBlue,
    accentBlueLight  = GhLightAccentBlueLight,
    accentRed        = GhLightAccentRed,
    accentGreen      = GhLightAccentGreen,
    accentBlueAlpha  = GhLightAccentBlueAlpha,
    accentRedAlpha   = GhLightAccentRedAlpha,
    accentBlueAlpha2 = GhLightAccentBlueAlpha2,
    placeholder      = GhLightPlaceholder,
    loginBorder      = GhLightLoginBorder,
)

private val DarkGitHubColorScheme = GitHubColorScheme(
    background       = GhDarkBackground,
    card             = GhDarkCard,
    cardExpanded     = GhDarkCardExpanded,
    expandedBorder   = GhDarkExpandedBorder,
    input            = GhDarkInput,
    actionBtn        = GhDarkActionBtn,
    commitWrap       = GhDarkCommitWrap,
    textPrimary      = GhDarkTextPrimary,
    textSecondary    = GhDarkTextSecondary,
    textMuted        = GhDarkTextMuted,
    textMutedLight   = GhDarkTextMutedLight,
    accentBlue       = GhDarkAccentBlue,
    accentBlueLight  = GhDarkAccentBlueLight,
    accentRed        = GhDarkAccentRed,
    accentGreen      = GhDarkAccentGreen,
    accentBlueAlpha  = GhDarkAccentBlueAlpha,
    accentRedAlpha   = GhDarkAccentRedAlpha,
    accentBlueAlpha2 = GhDarkAccentBlueAlpha2,
    placeholder      = GhDarkPlaceholder,
    loginBorder      = GhDarkLoginBorder,
)

val LocalGitHubColors = staticCompositionLocalOf<GitHubColorScheme> {
    error("LocalGitHubColors not provided")
}

@Composable
fun gitHubColors(): GitHubColorScheme =
    if (isSystemInDarkTheme()) DarkGitHubColorScheme else LightGitHubColorScheme
