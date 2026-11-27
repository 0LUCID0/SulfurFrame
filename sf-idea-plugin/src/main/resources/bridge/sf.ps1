# SulfurFrame: the running Minecraft game for coding agents. `sf help` lists the commands.
# Finds the IDE session whose project folder holds the current directory (or the only one), drops a request file
# there and prints the answer. Written by the SulfurFrame plugin into ~/.sulfurframe/bin; edits are overwritten.
$sfHome = if ($env:SULFURFRAME_HOME) { $env:SULFURFRAME_HOME } else { Join-Path $HOME '.sulfurframe' }
$root = Join-Path $sfHome 'bridge'
$utf8 = New-Object System.Text.UTF8Encoding($false)
if ($args.Count -gt 0 -and ($args[0] -eq 'run' -or $args[0] -eq 'relay' -or ($args[0] -eq 'feedback' -and $args.Count -gt 1 -and $args[1] -eq 'send'))) {   # no IDE: the host (or the feedback relay, or sending a report) runs here, in the foreground
    $bin = Join-Path $sfHome 'bin'
    $java = if ($env:SF_JAVA) { $env:SF_JAVA } elseif (Test-Path (Join-Path $bin 'host.java.txt')) { [IO.File]::ReadAllLines((Join-Path $bin 'host.java.txt'), $utf8)[0] } else { 'java' }
    $jar = if ($env:SF_HOST_JAR) { $env:SF_HOST_JAR } elseif (Test-Path (Join-Path $bin 'host.classpath.txt')) { [IO.File]::ReadAllLines((Join-Path $bin 'host.classpath.txt'), $utf8)[0] } else { $null }
    if (-not $jar) { [Console]::Error.WriteLine('sf run: no SulfurFrame jar known yet: open IntelliJ with SulfurFrame once, or set SF_HOST_JAR'); exit 3 }
    $gone = @($jar.Split(';') | Where-Object { $_ -and -not (Test-Path -LiteralPath $_) })
    if ($gone.Count -gt 0) { [Console]::Error.WriteLine("sf run: not found (moved or uninstalled): $($gone -join ', '): open IntelliJ with SulfurFrame once, or set SF_HOST_JAR"); exit 3 }
    $hostArgs = if ($args[0] -eq 'feedback') { @('feedback-send') + @($args | Select-Object -Skip 2) } else { $args }
    & $java --enable-native-access=ALL-UNNAMED -cp $jar dev.sulfurframe.idea.HeadlessHost @hostArgs
    exit $LASTEXITCODE
}
function Get-NormPath([string]$p) { $p.Replace([char]92, [char]47).ToLowerInvariant().TrimEnd([char]47) + '/' }
$here = Get-NormPath (Get-Location).ProviderPath
# the session whose project folder holds this folder (the longest match), among those alive: their IDE or sf run
# touches "alive" every 10 s, so a folder left by one that died (older than a minute) is skipped
$sessions = @(Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue | Where-Object {
    (Test-Path (Join-Path $_.FullName 'session.txt')) -and (Test-Path (Join-Path $_.FullName 'alive')) -and
    ((Get-Date) - (Get-Item (Join-Path $_.FullName 'alive')).LastWriteTime).TotalSeconds -lt 60 })
$session = $null; $best = 0; $open = @()
foreach ($d in $sessions) {
    $raw = [IO.File]::ReadAllLines((Join-Path $d.FullName 'session.txt'), $utf8)[0]
    $open += $raw
    $proj = Get-NormPath $raw
    if ($here.StartsWith($proj) -and $proj.Length -gt $best) { $session = $d.FullName; $best = $proj.Length }
}
if (-not $session -and $args.Count -gt 0 -and $args[0] -eq 'feedback') {   # no IDE: the report is still kept, without the game's state
    $dir = Join-Path (Join-Path $sfHome 'feedback') 'inbox'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $name = (Split-Path -Leaf (Get-Location).ProviderPath) -replace '[^A-Za-z0-9._-]', '_'
    $f = Join-Path $dir ("{0}-{1}.md" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $name)
    $text = ($args | Select-Object -Skip 1) -join ' '
    $nl = "`n"
    $body = "# SulfurFrame feedback (no IDE session)$nl$nl## What the agent reported$nl$nl$text$nl$nl## State$nl${nl}no SulfurFrame IDE answered; folder $((Get-Location).ProviderPath)$nl"
    [IO.File]::WriteAllText($f, $body, $utf8)
    "saved: $f"
    "To send it to SulfurFrame's developers as a GitHub issue, the user runs (in their own terminal): sf feedback send"; exit 0
}
if (-not $session) {
    if ($open.Count -gt 0) { [Console]::Error.WriteLine("sf: no SulfurFrame session for $((Get-Location).ProviderPath); run sf from inside one of these projects:`n  " + ($open -join "`n  ")) }
    else { [Console]::Error.WriteLine('sf: no SulfurFrame session is running. Open the project in IntelliJ with SulfurFrame 0.3.0 or newer (the first with sf), or start the game with: sf run') }
    exit 3
}
$req = Join-Path $session 'req'
$id = "$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
[IO.File]::WriteAllText((Join-Path $req "$id.part"), (($args -join ' ') + "`n"), $utf8)
Move-Item (Join-Path $req "$id.part") (Join-Path $req "$id.req")
$res = Join-Path $req "$id.res"
$i = 0
while (-not (Test-Path $res)) {
    Start-Sleep -Milliseconds 200; $i++
    if ((Test-Path (Join-Path $req "$id.req")) -and $i -ge 25) { Remove-Item (Join-Path $req "$id.req") -ErrorAction SilentlyContinue; [Console]::Error.WriteLine('sf: the IDE did not take the request (is it open and responsive?)'); exit 3 }
    if ($i -ge 3600) { [Console]::Error.WriteLine('sf: no answer in 12 minutes'); exit 3 }
    if (-not (Test-Path (Join-Path $session 'session.txt'))) {   # the IDE closed or sf run ended meanwhile
        if ($args.Count -gt 0 -and $args[0] -eq 'quit') { 'the game saved and quit'; exit 0 }
        [Console]::Error.WriteLine('sf: the session ended (IntelliJ closed or sf run finished)'); exit 3
    }
}
$lines = [IO.File]::ReadAllLines($res, $utf8)
Remove-Item $res
$code = 2
if ($lines.Count -gt 0 -and $lines[0] -match '^#exit (\d+)') { $code = [int]$Matches[1] }
[Console]::OutputEncoding = $utf8
$lines | Select-Object -Skip 1 | ForEach-Object { [Console]::Out.WriteLine($_) }
exit $code
