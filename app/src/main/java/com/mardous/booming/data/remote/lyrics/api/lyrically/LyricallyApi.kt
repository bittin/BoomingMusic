/*
 * Copyright (c) 2026 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.data.remote.lyrics.api.lyrically

import com.mardous.booming.BuildConfig
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.model.DownloadedLyrics
import com.mardous.booming.data.remote.lyrics.model.LyricallyLyricText
import com.mardous.booming.data.remote.lyrics.model.LyricallyLyricsResponse
import com.mardous.booming.data.remote.lyrics.model.LyricallySearchResult
import com.mardous.booming.data.remote.lyrics.model.toDownloadedLyrics
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.userAgent
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import kotlin.math.abs

/**
 * Fetch lyrics from Lyrically API.
 *
 * Based on [Metrolist](https://github.com/MetrolistGroup/Metrolist)'s implementation.
 */
class LyricallyApi(private val client: HttpClient) : LyricsApi {

    override val networkFeature = NetworkFeature.Lyrics.Lyrically

    override suspend fun songLyrics(song: Song, title: String, artist: String): DownloadedLyrics? {
        val searchResults = client.paxsenix("$BASE_URL/apple-music/search") {
            url.encodedParameters.append("q", "$artist $title".encodeURLParameter())
        }.body<List<LyricallySearchResult>>()

        if (searchResults.isNotEmpty()) {

            var lyrics: DownloadedLyrics? = null

            val scoredResults = scoreSearchResults(title, artist, song.duration, searchResults)
            for ((result, score) in scoredResults.take(5)) {
                if (score <= 0.0) continue

                val lyricsResponse = client.paxsenix("$BASE_URL/apple-music/lyrics") {
                    parameter("id", result.id)
                }.body<LyricallyLyricsResponse>()

                val newLyrics = parseResponse(song, lyricsResponse)

                if (lyrics == null) {
                    lyrics = newLyrics
                } else if (lyrics.syncedLyrics.isNullOrEmpty() &&
                    !newLyrics.syncedLyrics.isNullOrEmpty()) {
                    lyrics = lyrics.copy(syncedLyrics = newLyrics.syncedLyrics)
                } else if (lyrics.plainLyrics.isNullOrEmpty() &&
                    !newLyrics.plainLyrics.isNullOrEmpty()) {
                    lyrics = lyrics.copy(plainLyrics = newLyrics.plainLyrics)
                }

                if (lyrics.hasMultiOptions) {
                    return lyrics
                }
            }

            return lyrics
        }

        return null
    }

    private fun parseResponse(song: Song, response: LyricallyLyricsResponse): DownloadedLyrics {
        var lyrics = if (!response.elrcMultiPerson.isNullOrEmpty()) {
            song.toDownloadedLyrics(syncedLyrics = response.elrcMultiPerson)
        } else if (!response.elrc.isNullOrEmpty()) {
            song.toDownloadedLyrics(syncedLyrics = response.elrc)
        } else if (!response.lrc.isNullOrEmpty()) {
            song.toDownloadedLyrics(syncedLyrics = response.lrc)
        } else {
            song.toDownloadedLyrics(syncedLyrics = parseContent(response))
        }
        if (!response.plain.isNullOrEmpty()) {
            lyrics = lyrics.copy(plainLyrics = response.plain)
        }
        return lyrics
    }

    private fun parseContent(response: LyricallyLyricsResponse): String? {
        if (response.content.isEmpty()) return null

        val syncedLyrics = StringBuilder()
        val lines = response.content
        when (response.type) {
            "Syllable" -> {
                val isMultiPerson = lines.any { it.oppositeTurn }
                for (line in lines) {
                    syncedLyrics.append("[${line.timestamp.toLrcTimestamp()}]")

                    if (isMultiPerson) {
                        syncedLyrics.append(if (line.oppositeTurn) "v2:" else "v1:")
                    }

                    formatSyllableToLrc(syncedLyrics, line.text)

                    if (line.background) {
                        syncedLyrics.append("\n[bg:")
                        formatSyllableToLrc(syncedLyrics, line.backgroundText)
                        syncedLyrics.append("]")
                    }
                    syncedLyrics.append("\n")
                }
            }

            "Line" -> {
                for (line in lines) {
                    syncedLyrics.append("[${line.timestamp.toLrcTimestamp()}] ${line.text[0].text}\n")
                }
            }
        }

        return syncedLyrics.toString().dropLast(1)
    }

    private fun formatSyllableToLrc(output: StringBuilder, content: List<LyricallyLyricText>) {
        for (syllable in content) {
            val formatedBeginTimestamp = "<${syllable.timestamp.toLrcTimestamp()}>"
            val formatedEndTimestamp = "<${syllable.endtime.toLrcTimestamp()}>"
            if (!output.endsWith(formatedBeginTimestamp)) {
                output.append(formatedBeginTimestamp)
            }
            output.append(syllable.text)
            if (!syllable.part) {
                output.append(" ")
            }
            output.append(formatedEndTimestamp)
        }
    }

    private fun scoreSearchResults(
        title: String,
        artist: String,
        duration: Long,
        results: List<LyricallySearchResult>
    ): List<Pair<LyricallySearchResult, Double>> {
        return results.map { result ->
            val titleScore = JW_SIMILARITY.apply(title, result.songName)
            val artistScore = JW_SIMILARITY.apply(artist, result.artistName)

            val durationDiff = abs(result.duration - duration)
            val durationScore = when {
                durationDiff <= 2000 -> 1.0 // Excellent match
                durationDiff <= 5000 -> 0.6 // Good match
                durationDiff <= 10000 -> 0.2 // Acceptable match
                else -> -1.0 // Likely wrong version
            }

            result to (artistScore + titleScore + durationScore)
        }.sortedByDescending { it.second }
    }

    private suspend fun HttpClient.paxsenix(
        url: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ) = get(url) {
        header(HttpHeaders.Accept, "application/json")
        header(HttpHeaders.ContentType, "application/json")
        userAgent(USER_AGENT)
        block()
    }

    private fun Long.toLrcTimestamp(): String {
        val minutes = this / 60000
        val seconds = (this % 60000) / 1000
        val milliseconds = this % 1000

        val leadingZeros: Array<String> = arrayOf(
            if (minutes < 10) "0" else "",
            if (seconds < 10) "0" else "",
            if (milliseconds < 10) "00" else if (milliseconds < 100) "0" else ""
        )

        return "${leadingZeros[0]}$minutes:${leadingZeros[1]}$seconds.${leadingZeros[2]}$milliseconds"
    }

    companion object {
        private const val BASE_URL = "https://lyrics.paxsenix.org"
        private const val USER_AGENT = "BoomingMusic/${BuildConfig.VERSION_NAME}"

        private val JW_SIMILARITY = JaroWinklerSimilarity()
    }
}