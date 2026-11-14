$ErrorActionPreference = 'Stop'

function Read-G01ZipText($archive, [string]$entryName) {
	$entry = $archive.GetEntry($entryName)
	if ($null -eq $entry) { throw "Pinned worldgen resource is missing from its jar: $entryName" }
	$reader = [System.IO.StreamReader]::new($entry.Open())
	try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$resourcePath = Join-Path $repoRoot 'src/main/resources/data/minecraft/worldgen/structure_set/villages.json'
if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) {
	throw "G01 village structure-set resource is missing: $resourcePath"
}

$definition = Get-Content -Raw -LiteralPath $resourcePath | ConvertFrom-Json
$placement = $definition.placement
if ($placement.type -ne 'minecraft:random_spread') {
	throw 'G01 must preserve vanilla random-spread placement.'
}
if ([int]$placement.salt -ne 10387312) {
	throw 'G01 must preserve the vanilla village placement salt.'
}
if ([int]$placement.spacing -le 34 -or [int]$placement.separation -le 8) {
	throw 'G01 village-start spacing and separation must both exceed vanilla values (34/8).'
}
if ([int]$placement.spacing -ne 42 -or [int]$placement.separation -ne 12) {
	throw 'G01 village-start spacing policy drifted from the reviewed 42/12 values.'
}
if ([int]$placement.separation -ge [int]$placement.spacing) {
	throw 'G01 separation must remain lower than spacing.'
}

# This file controls distance between whole village starts only; keep the reviewed 42/12 values.
# House-to-house spacing is adjusted separately below through the actual vanilla and CTOV house pools.
$expectedStructures = @(
	'minecraft:village_plains',
	'minecraft:village_desert',
	'minecraft:village_savanna',
	'minecraft:village_snowy',
	'minecraft:village_taiga'
) | Sort-Object
$actualStructures = @($definition.structures | ForEach-Object { $_.structure } | Sort-Object)
if (($actualStructures -join "`n") -ne ($expectedStructures -join "`n")) {
	throw 'G01 must preserve the five vanilla village entries so CTOV can append its variants to the same set.'
}
if (@($definition.structures | Where-Object { [int]$_.weight -ne 1 }).Count -ne 0) {
	throw 'G01 must preserve the vanilla village structure weights.'
}

