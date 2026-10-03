# PanVK kbase/CSF on Mali-G720 (MT6899): two bugs under Zink

Draft report for [wonderkast02/panvk-g720-kbase-csf](https://github.com/wonderkast02/panvk-g720-kbase-csf).

## Setup

- Device: POCO X8 Pro (Redmi Turbo 5), MediaTek MT6899, Mali-G720 MC8
  (`gpu_id 0xc8700010`, arch 12, `shader_present 0x550055`), kbase CSF uAPI 1.30,
  Android 16, kernel 6.6.89.
- Driver: `g720-development` @ `ca16389`, built for **aarch64 glibc** (Arch Linux ARM sysroot,
  `-Dplatforms=x11,wayland -Dpanfrost-kmds=kbase,panthor,panfrost`), plus one patch that reports
  the kbase node's `st_rdev` as `drm.render_rdev` and `drm.primary_rdev` on non-Android builds.
  Build script: DroidDeck `tools/panvk/build-panvk-kbase.sh`.
- Environment: DroidDeck (Steam client under gamescope's nested Wayland backend, inside proot).
  The bugs reproduce outside proot too (run directly with the runtime's `ld-linux`).

## What works

- `vulkaninfo`, `vkcube` (600 frames, Wayland WSI to gamescope), `vkgears` (~355 fps for 60 s).
- `glxgears` on Zink **with Kopper** (`LIBGL_KOPPER_DRI2=true`), X11 through Xwayland:
  ~275 fps for 60 s, RSS steady at 118 MB, no queue timeouts.
- gamescope (Vulkan compositing) and the Steam client up to the Big Picture library.

## Bug 1: fragment subqueue waits forever on the vertex/tiler sync object

`kbase: timeout on subqueue N ... stream progress ...` followed by the client hanging, or
`zink: DEVICE LOST!` under Zink. With `PANVK_DEBUG=kbase_diag` the stuck job is the fragment
subqueue inside `wait_finish_tiling()`:

```
SYNC_ADD32 ... progress 0x2, 0x4 done
LOAD_MULTIPLE r66:r67, [d122]          ; subqueue ctx syncobjs
ADD_IMMEDIATE64 d68, d116, #1
SYNC_WAIT64gt.inherit d66, d68         ; <- never satisfied (progress stops at 0x6)
```

At timeout the vertex/tiler subqueue has completed all its ring jobs (`seqno 58, target 58`) but
its sync object reads 112 (= 56 frames x 2) while the fragment subqueue's view has moved on, so
the deferred `SYNC_ADD64` of one render pass on the VT subqueue appears never to land.

- mcghjbcg's multi-GPU fork (851a474) hits it after ~50 presented frames of `vkcube`, every run.
- `ca16389` does not hit it with `vkcube`/`vkgears`/`glxgears`, but does with the Steam client's
  CEF GPU process on Zink when Kopper is enabled (first frame never arrives, `DEVICE LOST`).
- No kernel fault or GPU reset in `dmesg`; the queue simply stops.

## Bug 2: Zink without Kopper (`LIBGL_KOPPER_DISABLE=true`)

Valve's `steamwebhelper.sh` exports `LIBGL_KOPPER_DISABLE=true`, so CEF's GPU process (ANGLE on
GL on Zink on PanVK) presents through the DRI3 loader path:

- `glxgears` with `LIBGL_KOPPER_DISABLE=true` segfaults (SIGSEGV) at start.
- CEF's GPU process starts and draws the UI, but on input-driven animation its RSS grows from
  ~0.6 GB to 2.7-3.1 GB within ~15 s, almost all `RssFile` (kbase mappings), with ~55 open
  `/dev/mali0` fds and ~47 dma-buf fds; Android's lmkd then kills it (or the whole app).
- Plain `vkAllocateMemory`/`vkMapMemory`/`vkFreeMemory` churn (200 x 4 MB per memory type) and
  instance/device create/destroy loops do not leak: RSS and fd counts stay flat.
