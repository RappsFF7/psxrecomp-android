package com.psxrecomp.android;

/**
 * The runtime's mod package manager (runtime/android/mods_jni.cpp), for the
 * launcher's Mods menu. Each call scans files/mods (bundled/ and installed/),
 * loads state.toml, does its job and saves, so the menu uses the same manifest
 * parser, .psxmod installer and state writer as the game and the desktop
 * launcher.
 *
 * Loading libmain.so in the launcher process only runs its JNI glue; the game
 * itself (SDL) only ever starts in the :game process.
 */
final class ModsNative {
    private ModsNative() {}

    private static boolean loaded;
    private static String loadError;

    /** Loads libmain.so once; null when ready, else why it could not load. */
    static synchronized String load() {
        if (loaded) return null;
        if (loadError != null) return loadError;
        try {
            System.loadLibrary("main");
            loaded = true;
            return null;
        } catch (Throwable t) {
            loadError = "Cannot load the game library: " + t.getMessage();
            return loadError;
        }
    }

    /** {"ok":true,"packages":[...],"errors":[...]} or {"ok":false,"error":...}. */
    static native String catalog(String modsRoot);

    /** Sets each package/feature switch and saves; null on success, else the error. */
    static native String setFeatures(String modsRoot, String[] packageIds, String[] featureIds,
                                     boolean[] enabled);

    /** Installs a .psxmod: {"ok":true,"id":...,"version":...} or {"ok":false,"error":...}. */
    static native String install(String modsRoot, String archivePath);

    /** Removes every installed version of a package; null on success, else the error. */
    static native String remove(String modsRoot, String packageId);
}
