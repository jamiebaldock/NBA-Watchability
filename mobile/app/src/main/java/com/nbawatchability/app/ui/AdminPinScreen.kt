package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TierInstantClassic

/**
 * Reached by tapping the About screen's title 12 times - a separate,
 * higher-count gesture than the version number's 8-tap route to
 * SecretScreen, so the two hidden pages don't collide on the same tap
 * target. PIN is checked server-side (adminService.ts); this screen only
 * ever holds what the user typed, never the real PIN itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPinScreen(
    isLoggingIn: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Admin", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Enter PIN", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty()) onSubmit(pin) }),
                singleLine = true,
                enabled = !isLoggingIn,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            if (error != null) {
                Text(
                    text = error,
                    color = TierInstantClassic,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Button(
                onClick = { onSubmit(pin) },
                enabled = !isLoggingIn && pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
            Text(
                text = "Not shown anywhere in Settings on purpose.",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally)
            )
        }
    }
}
