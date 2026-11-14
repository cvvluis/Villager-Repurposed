$ErrorActionPreference = 'Stop'
$skillsRoot = (Resolve-Path -LiteralPath '.agents/skills').Path
$expected = @(
    'fabric-26-2-expert',
    'fabric-26-2-mixin-internals-expert',
    'fabric-loom-gradle-expert',
    'minecraft-rendering-expert',
    'minecraft-performance-expert',
    'minecraft-mod-testing-expert',
    'minecraft-mod-architecture-expert'
)
$activationTerms = @{
    'fabric-26-2-expert' = 'registries|Fabric API|networking'
    'fabric-26-2-mixin-internals-expert' = 'Mixin|injector|access widener'
    'fabric-loom-gradle-expert' = 'Gradle|Loom|fabric.mod.json'
    'minecraft-rendering-expert' = 'rendering|renderer|HUD'
    'minecraft-performance-expert' = 'profil|optim|performance'
    'minecraft-mod-testing-expert' = 'test|crash|compatibility'
    'minecraft-mod-architecture-expert' = 'architecture|API|persistence'
}
$errors = [System.Collections.Generic.List[string]]::new()

foreach ($folder in $expected) {
    $skillDir = Join-Path $skillsRoot $folder
    $skillFile = Join-Path $skillDir 'SKILL.md'
    if (-not (Test-Path -LiteralPath $skillFile)) {
        $errors.Add("Missing $skillFile")
        continue
    }

    $content = Get-Content -LiteralPath $skillFile -Raw
    if ($content -notmatch '(?ms)^---\r?\nname: ([a-z0-9-]+)\r?\ndescription: "(.{80,})"\r?\n---') {
        $errors.Add("Invalid/missing name or specific activation description: $skillFile")
    } elseif ($Matches[1] -ne $folder) {
        $errors.Add("Folder/name mismatch: $folder vs $($Matches[1])")
    } elseif ($content -notmatch ('(?i)' + $activationTerms[$folder])) {
        $errors.Add("Activation description lacks its domain terms: $skillFile")
    }

    foreach ($section in @('Activation', 'Debugging', 'checklist', 'References')) {
        if ($content -notmatch [regex]::Escape($section)) {
            $errors.Add("Missing required section keyword '$section': $skillFile")
        }
    }

    $localRefs = [regex]::Matches($content, '\]\((references/[^)]+)\)')
    if ($localRefs.Count -eq 0) {
        $errors.Add("No local reference links: $skillFile")
    }
    foreach ($match in $localRefs) {
        $target = Join-Path $skillDir $match.Groups[1].Value
        if (-not (Test-Path -LiteralPath $target)) {
            $errors.Add("Broken local reference: $($match.Groups[1].Value) in $skillFile")
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "PASS: $($expected.Count) unique skill names, activation descriptions, required sections, and local reference targets."
