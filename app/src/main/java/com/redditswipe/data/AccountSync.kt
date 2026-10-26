package com.redditswipe.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Keeps this phone and the account's snapshot in step. The snapshot is one JSON object (liked cards,
 * sources, settings, swiped history) that the newest change wins, except when this phone and another
 * both changed since they last met: then [apply] is asked to merge (liked cards from both are kept).
 *
 * [snapshot] builds what to upload, [fingerprint] summarizes the parts that count as a change here, and
 * [apply] takes in a downloaded snapshot. All three run on the caller's thread.
 */
class AccountSync(
    private val account: AccountRepository,
    private val snapshot: () -> JSONObject,
    private val fingerprint: () -> String,
    private val apply: (data: JSONObject, merge: Boolean) -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Downloads, applies or merges, and uploads as needed. [firstTime] (just signed in) merges this phone's
     * likes into the account; [forceUpload] uploads even when only the swiped history changed.
     * Returns true if it uploaded.
     */
    suspend fun sync(firstTime: Boolean = false, forceUpload: Boolean = false): Boolean = mutex.withLock {
        if (!account.isSignedIn) return@withLock false
        val changedHere = firstTime || account.syncedFingerprint != fingerprint()
        val (remote, remoteAt) = account.download()
        if (remote != null && remoteAt > account.syncedAt) {
            // Another phone uploaded since this one last synced.
            apply(remote, changedHere)
            if (!changedHere && !forceUpload) {
                account.syncedAt = remoteAt
                account.syncedFingerprint = fingerprint()
                return@withLock false
            }
        } else if (!changedHere && !forceUpload && remote != null) {
            return@withLock false
        }
        // Newer than anything on the server, even if this phone's clock runs behind.
        val at = maxOf(System.currentTimeMillis(), remoteAt + 1)
        account.upload(snapshot(), at)
        account.syncedAt = at
        account.syncedFingerprint = fingerprint()
        true
    }
}
