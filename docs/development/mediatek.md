# MediaTek (Arm Mali) support

Status: **experimental, not validated on hardware.** Adreno remains the only tested GPU family.

A DroidDeck session uses two Vulkan drivers (see `gpu/TurnipDriver.java` and
`gpu/LinuxVulkanDriver.java`):

| Half | Process | Adreno | Mali (MediaTek) |
|---|---|---|---|
| Display: the app's compositor puts frames on the panel | bionic, in the app | bundled Turnip through adrenotools | the system's Mali driver (`libvulkan.so`) |
| Runtime: Steam's UI (Zink), DXVK/VKD3D, gamescope, the desktop | glibc, under proot | the runtime's Turnip, or an imported `-Linux` Turnip | an **imported** glibc Mali driver; the runtime has none |

## What the app does on a Mali

- **Detection** (`core/DeviceSupport.kt`, `gpu/GpuInfo.kt`): a Mali is recognised by the kbase node
  `/dev/mali0`, `/sys/class/misc/mali0`, or the vendor's Mali userspace. It is reported as
  family `MALI`, support `UNTESTED`, and named from kbase's `gpuinfo` (`Mali-G710`) where the kernel
  lets the app read it. The install and session-start warnings say what is still needed instead of
  "not an Adreno".
- **Display driver**: Auto picks no bundled Turnip and the compositor loads the system Vulkan
  driver. The compositor no longer asks for the dma-buf import extensions it cannot have
  (`vk_present.c`): it enables only what the driver offers, so a driver without
  `VK_EXT_image_drm_format_modifier` still gets a device. Without them, clients fall back to
  `wl_shm` (copies, slower) instead of the session failing outright.
- **Runtime driver**: the Linux runtime driver import accepts any glibc `libvulkan_*.so`, not only
  `libvulkan_freedreno.so`, and keeps its name (`meta.json`'s `libraryName`). The session hands it to
  the Vulkan loader exactly as it does an imported Turnip. With no imported driver on a Mali the
  session log says so.
- **GPU node**: proot presents `/dev/mali0` as the session's DRM render node (named `panfrost`, as
  mainline names a Mali's), the way it presents KGSL on an Adreno, so gamescope offers linux-dmabuf.
  The runtime's `drm.c` preload emulates GEM handles for that node too (takes effect once the
  runtime is rebuilt with it).
- **Overlay stats**: the GPU clock is read from the Mali devfreq node (`/sys/class/devfreq/*mali*`)
  where KGSL is absent.

## What a Mali user needs

A glibc, AArch64 Mesa build of PanVK that talks to Android's **kbase** kernel interface (mainline
PanVK expects the panfrost/panthor DRM driver, which Android kernels do not have).
`tools/panvk/build-panvk-kbase.sh` builds one: the community kbase/CSF backend
([wonderkast02/panvk-g720-kbase-csf](https://github.com/wonderkast02/panvk-g720-kbase-csf),
`g720-development`) cross-compiled for the runtime's Arch Linux ARM glibc, plus
`tools/panvk/patches` (the kbase node's device number reported as the DRM render and primary
device, which is how the runtime presents `/dev/mali0` and what gamescope's nested backend needs).
Their own releases are bionic builds for Winlator and are refused by the import.

Verified on a POCO X8 Pro (MT6899, Mali-G720 MC8, Android 16): gamescope, Xwayland and the Steam
client (Big Picture sign-in) run on it with no GPU queue timeouts. The
[mcghjbcg multi-GPU fork](https://github.com/mcghjbcg/panvk-Android) (851a474) hangs there: its
fragment subqueue waits forever on the vertex/tiler sync object after ~50 presented frames
(`kbase: timeout on subqueue`), which froze Steam's update window and gamescope.

Import the zip under GPU drivers > Linux runtime driver; on a Mali the first one imported is
selected. It targets Valhall CSF GPUs: Mali-G610/G615/G710/G715/G720. Upstream notes that vkd3d
(DX12) is not supported and some DXVK games can hang the GPU.

## Known gaps

- No glibc Mali driver ships with the app or the runtime, and none is offered by the release
  checker; it has to be built with the script above and imported.
- Whether the system Mali driver exposes `VK_EXT_image_drm_format_modifier` depends on the DDK
  version; the compositor's log (`present: driver MISSING ...`) says which it lacks.
- GPU load for the overlay is not read on Mali yet; the clock-pin option is KGSL-only.
- Xclipse (Exynos 2200+) and PowerVR remain unsupported.
