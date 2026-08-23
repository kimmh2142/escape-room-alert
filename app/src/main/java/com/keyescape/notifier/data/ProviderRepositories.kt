package com.keyescape.notifier.data

import android.text.Html
import android.util.Log
import com.keyescape.notifier.BuildConfig
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

data class ProviderCalendar(val dates: Set<String>)

interface EscapeRoomProviderRepository {
    val provider: Provider
    suspend fun themes(branch: Branch): Result<List<ThemeDto>>
    suspend fun calendar(branch: Branch, theme: ThemeDto, month: YearMonth): Result<ProviderCalendar>
    suspend fun times(branch: Branch, theme: ThemeDto, date: String): Result<List<TimeSlotDto>>
    fun reservationUrl(branch: Branch, theme: ThemeDto): String
}

@Singleton class KeyescapeProviderRepository @Inject constructor(private val source: KeyescapeRepository) : EscapeRoomProviderRepository {
    override val provider = Provider.KEYESCAPE
    override suspend fun themes(branch: Branch) = source.themes(branch.id)
    override suspend fun calendar(branch: Branch, theme: ThemeDto, month: YearMonth): Result<ProviderCalendar> = source.themeDate(theme.infoNum).map { response ->
        val start = runCatching { LocalDate.parse(response.calendarData?.today) }.getOrDefault(LocalDate.now())
        val days = response.data?.doing?.takeIf { it > 0 } ?: theme.doing.coerceAtLeast(1)
        ProviderCalendar((0 until days).map { start.plusDays(it.toLong()).toString() }.toSet())
    }
    override suspend fun times(branch: Branch, theme: ThemeDto, date: String) = source.times(branch.id, theme.themeNum, date)
    override fun reservationUrl(branch: Branch, theme: ThemeDto) = source.reservationUrl(branch.id, theme.themeNum, theme.infoNum)
}

@Singleton class ZeroworldHtmlParser @Inject constructor() {
    fun parseThemes(html: String): Result<List<ThemeDto>> = runCatching {
        // \b avoids matching the surrounding `choice-themes__names` container.
        val regex = Regex("""fun_theme_select\s*\(\s*['\"]([0-9]+)['\"][^)]*\)[\s\S]*?class\s*=\s*['\"][^'\"]*\bchoice-themes__name\b[^'\"]*['\"][^>]*>\s*([^<]+)""")
        if (!html.contains("choice-themes")) error("PARSING_ERROR: 테마 HTML 구조가 아닙니다.")
        regex.findAll(html).map { match ->
            val name = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim()
            ThemeDto(infoNum = match.groupValues[1].toInt(), themeNum = match.groupValues[1].toInt(), name = name)
        }.toList().also { themes ->
            if (BuildConfig.DEBUG) {
                Log.d("ZeroworldApi", "Parsed zeroworld themes count=${themes.size}")
                themes.forEach { Log.d("ZeroworldApi", "themeId=${it.themeNum}, themeName=${it.name}") }
            }
        }
    }
    fun parseCalendar(html: String): Result<ProviderCalendar> = runCatching {
        val dates = Regex("fun_days_select\\('([0-9]{4}-[0-9]{2}-[0-9]{2})'").findAll(html).map { it.groupValues[1] }.toSet()
        if (dates.isEmpty()) error("제로월드 달력 HTML을 해석할 수 없습니다.")
        ProviderCalendar(dates)
    }
    fun parseTimeSlots(html: String): Result<List<TimeSlotDto>> = runCatching {
        if (html.trim() == "9999" || html.isBlank()) error("제로월드 시간표 응답 오류")
        val regex = Regex("<a\\s+class=\"choice-time__time([^\"]*)\"([^>]*)>([^<]+)</a>")
        regex.findAll(html).map { match ->
            val unavailable = match.groupValues[1].contains("disable")
            val time = match.groupValues[3].trim()
            if (!Regex("[0-2][0-9]:[0-5][0-9]").matches(time)) error("잘못된 시간표")
            val parts = time.split(":")
            TimeSlotDto(hh = parts[0], mm = parts[1], enable = if (unavailable) "N" else "Y")
        }.toList().takeIf { it.isNotEmpty() } ?: error("제로월드 시간표 HTML을 해석할 수 없습니다.")
    }
}

