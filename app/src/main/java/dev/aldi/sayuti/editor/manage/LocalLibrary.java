package dev.aldi.sayuti.editor.manage;

import java.io.File;

/**
 * Represents a local library entry on disk.
 *
 * <p>Size is intentionally <b>not</b> computed at construction time. Computing
 * directory sizes recursively is expensive (one {@code stat} syscall per file
 * inside the library directory), and the previous implementation paid this cost
 * up-front for <em>every</em> library on every load — which is the main reason
 * the screen felt slow as the library count grew.
 *
 * <p>Instead, callers should hand the list returned by
 * {@link LocalLibrariesUtil#getAllLocalLibraries()} to
 * {@link LocalLibrariesUtil#computeSizesAsync(List, Runnable)}, which fills in
 * sizes on a background thread pool and uses a persistent cache keyed by
 * library name. The size string stays empty until then, and the adapter is
 * expected to render a placeholder.
 */
public class LocalLibrary {
    private final String name;
    private volatile String size = "";
    private boolean isSelected;

    public LocalLibrary(String name) {
        this.name = name;
    }

    /**
     * Builds a {@link LocalLibrary} from a directory entry. Performs no I/O
     * beyond reading the file's name — size lookup is deferred to
     * {@link LocalLibrariesUtil#computeSizesAsync}.
     */
    public static LocalLibrary fromFile(File file) {
        return new LocalLibrary(file.getName());
    }

    public String getName() {
        return name;
    }

    /**
     * Returns the formatted size, or an empty string if it has not been
     * computed yet. Adapters should treat an empty string as "loading".
     */
    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean isSelected) {
        this.isSelected = isSelected;
    }
}
