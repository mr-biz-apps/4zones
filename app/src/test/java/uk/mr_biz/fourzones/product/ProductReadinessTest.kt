package uk.mr_biz.fourzones.product

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class ProductReadinessTest {

    /**
     * The DELIBERATE product mapping for every production backend status. The
     * exhaustiveness test below fails visibly (not by ordinal, by enum value)
     * if a new PrivilegedBackendStatus appears without an entry here.
     */
    private val deliberateMapping = mapOf(
        PrivilegedBackendStatus.NOT_INSTALLED to ProductReadiness.SHIZUKU_NOT_INSTALLED,
        PrivilegedBackendStatus.BINDER_UNAVAILABLE to ProductReadiness.SHIZUKU_UNAVAILABLE,
        PrivilegedBackendStatus.BINDER_DIED to ProductReadiness.SHIZUKU_UNAVAILABLE,
        PrivilegedBackendStatus.PERMISSION_REQUIRED to ProductReadiness.SHIZUKU_PERMISSION_REQUIRED,
        PrivilegedBackendStatus.PERMISSION_DENIED to ProductReadiness.SHIZUKU_PERMISSION_REQUIRED,
        PrivilegedBackendStatus.UNSUPPORTED_SERVER to ProductReadiness.UNSUPPORTED_SHIZUKU,
        PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH to ProductReadiness.RESTART_REQUIRED,
        PrivilegedBackendStatus.CONNECTING to ProductReadiness.CONNECTING,
        PrivilegedBackendStatus.READY to ProductReadiness.READY,
    )

    /** Every backend input the mapper can receive: null + all enum values. */
    private val allBackendInputs: List<PrivilegedBackendStatus?> =
        listOf<PrivilegedBackendStatus?>(null) + PrivilegedBackendStatus.entries

    @Test
    fun `service disabled dominates null and every backend status including READY`() {
        allBackendInputs.forEach { status ->
            assertEquals(
                "serviceEnabled=false must dominate backendStatus=$status",
                ProductReadiness.SHORTCUT_SERVICE_DISABLED,
                productReadiness(serviceEnabled = false, backendStatus = status),
            )
        }
    }

    @Test
    fun `null backend status maps to CONNECTING and never READY`() {
        assertEquals(
            ProductReadiness.CONNECTING,
            productReadiness(serviceEnabled = true, backendStatus = null),
        )
    }

    @Test
    fun `READY maps to READY only when the service is enabled`() {
        assertEquals(
            ProductReadiness.READY,
            productReadiness(serviceEnabled = true, backendStatus = PrivilegedBackendStatus.READY),
        )
        assertEquals(
            ProductReadiness.SHORTCUT_SERVICE_DISABLED,
            productReadiness(serviceEnabled = false, backendStatus = PrivilegedBackendStatus.READY),
        )
    }

    @Test
    fun `binder unavailable and binder died collapse to SHIZUKU_UNAVAILABLE`() {
        listOf(
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            PrivilegedBackendStatus.BINDER_DIED,
        ).forEach { status ->
            assertEquals(
                ProductReadiness.SHIZUKU_UNAVAILABLE,
                productReadiness(serviceEnabled = true, backendStatus = status),
            )
        }
    }

    @Test
    fun `permission required and denied collapse to SHIZUKU_PERMISSION_REQUIRED`() {
        listOf(
            PrivilegedBackendStatus.PERMISSION_REQUIRED,
            PrivilegedBackendStatus.PERMISSION_DENIED,
        ).forEach { status ->
            assertEquals(
                ProductReadiness.SHIZUKU_PERMISSION_REQUIRED,
                productReadiness(serviceEnabled = true, backendStatus = status),
            )
        }
    }

    @Test
    fun `unsupported server maps to UNSUPPORTED_SHIZUKU`() {
        assertEquals(
            ProductReadiness.UNSUPPORTED_SHIZUKU,
            productReadiness(
                serviceEnabled = true,
                backendStatus = PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            ),
        )
    }

    @Test
    fun `user service version mismatch maps to RESTART_REQUIRED`() {
        assertEquals(
            ProductReadiness.RESTART_REQUIRED,
            productReadiness(
                serviceEnabled = true,
                backendStatus = PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            ),
        )
    }

    @Test
    fun `not installed maps to SHIZUKU_NOT_INSTALLED`() {
        assertEquals(
            ProductReadiness.SHIZUKU_NOT_INSTALLED,
            productReadiness(
                serviceEnabled = true,
                backendStatus = PrivilegedBackendStatus.NOT_INSTALLED,
            ),
        )
    }

    @Test
    fun `connecting maps to CONNECTING`() {
        assertEquals(
            ProductReadiness.CONNECTING,
            productReadiness(
                serviceEnabled = true,
                backendStatus = PrivilegedBackendStatus.CONNECTING,
            ),
        )
    }

    @Test
    fun `every production backend status has a deliberate mapping`() {
        PrivilegedBackendStatus.entries.forEach { status ->
            val expected = deliberateMapping[status]
                ?: fail(
                    "New PrivilegedBackendStatus.$status has no deliberate " +
                        "ProductReadiness mapping — add one (and never default it to READY).",
                )
            assertEquals(
                "backendStatus=$status",
                expected,
                productReadiness(serviceEnabled = true, backendStatus = status),
            )
        }
    }

    @Test
    fun `only the backend READY status can produce product READY`() {
        allBackendInputs
            .filter { it != PrivilegedBackendStatus.READY }
            .forEach { status ->
                assertNotEquals(
                    "backendStatus=$status must not be product READY",
                    ProductReadiness.READY,
                    productReadiness(serviceEnabled = true, backendStatus = status),
                )
            }
    }
}
