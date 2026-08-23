package com.keyescape.notifier.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface KeyescapeApiService {
    @GET("reservation.php") suspend fun reservationPage(): String

    @FormUrlEncoded @POST("controller/run_proc.php")
    suspend fun themes(@Field("t") type: String = "get_theme_info_list", @Field("zizum_num") branchId: Int): ThemeListResponse

    @FormUrlEncoded @POST("controller/run_proc.php")
    suspend fun themeDate(@Field("t") type: String = "get_theme_date", @Field("num") infoNum: Int): ThemeDateResponse

    @FormUrlEncoded @POST("controller/run_proc.php")
    suspend fun times(@Field("t") type: String = "get_theme_time", @Field("date") date: String, @Field("zizumNum") branchId: Int, @Field("themeNum") themeNum: Int): TimeResponse
}

@Serializable data class ThemeListResponse(val status: Boolean = false, val msg: String = "", val data: List<ThemeDto> = emptyList())
@Serializable data class ThemeDto(@SerialName("info_num") val infoNum: Int = 0, @SerialName("theme_num") val themeNum: Int = 0, @SerialName("zizum_num") val branchId: Int = 0, @SerialName("info_name") val name: String = "", val doing: Int = 0)
@Serializable data class ThemeDateResponse(val status: Boolean = false, val msg: String = "", val data: ThemeDetailDto? = null, val calendarData: CalendarDto? = null)
@Serializable data class ThemeDetailDto(val name: String = "", val doing: Int = 0)
@Serializable data class CalendarDto(val today: String = "")
@Serializable data class TimeResponse(val status: Boolean = false, val msg: String = "", val data: List<TimeSlotDto> = emptyList())
@Serializable data class TimeSlotDto(val num: Int = 0, val hh: String = "", val mm: String = "", val enable: String = "N", @SerialName("sale_txt") val saleText: String = "") {
    val displayTime get() = "$hh:$mm"
    val available get() = enable == "Y"
}
