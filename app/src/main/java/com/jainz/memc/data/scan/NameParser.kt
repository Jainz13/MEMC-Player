package com.jainz.memc.data.scan

import com.jainz.memc.data.db.Kind

data class ParsedName(
    val kind: Int,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val tech: String,
)

/** Works out what a video is from its file and folder names, the way Nova and Kodi do. */
object NameParser {

    val VIDEO_EXT = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "flv",
        "mpg", "mpeg", "vob", "3gp", "ogv", "rmvb", "divx", "f4v", "asf",
    )
    val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt", "sub", "ttml")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isVideo(name: String) = extension(name) in VIDEO_EXT
    fun isSubtitle(name: String) = extension(name) in SUBTITLE_EXT

    private val EPISODE_PATTERNS = listOf(
        Regex("""(?i)\bS(\d{1,2})\s?-?\s?E(\d{1,3})(?:\s?-?\s?E?\d{1,3})?\b"""),
        Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b"""),
        Regex("""(?i)\bseason\s?(\d{1,2})\s?-?\s?episode\s?(\d{1,3})\b"""),
    )
    private val SEASON_FOLDER = Regex("""(?i)^(season|series|staffel|saison|temporada)\s?(\d{1,2})$|^s(\d{1,2})$""")
    private val BARE_EPISODE = Regex("""(?i)(?:^|\s-\s|\bep?\.?\s?|\bepisode\s?)(\d{1,3})(?:\s|$)""")
    private val YEAR = Regex("""(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)""")
    private val JUNK = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|blu\s?ray|bdrip|brrip|bdremux|web\s?dl|webrip|web|hdrip|dvdrip|dvdscr|hdtv|hdcam|x264|x265|h\s?264|h\s?265|hevc|avc|av1|hdr10\+?|hdr10plus|hdr|dv|dovi|sdr|remux|proper|repack|extended|unrated|uncut|imax|directors\s?cut|10bit|8bit|aac\d?|ac3|eac3|ddp?\d?|dts(?:-?hd|-?x)?|atmos|truehd|multi|dual|subs?|esub|nf|amzn|dsnp|hmax|atvp|yts|yify|rarbg|hindi|tamil|telugu|malayalam|arabic)\b"""
    )

    fun parse(fileName: String, folderPath: String): ParsedName {
        val base = fileName.substringBeforeLast('.')
        val tech = detectTech(base)
        val norm = normalize(base)
        val folders = folderPath.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }

        // ---- Episodes ----
        for (p in EPISODE_PATTERNS) {
            val m = p.find(norm) ?: continue
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            var (title, year) = titleAndYear(norm.substring(0, m.range.first))
            if (title.isBlank()) {
                val (t, y) = showFromFolders(folders)
                title = t
                year = year ?: y
            }
            return ParsedName(Kind.EPISODE, title, year, season, episode, tech)
        }

        // "Show/Season 2/05 - Name.mkv" style: season from the folder, episode from the name.
        val seasonFolder = folders.lastOrNull()?.let { SEASON_FOLDER.find(normalize(it)) }
        if (seasonFolder != null) {
            val season = (seasonFolder.groupValues[2].ifEmpty { seasonFolder.groupValues[3] }).toIntOrNull()
            val ep = BARE_EPISODE.find(norm)?.groupValues?.get(1)?.toIntOrNull()
            if (season != null && ep != null) {
                val (t, y) = showFromFolders(folders)
                return ParsedName(Kind.EPISODE, t, y, season, ep, tech)
            }
        }

        // ---- Movies ----
        var (title, year) = titleAndYear(norm)
        val folder = folders.lastOrNull()?.let { normalize(it) }
        if (folder != null && !isGenericFolder(folder)) {
            val (ft, fy) = titleAndYear(folder)
            when {
                title.isBlank() || isGenericName(title) -> { title = ft; year = year ?: fy }
                year == null && fy != null && similar(ft, title) -> year = fy
            }
        }
        return ParsedName(Kind.MOVIE, title.ifBlank { base }, year, null, null, tech)
    }

    private fun normalize(s: String): String = s
        .replace(Regex("""\[[^\]]*]"""), " ")
        .replace(Regex("""\{[^}]*}"""), " ")
        .replace('_', ' ')
        .replace(Regex("""(?<=[^\s.])\.(?=[^\s.])"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Title = everything before the year (or before the first release-group junk word). */
    private fun titleAndYear(s: String): Pair<String, Int?> {
        val years = YEAR.findAll(s).toList()
        // A year at the very start is usually part of the title ("2001 A Space Odyssey 1968").
        val yearMatch = years.lastOrNull { it.range.first > 0 }
        var title: String
        var year: Int? = null
        if (yearMatch != null) {
            title = s.substring(0, yearMatch.range.first)
            year = yearMatch.value.toInt()
        } else {
            val junk = JUNK.find(s)
            title = if (junk != null && junk.range.first > 0) s.substring(0, junk.range.first) else s
        }
        val junk = JUNK.find(title)
        if (junk != null && junk.range.first > 0) title = title.substring(0, junk.range.first)
        return cleanTitle(title) to year
    }

    private fun cleanTitle(s: String): String = s
        .replace(Regex("""[()]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '–', ':', ',', '.')
        .trim()

    private fun showFromFolders(folders: List<String>): Pair<String, Int?> {
        for (f in folders.asReversed()) {
            val n = normalize(f)
            if (SEASON_FOLDER.matches(n) || isGenericFolder(n)) continue
            return titleAndYear(n)
        }
        return "" to null
    }

    private val GENERIC_FOLDERS = setOf(
        "movies", "movie", "films", "film", "videos", "video", "tv", "tv shows", "shows", "series",
        "download", "downloads", "media", "complete", "public", "share", "telegram", "telegram video",
        "specials", "extras", "featurettes", "nas", "home", "multimedia",
    )

    private fun isGenericFolder(n: String) = n.lowercase() in GENERIC_FOLDERS
    private fun isGenericName(n: String) = n.lowercase().let {
        it.length <= 2 || it in setOf("movie", "video", "film", "sample", "trailer") || it.matches(Regex("""(cd|disc|part)\s?\d"""))
    }

    private fun similar(a: String, b: String): Boolean {
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        val y = b.lowercase().filter { it.isLetterOrDigit() }
        return x.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    fun detectTech(name: String): String {
        val n = name.lowercase()
        val tags = mutableListOf<String>()
        when {
            Regex("""2160p|\b4k\b|\buhd\b""").containsMatchIn(n) -> tags += "4K"
            n.contains("1080") -> tags += "1080p"
            n.contains("720p") -> tags += "720p"
        }
        when {
            Regex("""\b(dv|dovi)\b|dolby.?vision""").containsMatchIn(n) -> tags += "DV"
            Regex("""hdr10\+|hdr10plus|hdr10p\b""").containsMatchIn(n) -> tags += "HDR10+"
            Regex("""\bhdr(10)?\b""").containsMatchIn(n) -> tags += "HDR10"
            Regex("""\bhlg\b""").containsMatchIn(n) -> tags += "HLG"
        }
        when {
            Regex("""\bav1\b""").containsMatchIn(n) -> tags += "AV1"
            Regex("""x265|h.?265|hevc""").containsMatchIn(n) -> tags += "HEVC"
        }
        when {
            n.contains("atmos") -> tags += "ATMOS"
            n.contains("truehd") -> tags += "TrueHD"
            Regex("""dts.?x\b""").containsMatchIn(n) -> tags += "DTS:X"
            Regex("""dts""").containsMatchIn(n) -> tags += "DTS"
        }
        if (n.contains("remux")) tags += "REMUX"
        return tags.joinToString("|")
    }
}
