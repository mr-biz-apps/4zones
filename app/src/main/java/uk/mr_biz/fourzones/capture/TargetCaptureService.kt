package uk.mr_biz.fourzones.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import uk.mr_biz.fourzones.BuildConfig
import uk.mr_biz.fourzones.R
import uk.mr_biz.fourzones.privileged.ShizukuPrivilegedBackend
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot diagnostic foreground service (shortService): gives the tester
 * ~5 seconds to switch to another app, performs exactly one fresh topology
 * read through the existing Phase 2B machinery, resolves the snap target
 * with the unchanged resolver, publishes the sanitized result, and stops.
 *
 * This is a Phase 2C1 hardware-validation seam, not a production background
 * runtime: it never stays running after the single capture, on any path
 * (success, failure, shortService timeout, or destruction).
 *
 * Lifecycle/teardown design:
 *  - [CaptureStartTracker] (pure, JVM-tested) owns start/controller
 *    lifetime: at most one concurrent capture, duplicate starts coalesced,
 *    active state cleared SYNCHRONOUSLY at completion so a start delivered
 *    before destruction begins a fresh capture, and stopSelf always uses
 *    the latest delivered startId so an older completed start can never
 *    stop a newer accepted one;
 *  - [completeCapture] is the ONE idempotent terminal path: publication is
 *    attempted first, and teardown (controller release, stopForeground,
 *    stopSelf) runs in a finally with each step contained — a broken
 *    diagnostic listener can never keep the foreground service alive;
 *  - ordinary RuntimeExceptions are contained at each Android boundary
 *    (promotion, controller construction/start, publication); fatal Errors
 *    are never caught.
 *
 * It owns its OWN backend and reader instances (application context only —
 * no Activity reference exists here), fully independent of MainActivity's.
 */
class TargetCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = CaptureStartTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldStartNewCapture = tracker.onStartDelivered(startId)

        val promoted = try {
            promoteToForeground()
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "foreground promotion failed: ${e.javaClass.simpleName}")
            false
        }
        if (!promoted) {
            if (shouldStartNewCapture) {
                completeCapture(
                    TargetCaptureResult.Failed("capture service could not enter foreground state"),
                )
            }
            // Else: an earlier capture is running and still governs teardown.
            return START_NOT_STICKY
        }

        if (!shouldStartNewCapture) {
            // A capture is genuinely running: coalesce this start. Its id is
            // already the latest, so completion stops this start as well.
            return START_NOT_STICKY
        }

        val started = try {
            startNewCapture(startId)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "capture start failed: ${e.javaClass.simpleName}")
            false
        }
        if (!started) {
            completeCapture(TargetCaptureResult.Failed("capture could not be started"))
        }
        return START_NOT_STICKY
    }

    // shortService timeout (API 34+): terminate explicitly.
    override fun onTimeout(startId: Int) {
        if (tracker.hasActive()) {
            completeCapture(
                TargetCaptureResult.Failed("shortService timeout reached before capture completed"),
            )
        } else {
            stopSelf(tracker.latestStartId)
        }
    }

    override fun onDestroy() {
        if (tracker.hasActive()) {
            // Publishes the explicit failure and releases backend/reader;
            // the redundant stop calls during destruction are harmless.
            completeCapture(
                TargetCaptureResult.Failed("capture service destroyed before completion"),
            )
        }
        super.onDestroy()
    }

    private fun startNewCapture(startId: Int) {
        var token: TargetCaptureController? = null
        val controller = TargetCaptureController(
            backend = ShizukuPrivilegedBackend(applicationContext),
            selfPackageName = packageName,
            captureDelayMillis = CAPTURE_DELAY_MILLIS,
            readinessTimeoutMillis = READINESS_TIMEOUT_MILLIS,
            scheduler = { delayMillis, action ->
                val runnable = Runnable { action() }
                mainHandler.postDelayed(runnable, delayMillis)
                CaptureCancellable { mainHandler.removeCallbacks(runnable) }
            },
            clock = object : CaptureClock {
                override fun wallClockMillis() = System.currentTimeMillis()
                override fun monotonicMillis() = SystemClock.elapsedRealtime()
            },
            sequencer = { captureSequence.incrementAndGet() },
            onResult = { result ->
                // Only the CURRENT capture may complete the service; results
                // from a superseded controller are inert and can never stop
                // or overwrite a newer accepted capture.
                val self = token
                if (self != null && tracker.isActive(self)) {
                    completeCapture(result)
                }
            },
        )
        token = controller
        tracker.activate(controller, startId)
        controller.start()
    }

    /**
     * THE single idempotent terminal/teardown path. Publication is
     * attempted first; teardown always follows, even if publication throws.
     */
    private fun completeCapture(result: TargetCaptureResult) {
        val controller = tracker.activeToken() as? TargetCaptureController
        // Synchronous clear: from here on, this capture's callbacks are
        // inert and a newly delivered start begins a fresh capture.
        val stopId = tracker.complete()
        try {
            safePublish(result)
        } finally {
            try {
                // Releases backend/reader if the controller had not already
                // terminated by itself; its result callback is inert now.
                controller?.cancel("capture service teardown")
            } catch (e: RuntimeException) {
                Log.e(TAG, "controller release failed: ${e.javaClass.simpleName}")
            }
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: RuntimeException) {
                Log.e(TAG, "stopForeground failed: ${e.javaClass.simpleName}")
            }
            // Latest delivered id: coalesced duplicates stop together; a
            // start accepted after this completion has a newer id and is
            // not stopped by this call.
            stopSelf(stopId)
        }
    }

    private fun safePublish(result: TargetCaptureResult) {
        try {
            TargetCaptureResultStore.publish(result)
            // One concise, sanitized diagnostic line for hardware testing;
            // never raw or filtered topology text.
            //
            // D-2 H10 release logging policy: GATED. describeForLog() carries
            // task ids, active desk root ids, foreground package names, display
            // ids and focused-task ids. Those identifiers are for local hardware
            // diagnosis only and must never be written to logcat by a build we
            // distribute, where they could reach a shared bug report. The
            // structured result is still published to the in-app diagnostics
            // store in every build; only the logcat line is debug-only.
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "one-shot target capture: ${result.describeForLog()}")
            }
        } catch (e: RuntimeException) {
            // The structured result is stored before listener delivery, so
            // a broken diagnostic listener loses only its notification —
            // and never the teardown that follows in the caller's finally.
            Log.e(TAG, "capture result publication failed: ${e.javaClass.simpleName}")
        }
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "4Zones diagnostics",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("4Zones target diagnostic")
            .setContentText("Performing a one-shot target diagnostic capture…")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "DexZonesCapture"
        const val CHANNEL_ID = "dexzones_diagnostics"
        const val NOTIFICATION_ID = 1001
        const val CAPTURE_DELAY_MILLIS = 5_000L
        const val READINESS_TIMEOUT_MILLIS = 10_000L

        /** Process-global capture ordinal; diagnostics only. */
        private val captureSequence = AtomicLong(0)
    }
}
