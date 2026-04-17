param(
  [Parameter(Mandatory = $true)]
  [string]$ArtifactDir,

  [Parameter(Mandatory = $true)]
  [ValidateSet("x64", "arm64")]
  [string]$Arch,

  [Parameter(Mandatory = $true)]
  [string]$Version,

  [Parameter(Mandatory = $true)]
  [string]$WorkspaceRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-SafeId {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Prefix,

    [Parameter(Mandatory = $true)]
    [string]$Value
  )

  $safe = [Regex]::Replace($Value, '[^A-Za-z0-9_]', '_')
  if ([string]::IsNullOrWhiteSpace($safe)) {
    $safe = "Item"
  }
  return "$Prefix$safe"
}

function ConvertTo-Rtf {
  param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
  )

  $text = Get-Content -LiteralPath $InputPath -Raw
  $escaped = $text.Replace('\', '\\').Replace('{', '\{').Replace('}', '\}')
  $escaped = $escaped -replace "`r`n|`n|`r", "\\par`r`n"
  $rtf = @"
{\rtf1\ansi\deff0{\fonttbl{\f0\fnil Segoe UI;}}
\viewkind4\uc1\pard\f0\fs20
$escaped
\par
}
"@
  Set-Content -LiteralPath $OutputPath -Value $rtf -Encoding ASCII
}

