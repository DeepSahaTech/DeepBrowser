package com.deepbrowser.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlTest {
    @Test fun extensions() {
        for (e in listOf("mp4", "mkv", "webm", "m3u8", "mpd", "mov", "ts"))
            assertTrue(e, MediaUrl.isDirectMedia("https://cdn.example.com/a/b/video.$e"))
    }
    @Test fun queryAndCase() = assertTrue(MediaUrl.isDirectMedia("http://x.com/V.MP4?token=1#t=5"))
    @Test fun extensionInQueryOnly() = assertFalse(MediaUrl.isDirectMedia("https://x.com/watch?v=a.mp4"))
    @Test fun htmlPage() = assertFalse(MediaUrl.isDirectMedia("https://x.com/page.html"))
    @Test fun hostLooksLikeExt() = assertFalse(MediaUrl.isDirectMedia("https://video.mp4"))
    @Test fun rootAndNull() { assertFalse(MediaUrl.isDirectMedia("https://x.com/")); assertFalse(MediaUrl.isDirectMedia(null)) }
    @Test fun nonHttp() = assertFalse(MediaUrl.isDirectMedia("file:///sdcard/a.mp4"))
    @Test fun noExtension() = assertFalse(MediaUrl.isDirectMedia("https://x.com/stream"))
}
