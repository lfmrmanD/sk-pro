package dev.aldi.sayuti.editor.manage;

import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.createLibraryMap;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.deleteSelectedLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getAllLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getLocalLibFile;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.invalidateListCache;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.rewriteLocalLibFile;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.MA;
import a.a.a.mB;
import mod.hey.studios.build.BuildSettings;
import pro.sketchware.R;
import pro.sketchware.databinding.ManageLocallibrariesBinding;
import pro.sketchware.databinding.ViewItemLocalLibBinding;
import pro.sketchware.databinding.ViewItemLocalLibSearchBinding;
import pro.sketchware.utility.AnalyticsHelper;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Activity for managing local libraries attached to a project (or globally
 * when {@code sc_id} equals {@code "system"}).
 *
 * <h3>Performance overview</h3>
 * <ul>
 *   <li>Project-used libraries are indexed into a {@link HashSet} once per
 *       load, so per-item "is this used?" checks are O(1) instead of O(m)
 *       on every bind and every sort comparison.</li>
 *   <li>Library sizes are loaded lazily on a background thread pool via
 *       {@link LocalLibrariesUtil#computeSizesAsync}; the list itself appears
 *       almost instantly and sizes trickle in.</li>
 *   <li>Both adapters extend {@link ListAdapter} with {@link DiffUtil} so
 *       list mutations only rebind the items that actually changed.</li>
 *   <li>The 500 ms artificial delay in the previous load path is gone.</li>
 * </ul>
 */
public class ManageLocalLibraryActivity extends BaseAppCompatActivity {
    private static final Gson GSON = new Gson();
    private static final String SIZE_PLACEHOLDER = "…";

    private final LibraryAdapter adapter = new LibraryAdapter();
    private final SearchAdapter searchAdapter = new SearchAdapter();

    /** Single background thread for disk I/O (list + project file). */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArrayList<HashMap<String, Object>> projectUsedLibs;
    private boolean notAssociatedWithProject;
    private boolean searchBarExpanded;
    private BuildSettings buildSettings;
    private ManageLocallibrariesBinding binding;
    private String scId;

    /**
     * Pre-built index of project-used libraries so per-item checks are O(1).
     * Rebuilt whenever {@link #projectUsedLibs} changes.
     */
    private final Set<String> usedLibraryNames = new HashSet<>();
    private final Map<String, String> libraryDependencies = new HashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ManageLocallibrariesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyWindowInsets();

        if (getIntent().hasExtra("sc_id")) {
            scId = Objects.requireNonNull(getIntent().getStringExtra("sc_id"));
            buildSettings = new BuildSettings(scId);
            notAssociatedWithProject = scId.equals("system");
        }

        adapter.setOnLocalLibrarySelectedStateChangedListener(item -> {
            long selectedItemCount = getSelectedLocalLibrariesCount();
            if (selectedItemCount > 0 && adapter.isSelectionModeEnabled) {
                binding.contextualToolbar.setTitle(String.valueOf(selectedItemCount));
                expandContextualToolbar();
            } else {
                adapter.isSelectionModeEnabled = false;
                collapseContextualToolbar();
            }
        });

        binding.librariesList.setAdapter(adapter);
        binding.searchList.setAdapter(searchAdapter);

        binding.searchBar.setNavigationOnClickListener(v -> {
            if (!mB.a()) {
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        binding.contextualToolbar.setNavigationOnClickListener(v -> hideContextualToolbarAndClearSelection());
        binding.contextualToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_select_all) {
                setLocalLibrariesSelected(true);
                binding.contextualToolbar.setTitle(String.valueOf(getSelectedLocalLibrariesCount()));
                return true;
            } else if (id == R.id.action_delete_selected_local_libraries) {
                k();
                ioExecutor.execute(() -> {
                    deleteSelectedLocalLibraries(
                            scId,
                            adapter.getCurrentList(),
                            projectUsedLibs);
                    invalidateListCache();
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        h();
                        SketchwareUtil.toast("Deleted successfully");
                        adapter.isSelectionModeEnabled = false;
                        collapseContextualToolbar();
                        runLoadLocalLibrariesTask();
                    });
                });
                return true;
            }
            return false;
        });

        binding.downloadLibraryButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag("library_downloader_dialog") != null) {
                return;
            }

            Bundle bundle = new Bundle();
            bundle.putBoolean("notAssociatedWithProject", notAssociatedWithProject);
            bundle.putSerializable("buildSettings", buildSettings);
            bundle.putString("localLibFile", getLocalLibFile(scId).getAbsolutePath());

            LibraryDownloaderDialogFragment fragment = new LibraryDownloaderDialogFragment();
            fragment.setArguments(bundle);
            fragment.setOnLibraryDownloadedTask(this::runLoadLocalLibrariesTask);
            fragment.show(getSupportFragmentManager(), "library_downloader_dialog");
        });

        binding.searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s.toString().trim();
                searchAdapter.filter(adapter.getCurrentList(), value);
                if (value.length() > 2) {
                    AnalyticsHelper.logFeatureSearch(ManageLocalLibraryActivity.this, value, "local_libraries");
                }
            }

            @Override
            public void onTextChanged(CharSequence newText, int start, int before, int count) {
            }
        });

        runLoadLocalLibrariesTask();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchBarExpanded) {
                    hideContextualToolbarAndClearSelection();
                } else if (binding.searchView.isShowing()) {
                    binding.searchView.hide();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    /** Applies edge-to-edge window insets to the search bar, contextual toolbar, lists, FAB. */
    private void applyWindowInsets() {
        {
            View view1 = binding.searchBar;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view1.getLayoutParams();
            int end = lp.getMarginEnd();
            int start = lp.getMarginStart();
            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.displayCutout());
                lp.setMarginEnd(end + insets.right);
                lp.setMarginStart(start + insets.left);
                v.setLayoutParams(lp);
                return i;
            });
        }
        {
            View view1 = binding.contextualToolbarContainer;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom);
                return i;
            });
        }
        {
            View view1 = binding.librariesList;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top, right + insets.right, bottom + insets.bottom);
                return i;
            });
        }
        {
            View view1 = binding.searchList;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top, right + insets.right, bottom + insets.bottom);
                return i;
            });
        }
        {
            View view1 = binding.downloadLibraryButton;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view1.getLayoutParams();
            int bottom = lp.bottomMargin;
            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars());
                lp.bottomMargin = bottom + insets.bottom;
                v.setLayoutParams(lp);
                return i;
            });
        }
    }

    /**
     * Loads the library list off the UI thread. The 500 ms artificial delay
     * from the previous implementation has been removed.
     */
    private void runLoadLocalLibrariesTask() {
        k();
        new LoadLocalLibrariesTask(this).execute();
    }

    private List<LocalLibrary> getAdapterLocalLibraries() {
        return adapter.getCurrentList();
    }

    private void hideContextualToolbarAndClearSelection() {
        adapter.isSelectionModeEnabled = false;
        if (collapseContextualToolbar()) {
            setLocalLibrariesSelected(false);
        }
    }

    public void setLocalLibrariesSelected(boolean selected) {
        for (LocalLibrary library : getAdapterLocalLibraries()) {
            library.setSelected(selected);
        }
        // Force a fresh list reference so DiffUtil picks up selection changes.
        adapter.submitList(new ArrayList<>(getAdapterLocalLibraries()));
    }

    private void expandContextualToolbar() {
        searchBarExpanded = true;
        binding.searchBar.expand(binding.contextualToolbarContainer, binding.appBarLayout);
    }

    private boolean collapseContextualToolbar() {
        searchBarExpanded = false;
        return binding.searchBar.collapse(binding.contextualToolbarContainer, binding.appBarLayout);
    }

    private long getSelectedLocalLibrariesCount() {
        long count = 0;
        for (LocalLibrary library : getAdapterLocalLibraries()) {
            if (library.isSelected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rebuilds the {@link #usedLibraryNames} / {@link #libraryDependencies}
     * lookup tables from {@link #projectUsedLibs}. Cheap (O(m)) and runs
     * on the background thread alongside the load, plus on the UI thread
     * after each toggle.
     */
    private void rebuildUsedLibraryIndex() {
        usedLibraryNames.clear();
        libraryDependencies.clear();
        if (projectUsedLibs == null) return;
        for (Map<String, Object> lib : projectUsedLibs) {
            String name = String.valueOf(lib.get("name"));
            usedLibraryNames.add(name);
            Object dep = lib.get("dependency");
            if (dep instanceof String) {
                libraryDependencies.put(name, (String) dep);
            }
        }
    }

    /** O(1) check used during sort and bind. */
    private boolean isUsedLibrary(String libraryName) {
        return usedLibraryNames.contains(libraryName);
    }

    /**
     * Adds or removes a library from {@link #projectUsedLibs} based on the
     * switch state, then rewrites the project file. After mutation the
     * lookup index is refreshed so subsequent renders are correct.
     *
     * <p>Shared between {@link LibraryAdapter} and {@link SearchAdapter}
     * so there is no duplicated logic.
     */
    private void onLibrarySwitchToggled(String name, boolean nowEnabled) {
        if (projectUsedLibs == null) return;

        if (!nowEnabled) {
            projectUsedLibs.removeIf(lib -> name.equals(String.valueOf(lib.get("name"))));
        } else {
            String dependency = libraryDependencies.get(name);
            projectUsedLibs.add(createLibraryMap(name, dependency));
            AnalyticsHelper.logAddLibrary(this, name, "local_library");
        }
        rewriteLocalLibFile(scId, GSON.toJson(projectUsedLibs));
        rebuildUsedLibraryIndex();
    }

    // This method runs on the background thread; every UI operation is
    // dispatched back through `mainHandler`.
    private void loadLibraries() {
        List<LocalLibrary> localLibraries = getAllLocalLibraries();
        if (!notAssociatedWithProject) {
            projectUsedLibs = getLocalLibraries(scId);
            rebuildUsedLibraryIndex();
        }

        // Sort: enabled (used) libraries first. With the HashSet index this
        // is O(n log n) instead of O(n × m × log n).
        localLibraries.sort((lib1, lib2) ->
                Boolean.compare(isUsedLibrary(lib2.getName()), isUsedLibrary(lib1.getName())));

        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            adapter.submitList(localLibraries);
            binding.noContentLayout.setVisibility(
                    localLibraries.isEmpty() ? View.VISIBLE : View.GONE);
        });

        // Now fill in sizes on the background pool. Submitting a fresh list
        // reference triggers DiffUtil to rebind only the items whose size
        // string actually changed.
        LocalLibrariesUtil.computeSizesAsync(localLibraries, () ->
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    adapter.submitList(new ArrayList<>(localLibraries));
                }));
    }

    public interface OnLocalLibrarySelectedStateChangedListener {
        void invoke(LocalLibrary library);
    }

    // ---- Background task wrapper (kept for compatibility with the existing
    //      task-tracking infrastructure in `MA`). Internal work is thin. ----

    private static class LoadLocalLibrariesTask extends MA {
        private final WeakReference<ManageLocalLibraryActivity> activity;

        public LoadLocalLibrariesTask(ManageLocalLibraryActivity activity) {
            super(activity);
            this.activity = new WeakReference<>(activity);
            activity.addTask(this);
        }

        @Override
        public void a() {
            ManageLocalLibraryActivity a = activity.get();
            if (a != null) a.h();
        }

        @Override
        public void a(String idk) {
            ManageLocalLibraryActivity a = activity.get();
            if (a != null) a.h();
        }

        @Override
        public void b() {
            try {
                ManageLocalLibraryActivity a = activity.get();
                if (a != null) a.loadLibraries();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ======================= LibraryAdapter =======================

    public class LibraryAdapter extends ListAdapter<LocalLibrary, LibraryAdapter.ViewHolder> {
        public boolean isSelectionModeEnabled;
        private @Nullable OnLocalLibrarySelectedStateChangedListener onLocalLibrarySelectedStateChangedListener;

        public LibraryAdapter() {
            super(new DiffUtil.ItemCallback<LocalLibrary>() {
                @Override
                public boolean areItemsTheSame(@NonNull LocalLibrary a, @NonNull LocalLibrary b) {
                    return a.getName().equals(b.getName());
                }

                @Override
                public boolean areContentsTheSame(@NonNull LocalLibrary a, @NonNull LocalLibrary b) {
                    return a.isSelected() == b.isSelected()
                            && Objects.equals(a.getSize(), b.getSize());
                }
            });
        }

        public void setOnLocalLibrarySelectedStateChangedListener(
                @Nullable OnLocalLibrarySelectedStateChangedListener listener) {
            this.onLocalLibrarySelectedStateChangedListener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ViewItemLocalLibBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ViewItemLocalLibBinding binding = holder.binding;
            LocalLibrary library = getItem(position);

            binding.libraryName.setText(library.getName());
            String size = library.getSize();
            binding.librarySize.setText(size == null || size.isEmpty() ? SIZE_PLACEHOLDER : size);
            binding.libraryName.setSelected(true);
            bindSelectedState(binding.card, library);

            binding.card.setOnClickListener(v -> {
                if (isSelectionModeEnabled) {
                    toggleLocalLibrary(binding.card, library, onLocalLibrarySelectedStateChangedListener);
                } else if (!notAssociatedWithProject) {
                    binding.materialSwitch.performClick();
                }
            });

            binding.card.setOnLongClickListener(v -> {
                if (isSelectionModeEnabled) {
                    return false;
                }
                isSelectionModeEnabled = true;
                toggleLocalLibrary(binding.card, library, onLocalLibrarySelectedStateChangedListener);
                return true;
            });

            // O(1) HashSet lookup instead of looping projectUsedLibs every bind.
            binding.materialSwitch.setChecked(usedLibraryNames.contains(library.getName()));
            if (!notAssociatedWithProject) {
                binding.materialSwitch.setOnClickListener(v ->
                        onLibrarySwitchToggled(library.getName(), binding.materialSwitch.isChecked()));
            } else {
                binding.materialSwitch.setEnabled(false);
            }
        }

        private void toggleLocalLibrary(MaterialCardView card, LocalLibrary library,
                                        @Nullable OnLocalLibrarySelectedStateChangedListener listener) {
            library.setSelected(!library.isSelected());
            bindSelectedState(card, library);
            if (listener != null) {
                listener.invoke(library);
            }
            if (library.isSelected() && isUsedLibrary(library.getName())) {
                new MaterialAlertDialogBuilder(ManageLocalLibraryActivity.this)
                        .setTitle(R.string.common_word_warning)
                        .setMessage("This library \"" + library.getName()
                                + "\" already used in your project, removing it may break your project\rDo you want to continue removing it?")
                        .setPositiveButton(R.string.common_word_yes, (dialog, which) -> dialog.dismiss())
                        .setNegativeButton(R.string.common_word_cancel, (dialog, which) -> {
                            toggleLocalLibrary(card, library, listener);
                            dialog.dismiss();
                        })
                        .show();
            }
        }

        private void bindSelectedState(MaterialCardView card, LocalLibrary library) {
            card.setChecked(library.isSelected());
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final ViewItemLocalLibBinding binding;

            public ViewHolder(@NonNull ViewItemLocalLibBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    // ======================= SearchAdapter =======================
    //
    // Mirrors the main adapter's data (same LocalLibrary instances) filtered
    // by the current query. Same O(1) used-library lookup is used here so
    // searching and scrolling search results is as cheap as the main list.

    public class SearchAdapter extends ListAdapter<LocalLibrary, SearchAdapter.ViewHolder> {

        public SearchAdapter() {
            super(new DiffUtil.ItemCallback<LocalLibrary>() {
                @Override
                public boolean areItemsTheSame(@NonNull LocalLibrary a, @NonNull LocalLibrary b) {
                    return a.getName().equals(b.getName());
                }

                @Override
                public boolean areContentsTheSame(@NonNull LocalLibrary a, @NonNull LocalLibrary b) {
                    return a.isSelected() == b.isSelected()
                            && Objects.equals(a.getSize(), b.getSize());
                }
            });
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ViewItemLocalLibSearchBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ViewItemLocalLibSearchBinding binding = holder.binding;
            LocalLibrary library = getItem(position);

            binding.libraryName.setText(library.getName());
            String size = library.getSize();
            binding.librarySize.setText(size == null || size.isEmpty() ? SIZE_PLACEHOLDER : size);
            binding.libraryName.setSelected(true);

            // O(1) HashSet lookup instead of looping projectUsedLibs every bind.
            binding.materialSwitch.setChecked(usedLibraryNames.contains(library.getName()));
            if (!notAssociatedWithProject) {
                binding.getRoot().setOnClickListener(v -> binding.materialSwitch.performClick());
                binding.materialSwitch.setOnClickListener(v -> {
                    onLibrarySwitchToggled(library.getName(), binding.materialSwitch.isChecked());
                    // Refresh the visible item so the switch reflects the new state.
                    notifyItemChanged(holder.getBindingAdapterPosition());
                });
            } else {
                binding.materialSwitch.setEnabled(false);
            }
        }

        /**
         * Builds a filtered, sorted list from the main adapter's current
         * data and submits it via {@link ListAdapter#submitList(List)} so
         * DiffUtil handles incremental updates instead of a full rebind.
         */
        public void filter(List<LocalLibrary> localLibraries, String query) {
            List<LocalLibrary> filtered = new ArrayList<>();
            if (query.isEmpty()) {
                filtered.addAll(localLibraries);
            } else {
                String lowerQuery = query.toLowerCase();
                for (LocalLibrary library : localLibraries) {
                    if (library.getName().toLowerCase().contains(lowerQuery)) {
                        filtered.add(library);
                    }
                }
            }
            // Keep enabled libraries on top, same as the main list.
            filtered.sort((lib1, lib2) ->
                    Boolean.compare(isUsedLibrary(lib2.getName()), isUsedLibrary(lib1.getName())));
            submitList(filtered);
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final ViewItemLocalLibSearchBinding binding;

            public ViewHolder(@NonNull ViewItemLocalLibSearchBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
