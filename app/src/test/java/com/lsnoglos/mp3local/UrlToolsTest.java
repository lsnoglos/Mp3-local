package com.lsnoglos.mp3local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class UrlToolsTest {
    @Test public void acceptsYoutubeVideos() {
        assertTrue(UrlTools.isVideoUrl("https://www.youtube.com/watch?v=abcdefghijk"));
        assertTrue(UrlTools.isVideoUrl("https://m.youtube.com/shorts/abcdefghijk"));
        assertTrue(UrlTools.isVideoUrl("https://youtu.be/abcdefghijk"));
        assertTrue(UrlTools.isVideoUrl("https://music.youtube.com/watch?v=abcdefghijk"));
    }

    @Test public void rejectsUnsafeOrNonVideoUrls() {
        assertFalse(UrlTools.isVideoUrl(null));
        assertFalse(UrlTools.isVideoUrl("https://youtube.com/"));
        assertFalse(UrlTools.isVideoUrl("https://youtube.com/results?search_query=test"));
        assertFalse(UrlTools.isVideoUrl("http://youtube.com/watch?v=abcdefghijk"));
        assertFalse(UrlTools.isVideoUrl("https://youtube.com.evil.org/watch?v=abcdefghijk"));
        assertFalse(UrlTools.isVideoUrl("https://example.com/watch?v=abcdefghijk"));
    }
}
