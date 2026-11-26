package com.example.walkingcadencepoc

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

private const val RESOURCES_VERSION = "1"
private const val ID_RESET_HISTORY = "reset_history"
private const val ID_OPEN_APP = "open_app"
private const val ID_ALLOW_PERMISSION = "allow_permission"

class CadenceTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        return serviceScope.future {
            val manager = WalkingCadenceManager.getInstance(applicationContext)
            val needsPermission = !manager.hasPermission()
            if (!needsPermission) {
                manager.ensureAlwaysOnStarted()
            }

            if (requestParams.currentState.lastClickableId == ID_RESET_HISTORY) {
                manager.resetHistory()
            }

            val currentSpm = manager.cadenceSpm.value?.toInt() ?: 0
            val history = manager.spmHistory.value

            val openAppAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()

            val openAppClickable = Clickable.Builder()
                .setId(ID_OPEN_APP)
                .setOnClick(openAppAction)
                .build()

            val resetClickable = Clickable.Builder()
                .setId(ID_RESET_HISTORY)
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .build()

            val allowClickable = Clickable.Builder()
                .setId(ID_ALLOW_PERMISSION)
                .setOnClick(openAppAction)
                .build()

            val rootElement = buildTileLayout(
                currentSpm = currentSpm,
                history = history,
                openAppClickable = openAppClickable,
                actionClickable = if (needsPermission) allowClickable else resetClickable,
                actionLabel = if (needsPermission) "Allow" else "Reset",
                needsPermission = needsPermission
            )

            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(0L)
                .setTileTimeline(Timeline.fromLayoutElement(rootElement))
                .build()
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return serviceScope.future {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        }
    }

    private fun buildTileLayout(
        currentSpm: Int,
        history: List<Int>,
        openAppClickable: Clickable,
        actionClickable: Clickable,
        actionLabel: String,
        needsPermission: Boolean
    ): LayoutElementBuilders.LayoutElement {
        val header = Text.Builder()
            .setText("$currentSpm SPM")
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(20f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .build()
            )
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(openAppClickable)
                    .build()
            )
            .build()

        val barRow = buildBarGraphRow(history)

        val buttonColor = if (needsPermission) 0xFF00E5FF.toInt() else 0xFF37474F.toInt()
        val buttonTextColor = if (needsPermission) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val actionButton = Box.Builder()
            .setWidth(dp(if (needsPermission) 96f else 76f))
            .setHeight(dp(28f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(actionClickable)
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(buttonColor))
                            .setCorner(Corner.Builder().setRadius(dp(14f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(actionLabel)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(11f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(argb(buttonTextColor))
                            .build()
                    )
                    .build()
            )
            .build()

        val column = Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(header)
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(barRow)
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(actionButton)
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    private fun buildBarGraphRow(history: List<Int>): LayoutElementBuilders.LayoutElement {
        val maxScale = maxOf(180, history.maxOrNull() ?: 180)
        val minScale = 0
        val rowBuilder = Row.Builder()
            .setWidth(wrap())
            .setHeight(dp(70f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)

        for (index in 0 until MAX_BARS) {
            if (index > 0) {
                rowBuilder.addContent(Spacer.Builder().setWidth(dp(3f)).build())
            }
            val value = history.getOrNull(index)
            val isLatest = value != null && index == history.lastIndex

            val colBuilder = Column.Builder()
                .setWidth(dp(15f))
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

            if (value != null) {
                val fraction = ((value - minScale).coerceAtLeast(10).toFloat() /
                    (maxScale - minScale).coerceAtLeast(1)).coerceIn(0.12f, 1f)
                val barHeightDp = 48f * fraction
                val barColor = if (isLatest) 0xFF00E5FF.toInt() else 0xFFBB86FC.toInt()
                val labelColor = if (isLatest) 0xFF00E5FF.toInt() else 0xFFB0BEC5.toInt()

                colBuilder.addContent(
                    Text.Builder()
                        .setText(value.toString())
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(8f))
                                .setColor(argb(labelColor))
                                .build()
                        )
                        .build()
                )
                colBuilder.addContent(Spacer.Builder().setHeight(dp(2f)).build())
                colBuilder.addContent(
                    Box.Builder()
                        .setWidth(dp(13f))
                        .setHeight(dp(barHeightDp))
                        .setModifiers(
                            Modifiers.Builder()
                                .setBackground(
                                    Background.Builder()
                                        .setColor(argb(barColor))
                                        .setCorner(Corner.Builder().setRadius(dp(3f)).build())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            } else {
                colBuilder.addContent(
                    Box.Builder()
                        .setWidth(dp(13f))
                        .setHeight(dp(3f))
                        .setModifiers(
                            Modifiers.Builder()
                                .setBackground(
                                    Background.Builder()
                                        .setColor(argb(0xFF444444.toInt()))
                                        .setCorner(Corner.Builder().setRadius(dp(1.5f)).build())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            }
            rowBuilder.addContent(colBuilder.build())
        }

        return rowBuilder.build()
    }
}
