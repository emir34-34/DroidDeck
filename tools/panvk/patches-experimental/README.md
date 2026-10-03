# Experimental PanVK patches (not applied by build-panvk-kbase.sh)

`0001-kbase-cross-subqueue-syncs-use-cmdbuf-scope.patch`: on kbase every PanVK subqueue is its
own CSG, and a sync update with `MALI_CS_SYNC_SCOPE_CSG` only wakes waiters in the same group.
`ca16389` already signals render-pass syncs with `cmdbuf->sync_scope` (SYSTEM on kbase), but the
compute-subqueue barrier, event set/reset and query availability still use CSG scope although
other subqueues (and the host) wait on them. This patch switches them to `cmdbuf->sync_scope`.

Tested on a Mali-G720 (2026-10-03): with it, the Steam client's interface came up with Kopper
kept (it did not before), but a vertex/tiler queue timeout still happened after ~2400 jobs and
the interface froze. So it is at most part of the fix.
