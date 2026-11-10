@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.plantroom.pocket.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Ink = Color(0xFF0C151B)
private val Steel = Color(0xFF17252E)
private val Raised = Color(0xFF20323C)
private val Line = Color(0xFF2C3D46)
private val Mist = Color(0xFFE9F0F3)
private val Muted = Color(0xFF9DAFB9)
private val Ice = Color(0xFFA4E2EB)
private val Warning = Color(0xFFFFCFAD)

@Composable
fun PlantroomScreen(state: UiState, actions: UiActions) {
    val scheme = darkColorScheme(primary = Ice, onPrimary = Ink, background = Ink, surface = Steel,
        onSurface = Mist, onBackground = Mist, surfaceVariant = Raised, onSurfaceVariant = Muted,
        outline = Line, error = Warning)
    MaterialTheme(colorScheme = scheme) {
        var setup by rememberSaveable { mutableStateOf(false) }
        var capture by rememberSaveable { mutableStateOf(false) }
        var buildings by rememberSaveable { mutableStateOf(false) }
        var filterSheet by rememberSaveable { mutableStateOf(false) }
        var inspector by remember { mutableStateOf<UiItem?>(null) }
        var library by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val filter = state.filters.kind?.name ?: "All"
        val keyboard = LocalSoftwareKeyboardController.current
        val locked = state.busy || state.recording
        val searching = !library && (state.isSearching || state.searchMillis != null)
        val showEvidence = library || searching
        val buildingName = buildingName(state.buildingId)
        val visible = state.items.filter { it.buildingId == state.buildingId && (filter == "All" || it.kind.name == filter) }
        val cover = state.items.firstOrNull { it.buildingId == state.buildingId && it.kind == UiKind.PHOTO }
        // Successful-result guidance is already beside the result filters. Keep progress/errors
        // here without repeating the same advisory in a second full-width notice.
        val showStatus = state.status.isNotBlank() && !state.status.startsWith("Closest matches") &&
            (library || state.busy || state.error != null || state.recording)
        LaunchedEffect(state.buildingId) { inspector = null }
        LaunchedEffect(library, searching, state.buildingId) { listState.scrollToItem(0) }
        fun navigate(toLibrary: Boolean) {
            actions.onStopAudio()
            actions.onQuery("")
            actions.onResetFilters()
            inspector = null
            library = toLibrary
            keyboard?.hide()
        }
        BackHandler(enabled = !locked && !setup && !capture && !buildings && !filterSheet && inspector == null && (library || searching)) {
            navigate(false)
        }

        Scaffold(
            containerColor = Ink,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(10.dp))
                    Text("Plantroom", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { setup = true }, modifier = Modifier.semantics { contentDescription = "Collection and model settings" }) {
                        Glyph("settings", tint = Muted)
                    }
                }
            },
            bottomBar = {
                Column(Modifier.background(Ink).navigationBarsPadding()) {
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { navigate(false) }, enabled = !locked, modifier = Modifier.weight(1f).testTag("plantroom.nav-search")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Glyph("search", tint = if (!library) Ice else Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(3.dp)); Text("Search", color = if (!library) Ice else Muted, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = { navigate(true) }, enabled = !locked, modifier = Modifier.weight(1f).testTag("plantroom.nav-library")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Glyph("library", tint = if (library) Ice else Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(3.dp)); Text("Library", color = if (library) Ice else Muted, fontSize = 11.sp)
                            }
                        }
                        Button(onClick = { capture = true }, enabled = !locked,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1.6f).height(48.dp)) {
                            Glyph("plus", tint = Ink, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Add evidence", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("plantroom.evidence-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().testTag("plantroom.building-selector").clickable(enabled = !locked) { buildings = true }.padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(state.buildingId, color = Ice, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(Steel, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 5.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(buildingName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp)); Glyph("down", Muted, Modifier.size(15.dp))
                        Spacer(Modifier.weight(1f))
                        Glyph("device", Muted, Modifier.size(13.dp)); Spacer(Modifier.width(5.dp))
                        Text("On device", color = Muted, fontSize = 11.sp)
                    }
                }
                item {
                    AnimatedVisibility(library && cover != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Box(Modifier.padding(top = 8.dp, bottom = 18.dp).fillMaxWidth().height(174.dp)) {
                            LocalPhoto(cover?.localPath.orEmpty(), "Collection photograph", Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .93f)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 16.dp)) {
                                Eyebrow("BUILDING LIBRARY", Ice)
                                Spacer(Modifier.height(4.dp))
                                Text("Inspection evidence", fontSize = 25.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
                                Text("${state.totalCount} sources stored on this phone", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (!library) item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(if (searching) 12.dp else 76.dp))
                        OutlinedTextField(value = state.query, onValueChange = actions.onQuery, enabled = !locked,
                            placeholder = { Text("Describe what you're looking for", color = Muted, fontSize = 14.sp) },
                            leadingIcon = { Glyph("search", Ice, Modifier.size(21.dp)) },
                            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { actions.onQuery("") }, enabled = !locked, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Glyph("close", Muted) } },
                            modifier = Modifier.fillMaxWidth().testTag("plantroom.query"), shape = RoundedCornerShape(16.dp), maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ice, unfocusedBorderColor = Line,
                                focusedContainerColor = Steel, unfocusedContainerColor = Steel),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (state.modelReady && !locked && state.query.isNotBlank()) { keyboard?.hide(); actions.onSearch() } }))
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = actions.onImageSearch, enabled = state.modelReady && !locked, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Glyph("image", if (state.modelReady && !locked) Ice else Muted, Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp)); Text("Search with a photo", fontSize = 12.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { keyboard?.hide(); actions.onSearch() },
                                enabled = state.modelReady && !locked && state.query.isNotBlank(), shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 19.dp), modifier = Modifier.height(43.dp).testTag("plantroom.submit-search")) {
                                Text("Search", fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(7.dp)); Glyph("arrow", Ink, Modifier.size(16.dp))
                            }
                        }
                        if (!state.modelReady) {
                            TextButton(onClick = { setup = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("Set up local search", color = Ice); Spacer(Modifier.width(8.dp)); Glyph("arrow", Ice, Modifier.size(15.dp))
                            }
                        } else if (state.indexedCount < state.totalCount && !locked && state.indexedCount == 0) {
                            TextButton(onClick = { navigate(true) }, contentPadding = PaddingValues(0.dp)) {
                                Text("Prepare your library for search", color = Ice)
                            }
                        }
                    }
                }
                if (library && state.modelReady && state.indexedCount < state.totalCount && !locked) item {
                    TextButton(onClick = actions.onIndex, modifier = Modifier.padding(horizontal = 22.dp).testTag("plantroom.index"), contentPadding = PaddingValues(0.dp)) {
                        Text("Index ${state.totalCount - state.indexedCount} remaining sources", color = Ice)
                        Spacer(Modifier.width(8.dp)); Glyph("arrow", Ice, Modifier.size(15.dp))
                    }
                }
                item {
                    AnimatedVisibility(state.busy || showStatus || state.error != null || state.recording) {
                        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp).animateContentSize()) {
                            if (state.recording) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(Warning, CircleShape)); Spacer(Modifier.width(8.dp))
                                    Text("Recording voice note", color = Warning, fontSize = 13.sp)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = actions.onRecordAudio) { Text("Stop & save", color = Ice) }
                                }
                            }
                            if (state.busy) {
                                if (state.progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Ice, trackColor = Line)
                                else LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Ice, trackColor = Line)
                                Spacer(Modifier.height(9.dp))
                                if (state.progress != null) TextButton(onClick = actions.onCancelIndex, contentPadding = PaddingValues(0.dp)) { Text("Pause indexing", color = Muted, fontSize = 12.sp) }
                            }
                            if (showStatus) Text(state.status, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            state.error?.let { error ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text(error, color = Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = actions.onDismissError, modifier = Modifier.semantics { contentDescription = "Dismiss error" }) { Glyph("close", Warning) }
                                }
                            }
                        }
                    }
                }
                if (showEvidence) item {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searching) "Search results" else "Your collection", fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp)
                        Spacer(Modifier.weight(1f))
                        if (searching && state.searchMillis != null) Text("${String.format(Locale.UK, "%.2f", state.searchMillis / 1000.0)} s", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        else Text("${state.indexedCount} indexed", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(5.dp))
                        IconButton(onClick = { keyboard?.hide(); filterSheet = true }, enabled = !locked,
                            modifier = Modifier.testTag("plantroom.open-filters").semantics { contentDescription = "Filter by asset and date" }) {
                            Box(contentAlignment = Alignment.Center) {
                                Glyph("settings", if (state.filters.metadataCount > 0) Ice else Muted, Modifier.size(21.dp))
                                if (state.filters.metadataCount > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).size(6.dp).background(Ice, CircleShape))
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("All" to "All sources", "PHOTO" to "Photos", "AUDIO" to "Voice notes", "REPORT" to "Reports").forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { actions.onKindFilter(if (key == "All") null else UiKind.valueOf(key)) }, enabled = !locked,
                                label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.filter.$key"),
                                shape = RoundedCornerShape(9.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Raised, selectedLabelColor = Ice),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == key, borderColor = Line, selectedBorderColor = Raised))
                        }
                    }
                    if (state.filters.metadataCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(state.filters.assetId?.let { if (it.isBlank()) "Untagged" else it },
                            state.filters.dateFrom.takeIf { it.isNotEmpty() }?.let { "From $it" },
                            state.filters.dateTo.takeIf { it.isNotEmpty() }?.let { "To $it" }).joinToString(" · "),
                            color = Ice, fontSize = 11.sp, modifier = Modifier.weight(1f), lineHeight = 17.sp)
                        TextButton(onClick = actions.onResetFilters, enabled = !locked, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.testTag("plantroom.clear-filters")) {
                            Text("Clear", fontSize = 11.sp)
                        }
                    }
                    if (searching && visible.isNotEmpty()) Text("Closest matches · inspect the source to confirm", Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp), color = Muted, fontSize = 11.sp)
                }
                if (showEvidence && visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 25.dp)) {
                        Glyph(if (searching) "search" else "library", Muted, Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(if (state.busy) "Working locally…" else if (state.filters.activeCount > 0) "No sources match these filters" else if (searching) "No sources to show" else if (state.totalCount == 0) "Your building library starts here" else "No ${filterLabel(filter).lowercase()} here yet",
                            fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(if (state.filters.activeCount > 0) "Change the asset or dates, or clear filters to see more sources." else if (searching) "Try another description or choose a different source type." else "Import a building pack, or add your own photos, reports and short recordings.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                        if (state.filters.activeCount > 0) TextButton(onClick = actions.onResetFilters, enabled = !locked, contentPadding = PaddingValues(0.dp)) { Text("Clear all filters") }
                        if (!searching && state.totalCount == 0) {
                            Spacer(Modifier.height(15.dp))
                            OutlinedButton(onClick = actions.onImportCorpus, enabled = !locked, shape = RoundedCornerShape(12.dp)) { Text("Import building pack") }
                        }
                    }
                } else if (showEvidence) items(visible, key = { it.id }) { item ->
                    EvidenceRow(item, searching, onClick = { inspector = item })
                }
                if (library && visible.isNotEmpty()) item {
                    Text("Original sources remain available on this phone.",
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp), color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }

        if (filterSheet) FilterSheet(state, actions, onClose = { filterSheet = false })

        if (buildings) ModalBottomSheet(onDismissRequest = { buildings = false }, containerColor = Steel) {
            SheetHeader("Building library", "Choose the building you're working in.")
            listOf("MC01", "CH02").forEach { id ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { actions.onBuilding(id); buildings = false }.padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Raised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph("building", Ice) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(buildingName(id), fontWeight = FontWeight.Medium, fontSize = 18.sp); Text(id, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    if (state.buildingId == id) Glyph("check", Ice)
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (capture) ModalBottomSheet(onDismissRequest = { capture = false }, containerColor = Steel) {
            SheetHeader("Add evidence", "Saved to $buildingName on this phone.")
            ActionRow("Take a photo", "Save a new observation to this building", "image", enabled = !locked) { capture = false; navigate(true); actions.onCapturePhoto() }
            ActionRow("Import files", "Photos, PDF reports and short recordings", "plus", enabled = !locked) { capture = false; navigate(true); actions.onImportFiles() }
            ActionRow(if (state.recording) "Stop & save recording" else "Record a voice note", "Capture an observation during your visit", "audio", enabled = !state.busy) { capture = false; navigate(true); actions.onRecordAudio() }
            ActionRow("Import a building pack", "Load a prepared collection from local storage", "library", enabled = !locked) { capture = false; navigate(true); actions.onImportCorpus() }
            Spacer(Modifier.height(26.dp))
        }

        if (setup) ModalBottomSheet(onDismissRequest = { setup = false }, containerColor = Steel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetHeader("On this phone", "Your collection and local search model.")
                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth()) {
                    Count("PHOTOS", state.photoCount, Modifier.weight(1f)); Count("VOICE NOTES", state.audioCount, Modifier.weight(1f)); Count("REPORTS", state.reportCount, Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                Column(Modifier.padding(24.dp)) {
                    Eyebrow("LOCAL SEARCH MODEL")
                    Spacer(Modifier.height(10.dp))
                    Text(state.modelLabel, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (state.modelReady) Ice else Muted, CircleShape)); Spacer(Modifier.width(8.dp))
                        Text(if (state.modelReady) "Ready on this phone" else "Import a model to enable search", color = if (state.modelReady) Ice else Muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = { setup = false; actions.onImportModel() }, enabled = !locked, shape = RoundedCornerShape(12.dp)) {
                        Text(if (state.modelReady) "Replace model" else "Import model file")
                    }
                    if (state.modelReady && state.indexedCount < state.totalCount && !locked) {
                        TextButton(onClick = { setup = false; actions.onIndex() }) { Text("Index remaining sources") }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                ActionRow("Import building pack", "Add a prepared collection from local storage", "library", enabled = !locked) { setup = false; actions.onImportCorpus() }
                Text("Search retrieves original evidence. It does not diagnose faults or generate maintenance instructions.", Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 35.dp), color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }

        inspector?.let { item ->
            ModalBottomSheet(onDismissRequest = { actions.onStopAudio(); inspector = null }, containerColor = Steel,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("plantroom.inspector")) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (item.kind == UiKind.PHOTO) LocalPhoto(item.localPath, item.title, Modifier.fillMaxWidth().height(285.dp))
                    Column(Modifier.padding(24.dp)) {
                        Eyebrow(item.kind.label.uppercase() + if (item.sourceLocation.isNotBlank()) " · ${item.sourceLocation}" else "")
                        Spacer(Modifier.height(12.dp))
                        Text(item.title, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
                        Spacer(Modifier.height(12.dp))
                        Text(listOf(buildingName(item.buildingId), item.assetId, item.date).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                        if (item.excerpt.isNotBlank()) {
                            Spacer(Modifier.height(22.dp))
                            Text(item.excerpt, fontSize = 15.sp, lineHeight = 24.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                        if (item.kind == UiKind.AUDIO) {
                            Button(onClick = { if (state.playingId == item.id) actions.onStopAudio() else actions.onPlayAudio(item) }, enabled = !state.recording,
                                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                                Glyph(if (state.playingId == item.id) "stop" else "play", Ink, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                                Text(if (state.playingId == item.id) "Stop recording playback" else "Play original recording")
                            }
                        } else Button(onClick = { actions.onOpenSource(item) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                            Text(if (item.kind == UiKind.REPORT) "Open original report" else "Open original photo"); Spacer(Modifier.width(9.dp)); Glyph("arrow", Ink, Modifier.size(18.dp))
                        }
                        if (item.synthetic) {
                            Spacer(Modifier.height(18.dp))
                            Text("FICTIONAL DEMO SOURCE", fontFamily = FontFamily.Monospace, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                            Text("Created for this demonstration. Not a real property record.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    var asset by remember { mutableStateOf(state.filters.assetId) }
    var from by remember { mutableStateOf(state.filters.dateFrom) }
    var to by remember { mutableStateOf(state.filters.dateTo) }
    var assetMenu by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val locked = state.busy || state.recording
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("plantroom.filter-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
            SheetHeader("Filter evidence", "Exact asset and record dates in ${buildingName(state.buildingId)}.")
            Column(Modifier.padding(horizontal = 24.dp)) {
                Eyebrow("ASSET")
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { assetMenu = true }, enabled = !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("plantroom.asset-picker"), shape = RoundedCornerShape(12.dp)) {
                        Text(asset?.ifBlank { "Without an asset tag" } ?: "All assets, including untagged", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Glyph("down", Ice, Modifier.size(17.dp))
                    }
                    DropdownMenu(expanded = assetMenu, onDismissRequest = { assetMenu = false }) {
                        DropdownMenuItem(text = { Text("All assets, including untagged") }, onClick = { asset = null; assetMenu = false; attempted = false })
                        state.availableAssetIds.forEach { id ->
                            DropdownMenuItem(text = { Text(id.ifBlank { "Without an asset tag" }) }, onClick = { asset = id; assetMenu = false; attempted = false }, modifier = Modifier.testTag("plantroom.asset-option.$id"))
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Eyebrow("RECORD DATE · INCLUSIVE")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = from, onValueChange = { from = it; attempted = false }, enabled = !locked, singleLine = true,
                        label = { Text("From") }, placeholder = { Text("YYYY-MM-DD", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.date-from"), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    OutlinedTextField(value = to, onValueChange = { to = it; attempted = false }, enabled = !locked, singleLine = true,
                        label = { Text("To") }, placeholder = { Text("YYYY-MM-DD", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.date-to"), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                }
                Spacer(Modifier.height(8.dp))
                Text("Leave either date blank for an open range.", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
                if (attempted && state.filterError != null) Text(state.filterError, color = Warning, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp).testTag("plantroom.filter-error"), lineHeight = 19.sp)
                Spacer(Modifier.height(22.dp))
                Button(onClick = { attempted = true; if (actions.onApplyFilters(state.filters.copy(assetId = asset, dateFrom = from, dateTo = to))) onClose() },
                    enabled = !locked, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("plantroom.apply-filters"), shape = RoundedCornerShape(14.dp)) { Text("Apply filters") }
                TextButton(onClick = { actions.onResetFilters(); onClose() }, enabled = !locked, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Reset all filters") }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun EvidenceRow(item: UiItem, searching: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("plantroom.source.${item.id}").clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp)) {
        if (item.kind == UiKind.PHOTO) {
            Box(Modifier.fillMaxWidth().height(if (searching) 160.dp else 164.dp).clip(RoundedCornerShape(13.dp))) {
                LocalPhoto(item.localPath, item.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .44f)), startY = 150f)))
                Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("image", Mist, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("PHOTO", color = Mist, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                }
                if (item.assetId.isNotBlank()) Text(item.assetId, color = Mist, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceMeta(item)
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).size(45.dp).background(Steel, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Glyph(if (item.kind == UiKind.AUDIO) "audio" else "report", Ice)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(item.kind.label.uppercase())
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.kind == UiKind.REPORT && item.excerpt.isNotBlank()) {
                        Spacer(Modifier.height(6.dp)); Text(item.excerpt, fontSize = 12.sp, color = Muted, maxLines = 2, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
                    }
                    SourceMeta(item)
                }
                Spacer(Modifier.width(7.dp)); Glyph(if (item.kind == UiKind.AUDIO) "play" else "arrow", Muted, Modifier.padding(top = 19.dp).size(17.dp))
            }
            Spacer(Modifier.height(18.dp)); HorizontalDivider(color = Line.copy(alpha = .65f))
        }
    }
}

@Composable
private fun SourceMeta(item: UiItem) {
    val text = listOf(item.date, item.assetId.takeIf { item.kind != UiKind.PHOTO }.orEmpty(), item.sourceLocation).filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotBlank()) Text(text, Modifier.padding(top = 5.dp), color = Muted, fontSize = 11.sp, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun LocalPhoto(path: String, description: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (path.isBlank() || !File(path).isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
                val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                val oriented = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (oriented !== raw) raw.recycle()
                oriented.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Raised), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Glyph("image", Muted, Modifier.size(28.dp).semantics { contentDescription = "Photo preview unavailable" })
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 14.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
        Spacer(Modifier.height(7.dp)); Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, if (enabled) Ice else Muted, Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, color = if (enabled) Mist else Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
        Spacer(Modifier.width(10.dp)); Glyph("arrow", Muted, Modifier.size(17.dp))
    }
}

@Composable
private fun Count(label: String, count: Int, modifier: Modifier) {
    Column(modifier) { Text(count.toString(), fontSize = 30.sp, fontWeight = FontWeight.Light, color = Mist); Spacer(Modifier.height(5.dp)); Eyebrow(label) }
}

@Composable
private fun Eyebrow(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = .8.sp)
}

private fun buildingName(id: String) = when (id) { "MC01" -> "Mercer Court"; "CH02" -> "Calder House"; else -> id }
private fun filterLabel(filter: String) = when (filter) { "PHOTO" -> "Photos"; "AUDIO" -> "Voice notes"; "REPORT" -> "Reports"; else -> "Sources" }

@Composable
private fun BrandMark() {
    Canvas(Modifier.size(25.dp)) {
        val s = size.width
        drawLine(Ice, Offset(s * .1f, s * .85f), Offset(s * .1f, s * .18f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Ice, Offset(s * .1f, s * .18f), Offset(s * .68f, s * .18f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Ice, Offset(s * .68f, s * .18f), Offset(s * .68f, s * .52f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Ice, Offset(s * .68f, s * .52f), Offset(s * .34f, s * .52f), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Ice, 2.dp.toPx(), Offset(s * .89f, s * .84f))
    }
}

@Composable
private fun Glyph(name: String, tint: Color = Ice, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = 1.7f * u
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(tint, point(x, y), Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = Stroke(stroke))
        when (name) {
            "search" -> { drawCircle(tint, 6.5f * u, point(10f, 10f), style = Stroke(stroke)); line(15f, 15f, 21f, 21f) }
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            "arrow" -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
            "down" -> { line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
            "device" -> { box(6f, 2f, 12f, 20f); line(10f, 18f, 14f, 18f) }
            "settings" -> { line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f); drawCircle(Ink, 3 * u, point(9f, 7f)); drawCircle(tint, 3 * u, point(9f, 7f), style = Stroke(stroke)); drawCircle(Ink, 3 * u, point(16f, 17f)); drawCircle(tint, 3 * u, point(16f, 17f), style = Stroke(stroke)) }
            "image" -> { box(3f, 3f, 18f, 18f); drawCircle(tint, 1.7f * u, point(15.5f, 8f)); line(3f, 17f, 9f, 11f); line(9f, 11f, 18f, 21f) }
            "library" -> { box(3f, 5f, 6f, 16f); box(12f, 5f, 8f, 16f); line(5f, 2f, 18f, 2f) }
            "report" -> { box(5f, 2f, 14f, 20f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f) }
            "audio" -> { listOf(7f, 14f, 20f, 12f, 6f).forEachIndexed { index, height -> line(4f + index * 4, 12f - height / 2, 4f + index * 4, 12f + height / 2) } }
            "play" -> { val path = Path().apply { moveTo(8 * u, 4 * u); lineTo(20 * u, 12 * u); lineTo(8 * u, 20 * u); close() }; drawPath(path, tint) }
            "stop" -> drawRoundRect(tint, point(5f, 5f), Size(14 * u, 14 * u), androidx.compose.ui.geometry.CornerRadius(2 * u))
            "building" -> { box(4f, 2f, 16f, 20f); line(9f, 7f, 9f, 9f); line(15f, 7f, 15f, 9f); line(9f, 13f, 9f, 15f); line(15f, 13f, 15f, 15f) }
        }
    }
}
