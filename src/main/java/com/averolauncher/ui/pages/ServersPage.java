package com.averolauncher.ui.pages;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.model.DirectoryServerEntry;
import com.averolauncher.model.LaunchProfile;
import com.averolauncher.model.ServerEntry;
import com.averolauncher.model.manifest.VersionManifest;
import com.averolauncher.servers.ServerPinger;
import com.averolauncher.store.AveroId;
import com.averolauncher.store.StoreApi;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.NavPage;
import com.averolauncher.util.UiAccents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Servers page: three tabs.
 * <ul>
 *   <li><b>Favorites</b> — starred servers, shown first so they're one click away.</li>
 *   <li><b>Server Directory</b> — a centrally-maintained list of public servers, fetched live
 *       from the Avero backend ({@code GET /api/v1/servers/directory}) with real-time ping,
 *       status and player counts. Networks that require a premium (Microsoft) account are
 *       hidden automatically from players using an offline account — connecting would just
 *       fail, so there's no point showing them.</li>
 *   <li><b>My Servers</b> — servers the player has manually added, same as before.</li>
 * </ul>
 * "Connect" installs the selected version if needed and launches straight into that server.
 */
public class ServersPage {

    private static final String TAG = "ServersPage";

    private final AppContext appContext;
    private final StoreApi api = AveroId.get().api();

    private List<ServerEntry> servers;
    private List<DirectoryServerEntry> directoryServers = new ArrayList<>();

    private final VBox favoritesBox = new VBox(10);
    private final VBox directoryBox = new VBox(10);
    private final VBox serverListBox = new VBox(10); // "My Servers" tab
    private final Label statusLabel = new Label();
    private final Label directoryStatusLabel = new Label();

    // Each tab keeps its own view/ping map, even for the same underlying server, so a
    // server shown in both Favorites and My Servers gets two independently live rows
    // instead of one tab silently stealing the other's ping updates.
    private final Map<String, ServerRowViews> rowViews = new HashMap<>();       // My Servers, keyed by ServerEntry.id
    private final Map<String, ServerRowViews> favRowViews = new HashMap<>();    // Favorites, keyed by ServerEntry.id
    private final Map<String, ServerRowViews> dirRowViews = new HashMap<>();    // Directory, keyed by DirectoryServerEntry.id

