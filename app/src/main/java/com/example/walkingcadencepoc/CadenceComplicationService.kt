package com.example.walkingcadencepoc

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class CadenceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildComplicationData(
            type = type,
            spm = 125,
            isTracking = true,
            tapAction = null
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val manager = WalkingCadenceManager.getInstance(applicationContext)
        val isTracking = manager.isTracking.value
        val latest10sSpm = manager.spmHistory.value.lastOrNull()
            ?: manager.cadenceSpm.value?.toInt()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return buildComplicationData(
            type = request.complicationType,
            spm = if (isTracking) latest10sSpm else null,
            isTracking = isTracking,
            tapAction = tapPendingIntent
        )
    }

    private fun buildComplicationData(
        type: ComplicationType,
        spm: Int?,
        isTracking: Boolean,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val valueString = when {
            spm != null -> spm.toString()
            isTracking -> "..."
            else -> "--"
        }
        val plainValue = PlainComplicationText.Builder(valueString).build()
        val plainTitle = PlainComplicationText.Builder("SPM").build()
        val contentDesc = PlainComplicationText.Builder("Walking cadence $valueString SPM").build()
        val walkIcon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_walk)
        ).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = plainValue,
                    contentDescription = contentDesc
                )
                    .setTitle(plainTitle)
                    .setMonochromaticImage(walkIcon)
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.RANGED_VALUE -> {
                val numericValue = (spm ?: 0).toFloat().coerceIn(0f, 180f)
                RangedValueComplicationData.Builder(
                    value = numericValue,
                    min = 0f,
                    max = 180f,
                    contentDescription = contentDesc
                )
                    .setText(plainValue)
                    .setTitle(plainTitle)
                    .setMonochromaticImage(walkIcon)
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("$valueString SPM").build(),
                    contentDescription = contentDesc
                )
                    .setTitle(PlainComplicationText.Builder("Cadence").build())
                    .setMonochromaticImage(walkIcon)
                    .setTapAction(tapAction)
                    .build()
            }

            else -> null
        }
    }
}
