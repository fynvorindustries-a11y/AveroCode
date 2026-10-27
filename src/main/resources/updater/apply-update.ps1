<#
 AveroLauncher update helper.

 Started by the launcher (from a copy OUTSIDE the install folder) with one argument: a plan.json the launcher
 wrote after it downloaded and verified every file. It takes no commands from anywhere else.

 Order of operations:
   1  single-instance mutex, validate every path in the plan (must stay inside the per-user folders)
   2  wait for the launcher process to exit and release its files
   3  re-verify staged files (SHA-256) and free disk space  -> nothing in the install folder has been touched yet
   4  back up everything that will be replaced or deleted, write a journal
   5  replace files (copy to a temp name next to the target, then atomic File.Replace/Move), delete removed files
   6  re-verify the installed files, start the new launcher with AVERO_UPDATE_HEALTH_FILE set
   7  wait for the launcher's startup-ok signal  -> commit (drop backup)   |  otherwise ROLL BACK and restart the old version
 Compatible with Windows PowerShell 5.1 and PowerShell 7.
#>
param([Parameter(Mandatory = $true)][string]$PlanFile)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:LogFile = $null
$sep = [IO.Path]::DirectorySeparatorChar

function Write-Log([string]$msg) {
    try {
        if ($script:LogFile) {
            Add-Content -LiteralPath $script:LogFile -Encoding UTF8 -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'), $msg)
        }
    } catch { }
}

function Get-Opt($obj, [string]$name, $default) {
    $p = $obj.PSObject.Properties[$name]
    if ($p -and $null -ne $p.Value) { return $p.Value }
    return $default
}

function Test-Under([string]$child, [string]$root) {
    $r = $root.TrimEnd($sep) + $sep
    return $child.StartsWith($r, [StringComparison]::OrdinalIgnoreCase)
}

function Resolve-Rel([string]$base, [string]$rel) {
    if ([string]::IsNullOrEmpty($rel) -or $rel.StartsWith('/') -or $rel.Contains('\') -or $rel.Contains(':') -or
        $rel -match '(^|/)\.\.?(/|$)') { throw "unsafe path in plan: $rel" }
    $full = [IO.Path]::GetFullPath((Join-Path $base ($rel.Replace('/', [string]$sep))))
    if (-not (Test-Under $full $base)) { throw "path escapes its folder: $rel" }
    return $full
}

function Save-Json($obj, [string]$path) {
    ($obj | ConvertTo-Json -Depth 5) | Set-Content -LiteralPath $path -Encoding UTF8
}

function Put-File([string]$src, [string]$dest) {
    $parent = Split-Path -Parent $dest
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $tmp = $dest + '.avero-new'
    try {
        Copy-Item -LiteralPath $src -Destination $tmp -Force
        if (Test-Path -LiteralPath $dest -PathType Leaf) {
            try { (Get-Item -LiteralPath $dest).IsReadOnly = $false } catch { }
            # [NullString]::Value, not $null: PowerShell would turn $null into "" and File.Replace rejects that.
            [IO.File]::Replace($tmp, $dest, [NullString]::Value)
        } else {
            [IO.File]::Move($tmp, $dest)
        }
    } catch {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
        throw
    }
}

function Wait-Unlocked([string]$path, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $fs.Close()
            return
        } catch [IO.FileNotFoundException] { return } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "file stayed locked: $path"
}

function Remove-EmptyParents([string]$file, [string]$stopAt) {
    $dir = Split-Path -Parent $file
    while ($dir -and (Test-Under $dir $stopAt)) {
        if (@(Get-ChildItem -LiteralPath $dir -Force).Count -gt 0) { break }
        Remove-Item -LiteralPath $dir -Force
        $dir = Split-Path -Parent $dir
    }
}

function Invoke-Rollback($journal, [string]$installDir, [string]$backupDir) {
    Write-Log 'rolling back'
    $problems = New-Object System.Collections.ArrayList
    foreach ($rel in @($journal.created)) {
        if (-not $rel) { continue }
        try {
            $dest = Resolve-Rel $installDir $rel
            if (Test-Path -LiteralPath $dest -PathType Leaf) { Remove-Item -LiteralPath $dest -Force }
            Remove-EmptyParents $dest $installDir
        } catch { [void]$problems.Add("$rel : $($_.Exception.Message)") }
    }
    foreach ($rel in (@($journal.existed) + @($journal.deleted))) {
        if (-not $rel) { continue }
        try {
            $dest = Resolve-Rel $installDir $rel
            $bak = Resolve-Rel $backupDir $rel
            if (Test-Path -LiteralPath $bak -PathType Leaf) { Put-File $bak $dest }
        } catch { [void]$problems.Add("$rel : $($_.Exception.Message)") }
    }
    # sweep temp files an interrupted swap may have left behind
    Get-ChildItem -LiteralPath $installDir -Recurse -Force -Filter '*.avero-new' -ErrorAction SilentlyContinue |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
    if ($problems.Count -gt 0) { throw ('rollback could not restore: ' + ($problems -join '; ')) }
}

