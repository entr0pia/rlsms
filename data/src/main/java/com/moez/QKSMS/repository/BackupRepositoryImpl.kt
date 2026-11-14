/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.repository

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.moez.QKSMS.common.util.extensions.now
import com.moez.QKSMS.model.BackupFile
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.util.Preferences
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import io.realm.Sort
import okio.buffer
import okio.source
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository
) : BackupRepository {

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the entire file
     */
    data class BackupMetadata(
        val messageCount: Int = 0
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    override fun getDefaultBackupPath(): String {
        return "${Environment.getExternalStorageDirectory()}/RLSMS/Backups"
    }

    override fun getBackupDocumentTree(): DocumentFile? {
        return prefs.backupDirectory.get()
                .takeIf { uri -> uri != Uri.EMPTY }
                ?.let { uri -> DocumentFile.fromTreeUri(context, uri) }
                ?.takeIf { dir -> dir.exists() && dir.canRead() && dir.canWrite() }
    }

    override fun getBackupPathUriForPicker(): Uri {
        return prefs.backupDirectory.get().takeIf { uri -> uri != Uri.EMPTY }
                ?: getDefaultBackupPath().toUri()
    }

    override fun persistBackupDirectory(directory: Uri) {
        context.contentResolver.takePersistableUriPermission(directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.backupDirectory.set(directory)

        Timber.v("Updated backup directory: $directory")
    }

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        var messageCount: Int

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages)).toByteArray()

        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(now())
            val inputStream = getBackupDocumentTree()
                    ?.createFile("application/json", "backup-$timestamp.json")
                    ?.let { file -> context.contentResolver.openOutputStream(file.uri) }
                    ?: throw Exception("Failed to open output stream")

            inputStream.use { stream ->
                stream.write(json)
            }
        } catch (e: Exception) {
            Timber.w(e)
        }

        // Mark the task finished, and set it as Idle a second later
        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    @SuppressLint("Recycle") // InputStream is closed by Okio BufferedSource
    override fun parseBackup(uri: Uri): BackupFile {
        val adapter = moshi.adapter(BackupMetadata::class.java)

        val file = DocumentFile.fromSingleUri(context, uri)
                ?: throw IllegalArgumentException("Couldn't open backup file")

        val metadata = context.contentResolver.openInputStream(file.uri)
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)
                ?: throw IllegalArgumentException("Couldn't parse backup file")

        return BackupFile(file.lastModified(), metadata.messageCount)
    }

    override fun performRestore(uri: Uri) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val adapter = moshi.adapter(Backup::class.java)
        val backup = DocumentFile.fromSingleUri(context, uri)
                ?.let { file -> context.contentResolver.openInputStream(file.uri) }
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0

        val messages = backup?.messages ?: emptyList()
        val canUseSubId = prefs.canUseSubId.get()
        val batchSize = 100
        var processedCount = 0

        val existingSmsByBody = HashMap<String, MutableList<Long>>()
        try {
            context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.DATE, Telephony.Sms.BODY),
                    null,
                    null,
                    null
            )?.use { cursor ->
                val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
                val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    val date = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    val body = if (bodyCol != -1) cursor.getString(bodyCol)?.trim() ?: "" else ""
                    if (body.isNotEmpty()) {
                        existingSmsByBody.getOrPut(body) { mutableListOf() }.add(date)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query existing SMS for deduplication")
        }

        // Deduplicate messages against existing ones within 2 seconds tolerance, and within backup
        val toRestoreMessages = messages.filter { message ->
            val body = message.body.trim()
            if (body.isEmpty()) return@filter true
            val dates = existingSmsByBody.getOrPut(body) { mutableListOf() }
            val isDuplicate = dates.any { existingDate -> Math.abs(existingDate - message.date) <= 2000L }
            if (isDuplicate) {
                false
            } else {
                dates.add(message.date)
                true
            }
        }

        val toRestoreCount = toRestoreMessages.size
        restoreProgress.onNext(BackupRepository.Progress.Running(toRestoreCount, 0))

        for (chunk in toRestoreMessages.chunked(batchSize)) {
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            val valuesList = chunk.map { message ->
                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (canUseSubId) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                values
            }

            try {
                val inserted = context.contentResolver.bulkInsert(Telephony.Sms.CONTENT_URI, valuesList.toTypedArray())
                if (inserted < valuesList.size) {
                    errorCount += (valuesList.size - inserted)
                }
            } catch (e: Exception) {
                Timber.w(e, "bulkInsert failed for batch of ${valuesList.size}, falling back to single inserts")
                for (values in valuesList) {
                    try {
                        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                    } catch (singleError: Exception) {
                        Timber.w(singleError)
                        errorCount++
                    }
                }
            }

            processedCount += chunk.size
            restoreProgress.onNext(BackupRepository.Progress.Running(toRestoreCount, processedCount))
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to restore $errorCount/$toRestoreCount messages"))
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    override fun stopRestore() {
        stopFlag = true
    }

    override fun deleteDuplicateMessages(): Single<Int> {
        return Single.fromCallable {
            val toDeleteSmsContentIds = mutableListOf<Long>()
            val toDeleteMmsContentIds = mutableListOf<Long>()
            val toDeleteRealmIds = mutableListOf<Long>()

            val realm = Realm.getDefaultInstance()
            realm.use { r ->
                val allMessages = r.where(Message::class.java)
                        .sort("date", Sort.ASCENDING)
                        .findAll()

                val groupedByThread = allMessages.groupBy { it.threadId }

                for ((_, messagesInThread) in groupedByThread) {
                    val seenMessages = mutableListOf<Message>()

                    for (msg in messagesInThread) {
                        val body = msg.getSummary().trim()
                        if (body.isEmpty()) continue

                        // Check if an identical message exists in the same thread within 2 seconds window
                        val isDuplicate = seenMessages.any { seen ->
                            seen.getSummary().trim() == body &&
                            Math.abs(seen.date - msg.date) <= 2000L
                        }

                        if (isDuplicate) {
                            toDeleteRealmIds.add(msg.id)
                            if (msg.isMms()) {
                                toDeleteMmsContentIds.add(msg.contentId)
                            } else {
                                toDeleteSmsContentIds.add(msg.contentId)
                            }
                        } else {
                            seenMessages.add(msg)
                        }
                    }
                }
            }

            var deletedCount = 0

            // 1. Delete system SMS duplicates
            if (toDeleteSmsContentIds.isNotEmpty()) {
                for (chunk in toDeleteSmsContentIds.chunked(100)) {
                    val where = "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})"
                    try {
                        val count = context.contentResolver.delete(Telephony.Sms.CONTENT_URI, where, null)
                        deletedCount += count
                    } catch (e: Exception) {
                        Timber.w(e, "Bulk delete SMS failed, falling back to single delete")
                        for (id in chunk) {
                            try {
                                val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
                                deletedCount += context.contentResolver.delete(uri, null, null)
                            } catch (singleE: Exception) {
                                Timber.w(singleE)
                            }
                        }
                    }
                }
            }

            // 2. Delete system MMS duplicates
            if (toDeleteMmsContentIds.isNotEmpty()) {
                for (id in toDeleteMmsContentIds) {
                    try {
                        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
                        val count = context.contentResolver.delete(uri, null, null)
                        deletedCount += count
                    } catch (e: Exception) {
                        Timber.w(e, "Delete MMS failed for id $id")
                    }
                }
            }

            // 3. Delete from Realm database directly
            if (toDeleteRealmIds.isNotEmpty()) {
                val r = Realm.getDefaultInstance()
                r.executeTransaction { bgRealm ->
                    for (realmId in toDeleteRealmIds) {
                        bgRealm.where(Message::class.java).equalTo("id", realmId).findFirst()?.deleteFromRealm()
                    }
                }
                r.close()
            }

            // 4. Trigger sync to refresh conversation snippets and lastMessages
            if (deletedCount > 0 || toDeleteRealmIds.isNotEmpty()) {
                syncRepo.syncMessages()
            }

            maxOf(deletedCount, toDeleteRealmIds.size)
        }.subscribeOn(Schedulers.io())
    }

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}