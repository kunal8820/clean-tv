package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The #1204 race, replayed in the order MainActivity runs it. The Inputs pill is focused when
 * the external source picker opens, Home is pressed, the resume refocus fires early, the window
 * regains focus and the framework hands focus back to the pill, and only then does
 * onWindowFocusChanged run. Without the HomeFocus wait the pill wins; with it, the first tile
 * wins. The fake below mirrors MainActivity's call sites (onNewIntent, onStart, onResume,
 * onPause, onWindowFocusChanged) and the framework restoring the previously focused view when
 * the window gains focus, using the real HomeFocus and TileRefresh classes.
 */
public class HomeFocusRaceTest {

    /** Mirrors MainActivity's wiring plus the framework's focus restore on window focus gain.
     * One modeling rule matters: a focus request made while the window lacks focus does not
     * change what the framework restores when focus returns. That is the #1204 mechanism itself:
     * the resume refocus fires early, but the pill (focused when the picker opened) is what the
     * window hands focus back to, so the early refocus is undone unless onWindowFocusChanged
     * applies the first tile after it. */
    static final class FakeHome {
        private final HomeFocus homeFocus = new HomeFocus();
        private final TileRefresh tileRefresh = new TileRefresh();
        private final boolean useFix;
        private boolean stopped;
        private boolean windowFocus = true;
        private String focused;
        private String restored;

        FakeHome(boolean useFix) {
            this.useFix = useFix;
            // Tiles already built once, as on a running launcher: clear TileRefresh's first-run stale flag.
            tileRefresh.onStart();
            tileRefresh.onResume(false);
        }

        /** The launcher is up, and the Inputs pill has focus. */
        void pillFocused() {
            focused = "pill";
            restored = "pill";
        }

        /** The external picker opens over a stopped Home (the #1204 case). */
        void pickerOpensOverStoppedHome() {
            pillFocused();
            windowFocus = false;
            stopped = true;
            tileRefresh.onStop();
        }

        /** The external picker opens over a Home that is still in front (not stopped). */
        void pickerOpensOverForegroundHome() {
            pillFocused();
            windowFocus = false;
        }

        /** Mirrors onNewIntent for a Home press. */
        void homePressed() {
            if (useFix) {
                if (homeFocus.onHomeIntent(stopped, windowFocus)) {
                    focus("tile0"); // grid.post(() -> focusTile(0))
                } else {
                    tileRefresh.onHomeAgain();
                }
            } else {
                // Pre-fix behavior: stopped means refocus on resume; on screen without window
                // focus means the focus is posted at once, before the window focus returns.
                if (stopped) {
                    tileRefresh.onHomeAgain();
                } else {
                    focus("tile0");
                }
            }
        }

        /** Mirrors onStart. */
        void start() {
            stopped = false;
            tileRefresh.onStart();
        }

        /** Mirrors onResume: a REFOCUS puts the focus back on the first tile, early. */
        void resume() {
            if (tileRefresh.onResume(false) == TileRefresh.Action.REFOCUS) focus("tile0");
        }

        /** Mirrors onPause. */
        void pause() {
            homeFocus.onPause();
        }

        /** The window gains focus: the framework first restores the previously focused view, then
         * MainActivity's onWindowFocusChanged runs. */
        void windowFocusGained() {
            windowFocus = true;
            focused = restored;
            if (useFix && homeFocus.onWindowFocusChanged(true)) focus("tile0");
        }

        /** The window loses focus; the framework remembers the currently focused view. */
        void windowFocusLost() {
            windowFocus = false;
        }

        String focused() {
            return focused;
        }

        private void focus(String view) {
            focused = view;
            // Only a focus applied while the window has focus changes what a later window
            // focus gain restores; an early request while unfocused is undone by the restore.
            if (windowFocus) restored = view;
        }
    }

    @Test
    public void stoppedPickerHomeEndsOnFirstTile() {
        FakeHome home = new FakeHome(true);
        home.pickerOpensOverStoppedHome();
        home.homePressed();
        home.start();
        home.resume();
        home.windowFocusGained();
        assertEquals("tile0", home.focused());
    }

    @Test
    public void stoppedPickerHomeWithoutFixEndsOnPill() {
        // Control: the same replay without the fix reproduces #1204, so the test can catch it.
        FakeHome home = new FakeHome(false);
        home.pickerOpensOverStoppedHome();
        home.homePressed();
        home.start();
        home.resume();
        home.windowFocusGained();
        assertEquals("pill", home.focused());
    }

    @Test
    public void foregroundPickerHomeEndsOnFirstTile() {
        FakeHome home = new FakeHome(true);
        home.pickerOpensOverForegroundHome();
        home.homePressed();
        home.windowFocusGained();
        assertEquals("tile0", home.focused());
    }

    @Test
    public void foregroundPickerHomeWithoutFixEndsOnPill() {
        FakeHome home = new FakeHome(false);
        home.pickerOpensOverForegroundHome();
        home.homePressed();
        home.windowFocusGained();
        assertEquals("pill", home.focused());
    }

    @Test
    public void pauseClearsPendingSoNoLateJump() {
        FakeHome home = new FakeHome(true);
        home.pickerOpensOverStoppedHome();
        home.homePressed();
        home.pause();
        home.start();
        home.resume();
        home.windowFocusGained();
        assertEquals("pill", home.focused());
        // A later window focus cycle must not jump focus to the first tile either.
        home.windowFocusLost();
        home.windowFocusGained();
        assertEquals("pill", home.focused());
    }

    @Test
    public void homeInFrontWithWindowFocusFocusesAtOnce() {
        FakeHome home = new FakeHome(true);
        home.pillFocused();
        home.homePressed();
        assertEquals("tile0", home.focused());
        home.windowFocusLost();
        home.windowFocusGained();
        assertEquals("tile0", home.focused());
    }
}
