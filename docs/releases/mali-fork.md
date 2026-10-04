# DroidDeck MediaTek / Mali fork: changelog

Experimental Arm Mali (MediaTek) support on top of DroidDeck 0.3.0. APKs and the PanVK driver are
published on the fork's [releases](https://github.com/emir34-34/DroidDeck/releases). Newest first.
Details and known gaps: [docs/development/mediatek.md](../development/mediatek.md).

## 2026-10-04 · PanVK kbase glibc r6 (APK unchanged, `91516fa`)

- **Fixes Steam's interface freezing on the first touches, and its memory blowing up to 3 GB.** The
  driver called a helper command stream through an address cut to 32 bits. On Android's Mali kernel
  driver GPU addresses are high, so the GPU jumped to an invalid address (a translation fault at
  `0xfffef000`) and every queue stopped. One-line fix in `tools/panvk/patches/0002`.
- **Removes r5's patches 0002 and 0003.** They were built on a wrong diagnosis (a queue deadlock)
  and only changed timing.
- Tested on a POCO X8 Pro (Mali-G720): 120 scroll/tap/back gestures in Big Picture over ~5 minutes,
  with no GPU fault and the interface process at 0.45-0.7 GB.

## 2026-10-04 · APK `91516fa`

- **GPU drivers page on a Mali:** the Auto/Manual pair cards and the "No drivers listed yet" list
  only ever applied to Adreno. On a Mali they are replaced by what to set instead: the imported
  PanVK as the runtime driver, and the display driver on Auto (the phone's own Mali driver).

Driver: unchanged (PanVK kbase glibc r5).

## 2026-10-03 · APK `a7a0942` + PanVK kbase glibc r5

First published build.

### App
- **Mali detection:** a Mali (MediaTek Dimensity/Helio) is recognised and named from the GL driver
  ("Mali-G720 MC8"). It is marked experimental instead of unsupported.
- **Display:** the screen is drawn with the phone's own Mali Vulkan driver. The compositor no longer
  requires dma-buf import extensions the driver may lack.
- **Linux runtime driver import:** accepts any glibc `libvulkan_*.so` (such as PanVK's
  `libvulkan_panfrost.so`), not only Turnip. On a Mali the first imported driver is selected.
- **Runtime:** `/dev/mali0` is presented as the session's GPU render node. The GPU clock is read from
  the Mali devfreq node for the performance overlay.
- **Clear errors:** starting Steam on a Mali with no Linux runtime driver opens a dialog pointing to
  GPU drivers, instead of a session that ends at once. The session-ended screen also says when
  gamescope found no GPU, or when the runtime still ran Turnip.

### Driver: PanVK kbase glibc r5
- PanVK for Android's Mali kernel driver (kbase/CSF), from
  [wonderkast02/panvk-g720-kbase-csf](https://github.com/wonderkast02/panvk-g720-kbase-csf) `ca16389`,
  built for the Linux runtime (aarch64 glibc) by `tools/panvk/build-panvk-kbase.sh`.
- `0001`: reports `/dev/mali0` as the DRM render and primary device, which gamescope needs.
- `0002`: cross-queue syncs reach queues in other groups (SYSTEM scope on kbase).
- `0003`: fixes the deadlock that froze Steam's interface and grew it to 3 GB on the first touch: the
  geometry queue may run at most 8 render passes ahead of the fragment queue.
- Tested on a POCO X8 Pro (MT6899, Mali-G720 MC8): the Steam client reaches the Big Picture library
  and scrolls.

### Known issues
- A GPU queue timeout can still freeze Steam's interface in longer use.
- Games (Proton/DXVK) are untested.
