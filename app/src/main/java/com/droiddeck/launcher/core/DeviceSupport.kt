package com.droiddeck.launcher.core

import android.os.Build
import java.io.File

/**
 * Whether this device can draw a Linux session at all.
 *
 * The runtime draws with Turnip, an Adreno driver. On Xclipse and PowerVR the compositor gets no
 * usable Vulkan device and a session comes up as sound over a black screen - a failure with nothing
 * in it to read. The app cannot fix that, so it says so before the download rather than after it.
 * Adreno is recognised by what only Qualcomm's stack has: the KGSL node, or the vendor's own Vulkan
 * driver at its usual path.
 *
 * Mali (MediaTek Dimensity/Helio, Exynos before Xclipse, Tensor) is experimental: the compositor
 * runs on the system's Mali Vulkan driver, and the runtime needs an imported glibc Mali driver
 * (PanVK built for the kbase kernel interface) - see docs/development/mediatek.md. Mali is
 * recognised by the kbase node every Mali kernel creates, or the vendor's Mali userspace.
 */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    /** The kbase device node of a Mali GPU; Android names it mali0 on every vendor kernel. */
    const val MALI_DEVICE = "/dev/mali0"

    @JvmStatic
    fun mali(): Boolean = !adreno() && (
        File(MALI_DEVICE).exists() || File("/sys/class/misc/mali0").exists() ||
            File("/vendor/lib64/hw/vulkan.mali.so").exists() || File("/vendor/lib64/egl/libGLES_mali.so").exists() ||
            File("/vendor/lib64/egl/mali/libGLES_mali.so").exists())

    /** A MediaTek SoC ("mt6983", "MT6989"), whatever its GPU: for the device card and the report. */
    fun mediatek(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        return soc.equals("Mediatek", ignoreCase = true) || Build.HARDWARE.lowercase().startsWith("mt")
    }

    /** A GPU a session can at least try to draw on: Adreno, or Mali through an imported driver. */
    fun canTry(): Boolean = adreno() || mali()

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { "this GPU" }
    }
}
