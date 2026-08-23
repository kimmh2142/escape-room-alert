@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.keyescape.notifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyescape.notifier.data.*
import com.keyescape.notifier.monitoring.CheckMonitoringTargetsUseCase
import com.keyescape.notifier.monitoring.MonitoringCheckSummary
import com.keyescape.notifier.monitoring.MonitoringScheduler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitoringScheduler.ensureScheduled(this)
        setContent { EscapeNotifierTheme { EscapeNotifierApp() } }
    }
}

@Composable private fun EscapeNotifierTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = Color(0xFF365B66), secondary = Color(0xFF8A5A44)), content = content
)

private enum class Screen { WELCOME, HOME, SETUP, DETAIL, SETTINGS }

@Composable fun EscapeNotifierApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.WELCOME) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    when (screen) {
        Screen.WELCOME -> WelcomeScreen { screen = Screen.HOME }
        Screen.HOME -> HomeScreen({ editId = null; screen = Screen.SETUP }, { detailId = it; screen = Screen.DETAIL }, { screen = Screen.SETTINGS })
        Screen.SETUP -> SetupScreen({ editId = null; screen = Screen.HOME }, { editId = null; screen = Screen.HOME }, editId)
        Screen.DETAIL -> detailId?.let { DetailScreen(it, { screen = Screen.HOME }, { editId = it; screen = Screen.SETUP }) }
        Screen.SETTINGS -> SettingsScreen { screen = Screen.HOME }
    }
}

@Composable private fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStart() }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("방탈출 알리미", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp)); Text("원하는 방탈출에 빈자리가 생기면\n알림으로 바로 알려드릴게요.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp)); Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onStart()
        }, modifier = Modifier.fillMaxWidth()) { Text("시작하기") }
    }
}

data class CheckUiState(val running: Boolean = false, val resultMessage: String? = null)

@HiltViewModel class HomeViewModel @Inject constructor(private val repository: MonitoringRepository, private val checkTargets: CheckMonitoringTargetsUseCase) : ViewModel() {
    val groups = repository.allGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _checkUi = MutableStateFlow(CheckUiState()); val checkUi = _checkUi.asStateFlow()
    fun checkNow(group: MonitoringGroupWithSlots) = viewModelScope.launch { if (_checkUi.value.running) return@launch; _checkUi.value = CheckUiState(running = true); _checkUi.value = CheckUiState(resultMessage = summaryMessage(checkTargets.check(listOf(group)))) }
    fun consumeResult() { _checkUi.update { it.copy(resultMessage = null) } }
}

private fun summaryMessage(summary: MonitoringCheckSummary): String {
    if (summary.checkedSlots.isEmpty()) return if (summary.failedGroupCount > 0) "확인 실패 · 네트워크 상태를 확인해 주세요." else "확인할 모니터링이 없습니다."
    val headline = if (summary.availableSlots.isEmpty()) "확인 완료 · 현재 취소표 없음" else "확인 완료 · 취소표 발견!"
    val shown = if (summary.availableSlots.isEmpty()) summary.checkedSlots else summary.availableSlots
    val grouped = shown.groupBy { it.slot.date }.toSortedMap().entries.joinToString("\n") { (date, slots) ->
        val shortDate = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d")) }.getOrDefault(date)
        "$shortDate · ${slots.map { it.slot.desiredTime }.sorted().joinToString(", ")}" }
    return "$headline\n$grouped" + if (summary.failedGroupCount > 0) "\n일부 항목 확인 실패" else ""
}

@Composable private fun HomeScreen(onAdd: () -> Unit, onDetail: (String) -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val groups by vm.groups.collectAsState(); val checkUi by vm.checkUi.collectAsState(); val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(checkUi.resultMessage) { checkUi.resultMessage?.let { snackbar.showSnackbar(it); vm.consumeResult() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text("방탈출 알리미") }, actions = {
        TextButton(onClick = onSettings) { Text("설정") }
    }) }, floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text("모니터링 추가") } }) { padding ->
        if (groups.isEmpty()) Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("등록된 모니터링이 없습니다.", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp)); Button(onClick = onAdd) { Text("첫 모니터링 만들기") } }
        else LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(groups, key = { it.group.id }) { group -> MonitoringCard(group, checkUi.running, { onDetail(group.group.id) }) { vm.checkNow(group); scope.launch { snackbar.showSnackbar("지금 확인을 시작했습니다.") } } } }
    }
}

