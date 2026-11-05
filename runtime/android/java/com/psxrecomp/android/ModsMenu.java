package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On/off switches for the game's own mod features, shown from the start menu.
 *
 * The APK carries the game's mod catalog in assets/mods/bundled/<package>/<version>/
 * (the same layout the desktop build stages next to the exe). {@link #install} copies it
 * to files/mods/bundled, where the runtime looks for mods on Android, and this menu writes
 * files/mods/state.toml, the runtime's own record of which packages and features are on.
 * Changes apply the next time the game starts.
 *
 * Only plain on/off features are offered: the runtime's options, resources and quick-mod
 * lists are not edited here, and state.toml is rewritten with just the packages and features
 * the menu knows about.
 */
final class ModsMenu {
    private ModsMenu() {}

    static final String ASSET_ROOT = "mods/bundled";

    /** One [[feature]] of a package manifest. */
    static final class Feature {
        String id = "", name = "", description = "", group = "";
        boolean defaultEnabled;
    }

    /** One package manifest (assets/mods/bundled/<id>/<version>/manifest.toml). */
    static final class Pkg {
        String id = "", version = "", name = "", description = "";
        final List<Feature> features = new ArrayList<>();
    }

    /** True when this APK carries a mod catalog with at least one feature. */
    static boolean available(Context context) {
        for (Pkg p : load(context)) if (!p.features.isEmpty()) return true;
        return false;
    }

    /**
     * Replace files/mods/bundled with the APK's catalog. Run before every game start, so an
     * updated APK's manifests always win; state.toml (the player's choices) is kept.
     */
    static void install(Context context) throws IOException {
        String[] packages = context.getAssets().list(ASSET_ROOT);
        File bundled = new File(context.getFilesDir(), ASSET_ROOT);
        PsxFiles.deleteRecursively(bundled);
        if (packages == null || packages.length == 0) return;
        copyTree(context, ASSET_ROOT, bundled);
    }

    private static void copyTree(Context context, String asset, File dest) throws IOException {
        String[] children = context.getAssets().list(asset);
        if (children == null || children.length == 0) {
            PsxFiles.copyAsset(context, asset, dest);
            return;
        }
        for (String child : children) copyTree(context, asset + "/" + child, new File(dest, child));
    }

    static List<Pkg> load(Context context) {
        List<Pkg> out = new ArrayList<>();
        try {
            String[] ids = context.getAssets().list(ASSET_ROOT);
            if (ids == null) return out;
            for (String id : ids) {
                String[] versions = context.getAssets().list(ASSET_ROOT + "/" + id);
                if (versions == null) continue;
                for (String version : versions) {
                    String path = ASSET_ROOT + "/" + id + "/" + version + "/manifest.toml";
                    try {
                        out.add(parse(PsxFiles.readAsset(context, path)));
                    } catch (IOException e) {
                        // Not a package version folder (a README, say).
                    }
                }
            }
        } catch (IOException e) {
            Log.w("psxrecomp", "mods: cannot list the bundled catalog", e);
        }
        return out;
    }

    /** Reads the few manifest keys the menu shows. Not a general TOML parser. */
    static Pkg parse(String text) {
        Pkg pkg = new Pkg();
        Feature feature = null;
        String table = "";
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                table = line;
                if (line.equals("[[feature]]")) {
                    feature = new Feature();
                    pkg.features.add(feature);
                } else {
                    feature = null;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (table.isEmpty()) {
                if (key.equals("id")) pkg.id = string(value);
                else if (key.equals("version")) pkg.version = string(value);
                else if (key.equals("name")) pkg.name = string(value);
                else if (key.equals("description")) pkg.description = string(value);
            } else if (feature != null) {
                if (key.equals("id")) feature.id = string(value);
                else if (key.equals("name")) feature.name = string(value);
                else if (key.equals("description")) feature.description = string(value);
                else if (key.equals("group")) feature.group = string(value);
                else if (key.equals("default_enabled")) feature.defaultEnabled = value.startsWith("true");
            }
        }
        return pkg;
    }

    /** A basic TOML string: "..." with \" \\ \n escapes. */
    private static String string(String value) {
        if (!value.startsWith("\"")) return value;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static File stateFile(Context context) {
        return new File(context.getFilesDir(), "mods/state.toml");
    }

    /** Feature choices from state.toml, keyed "package/feature". */
    static Map<String, Boolean> readState(Context context) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        File file = stateFile(context);
        if (!file.isFile()) return out;
        try {
            String pkg = null, id = null;
            Boolean enabled = null;
            boolean inFeature = false;
            for (String raw : (PsxFiles.readText(file) + "\n[end]").split("\n")) {
                String line = raw.trim();
                if (line.startsWith("[")) {
                    if (inFeature && pkg != null && id != null && enabled != null)
                        out.put(pkg + "/" + id, enabled);
                    inFeature = line.equals("[[feature]]");
                    pkg = id = null;
                    enabled = null;
                    continue;
                }
                if (!inFeature) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.equals("package_id")) pkg = string(value);
                else if (key.equals("id")) id = string(value);
                else if (key.equals("enabled")) enabled = value.startsWith("true");
            }
        } catch (IOException e) {
            Log.w("psxrecomp", "mods: cannot read state.toml", e);
        }
        return out;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Rewrites state.toml (format 2): every package enabled, each feature as chosen. */
    static void writeState(Context context, List<Pkg> packages, Map<String, Boolean> choice)
            throws IOException {
        StringBuilder sb = new StringBuilder("format_version = 2\n");
        for (Pkg p : packages) {
            sb.append("\n[[package]]\nid = ").append(quote(p.id))
              .append("\nenabled = true\nversion = ").append(quote(p.version)).append("\n");
        }
        for (Pkg p : packages) {
            for (Feature f : p.features) {
                Boolean on = choice.get(p.id + "/" + f.id);
                sb.append("\n[[feature]]\npackage_id = ").append(quote(p.id))
                  .append("\nid = ").append(quote(f.id))
                  .append("\nenabled = ").append(on != null ? on : f.defaultEnabled).append("\n");
            }
        }
        File file = stateFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Unable to create " + parent.getName());
        File tmp = new File(parent, "state.toml.tmp");
        PsxFiles.writeText(tmp, sb.toString());
        if (!tmp.renameTo(file)) throw new IOException("Unable to save state.toml");
    }

    /** The Mods dialog: one switch per feature, grouped, with its description. */
    static void show(Activity activity) {
        final List<Pkg> packages = load(activity);
        final Map<String, Boolean> choice = readState(activity);
        float dp = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, (int) (8 * dp), pad, (int) (8 * dp));

        TextView intro = new TextView(activity);
        intro.setText("Changes apply the next time the game starts. "
                + "Save states only load with the same mods on or off as when they were made.");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setPadding(0, 0, 0, (int) (8 * dp));
        list.addView(intro);

        // Grouped like the desktop Features list: group heading, then each feature.
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (Pkg p : packages)
            for (Feature f : p.features)
                groups.computeIfAbsent(f.group.isEmpty() ? "Other" : f.group, k -> new ArrayList<>())
                      .add(new Object[] { p, f });

        for (Map.Entry<String, List<Object[]>> group : groups.entrySet()) {
            TextView heading = new TextView(activity);
            heading.setText(group.getKey());
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            heading.setPadding(0, (int) (14 * dp), 0, (int) (2 * dp));
            list.addView(heading);
            for (Object[] pf : group.getValue()) {
                final Pkg p = (Pkg) pf[0];
                final Feature f = (Feature) pf[1];
                final String key = p.id + "/" + f.id;
                Switch sw = new Switch(activity);
                sw.setText(f.name.isEmpty() ? f.id : f.name);
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                Boolean on = choice.get(key);
                sw.setChecked(on != null ? on : f.defaultEnabled);
                sw.setPadding(0, (int) (10 * dp), 0, 0);
                sw.setOnCheckedChangeListener((b, checked) -> choice.put(key, checked));
                list.addView(sw);
                TextView desc = new TextView(activity);
                // The package name beneath the feature, as on desktop, unless it only repeats it.
                String pkgName = p.name.isEmpty() ? p.id : p.name;
                desc.setText(pkgName.equals(f.name) ? f.description : pkgName + "\n" + f.description);
                desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                desc.setAlpha(0.75f);
                list.addView(desc);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        new AlertDialog.Builder(activity)
                .setTitle("Mods")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        writeState(activity, packages, choice);
                    } catch (IOException e) {
                        Log.e("psxrecomp", "mods: cannot save state.toml", e);
                        new AlertDialog.Builder(activity).setMessage("Could not save the mod choices: "
                                + e.getMessage()).setPositiveButton("OK", null).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
