package com.ayuvo.health.data

import com.ayuvo.health.models.UserExercise

/**
 * Media for one exercise.
 *
 * - Catalogue exercises stream a 180×180 JPEG thumbnail ([thumbnailUrl]) and an animated
 *   GIF ([animationUrl]) from the dataset's pinned GitHub URLs; both are © Gym visual and
 *   must be shown with [ExerciseItem.MEDIA_ATTRIBUTION].
 * - User-created exercises show their on-device photo ([photoFilename]).
 * - Anything else (quick-log activities, snapshots without media) shows a placeholder.
 */
data class ExerciseVisual(
    val thumbnailUrl: String? = null,
    val animationUrl: String? = null,
    val photoFilename: String? = null
) {
    val hasRemoteMedia: Boolean get() = thumbnailUrl != null || animationUrl != null

    companion object {
        val None = ExerciseVisual()

        fun from(item: ExerciseItem): ExerciseVisual = ExerciseVisual(
            thumbnailUrl = item.imageUrl,
            animationUrl = item.gifUrl,
            photoFilename = item.imagePaths.firstOrNull(UserExercise::isUserPhotoFilename)
        )
    }
}