@Composable private fun MonitoringCard(groupWithSlots: MonitoringGroupWithSlots, checking: Boolean, onClick: () -> Unit, onCheck: () -> Unit) = Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Column(Modifier.padding(18.dp)) {
    val group = groupWithSlots.group; val latest = groupWithSlots.slots.mapNotNull { it.lastCheckedAt }.maxOrNull()
    Text(if (group.enabled) "● 모니터링 중" else "○ 일시정지", color = if (group.enabled) Color(0xFF257942) else Color.Gray); Spacer(Modifier.height(8.dp)); Text(group.branchName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(group.themeName); Spacer(Modifier.height(8.dp)); Text("${groupWithSlots.slots.groupBy { it.date }.size}일 · ${groupWithSlots.slots.size}개 시간"); Text(latest?.let { "마지막 확인: ${SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it))}" } ?: "아직 확인하지 않았습니다.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)); OutlinedButton(enabled = group.enabled && !checking, onClick = onCheck) { Text(if (checking) "확인 중" else "지금 확인") }
} }

data class SetupState(val region: String = "", val branch: Branch? = null, val themes: List<ThemeDto> = emptyList(), val theme: ThemeDto? = null, val editingGroupId: String? = null, val month: YearMonth = YearMonth.now(), val availableStart: LocalDate? = null, val availableEnd: LocalDate? = null, val selectedDates: Set<String> = emptySet(), val slotsByDate: Map<String, List<TimeSlotDto>> = emptyMap(), val selectedTimes: Map<String, Set<String>> = emptyMap(), val loadingDates: Set<String> = emptySet(), val loading: Boolean = false, val themeLoading: Boolean = false, val themesLoaded: Boolean = false, val themeError: String? = null, val error: String? = null)

@HiltViewModel class SetupViewModel @Inject constructor(private val catalog: BranchCatalog, private val api: KeyescapeRepository, private val monitoring: MonitoringRepository) : ViewModel() {
    private val _state = MutableStateFlow(SetupState()); val state = _state.asStateFlow(); val regions = catalog.regions(); fun branches(region: String) = catalog.branches.filter { it.region == region }
    fun chooseRegion(region: String) { _state.value = SetupState(region = region) }
    fun chooseBranch(branch: Branch) = viewModelScope.launch {
        _state.update { it.copy(branch = branch, theme = null, themes = emptyList(), selectedDates = emptySet(), slotsByDate = emptyMap(), selectedTimes = emptyMap(), loading = false, themeLoading = true, themesLoaded = false, themeError = null, error = null) }
        api.themes(branch.id).onSuccess { themes ->
            if (BuildConfig.DEBUG) Log.d("KeyescapeThemes", "Theme ViewModel state: branchId=${branch.id}, count=${themes.size}")
            _state.update { it.copy(themes = themes, themeLoading = false, themesLoaded = true) }
        }.onFailure { error ->
            _state.update { it.copy(themeLoading = false, themesLoaded = true, themeError = error.message ?: "테마를 불러오지 못했습니다.") }
        }
    }
    fun retryThemes() { _state.value.branch?.let(::chooseBranch) }
    fun chooseTheme(theme: ThemeDto) = viewModelScope.launch { _state.update { it.copy(theme = theme, selectedDates = emptySet(), slotsByDate = emptyMap(), selectedTimes = emptyMap(), loading = true, error = null) }; api.themeDate(theme.infoNum).onSuccess { response -> val today = runCatching { LocalDate.parse(response.calendarData?.today) }.getOrDefault(LocalDate.now()); val days = response.data?.doing?.takeIf { it > 0 } ?: theme.doing.coerceAtLeast(1); _state.update { it.copy(month = YearMonth.from(today), availableStart = today, availableEnd = today.plusDays((days - 1).toLong()), loading = false) } }.onFailure(::fail) }
    fun loadForEdit(groupId: String) = viewModelScope.launch {
        if (_state.value.editingGroupId == groupId) return@launch
        val saved = monitoring.group(groupId).first() ?: return@launch
        val group = saved.group
        val branch = catalog.branches.firstOrNull { it.id == group.branchId } ?: Branch(group.branchId, group.branchName, "기존 지점")
        val theme = ThemeDto(group.themeInfoNum, group.themeNum, group.branchId, group.themeName)
        val selectedTimes = saved.slots.groupBy { it.date }.mapValues { (_, slots) -> slots.map { it.desiredTime }.toSet() }
        _state.value = SetupState(region = branch.region, branch = branch, themes = listOf(theme), theme = theme, editingGroupId = groupId, selectedDates = selectedTimes.keys, selectedTimes = selectedTimes, loading = true)
        api.themeDate(theme.infoNum).onSuccess { response ->
            val today = runCatching { LocalDate.parse(response.calendarData?.today) }.getOrDefault(LocalDate.now())
            val days = response.data?.doing?.takeIf { it > 0 } ?: theme.doing.coerceAtLeast(1)
            _state.update { it.copy(month = YearMonth.from(today), availableStart = today, availableEnd = today.plusDays((days - 1).toLong()), loading = false) }
            selectedTimes.keys.forEach(::loadSlots)
        }.onFailure(::fail)
    }
    fun changeMonth(delta: Long) { _state.update { it.copy(month = it.month.plusMonths(delta)) } }
    fun toggleDate(date: LocalDate) { val s = _state.value; val key = date.toString(); if (!isSelectable(date, s)) return; if (key in s.selectedDates) _state.update { it.copy(selectedDates = it.selectedDates - key, selectedTimes = it.selectedTimes - key) } else { _state.update { it.copy(selectedDates = it.selectedDates + key) }; if (key !in s.slotsByDate) loadSlots(key) } }
    private fun loadSlots(date: String) = viewModelScope.launch { val s = _state.value; val branch = s.branch ?: return@launch; val theme = s.theme ?: return@launch; _state.update { it.copy(loadingDates = it.loadingDates + date) }; api.times(branch.id, theme.themeNum, date).onSuccess { slots -> _state.update { it.copy(slotsByDate = it.slotsByDate + (date to slots), loadingDates = it.loadingDates - date) } }.onFailure { error -> _state.update { it.copy(loadingDates = it.loadingDates - date, error = error.message ?: "시간표를 불러오지 못했습니다.") } } }
    fun toggleTime(date: String, time: String) { _state.update { state -> val map = state.selectedTimes.toMutableMap(); val values = map[date].orEmpty().toMutableSet(); if (!values.add(time)) values.remove(time); map[date] = values; state.copy(selectedTimes = map) } }
    suspend fun persist(): Boolean { val s = _state.value; val branch = s.branch ?: return false; val theme = s.theme ?: return false; if (s.selectedTimes.values.flatten().isEmpty()) return false; val group = MonitoringGroupEntity(s.editingGroupId ?: UUID.randomUUID().toString(), "KEYESCAPE", branch.id, branch.name, theme.themeNum, theme.infoNum, theme.name); val slots = s.selectedTimes.flatMap { (date, times) -> times.map { time -> MonitoringSlotEntity(UUID.randomUUID().toString(), group.id, date, time) } }; if (s.editingGroupId == null) monitoring.createOrAppend(group, slots) else monitoring.replaceSlots(group.id, slots); return true }
    private fun fail(error: Throwable) { _state.update { it.copy(loading = false, error = error.message ?: "네트워크 오류가 발생했습니다. 다시 시도해 주세요.") } }
}

private fun isSelectable(date: LocalDate, state: SetupState) = state.availableStart?.let { start -> date >= start && date <= (state.availableEnd ?: start) } == true

@Composable private fun SetupScreen(onBack: () -> Unit, onDone: () -> Unit, editGroupId: String?, vm: SetupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState(); val context = LocalContext.current; val scope = rememberCoroutineScope(); var availableSlot by remember { mutableStateOf<TimeSlotDto?>(null) }
    LaunchedEffect(editGroupId) { editGroupId?.let(vm::loadForEdit) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (editGroupId == null) "모니터링 등록" else "모니터링 조건 수정") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }) }) { padding -> LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("1. 지역", style = MaterialTheme.typography.titleMedium); ChoiceRow(vm.regions, state.region, vm::chooseRegion) }
        if (state.region.isNotBlank()) item { Text("2. 지점", style = MaterialTheme.typography.titleMedium); ChoiceRow(vm.branches(state.region).map { it.name }, state.branch?.name.orEmpty()) { vm.chooseBranch(vm.branches(state.region).first { branch -> branch.name == it }) } }
        if (state.branch != null) {
            item { Text("3. 테마", style = MaterialTheme.typography.titleMedium) }
            when {
                state.themeLoading -> item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("테마를 불러오는 중...") } }
                state.themeError != null -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("테마를 불러오지 못했습니다.\n다시 시도해주세요.", color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = vm::retryThemes) { Text("다시 시도") } } }
                state.themesLoaded && state.themes.isEmpty() -> item { Text("등록된 테마가 없습니다.") }
                else -> items(state.themes, key = { theme -> theme.infoNum }) { theme ->
                    FilterChip(selected = state.theme?.infoNum == theme.infoNum, onClick = { vm.chooseTheme(theme) }, label = { Text(theme.name) })
                }
            }
        }
        if (state.theme != null && state.availableStart != null) item { Text("4. 날짜 선택", style = MaterialTheme.typography.titleMedium); Text("여러 날짜를 선택할 수 있습니다.", style = MaterialTheme.typography.bodySmall); MonthCalendar(state, vm) }
        if (state.selectedDates.isNotEmpty()) item { Text("5. 날짜별 시간 선택", style = MaterialTheme.typography.titleMedium) }
        items(state.selectedDates.sorted(), key = { it }) { date -> TimeSelectionCard(date, state, vm) { availableSlot = it } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }; if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.selectedTimes.values.flatten().isNotEmpty()) item { Button(onClick = { scope.launch { if (vm.persist()) { MonitoringScheduler.ensureScheduled(context); onDone() } } }, modifier = Modifier.fillMaxWidth()) { Text(if (editGroupId == null) "선택한 시간 모니터링 등록" else "수정 저장") } }
    } }
    availableSlot?.let { slot -> AlertDialog(onDismissRequest = { availableSlot = null }, title = { Text("현재 예약 가능한 자리입니다.") }, text = { Text("${slot.displayTime}${slot.saleText}은 지금 예약할 수 있습니다.") }, confirmButton = { TextButton(onClick = { val s = state; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.keyescape.com/reservation1.php?zizum_num=${s.branch?.id}&theme_num=${s.theme?.themeNum}&theme_info_num=${s.theme?.infoNum}"))); availableSlot = null }) { Text("예약 사이트 열기") } }, dismissButton = { TextButton(onClick = { availableSlot = null }) { Text("닫기") } }) }
}

@Composable private fun ChoiceRow(options: List<String>, selected: String, onChoose: (String) -> Unit) = Column { options.forEach { option -> FilterChip(selected = selected == option, onClick = { onChoose(option) }, label = { Text(option) }); Spacer(Modifier.height(6.dp)) } }

@Composable private fun MonthCalendar(state: SetupState, vm: SetupViewModel) {
    val month = state.month
    val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.changeMonth(-1) }) { Text("‹") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy년 M월")), fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.changeMonth(1) }) { Text("›") }
            }
            Row(Modifier.fillMaxWidth()) {
                weekdays.forEach { day -> Text(day, Modifier.weight(1f), textAlign = TextAlign.Center, color = if (day == "일") Color(0xFFC34A4A) else Color.Unspecified) }
            }
            val leading = month.atDay(1).dayOfWeek.value % 7
            val cells = List<LocalDate?>(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) Spacer(Modifier.weight(1f).height(44.dp))
                        else Box(Modifier.weight(1f).height(44.dp)) {
                            CalendarDay(date, state.selectedDates.contains(date.toString()), isSelectable(date, state), Modifier.fillMaxSize()) { vm.toggleDate(date) }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f).height(44.dp)) }
                }
            }
        }
    }
}

