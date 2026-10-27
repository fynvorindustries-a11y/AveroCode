package com.averolauncher.ui.pages;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.curseforge.CurseForgeSearchResponse;
import com.averolauncher.model.modrinth.ModrinthSearchResponse;
import com.averolauncher.model.modrinth.ModrinthVersion;
import com.averolauncher.service.CurseForgeService;
import com.averolauncher.service.ModrinthService;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.NavPage;
import com.averolauncher.util.UiAccents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dual-source content browser: Modrinth v2 API + CurseForge Core v1 API.
 *
 * Content types with correct install sub-folders:
 *   Mods           → mods/
 *   Modpacks       → modpacks/
 *   Resource Packs → resourcepacks/
 *   Shaders        → shaderpacks/
 *   Data Packs     → datapacks/
 *   Plugins        → plugins/       (CurseForge only)
 *   Worlds         → saves/         (CurseForge only)
 */
public class ModsPage {

    private static final String TAG = "ModsPage";

    // ── Source toggle ────────────────────────────────────────────────────
    private enum Source { MODRINTH, CURSEFORGE }
    private Source activeSource = Source.MODRINTH;

    // ── Content type ─────────────────────────────────────────────────────
    private enum ContentType {
        MOD("Mods", "mods"),
        MODPACK("Modpacks", "modpacks"),
        RESOURCEPACK("Resource Packs", "resourcepacks"),
        SHADER("Shaders", "shaderpacks"),
        DATAPACK("Data Packs", "datapacks"),
        PLUGIN("Plugins", "plugins"),
        WORLD("Worlds", "saves");

        final String label;
        final String folder;
        ContentType(String label, String folder) { this.label = label; this.folder = folder; }
    }
    private ContentType activeType = ContentType.MOD;

    private final AppContext appContext;

    // ── Controls ─────────────────────────────────────────────────────────
    /** Width of one result card: four of these plus gaps fit in a normal window. */
    private static final int CARD_W = 226;
    private final TilePane resultsGrid   = new TilePane();
    private final Label    resultCount   = new Label("");
    private final TextField searchField  = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> loaderBox  = new ComboBox<>();
    private final ComboBox<String> sortBox    = new ComboBox<>();
    private final Label    statusLabel   = new Label();
    private final HBox     typeTabsRow   = new HBox(6);

    public ModsPage(AppContext appContext) {
        this.appContext = appContext;
    }

    // ── Build ─────────────────────────────────────────────────────────────
    public VBox build() {
        VBox root = new VBox(20);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(28, 32, 32, 32));

        StackPane hero = UiAccents.heroBanner("/images/mods-hero.png", 88);

        root.getChildren().addAll(
                hero,
                buildHeader(),
                buildSourceToggle(),
                buildTypeTabs(),
                buildToolbar(),
                buildBody()
        );

        runSearch();
        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────
    private VBox buildHeader() {
        Label part1 = new Label("Explore ");
        part1.getStyleClass().add("page-title");
        Label part2 = new Label("Minecraft Content");
        part2.getStyleClass().addAll("page-title", "title-accent");
        HBox titleRow = new HBox(part1, part2);

        HBox headerRow = new HBox(14, UiAccents.pageBadge(NavPage.MODS, 40), titleRow);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(
                "Discover mods, modpacks, resource packs, shaders and more — " +
                "powered by both Modrinth and CurseForge.");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);