function Start-Launcher([string]$exe, [string]$workDir) {
    return Start-Process -FilePath $exe -WorkingDirectory $workDir -PassThru
}

# ------------------------------------------------------------------------------------------------ main
$exitCode = 1
$mutex = $null
$installDir = $null; $backupDir = $null; $updaterDir = $null; $exe = $null
$journal = $null; $journalPath = $null; $newProc = $null; $applyStarted = $false; $plan = $null

try {
    $plan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($plan.protocol -ne 1) { throw 'unsupported plan protocol' }

    $updaterDir = [IO.Path]::GetFullPath($plan.updaterDir)
    $script:LogFile = [IO.Path]::GetFullPath($plan.logFile)
    New-Item -ItemType Directory -Path (Split-Path -Parent $script:LogFile) -Force | Out-Null
    Write-Log ("helper start: {0} -> {1}" -f $plan.previousVersion, $plan.version)

    $mutex = New-Object System.Threading.Mutex($false, 'Local\AveroLauncherUpdateHelper')
    if (-not $mutex.WaitOne(0)) { Write-Log 'another helper is running'; exit 3 }

    # --- validate the plan
    $userRoot = $env:USERPROFILE
    if ([string]::IsNullOrEmpty($userRoot)) { $userRoot = $HOME }
    $userRoot = [IO.Path]::GetFullPath($userRoot)
    $installDir = [IO.Path]::GetFullPath($plan.installDir)
    $stagingDir = [IO.Path]::GetFullPath($plan.stagingDir)
    $backupDir = [IO.Path]::GetFullPath($plan.backupDir)
    $healthFile = [IO.Path]::GetFullPath($plan.healthFile)
    if (-not (Test-Under $installDir $userRoot)) { throw 'install folder is not inside the user profile' }
    if (-not (Test-Under $updaterDir $userRoot)) { throw 'updater folder is not inside the user profile' }
    foreach ($d in @($stagingDir, $backupDir, $healthFile, $script:LogFile)) {
        if (-not (Test-Under $d $updaterDir)) { throw "path outside the updater folder: $d" }
    }
    if (Test-Under $updaterDir $installDir) { throw 'updater folder must not be inside the install folder' }
    $exe = Resolve-Rel $installDir $plan.launcher
    $files = @($plan.files)
    $deletes = @($plan.delete | Where-Object { $_ })
    foreach ($f in $files) { [void](Resolve-Rel $installDir $f.path) }
    foreach ($d in $deletes) { [void](Resolve-Rel $installDir $d) }

    # --- recover from a previous run that died halfway
    $journalPath = Join-Path $backupDir 'journal.json'
    if (Test-Path -LiteralPath $journalPath) {
        $old = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        if ($old.state -eq 'applying') { Invoke-Rollback $old $installDir $backupDir }
        Remove-Item -LiteralPath $backupDir -Recurse -Force
    }

    # --- wait for the launcher to exit
    $proc = Get-Process -Id ([int]$plan.pid) -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Log 'waiting for launcher to exit'
        if (-not $proc.WaitForExit(60000)) { throw 'launcher did not exit in time' }
    }
    if (Test-Path -LiteralPath $exe) { Wait-Unlocked $exe 30 }

    # --- verify staged files and disk space BEFORE touching anything
    $needBytes = [long]0
    foreach ($f in $files) {
        $staged = Resolve-Rel $stagingDir $f.path
        if (-not (Test-Path -LiteralPath $staged -PathType Leaf)) { throw "staged file missing: $($f.path)" }
        if ((Get-Item -LiteralPath $staged).Length -ne [long]$f.size) { throw "staged file has wrong size: $($f.path)" }
        if ((Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant() -ne ([string]$f.sha256).ToLowerInvariant()) {
            throw "staged file failed verification: $($f.path)"
        }
        $needBytes += [long]$f.size
    }
    $free = $null
    try { $free = (New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($installDir))).AvailableFreeSpace }
    catch { Write-Log "disk space check skipped: $($_.Exception.Message)" }
    if ($null -ne $free -and $free -lt (2 * $needBytes + 32MB)) { throw 'not enough free disk space on the install drive' }
    Write-Log ("verified {0} staged files" -f $files.Count)

    # --- back up what will change
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $existed = New-Object System.Collections.ArrayList
    $created = New-Object System.Collections.ArrayList
    $deleted = New-Object System.Collections.ArrayList
    foreach ($f in $files) {
        $dest = Resolve-Rel $installDir $f.path
        if (Test-Path -LiteralPath $dest -PathType Leaf) {
            Put-File $dest (Resolve-Rel $backupDir $f.path); [void]$existed.Add($f.path)
        } else { [void]$created.Add($f.path) }
    }
    foreach ($d in $deletes) {
        $dest = Resolve-Rel $installDir $d
        if (Test-Path -LiteralPath $dest -PathType Leaf) {
            Put-File $dest (Resolve-Rel $backupDir $d); [void]$deleted.Add($d)
        }
    }
    $journal = [pscustomobject]@{ state = 'applying'; existed = $existed.ToArray(); created = $created.ToArray(); deleted = $deleted.ToArray() }
    Save-Json $journal $journalPath

    # --- apply
    $applyStarted = $true
    foreach ($f in $files) { Put-File (Resolve-Rel $stagingDir $f.path) (Resolve-Rel $installDir $f.path) }
    foreach ($d in $deleted) {
        $dest = Resolve-Rel $installDir $d
        Remove-Item -LiteralPath $dest -Force
        Remove-EmptyParents $dest $installDir
    }
    foreach ($f in $files) {
        $dest = Resolve-Rel $installDir $f.path
        if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne ([string]$f.sha256).ToLowerInvariant()) {
            throw "installed file failed verification: $($f.path)"
        }
    }
    Write-Log 'files applied and verified; starting new launcher'

    # --- start the new version and wait for its explicit startup signal
    if (Test-Path -LiteralPath $healthFile) { Remove-Item -LiteralPath $healthFile -Force }
    $env:AVERO_UPDATE_HEALTH_FILE = $healthFile
    try { $newProc = Start-Launcher $exe $installDir } finally { Remove-Item Env:\AVERO_UPDATE_HEALTH_FILE -ErrorAction SilentlyContinue }

    $timeout = [int](Get-Opt $plan 'healthTimeoutSec' 90)
    $grace = [int](Get-Opt $plan 'exitGraceSec' 15)
    $deadline = (Get-Date).AddSeconds($timeout)
    $exitedAt = $null
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $healthFile) {
            $h = $null
            try { $h = Get-Content -LiteralPath $healthFile -Raw | ConvertFrom-Json } catch { $h = $null }   # may be mid-write
            if ($h -and $h.version) {
                if ($h.version -eq $plan.version) { $healthy = $true; break }
                throw "new launcher reported version $($h.version)"
            }
        }
        if ($newProc.HasExited) {
            if ($null -eq $exitedAt) { $exitedAt = Get-Date }
            elseif (((Get-Date) - $exitedAt).TotalSeconds -gt $grace) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) { throw 'the new launcher did not confirm a successful start' }

    # --- commit
    $journal.state = 'committed'
    Save-Json $journal $journalPath
    foreach ($r in @($plan.recordFiles)) {
        if ($r) { Copy-Item -LiteralPath $r -Destination (Join-Path $updaterDir ([IO.Path]::GetFileName($r))) -Force }
    }
    Save-Json ([pscustomobject]@{ status = 'updated'; version = $plan.version; previousVersion = $plan.previousVersion; time = (Get-Date).ToString('o') }) (Join-Path $updaterDir 'last-update.json')
    Remove-Item -LiteralPath $backupDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Split-Path -Parent $stagingDir) -Recurse -Force -ErrorAction SilentlyContinue
    Write-Log 'update committed'
    $exitCode = 0
}
catch {
    $msg = $_.Exception.Message
    Write-Log ("FAILED: $msg")
    $status = 'failed'
    try {
        if ($applyStarted -and $journal) {
            if ($newProc -and -not $newProc.HasExited) { Stop-Process -Id $newProc.Id -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2 }
            Invoke-Rollback $journal $installDir $backupDir
            $status = 'rolled_back'
        }
    } catch { Write-Log ("ROLLBACK PROBLEM: " + $_.Exception.Message); $status = 'rollback_incomplete' }
    try {
        if ($updaterDir) {
            Save-Json ([pscustomobject]@{ status = $status; version = $(if ($plan) { $plan.version } else { '' }); error = $msg; time = (Get-Date).ToString('o') }) (Join-Path $updaterDir 'last-update.json')
        }
        if ($status -ne 'rollback_incomplete' -and $exe -and (Test-Path -LiteralPath $exe)) {
            [void](Start-Launcher $exe $installDir)      # bring the previous version back up
            Write-Log 'previous version restarted'
        }
    } catch { Write-Log ("RESTART PROBLEM: " + $_.Exception.Message) }
    $exitCode = 1
}
finally {
    if ($mutex) { try { $mutex.ReleaseMutex() } catch { } ; $mutex.Dispose() }
}
exit $exitCode
