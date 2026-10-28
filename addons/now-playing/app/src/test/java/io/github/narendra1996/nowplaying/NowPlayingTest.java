package io.github.narendra1996.nowplaying;

import org.junit.Test;
import static org.junit.Assert.*;

public class NowPlayingTest {

    @Test
    public void testNowPlayingInfoConstruction() {
        NowPlayingInfo info = new NowPlayingInfo(
                "com.spotify.tv.android",
                "Spotify",
                "Starboy",
                "The Weeknd",
                0xFF1DB954,
                true,
                null,
                null
        );

        assertEquals("com.spotify.tv.android", info.packageName);
        assertEquals("Spotify", info.appName);
        assertEquals("Starboy", info.title);
        assertEquals("The Weeknd", info.artist);
        assertEquals(0xFF1DB954, info.accentColor);
        assertTrue(info.isPlaying);
    }

    @Test
    public void testNowPlayingInfoNullSafety() {
        NowPlayingInfo info = new NowPlayingInfo(
                null,
                null,
                null,
                null,
                0,
                false,
                null,
                null
        );

        assertEquals("", info.packageName);
        assertEquals("", info.appName);
        assertEquals("", info.title);
        assertEquals("", info.artist);
        assertEquals(0xFF1DB954, info.accentColor);
        assertFalse(info.isPlaying);
    }

    @Test
    public void testNowPlayingInfoWhitespaceTrimming() {
        NowPlayingInfo info = new NowPlayingInfo(
                "com.spotify.tv.android",
                "Spotify",
                "   Blinding Lights   ",
                "   The Weeknd   ",
                0xFF1DB954,
                true,
                null,
                null
        );

        assertEquals("Blinding Lights", info.title);
        assertEquals("The Weeknd", info.artist);
    }
}
