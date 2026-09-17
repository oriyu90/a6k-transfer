package jp.yuki.a6000transfer.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.yuki.a6000transfer.R
import jp.yuki.a6000transfer.data.DateGroup
import jp.yuki.a6000transfer.data.DlnaRepository
import jp.yuki.a6000transfer.data.MediaKind
import jp.yuki.a6000transfer.data.Photo
import jp.yuki.a6000transfer.data.TransferManager
import jp.yuki.a6000transfer.sony.CameraProfile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryScreen(ctx: Context, appState: AppState) {
    val scope = rememberCoroutineScope()
    val location by appState.location.collectAsState()
    val model by appState.cameraModel.collectAsState()
    val transferState by TransferManager.state.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<DateGroup>>(emptyList()) }
    var dateIdx by remember { mutableStateOf(0) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val doneIds = remember { mutableStateMapOf<String, Boolean>() }
    var progressText by remember { mutableStateOf("") }
    // null=すべて、PHOTO=写真のみ、VIDEO=動画のみ
    var filter by remember { mutableStateOf<MediaKind?>(null) }

    // サービス側で確定した転送済みをラベルへ反映
    LaunchedEffect(transferState.doneIds) {
        transferState.doneIds.forEach { doneIds[it] = true }
    }
    LaunchedEffect(transferState.finishedText) {
        val t = transferState.finishedText
        if (t != null) {
            progressText = t
            selected.clear()
            TransferManager.consumeFinished()
        }
    }

    val allPhotos = groups.getOrNull(dateIdx)?.photos ?: emptyList()
    val hasPhoto = allPhotos.any { it.kind == MediaKind.PHOTO }
    val hasVideo = allPhotos.any { it.kind == MediaKind.VIDEO }
    val photos = when (filter) {
        MediaKind.PHOTO -> allPhotos.filter { it.kind == MediaKind.PHOTO }
        MediaKind.VIDEO -> allPhotos.filter { it.kind == MediaKind.VIDEO }
        else -> allPhotos
    }
    val selectedPhotos = photos.filter { selected[it.id] == true }
    val transferring = transferState.running
    val progress = if (transferState.total > 0) {
        transferState.done.toFloat() / transferState.total
    } else {
        0f
    }

    fun load() {
        val loc = location ?: return
        if (loading) return
        loading = true
        status = ctx.getString(R.string.loading_list)
        scope.launch {
            try {
                val depth = CameraProfile.of(if (model == CameraProfile.AUTO) null else model).maxDepth
                val tree = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                    DlnaRepository.loadTree(loc, depth)
                }
                if (tree == null) {
                    status = ctx.getString(R.string.timeout)
                } else if (tree.isEmpty()) {
                    status = ctx.getString(R.string.no_photos)
                } else {
                    groups = tree
                    dateIdx = 0
                    filter = null
                    val n = tree.sumOf { it.photos.size }
                    status = ctx.getString(R.string.days_items, tree.size, n)
                    try {
                        val existing = DlnaRepository.existingTitles(ctx)
                        val history = DlnaRepository.downloadHistory(ctx)
                        tree.flatMap { it.photos }.forEach { p ->
                            if (existing.contains(p.title) || history.contains(p.title)) {
                                doneIds[p.id] = true
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                status = ctx.getString(R.string.failed, e.message ?: "?")
            } finally {
                loading = false
            }
        }
    }

    fun transfer() {
        if (transferring || selectedPhotos.isEmpty()) return
        progressText = ""
        TransferManager.start(ctx, selectedPhotos)
    }

    Column(Modifier.padding(16.dp)) {
        if (location == null) {
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    stringResource(R.string.gallery_need_connect),
                    modifier = Modifier.padding(24.dp),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Button(onClick = { load() }, enabled = !loading && !transferring) {
                Text(stringResource(R.string.load_list))
            }
            if (loading) LoadingIndicator() else Text(status, fontSize = 13.sp)
        }
        if (groups.size > 1) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
            ) {
                groups.forEachIndexed { i, g ->
                    SegmentedButton(
                        selected = i == dateIdx,
                        onClick = { dateIdx = i },
                        shape = SegmentedButtonDefaults.itemShape(i, groups.size),
                    ) { Text("${g.title}(${g.photos.size})", fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
        // 種別フィルタ（写真と動画が混在する場合のみ表示）
        if (hasPhoto && hasVideo) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
                FilterChip(
                    selected = filter == MediaKind.PHOTO,
                    onClick = { filter = MediaKind.PHOTO },
                    label = { Text(stringResource(R.string.filter_photos)) },
                )
                FilterChip(
                    selected = filter == MediaKind.VIDEO,
                    onClick = { filter = MediaKind.VIDEO },
                    label = { Text(stringResource(R.string.filter_videos)) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(photos, key = { it.id }) { p ->
                PhotoCell(
                    ctx = ctx,
                    photo = p,
                    checked = selected[p.id] == true,
                    done = doneIds[p.id] == true,
                    onToggle = {
                        if (selected[p.id] == true) selected.remove(p.id) else selected[p.id] = true
                    },
                )
            }
        }
        if (transferring) {
            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                ctx.getString(
                    R.string.transfer_progress_text,
                    transferState.done + 1,
                    transferState.total,
                    transferState.currentTitle,
                ),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = { TransferManager.cancel(ctx) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.cancel))
            }
        } else if (progressText.isNotEmpty()) {
            Text(progressText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            Arrangement.spacedBy(12.dp),
            Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                if (selected.size == photos.size) selected.clear()
                else photos.forEach { selected[it.id] = true }
            }) {
                Text(
                    if (selected.size == photos.size && photos.isNotEmpty()) stringResource(R.string.clear_selection)
                    else stringResource(R.string.select_all),
                )
            }
            ExtendedFloatingActionButton(
                text = { Text(ctx.getString(R.string.transfer_selected, selectedPhotos.size)) },
                icon = { Text("⇩") },
                onClick = { transfer() },
                expanded = !transferring,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PhotoCell(
    ctx: Context,
    photo: Photo,
    checked: Boolean,
    done: Boolean,
    onToggle: () -> Unit,
) {
    val isVideo = photo.kind == MediaKind.VIDEO
    // 動画はカメラ側にサムネイルが無くフルDLが必要なため取得しない（大容量DL防止）
    val bmp by produceState<Bitmap?>(initialValue = null, photo.id) {
        value = if (isVideo) null else DlnaRepository.thumbnailBitmap(ctx, photo)
    }
    val shape = MaterialTheme.shapes.medium
    val borderMod = if (checked) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(borderMod)
            .clickable { onToggle() },
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = photo.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isVideo) {
                    Text("▶", fontSize = 24.sp)
                }
                Text(
                    photo.title,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if (isVideo) {
            Text(
                stringResource(R.string.video_badge),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (done) {
            Text(
                stringResource(R.string.saved_badge),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
