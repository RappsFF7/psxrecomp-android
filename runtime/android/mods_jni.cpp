/* JNI bridge from the Android launcher's Mods menu (ModsMenu.java) to the
 * runtime's own mod package manager (mod_packages.cpp).
 *
 * The launcher runs in its own process, before the game starts, and calls
 * these with the game's mods root (files/mods). Each call builds a fresh
 * manager, scans both catalog roots (bundled/ and installed/), loads
 * state.toml, does its one job and saves. Nothing is cached between calls,
 * so the menu always shows what is on disk, and every change goes through
 * the same parser, installer and state writer the desktop launcher uses:
 * archive checks, manifest validation, selections the menu does not know
 * about (options, resources, dormant packages) kept on save.
 *
 * Results come back as small JSON strings so the Java side needs no
 * per-field JNI plumbing. */
#if defined(__ANDROID__)

#include "mod_packages.h"

#include <jni.h>

#include <cstdio>
#include <filesystem>
#include <sstream>
#include <string>
#include <vector>

namespace {

using PSXRecompV4::ModFeature;
using PSXRecompV4::ModOption;
using PSXRecompV4::ModPackage;
using PSXRecompV4::ModPackageManager;
using PSXRecompV4::ModPackageOrigin;

std::string from_java(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_java(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::string json(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof buf, "\\u%04x", c);
                out += buf;
            } else {
                out += static_cast<char>(c);
            }
        }
    }
    return out + "\"";
}

/* The same manager setup as mod_runtime_initialize(): root, the title's
 * disabled-package policy, scan, then the player's saved state. */
bool open_manager(ModPackageManager& manager, const std::string& root, std::string* error) {
    manager.set_root(root);
#ifdef PSX_MOD_DISABLED_PACKAGES
    std::vector<std::string> disabled;
    std::istringstream stream(PSX_MOD_DISABLED_PACKAGES);
    for (std::string id; std::getline(stream, id, ',');)
        if (!id.empty()) disabled.push_back(id);
    manager.set_disabled_packages(std::move(disabled));
#endif
    return manager.scan(error) && manager.load_state(error);
}

std::string error_json(const std::string& error) { return "{\"ok\":false,\"error\":" + json(error) + "}"; }

std::string catalog_json(const ModPackageManager& manager) {
    std::string out = "{\"ok\":true,\"packages\":[";
    bool first_package = true;
    for (const auto& [id, versions] : manager.packages()) {
        const ModPackage* p = manager.selected_package(id);
        if (!p) continue;
        if (!first_package) out += ",";
        first_package = false;
        out += "{\"id\":" + json(p->id) + ",\"version\":" + json(p->version) +
               ",\"name\":" + json(p->name) + ",\"author\":" + json(p->author) +
               ",\"description\":" + json(p->description) +
               ",\"bundled\":" + (p->origin == ModPackageOrigin::Bundled ? "true" : "false") +
               ",\"shadows_bundled\":" + (p->shadows_bundled ? "true" : "false");
        /* Every installed (removable) version, so Remove takes them all. */
        out += ",\"installed_versions\":[";
        bool first_version = true;
        for (const auto& [version, package] : versions) {
            if (package.origin != ModPackageOrigin::Installed) continue;
            if (!first_version) out += ",";
            first_version = false;
            out += json(version);
        }
        out += "],\"games\":[";
        for (size_t i = 0; i < p->targets.size(); ++i)
            out += (i ? "," : "") + json(p->targets[i].game_id);
        out += "],\"features\":[";
        bool first_feature = true;
        for (const ModFeature& f : p->features) {
            if (f.hidden) continue;
            int options = 0;
            for (const ModOption& o : p->options)
                if (o.feature_id == f.id) ++options;
            if (!first_feature) out += ",";
            first_feature = false;
            out += "{\"id\":" + json(f.id) + ",\"name\":" + json(f.name) +
                   ",\"description\":" + json(f.description) + ",\"group\":" + json(f.group) +
                   ",\"enabled\":" + (manager.feature_enabled(p->id, f.id) ? "true" : "false") +
                   ",\"options\":" + std::to_string(options) + "}";
        }
        out += "]}";
    }
    out += "],\"errors\":[";
    const std::vector<std::string>& errors = manager.scan_errors();
    for (size_t i = 0; i < errors.size(); ++i) out += (i ? "," : "") + json(errors[i]);
    return out + "]}";
}

}  // namespace