function Add-DirectoryXml {
  param(
    [Parameter(Mandatory = $true)]
    [System.Xml.XmlDocument]$Document,

    [Parameter(Mandatory = $true)]
    [System.Xml.XmlElement]$ParentElement,

    [Parameter(Mandatory = $true)]
    [string]$DirectoryPath,

    [Parameter(Mandatory = $true)]
    [string]$RelativePath,

    [Parameter(Mandatory = $true)]
    [System.Collections.Generic.List[string]]$FeatureRefs
  )

  $currentElement = $ParentElement
  if ($RelativePath -ne ".") {
    $directoryElement = $Document.CreateElement("Directory", $Document.DocumentElement.NamespaceURI)
    $directoryElement.SetAttribute("Id", (Get-SafeId -Prefix "Dir_" -Value $RelativePath))
    $directoryElement.SetAttribute("Name", [IO.Path]::GetFileName($DirectoryPath))
    $ParentElement.AppendChild($directoryElement) | Out-Null
    $currentElement = $directoryElement
  }

  Get-ChildItem -LiteralPath $DirectoryPath -File | Sort-Object Name | ForEach-Object {
    $fileRelativePath = if ($RelativePath -eq ".") { $_.Name } else { "$RelativePath/$($_.Name)" }
    $componentId = Get-SafeId -Prefix "Cmp_" -Value $fileRelativePath

    $componentElement = $Document.CreateElement("Component", $Document.DocumentElement.NamespaceURI)
    $componentElement.SetAttribute("Id", $componentId)
    $componentElement.SetAttribute("Guid", "*")

    $fileElement = $Document.CreateElement("File", $Document.DocumentElement.NamespaceURI)
    $fileElement.SetAttribute("Id", (Get-SafeId -Prefix "Fil_" -Value $fileRelativePath))
    $fileElement.SetAttribute("Source", $_.FullName)
    $fileElement.SetAttribute("KeyPath", "yes")
    $componentElement.AppendChild($fileElement) | Out-Null

    $currentElement.AppendChild($componentElement) | Out-Null
    $FeatureRefs.Add($componentId) | Out-Null
  }

  Get-ChildItem -LiteralPath $DirectoryPath -Directory | Sort-Object Name | ForEach-Object {
    $childRelativePath = if ($RelativePath -eq ".") { $_.Name } else { "$RelativePath/$($_.Name)" }
    Add-DirectoryXml `
      -Document $Document `
      -ParentElement $currentElement `
      -DirectoryPath $_.FullName `
      -RelativePath $childRelativePath `
      -FeatureRefs $FeatureRefs
  }
}

$artifactDir = (Resolve-Path -LiteralPath $ArtifactDir).Path
$workspaceRoot = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$zipArtifact = Get-ChildItem -LiteralPath $artifactDir -Filter "*.win.zip" | Sort-Object Name | Select-Object -First 1
if (-not $zipArtifact) {
  throw "No .win.zip artifact found in $artifactDir"
}

$stageRoot = Join-Path $workspaceRoot ".codex-wix\$Arch"
if (Test-Path -LiteralPath $stageRoot) {
  Remove-Item -LiteralPath $stageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stageRoot | Out-Null

$extractDir = Join-Path $stageRoot "expanded"
Expand-Archive -LiteralPath $zipArtifact.FullName -DestinationPath $extractDir -Force

$topLevelEntries = Get-ChildItem -LiteralPath $extractDir
if ($topLevelEntries.Count -eq 1 -and $topLevelEntries[0].PSIsContainer) {
  $payloadRoot = $topLevelEntries[0].FullName
} else {
  $payloadRoot = $extractDir
}

$licenseSource = Join-Path $workspaceRoot "LICENSE"
$licenseRtf = Join-Path $stageRoot "LICENSE.rtf"
ConvertTo-Rtf -InputPath $licenseSource -OutputPath $licenseRtf

$icoPath = Join-Path $workspaceRoot "customization\resources\termlab.ico"
$mainExe = Join-Path $payloadRoot "bin\termlab64.exe"
if (-not (Test-Path -LiteralPath $mainExe)) {
  throw "Expected main executable at $mainExe"
}

$wixSource = Join-Path $stageRoot "TermLabMsi.wxs"
$msiOutput = Join-Path $artifactDir ("termlab-$Version-$Arch.msi")
$upgradeCode = if ($Arch -eq "x64") { "{3FB1C112-61C2-4D0E-A7E4-A0AF9E7F8C17}" } else { "{EA94A290-3ED7-44A4-A1A7-76AB95B62F33}" }

$doc = New-Object System.Xml.XmlDocument
$declaration = $doc.CreateXmlDeclaration("1.0", "utf-8", $null)
$doc.AppendChild($declaration) | Out-Null

$wix = $doc.CreateElement("Wix", "http://wixtoolset.org/schemas/v4/wxs")
$wix.SetAttribute("xmlns:ui", "http://wixtoolset.org/schemas/v4/wxs/ui")
$doc.AppendChild($wix) | Out-Null

$package = $doc.CreateElement("Package", $wix.NamespaceURI)
$package.SetAttribute("Name", "TermLab")
$package.SetAttribute("Manufacturer", "TermLab")
$package.SetAttribute("Version", $Version)
$package.SetAttribute("UpgradeCode", $upgradeCode)
$package.SetAttribute("Language", "1033")
$package.SetAttribute("InstallerVersion", "500")
$package.SetAttribute("Scope", "perMachine")
$package.SetAttribute("Compressed", "yes")
$wix.AppendChild($package) | Out-Null

$majorUpgrade = $doc.CreateElement("MajorUpgrade", $wix.NamespaceURI)
$majorUpgrade.SetAttribute("DowngradeErrorMessage", "A newer version of [ProductName] is already installed.")
$package.AppendChild($majorUpgrade) | Out-Null

$mediaTemplate = $doc.CreateElement("MediaTemplate", $wix.NamespaceURI)
$mediaTemplate.SetAttribute("EmbedCab", "yes")
$package.AppendChild($mediaTemplate) | Out-Null

$icon = $doc.CreateElement("Icon", $wix.NamespaceURI)
$icon.SetAttribute("Id", "AppIcon")
$icon.SetAttribute("SourceFile", $icoPath)
$package.AppendChild($icon) | Out-Null

$arpIcon = $doc.CreateElement("Property", $wix.NamespaceURI)
$arpIcon.SetAttribute("Id", "ARPPRODUCTICON")
$arpIcon.SetAttribute("Value", "AppIcon")
$package.AppendChild($arpIcon) | Out-Null

$standardDirectory = $doc.CreateElement("StandardDirectory", $wix.NamespaceURI)
$standardDirectory.SetAttribute("Id", "ProgramFiles6432Folder")
$package.AppendChild($standardDirectory) | Out-Null

$installRoot = $doc.CreateElement("Directory", $wix.NamespaceURI)
$installRoot.SetAttribute("Id", "INSTALLROOT")
$installRoot.SetAttribute("Name", "TermLab")
$standardDirectory.AppendChild($installRoot) | Out-Null

$feature = $doc.CreateElement("Feature", $wix.NamespaceURI)
$feature.SetAttribute("Id", "MainFeature")
$feature.SetAttribute("Title", "TermLab")
$feature.SetAttribute("Level", "1")
$package.AppendChild($feature) | Out-Null

$ui = $doc.CreateElement("ui", "WixUI", "http://wixtoolset.org/schemas/v4/wxs/ui")
$ui.SetAttribute("Id", "WixUI_FeatureTree")
$package.AppendChild($ui) | Out-Null

$wixVariable = $doc.CreateElement("WixVariable", $wix.NamespaceURI)
$wixVariable.SetAttribute("Id", "WixUILicenseRtf")
$wixVariable.SetAttribute("Value", $licenseRtf)
$package.AppendChild($wixVariable) | Out-Null

$componentRefs = New-Object 'System.Collections.Generic.List[string]'
Add-DirectoryXml `
  -Document $doc `
  -ParentElement $installRoot `
  -DirectoryPath $payloadRoot `
  -RelativePath "." `
  -FeatureRefs $componentRefs

foreach ($componentId in $componentRefs) {
  $componentRef = $doc.CreateElement("ComponentRef", $wix.NamespaceURI)
  $componentRef.SetAttribute("Id", $componentId)
  $feature.AppendChild($componentRef) | Out-Null
}

$settings = New-Object System.Xml.XmlWriterSettings
$settings.Indent = $true
$settings.Encoding = [System.Text.UTF8Encoding]::new($false)
$writer = [System.Xml.XmlWriter]::Create($wixSource, $settings)
$doc.Save($writer)
$writer.Dispose()

try {
  dotnet tool update --global wix | Out-Null
} catch {
  dotnet tool install --global wix | Out-Null
}
$env:PATH += ";$env:USERPROFILE\.dotnet\tools"
wix extension add -g WixToolset.UI.wixext
wix build $wixSource -arch $Arch -ext WixToolset.UI.wixext -out $msiOutput

if (-not (Test-Path -LiteralPath $msiOutput)) {
  throw "MSI was not created at $msiOutput"
}

Write-Host "Built MSI: $msiOutput"
