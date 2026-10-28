package io.github.ptrinh.nowplaying;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which session is shown, what its lines say, the times, the glow colour and when the pop-up shows. */
public class NowPlayingTest {
    private static final String SELF = "io.github.ptrinh.nowplaying";

    @Test
    public void playingBeatsPausedWhateverTheOrder() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_PAUSED, NowPlaying.STATE_PLAYING},
                new String[]{"a", "b"}, SELF));
        assertEquals(0, NowPlaying.choose(new int[]{NowPlaying.STATE_BUFFERING, NowPlaying.STATE_PLAYING},
                new String[]{"a", "b"}, SELF));
    }

    @Test
    public void firstPausedWhenNothingPlays() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_STOPPED, NowPlaying.STATE_PAUSED, NowPlaying.STATE_PAUSED},
                new String[]{"a", "b", "c"}, SELF));
    }

    @Test
    public void nothingToShow() {
        assertEquals(-1, NowPlaying.choose(new int[0], new String[0], SELF));
        assertEquals(-1, NowPlaying.choose(new int[]{NowPlaying.STATE_NONE, NowPlaying.STATE_STOPPED, NowPlaying.STATE_ERROR},
                new String[]{"a", "b", "c"}, SELF));
    }

    @Test
    public void skipsItself() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_PLAYING, NowPlaying.STATE_PAUSED},
                new String[]{SELF, "b"}, SELF));
    }

    @Test
    public void activeStates() {
        assertTrue(NowPlaying.isActive(NowPlaying.STATE_SKIPPING_TO_NEXT));
        assertFalse(NowPlaying.isActive(NowPlaying.STATE_PAUSED));
        assertFalse(NowPlaying.isActive(NowPlaying.STATE_ERROR));
    }

    @Test
    public void titleFallsBack() {
        assertEquals("Song", NowPlaying.title(" Song ", "Display", "Music App"));
        assertEquals("Display", NowPlaying.title("", "Display", "Music App"));
        assertEquals("Music App", NowPlaying.title(null, " ", "Music App"));
    }

    @Test
    public void subtitleJoinsArtistAndApp() {
        assertEquals("Artist · Music App", NowPlaying.subtitle("Artist", "Album", "Music App", "Song"));
        assertEquals("Album · Radio", NowPlaying.subtitle(null, "Album", "Radio", "Song"));
        assertEquals("Radio", NowPlaying.subtitle("", null, "Radio", "Song"));
        assertEquals("Artist", NowPlaying.subtitle("Artist", null, "Radio", "Radio"));
        assertEquals("", NowPlaying.subtitle(null, null, "Radio", "Radio"));
    }

    @Test
    public void positionMovesOnWhilePlaying() {
        assertEquals(30_000, NowPlaying.position(30_000, 0, 1f, false, 60_000, 99_999));
        assertEquals(45_000, NowPlaying.position(30_000, 1_000, 1f, true, 60_000, 16_000));   // 15 s later
        assertEquals(60_000, NowPlaying.position(59_000, 1_000, 1f, true, 60_000, 90_000));   // capped
        assertEquals(31_000, NowPlaying.position(30_000, 1_000, 1f, true, 0, 2_000));         // live: no cap
        assertEquals(60_000, NowPlaying.position(30_000, 1_000, 2f, true, 0, 16_000));        // double speed
        assertEquals(-1, NowPlaying.position(-1, 1_000, 1f, true, 60_000, 2_000));
    }

    @Test
    public void progress() {
        assertEquals(0.75f, NowPlaying.progress(45_000, 60_000), 1e-6);
        assertEquals(1f, NowPlaying.progress(70_000, 60_000), 1e-6);
        assertEquals(-1f, NowPlaying.progress(30_000, 0), 1e-6);
        assertEquals(-1f, NowPlaying.progress(-1, 60_000), 1e-6);
    }

    @Test
    public void clock() {
        assertEquals("0:00", NowPlaying.clock(0));
        assertEquals("0:07", NowPlaying.clock(7_900));
        assertEquals("3:45", NowPlaying.clock(225_000));
        assertEquals("1:02:03", NowPlaying.clock(3_723_000));
        assertEquals("0:00", NowPlaying.clock(-5_000));
    }

    @Test
    public void popsOnceForANewTrack() {
        String a = NowPlaying.trackKey("app", "Song", "Artist");
        String b = NowPlaying.trackKey("app", "Next song", "Artist");
        assertTrue(NowPlaying.shouldPop(null, a, true, true, true, false, false));
        assertFalse("the same track again", NowPlaying.shouldPop(a, a, true, true, true, false, false));
        assertTrue(NowPlaying.shouldPop(a, b, true, true, true, false, false));
        assertEquals(a, NowPlaying.trackKey("app", " Song ", "Artist "));
    }

    @Test
    public void staysAway() {
        String k = NowPlaying.trackKey("app", "Song", null);
        assertFalse("paused", NowPlaying.shouldPop(null, k, false, true, true, false, false));
        assertFalse("switched off", NowPlaying.shouldPop(null, k, true, false, true, false, false));
        assertFalse("screen off", NowPlaying.shouldPop(null, k, true, true, false, false, false));
        assertFalse("the music app is on screen", NowPlaying.shouldPop(null, k, true, true, true, true, false));
        assertFalse("the card is open", NowPlaying.shouldPop(null, k, true, true, true, false, true));
    }

    @Test
    public void glowFromArtwork() {
        int accent = 0xFFFF6B4A;
        assertEquals(0xFFFF0000, NowPlaying.glowColor(0x800000, accent));      // dark red, brightened
        assertEquals(0xFF55AAFF, NowPlaying.glowColor(0x336699, accent));
        assertEquals(accent, NowPlaying.glowColor(0x101010, accent));          // too dark
        assertEquals(accent, NowPlaying.glowColor(0x808080, accent));          // grey
        assertEquals(accent, NowPlaying.glowColor(0xC0B8B0, accent));          // nearly grey
    }
}
