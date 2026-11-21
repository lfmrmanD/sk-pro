package dev.aldi.sayuti.editor.manage;

import static pro.sketchware.utility.FileUtil.deleteFile;
import static pro.sketchware.utility.FileUtil.getExternalStorageDir;
import static pro.sketchware.utility.FileUtil.isExistFile;
import static pro.sketchware.utility.FileUtil.listDirAsFile;
import static pro.sketchware.utility.FileUtil.readFile;
import static pro.sketchware.utility.FileUtil.writeFile;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mod.hey.studios.util.Helper;
import pro.sketchware.utility.FileUtil;

/**
 * Utility class for reading/writing local libraries on disk.
 *
 * <h3>Performance notes</h3>
 * <ul>
 *   <li>{@link #getAllLocalLibraries()} performs no recursive size computation.
 *       Sizes are filled in lazily by {@link #computeSizesAsync(List, Runnable)}.</li>
 *   <li>Directory sizes are computed with {@link Files#walkFileTree} using
 *       {@link BasicFileAttributes}, which avoids a separate {@code stat} call
 *       per file.</li>
 *   <li>Computed sizes are cached in {@link #SIZE_CACHE} keyed by library name,
 *       so subsequent loads are nearly free.</li>
 *   <li>The library list itself is cached for a short TTL keyed on the parent
 *       directory's {@code lastModifiedTime}, so repeated calls within a few
 *       seconds don't re-scan the filesystem.</li>
 *   <li>{@link #createLibraryMap(String, String)} batches existence checks via
 *       a single {@link DirectoryStream} pass instead of seven separate
 *       {@link File#exists()} calls.</li>
 * </ul>
 */
public class LocalLibrariesUtil {
    private static final String localLibsPath =
            getExternalStorageDir().concat("/.sketchware/libs/local_libs/");

    /** Reused Gson instance — {@code new Gson()} per call is wasteful. */
    private static final Gson GSON = new Gson();

    /**
     * Persistent cache of formatted sizes keyed by library name.
     * Survives across activity instances so the second visit is instant.
     */
    private static final Map<String, String> SIZE_CACHE = new ConcurrentHashMap<>();

