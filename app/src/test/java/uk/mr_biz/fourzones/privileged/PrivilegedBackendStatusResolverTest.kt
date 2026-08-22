package uk.mr_biz.fourzones.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedBackendStatusResolverTest {

    private fun resolve(
        installed: Boolean = true,
        binderAlive: Boolean = true,
        binderEverDied: Boolean = false,
        serverSupported: Boolean = true,
        granted: Boolean = false,
        denied: Boolean = false,
    ) = PrivilegedBackendStatusResolver.resolve(
        managerInstalled = installed,
        binderAlive = binderAlive,
        binderEverDied = binderEverDied,
        serverSupported = serverSupported,
        permissionGranted = granted,
        permissionDeniedByUser = denied,
    )

    @Test
    fun `not installed wins over everything`() {
        assertEquals(
            PrivilegedBackendStatus.NOT_INSTALLED,
            resolve(
                installed = false,
                binderAlive = false,
                binderEverDied = true,
                serverSupported = false,
                granted = true,
            ),
        )
    }

    @Test
    fun `installed but binder never received is unavailable`() {
        assertEquals(
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            resolve(binderAlive = false),
        )
    }

    @Test
    fun `binder death is reported distinctly`() {
        assertEquals(
            PrivilegedBackendStatus.BINDER_DIED,
            resolve(binderAlive = false, binderEverDied = true),
        )
    }

    @Test
    fun `granted permission with live supported server is ready`() {
        assertEquals(PrivilegedBackendStatus.READY, resolve(granted = true))
    }

    @Test
    fun `denied permission is reported as denied`() {
        assertEquals(PrivilegedBackendStatus.PERMISSION_DENIED, resolve(denied = true))
    }

    @Test
    fun `live binder without permission answer requires permission`() {
        assertEquals(PrivilegedBackendStatus.PERMISSION_REQUIRED, resolve())
    }

    @Test
    fun `binder recovery after death returns to permission flow`() {
        // Once the binder is alive again, past death no longer matters.
        assertEquals(
            PrivilegedBackendStatus.READY,
            resolve(binderAlive = true, binderEverDied = true, granted = true),
        )
    }

    @Test
    fun `pre-v11 server is unsupported not permission-required`() {
        assertEquals(
            PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            resolve(serverSupported = false),
        )
    }

    @Test
    fun `ready is impossible on an unsupported server`() {
        // Even a (mis)reported granted permission cannot produce READY.
        assertEquals(
            PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            resolve(serverSupported = false, granted = true),
        )
    }

    @Test
    fun `unsupported server is not disguised as permission denial`() {
        assertEquals(
            PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            resolve(serverSupported = false, denied = true),
        )
    }

    @Test
    fun `binder state is reported before server support`() {
        // With no live binder there is no server to judge; binder states win.
        assertEquals(
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            resolve(binderAlive = false, serverSupported = false),
        )
        assertEquals(
            PrivilegedBackendStatus.BINDER_DIED,
            resolve(binderAlive = false, binderEverDied = true, serverSupported = false),
        )
    }
}
