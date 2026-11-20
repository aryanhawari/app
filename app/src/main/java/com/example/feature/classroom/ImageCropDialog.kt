package com.example.feature.classroom

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.media.MediaProcessingHelper

@Composable
fun ImageCropDialog(
    initialBitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    var workingBitmap by remember(initialBitmap) { mutableStateOf(initialBitmap) }
    var leftFrac by remember { mutableFloatStateOf(0.06f) }
    var topFrac by remember { mutableFloatStateOf(0.06f) }
    var rightFrac by remember { mutableFloatStateOf(0.94f) }
    var bottomFrac by remember { mutableFloatStateOf(0.94f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Crop & Rotate Question",
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(
                        onClick = {
                            workingBitmap = MediaProcessingHelper.rotateBitmap90(workingBitmap)
                        },
                        modifier = Modifier.testTag("rotate_image_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90 degrees")
                        Text("Rotate", modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Text(
                    text = "Drag inside the frame to adjust the crop region.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val ratio = (workingBitmap.width.toFloat() / workingBitmap.height.toFloat()).coerceIn(0.6f, 1.8f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                ) {
                    Image(
                        bitmap = workingBitmap.asImageBitmap(),
                        contentDescription = "Selected image preview",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (size.width <= 0 || size.height <= 0) return@detectDragGestures
                                    val dx = dragAmount.x / size.width
                                    val dy = dragAmount.y / size.height
                                    val touchX = change.position.x / size.width
                                    val touchY = change.position.y / size.height
                                    if (touchX < (leftFrac + rightFrac) / 2f) {
                                        leftFrac = (leftFrac + dx).coerceIn(0f, rightFrac - 0.15f)
                                    } else {
                                        rightFrac = (rightFrac + dx).coerceIn(leftFrac + 0.15f, 1f)
                                    }
                                    if (touchY < (topFrac + bottomFrac) / 2f) {
                                        topFrac = (topFrac + dy).coerceIn(0f, bottomFrac - 0.15f)
                                    } else {
                                        bottomFrac = (bottomFrac + dy).coerceIn(topFrac + 0.15f, 1f)
                                    }
                                }
                            }
                    ) {
                        val l = leftFrac * size.width
                        val t = topFrac * size.height
                        val r = rightFrac * size.width
                        val b = bottomFrac * size.height

                        drawRect(
                            color = Color.Black.copy(alpha = 0.42f),
                            topLeft = Offset.Zero,
                            size = Size(size.width, t)
                        )
                        drawRect(
                            color = Color.Black.copy(alpha = 0.42f),
                            topLeft = Offset(0f, b),
                            size = Size(size.width, size.height - b)
                        )
                        drawRect(
                            color = Color.Black.copy(alpha = 0.42f),
                            topLeft = Offset(0f, t),
                            size = Size(l, b - t)
                        )
                        drawRect(
                            color = Color.Black.copy(alpha = 0.42f),
                            topLeft = Offset(r, t),
                            size = Size(size.width - r, b - t)
                        )
                        drawRect(
                            color = Color(0xFFFBBF24),
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            style = Stroke(width = 4f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).testTag("cancel_crop_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val cropped = MediaProcessingHelper.cropNormalized(
                                workingBitmap,
                                leftFrac,
                                topFrac,
                                rightFrac,
                                bottomFrac
                            )
                            onConfirm(cropped)
                        },
                        modifier = Modifier.weight(1f).testTag("confirm_crop_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Send to Teacher")
                    }
                }
            }
        }
    }
}