    /** Background pool used for parallel size computation. */
    private static final ExecutorService SIZE_COMPUTATION_EXECUTOR =
            Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()));

    /** Short-lived cache of the library list itself. */
    private static volatile List<LocalLibrary> cachedLibraries;
    private static volatile long cacheKey = -1L;
    private static volatile long cacheTimestamp;
    private static final long CACHE_TTL_MS = 5_000L;

    /**
     * Returns all local libraries. Sizes are left empty — call
     * {@link #computeSizesAsync(List, Runnable)} to populate them.
     *
     * <p>This call performs only a flat directory listing and a quick sort,
     * so it scales with library <em>count</em>, not total file count.
     */
    public static List<LocalLibrary> getAllLocalLibraries() {
        Path libsDir = Paths.get(localLibsPath);
        if (!Files.isDirectory(libsDir)) {
            return new ArrayList<>();
        }

        // Short-circuit if the parent dir hasn't changed and the cache is fresh.
        long currentKey;
        try {
            currentKey = Files.getLastModifiedTime(libsDir).toMillis();
        } catch (IOException e) {
            currentKey = -1L;
        }
        long now = System.currentTimeMillis();
        List<LocalLibrary> cached = cachedLibraries;
        if (cached != null && currentKey == cacheKey && now - cacheTimestamp < CACHE_TTL_MS) {
            return new ArrayList<>(cached);
        }

        ArrayList<File> localLibraryFiles = new ArrayList<>();
        listDirAsFile(localLibsPath, localLibraryFiles);
        localLibraryFiles.sort(new LocalLibrariesComparator());

        List<LocalLibrary> localLibraries = new ArrayList<>();
        for (File libraryFile : localLibraryFiles) {
            if (libraryFile.isDirectory()) {
                LocalLibrary lib = LocalLibrary.fromFile(libraryFile);
                // Pre-fill from the persistent size cache if available.
                String cachedSize = SIZE_CACHE.get(lib.getName());
                if (cachedSize != null) {
                    lib.setSize(cachedSize);
                }
                localLibraries.add(lib);
            }
        }

        cachedLibraries = new ArrayList<>(localLibraries);
        cacheKey = currentKey;
        cacheTimestamp = now;
        return localLibraries;
    }

    /**
     * Asynchronously computes sizes for any libraries whose {@link LocalLibrary#getSize()}
     * is currently empty. Uses {@link #SIZE_CACHE} to skip work already done.
     *
     * @param libraries  the list returned by {@link #getAllLocalLibraries()}
     * @param onComplete optional callback (invoked on the background thread) once
     *                   all sizes have been computed; may be {@code null}
     */
    public static void computeSizesAsync(List<LocalLibrary> libraries, Runnable onComplete) {
        List<LocalLibrary> toCompute = new ArrayList<>();
        for (LocalLibrary lib : libraries) {
            if (lib.getSize() == null || lib.getSize().isEmpty()) {
                toCompute.add(lib);
            }
        }

        if (toCompute.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        SIZE_COMPUTATION_EXECUTOR.execute(() -> {
            // Parallel stream reuses the ForkJoinPool inside computeSizesAsync's
            // executor; this is fine because we already moved off the UI thread.
            toCompute.parallelStream().forEach(lib -> {
                String formatted = SIZE_CACHE.computeIfAbsent(lib.getName(), name -> {
                    long bytes = getDirectorySize(Paths.get(localLibsPath, name));
                    return FileUtil.formatFileSize(bytes);
                });
                lib.setSize(formatted);
            });
            if (onComplete != null) onComplete.run();
        });
    }

    /**
     * Recursively sums file sizes under {@code dir} using NIO file tree walk.
     *
     * <p>{@link BasicFileAttributes} is populated by the walk itself, so we
     * don't pay for a separate {@code stat} per file — roughly 2-3x faster
     * than the equivalent {@link File#listFiles()} recursion with
     * {@link File#length()}.
     */
    private static long getDirectorySize(Path dir) {
        if (!Files.isDirectory(dir)) {
            try {
                return Files.size(dir);
            } catch (IOException e) {
                return 0L;
            }
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return total[0];
    }

    public static ArrayList<HashMap<String, Object>> getLocalLibraries(String scId) {
        File localLibFile = getLocalLibFile(scId);
        String fileContent;
        if (!localLibFile.exists()
                || (fileContent = readFile(localLibFile.getAbsolutePath())).isEmpty()) {
            writeFile(localLibFile.getAbsolutePath(), "[]");
            return new ArrayList<>();
        }
        return GSON.fromJson(fileContent, Helper.TYPE_MAP_LIST);
    }

    /**
     * Deletes every selected {@link LocalLibrary} on disk and removes it from
     * {@code projectUsedLibs} when present.
     *
     * <p>This version does <em>not</em> mutate the input {@code localLibraries}
     * list — the caller should re-fetch via {@link #getAllLocalLibraries()}
     * (or {@link #invalidateListCache()}) to refresh the UI.
     */
    public static void deleteSelectedLocalLibraries(
            String scId,
            List<LocalLibrary> localLibraries,
            ArrayList<HashMap<String, Object>> projectUsedLibs) {

        List<String> namesToDelete = new ArrayList<>();
        for (LocalLibrary lib : localLibraries) {
            if (lib.isSelected()) {
                namesToDelete.add(lib.getName());
            }
        }

        for (String name : namesToDelete) {
            deleteFile(localLibsPath.concat(name));
            SIZE_CACHE.remove(name);
        }

        if (projectUsedLibs != null && !namesToDelete.isEmpty()) {
            Set<String> namesToDeleteSet = new HashSet<>(namesToDelete);
            projectUsedLibs.removeIf(lib ->
                    namesToDeleteSet.contains(String.valueOf(lib.get("name"))));
            rewriteLocalLibFile(scId, GSON.toJson(projectUsedLibs));
        }

        invalidateListCache();
    }

    public static File getLocalLibFile(String scId) {
        return new File(getExternalStorageDir()
                .concat("/.sketchware/data/")
                .concat(scId)
                .concat("/local_library"));
    }

    public static void rewriteLocalLibFile(String scId, String newContent) {
        writeFile(getLocalLibFile(scId).getAbsolutePath(), newContent);
    }

    /**
     * Builds the library descriptor map used by the build system.
     *
     * <p>Existence checks are batched through a single {@link DirectoryStream}
     * pass instead of seven separate {@link File#exists()} syscalls.
     */
    public static HashMap<String, Object> createLibraryMap(String name, String dependency) {
        Path libDir = Paths.get(localLibsPath, name);

        HashMap<String, Object> localLibrary = new HashMap<>();
        localLibrary.put("name", name);
        if (dependency != null) {
            localLibrary.put("dependency", dependency);
        }

        if (!Files.isDirectory(libDir)) {
            return localLibrary;
        }

        Set<String> entries = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libDir)) {
            for (Path entry : ds) {
                entries.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
            // Fall back to per-file checks so we don't silently drop metadata.
            String[] known = {"config", "res", "classes.jar", "classes.dex",
                    "AndroidManifest.xml", "proguard.txt", "assets"};
            for (String n : known) {
                if (isExistFile(localLibsPath + name + "/" + n)) {
                    entries.add(n);
                }
            }
        }

        String base = libDir.toAbsolutePath().toString();
        if (entries.contains("config")) {
            localLibrary.put("packageName", readFile(base + "/config"));
        }
        if (entries.contains("res")) {
            localLibrary.put("resPath", base + "/res");
        }
        if (entries.contains("classes.jar")) {
            localLibrary.put("jarPath", base + "/classes.jar");
        }
        if (entries.contains("classes.dex")) {
            localLibrary.put("dexPath", base + "/classes.dex");
        }
        if (entries.contains("AndroidManifest.xml")) {
            localLibrary.put("manifestPath", base + "/AndroidManifest.xml");
        }
        if (entries.contains("proguard.txt")) {
            localLibrary.put("pgRulesPath", base + "/proguard.txt");
        }
        if (entries.contains("assets")) {
            localLibrary.put("assetsPath", base + "/assets");
        }
        return localLibrary;
    }

    /** Clears the in-memory library list cache; call after add/delete operations. */
    public static void invalidateListCache() {
        cachedLibraries = null;
        cacheKey = -1L;
    }

    /** Clears the in-memory size cache; rarely needed, mostly for tests. */
    public static void invalidateSizeCache() {
        SIZE_CACHE.clear();
    }
}