@Composable private fun CalendarDay(date: LocalDate, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) { val color = if (!enabled) Color.Gray else if (date.dayOfWeek == DayOfWeek.SUNDAY) Color(0xFFC34A4A) else MaterialTheme.colorScheme.onSurface; TextButton(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = Color.LightGray)) { Text(date.dayOfMonth.toString(), modifier = if (selected) Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier, color = if (selected) Color.White else color) } }

@Composable private fun TimeSelectionCard(date: String, state: SetupState, vm: SetupViewModel, onAvailable: (TimeSlotDto) -> Unit) {
    val slots = state.slotsByDate[date]
    val label = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)) }.getOrDefault(date)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                date in state.loadingDates -> LinearProgressIndicator(Modifier.fillMaxWidth())
                slots == null -> Text("시간표를 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
                else -> slots.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { slot ->
                            val selected = slot.displayTime in state.selectedTimes[date].orEmpty()
                            FilterChip(selected = selected, onClick = { if (slot.available) onAvailable(slot) else vm.toggleTime(date, slot.displayTime) }, label = { Text(slot.displayTime + if (slot.available) " · 예약 가능" else slot.saleText) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@HiltViewModel class DetailViewModel @Inject constructor(private val repository: MonitoringRepository, private val checkTargets: CheckMonitoringTargetsUseCase) : ViewModel() { private val _result = MutableStateFlow<String?>(null); val result = _result.asStateFlow(); fun group(id: String) = repository.group(id); fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setGroupEnabled(id, enabled) }; fun delete(id: String, done: () -> Unit) = viewModelScope.launch { repository.deleteGroup(id); done() }; fun checkNow(group: MonitoringGroupWithSlots) = viewModelScope.launch { _result.value = summaryMessage(checkTargets.check(listOf(group))) }; fun consumeResult() { _result.value = null } }

@Composable private fun DetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val groupWithSlots by vm.group(id).collectAsState(initial = null)
    val result by vm.result.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(result) { result?.let { snackbar.showSnackbar(it); vm.consumeResult() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text("모니터링 상세") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }) }) { padding ->
        groupWithSlots?.let { groupValue ->
            Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val group = groupValue.group; val latest = groupValue.slots.mapNotNull { it.lastCheckedAt }.maxOrNull()
                Text(group.branchName, style = MaterialTheme.typography.headlineSmall)
                Text(group.themeName, style = MaterialTheme.typography.titleLarge)
                groupValue.slots.groupBy { it.date }.toSortedMap().forEach { (date, slots) -> Text("${runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M월 d일")) }.getOrDefault(date)}\n${slots.map { it.desiredTime }.sorted().joinToString(" · ")}") }
                Text(if (group.enabled) "모니터링 중" else "일시정지됨")
                Text(latest?.let { "마지막 확인: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it))}" } ?: "아직 확인하지 않았습니다.")
                Button(enabled = group.enabled, onClick = { vm.checkNow(groupValue) }) { Text("지금 확인") }
                Button(onClick = { vm.setEnabled(group.id, !group.enabled) }) { Text(if (group.enabled) "모니터링 일시정지" else "다시 시작") }
                OutlinedButton(onClick = { onEdit(group.id) }) { Text("조건 수정") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.keyescape.com/reservation1.php?zizum_num=${group.branchId}&theme_num=${group.themeNum}&theme_info_num=${group.themeInfoNum}"))) }) { Text("예약 사이트 열기") }
                TextButton(onClick = { vm.delete(group.id, onBack) }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("설정") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp)) {
            TextButton(onClick = { context.startActivity(Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.packageName)) }) { Text("알림 설정") }
            Text("방탈출 알리미 1.0.0")
        }
    }
}
