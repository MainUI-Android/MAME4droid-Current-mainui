package io.github.mainui.stateapi;

import android.view.KeyEvent;

import com.seleuco.mame4droid.Emulator;
import com.seleuco.mame4droid.helpers.DialogHelper;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * MAME4droid's side of the MainUI State API ({@link MainUIStateApi}). MAME saves and loads
 * "positions" named by a key: the menu's Save / Load state press MAME's save / load key and MAME
 * asks for the position. Here the position key is pressed too: MainUI's slots 0-9 are positions
 * 0-9, its resume state is position "m". With states in MAME4droid's own folder (the default), a
 * save is answered once its file (sta/&lt;game&gt;/&lt;position&gt;.sta) is written; with states
 * beside the roms (a folder MAME reaches through Android's storage access), after a moment.
 * Drivers MAME can't save say so on screen.
 */
public final class Mame4droidStates implements MainUIStateApi.Emulator {
    public static final Mame4droidStates INSTANCE = new Mame4droidStates();

    private static final long PRESS_MS = 150;
    /** slotTime when the file can't be looked at: maybe there, MAME will say. */
    private static final long UNKNOWN = 1;

    private static volatile Callable<String> installDir;

    private Mame4droidStates() {}

    /** The emulator activity is up: attach (with how to find MAME4droid's folder). */
    public static void attach(Callable<String> dir) {
        installDir = dir;
        MainUIStateApi.attach(INSTANCE);
    }

    private static char key(int slot) { return slot == MainUIStateApi.RESUME_SLOT ? 'm' : (char) ('0' + slot); }

    private static int keyCode(int slot) { return slot == MainUIStateApi.RESUME_SLOT ? KeyEvent.KEYCODE_M : KeyEvent.KEYCODE_0 + slot; }

    @Override public boolean isRunning() { return Emulator.isInGame(); }

    @Override
    public boolean isReady() {
        return resumed && Emulator.isInGameButNotInMenu() && !paused() && !Emulator.isInOptions() && !Emulator.isSaveorload();
    }

    /** The emulator activity is in front. */
    private static volatile boolean resumed;
    /** Emulator.isPaused() is still true from before the game started, though it runs (a cold start). */
    private static volatile boolean staleStart;

    public static void resumed(boolean r) { resumed = r; }

    /** The native core has started, running (called by the emulation thread). */
    public static void started() { staleStart = Emulator.isPaused(); }

    /** The game is really paused: Emulator's flag, unless left over from a cold start (a dialog of MAME4droid's pauses it). */
    private static boolean paused() {
        if (!Emulator.isPaused()) { staleStart = false; return false; }
        return !staleStart || DialogHelper.savedDialog != DialogHelper.DIALOG_NONE;
    }

    @Override public int slotCount() { return 10; }

    /** MainUI's pause took effect (not one the user or MAME4droid's menu holds). */
    private static volatile boolean pausedByMainUi;

    @Override
    public boolean pause() {
        if (!pausedByMainUi && paused()) return true;
        Emulator.pause();
        staleStart = false;
        pausedByMainUi = Emulator.isPaused();
        return pausedByMainUi;
    }

    @Override
    public void resume() {
        if (!pausedByMainUi) return;
        pausedByMainUi = false;
        Emulator.resume();
    }

    @Override
    public boolean liftOwnPause() {
        if (pausedByMainUi || !resumed || !paused()) return false;
        Emulator.resume();
        return !Emulator.isPaused();
    }

    @Override public void restoreOwnPause() { Emulator.pause(); }

    @Override
    public String save(int slot) throws Exception {
        File f = stateFile(slot);
        long before = f != null && f.isFile() ? f.lastModified() : 0;
        position(true, slot);
        if (f != null) {
            if (!MainUIStateApi.await(() -> f.isFile() && f.lastModified() != before, 15_000)) {
                throw new IllegalStateException("MAME didn't save the state (some games can't be saved)");
            }
            // MAME empties the file first and writes the compressed state after: done once it stops changing.
            final long[] last = {-1, -1};
            final int[] same = {0};
            if (!MainUIStateApi.await(() -> {
                long len = f.length(), mod = f.lastModified();
                same[0] = len > 0 && len == last[0] && mod == last[1] ? same[0] + 1 : 0;
                last[0] = len;
                last[1] = mod;
                Thread.sleep(100);
                return same[0] >= 3;
            }, 15_000)) {
                throw new IllegalStateException("MAME didn't finish saving the state");
            }
            return f.getAbsolutePath();
        }
        Thread.sleep(1500);
        return "MAME position " + key(slot);
    }

    @Override
    public void load(int slot) throws Exception {
        position(false, slot);
        Thread.sleep(1000);
    }

    @Override
    public long slotTime(int slot) {
        File f = stateFile(slot);
        if (f == null) return UNKNOWN;
        return f.isFile() ? f.lastModified() : 0;
    }

    /** MAME's file for [slot] in MAME4droid's folder, or null when it can't be told (states beside the roms). */
    private static File stateFile(int slot) {
        try {
            String dir = installDir != null ? installDir.call() : null;
            String game = Emulator.getValueStr(Emulator.GAME_SELECTED);
            if (dir == null || game == null || game.isEmpty()) return null;
            File sta = new File(dir, "sta");
            if (!sta.isDirectory()) return null;
            return new File(new File(sta, game), key(slot) + ".sta");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Presses MAME's save (or load) state key, then the position's key when MAME asks for it. */
    private static void position(boolean save, int slot) throws InterruptedException {
        int which = save ? Emulator.SAVESTATE : Emulator.LOADSTATE;
        Emulator.setSaveorload(true);
        Emulator.setValue(which, 1);
        Thread.sleep(PRESS_MS);
        Emulator.setValue(which, 0);
        Thread.sleep(300);
        Emulator.setKeyData(keyCode(slot), Emulator.KEY_DOWN, key(slot));
        Thread.sleep(PRESS_MS);
        Emulator.setKeyData(keyCode(slot), Emulator.KEY_UP, key(slot));
    }
}
