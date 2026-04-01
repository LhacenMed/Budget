package com.lhacenmed.budget.ui.page.status

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Page indices ──────────────────────────────────────────────────────────────
// Layout: page = appIndex * 2 + mediaIndex
// 0 = WA Images | 1 = WA Videos | 2 = Biz Images | 3 = Biz Videos
private const val PAGE_COUNT = 4
private fun pageOf(appIndex: Int, mediaIndex: Int) = appIndex * 2 + mediaIndex
private fun appIndexOf(page: Int)   = page / 2
private fun mediaIndexOf(page: Int) = page % 2

// ── Content ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun StatusContent(
    state:               StatusUiState,
    padding:             PaddingValues,
    onPermissionGranted: (Uri?) -> Unit,
    onSave:              (StatusItem) -> Unit,
    onItemClick:         (StatusItem) -> Unit,
    onRefresh:           (StatusSource) -> Unit,
    onShowSnackbar:      (String) -> Unit
) {
    LaunchedEffect(state.message) {
        state.message?.let { onShowSnackbar(it) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onPermissionGranted(uri) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            !state.hasPermission -> PermissionScreen {
                launcher.launch(StatusSaverRepository.ANDROID_MEDIA_ROOT_URI)
            }
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            else -> DualAppStatusPager(
                state       = state,
                onSave      = onSave,
                onItemClick = onItemClick,
                onRefresh   = onRefresh
            )
        }
    }
}

