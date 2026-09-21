package com.focusflow.enforcement

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import javax.swing.filechooser.FileSystemView

/**
 * AppIconExtractor
 *
 * On Windows: uses Shell32 SHGetFileInfo (SHGFI_LARGEICON, 32×32) via JNA
 * reflection so the code compiles on any platform.  Falls back to
 * FileSystemView (the original 16×16 shell-icon path) if Shell32 fails.
 *
 * On Linux: resolves the Icon= value from the matching .desktop file through
 * standard XDG icon locations, then falls back to FileSystemView.
 *
 * Results are cached so each path is resolved at most once per session.
 */
object AppIconExtractor {

    // ConcurrentHashMap forbids null values at the JVM level — storing a null result via
    // getOrPut/putIfAbsent throws NullPointerException even though the Kotlin type allows it.
    // Solution: keep the cache strictly non-null and track null-result paths separately so
    // we never re-attempt extraction for a path that already returned nothing.
    private val cache     = ConcurrentHashMap<String, ImageBitmap>()
    private val nullPaths = ConcurrentSkipListSet<String>()
    // isWindows imported from com.focusflow.Platform.kt
    // isLinux imported from com.focusflow.Platform.kt

    fun extractIcon(exePath: String, processName: String? = null): ImageBitmap? {
        val cacheKey = "$exePath\u0000${processName.orEmpty().lowercase()}"
        cache[cacheKey]?.let { return it }
        if (cacheKey in nullPaths) return null
        val bitmap = doExtract(exePath, processName)
        if (bitmap != null) cache[cacheKey] = bitmap else nullPaths.add(cacheKey)
        return bitmap
    }

    private fun doExtract(exePath: String, processName: String? = null): ImageBitmap? {
        val file = File(exePath)
        return if (isWindows) {
            if (!file.exists()) return null
            extractLargeIconWindows(exePath) ?: extractViaFileSystemView(file)
        } else if (isLinux) {
            extractXdgIcon(exePath, processName)
                ?: file.takeIf { it.exists() }?.let(::extractViaFileSystemView)
        } else {
            file.takeIf { it.exists() }?.let(::extractViaFileSystemView)
        }
    }

    private fun extractXdgIcon(exePath: String, processName: String?): ImageBitmap? {
        val desktopPath = InstalledAppsScanner.getDesktopFileForExecutable(exePath, processName)
            ?: return null
        val iconName = try {
            File(desktopPath).forEachLine { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("#") && trimmed.startsWith("Icon=")) {
                    throw IconValueFound(trimmed.removePrefix("Icon=").trim())
                }
            }
            null
        } catch (found: IconValueFound) {
            found.value.takeIf { it.isNotBlank() }
        } ?: return null

        val iconFile = findXdgIconFile(iconName) ?: return null
        return try {
            ImageIO.read(iconFile)?.toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private class IconValueFound(val value: String) : RuntimeException(null, null, false, false)

    private fun findXdgIconFile(iconName: String): File? {
        val direct = File(iconName)
        if (direct.isAbsolute && direct.isFile) return direct

        val home = System.getProperty("user.home", "")
        val roots = listOf(
            File("$home/.local/share/icons"),
            File("/usr/share/icons")
        )
        val sizes = listOf("256x256", "192x192", "128x128", "96x96", "64x64", "48x48", "32x32", "24x24", "16x16")
        val names = if (iconName.substringAfterLast('/').contains('.')) {
            listOf(iconName)
        } else {
            listOf("$iconName.png", "$iconName.jpg", "$iconName.jpeg")
        }

        for (root in roots) {
            if (!root.isDirectory) continue
            val themes = buildList {
                add(File(root, "hicolor"))
                root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach(::add)
            }.distinctBy { it.absolutePath }

            for (theme in themes) {
                for (size in sizes) {
                    for (name in names) {
                        File(theme, "$size/apps/$name").takeIf { it.isFile }?.let { return it }
                    }
                }
                for (name in names) {
                    File(theme, "apps/$name").takeIf { it.isFile }?.let { return it }
                }
            }
        }

        val pixmaps = File("/usr/share/pixmaps")
        for (name in names) {
            File(pixmaps, name).takeIf { it.isFile }?.let { return it }
        }
        for (name in names) {
            File("/usr/share/icons", name).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * Calls Shell32 via reflection so we never get compile-time errors on Linux.
     * Equivalent to: Shell32.INSTANCE.SHGetFileInfo(path, 0, shfi, size, SHGFI_ICON|SHGFI_LARGEICON)
     * then renders the HICON into a 32×32 BufferedImage.
     */
    private fun extractLargeIconWindows(exePath: String): ImageBitmap? {
        return try {
            // Use sun.awt.shell.ShellFolder — available on all Windows JDKs,
            // already used internally by FileSystemView on Windows.
            val shellFolderClass = Class.forName("sun.awt.shell.ShellFolder")
            val getShellFolder   = shellFolderClass.getMethod("getShellFolder", File::class.java)
            val sf               = getShellFolder.invoke(null, File(exePath))
            val getIcon          = shellFolderClass.getMethod("getIcon", Boolean::class.javaPrimitiveType)
            val icon             = getIcon.invoke(sf, true) as? javax.swing.Icon ?: return null

            val w  = icon.iconWidth.coerceAtLeast(16)
            val h  = icon.iconHeight.coerceAtLeast(16)
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g  = bi.createGraphics()
            try { icon.paintIcon(null, g, 0, 0) } finally { g.dispose() }
            bi.toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    /** Fallback: Swing FileSystemView — cross-platform, returns 16×16 shell icon. */
    private fun extractViaFileSystemView(file: File): ImageBitmap? {
        return try {
            val fsv  = FileSystemView.getFileSystemView()
            val icon = fsv.getSystemIcon(file) ?: return null
            val w    = icon.iconWidth.coerceAtLeast(16)
            val h    = icon.iconHeight.coerceAtLeast(16)
            val bi   = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g    = bi.createGraphics()
            try { icon.paintIcon(null, g, 0, 0) } finally { g.dispose() }
            bi.toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    /** Pre-warm icon for a path in the background (best-effort). */
    fun prefetch(exePath: String) {
        val cacheKey = "$exePath\u0000"
        if (cache.containsKey(cacheKey) || nullPaths.contains(cacheKey)) return
        val bitmap = doExtract(exePath)
        if (bitmap != null) cache[cacheKey] = bitmap else nullPaths.add(cacheKey)
    }
}
