package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import uk.mr_biz.fourzones.snap.TopologyFetch
import uk.mr_biz.fourzones.snap.TopologySource

/**
 * Builds the shortcut transaction's [TopologySource] from a NON-QUEUING read.
 *
 * `SnapExecutionOrchestrator` fetches topology through this single source for
 * ALL of T1, T2 and T3, so wiring it to [readNow] (the backend's
 * "current-verified-service-now-or-fail" read) guarantees EVERY topology-read
 * admission in a shortcut transaction is non-queuing: if the privileged service
 * is unavailable at any of T1/T2/T3, the read fails immediately and the
 * transaction is never resumed after reconnect. The ordinary queueing
 * `readActivityTopology` is intentionally NOT used here.
 *
 * Reuses the existing parser; duplicates no topology logic.
 */
fun shortcutTopologySource(
    readNow: (onResult: (TopologyReadResult) -> Unit) -> Unit,
): TopologySource = TopologySource { onResult ->
    readNow { result ->
        onResult(
            when (result) {
                is TopologyReadResult.Success ->
                    TopologyFetch.Fetched(DesktopTopologyParser.parse(result.filteredDump))
                is TopologyReadResult.BackendUnavailable ->
                    TopologyFetch.Unavailable(result.status)
                is TopologyReadResult.CommandFailed ->
                    TopologyFetch.Failed(result.message)
            },
        )
    }
}
