package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.adminAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "admin_auth")

private val ADMIN_TOKEN_KEY = stringPreferencesKey("admin_token")

/**
 * Persists the Admin page's bearer token on-device only, so re-entering the
 * hidden page doesn't require the PIN every single time - same tradeoff
 * AppSettingsRepository already makes for every other persisted value. The
 * token itself is opaque and short-lived server-side (adminService.ts,
 * 24h TTL, invalidated on every backend restart), so persisting it locally
 * is no worse than a normal "stay signed in" cookie; the real gate is the
 * PIN check that issued it.
 */
class AdminAuthRepository(private val context: Context) {

    val token: Flow<String?> = context.adminAuthDataStore.data.map { it[ADMIN_TOKEN_KEY] }

    suspend fun setToken(token: String) {
        context.adminAuthDataStore.edit { it[ADMIN_TOKEN_KEY] = token }
    }

    suspend fun clearToken() {
        context.adminAuthDataStore.edit { it.remove(ADMIN_TOKEN_KEY) }
    }
}