        VBox box = new VBox(8, headerRow, UiAccents.pageUnderline(), subtitle);
        return box;
    }

    // ── Source toggle (Modrinth / CurseForge) ─────────────────────────────
    private HBox buildSourceToggle() {
        HBox bar = new HBox(0);
        bar.getStyleClass().add("source-toggle-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup group = new ToggleGroup();

        ToggleButton modrinthBtn = makeSourceBtn("⬡  Modrinth", Source.MODRINTH, group);
        modrinthBtn.setSelected(true);
        ToggleButton cfBtn = makeSourceBtn("🔥  CurseForge", Source.CURSEFORGE, group);

        // Source description label
        Label srcDesc = new Label("Free & open-source");
        srcDesc.getStyleClass().add("source-desc-label");
        srcDesc.setStyle("-fx-text-fill: rgba(234,243,248,0.45); -fx-font-size:10.5px; -fx-padding: 0 0 0 14;");

        group.selectedToggleProperty().addListener((obs, old, nw) -> {
            if (nw == null) { if (old != null) old.setSelected(true); return; }
            if (nw == modrinthBtn) {
                activeSource = Source.MODRINTH;
                srcDesc.setText("Free & open-source");
                refreshTypeTabs();
                runSearch();
            } else {
                activeSource = Source.CURSEFORGE;
                srcDesc.setText("Powered by CurseForge Core API");
                refreshTypeTabs();
                runSearch();
            }
        });

        HBox spacer = new HBox(); HBox.setHgrow(spacer, Priority.ALWAYS);

        // API badges
        Label modrinthBadge = new Label("Modrinth v2");
        modrinthBadge.setStyle(
            "-fx-background-color: rgba(30,200,120,0.15);" +
            "-fx-border-color: rgba(30,200,120,0.35);" +
            "-fx-border-radius:0; -fx-background-radius:0;" +
            "-fx-text-fill:#1ec878; -fx-font-size:10px; -fx-font-weight:bold;" +
            "-fx-padding: 3 9 3 9;");
        Label cfBadge = new Label("CurseForge v1");
        cfBadge.setStyle(
            "-fx-background-color: rgba(255,70,85,0.15);" +
            "-fx-border-color: rgba(255,70,85,0.35);" +
            "-fx-border-radius:0; -fx-background-radius:0;" +
            "-fx-text-fill:#ff4655; -fx-font-size:10px; -fx-font-weight:bold;" +
            "-fx-padding: 3 9 3 9;");
        HBox badges = new HBox(8, modrinthBadge, cfBadge);
        badges.setAlignment(Pos.CENTER_RIGHT);

        bar.getChildren().addAll(modrinthBtn, cfBtn, srcDesc, spacer, badges);
        return bar;
    }

    private ToggleButton makeSourceBtn(String text, Source src, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.getStyleClass().add("source-toggle-button");
        return btn;
    }

    // ── Content-type tabs (change based on source) ────────────────────────
    private void refreshTypeTabs() {
        typeTabsRow.getChildren().clear();
        List<ContentType> types = getTypesForSource(activeSource);

        // reset activeType if not valid for this source
        if (!types.contains(activeType)) {
            activeType = types.get(0);
        }

        ToggleGroup group = new ToggleGroup();
        for (ContentType t : types) {
            ToggleButton btn = new ToggleButton(t.label);
            btn.setToggleGroup(group);
            btn.getStyleClass().add("type-tab-button");
            btn.setSelected(t == activeType);
            final ContentType ft = t;
            btn.setOnAction(e -> { activeType = ft; runSearch(); });
            typeTabsRow.getChildren().add(btn);
        }
    }

    private List<ContentType> getTypesForSource(Source src) {
        if (src == Source.MODRINTH) {
            return List.of(ContentType.MOD, ContentType.MODPACK,
                           ContentType.RESOURCEPACK, ContentType.SHADER, ContentType.DATAPACK);
        } else {
            return List.of(ContentType.MOD, ContentType.MODPACK,
                           ContentType.RESOURCEPACK, ContentType.SHADER,
                           ContentType.DATAPACK, ContentType.PLUGIN, ContentType.WORLD);
        }
    }

    private HBox buildTypeTabs() {
        typeTabsRow.getStyleClass().add("content-type-tabs");
        refreshTypeTabs();
        HBox wrapper = new HBox(typeTabsRow);
        wrapper.setAlignment(Pos.CENTER);
        return wrapper;
    }


    // ── Live Minecraft version list ────────────────────────────────────────
    /** Fetches every release version from Modrinth and refreshes the dropdown (keeps the current pick). */
    private void loadLiveGameVersions() {
        CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(8)).build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("https://api.modrinth.com/v2/tag/game_version"))
                        .timeout(java.time.Duration.ofSeconds(12))
                        .header("User-Agent", "AveroLauncher/1.0 (mod browser)")
                        .header("Accept", "application/json")
                        .GET().build();
                java.net.http.HttpResponse<String> r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return java.util.Collections.<String>emptyList();
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(r.body()).getAsJsonArray();
                java.util.List<com.google.gson.JsonObject> releases = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement e : arr) {
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    if (o.has("version_type") && "release".equals(o.get("version_type").getAsString())) releases.add(o);
                }
                releases.sort((a, b) -> b.get("date").getAsString().compareTo(a.get("date").getAsString())); // newest first
                java.util.List<String> out = new java.util.ArrayList<>();
                for (com.google.gson.JsonObject o : releases) out.add(o.get("version").getAsString());
                return out;
            } catch (Exception ex) {
                AppLogger.get().warn(TAG, "Could not load live version list (using built-in list): " + ex.getMessage());
                return java.util.Collections.<String>emptyList();
            }
        }).thenAccept(list -> {
            if (list.isEmpty()) return;
            Platform.runLater(() -> {
                String current = versionBox.getValue();
                javafx.event.EventHandler<javafx.event.ActionEvent> handler = versionBox.getOnAction();
                versionBox.setOnAction(null); // refreshing the list must not trigger a search
                java.util.List<String> items = new java.util.ArrayList<>();
                items.add("Any version");
                items.addAll(list);
                versionBox.getItems().setAll(items);
                versionBox.setValue(current != null && items.contains(current) ? current : "Any version");
                versionBox.setOnAction(handler);
            });
        });
    }

    // ── Toolbar ────────────────────────────────────────────────────────────
    private HBox buildToolbar() {
        searchField.setPromptText("Search content...");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(260);
        searchField.setOnAction(e -> runSearch());

        // Offline fallback (newest first). The full, current list is fetched live from Modrinth below,
        // so new Minecraft versions (26.x and beyond) show up without a launcher update.
        versionBox.getItems().addAll("Any version","26.2","26.1.2","26.1.1","26.1",
                "1.21.11","1.21.10","1.21.9","1.21.8","1.21.7","1.21.6","1.21.5","1.21.4","1.21.3","1.21.2",
                "1.21.1","1.21","1.20.6","1.20.4","1.20.2","1.20.1","1.19.4","1.19.2","1.18.2","1.17.1","1.16.5","1.12.2","1.8.9");
        versionBox.setValue("1.20.1");
        versionBox.setOnAction(e -> runSearch());
        loadLiveGameVersions();

        loaderBox.getItems().addAll("Any loader","fabric","forge","neoforge","quilt");
        loaderBox.setValue("Any loader");
        loaderBox.setOnAction(e -> runSearch());

        sortBox.getItems().addAll("Popular","Newest","Recently Updated","Most Downloaded","Featured");
        sortBox.setValue("Popular");
        sortBox.setOnAction(e -> runSearch());

        Button clearBtn = new Button("✕  Clear all");
        clearBtn.getStyleClass().add("secondary-button");
        clearBtn.setOnAction(e -> clearFilters());

        Button searchBtn = new Button("Search");
        searchBtn.getStyleClass().add("primary-cta-button");
        searchBtn.setOnAction(e -> runSearch());

        HBox spacer = new HBox(); HBox.setHgrow(spacer, Priority.ALWAYS);

        resultCount.setStyle("-fx-text-fill: rgba(234,243,248,0.5); -fx-font-size:11px;");

        HBox bar = new HBox(10, searchField, versionBox, loaderBox, sortBox, clearBtn, searchBtn, spacer, resultCount);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mods-toolbar");
        bar.setPadding(new Insets(10, 14, 10, 14));

        for (Control c : new Control[]{searchField, versionBox, loaderBox, sortBox, clearBtn, searchBtn}) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        return bar;
    }

    // ── Body ───────────────────────────────────────────────────────────────
    private HBox buildBody() {
        HBox body = new HBox(20);

        VBox resultsWrap = new VBox(10);
        HBox.setHgrow(resultsWrap, Priority.ALWAYS);

        // Four cards per row (fewer only if the window is too narrow to fit them).
        resultsGrid.setHgap(14);
        resultsGrid.setVgap(14);
        resultsGrid.setPrefColumns(4);
        resultsGrid.setTileAlignment(Pos.TOP_LEFT);

        statusLabel.getStyleClass().add("news-desc");
        statusLabel.setWrapText(true);

        resultsWrap.getChildren().addAll(statusLabel, resultsGrid);
        body.getChildren().add(resultsWrap);
        return body;
    }

    /** Resets the search box and every dropdown to its default, then re-runs the search. */
    private void clearFilters() {
        searchField.clear();
        // Changing the values fires each box's onAction handler (it runs a search), so
        // silence them while resetting and search exactly once afterwards.
        javafx.event.EventHandler<javafx.event.ActionEvent> vh = versionBox.getOnAction();
        javafx.event.EventHandler<javafx.event.ActionEvent> lh = loaderBox.getOnAction();
        javafx.event.EventHandler<javafx.event.ActionEvent> sh = sortBox.getOnAction();
        versionBox.setOnAction(null); loaderBox.setOnAction(null); sortBox.setOnAction(null);
        versionBox.setValue("Any version");
        loaderBox.setValue("Any loader");
        sortBox.setValue("Popular");
        versionBox.setOnAction(vh); loaderBox.setOnAction(lh); sortBox.setOnAction(sh);
        runSearch();
    }

    // ── Search dispatch ────────────────────────────────────────────────────
    private void runSearch() {
        resultsGrid.getChildren().clear();
        statusLabel.setText("Searching " + (activeSource == Source.MODRINTH ? "Modrinth" : "CurseForge") + "...");
        resultCount.setText("");

        if (activeSource == Source.MODRINTH) {
            runModrinthSearch();
        } else {
            runCurseForgeSearch();
        }
    }

    // ── Modrinth search ────────────────────────────────────────────────────
    private void runModrinthSearch() {
        ModrinthService.SearchQuery q = new ModrinthService.SearchQuery();
        q.text = searchField.getText() != null ? searchField.getText() : "";
        q.projectType = toModrinthType(activeType);
        q.gameVersion = "Any version".equals(versionBox.getValue()) ? null : versionBox.getValue();
        q.loader = "Any loader".equals(loaderBox.getValue()) ? null : loaderBox.getValue();
        q.sort = toModrinthSort(sortBox.getValue());
        q.limit = 20;

        appContext.modrinthService.search(q)
                .thenAccept(resp -> Platform.runLater(() -> renderModrinthResults(resp)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("⚠ Modrinth unavailable: " + rootMsg(ex));
                        AppLogger.get().error(TAG, "Modrinth search failed", ex);
                    });
                    return null;
                });
    }

    private ModrinthService.ProjectType toModrinthType(ContentType t) {
        return switch (t) {
            case MODPACK      -> ModrinthService.ProjectType.MODPACK;
            case RESOURCEPACK -> ModrinthService.ProjectType.RESOURCEPACK;
            case SHADER       -> ModrinthService.ProjectType.SHADER;
            case DATAPACK     -> ModrinthService.ProjectType.DATAPACK;
            default           -> ModrinthService.ProjectType.MOD;
        };
    }

    private ModrinthService.SortIndex toModrinthSort(String label) {
        return switch (label) {
            case "Newest"           -> ModrinthService.SortIndex.NEWEST;
            case "Recently Updated" -> ModrinthService.SortIndex.UPDATED;
            case "Most Downloaded"  -> ModrinthService.SortIndex.DOWNLOADS;
            case "Featured"         -> ModrinthService.SortIndex.RELEVANCE;
            default                 -> ModrinthService.SortIndex.DOWNLOADS;
        };
    }

    private void renderModrinthResults(ModrinthSearchResponse resp) {
        resultsGrid.getChildren().clear();
        if (resp == null || resp.hits == null || resp.hits.isEmpty()) {
            statusLabel.setText("No results found on Modrinth.");
            resultCount.setText("0 results");
            return;
        }
        statusLabel.setText("");
        resultCount.setText(resp.total_hits + " results");
        for (ModrinthSearchResponse.ModrinthHit hit : resp.hits) {
            resultsGrid.getChildren().add(buildModrinthCard(hit));
        }
    }

    private VBox buildModrinthCard(ModrinthSearchResponse.ModrinthHit hit) {
        VBox card = new VBox(10);
        card.getStyleClass().add("mod-card");
        card.setPrefWidth(CARD_W);
        card.setMaxWidth(CARD_W);
        card.setPadding(new Insets(0, 0, 14, 0));

        // Thumbnail
        StackPane thumb = buildThumb(hit.icon_url, CARD_W, 108,
                hit.title != null ? hit.title.substring(0, 1) : "?");

        // Source badge overlay on thumb
        Label srcBadge = new Label("MODRINTH");
        srcBadge.setStyle("-fx-background-color: rgba(30,200,120,0.85); -fx-text-fill:#0f1923;" +
                "-fx-font-size:8px; -fx-font-weight:bold; -fx-background-radius:0;" +
                "-fx-padding: 2 7 2 7;");
        StackPane.setAlignment(srcBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(srcBadge, new Insets(8, 8, 0, 0));
        thumb.getChildren().add(srcBadge);

        VBox content = new VBox(6);
        content.setPadding(new Insets(0, 14, 0, 14));

        Label title = new Label(hit.title);
        title.getStyleClass().add("mod-card-title");
        title.setWrapText(false);

        Label author = new Label("by " + hit.author);
        author.getStyleClass().add("mod-card-author");

        Label desc = new Label(hit.description);
        desc.setWrapText(true);
        desc.getStyleClass().add("mod-card-desc");
        desc.setMaxHeight(46);

        // Tags
        FlowPane tags = new FlowPane(5, 5);
        if (hit.display_categories != null) {
            hit.display_categories.stream().limit(3).forEach(cat -> {
                Label tag = new Label(cat);
                tag.getStyleClass().add("mod-tag");
                tags.getChildren().add(tag);
            });
        }

        // Stats row
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_LEFT);
        Label dl = new Label("⬇ " + fmt(hit.downloads));
        dl.getStyleClass().add("mod-card-stat");
        Label ver = new Label(versionBox.getValue());
        ver.getStyleClass().add("mod-card-stat");
        stats.getChildren().addAll(dl, ver);
        if (hit.follows != null) {
            Label follows = new Label("♥ " + hit.follows);
            follows.getStyleClass().add("mod-card-stat");
            stats.getChildren().add(follows);
        }

        Button installBtn = buildInstallButton();
        installBtn.setOnAction(e -> onModrinthInstall(hit, installBtn));

        content.getChildren().addAll(title, author, desc, tags, stats, installBtn);
        card.getChildren().addAll(thumb, content);
        return card;
    }

    // ── CurseForge search ─────────────────────────────────────────────────
    private void runCurseForgeSearch() {
        CurseForgeService.SearchQuery q = new CurseForgeService.SearchQuery();
        q.classId = toCFClassId(activeType);
        q.searchFilter = searchField.getText() != null ? searchField.getText() : "";
        q.gameVersion = "Any version".equals(versionBox.getValue()) ? null : versionBox.getValue();
        q.modLoaderType = toCFLoader(loaderBox.getValue());
        q.sortField = toCFSort(sortBox.getValue());
        q.pageSize = 20;

        appContext.curseForgeService.search(q)
                .thenAccept(resp -> Platform.runLater(() -> renderCFResults(resp)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("⚠ CurseForge unavailable: " + rootMsg(ex));
                        AppLogger.get().error(TAG, "CurseForge search failed", ex);
                    });
                    return null;
                });
    }

    private int toCFClassId(ContentType t) {
        return switch (t) {
            case MODPACK      -> CurseForgeService.CLASS_MODPACK;
            case RESOURCEPACK -> CurseForgeService.CLASS_RESOURCEPACK;
            case SHADER       -> CurseForgeService.CLASS_SHADER;
            case DATAPACK     -> CurseForgeService.CLASS_DATAPACK;
            case PLUGIN       -> CurseForgeService.CLASS_PLUGIN;
            case WORLD        -> CurseForgeService.CLASS_WORLD;
            default           -> CurseForgeService.CLASS_MOD;
        };
    }

    private Integer toCFLoader(String val) {
        return switch (val) {
            case "fabric"   -> CurseForgeService.LOADER_FABRIC;
            case "forge"    -> CurseForgeService.LOADER_FORGE;
            case "neoforge" -> CurseForgeService.LOADER_NEOFORGE;
            case "quilt"    -> CurseForgeService.LOADER_QUILT;
            default         -> null;
        };
    }

    private CurseForgeService.SortField toCFSort(String label) {
        return switch (label) {
            case "Newest"           -> CurseForgeService.SortField.CREATED_DATE;
            case "Recently Updated" -> CurseForgeService.SortField.LAST_UPDATED;
            case "Most Downloaded"  -> CurseForgeService.SortField.TOTAL_DOWNLOADS;
            case "Featured"         -> CurseForgeService.SortField.FEATURED;
            default                 -> CurseForgeService.SortField.POPULARITY;
        };
    }

    private void renderCFResults(CurseForgeSearchResponse resp) {
        resultsGrid.getChildren().clear();
        if (resp == null || resp.data == null || resp.data.isEmpty()) {
            statusLabel.setText("No results found on CurseForge.");
            resultCount.setText("0 results");
            return;
        }
        statusLabel.setText("");
        int total = resp.pagination != null ? resp.pagination.totalCount : resp.data.size();
        resultCount.setText(total + " results");
        for (CurseForgeSearchResponse.CurseForgeMod mod : resp.data) {
            resultsGrid.getChildren().add(buildCFCard(mod));
        }
    }

    private VBox buildCFCard(CurseForgeSearchResponse.CurseForgeMod mod) {
        VBox card = new VBox(10);
        card.getStyleClass().add("mod-card");
        card.setPrefWidth(CARD_W);
        card.setMaxWidth(CARD_W);
        card.setPadding(new Insets(0, 0, 14, 0));

        String iconUrl = mod.getIconUrl();
        String initial = mod.name != null && !mod.name.isEmpty() ? mod.name.substring(0, 1) : "?";
        StackPane thumb = buildThumb(iconUrl, CARD_W, 108, initial);

        // Source badge
        Label srcBadge = new Label("CURSEFORGE");
        srcBadge.setStyle("-fx-background-color: rgba(255,70,85,0.88); -fx-text-fill:#fff;" +
                "-fx-font-size:8px; -fx-font-weight:bold; -fx-background-radius:0;" +
                "-fx-padding: 2 7 2 7;");
        StackPane.setAlignment(srcBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(srcBadge, new Insets(8, 8, 0, 0));
        thumb.getChildren().add(srcBadge);

        // Downloads badge
        if (mod.downloadCount > 0) {
            Label dlBadge = new Label("⬇ " + fmt(mod.downloadCount));
            dlBadge.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-text-fill:#eaf3f8;" +
                    "-fx-font-size:9px; -fx-background-radius:0; -fx-padding:2 7 2 7;");
            StackPane.setAlignment(dlBadge, Pos.BOTTOM_LEFT);
            StackPane.setMargin(dlBadge, new Insets(0, 0, 8, 8));
            thumb.getChildren().add(dlBadge);
        }

        VBox content = new VBox(6);
        content.setPadding(new Insets(0, 14, 0, 14));

        Label title = new Label(mod.name);
        title.getStyleClass().add("mod-card-title");
        title.setWrapText(false);

        Label author = new Label("by " + mod.getAuthorName());
        author.getStyleClass().add("mod-card-author");

        Label desc = new Label(mod.summary != null ? mod.summary : "");
        desc.setWrapText(true);
        desc.getStyleClass().add("mod-card-desc");
        desc.setMaxHeight(46);

        // Category tags
        FlowPane tags = new FlowPane(5, 5);
        if (mod.categories != null) {
            mod.categories.stream()
                .filter(c -> !c.isClass)
                .limit(3)
                .forEach(cat -> {
                    Label tag = new Label(cat.name);
                    tag.getStyleClass().add("mod-tag");
                    tags.getChildren().add(tag);
                });
        }

        // Stats row
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_LEFT);
        Label dl = new Label("⬇ " + fmt(mod.downloadCount));
        dl.getStyleClass().add("mod-card-stat");
        Label rank = new Label("★ #" + mod.gamePopularityRank);
        rank.getStyleClass().add("mod-card-stat");
        stats.getChildren().addAll(dl, rank);
        if (mod.thumbsUpCount > 0) {
            Label thumbsUp = new Label("👍 " + fmt(mod.thumbsUpCount));
            thumbsUp.getStyleClass().add("mod-card-stat");
            stats.getChildren().add(thumbsUp);
        }

        Button installBtn = buildInstallButton();
        installBtn.setOnAction(e -> onCFInstall(mod, installBtn));

        content.getChildren().addAll(title, author, desc, tags, stats, installBtn);
        card.getChildren().addAll(thumb, content);
        return card;
    }

    // ── Install handlers ──────────────────────────────────────────────────

    private void onModrinthInstall(ModrinthSearchResponse.ModrinthHit hit, Button btn) {
        if (!checkDir()) return;
        setInstalling(btn);

        String gv = "Any version".equals(versionBox.getValue()) ? null : versionBox.getValue();
        String ldr = "Any loader".equals(loaderBox.getValue()) ? null : loaderBox.getValue();

        // Fetch every version for the chosen loader (not just the selected Minecraft version),
        // then let the user pick exactly which one to install.
        appContext.modrinthService.fetchVersions(hit.project_id, null, ldr)
                .thenCompose(versions -> {
                    if (versions == null || versions.isEmpty())
                        throw new RuntimeException("No versions found for the selected loader.");
                    List<VersionChoice> choices = new java.util.ArrayList<>();
                    for (ModrinthVersion v : versions) {
                        if (v.files == null || v.files.isEmpty()) continue;
                        ModrinthVersion.ModrinthFile primary = v.files.stream().filter(f -> f.primary).findFirst().orElse(v.files.get(0));
                        String label = firstText(fld(v, "version_number"), fld(v, "name"), primary.filename);
                        List<String> games = strList(fld(v, "game_versions"));
                        String sub = (games.isEmpty() ? "" : "Minecraft " + String.join(", ", games))
                                + (strList(fld(v, "loaders")).isEmpty() ? "" : "  \u00B7  " + String.join(", ", strList(fld(v, "loaders"))))
                                + "  \u00B7  " + primary.filename;
                        choices.add(new VersionChoice(label, sub, games, fld(v, "version_type") == null ? "" : String.valueOf(fld(v, "version_type")), v));
                    }
                    if (choices.isEmpty()) throw new RuntimeException("No downloadable files found for this mod.");
                    return pickVersion(hit.title, choices, gv, btn);
                })
                .thenCompose(choice -> {
                    ModrinthVersion v = (ModrinthVersion) choice.payload();
                    ModrinthVersion.ModrinthFile file = v.files.stream()
                            .filter(f -> f.primary).findFirst()
                            .orElse(v.files.get(0));
                    return appContext.modManager.installFromModrinth(
                            appContext.settings.minecraftDirectory,
                            file.url, file.filename,
                            file.hashes != null ? file.hashes.sha1 : null,
                            file.size,
                            activeType.folder);
                })
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (ex != null && isCancelled(ex)) {
                        resetInstallButton(btn);
                    } else if (ex != null) {
                        setInstallFailed(btn, ex);
                    } else {
                        setInstalled(btn, hit.title, activeType.folder);
                    }
                }));
    }

    private void onCFInstall(CurseForgeSearchResponse.CurseForgeMod mod, Button btn) {
        if (!checkDir()) return;
        setInstalling(btn);

        String gv = "Any version".equals(versionBox.getValue()) ? null : versionBox.getValue();
        Integer loaderType = toCFLoader(loaderBox.getValue());

        appContext.curseForgeService.getFiles(mod.id, null, loaderType)
                .thenCompose(fileResp -> {
                    if (fileResp == null || fileResp.data == null || fileResp.data.isEmpty())
                        throw new RuntimeException("No files found for this mod.");
                    List<VersionChoice> choices = new java.util.ArrayList<>();
                    for (CurseForgeSearchResponse.LatestFile f : fileResp.data) {
                        String label = firstText(fld(f, "displayName"), f.fileName);
                        List<String> games = strList(fld(f, "gameVersions"));
                        String type = f.releaseType == 1 ? "release" : f.releaseType == 2 ? "beta" : "alpha";
                        String sub = (games.isEmpty() ? "" : String.join(", ", games) + "  \u00B7  ") + f.fileName;
                        choices.add(new VersionChoice(label, sub, games, type, f));
                    }
                    return pickVersion(mod.name, choices, gv, btn);
                })
                .thenCompose(choice -> {
                    CurseForgeSearchResponse.LatestFile best = (CurseForgeSearchResponse.LatestFile) choice.payload();
                    // downloadUrl can be null for some CurseForge files — resolve it
                    if (best.downloadUrl != null && !best.downloadUrl.isBlank()) {
                        return CompletableFuture.completedFuture(best);
                    }
                    // Fetch the real download URL from the separate endpoint
                    return appContext.curseForgeService.getFileDownloadUrl(mod.id, best.id)
                            .thenApply(url -> {
                                best.downloadUrl = url;
                                return best;
                            });
                })
                .thenCompose(file -> {
                    if (file.downloadUrl == null || file.downloadUrl.isBlank())
                        throw new RuntimeException("Download URL not available (mod author may have disabled third-party distribution).");

                    String sha1 = null;
                    if (file.hashes != null) {
                        sha1 = file.hashes.stream()
                                .filter(h -> h.algo == 1)
                                .map(h -> h.value)
                                .findFirst()
                                .orElse(null);
                    }
                    // Re-use ModManager's generic install path (same method modrinth uses)
                    return appContext.modManager.installFromModrinth(
                            appContext.settings.minecraftDirectory,
                            file.downloadUrl,
                            file.fileName,
                            sha1,
                            file.fileLength,
                            activeType.folder);
                })
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (ex != null && isCancelled(ex)) {
                        resetInstallButton(btn);
                    } else if (ex != null) {
                        setInstallFailed(btn, ex);
                    } else {
                        setInstalled(btn, mod.name, activeType.folder);
                    }
                }));
    }

    // ── Version picker ─────────────────────────────────────────────────────

    /** One installable version shown in the picker. {@code payload} is the original API object. */
    private record VersionChoice(String label, String sub, List<String> gameVersions, String type, Object payload) {
        @Override public String toString() { return label; }
    }

    /**
     * Shows a dialog listing every version of the mod so the user chooses which one to install.
     * Completes with the pick, or exceptionally with a CancellationException if the dialog is dismissed.
     */
    private CompletableFuture<VersionChoice> pickVersion(String modName, List<VersionChoice> choices,
                                                         String gameVersionFilter, Button anchor) {
        CompletableFuture<VersionChoice> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Dialog<VersionChoice> dlg = new Dialog<>();
                dlg.setTitle("Choose a version");
                dlg.setHeaderText(null);
                try {
                    javafx.scene.Scene owner = anchor.getScene();
                    if (owner != null) {
                        dlg.initOwner(owner.getWindow());
                        dlg.getDialogPane().getStylesheets().setAll(owner.getStylesheets());
                    }
                } catch (Exception ignored) { }
                DialogPane pane = dlg.getDialogPane();
                pane.getStyleClass().add("device-dialog");

                Label heading = new Label("Choose a version of " + modName);
                heading.getStyleClass().add("device-title");
                Label hint = new Label("Pick the exact version to install. Newest versions are at the top.");
                hint.getStyleClass().add("device-step");
                hint.setWrapText(true);

                ListView<VersionChoice> list = new ListView<>();
                list.setPrefSize(520, 320);
                list.setCellFactory(lv -> new ListCell<>() {
                    @Override protected void updateItem(VersionChoice c, boolean empty) {
                        super.updateItem(c, empty);
                        if (empty || c == null) { setGraphic(null); setText(null); return; }
                        Label name = new Label(c.label());
                        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #ece8e1;");
                        String t = c.type() == null ? "" : c.type().toLowerCase();
                        Label chip = new Label(t.isEmpty() ? "" : t.toUpperCase());
                        String col = t.equals("release") ? "#5ec97a" : t.equals("beta") ? "#e0a030" : "#e05a50";
                        chip.setStyle("-fx-text-fill: " + col + "; -fx-font-size: 9.5px; -fx-font-weight: bold;");
                        chip.setVisible(!t.isEmpty()); chip.setManaged(!t.isEmpty());
                        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                        HBox top = new HBox(8, name, gap, chip);
                        top.setAlignment(Pos.CENTER_LEFT);
                        Label sub = new Label(c.sub());
                        sub.setStyle("-fx-text-fill: rgba(232,242,249,0.50); -fx-font-size: 10.5px;");
                        sub.setMaxWidth(470);
                        VBox box = new VBox(2, top, sub);
                        box.setPadding(new Insets(4, 6, 4, 6));
                        setText(null);
                        setGraphic(box);
                    }
                });

                boolean hasMatch = gameVersionFilter != null && choices.stream()
                        .anyMatch(c -> c.gameVersions().contains(gameVersionFilter));
                CheckBox only = new CheckBox("Only versions for Minecraft " + gameVersionFilter);
                only.setVisible(hasMatch);
                only.setManaged(hasMatch);
                only.setSelected(hasMatch);
                Runnable refill = () -> {
                    List<VersionChoice> shown = new java.util.ArrayList<>();
                    for (VersionChoice c : choices) {
                        if (only.isSelected() && hasMatch && !c.gameVersions().contains(gameVersionFilter)) continue;
                        shown.add(c);
                    }
                    list.getItems().setAll(shown);
                    if (!shown.isEmpty()) list.getSelectionModel().select(0);
                };
                only.setOnAction(e -> refill.run());
                refill.run();

                VBox content = new VBox(12, heading, hint, only, list);
                content.setPadding(new Insets(4, 6, 4, 6));
                pane.setContent(content);
                ButtonType installType = new ButtonType("Install", ButtonBar.ButtonData.OK_DONE);
                pane.getButtonTypes().setAll(installType, ButtonType.CANCEL);
                Node okBtn = pane.lookupButton(installType);
                okBtn.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
                list.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) ((Button) okBtn).fire(); });
                dlg.setResultConverter(bt -> bt == installType ? list.getSelectionModel().getSelectedItem() : null);

                java.util.Optional<VersionChoice> picked = dlg.showAndWait();
                if (picked.isPresent()) result.complete(picked.get());
                else result.completeExceptionally(new java.util.concurrent.CancellationException("cancelled"));
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    private static boolean isCancelled(Throwable t) {
        while (t != null) {
            if (t instanceof java.util.concurrent.CancellationException) return true;
            t = t.getCause();
        }
        return false;
    }

    private void resetInstallButton(Button btn) {
        btn.setDisable(false);
        btn.setText("Install");
        btn.setStyle(buildInstallButton().getStyle());
    }

    /** Reads a public field by name, or null if the model class doesn't have it (keeps this robust). */
    private static Object fld(Object o, String name) {
        try { return o.getClass().getField(name).get(o); } catch (Exception e) { return null; }
    }

    private static List<String> strList(Object o) {
        List<String> out = new java.util.ArrayList<>();
        if (o instanceof Iterable<?> it) for (Object x : it) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    private static String firstText(Object... values) {
        for (Object v : values) if (v != null && !String.valueOf(v).isBlank() && !"null".equals(String.valueOf(v))) return String.valueOf(v);
        return "Unknown version";
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private Button buildInstallButton() {
        Button btn = new Button("Install");
        btn.getStyleClass().add("primary-cta-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #ff4655, #ff5d6b);" +
            "-fx-text-fill: #ffffff;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 9 20 9 20;" +
            "-fx-cursor: hand;" +
            "-fx-font-size: 12px;"
        );
        return btn;
    }

    // ── Icon loading ───────────────────────────────────────────────────────
    // JavaFX's own Image loader only understands PNG/JPEG/GIF/BMP, but Modrinth serves many
    // project icons as WebP — those used to fail silently and leave the placeholder letter.
    // Icons are therefore downloaded as raw bytes and, if JavaFX can't decode them, re-decoded
    // with ImageIO (WebP support comes from the webp-imageio dependency) and handed to JavaFX as PNG.
    private static final java.net.http.HttpClient ICON_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
    private static final java.util.Map<String, Image> ICON_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Result of an icon load: either an image or a short human-readable reason it failed. */
    private record IconResult(Image image, String error) {}

    private IconResult loadIcon(String url, int px) {
        Image cached = ICON_CACHE.get(url);
        if (cached != null) return new IconResult(cached, null);
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("User-Agent", "AveroLauncher")
                    .GET().build();
            java.net.http.HttpResponse<byte[]> resp =
                    ICON_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) return fail(url, "HTTP " + resp.statusCode());
            byte[] data = resp.body();
            if (data.length == 0) return fail(url, "empty response");

            Image img = new Image(new java.io.ByteArrayInputStream(data), px, px, true, true);
            if (img.isError() || img.getWidth() <= 0) {
                // Not a format JavaFX knows (e.g. WebP) — decode with ImageIO and re-encode as PNG.
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                if (bi == null) return fail(url, "no decoder for this image format");
                java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bi, "png", png);
                img = new Image(new java.io.ByteArrayInputStream(png.toByteArray()), px, px, true, true);
                if (img.isError()) return fail(url, "decoded image rejected by JavaFX");
            }
            ICON_CACHE.put(url, img);
            return new IconResult(img, null);
        } catch (Throwable t) {
            return fail(url, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private IconResult fail(String url, String reason) {
        AppLogger.get().warn("ModsPage", "Icon failed (" + reason + "): " + url);
        return new IconResult(null, reason);
    }

    private StackPane buildThumb(String iconUrl, int w, int h, String fallbackInitial) {
        StackPane thumb = new StackPane();
        thumb.getStyleClass().add("mod-card-thumb");
        thumb.setPrefHeight(h);
        thumb.setMinHeight(h);
        thumb.setPrefWidth(w);

        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(0);
        clip.setArcHeight(0);
        thumb.setClip(clip);

        // The initial-letter placeholder is always present underneath; the real icon replaces
        // it only once it has been downloaded and decoded successfully.
        addFallbackThumb(thumb, fallbackInitial, w, h);
        Node placeholder = thumb.getChildren().get(0);

        if (iconUrl == null || iconUrl.isBlank()) {
            Tooltip.install(thumb, new Tooltip("This project has no icon"));
        } else {
            int box = h - 24; // icons are square: keep the aspect ratio instead of stretching
            appContext.launchService.getBackgroundExecutor().execute(() -> {
                IconResult res = loadIcon(iconUrl, box * 2); // decode at 2x for sharpness
                if (res.image() == null) {
                    // Hover the placeholder letter to see why the icon didn't load.
                    Platform.runLater(() -> Tooltip.install(thumb,
                            new Tooltip("Icon not loaded: " + res.error() + "\n" + iconUrl)));
                    return;
                }
                Platform.runLater(() -> {
                    ImageView iv = new ImageView(res.image());
                    iv.setFitWidth(box);
                    iv.setFitHeight(box);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    thumb.getChildren().add(iv);
                    placeholder.setVisible(false);
                });
            });
        }
        return thumb;
    }

    private void addFallbackThumb(StackPane thumb, String initial, int w, int h) {
        Label lbl = new Label(initial.toUpperCase());
        lbl.setStyle("-fx-text-fill: rgba(234,243,248,0.35); -fx-font-size:36px; -fx-font-weight:bold;");
        thumb.getChildren().add(lbl);
    }

    private boolean checkDir() {
        if (appContext.settings.minecraftDirectory == null
                || appContext.settings.minecraftDirectory.isBlank()) {
            statusLabel.setText("⚠ Set a Minecraft directory in Settings first.");
            return false;
        }
        return true;
    }

    private void setInstalling(Button btn) {
        btn.setDisable(true);
        btn.setText("Installing...");
        btn.setStyle(btn.getStyle() +
                "-fx-background-color: rgba(255,70,85,0.35);");
    }

    private void setInstalled(Button btn, String name, String folder) {
        btn.setDisable(false);
        btn.setText("✓ Installed");
        btn.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #1ec878, #2edc8a);" +
            "-fx-text-fill: #0f1923;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 9 20 9 20;" +
            "-fx-cursor: hand;" +
            "-fx-font-size: 12px;"
        );
        statusLabel.setText("✓ Installed " + name + " → " + folder + "/");
        AppLogger.get().info(TAG, "Installed " + name + " to " + folder);
    }

    private void setInstallFailed(Button btn, Throwable ex) {
        btn.setDisable(false);
        btn.setText("⚠ Retry");
        btn.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #e84242, #ff5a5a);" +
            "-fx-text-fill: #fff;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 9 20 9 20;" +
            "-fx-cursor: hand;" +
            "-fx-font-size: 12px;"
        );
        statusLabel.setText("⚠ Install failed: " + rootMsg(ex));
        AppLogger.get().error(TAG, "Install failed", ex);
    }

    private String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String rootMsg(Throwable ex) {
        Throwable c = ex;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : ex.getMessage();
    }
}