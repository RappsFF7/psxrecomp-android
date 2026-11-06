package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher's Mods menu, in two tabs like the desktop launcher's:
 *
 * - Features: one switch per mod feature, grouped, with "All mods" on top.
 *   Changes are kept until Save, and apply the next time the game starts.
 * - Packages: the mods the game ships with (built in, cannot be removed) and
 *   the ones the player added. "Add mod" installs a .psxmod file picked from
 *   the phone's storage; Remove deletes one the player added.
 *
 * The catalog, the switches, installing and removing all go through the
 * runtime's package manager (ModsNative), which reads files/mods/bundled
 * (the APK's catalog, copied by {@link #install}) and files/mods/installed,
 * and keeps files/mods/state.toml.
 */
final class ModsMenu {
    static final String ASSET_ROOT = "mods/bundled";
    static final int REQUEST_MOD = 84;
    private static final String TAG = "psxrecomp";

    /**
     * Replace files/mods/bundled with the APK's catalog. Run before every game start
     * and when the menu opens, so an updated APK's manifests always win; installed/
     * and state.toml (the player's own) are never touched.
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

    /* ---- catalog ---------------------------------------------------------- */

    static final class Feature {
        String id, name, description, group;
        boolean enabled;
        int options;
    }

    static final class Pkg {
        String id, version, name, author, description;
        boolean bundled, shadowsBundled;
        final List<String> games = new ArrayList<>();
        final List<Feature> features = new ArrayList<>();
    }

    private static List<Pkg> parseCatalog(JSONObject o) throws JSONException {
        List<Pkg> out = new ArrayList<>();
        JSONArray packages = o.getJSONArray("packages");
        for (int i = 0; i < packages.length(); i++) {
            JSONObject p = packages.getJSONObject(i);
            Pkg pkg = new Pkg();
            pkg.id = p.getString("id");
            pkg.version = p.optString("version");
            pkg.name = p.optString("name");
            if (pkg.name.isEmpty()) pkg.name = pkg.id;
            pkg.author = p.optString("author");
            pkg.description = p.optString("description");
            pkg.bundled = p.optBoolean("bundled");
            pkg.shadowsBundled = p.optBoolean("shadows_bundled");
            JSONArray games = p.optJSONArray("games");
            if (games != null) for (int g = 0; g < games.length(); g++) pkg.games.add(games.getString(g));
            JSONArray features = p.getJSONArray("features");
            for (int f = 0; f < features.length(); f++) {
                JSONObject j = features.getJSONObject(f);
                Feature feat = new Feature();
                feat.id = j.getString("id");
                feat.name = j.optString("name");
                if (feat.name.isEmpty()) feat.name = feat.id;
                feat.description = j.optString("description");
                feat.group = j.optString("group");
                if (feat.group.isEmpty()) feat.group = "Other";
                feat.enabled = j.optBoolean("enabled");
                feat.options = j.optInt("options");
                pkg.features.add(feat);
            }
            out.add(pkg);
        }
        return out;
    }

    /* ---- the dialog ------------------------------------------------------- */

    /** The open menu, so the launcher can hand it the picked .psxmod file. */
    private static ModsMenu open;

    private final Activity activity;
    private final String root;
    private final String gameId;
    private final float dp;
    /** Unsaved switch choices, "package/feature" → on. Survive install/remove. */
    private final Map<String, Boolean> choice = new HashMap<>();
    private List<Pkg> packages = new ArrayList<>();
    private List<String> scanErrors = new ArrayList<>();
    private boolean packagesTab;

    private AlertDialog dialog;
    private FrameLayout content;
    private TextView featuresTab, packagesTabView;

    private ModsMenu(Activity activity) {
        this.activity = activity;
        this.root = new File(activity.getFilesDir(), "mods").getAbsolutePath();
        int id = activity.getResources().getIdentifier("psx_game_id", "string", activity.getPackageName());
        this.gameId = id != 0 ? activity.getString(id) : "";
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    static void show(Activity activity) {
        String failure = ModsNative.load();
        if (failure == null) {
            try {
                install(activity);
            } catch (IOException e) {
                failure = "Cannot copy the game's mods: " + e.getMessage();
            }
        }
        if (failure != null) {
            message(activity, "Mods", failure);
            return;
        }
        ModsMenu menu = new ModsMenu(activity);
        if (!menu.reload()) return;
        menu.build();
    }

    /** Reads the catalog again. False (after saying why) when it cannot be read. */
    private boolean reload() {
        try {
            JSONObject o = new JSONObject(ModsNative.catalog(root));
            if (!o.optBoolean("ok")) {
                message(activity, "Mods", "Cannot read the mods: " + o.optString("error"));
                return false;
            }
            packages = parseCatalog(o);
            scanErrors = new ArrayList<>();
            JSONArray errors = o.optJSONArray("errors");
            if (errors != null) for (int i = 0; i < errors.length(); i++) scanErrors.add(errors.getString(i));
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "mods: bad catalog", e);
            message(activity, "Mods", "Cannot read the mods: " + e.getMessage());
            return false;
        }
    }

    private void build() {
        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);

        // Header: title on the left, the two tabs on the right.
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(24), px(16), px(16), 0);
        TextView title = new TextView(activity);
        title.setText("Mods");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        featuresTab = tab("Features", false);
        packagesTabView = tab("Packages", true);
        header.addView(featuresTab);
        header.addView(packagesTabView);
        frame.addView(header);

        content = new FrameLayout(activity);
        frame.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog = new AlertDialog.Builder(activity)
                .setView(frame)
                .setPositiveButton("Save", (d, w) -> save())
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnDismissListener(d -> { if (open == this) open = null; });
        open = this;
        showTab(false);
        dialog.show();
    }

    private TextView tab(String label, boolean isPackages) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(px(14), px(8), px(14), px(8));
        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
            t.setBackgroundResource(ripple.resourceId);
        t.setOnClickListener(v -> showTab(isPackages));
        return t;
    }

    private void showTab(boolean packages) {
        packagesTab = packages;
        for (TextView t : new TextView[] { featuresTab, packagesTabView }) {
            boolean selected = (t == packagesTabView) == packages;
            t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setAlpha(selected ? 1f : 0.55f);
            t.setPaintFlags(selected ? t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    : t.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }
        content.removeAllViews();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(packages ? packagesView() : featuresView());
        content.addView(scroll);
    }

    private LinearLayout column() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(24), px(8), px(24), px(8));
        return list;
    }

    private TextView text(String s, int sp, float alpha) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setAlpha(alpha);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 16, 1f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(14), 0, px(2));
        return t;
    }

    private boolean forThisGame(Pkg p) {
        if (p.games.isEmpty() || gameId.isEmpty()) return true;
        for (String g : p.games) if (g.equals(gameId) || g.equals("*")) return true;
        return false;
    }

    private static String key(Pkg p, Feature f) { return p.id + "/" + f.id; }

    private boolean isOn(Pkg p, Feature f) {
        Boolean on = choice.get(key(p, f));
        return on != null ? on : f.enabled;
    }

    /* ---- Features tab ----------------------------------------------------- */

    private View featuresView() {
        LinearLayout list = column();
        list.addView(text("Changes apply the next time the game starts. "
                + "Save states only load with the same mods on or off as when they were made.", 13, 1f));

        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (Pkg p : packages)
            for (Feature f : p.features)
                groups.computeIfAbsent(f.group, k -> new ArrayList<>()).add(new Object[] { p, f });
        if (groups.isEmpty()) {
            TextView none = text("No mods yet. Add one on the Packages tab.", 14, 0.75f);
            none.setPadding(0, px(14), 0, 0);
            list.addView(none);
            return list;
        }

        // One switch that turns every feature on or off; "on" only while every one is.
        final Switch all = new Switch(activity);
        all.setText("All mods");
        all.setTypeface(Typeface.DEFAULT_BOLD);
        all.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        all.setPadding(0, px(10), 0, px(6));
        list.addView(all);
        final List<Switch> switches = new ArrayList<>();
        final boolean[] syncing = { false };
        final Runnable syncAll = () -> {
            boolean every = !switches.isEmpty();
            for (Switch s : switches) every &= s.isChecked();
            syncing[0] = true;
            all.setChecked(every);
            syncing[0] = false;
        };
        all.setOnCheckedChangeListener((b, checked) -> {
            if (syncing[0]) return;
            syncing[0] = true;
            for (Switch s : switches) s.setChecked(checked);   // each one records its choice
            syncing[0] = false;
        });

        for (Map.Entry<String, List<Object[]>> group : groups.entrySet()) {
            list.addView(heading(group.getKey()));
            for (Object[] pf : group.getValue()) {
                final Pkg p = (Pkg) pf[0];
                final Feature f = (Feature) pf[1];
                Switch sw = new Switch(activity);
                sw.setText(f.name);
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                sw.setChecked(isOn(p, f));
                sw.setPadding(0, px(10), 0, 0);
                sw.setOnCheckedChangeListener((b, checked) -> {
                    choice.put(key(p, f), checked);
                    if (!syncing[0]) syncAll.run();
                });
                /* A mod made for another game would stop this one from starting: it can
                 * only be switched off here (if it was on), and "All mods" leaves it alone. */
                if (forThisGame(p)) switches.add(sw);
                else sw.setEnabled(sw.isChecked());
                list.addView(sw);
                // The package name beneath the feature, as on desktop, unless it only repeats it.
                StringBuilder desc = new StringBuilder();
                if (!p.name.equals(f.name)) desc.append(p.name).append('\n');
                desc.append(f.description);
                if (!forThisGame(p)) desc.append("\n⚠ Made for another game.");
                if (f.options > 0) desc.append("\nUses its default settings (they can't be changed here).");
                list.addView(text(desc.toString(), 12, 0.75f));
            }
        }
        syncAll.run();
        return list;
    }

    private void save() {
        List<String> ids = new ArrayList<>(), features = new ArrayList<>();
        List<Boolean> on = new ArrayList<>();
        for (Pkg p : packages)
            for (Feature f : p.features) {
                Boolean chosen = choice.get(key(p, f));
                if (chosen == null || chosen == f.enabled) continue;
                ids.add(p.id);
                features.add(f.id);
                on.add(chosen);
            }
        if (ids.isEmpty()) return;
        boolean[] values = new boolean[on.size()];
        for (int i = 0; i < values.length; i++) values[i] = on.get(i);
        String error = ModsNative.setFeatures(root, ids.toArray(new String[0]),
                features.toArray(new String[0]), values);
        if (error != null) message(activity, "Mods", "Could not save the mod choices: " + error);
    }

    /* ---- Packages tab ----------------------------------------------------- */

    private View packagesView() {
        LinearLayout list = column();
        list.addView(text("Mods come as .psxmod files. They can change the game's data, text and "
                + "pictures; they can't add new program code. Added mods start switched off "
                + "unless they say otherwise: turn them on in Features.", 13, 1f));
        Button add = new Button(activity);
        add.setText("Add mod (.psxmod)");
        add.setOnClickListener(v -> pick());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        list.addView(add, lp);

        List<Pkg> added = new ArrayList<>(), builtIn = new ArrayList<>();
        for (Pkg p : packages) (p.bundled ? builtIn : added).add(p);
        list.addView(heading("Added by you"));
        if (added.isEmpty()) list.addView(text("None yet.", 13, 0.75f));
        for (Pkg p : added) list.addView(packageRow(p, true));
        if (!builtIn.isEmpty()) {
            list.addView(heading("Built in"));
            for (Pkg p : builtIn) list.addView(packageRow(p, false));
        }
        if (!scanErrors.isEmpty()) {
            list.addView(heading("Could not be read"));
            for (String e : scanErrors) list.addView(text(e, 12, 0.75f));
        }
        return list;
    }

    private View packageRow(Pkg p, boolean removable) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(8), 0, px(4));
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(p.name + "  " + p.version, 15, 1f);
        info.addView(name);
        StringBuilder sub = new StringBuilder();
        if (!p.author.isEmpty()) sub.append("by ").append(p.author).append('\n');
        if (!p.description.isEmpty()) sub.append(p.description).append('\n');
        if (p.shadowsBundled) sub.append("Replaces the built-in version.\n");
        if (!forThisGame(p)) sub.append("⚠ Made for another game: ").append(String.join(", ", p.games)).append('\n');
        if (sub.length() > 0) info.addView(text(sub.toString().trim(), 12, 0.75f));
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (removable) {
            Button remove = new Button(activity);
            remove.setText("Remove");
            remove.setOnClickListener(v -> confirmRemove(p));
            row.addView(remove);
        }
        return row;
    }

    private void confirmRemove(Pkg p) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove " + p.name + "?")
                .setMessage("Its features are switched off and its files deleted. "
                        + "Save states made with it on won't load until it's added back.")
                .setPositiveButton("Remove", (d, w) -> {
                    String error = ModsNative.remove(root, p.id);
                    if (error != null) {
                        message(activity, "Could not remove " + p.name, error);
                        return;
                    }
                    for (Feature f : p.features) choice.remove(key(p, f));
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Catalog changed on disk: read it again and redraw the current tab. */
    private void refresh() {
        if (reload()) showTab(packagesTab);
    }

    /* ---- adding a .psxmod ------------------------------------------------- */

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // .psxmod has no registered MIME type; providers report it as a zip or as binary.
        intent.setType("*/*");
        activity.startActivityForResult(intent, REQUEST_MOD);
    }

    /** From the launcher's onActivityResult: the .psxmod the player picked. */
    static void onPicked(Activity activity, Uri uri) {
        ModsMenu menu = open;
        if (menu == null || menu.activity != activity) return;
        menu.installFrom(uri);
    }

    private void installFrom(Uri uri) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage("Adding the mod…");
        progress.setCancelable(false);
        progress.show();
        new Thread(() -> {
            String result;
            File temp = new File(activity.getCacheDir(), "import.psxmod");
            try {
                String name = displayName(uri);
                if (name != null && !name.toLowerCase(java.util.Locale.US).endsWith(".psxmod")
                        && !name.toLowerCase(java.util.Locale.US).endsWith(".zip"))
                    throw new IOException("\"" + name + "\" is not a .psxmod file.");
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Cannot open the selected file.");
                    byte[] buf = new byte[256 * 1024];
                    for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
                }
                result = ModsNative.install(root, temp.getAbsolutePath());
            } catch (IOException e) {
                result = errorJson(e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            final String done = result;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                installed(done);
            });
        }, "psx-mod-install").start();
    }

    private void installed(String json) {
        String error, id = null;
        try {
            JSONObject o = new JSONObject(json);
            error = o.optBoolean("ok") ? null : o.optString("error");
            id = o.optString("id", null);
        } catch (JSONException e) {
            error = e.getMessage();
        }
        if (error != null) {
            message(activity, "Could not add the mod", error);
            return;
        }
        refresh();
        for (Pkg p : packages) {
            if (!p.id.equals(id)) continue;
            if (!forThisGame(p))
                message(activity, "Mod added", p.name + " is made for another game ("
                        + String.join(", ", p.games) + "). It will not apply to this one.");
            else
                Toast.makeText(activity, "Added " + p.name + ". Turn it on in Features.",
                        Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // Some providers have no display name; the installer checks the contents anyway.
        }
        return null;
    }

    private static String errorJson(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"\"}";
        }
    }

    private int px(int dpValue) { return (int) (dpValue * dp); }

    private static void message(Activity activity, String title, String text) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(text)
                .setPositiveButton("OK", null).show();
    }
}