# Empty elements leave a connector as an open lot. Keep each entry within the game's 1-150 limit
# and target about half of every house pool for open lots. This lowers new-village house density
# materially but is not a guaranteed minimum gap: Jigsaw has no per-house spacing field. Existing
# chunks are untouched.
# Pool totals are pinned to Minecraft 26.2 and CTOV 4.1.0.
$modifierDirectory = Join-Path $repoRoot 'src/main/resources/data/villagerplus/lithostitched/worldgen_modifier/g01'
$spacingModifiers = @(
	[pscustomobject]@{
		file = 'vanilla_house_spacing.json'
		baseWeights = @{
			'minecraft:village/desert/houses' = 72
			'minecraft:village/desert/zombie/houses' = 68
			'minecraft:village/plains/houses' = 87
			'minecraft:village/plains/zombie/houses' = 83
			'minecraft:village/savanna/houses' = 81
			'minecraft:village/savanna/zombie/houses' = 72
			'minecraft:village/snowy/houses' = 68
			'minecraft:village/snowy/zombie/houses' = 65
			'minecraft:village/taiga/houses' = 76
			'minecraft:village/taiga/zombie/houses' = 74
		}
		weights = @(90)
		emptyShareRange = @(0.50, 0.60)
		ctov = $false
	},
	[pscustomobject]@{
		file = 'ctov_house_spacing.json'
		baseWeights = @{
			'ctov:village/beach/house' = 92
			'ctov:village/christmas/house' = 92
			'ctov:village/desert/house' = 102
			'ctov:village/desert_oasis/house' = 100
			'ctov:village/halloween/house' = 96
			'ctov:village/jungle/house' = 88
			'ctov:village/mesa/house' = 112
			'ctov:village/mesa_fortified/house' = 90
			'ctov:village/mountain/house' = 95
			'ctov:village/mountain_alpine/house' = 95
			'ctov:village/plains/house' = 89
			'ctov:village/swamp/house' = 100
			'ctov:village/swamp_fortified/house' = 95
			'ctov:village/taiga/house' = 98
			'ctov:village/taiga_fortified/house' = 83
		}
		weights = @(110)
		emptyShareRange = @(0.49, 0.57)
		ctov = $true
	},
	[pscustomobject]@{
		file = 'ctov_large_house_spacing.json'
		baseWeights = @{
			'ctov:village/jungle_tree/house' = 120
			'ctov:village/mushroom/house' = 143
			'ctov:village/savanna_na/house' = 150
			'ctov:village/snowy_igloo/house' = 135
		}
		weights = @(150)
		emptyShareRange = @(0.50, 0.56)
		ctov = $true
	},
	[pscustomobject]@{
		file = 'ctov_plains_fortified_house_spacing.json'
		baseWeights = @{ 'ctov:village/plains_fortified/house' = 266 }
		weights = @(150, 150)
		emptyShareRange = @(0.52, 0.54)
		ctov = $true
	},
	[pscustomobject]@{
		file = 'ctov_savanna_house_spacing.json'
		baseWeights = @{ 'ctov:village/savanna/house' = 535 }
		weights = @(150, 150, 150, 150)
		emptyShareRange = @(0.52, 0.54)
		ctov = $true
	},
	[pscustomobject]@{
		file = 'ctov_underground_house_spacing.json'
		baseWeights = @{ 'ctov:village/underground/house' = 190 }
		weights = @(150, 50)
		emptyShareRange = @(0.51, 0.53)
		ctov = $true
	}
)

# The pinned game jar is a local source for vanilla pool totals. Verify its real 26.2 weights
# when available, while keeping the script useful in a fresh checkout without Gradle caches.
$minecraftJarPath = Join-Path $env:USERPROFILE '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar'
$minecraftJarVerified = $false
if (Test-Path -LiteralPath $minecraftJarPath -PathType Leaf) {
	Add-Type -AssemblyName System.IO.Compression.FileSystem
	$minecraftArchive = [System.IO.Compression.ZipFile]::OpenRead($minecraftJarPath)
	try {
		$vanillaCase = @($spacingModifiers | Where-Object { $_.file -eq 'vanilla_house_spacing.json' })[0]
		foreach ($pool in $vanillaCase.baseWeights.Keys) {
			$poolPath = "data/minecraft/worldgen/template_pool/$($pool.Substring('minecraft:'.Length)).json"
			$poolData = Read-G01ZipText $minecraftArchive $poolPath | ConvertFrom-Json
			$actualWeight = 0
			foreach ($element in @($poolData.elements)) { $actualWeight += [int]$element.weight }
			if ($actualWeight -ne [int]$vanillaCase.baseWeights[$pool]) {
				throw "G01 Minecraft 26.2 pool baseline drifted for $pool (expected $($vanillaCase.baseWeights[$pool]), found $actualWeight)."
			}
			if (@($poolData.elements | Where-Object { $_.element.element_type -ne 'minecraft:empty_pool_element' }).Count -eq 0) {
				throw "G01 vanilla house pool has no house elements: $pool"
			}
		}
		$minecraftJarVerified = $true
		Write-Output "G01 verified $($vanillaCase.baseWeights.Count) pinned Minecraft 26.2 house-pool totals."
	} finally { $minecraftArchive.Dispose() }
} else {
	Write-Warning 'Minecraft 26.2 merged jar was not found in the Loom cache; using pinned vanilla pool totals.'
}

