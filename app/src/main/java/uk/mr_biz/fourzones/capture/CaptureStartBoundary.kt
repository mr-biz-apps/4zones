package uk.mr_biz.fourzones.capture

/**
 * Containment boundary for starting the capture service from the UI.
 *
 * Ordinary [RuntimeException]s thrown by the framework start call (e.g.
 * ForegroundServiceStartNotAllowedException, SecurityException) become one
 * sanitized [TargetCaptureResult.Failed] published through the normal
 * result mechanism — the app never crashes and the UI shows the failure
 * instead of a stale/pending impression. Only the exception's class name is
 * exposed; no messages, stack traces, or privileged data.
 *
 * The publisher itself is untrusted here too: a [RuntimeException] thrown
 * during publication (e.g. a broken diagnostic listener) is contained —
 * exactly one publication attempt, no rethrow, no second failure about the
 * failed publication. With the default store publisher, lastResult is
 * written before listener delivery, so the sanitized failure remains
 * readable even when the listener throws.
 *
 * Fatal JVM [Error]s from either the start action or the publisher are NOT
 * caught.
 */
object CaptureStartBoundary {

    fun start(
        publish: (TargetCaptureResult) -> Unit = TargetCaptureResultStore::publish,
        startAction: () -> Unit,
    ) {
        try {
            startAction()
        } catch (e: RuntimeException) {
            val failure = TargetCaptureResult.Failed(
                "could not start the capture service (${e.javaClass.simpleName})",
            )
            try {
                publish(failure)
            } catch (publishFailure: RuntimeException) {
                // Contained: the startup boundary stays crash-safe even when
                // the diagnostic publisher is broken.
            }
        }
    }
}