    private final ScheduledExecutorService pingExecutor = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "server-pinger");
        t.setDaemon(true);
        return t;
    });

    public ServersPage(AppContext appContext) {
        this.appContext = appContext;
        this.servers = appContext.settings.savedServers != null
                ? new ArrayList<>(appContext.settings.savedServers) : new ArrayList<>();
    }

    public VBox build() {
        VBox root = new VBox(16);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));

        StackPane hero = UiAccents.heroBanner("/images/servers-hero.png", 88);

        Label title = new Label("Servers");
        title.getStyleClass().add("page-title");

        HBox titleRow = new HBox(14, UiAccents.pageBadge(NavPage.SERVERS, 40), title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label("Saved servers let you connect directly with one click. The launcher installs the version if needed, then starts Minecraft with that server pre-joined.");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("page-subtitle");

        // Segmented tab bar (same look as the Play page) instead of a JavaFX TabPane:
        // a TabPane sizes itself from its content's *initial* preferred height, so the
        // async-loaded server directory got clipped, and its default chrome ignored the theme.
        VBox favoritesContent = buildFavoritesTabContent();
        VBox directoryContent = buildDirectoryTabContent();
        VBox myServersContent = buildMyServersTabContent();

        VBox tabHolder = new VBox();
        tabHolder.getChildren().setAll(favoritesContent); // favorites are the point of this page — show them first

        ToggleGroup tabGroup = new ToggleGroup();
        ToggleButton favoritesBtn = new ToggleButton("★ Favorites");
        ToggleButton directoryBtn = new ToggleButton("🌐 Server Directory");
        ToggleButton myServersBtn = new ToggleButton("💾 My Servers");
        ToggleButton[] tabButtons = {favoritesBtn, directoryBtn, myServersBtn};
        VBox[] tabPanes = {favoritesContent, directoryContent, myServersContent};
        for (int i = 0; i < tabButtons.length; i++) {
            ToggleButton b = tabButtons[i];
            final VBox pane = tabPanes[i];
            b.getStyleClass().add("play-tab-button");
            b.setToggleGroup(tabGroup);
            b.setOnAction(e -> tabHolder.getChildren().setAll(pane));
            // A ToggleGroup lets the selected button be clicked off; keep one tab always selected.
            b.selectedProperty().addListener((o, was, now) -> {
                if (!now && tabGroup.getSelectedToggle() == null) b.setSelected(true);
            });
        }
        favoritesBtn.setSelected(true);

        HBox tabBar = new HBox(6, favoritesBtn, directoryBtn, myServersBtn);
        tabBar.getStyleClass().add("play-tab-bar");
        tabBar.setMaxWidth(Region.USE_PREF_SIZE);
        VBox tabs = new VBox(0, tabBar, tabHolder);
        tabs.setAlignment(Pos.TOP_CENTER);

        refreshList();
        pingAllServers();
        refreshFavoritesTab();
        loadDirectory();

        root.getChildren().addAll(hero, titleRow, UiAccents.pageUnderline(), subtitle, tabs);
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account type — whether the signed-in Minecraft account can join premium-only
    // (authenticated) servers like Hypixel, or only cracked/offline-friendly ones.
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isPremiumAccount() {
        Account acc = resolveActiveAccount();
        return acc != null && acc.type == Account.Type.MICROSOFT;
    }

    private Account resolveActiveAccount() {
        if (appContext.settings.activeAccountId == null) return null;
        return appContext.accountRepository.load().stream()
                .filter(a -> a.id.equals(appContext.settings.activeAccountId))
                .findFirst().orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Favorites — persisted as a set of normalized addresses. A favorite always
    // corresponds to a real saved ServerEntry: favoriting a Directory server that
    // isn't saved yet saves it first, so the Favorites tab can simply filter
    // "My Servers" by address instead of juggling two different row shapes.
    // ─────────────────────────────────────────────────────────────────────────

    private static String normalizeAddr(String address) {
        return address == null ? "" : address.trim().toLowerCase();
    }

    private boolean isFavorite(String address) {
        return appContext.settings.favoriteServerAddresses.contains(normalizeAddr(address));
    }

    private void setFavorite(String address, boolean fav) {
        String key = normalizeAddr(address);
        if (fav) appContext.settings.favoriteServerAddresses.add(key);
        else appContext.settings.favoriteServerAddresses.remove(key);
        appContext.settingsRepository.save(appContext.settings);
    }

    /** Finds the saved server matching a directory entry's address, or creates and saves one. */
    private ServerEntry findOrCreateSaved(DirectoryServerEntry d) {
        for (ServerEntry s : servers) {
            if (s.address.equalsIgnoreCase(d.address)) return s;
        }
        ServerEntry s = new ServerEntry(UUID.randomUUID().toString(), d.name, d.address);
        s.description = d.description;
        s.category = d.category;
        servers.add(s);
        saveServers();
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Favorites tab
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildFavoritesTabContent() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20, 0, 0, 0));
        Label hint = new Label("Servers you've starred, from either the Directory or My Servers tab.");
        hint.setWrapText(true);
        hint.getStyleClass().add("page-subtitle");
        VBox.setVgrow(favoritesBox, Priority.ALWAYS);
        box.getChildren().addAll(hint, favoritesBox);
        return box;
    }

    private void refreshFavoritesTab() {
        favoritesBox.getChildren().clear();
        favRowViews.clear();
        List<ServerEntry> favs = servers.stream()
                .filter(s -> isFavorite(s.address))
                .collect(Collectors.toList());
        if (favs.isEmpty()) {
            Label empty = new Label("No favorites yet. Star a server in the Directory or My Servers tab to pin it here.");
            empty.getStyleClass().add("activity-empty");
            favoritesBox.getChildren().add(empty);
            return;
        }
        for (ServerEntry s : favs) {
            favoritesBox.getChildren().add(buildServerRow(s, favRowViews));
            pingServer(s, favRowViews);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server Directory tab — fetched from the backend, filtered by account type
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildDirectoryTabContent() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20, 0, 0, 0));

        Label hint = new Label(isPremiumAccount()
                ? "A centrally-maintained list of public servers, including premium networks since you're signed in with a Microsoft account."
                : "Showing cracked-friendly servers only — sign in with a Microsoft account to also see premium networks like Hypixel.");
        hint.setWrapText(true);
        hint.getStyleClass().add("page-subtitle");

        Button refreshBtn = new Button("↻ Refresh Directory");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setOnAction(e -> loadDirectory());

        directoryStatusLabel.getStyleClass().add("play-card-hint");
        directoryStatusLabel.setWrapText(true);

        HBox toolbar = new HBox(12, refreshBtn, directoryStatusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(directoryBox, Priority.ALWAYS);
        box.getChildren().addAll(hint, toolbar, directoryBox);
        return box;
    }

    private void loadDirectory() {
        directoryBox.getChildren().clear();
        dirRowViews.clear();
        Label loading = new Label("Loading server directory…");
        loading.getStyleClass().add("activity-empty");
        directoryBox.getChildren().add(loading);
        directoryStatusLabel.setText("");

        api.fetchServerDirectory().whenComplete((list, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                directoryBox.getChildren().clear();
                Label err = new Label("Couldn't load the server directory: " + rootMessage(ex));
                err.getStyleClass().add("activity-empty");
                err.setWrapText(true);
                directoryBox.getChildren().add(err);
                AppLogger.get().error(TAG, "Failed to load server directory", ex);
                return;
            }
            boolean premium = isPremiumAccount();
            directoryServers = list.stream()
                    .filter(d -> premium || !d.requiresPremium)
                    .collect(Collectors.toList());
            int hidden = list.size() - directoryServers.size();
            directoryStatusLabel.setText(hidden > 0
                    ? hidden + " premium-only server" + (hidden == 1 ? "" : "s") + " hidden — sign in with Microsoft to see them."
                    : "");
            renderDirectory();
        }));
    }

    private void renderDirectory() {
        directoryBox.getChildren().clear();
        dirRowViews.clear();
        if (directoryServers.isEmpty()) {
            Label empty = new Label("No servers available right now.");
            empty.getStyleClass().add("activity-empty");
            directoryBox.getChildren().add(empty);
            return;
        }
        for (DirectoryServerEntry d : directoryServers) {
            directoryBox.getChildren().add(buildDirectoryRow(d));
            pingDirectoryServer(d);
        }
    }

    private HBox buildDirectoryRow(DirectoryServerEntry d) {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 18, 14, 18));
        row.getStyleClass().add("settings-section");

        ServerRowViews views = new ServerRowViews();
        dirRowViews.put(d.id, views);

        views.iconPane = new StackPane();
        views.iconPane.setPrefSize(48, 48);
        views.iconPane.setMinSize(48, 48);
        views.iconPane.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 0;");
        views.iconFallback = new Label(d.name.isBlank() ? "?" : d.name.substring(0, 1).toUpperCase());
        views.iconFallback.getStyleClass().add("modpack-card-icon-label");
        views.iconView = new ImageView();
        views.iconView.setFitWidth(48);
        views.iconView.setFitHeight(48);
        views.iconView.setVisible(false);
        views.iconPane.getChildren().addAll(views.iconFallback, views.iconView);

        VBox info = new VBox(2);
        Label name = new Label(d.name);
        name.getStyleClass().add("profile-name");
        Label addr = new Label(d.address);
        addr.getStyleClass().add("profile-tag");
        Label category = new Label(d.category + (d.requiresPremium ? "  ·  Premium account required" : "  ·  Cracked-friendly"));
        category.getStyleClass().add("modpack-card-meta");
        Label desc = new Label(d.description != null ? d.description : "");
        desc.setWrapText(true);
        desc.setMaxWidth(320);
        desc.getStyleClass().add("modpack-card-meta");
        info.getChildren().addAll(name, addr, category, desc);

        views.statusDot = new Circle(4, Color.web("#8a8a8a"));
        views.statusText = new Label("Checking…");
        views.statusText.getStyleClass().add("profile-tag");
        HBox statusRow = new HBox(6, views.statusDot, views.statusText);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        views.playersLabel = new Label("—");
        views.playersLabel.getStyleClass().add("modpack-card-meta");
        views.pingLabel = new Label("—");
        views.pingLabel.getStyleClass().add("modpack-card-meta");
        views.motdLabel = new Label(""); // unused here; description already shown above

        VBox statusCol = new VBox(2, statusRow, views.playersLabel, views.pingLabel);
        statusCol.setMinWidth(120);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        boolean fav = isFavorite(d.address);
        Button favBtn = new Button(fav ? "★ Favorited" : "☆ Favorite");
        favBtn.getStyleClass().add("secondary-button");
        favBtn.setOnAction(e -> {
            ServerEntry saved = findOrCreateSaved(d);
            boolean now = !isFavorite(saved.address);
            setFavorite(saved.address, now);
            favBtn.setText(now ? "★ Favorited" : "☆ Favorite");
            refreshList();
            refreshFavoritesTab();
        });

        Button connectBtn = new Button("▶ Connect");
        connectBtn.getStyleClass().add("play-button");
        connectBtn.setStyle("-fx-padding: 7 18 7 18; -fx-font-size: 12px;");
        UiAccents.applyPrimaryGradient(connectBtn);
        connectBtn.setOnAction(e -> {
            ServerEntry saved = findOrCreateSaved(d);
            refreshList();
            connectToServer(saved, connectBtn);
        });

        Button refreshBtn = new Button("↻");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setTooltip(new Tooltip("Refresh status"));
        refreshBtn.setOnAction(e -> pingDirectoryServer(d));

        row.getChildren().addAll(views.iconPane, info, spacer, statusCol, favBtn, connectBtn, refreshBtn);
        return row;
    }

    private void pingDirectoryServer(DirectoryServerEntry d) {
        ServerRowViews views = dirRowViews.get(d.id);
        if (views == null) return;
        resetViews(views);
        pingExecutor.execute(() -> {
            ServerPinger.PingResult result = ServerPinger.ping(d.address);
            Platform.runLater(() -> {
                if (dirRowViews.get(d.id) == views) applyPingResultGeneric(views, result);
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // My Servers tab — manually saved servers (unchanged behavior from before)
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildMyServersTabContent() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20, 0, 0, 0));

        Label savedHeader = new Label("Your Servers");
        savedHeader.getStyleClass().add("section-header");

        Button addBtn = new Button("+ Add Server");
        addBtn.getStyleClass().add("primary-cta-button");
        addBtn.setStyle(
            "-fx-background-color: linear-gradient(to bottom, rgba(255,255,255,0.22) 0%, rgba(255,255,255,0.0) 45%), linear-gradient(to bottom right, #ff4655 0%, #ff5d6b 100%);" +
            "-fx-text-fill: #ffffff;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 9 20 9 20;" +
            "-fx-cursor: hand;"
        );
        addBtn.setOnAction(e -> showAddDialog());

        Button refreshAllBtn = new Button("↻ Refresh All");
        refreshAllBtn.getStyleClass().add("secondary-button");
        refreshAllBtn.setOnAction(e -> pingAllServers());

        statusLabel.getStyleClass().add("play-card-hint");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        addBtn.setMinWidth(Region.USE_PREF_SIZE);
        refreshAllBtn.setMinWidth(Region.USE_PREF_SIZE);

        HBox toolbar = new HBox(12, addBtn, refreshAllBtn, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        VBox.setVgrow(serverListBox, Priority.ALWAYS);

        box.getChildren().addAll(savedHeader, toolbar, serverListBox);
        return box;
    }

    private void refreshList() {
        serverListBox.getChildren().clear();
        rowViews.clear();
        if (servers.isEmpty()) {
            Label empty = new Label("No servers added yet. Click '+ Add Server' to save one, or add one from the Server Directory tab.");
            empty.getStyleClass().add("activity-empty");
            serverListBox.getChildren().add(empty);
            return;
        }
        for (ServerEntry s : servers) {
            serverListBox.getChildren().add(buildServerRow(s, rowViews));
        }
    }

    /** Bundles the parts of a server row that get updated once its ping result comes back. */
    private static final class ServerRowViews {
        StackPane iconPane;
        ImageView iconView;
        Label iconFallback;
        Circle statusDot;
        Label statusText;
        Label playersLabel;
        Label pingLabel;
        Label motdLabel;
    }

    private HBox buildServerRow(ServerEntry s, Map<String, ServerRowViews> targetMap) {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 18, 14, 18));
        row.getStyleClass().add("settings-section");

        ServerRowViews views = new ServerRowViews();
        targetMap.put(s.id, views);

        views.iconPane = new StackPane();
        views.iconPane.setPrefSize(48, 48);
        views.iconPane.setMinSize(48, 48);
        views.iconPane.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 0;");

        views.iconFallback = new Label(s.name.isBlank() ? "?" : s.name.substring(0, 1).toUpperCase());
        views.iconFallback.getStyleClass().add("modpack-card-icon-label");

        views.iconView = new ImageView();
        views.iconView.setFitWidth(48);
        views.iconView.setFitHeight(48);
        views.iconView.setVisible(false);

        if (s.iconBase64 != null && !s.iconBase64.isBlank()) {
            applyFavicon(views, s.iconBase64);
        }
        views.iconPane.getChildren().addAll(views.iconFallback, views.iconView);

        VBox info = new VBox(2);
        Label name = new Label(s.name);
        name.getStyleClass().add("profile-name");
        Label addr = new Label(s.address);
        addr.getStyleClass().add("profile-tag");
        views.motdLabel = new Label("");
        views.motdLabel.getStyleClass().add("modpack-card-meta");
        views.motdLabel.setWrapText(true);
        views.motdLabel.setMaxWidth(320);
        info.getChildren().addAll(name, addr, views.motdLabel);

        views.statusDot = new Circle(4, Color.web("#8a8a8a"));
        views.statusText = new Label("Checking…");
        views.statusText.getStyleClass().add("profile-tag");
        HBox statusRow = new HBox(6, views.statusDot, views.statusText);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        views.playersLabel = new Label("—");
        views.playersLabel.getStyleClass().add("modpack-card-meta");
        views.pingLabel = new Label("—");
        views.pingLabel.getStyleClass().add("modpack-card-meta");

        VBox statusCol = new VBox(2, statusRow, views.playersLabel, views.pingLabel);
        statusCol.setMinWidth(120);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        boolean fav = isFavorite(s.address);
        Button favBtn = new Button(fav ? "★ Favorited" : "☆ Favorite");
        favBtn.getStyleClass().add("secondary-button");
        favBtn.setOnAction(e -> {
            boolean now = !isFavorite(s.address);
            setFavorite(s.address, now);
            favBtn.setText(now ? "★ Favorited" : "☆ Favorite");
            refreshFavoritesTab();
        });

        Button connectBtn = new Button("▶ Connect");
        connectBtn.getStyleClass().add("play-button");
        connectBtn.setStyle("-fx-padding: 7 18 7 18; -fx-font-size: 12px;");
        UiAccents.applyPrimaryGradient(connectBtn);
        connectBtn.setOnAction(e -> connectToServer(s, connectBtn));

        Button refreshBtn = new Button("↻");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setTooltip(new Tooltip("Refresh status"));
        refreshBtn.setOnAction(e -> pingServer(s, targetMap));

        Button removeBtn = new Button("Remove");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setOnAction(e -> {
            servers.remove(s);
            rowViews.remove(s.id);
            favRowViews.remove(s.id);
            setFavorite(s.address, false);
            saveServers();
            refreshList();
            refreshFavoritesTab();
        });

        row.getChildren().addAll(views.iconPane, info, spacer, statusCol, favBtn, connectBtn, refreshBtn, removeBtn);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live status: online/offline, ping, players, favicon
    // ─────────────────────────────────────────────────────────────────────────

    private void pingAllServers() {
        for (ServerEntry s : servers) pingServer(s, rowViews);
    }

    private void pingServer(ServerEntry s, Map<String, ServerRowViews> targetMap) {
        ServerRowViews views = targetMap.get(s.id);
        if (views == null) return;
        resetViews(views);

        pingExecutor.execute(() -> {
            ServerPinger.PingResult result = ServerPinger.ping(s.address);
            Platform.runLater(() -> {
                // Discard result if the list was rebuilt since this ping was queued.
                if (targetMap.get(s.id) == views) {
                    applyPingResult(s, views, result);
                }
            });
        });
    }

    private void resetViews(ServerRowViews views) {
        views.statusDot.setFill(Color.web("#8a8a8a"));
        views.statusText.setText("Checking…");
        views.playersLabel.setText("—");
        views.pingLabel.setText("—");
    }

    private void applyPingResult(ServerEntry s, ServerRowViews views, ServerPinger.PingResult result) {
        applyPingResultGeneric(views, result);
        if (result.online && result.faviconBase64 != null && !result.faviconBase64.isBlank()
                && !result.faviconBase64.equals(s.iconBase64)) {
            s.iconBase64 = result.faviconBase64;
            saveServers();
        }
    }

    /** Same as {@link #applyPingResult}, but for rows with no backing {@link ServerEntry} to persist a favicon to. */
    private void applyPingResultGeneric(ServerRowViews views, ServerPinger.PingResult result) {
        if (result.online) {
            views.statusDot.setFill(Color.web("#4caf50"));
            views.statusText.setText("Online");
            views.playersLabel.setText(result.onlinePlayers + " / " + result.maxPlayers + " players");
            views.pingLabel.setText(result.pingMs + " ms" + (result.versionName != null && !result.versionName.isBlank()
                    ? "  ·  " + result.versionName : ""));
            if (views.motdLabel != null) views.motdLabel.setText(result.motd != null ? result.motd : "");
            if (result.faviconBase64 != null && !result.faviconBase64.isBlank()) {
                applyFavicon(views, result.faviconBase64);
            }
        } else {
            views.statusDot.setFill(Color.web("#e53935"));
            views.statusText.setText("Offline");
            views.playersLabel.setText("—");
            views.pingLabel.setText(result.errorMessage != null ? result.errorMessage : "Unreachable");
            if (views.motdLabel != null) views.motdLabel.setText("");
        }
    }

    private void applyFavicon(ServerRowViews views, String base64Png) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Png);
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError()) return;
            views.iconView.setImage(image);
            views.iconView.setVisible(true);
            views.iconFallback.setVisible(false);
        } catch (IllegalArgumentException ignored) {
            // malformed base64 — keep the fallback letter tile
        }
    }

    private void showAddDialog() {
        Dialog<ServerEntry> dialog = new Dialog<>();
        dialog.setTitle("Add Server");
        dialog.setHeaderText("Enter server details");

        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField nameField = new TextField();
        nameField.setPromptText("My Server");
        TextField addressField = new TextField();
        addressField.setPromptText("play.example.com:25565");

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Address:"), 0, 1);
        grid.add(addressField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == addType) {
                String name = nameField.getText().trim();
                String addr = addressField.getText().trim();
                if (!name.isBlank() && !addr.isBlank()) {
                    return new ServerEntry(UUID.randomUUID().toString(), name, addr);
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(s -> {
            servers.add(s);
            saveServers();
            refreshList();
            pingServer(s, rowViews);
        });
    }

    private void connectToServer(ServerEntry s, Button btn) {
        if (appContext.settings.minecraftDirectory == null || appContext.settings.minecraftDirectory.isBlank()) {
            statusLabel.setText("Set a Minecraft directory in Settings first.");
            return;
        }
        String versionId = appContext.settings.defaultVersionId;
        if (versionId == null || versionId.isBlank()) {
            statusLabel.setText("Set a default version first (Play page > select a version).");
            return;
        }

        btn.setDisable(true);
        statusLabel.setText("Connecting to " + s.address + "…");

        LaunchProfile profile = new LaunchProfile();
        profile.id = "server-" + s.id;
        profile.name = s.name;
        profile.versionId = versionId;
        profile.serverAddressAutoJoin = s.address;

        appContext.launchService.getManifestService().fetchManifest(true)
                .thenCompose(manifest -> {
                    VersionManifest.VersionEntry entry = manifest.versions.stream()
                            .filter(v -> v.id.equals(versionId)).findFirst()
                            .orElseThrow(() -> new RuntimeException("Version " + versionId + " not found in manifest."));
                    return appContext.launchService.installAndLaunch(
                            entry, appContext.settings, profile,
                            resolveActiveAccount(), appContext.microsoftAuthService,
                            task -> {}, line -> {}, msg -> Platform.runLater(() -> statusLabel.setText(msg)));
                })
                .whenComplete((proc, ex) -> Platform.runLater(() -> {
                    btn.setDisable(false);
                    if (ex != null) {
                        statusLabel.setText("Failed: " + rootMessage(ex));
                        AppLogger.get().error(TAG, "Server connect failed", ex);
                    } else {
                        statusLabel.setText("Connected to " + s.address + " (PID " + proc.pid() + ")");
                    }
                }));
    }

    private void saveServers() {
        appContext.settings.savedServers = new ArrayList<>(servers);
        appContext.settingsRepository.save(appContext.settings);
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
    }
}
