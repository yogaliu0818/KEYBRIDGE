<# KEYBRIDGE 0.7 beta: verify the receiver before every launch. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$version = 'v0.7.0-beta'
$zipHash = '5119BE60C69C69BA25AC0D690AB6FAC7F458238DD3B086A5405C3700C1D2AE6D'
$exeHash = 'CA9FD5C18DFA691A6533A4AF5CF654731E23542522D7F875963CCBF782B1CFB9'
$url = 'https://github.com/yogaliu0818/KEYBRIDGE/releases/download/v0.7.0-beta/KEYBRIDGE_Windows_receiver_0.7.0-beta.zip'
$installDir = Join-Path $env:LOCALAPPDATA "KEYBRIDGE\$version"
$exe = Join-Path $installDir 'KeyBridgeReceiver.exe'

try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw '需要 64 位元 Windows。' }
    $valid = (Test-Path -LiteralPath $exe -PathType Leaf) -and ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -eq $exeHash)
    if (-not $valid) {
        if (Test-Path -LiteralPath $exe) { throw "現有接收程式的 SHA-256 不符，已停止；請檢查 $exe" }
        New-Item -ItemType Directory -Path $installDir -Force | Out-Null
        $tmp = Join-Path $installDir ([guid]::NewGuid().ToString() + '.zip')
        try {
            Write-Host '下載 Windows 接收程式…'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
            if ((Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash -ne $zipHash) { throw '下載 ZIP 的 SHA-256 不符，已停止。' }
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $archive = [IO.Compression.ZipFile]::OpenRead($tmp)
            try {
                $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'KeyBridgeReceiver.exe' })
                if ($entries.Count -ne 1) { throw 'ZIP 內的接收程式不存在或重複。' }
                $candidate = Join-Path $installDir ([guid]::NewGuid().ToString() + '.tmp')
                try {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $candidate)
                    if ((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $exeHash) { throw '接收程式的 SHA-256 不符，已停止。' }
                    Move-Item -LiteralPath $candidate -Destination $exe
                } finally { if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate -Force } }
            } finally { $archive.Dispose() }
        } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force } }
    }
    Write-Host '接收程式 SHA-256 驗證通過。'
    try {
        $radios = @(Get-PnpDevice -Class Bluetooth -PresentOnly -ErrorAction Stop | Where-Object { $_.InstanceId -match '^(USB|PCI|ACPI)\\' })
        if ($radios.Count -eq 0) { Write-Warning '無法確認藍牙介面卡是否可用；請在 Windows 設定中檢查。' }
        elseif (-not @($radios | Where-Object Status -eq 'OK').Count) { Write-Warning '藍牙介面卡未顯示正常；請在 Windows 設定中檢查。' }
        else { Write-Host '偵測到正常的藍牙裝置；此檢查無法確認藍牙無線電已開啟。' }
    } catch { Write-Warning '無法讀取藍牙裝置狀態；請在 Windows 設定中檢查。' }
    Write-Host '請確認 Windows 藍牙已開啟，並在設定中與手機配對。配對不代表 KEYBRIDGE 已連線。'
    $choice = Read-Host '輸入 S 開啟藍牙設定；按 Enter 繼續啟動接收程式'
    if ($choice -match '^[sS]$') { Start-Process 'ms-settings:bluetooth'; Read-Host '完成檢查後按 Enter' | Out-Null }
    Write-Host '手機第一次使用：選擇「完整文字」、選取已配對電腦，輸入接收程式顯示的六位代碼。成功後下次會自動連線。'
    Write-Host '請保持接收程式視窗開啟；自動連線不會自動輸入文字，必須在手機按「傳送」。'
    & $exe
    if ($LASTEXITCODE -ne 0) { throw "接收程式結束，代碼：$LASTEXITCODE" }
} catch {
    Write-Error "KEYBRIDGE 啟動失敗：$($_.Exception.Message)" -ErrorAction Continue
    exit 1
}
