package com.besome.sketch.lib.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import a.a.a.DB;
import a.a.a.wq;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.GsonUtils;

/**
 * TEMPORARY MIGRATION UTILITY
 *
 * Migrates legacy custom colors & Material 3 attribute names from
 * SharedPreferences (DB "P24" / keys "P24I1", "P24I2") to the new
 * file-based storage at {@link wq#getCustomColors()}.
 *
 * Why: older versions stored these in SharedPreferences, which get wiped
 * when the app is uninstalled. The new storage lives in
 * .sketchware/data/system/ and survives uninstall.
 *
 * HOW TO REMOVE LATER
 * -------------------
 * Once enough time has passed that everyone is on the new version:
 *   1. Delete this file (LegacyCustomColorsMigrator.java).
 *   2. Delete the single line in ColorPickerDialog.initialize():
 *          LegacyCustomColorsMigrator.migrateIfNeeded(activity);
 *   Nothing else depends on this class.
 */
final class LegacyCustomColorsMigrator {

    private static final String LEGACY_PREF_NAME = "P24";
    private static final String LEGACY_COLORS_KEY = "P24I1";
    private static final String LEGACY_ATTRS_KEY = "P24I2";

    private LegacyCustomColorsMigrator() {
        // no instances
    }

    static void migrateIfNeeded(Context context) {
        // Already migrated (or user simply has no legacy data) -> nothing to do.
        if (FileUtil.isExistFile(wq.getCustomColors())) {
            return;
        }

        DB legacyPref = new DB(context, LEGACY_PREF_NAME);

        ArrayList<String> colors = splitCommaSeparated(legacyPref.f(LEGACY_COLORS_KEY));
        ArrayList<String> attrs = splitCommaSeparated(legacyPref.f(LEGACY_ATTRS_KEY));

        if (colors.isEmpty() && attrs.isEmpty()) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("colors", colors);
        data.put("attrs", attrs);

        FileUtil.writeFile(wq.getCustomColors(), GsonUtils.getGson().toJson(data));
    }

    private static ArrayList<String> splitCommaSeparated(String raw) {
        ArrayList<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}