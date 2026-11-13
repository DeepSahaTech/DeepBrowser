package com.deepbrowser.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Reads one track of a [StreamStore] as a growing, non-seekable source: the length is unknown
 * and reads block until the browser has sent more bytes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GrowingDataSource(private val store: StreamStore, private val track: String) : BaseDataSource(true) {

    private var input: StreamStore.Input? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val opened = store.open(track)
        try {
            opened.seekTo(dataSpec.position)
        } catch (e: IOException) {
            opened.close()
            throw e
        }
        input = opened
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        val src = input ?: return C.RESULT_END_OF_INPUT
        val n = src.read(buffer, offset, readLength)
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        input?.let {
            it.close()
            input = null
            uri = null
            transferEnded()
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class GrowingDataSourceFactory(private val store: StreamStore, private val track: String) : DataSource.Factory {
    override fun createDataSource(): DataSource = GrowingDataSource(store, track)
}