# When the exact Gradle-cached CTOV jar is available, recheck the pinned source pool totals
# and the jigsaw structure schema against its real resources. The JSON-only policy check can
# still run without the optional cache entry (for example in a fresh checkout).
$ctovJarVerified = $false
$propertiesText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'gradle.properties')
$ctovVersionMatch = [regex]::Match($propertiesText, '(?m)^modrinth_ctov=(\S+)\s*$')
if (-not $ctovVersionMatch.Success -or $ctovVersionMatch.Groups[1].Value -ne 'Vb09nbge') {
	throw 'G01 baselines are pinned to CTOV 4.1.0 (Modrinth version Vb09nbge); review the pools before changing this dependency.'
}
$ctovCacheDirectory = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/maven.modrinth/ct-overhaul-village/Vb09nbge'
$ctovJar = if (Test-Path -LiteralPath $ctovCacheDirectory -PathType Container) {
	Get-ChildItem -LiteralPath $ctovCacheDirectory -Recurse -File -Filter '*.jar' | Select-Object -First 1
} else { $null }
if ($null -ne $ctovJar) {
	$archive = [System.IO.Compression.ZipFile]::OpenRead($ctovJar.FullName)
	try {
		$metadata = Read-G01ZipText $archive 'fabric.mod.json' | ConvertFrom-Json
		if ($metadata.id -ne 'ctov' -or $metadata.version -ne '4.1.0') {
			throw "G01 expected CTOV 4.1.0, found $($metadata.id) $($metadata.version)."
		}
		$verifiedPools = 0
		foreach ($modifierCase in $spacingModifiers | Where-Object { $_.ctov }) {
			foreach ($pool in $modifierCase.baseWeights.Keys) {
				$poolPath = "data/ctov/worldgen/template_pool/$($pool.Substring('ctov:'.Length)).json"
				$poolData = Read-G01ZipText $archive $poolPath | ConvertFrom-Json
				$actualWeight = 0
				foreach ($element in @($poolData.elements)) { $actualWeight += [int]$element.weight }
				if ($actualWeight -ne [int]$modifierCase.baseWeights[$pool]) {
					throw "G01 CTOV 4.1.0 pool baseline drifted for $pool (expected $($modifierCase.baseWeights[$pool]), found $actualWeight)."
				}
				$verifiedPools++
			}
		}
		$structureEntries = @($archive.Entries | Where-Object {
			$_.FullName -match '^data/ctov/worldgen/structure/(small|medium|large)/village_.+\.json$'
		})
		if ($structureEntries.Count -eq 0) { throw 'G01 could not find CTOV village jigsaw structures in the pinned jar.' }
		foreach ($entry in $structureEntries) {
			$structure = Read-G01ZipText $archive $entry.FullName | ConvertFrom-Json
			if ($structure.type -ne 'lithostitched:jigsaw' -or $null -eq $structure.size -or $null -eq $structure.max_distance_from_center) {
				throw "G01 CTOV structure schema drifted: $($entry.FullName)"
			}
			$houseDistanceFields = @($structure.PSObject.Properties.Name | Where-Object {
				$_ -match '(?i)(house.*(distance|spacing)|(distance|spacing).*house)'
			})
			if ($houseDistanceFields.Count -gt 0) {
				throw "CTOV added a house-distance structure field ($($houseDistanceFields -join ', ')); review G01 for a hard gap control."
			}
		}
		$ctovJarVerified = $true
		Write-Output "G01 verified $verifiedPools pinned house-pool totals and $($structureEntries.Count) CTOV jigsaw structure schemas against CTOV 4.1.0."
	} finally { $archive.Dispose() }
} else {
	Write-Warning 'CTOV 4.1.0 jar was not found in the Gradle cache; running pinned JSON and probability checks only.'
}

