package com.nbawatchability.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbawatchability.app.BuildConfig
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

// Tap the version number this many times to reach SecretScreen - deliberately
// undocumented anywhere in the app's own UI. 8, not a round number - Kobe's
// number after he switched from 8 to 24.
private const val TAPS_TO_UNLOCK_SECRET = 8

// Tap the app title this many times to reach the hidden Admin page - a
// separate counter on a separate element from the version number above, so
// the two hidden destinations never fight over the same tap target/count.
private const val TAPS_TO_UNLOCK_ADMIN = 12

// Same contact address already published in play-store/privacy-policy.txt -
// reused here rather than introduced fresh, so there's one address for users
// to remember, not two.
private const val CONTACT_EMAIL = "help@tech3d.com.au"

// Hosted from docs/privacy-policy.html via GitHub Pages (this repo's docs/
// folder) - verified live before wiring it in here.
private const val PRIVACY_POLICY_URL = "https://jamiebaldock.github.io/NBA-Watchability/privacy-policy.html"

/** Reached from Settings rather than the bottom nav - frees up a nav slot for Starred. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onSecretUnlocked: () -> Unit, onAdminUnlocked: () -> Unit) {
    // Plain remember, not rememberSaveable/persisted - resets every time this
    // screen is (re)entered, same as a real hidden-menu tap counter should.
    var tapCount by remember { mutableIntStateOf(0) }
    var titleTapCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("About", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Undocumented on purpose - see TAPS_TO_UNLOCK_ADMIN above.
                Text(
                    text = "Big4 Watchability",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        titleTapCount++
                        if (titleTapCount >= TAPS_TO_UNLOCK_ADMIN) {
                            titleTapCount = 0
                            onAdminUnlocked()
                        }
                    }
                )
                Text(
                    text = "Spoiler-free NBA, WNBA, MLB, NFL & NHL watchability scores.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)
                )
                // Undocumented on purpose - see TAPS_TO_UNLOCK_SECRET above.
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 8.dp).clickable {
                        tapCount++
                        if (tapCount >= TAPS_TO_UNLOCK_SECRET) {
                            tapCount = 0
                            onSecretUnlocked()
                        }
                    }
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            AboutSectionHeader(icon = Icons.Default.Info, title = "How it works")
            Text(
                text = "Every NBA, WNBA, MLB, NFL, and NHL game gets a Watchability Score once it's " +
                    "final, built from what actually makes a game exciting: close margins, comebacks, " +
                    "lead changes, clutch finishes, buzzer-beaters, overtime, and standout individual " +
                    "performances.\n\nScores land in one of four tiers - 🔥 Instant Classic, " +
                    "⭐ Worth Your Time, 👍 Solid, or 😴 Skippable - and each " +
                    "league is scored on its own scale, calibrated separately against real completed " +
                    "games rather than one generic formula. Nothing about how a game turns out is shown " +
                    "until you ask for it.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                modifier = Modifier.padding(bottom = 20.dp)
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            AboutSectionHeader(icon = Icons.AutoMirrored.Filled.HelpOutline, title = "How to use the app")
            AboutExpandableItem(
                title = "Browsing the schedule",
                body = "Schedule shows a full week of games per league, past and future - swipe " +
                    "between days, pull to refresh live scores, and switch leagues from the dropdown " +
                    "up top. Games in progress or upcoming show a short preview blurb, never a hint at " +
                    "the result."
            )
            AboutExpandableItem(
                title = "Reading a score",
                body = "Once a game's final, its tile shows a tier badge instead of a score. Tap a " +
                    "tile to reveal the full breakdown - margin, comebacks, lead changes, and more - " +
                    "still without the actual final score or winner unless you choose to see it."
            )
            AboutExpandableItem(
                title = "Starred & Favorites",
                body = "Star individual games from Schedule to track them in the Starred tab. " +
                    "Favorites is different: pick teams and players you follow, and Favorites collects " +
                    "all of their games - past and upcoming - in one place, across every league."
            )
            AboutExpandableItem(
                title = "Alerts",
                body = "Tap the bell on any game tile to get a local notification shortly before it " +
                    "starts, plus a push alert if the score swings late. Turn on alerts for all your " +
                    "favorite teams' games at once from Settings → Alerts."
            )
            AboutExpandableItem(
                title = "History",
                body = "History is the full past-games archive for a league, sortable by date or by " +
                    "score. Breakdowns stay spoiler-blurred by default even for old games - tap one to " +
                    "reveal it, or flip the default in Settings."
            )
            AboutExpandableItem(
                title = "Customizing the rating",
                body = "Every league's Watchability Score is a weighted mix of factors like margin, " +
                    "comebacks, and clutch finishes. Settings → Watchability rating weights lets " +
                    "you scale any factor up or down, per league, and reset back to default anytime."
            )
            AboutExpandableItem(
                title = "Leaders, Stats & News",
                body = "Leaders covers standings and statistical leaders for the selected league; " +
                    "News pulls in the latest headlines. Both stay spoiler-safe - no live scores sneak " +
                    "in outside Schedule/History.",
                trailingDivider = false
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            AboutSectionHeader(icon = Icons.Default.Gavel, title = "Legal")
            AboutExpandableItem(
                title = "Trademarks",
                body = "NBA, WNBA, MLB, NFL, NHL, and all associated team names, logos, and marks are " +
                    "trademarks of their respective owners - the National Basketball Association, " +
                    "Major League Baseball, the National Football League, the National Hockey League, " +
                    "and their member teams. Big4 Watchability is an independent app, not affiliated " +
                    "with, endorsed by, or sponsored by any of these leagues or teams."
            )
            AboutExpandableItem(
                title = "Data & content",
                body = "Game schedules, scores, statistics, team crests, and news are sourced from " +
                    "ESPN's publicly accessible content servers. Big4 Watchability is not affiliated " +
                    "with or endorsed by ESPN."
            )
            AboutExpandableItem(
                title = "Open-source software",
                body = "Built with Jetpack Compose, Kotlin Coroutines & Serialization, Coil, AndroidX " +
                    "WorkManager, Firebase Cloud Messaging, and the Android YouTube Player library, " +
                    "each used under its own open-source license.",
                trailingDivider = false
            )

            AboutLinkRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "Privacy policy",
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
            )

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            AboutLinkRow(
                icon = Icons.Default.Email,
                label = "Questions or feedback",
                sublabel = CONTACT_EMAIL,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")))
                }
            )

            Text(
                text = "© 2026 Tech3D. All rights reserved.",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun AboutSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = TextSecondary)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * One collapsed-by-default row that expands in place to show [body] -
 * every "How to use"/"Legal" entry uses this so the section reads as a
 * scannable list of headings rather than a wall of text up front.
 */
@Composable
private fun AboutExpandableItem(title: String, body: String, trailingDivider: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextMuted,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = body,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                modifier = Modifier.padding(bottom = 14.dp)
            )
        }
        if (trailingDivider) {
            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
        }
    }
}

/** A tappable external-action row (open a link, compose an email) - same shape as SettingsScreen's nav rows. */
@Composable
private fun AboutLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp))
            if (sublabel != null) {
                Text(text = sublabel, color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp))
            }
        }
        Icon(imageVector = icon, contentDescription = null, tint = TextMuted)
    }
}
