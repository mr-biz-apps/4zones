package uk.mr_biz.fourzones.capture

/**
 * Pure, main-thread state for the capture service's start/controller
 * lifetime, extracted so the duplicate-start and teardown semantics are
 * JVM-testable:
 *
 *  - at most one active capture; a start delivered while one is RUNNING is
 *    coalesced (its startId still becomes the latest, so teardown stops the
 *    newest delivered start too);
 *  - completion clears the active state SYNCHRONOUSLY via [complete], never
 *    waiting for onDestroy — so a start delivered after completion but
 *    before service destruction begins a fresh capture instead of being
 *    lost to a stale reference;
 *  - results are attributed by token identity ([isActive]): once a capture
 *    completes, callbacks from its (former) controller are inert and can
 *    never publish or stop a newer accepted capture;
 *  - [complete] returns the LATEST delivered startId for stopSelf(startId):
 *    an older completed start therefore cannot stop a newer accepted start
 *    (Android ignores stopSelf with a non-latest id), while coalesced
 *    duplicates stop together by design.
 */
class CaptureStartTracker {

    private var activeToken: Any? = null

    var activeStartId: Int = NO_START_ID
        private set

    var latestStartId: Int = NO_START_ID
        private set

    /**
     * Records a delivered start. Returns true when a new capture should
     * begin (none active); false when the delivery is coalesced into the
     * running capture.
     */
    fun onStartDelivered(startId: Int): Boolean {
        latestStartId = startId
        return activeToken == null
    }

    fun activate(token: Any, startId: Int) {
        activeToken = token
        activeStartId = startId
    }

    fun hasActive(): Boolean = activeToken != null

    /** Identity check: only the currently active capture's events count. */
    fun isActive(token: Any): Boolean = token === activeToken

    fun activeToken(): Any? = activeToken

    /**
     * Clears the active capture synchronously and returns the startId that
     * teardown must pass to stopSelf. Idempotent: a second call still
     * returns the latest delivered id and leaves the tracker inactive.
     */
    fun complete(): Int {
        activeToken = null
        activeStartId = NO_START_ID
        return latestStartId
    }

    companion object {
        const val NO_START_ID = -1
    }
}