$allConfiguredPools = @()
foreach ($modifierCase in $spacingModifiers) {
	$modifierPath = Join-Path $modifierDirectory $modifierCase.file
	if (-not (Test-Path -LiteralPath $modifierPath -PathType Leaf)) {
		throw "G01 house-spacing modifier is missing: $modifierPath"
	}
	$modifier = Get-Content -Raw -LiteralPath $modifierPath | ConvertFrom-Json
	if ($modifier.type -ne 'lithostitched:add_template_pool_elements') {
		throw "G01 house-spacing modifier has the wrong type: $($modifierCase.file)"
	}
	$actualPools = @($modifier.template_pools | ForEach-Object { [string]$_ } | Sort-Object)
	$expectedPools = @($modifierCase.baseWeights.Keys | Sort-Object)
	if (($actualPools -join "`n") -ne ($expectedPools -join "`n")) {
		throw "G01 target pools drifted in $($modifierCase.file)"
	}
	$elements = @($modifier.elements)
	if ($elements.Count -ne $modifierCase.weights.Count) {
		throw "G01 empty-lot entry count drifted in $($modifierCase.file)"
	}
	$emptyWeight = 0
	for ($index = 0; $index -lt $elements.Count; $index++) {
		if ([int]$elements[$index].weight -ne [int]$modifierCase.weights[$index]) {
			throw "G01 empty-lot weight drifted in $($modifierCase.file)"
		}
		if ([int]$elements[$index].weight -lt 1 -or [int]$elements[$index].weight -gt 150) {
			throw "G01 element weight is outside Minecraft's supported 1-150 range in $($modifierCase.file)"
		}
		if ($elements[$index].element.element_type -ne 'minecraft:empty_pool_element') {
			throw "G01 house-spacing modifier must add empty pool elements: $($modifierCase.file)"
		}
		if ($elements[$index].element.projection -ne 'rigid') {
			throw "G01 empty pool element must preserve rigid projection: $($modifierCase.file)"
		}
		$emptyWeight += [int]$elements[$index].weight
	}
	foreach ($pool in $actualPools) {
		$baseWeight = [double]$modifierCase.baseWeights[$pool]
		$emptyShare = [double]$emptyWeight / ($baseWeight + [double]$emptyWeight)
		if ($emptyShare -lt [double]$modifierCase.emptyShareRange[0] -or $emptyShare -gt [double]$modifierCase.emptyShareRange[1]) {
			$minimumShare = [math]::Round([double]$modifierCase.emptyShareRange[0] * 100, 1)
			$maximumShare = [math]::Round([double]$modifierCase.emptyShareRange[1] * 100, 1)
			throw "G01 empty-lot probability must stay in the $minimumShare-$maximumShare% band for $pool (actual: $([math]::Round($emptyShare * 100, 1))%)."
		}
	}
	if ($modifierCase.ctov) {
		$condition = $modifier.'fabric:load_conditions'
		if ($condition.condition -ne 'fabric:all_mods_loaded' -or @($condition.values).Count -ne 1 -or $condition.values[0] -ne 'ctov') {
			throw "CTOV-only modifier must be guarded by its Fabric load condition: $($modifierCase.file)"
		}
	} elseif ($null -ne $modifier.'fabric:load_conditions') {
		throw 'Minecraft vanilla house pools must receive their modifier even when CTOV is absent.'
	}
	$allConfiguredPools += $actualPools
}

$expectedPoolCount = 32
if ($allConfiguredPools.Count -ne $expectedPoolCount -or @($allConfiguredPools | Sort-Object -Unique).Count -ne $expectedPoolCount) {
	throw "G01 must configure exactly $expectedPoolCount distinct vanilla and CTOV house pools."
}

