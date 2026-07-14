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

/**
 * Replaces a tab's static title with a tappable league logo + name whenever
 * "Show WNBA" is on - shared by every tab's top bar (Games, Starred,
 * History, Leaders, News), not just Games, so the league can be switched
 * from wherever the user happens to be. Off (the default), each tab keeps
 * its own plain static title with no dropdown affordance at all.
 */
@Composable
fun TitleLeagueSelector(selectedLeague: LeagueGroup, onLeagueSelected: (LeagueGroup) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = true }
        ) {
            AsyncImage(
                model = selectedLeague.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedLeague.displayName,
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
            LeagueGroup.entries.forEach { league ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = league.logoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(league.displayName)
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

/**
 * A tab's top-bar title: the tappable league selector when "Show WNBA" is
 * on, otherwise [plainTitle] as static text - the same swap Games has
 * always done, just shared so every tab's title behaves identically.
 */
@Composable
fun TabTitle(showWnba: Boolean, selectedLeague: LeagueGroup, onLeagueSelected: (LeagueGroup) -> Unit, plainTitle: String) {
    if (showWnba) {
        TitleLeagueSelector(selectedLeague = selectedLeague, onLeagueSelected = onLeagueSelected)
    } else {
        Text(plainTitle, color = TextPrimary)
    }
}
