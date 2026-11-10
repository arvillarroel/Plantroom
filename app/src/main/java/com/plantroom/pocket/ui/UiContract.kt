package com.plantroom.pocket.ui

enum class UiKind(val label: String) { PHOTO("Photo"), AUDIO("Voice note"), REPORT("Report") }

/** Null asset/kind means all. An empty asset ID explicitly selects untagged sources. */
data class UiFilters(
    val assetId: String? = null,
    val kind: UiKind? = null,
    val dateFrom: String = "",
    val dateTo: String = "",
) {
    val activeCount: Int get() = listOf(assetId != null, kind != null, dateFrom.isNotEmpty(), dateTo.isNotEmpty()).count { it }
    val metadataCount: Int get() = listOf(assetId != null, dateFrom.isNotEmpty(), dateTo.isNotEmpty()).count { it }
}

data class UiItem(
    val id: String,
    val title: String,
    val kind: UiKind,
    val buildingId: String,
    val assetId: String = "",
    val date: String = "",
    val localPath: String = "",
    val excerpt: String = "",
    val sourceLocation: String = "",
    val synthetic: Boolean = true,
    val score: Float? = null,
)

data class UiState(
    val buildingId: String = "MC01",
    val query: String = "",
    val items: List<UiItem> = emptyList(),
    val totalCount: Int = 0,
    val indexedCount: Int = 0,
    val photoCount: Int = 0,
    val audioCount: Int = 0,
    val reportCount: Int = 0,
    val modelReady: Boolean = false,
    val modelLabel: String = "EmbeddingGemma 2",
    val busy: Boolean = false,
    val progress: Float? = null,
    val status: String = "",
    val error: String? = null,
    val searchMillis: Long? = null,
    val isSearching: Boolean = false,
    val playingId: String? = null,
    val recording: Boolean = false,
    val filters: UiFilters = UiFilters(),
    val availableAssetIds: List<String> = emptyList(),
    val filterError: String? = null,
)

data class UiActions(
    val onBuilding: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onImportModel: () -> Unit = {},
    val onImportCorpus: () -> Unit = {},
    val onImportFiles: () -> Unit = {},
    val onImageSearch: () -> Unit = {},
    val onRecordAudio: () -> Unit = {},
    val onOpenSource: (UiItem) -> Unit = {},
    val onPlayAudio: (UiItem) -> Unit = {},
    val onStopAudio: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onIndex: () -> Unit = {},
    val onCancelIndex: () -> Unit = {},
    val onCapturePhoto: () -> Unit = {},
    val onApplyFilters: (UiFilters) -> Boolean = { true },
    val onKindFilter: (UiKind?) -> Unit = {},
    val onResetFilters: () -> Unit = {},
)
