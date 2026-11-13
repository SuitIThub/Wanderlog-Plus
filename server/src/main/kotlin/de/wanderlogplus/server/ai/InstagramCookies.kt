package de.wanderlogplus.server.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isRegularFile

/**
 * Writes a Netscape cookies file with Instagram's `sessionid` (WLP_INSTAGRAM_SESSIONID) into [dir], readable only by
 * the server's user where the file system allows it; yt-dlp and gallery-dl read it with `--cookies`.
 */
fun writeSessionCookies(dir: Path, sessionId: String): Path {
    val id = sessionId.trim()
    require(id.isNotEmpty() && id.none { it == '\t' || it == '\n' || it == '\r' }) { "invalid Instagram session id" }
    Files.createDirectories(dir)
    val file = dir.resolve("instagram-cookies.txt")
    // Created empty and restricted first, then filled: the session never sits in a world-readable file.
    Files.deleteIfExists(file)
    try {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } catch (e: UnsupportedOperationException) {
        Files.createFile(file)
    }
    Files.writeString(file, "# Netscape HTTP Cookie File\n" + listOf(".instagram.com", "TRUE", "/", "TRUE", "2147483647", "sessionid", id).joinToString("\t") + "\n")
    return file
}

/**
 * The cookies file for the downloaders: [cookies] (WLP_INSTAGRAM_COOKIES) when it is a file; otherwise one written from
 * [sessionId] into [dataDir]; else none. A [cookies] setting that is no file is ignored with a [warn]ing (never its
 * value: it may be the session itself), so reels keep loading without a login.
 */
fun resolveInstagramCookies(cookies: Path?, sessionId: String?, dataDir: Path, warn: (String) -> Unit): Path? {
    if (cookies != null) {
        if (cookies.isRegularFile()) return cookies
        warn("WLP_INSTAGRAM_COOKIES is not a file and is ignored; for a bare session id use WLP_INSTAGRAM_SESSIONID")
    }
    return sessionId?.takeIf { it.isNotBlank() }?.let { writeSessionCookies(dataDir, it) }
}