# CTOV house pools are one shared connector target per biome; its 4.1.0 structure JSON
# exposes jigsaw size/max-distance controls, but no per-house minimum-gap field. Add one
# small shared open-lot choice per CTOV pool instead of replacing pools or street templates.
$corridorDirectory = Join-Path $repoRoot 'src/main/resources/data/ctov/lithostitched/worldgen_modifier/g01'
$corridorFile = 'house_open_lot.json'
$corridorPath = Join-Path $corridorDirectory $corridorFile
if (-not (Test-Path -LiteralPath $corridorPath -PathType Leaf)) {
	throw "G01 CTOV open-lot modifier is missing: $corridorPath"
}
$corridor = Get-Content -Raw -LiteralPath $corridorPath | ConvertFrom-Json
if ($corridor.type -ne 'lithostitched:add_template_pool_elements') {
	throw "G01 CTOV open-lot modifier has the wrong type: $corridorFile"
}
$condition = $corridor.'fabric:load_conditions'
if ($condition.condition -ne 'fabric:all_mods_loaded' -or @($condition.values).Count -ne 1 -or $condition.values[0] -ne 'ctov') {
	throw 'CTOV-only open-lot modifier must be guarded when CTOV is absent.'
}
$actualPools = @($corridor.template_pools | ForEach-Object { [string]$_ } | Sort-Object)
$expectedPools = @($spacingModifiers | Where-Object { $_.ctov } | ForEach-Object { $_.baseWeights.Keys } | Sort-Object)
if (($actualPools -join "`n") -ne ($expectedPools -join "`n") -or @($actualPools | Sort-Object -Unique).Count -ne 22) {
	throw 'G01 CTOV open-lot targets must cover all 22 distinct pinned CTOV house pools exactly once.'
}
$elements = @($corridor.elements)
if ($elements.Count -ne 1 -or [int]$elements[0].weight -ne 10 -or $elements[0].element.element_type -ne 'minecraft:empty_pool_element' -or $elements[0].element.projection -ne 'rigid') {
	throw 'G01 CTOV open-lot modifier must add one rigid empty element of weight 10 per target pool.'
}
$minimumDelta = [double]::PositiveInfinity
$maximumDelta = [double]::NegativeInfinity
$minimumFinalShare = [double]::PositiveInfinity
$maximumFinalShare = [double]::NegativeInfinity
$corridorWeight = [double]$elements[0].weight
foreach ($pool in $actualPools) {
	$sourceCase = @($spacingModifiers | Where-Object { $_.ctov -and $_.baseWeights.ContainsKey($pool) })
	if ($sourceCase.Count -ne 1) { throw "G01 CTOV pool has no unique verified prior weight baseline: $pool" }
	$sourceCase = $sourceCase[0]
	$oldEmptyWeight = 0
	foreach ($weight in $sourceCase.weights) { $oldEmptyWeight += [int]$weight }
	$baseWeight = [double]$sourceCase.baseWeights[$pool]
	$currentEmptyShare = $oldEmptyWeight / ($baseWeight + $oldEmptyWeight)
	$revisedEmptyShare = ($oldEmptyWeight + $corridorWeight) / ($baseWeight + $oldEmptyWeight + $corridorWeight)
	$delta = $revisedEmptyShare - $currentEmptyShare
	$minimumDelta = [math]::Min($minimumDelta, $delta)
	$maximumDelta = [math]::Max($maximumDelta, $delta)
	$minimumFinalShare = [math]::Min($minimumFinalShare, $revisedEmptyShare)
	$maximumFinalShare = [math]::Max($maximumFinalShare, $revisedEmptyShare)
	if ($delta -le 0 -or $delta -gt 0.04) {
		throw "G01 CTOV open-lot probability change must stay above zero and at or below 4 percentage points for $pool (delta: $([math]::Round($delta * 100, 2)) points)."
	}
	if ($revisedEmptyShare -lt 0.50 -or $revisedEmptyShare -gt 0.60) {
		throw "G01 CTOV pool must keep open lots between 50% and 60% of final weight to materially reduce house density without removing houses: $pool (actual: $([math]::Round($revisedEmptyShare * 100, 1))%)."
	}
}

Write-Output "G01 vanilla/CTOV house-pool checks: PASS (vanilla open-lot share 50-60%; final CTOV open-lot share $([math]::Round($minimumFinalShare * 100, 1))-$([math]::Round($maximumFinalShare * 100, 1))%; corridor modifier adds +$([math]::Round($minimumDelta * 100, 1)) to +$([math]::Round($maximumDelta * 100, 1)) points; village-start 42/12 placement unchanged; no guaranteed minimum house gap)"
