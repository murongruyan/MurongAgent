package com.murong.agent.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Root-only rendezvous. It exposes no records and rejects every non-UID-0 call. */
class RootAgentDisplayBrokerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 0) throw SecurityException("Root caller required")
        check(method == METHOD_PUBLISH) { "Unsupported Root Agent Display operation" }
        val token = requireNotNull(arg) { "Missing Root Agent Display token" }
        val binder = requireNotNull(extras?.getBinder(EXTRA_SERVICE)) {
            "Missing Root Agent Display Binder"
        }
        val service = IShizukuCommandService.Stub.asInterface(binder)
            ?: error("Invalid Root Agent Display Binder")
        check(service.remoteUid() == 0) { "Agent Display service is not running as Root" }
        return Bundle().apply {
            putBoolean(RESULT_ACCEPTED, RootAgentDisplayBrokerRegistry.publish(token, service))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.murong.agent.root-agent-display"
        const val METHOD_PUBLISH = "publish"
        const val EXTRA_SERVICE = "service"
        const val RESULT_ACCEPTED = "accepted"
    }
}

internal object RootAgentDisplayBrokerRegistry {
    private val lock = Any()
    private var expectedToken: String? = null
    private var ready = CountDownLatch(0)
    private var service: IShizukuCommandService? = null

    fun prepare(token: String) = synchronized(lock) {
        expectedToken = token
        service = null
        ready = CountDownLatch(1)
    }

    fun publish(token: String, published: IShizukuCommandService): Boolean = synchronized(lock) {
        if (token != expectedToken) return@synchronized false
        service = published
        ready.countDown()
        true
    }

    fun await(token: String, timeout: Long, unit: TimeUnit): IShizukuCommandService? {
        val latch = synchronized(lock) {
            if (token != expectedToken) return null
            ready
        }
        if (!latch.await(timeout, unit)) return null
        return synchronized(lock) { if (token == expectedToken) service else null }
    }

    fun cancel(token: String) = synchronized(lock) {
        if (token == expectedToken) {
            expectedToken = null
            service = null
            ready.countDown()
        }
    }
}
