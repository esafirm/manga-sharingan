package com.esafirm.sharingan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-series settings. A row with a null [coverName] means "we already tried to find a cover
 * online and found nothing" so we don't keep retrying on every launch.
 */
@Entity(tableName = "series")
data class SeriesMeta(
    @PrimaryKey val seriesKey: String,
    val coverName: String?,
)
