/**
 * Auralys Extended (C) 2026 — based on Metrolist (GPL-3.0)
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback.queues

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.models.toMediaMetadata
import androidx.media3.common.MediaItem
import timber.log.Timber

/**
 * Radio built from a lyric segment.
 *
 * Given a highlighted lyric line ("segmen lirik"), we use that text as a
 * seed query against YouTube Music search (songs filter), combine the top
 * matches with the related-songs radio of the currently playing song, and
 * present them as a queue starting from the best match.
 */
class LyricSegmentRadio(
    private val seedLyric: String,
    private val currentSong: MediaItem,
) : Queue {
    override val preloadItem = null

    override suspend fun getInitialStatus(): Queue.Status {
        val items = mutableListOf<MediaItem>()

        // 1) Search for songs matching the lyric segment
        val query = seedLyric.trim().take(120)
        val search = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        search?.items
            ?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
            ?.take(25)
            ?.map { song: SongItem -> song.toMediaMetadata().toMediaItem() }
            ?.let { items.addAll(it) }

        // 2) Fallback / supplement: related songs of the currently playing item
        if (items.size < 5) {
            currentSong.mediaId.takeIf { it.isNotBlank() }?.let { videoId ->
                YouTube.next(WatchEndpoint(videoId = videoId))
                    .onSuccess { nextResult ->
                        nextResult.items
                            .filterIsInstance<com.metrolist.innertube.models.SongItem>()
                            .filter { song -> song.id != currentSong.mediaId }
                            .take(25)
                            .forEach { song -> items.add(song.toMediaMetadata().toMediaItem()) }
                    }
                    .onFailure { Timber.tag(TAG).d("related fetch failed: %s", it.message) }
            }
        }

        val deduped = items.distinctBy { it.mediaId }.filter { it.mediaId != currentSong.mediaId }
        Timber.tag(TAG).i("LyricSegmentRadio built with %d items for seed \"%s\"", deduped.size, query)
        return Queue.Status(
            title = seedLyric.trim().take(40),
            items = deduped,
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage() = false

    override suspend fun nextPage() = throw UnsupportedOperationException()

    companion object {
        private const val TAG = "LyricSegmentRadio"
    }
}
