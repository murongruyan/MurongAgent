package com.murong.agent.shizuku

import android.content.Context
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.util.Log

/** Entry point executed by `/system/bin/app_process` under UID 0. */
object RootAgentDisplayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val packageName = args.option("--package=") ?: error("Missing --package")
        val token = args.option("--token=") ?: error("Missing --token")
        exemptHiddenApis()
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val systemContext = systemContext()
        val commandService = ShizukuCommandUserService(systemContext)
        val extras = Bundle().apply {
            putBinder(RootAgentDisplayBrokerProvider.EXTRA_SERVICE, commandService.asBinder())
        }
        val authority = if (packageName == "com.murong.agent") {
            RootAgentDisplayBrokerProvider.AUTHORITY
        } else {
            "$packageName.root-agent-display"
        }
        val result = callExternalProvider(
            authority = authority,
            method = RootAgentDisplayBrokerProvider.METHOD_PUBLISH,
            argument = token,
            extras = extras,
        )
        check(result?.getBoolean(RootAgentDisplayBrokerProvider.RESULT_ACCEPTED) == true) {
            "Murong rejected the Root Agent Display Binder"
        }
        Log.i(TAG, "Root Agent Display Binder published from UID ${android.os.Process.myUid()}")
        Looper.loop()
    }

    /** Mirrors Android's `content` shell command: it does not require an ApplicationThread. */
    private fun callExternalProvider(
        authority: String,
        method: String,
        argument: String,
        extras: Bundle,
    ): Bundle? {
        val activityManagerClass = Class.forName("android.app.ActivityManager")
        val activityManager = activityManagerClass.getDeclaredMethod("getService").invoke(null)
        val externalToken = Binder()
        val acquire = activityManager.javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal"
        } ?: error("ActivityManager external provider API is unavailable")
        var stringIndex = 0
        val acquireArguments = acquire.parameterTypes.map { type ->
            when {
                type == String::class.java -> if (stringIndex++ == 0) authority else TAG
                type == Int::class.javaPrimitiveType -> 0
                IBinder::class.java.isAssignableFrom(type) -> externalToken
                else -> error("Unsupported getContentProviderExternal argument ${type.name}")
            }
        }.toTypedArray()
        val holder = acquire.invoke(activityManager, *acquireArguments)
            ?: error("Android did not return the Murong Root broker provider")
        val provider = holder.javaClass.getDeclaredField("provider").apply {
            isAccessible = true
        }.get(holder) as? IInterface ?: error("Murong Root broker returned no provider Binder")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CONTENT_PROVIDER_DESCRIPTOR)
            AttributionSource.Builder(android.os.Process.myUid()).build().writeToParcel(data, 0)
            data.writeString(authority)
            data.writeString(method)
            data.writeString(argument)
            data.writeBundle(extras)
            check(provider.asBinder().transact(CONTENT_PROVIDER_CALL_TRANSACTION, data, reply, 0)) {
                "Murong Root broker rejected its Binder transaction"
            }
            reply.readException()
            reply.readBundle(RootAgentDisplayMain::class.java.classLoader)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun systemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null)
        return activityThreadClass.getDeclaredMethod("getSystemContext")
            .invoke(activityThread) as Context
    }

    private fun Array<String>.option(prefix: String): String? =
        firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private const val TAG = "MurongRootDisplay"
    private const val CONTENT_PROVIDER_DESCRIPTOR = "android.content.IContentProvider"
    private const val CONTENT_PROVIDER_CALL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 20
}
