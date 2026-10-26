package com.redditswipe.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.redditswipe.AccountState

/**
 * The optional account: signed out, it asks for an email and then the 6-digit code sent there; signed in,
 * it shows the address, when this phone last synced, and Sync now / Sign out.
 */
@Composable
fun AccountScreen(
    account: AccountState,
    onRequestCode: (String) -> Unit,
    onVerifyCode: (String) -> Unit,
    onCancel: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            account.email != null -> SignedIn(account, onSyncNow, onSignOut)
            account.pendingEmail != null -> EnterCode(account, onVerifyCode, onRequestCode, onCancel)
            else -> EnterEmail(account, onRequestCode)
        }
        account.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EnterEmail(account: AccountState, onRequestCode: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    val send = { if (email.isNotBlank()) onRequestCode(email) }
    Text("Sync across phones", style = MaterialTheme.typography.titleLarge)
    Text(
        "An account is optional. Sign in to keep your liked cards, news sources, subreddits and settings the " +
            "same on every phone. No password: we email you a 6-digit code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyButton("Email me a code", account.isBusy, enabled = email.isNotBlank(), onClick = send)
}

@Composable
private fun EnterCode(
    account: AccountState,
    onVerifyCode: (String) -> Unit,
    onRequestCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val verify = { if (code.length == 6) onVerifyCode(code) }
    Text("Check your email", style = MaterialTheme.typography.titleLarge)
    Text(
        "We sent a 6-digit code to ${account.pendingEmail}. It works for 10 minutes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { input -> code = input.filter(Char::isDigit).take(6) },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { verify() }),
        modifier = Modifier.fillMaxWidth(),
    )
    BusyButton("Sign in", account.isBusy, enabled = code.length == 6, onClick = verify)
    Row {
        TextButton(onClick = { account.pendingEmail?.let(onRequestCode) }, enabled = !account.isBusy) { Text("Send again") }
        TextButton(onClick = onCancel, enabled = !account.isBusy) { Text("Use a different email") }
    }
}

@Composable
private fun SignedIn(account: AccountState, onSyncNow: () -> Unit, onSignOut: () -> Unit) {
    Text("Signed in", style = MaterialTheme.typography.titleLarge)
    Text(account.email.orEmpty(), style = MaterialTheme.typography.bodyLarge)
    Text(
        if (account.lastSyncedAt > 0) {
            "Last synced " + DateUtils.getRelativeTimeSpanString(
                account.lastSyncedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        } else {
            "Not synced yet"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Liked cards, news sources, subreddits and settings sync on their own a few seconds after they change, " +
            "and when the app opens.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BusyButton("Sync now", account.isBusy, enabled = true, onClick = onSyncNow)
    OutlinedButton(onClick = onSignOut, enabled = !account.isBusy) { Text("Sign out") }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}
