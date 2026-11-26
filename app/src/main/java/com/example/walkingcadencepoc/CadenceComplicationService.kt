package com.example.walkingcadencepoc

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

private const val MIN_CADENCE_SPM = 0f
private const val MAX_CADENCE_SPM = 180f

class CadenceComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        runCatching {
            WalkingCadenceManager.getInstance(applicationContext).ensureAlwaysOnStarted()
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildComplicationData(
            type = type,
            spm = 120,
            tapAction = null
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val manager = WalkingCadenceManager.getInstance(applicationContext)
        runCatching { manager.ensureAlwaysOnStarted() }
        val latestSpm = manager.cadenceSpm.value?.toInt()
            ?: manager.spmHistory.value.lastOrNull()
            ?: 0

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
            spm = latestSpm,
            tapAction = tapPendingIntent
        )
    }

    private fun buildComplicationData(
        type: ComplicationType,
        spm: Int,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val valueString = spm.toString()
        val plainValue = PlainComplicationText.Builder(valueString).build()
        val contentDesc = PlainComplicationText.Builder("Walking cadence $valueString SPM").build()
        val walkIcon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_walk)
        ).build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                val numericValue = spm.toFloat().coerceIn(MIN_CADENCE_SPM, MAX_CADENCE_SPM)
                RangedValueComplicationData.Builder(
                    value = numericValue,
                    min = MIN_CADENCE_SPM,
                    max = MAX_CADENCE_SPM,
                    contentDescription = contentDesc
                )
                    .setText(plainValue)
                    .setMonochromaticImage(walkIcon)
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = plainValue,
                    contentDescription = contentDesc
                )
                    .setMonochromaticImage(walkIcon)
                    .setTapAction(tapAction)
                    .build()
            }

            else -> null
        }
    }
}
