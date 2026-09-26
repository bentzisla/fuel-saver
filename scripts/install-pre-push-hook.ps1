# Installs the repo's git hooks:
#   commit-msg: strips AI attribution trailers (Co-Authored-By: Claude/Anthropic, Claude-Session:, "Generated with Claude")
#   pre-push:   refuses commits with AI attribution or an Anthropic author/committer, then runs unit tests + lint
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File .\scripts\install-pre-push-hook.ps1
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$hooksDir = Join-Path $repoRoot ".git\hooks"

if (-not (Test-Path -LiteralPath $hooksDir)) {
    throw "No .git/hooks directory found under $repoRoot (is this a git repo?)"
}

$prePush = @'
#!/bin/sh
# FuelRoute pre-push gate: no AI attribution, then JVM unit tests + lint.
# Install via scripts/install-pre-push-hook.ps1
set -e
zero=0000000000000000000000000000000000000000
bad=""
while read -r local_ref local_sha remote_ref remote_sha; do
    [ "$local_sha" = "$zero" ] && continue
    if [ "$remote_sha" = "$zero" ]; then
        range="$local_sha --not --remotes"
    else
        range="$remote_sha..$local_sha"
    fi
    for c in $(git rev-list $range); do
        if git log -1 --format='%ae%n%ce' "$c" | grep -qi '@anthropic\.com$' ||
           git log -1 --format='%B' "$c" | grep -qiE '^(co-authored-by:.*(claude|anthropic)|claude-session:|.*generated with \[?claude)'; then
            bad="$bad $(git log -1 --format='%h %s' "$c")
"
        fi
    done
done
if [ -n "$bad" ]; then
    echo "[pre-push] Refusing to push commits with AI attribution or an Anthropic author/committer:"
    printf '%s' "$bad"
    echo "[pre-push] Rewrite them (author -> your git identity, drop the trailers) and push again."
    exit 1
fi
echo "[pre-push] Running unit tests + lint..."
./gradlew.bat testSideloadDebugUnitTest testPlayDebugUnitTest lintSideloadDebug lintPlayDebug --console=plain
'@

$commitMsg = @'
#!/bin/sh
# FuelRoute commit-msg: strip AI attribution trailers from the message.
# Install via scripts/install-pre-push-hook.ps1
msg="$1"
tmp="$msg.tmp"
grep -viE '^(co-authored-by:.*(claude|anthropic)|claude-session:|.*generated with \[?claude)' "$msg" > "$tmp" || true
# Drop trailing blank lines left behind by the removed trailers.
sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' "$tmp" > "$msg"
rm -f "$tmp"
'@

foreach ($h in @(@{ Name = "pre-push"; Body = $prePush }, @{ Name = "commit-msg"; Body = $commitMsg })) {
    $path = Join-Path $hooksDir $h.Name
    Set-Content -LiteralPath $path -Value ($h.Body -replace "`r`n", "`n") -Encoding ASCII -NoNewline
    Write-Host "Installed $($h.Name) hook at $path"
}
Write-Host "To skip the pre-push gate for a single push:  git push --no-verify"