// ── Permission screen ─────────────────────────────────────────────────────────

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    val onSurface        = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary          = MaterialTheme.colorScheme.primary
    val outline          = MaterialTheme.colorScheme.outline

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector        = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier           = Modifier.size(64.dp),
            tint               = primary
        )
        Spacer(Modifier.height(20.dp))

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text       = "One-time Permission Required",
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = "Starting Android 11 (Android R), Status Saver can no longer " +
                    "access WhatsApp Statuses directly due to Android storage restrictions. " +
                    "These steps are only required once.",
            style     = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color     = onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ── Steps ─────────────────────────────────────────────────────────────
        Text(
            text       = "Follow these steps:",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        val steps = listOf(
            "Enable "       to "'Show Internal Storage'",
            "Select your phone's " to "Primary Storage",
            "Navigate to the "    to "Android › media",
            "Tap "                to "\"Use This Folder\""
        )
        val stepDetails = listOf(
            " if you cannot see your primary storage in the left drawer.",
            " from the left drawer of the file picker.",
            " folder — do not open it, just navigate to it.",
            " button to grant access. If the folder is already open, just allow access."
        )

        steps.forEachIndexed { index, (plain, bold) ->
            Row(
                modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    color    = primary,
                    shape    = MaterialTheme.shapes.small,
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text       = "${index + 1}",
                            style      = MaterialTheme.typography.labelSmall,
                            color      = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = buildAnnotatedString {
                        append(plain)
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = onSurface)) {
                            append(bold)
                        }
                        append(stepDetails[index])
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Note ──────────────────────────────────────────────────────────────
        Surface(
            color  = MaterialTheme.colorScheme.surfaceVariant,
            shape  = MaterialTheme.shapes.medium
        ) {
            Text(
                text      = "Note: If the Android/media folder is already shown when the picker opens, " +
                        "just tap \"Use This Folder\" straight away.",
                style     = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color     = outline,
                modifier  = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        Spacer(Modifier.height(28.dp))

        // ── CTA ───────────────────────────────────────────────────────────────
        Button(
            onClick  = onGrant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Grant Folder Permission")
        }
    }
}

// ── Dual-app pager ────────────────────────────────────────────────────────────

/**
 * Two stacked tab rows drive a single [HorizontalPager] of 4 pages:
 *   Row 1 — App selector:   WhatsApp | WhatsApp Business   ([PrimaryTabRow])
 *   Row 2 — Media type:     Images   | Videos              ([SecondaryTabRow])
 *
 * Page layout: page = appIndex * 2 + mediaIndex
 * This avoids nested pagers entirely, so there are no scroll-conflict issues.
 *
 * Each page is wrapped in a [PullToRefreshBox] that triggers a partial SAF
 * re-scan for only that page's app source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun DualAppStatusPager(
    state:       StatusUiState,
    onSave:      (StatusItem) -> Unit,
    onItemClick: (StatusItem) -> Unit,
    onRefresh:   (StatusSource) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope      = rememberCoroutineScope()

    val appIndex   = appIndexOf(pagerState.currentPage)
    val mediaIndex = mediaIndexOf(pagerState.currentPage)

    val currentStatuses = if (appIndex == 0) state.whatsapp else state.business

    Column(Modifier.fillMaxSize()) {

        // ── Row 1: App selector ───────────────────────────────────────────────
        PrimaryTabRow(selectedTabIndex = appIndex) {
            AppTab(
                selected = appIndex == 0,
                label    = "WhatsApp",
                onClick  = { scope.launch { pagerState.animateScrollToPage(pageOf(0, mediaIndex)) } }
            )
            AppTab(
                selected = appIndex == 1,
                label    = "Business",
                onClick  = { scope.launch { pagerState.animateScrollToPage(pageOf(1, mediaIndex)) } }
            )
        }

        // ── Row 2: Media type ─────────────────────────────────────────────────
        SecondaryTabRow(selectedTabIndex = mediaIndex) {
            Tab(
                selected = mediaIndex == 0,
                onClick  = { scope.launch { pagerState.animateScrollToPage(pageOf(appIndex, 0)) } },
                text     = { Text("Images (${currentStatuses.images.size})") }
            )
            Tab(
                selected = mediaIndex == 1,
                onClick  = { scope.launch { pagerState.animateScrollToPage(pageOf(appIndex, 1)) } },
                text     = { Text("Videos (${currentStatuses.videos.size})") }
            )
        }

        // ── Content pager ─────────────────────────────────────────────────────
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val source = if (appIndexOf(page) == 0) StatusSource.WHATSAPP else StatusSource.WHATSAPP_BUSINESS
            val isRefreshing = if (appIndexOf(page) == 0) state.isRefreshingWhatsapp
            else                        state.isRefreshingBusiness
            val items = when (page) {
                0    -> state.whatsapp.images
                1    -> state.whatsapp.videos
                2    -> state.business.images
                3    -> state.business.videos
                else -> emptyList()
            }
            val isVideos = mediaIndexOf(page) == 1
            val appLabel = if (appIndexOf(page) == 0) "WhatsApp" else "WhatsApp Business"

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh    = { onRefresh(source) },
                modifier     = Modifier.fillMaxSize()
            ) {
                if (items.isEmpty()) {
                    // Empty state must fill the box so pull-to-refresh is reachable
                    EmptyPage(appLabel = appLabel, isVideos = isVideos)
                } else {
                    StatusGrid(
                        items       = items,
                        savingUri   = state.savingUri,
                        onSave      = onSave,
                        onItemClick = onItemClick
                    )
                }
            }
        }
    }
}

// ── App tab ───────────────────────────────────────────────────────────────────

@Composable
private fun AppTab(
    selected: Boolean,
    label:    String,
    onClick:  () -> Unit
) {
    Tab(
        selected = selected,
        onClick  = onClick,
        text     = {
            Text(
                text       = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyPage(appLabel: String, isVideos: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text      = "No ${if (isVideos) "videos" else "images"} from $appLabel.\n" +
                    "View some statuses in the app first.",
            textAlign = TextAlign.Center,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            style     = MaterialTheme.typography.bodyMedium
        )
    }
}

// ── Status grid ───────────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun StatusGrid(
    items:       List<StatusItem>,
    savingUri:   Uri?,
    onSave:      (StatusItem) -> Unit,
    onItemClick: (StatusItem) -> Unit
) {
    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        modifier              = Modifier.fillMaxSize(),
        contentPadding        = PaddingValues(4.dp),
        verticalArrangement   = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            StatusItemCell(
                item     = item,
                isSaving = savingUri == item.uri,
                onSave   = { onSave(item) },
                onClick  = { onItemClick(item) }
            )
        }
    }
}

// ── Grid cell ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun StatusItemCell(
    item:     StatusItem,
    isSaving: Boolean,
    onSave:   () -> Unit,
    onClick:  () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (item.isVideo) {
            VideoThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())
            Icon(
                imageVector        = Icons.Default.PlayCircle,
                contentDescription = null,
                modifier           = Modifier.align(Alignment.Center).size(36.dp),
                tint               = Color.White.copy(alpha = 0.9f)
            )
        } else {
            AsyncImage(
                model              = item.uri,
                contentDescription = item.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    MaterialTheme.shapes.small
                )
        ) {
            if (isSaving) {
                LoadingIndicator(modifier = Modifier.size(28.dp).padding(4.dp))
            } else {
                IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Save",
                        modifier           = Modifier.size(18.dp),
                        tint               = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ── Video thumbnail ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun VideoThumbnail(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0)
                }
            } catch (_: Exception) { null }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap             = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = modifier
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        }
    }
}
