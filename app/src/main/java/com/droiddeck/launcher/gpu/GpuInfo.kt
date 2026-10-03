package com.droiddeck.launcher.gpu

import android.os.Build
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * What GPU this is, in the terms the driver lists are sorted by. KGSL names the model
 * ("Adreno740v2", "Adreno825"); the family decides which Turnip builds run on it at all, and the
 * support level is what the app has actually been tested on (Adreno 725 and up) - an Adreno 610
 * may start, but it is not "supported" just because it is a Qualcomm chip.
 */
data class GpuInfo(
    /** "Adreno 740", or the vendor's own name when this is not an Adreno. */
    val name: String,
    /** The three-digit Adreno model (740, 825), or 0 when KGSL does not say. */
    val model: Int,
    val family: Family,
    /** The SoC as the device reports it ("SM8550", "QCS8550"), or "" when it does not. */
    val soc: String,
    /** Samsung's One UI on an 8 Gen 2: its Turnip needs the OneUI build, or frames tear and flicker. */
    val oneUi8Gen2: Boolean,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        /** Adreno 710/720/722: gen 7 cores cut down enough to need their own patches. */
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        /** An Adreno whose model KGSL does not give: treated as the newest family it could be. */
        ADRENO_UNKNOWN("Adreno"),
        /** Arm Mali (MediaTek Dimensity/Helio and others): experimental, no Turnip runs on it. */
        MALI("Arm Mali"),
        NOT_ADRENO("Not an Adreno GPU"),
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            family == Family.MALI -> Support.UNTESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    /** One line for the device card and the system check. */
    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when (family) {
                Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                Family.MALI -> "Experimental: Mali needs an imported Linux runtime driver (PanVK for kbase)"
                else -> "Below tested hardware (Adreno 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU"
        }

    companion object {
        fun detect(): GpuInfo {
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            val raw = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }.orEmpty() else ""
            val model = raw?.let { Regex("""(\d{3})""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            if (!adreno && DeviceSupport.mali()) return detectMali(soc)
            val family = familyOf(adreno, model)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = if (!adreno) Build.HARDWARE.ifBlank { "this GPU" } else if (model > 0) "Adreno $model" else "Adreno",
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
            )
        }

        /**
         * A Mali: kbase's gpuinfo names it ("Mali-G710 10 cores r0p0 0xA862"), where the kernel lets
         * the app read it - most retail kernels do not - else the GL driver's renderer string
         * ("Mali-G720 MC8"); otherwise the SoC stands in. model is the number after the G ("720").
         */
        private fun detectMali(soc: String): GpuInfo {
            val raw = listOf("/sys/class/misc/mali0/device/gpuinfo", "/sys/devices/platform/mali/gpuinfo")
                .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            val name = (raw ?: glRenderer())?.let { Regex("""Mali-[A-Z]?\d+( MC\d+)?""").find(it)?.value }
            val model = name?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() } ?: 0
            return GpuInfo(
                name = name ?: "Mali (${soc.ifEmpty { Build.HARDWARE.ifBlank { "unknown SoC" } }})",
                model = model, family = Family.MALI, soc = soc, oneUi8Gen2 = false,
            )
        }

        @Volatile private var renderer: String? = null
        @Volatile private var rendererRead = false

        /**
         * GL_RENDERER from a throwaway 1x1 pbuffer context, once per process: the one name for the
         * GPU an app may always read. Null when EGL refuses.
         */
        @Synchronized
        private fun glRenderer(): String? {
            if (rendererRead) return renderer
            rendererRead = true
            renderer = runCatching {
                val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching null
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                android.opengl.EGL14.eglChooseConfig(display, intArrayOf(
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                    android.opengl.EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
                val config = configs[0] ?: return@runCatching null
                val context = android.opengl.EGL14.eglCreateContext(display, config, android.opengl.EGL14.EGL_NO_CONTEXT,
                    intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
                val surface = android.opengl.EGL14.eglCreatePbufferSurface(display, config,
                    intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
                try {
                    if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)) null
                    else android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
                } finally {
                    android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
                    android.opengl.EGL14.eglDestroySurface(display, surface)
                    android.opengl.EGL14.eglDestroyContext(display, context)
                }
            }.getOrNull()
            return renderer
        }

        internal fun familyOf(adreno: Boolean, model: Int): Family = when {
            !adreno -> Family.NOT_ADRENO
            model == 0 -> Family.ADRENO_UNKNOWN
            model >= 800 -> Family.A8XX
            model in listOf(710, 720, 722) -> Family.A7XX_LOW
            model >= 700 -> Family.A7XX
            model >= 600 -> Family.A6XX
            else -> Family.ADRENO_UNKNOWN
        }
    }
}
