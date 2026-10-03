package com.claudecode.countdown.ui.calendar

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Task

/** Label of the clip a dragged task carries; the time grid only accepts such drops. */
private const val TASK_CLIP_LABEL = "tiktak-task"

/** Whether a drag carries a task from the "Без даты" strip. */
internal fun DragAndDropEvent.carriesTask(): Boolean =
    toAndroidDragEvent().clipDescription?.label == TASK_CLIP_LABEL

/** The id of the dragged task. */
internal fun DragAndDropEvent.taskId(): String? =
    toAndroidDragEvent().clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

/**
 * Open tasks without a date, as chips above the time grid. A long press picks one up; dropped on
 * the grid it gets that time (time blocking, as in TickTick). A tap opens the task.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UndatedTasks(tasks: List<Task>, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(vertical = 6.dp)) {
        Text(
            if (tasks.isEmpty()) "Задач без даты нет" else "Без даты — удерживайте и перетащите на нужное время",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { t ->
                val color = entryColor(t)
                Text(
                    t.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.22f))
                        .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .dragAndDropSource {
                            detectTapGestures(
                                onTap = { onOpen(t.id) },
                                onLongPress = {
                                    startTransfer(
                                        DragAndDropTransferData(
                                            ClipData(TASK_CLIP_LABEL, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(t.id))
                                        )
                                    )
                                },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