/* {"ok":true,"packages":[...],"errors":[...]} or {"ok":false,"error":...}. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_psxrecomp_android_ModsNative_catalog(JNIEnv* env, jclass, jstring root) {
    ModPackageManager manager;
    std::string error;
    if (!open_manager(manager, from_java(env, root), &error)) return to_java(env, error_json(error));
    return to_java(env, catalog_json(manager));
}

/* Sets each (package, feature) switch and saves state.toml. Returns null on
 * success, else the error. Selections not named here are kept as they are. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_psxrecomp_android_ModsNative_setFeatures(JNIEnv* env, jclass, jstring root,
                                                  jobjectArray packages, jobjectArray features,
                                                  jbooleanArray enabled) {
    ModPackageManager manager;
    std::string error;
    if (!open_manager(manager, from_java(env, root), &error)) return to_java(env, error);
    const jsize n = env->GetArrayLength(packages);
    if (env->GetArrayLength(features) != n || env->GetArrayLength(enabled) != n)
        return to_java(env, "setFeatures: array lengths differ");
    std::vector<jboolean> on(static_cast<size_t>(n));
    if (n) env->GetBooleanArrayRegion(enabled, 0, n, on.data());
    for (jsize i = 0; i < n; ++i) {
        auto* p = static_cast<jstring>(env->GetObjectArrayElement(packages, i));
        auto* f = static_cast<jstring>(env->GetObjectArrayElement(features, i));
        const std::string package_id = from_java(env, p), feature_id = from_java(env, f);
        env->DeleteLocalRef(p);
        env->DeleteLocalRef(f);
        if (!manager.set_feature_enabled(package_id, feature_id, on[static_cast<size_t>(i)] != 0, &error))
            return to_java(env, package_id + "/" + feature_id + ": " + error);
    }
    if (!manager.save_state(&error)) return to_java(env, error);
    return nullptr;
}

/* Installs a .psxmod into installed/. {"ok":true,"id":...,"version":...} or
 * {"ok":false,"error":...}. New features start as their manifest's default. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_psxrecomp_android_ModsNative_install(JNIEnv* env, jclass, jstring root, jstring archive) {
    ModPackageManager manager;
    std::string error, id, version;
    if (!open_manager(manager, from_java(env, root), &error)) return to_java(env, error_json(error));
    if (!manager.install_archive(std::filesystem::path(from_java(env, archive)), &id, &version, &error))
        return to_java(env, error_json(error));
    return to_java(env, "{\"ok\":true,\"id\":" + json(id) + ",\"version\":" + json(version) + "}");
}

/* Removes every installed version of a package. Its features are switched off
 * and saved first (the manager refuses to remove an active version); a bundled
 * copy it shadowed comes back. Returns null on success, else the error. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_psxrecomp_android_ModsNative_remove(JNIEnv* env, jclass, jstring root, jstring package_id) {
    ModPackageManager manager;
    std::string error;
    const std::string id = from_java(env, package_id);
    if (!open_manager(manager, from_java(env, root), &error)) return to_java(env, error);
    const auto found = manager.packages().find(id);
    if (found == manager.packages().end()) return to_java(env, "package " + id + " is not installed");
    std::vector<std::string> installed;
    for (const auto& [version, package] : found->second)
        if (package.origin == ModPackageOrigin::Installed) installed.push_back(version);
    if (installed.empty()) return to_java(env, "cannot remove a bundled package; it ships with the game");
    if (const ModPackage* selected = manager.selected_package(id)) {
        if (selected->origin == ModPackageOrigin::Installed) {
            for (const ModFeature& f : selected->features)
                if (manager.feature_enabled(id, f.id) &&
                    !manager.set_feature_enabled(id, f.id, false, &error))
                    return to_java(env, error);
            if (!manager.save_state(&error)) return to_java(env, error);
        }
    }
    for (const std::string& version : installed)
        if (!manager.remove_version(id, version, &error)) return to_java(env, error);
    /* remove_version() deletes <installed>/<id>/<version>; drop the then-empty
     * <id> folder too (remove() leaves a non-empty one alone). */
    std::error_code ec;
    std::filesystem::remove(manager.installed_root() / id, ec);
    return nullptr;
}

#endif  // __ANDROID__
