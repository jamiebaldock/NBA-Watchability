package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl

/**
 * A tab's top-bar title: a tappable league logo + name - shared by every
 * tab's top bar (Games, Starred, History, Leaders, News), not just Games,
 * so the league can be switched from wherever the user happens to be.
 * [enabledLeagues] (Settings' "Selected Sports") controls which leagues
 * actually list in the dropdown - filtered against LeagueGroup.entries
 * (not iterated directly from the Set) so the list always renders in a
 * stable, consistent order regardless of Set iteration order.
 */
@Composable
fun TitleLeagueSelector(selectedLeague: LeagueGroup, onLeagueSelected: (LeagueGroup) -> Unit, enabledLeagues: Set<LeagueGroup>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = true }
        ) {
            AsyncImage(
                model = themeAwareLogoUrl(selectedLeague.logoUrl),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedLeague.shortDisplayName,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select league",
                tint = TextPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LeagueGroup.entries.filter { it in enabledLeagues }.forEach { league ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = themeAwareLogoUrl(league.logoUrl),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = league.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
                            )
                        }
                    },
                    onClick = {
                        onLeagueSelected(league)
                        expanded = false
                    }
                )
            }
        }
    }
}