@Singleton class ZeroworldRepository @Inject constructor(private val api: ZeroworldApiService, private val parser: ZeroworldHtmlParser) : EscapeRoomProviderRepository {
    override val provider = Provider.ZEROWORLD
    private fun group(branch: Branch) = branch.providerMetadata.ifBlank { error("제로월드 예약 그룹 정보가 없습니다.") }
    override suspend fun themes(branch: Branch): Result<List<ThemeDto>> {
        val fields = mapOf("act" to "theme_list", "zizum_num" to branch.id.toString(), "rev_days" to LocalDate.now().toString(), "theme_num" to "", "s_subj" to group(branch))
        return request("theme", fields).fold({ html ->
            parser.parseThemes(html).onSuccess { themes ->
                debug("theme parse: result=${if (themes.isEmpty()) "NO_THEMES" else "SUCCESS"}, count=${themes.size}")
            }.onFailure { debug("theme parse: PARSING_ERROR ${it.message}") }
        }, { Result.failure(it) })
    }
    override suspend fun calendar(branch: Branch, theme: ThemeDto, month: YearMonth) = request("calendar", mapOf("act" to "calendar", "zizum_num" to branch.id.toString(), "rev_days" to month.atDay(1).toString(), "year" to month.year.toString(), "month" to month.monthValue.toString(), "s_subj" to group(branch))).fold({ parser.parseCalendar(it) }, { Result.failure(it) })
    override suspend fun times(branch: Branch, theme: ThemeDto, date: String) = request("time", mapOf("act" to "theme_time_list", "zizum_num" to branch.id.toString(), "rev_days" to date, "theme_num" to theme.themeNum.toString())).fold({ parser.parseTimeSlots(it) }, { Result.failure(it) })
    override fun reservationUrl(branch: Branch, theme: ThemeDto) = "https://zeroworldkorea.com/layout/res/home.php?go=rev.make&s_subj=${group(branch)}&zizum_num=${branch.id}&theme_num=${theme.themeNum}"

    private suspend fun request(kind: String, fields: Map<String, String>): Result<String> = runCatching {
        debug("Zeroworld $kind request: ${fields.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        val response = api.fragment(fields)
        val body = (if (response.isSuccessful) response.body() else response.errorBody())?.string().orEmpty()
        val preview = body.replace(Regex("\\s+"), " ").take(240)
        val contentType = response.headers()["Content-Type"].orEmpty()
        debug("Zeroworld $kind response: status=${response.code()}, contentType=$contentType, length=${body.length}, is9999=${body.trim() == "9999"}, isBlank=${body.isBlank()}, bodyPreview=$preview")
        if (kind == "theme") {
            val nameStart = body.indexOf("choice-themes__name\">")
            val rawThemePreview = if (nameStart >= 0) body.substring(nameStart, (nameStart + 180).coerceAtMost(body.length)).replace(Regex("\\s+"), " ") else "not found"
            debug("Zeroworld raw theme preview: $rawThemePreview")
        }
        when {
            !response.isSuccessful -> error("HTTP_ERROR: ${response.code()}")
            body.isBlank() -> error("EMPTY_RESPONSE")
            body.trim() == "9999" -> error("SERVER_ERROR_9999")
            else -> body
        }
    }.onFailure { error ->
        val kindLabel = if (error.message?.startsWith("HTTP_ERROR") == true || error.message == "EMPTY_RESPONSE" || error.message == "SERVER_ERROR_9999") error.message else "NETWORK_ERROR: ${error.message}"
        debug("$kind failure: $kindLabel")
    }

    private fun debug(message: String) { if (BuildConfig.DEBUG) Log.d("ZeroworldApi", message) }
}

@Singleton class ProviderRepositoryResolver @Inject constructor(keyescape: KeyescapeProviderRepository, zeroworld: ZeroworldRepository) {
    private val repositories = listOf(keyescape, zeroworld).associateBy { it.provider }
    fun get(provider: Provider): EscapeRoomProviderRepository = repositories[provider] ?: error("지원하지 않는 매장입니다.")
}
