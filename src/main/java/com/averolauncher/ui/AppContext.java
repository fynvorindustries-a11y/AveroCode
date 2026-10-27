package com.averolauncher.ui;

import com.averolauncher.auth.MicrosoftAuthService;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.mods.ModManager;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.service.CurseForgeService;
import com.averolauncher.service.LaunchService;
import com.averolauncher.service.ModrinthService;
import com.averolauncher.update.UpdateChecker;
import com.averolauncher.update.UpdateDownloader;

/**
 * Holds the shared, long-lived services pages need (settings, launch
 * pipeline) so each page doesn't spin up its own thread pools or duplicate
 * state. Created once in AveroLauncherApplication and passed down to
 * MainWindow and its pages.
 */
public class AppContext {
    public final LauncherSettings settings;
    public final SettingsRepository settingsRepository;
    public final LaunchService launchService;
    public final ModrinthService modrinthService;
    public final CurseForgeService curseForgeService;
    public final ModManager modManager;
    public final AccountRepository accountRepository;
    public MicrosoftAuthService microsoftAuthService; // rebuilt when msClientId changes in Settings
    public final UpdateChecker updateChecker;
    public final UpdateDownloader updateDownloader;

    public AppContext(LauncherSettings settings, SettingsRepository settingsRepository, LaunchService launchService,
                       ModrinthService modrinthService, CurseForgeService curseForgeService,
                       ModManager modManager, AccountRepository accountRepository,
                       MicrosoftAuthService microsoftAuthService, UpdateChecker updateChecker,
                       UpdateDownloader updateDownloader) {
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.launchService = launchService;
        this.modrinthService = modrinthService;
        this.curseForgeService = curseForgeService;
        this.modManager = modManager;
        this.accountRepository = accountRepository;
        this.microsoftAuthService = microsoftAuthService;
        this.updateChecker = updateChecker;
        this.updateDownloader = updateDownloader;
    }
}
