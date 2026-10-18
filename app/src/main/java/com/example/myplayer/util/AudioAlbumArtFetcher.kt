package com.example.myplayer.util

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

class AudioAlbumArtFetcher(
    private val data: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {
    override suspend fun fetch(): FetchResult? = null

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme == "content") {
                return AudioAlbumArtFetcher(data, options, context)
            }
            return null
        }
    }
}
