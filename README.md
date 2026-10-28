# KEYBRIDGE 0.7.2 測試版

Android 手機在本機編輯文字，使用藍牙 HID 鍵盤，或透過 Windows 藍牙接收程式送出 Unicode 輸入。首頁提供獨立 Enter 按鈕。這是測試版，尚未在使用者的 POCO X6 與 Windows 11 組合完成實機驗收。

## 隨附檔案

- `KEYBRIDGE_0.7.2-beta.apk`：Android 除錯版；僅支援 ARM64、Android 9 以上。簽章為開發用簽章。
- `KEYBRIDGE_Windows_receiver_0.7.2-beta.zip`：Windows x64 自包含 EXE，無須另裝 .NET；未經程式碼簽署。
- 原始碼：`Android/` 與 `Windows/`。

## 直接藍牙鍵盤模式（預設）

這是一般藍牙鍵盤的使用方式：Windows 不需要安裝 KEYBRIDGE 接收程式。

1. 在手機 KEYBRIDGE 的設定中保留「直接鍵盤」，按「啟用手機鍵盤」。
2. 在 Windows 11 開啟「設定 → 藍牙與裝置 → 新增裝置 → Bluetooth」，選擇 `KEYBRIDGE` 或手機名稱完成配對。
3. 回到手機輸入文字，按「傳送」；獨立 Enter 按鈕會送出一次 Enter。

標準 Bluetooth HID 鍵盤可可靠表達的是英文、數字、基本符號與按鍵事件。中文、Emoji、部分非美式鍵盤符號會受 HID 鍵盤配置與 Windows 輸入法限制，不能保證直接輸入；需要完整 Unicode 時，才使用下方的進階模式。

## 完整文字模式（進階，需要 Windows 接收程式）

1. 下載 Windows 接收程式 ZIP，解壓縮後雙擊 `KeyBridgeReceiver.exe`。這是未簽署的測試程式；請先確認下載來源。保持視窗開啟。
2. 在手機與 Windows 的藍牙設定中完成配對。
3. 第一次使用時，手機點設定，選擇「完整文字」，載入已配對裝置、選電腦並輸入 Windows 視窗顯示的代碼，再連線。成功後兩端會記住信任；下次開啟 App 會自動嘗試連線，不必再次輸入代碼。
4. 在電腦目標文字欄位點選游標。手機輸入並檢查文字，按「傳送」。獨立 Enter 按鈕只送一次 Enter，不傳送文字框內容。
5. 回報「輸入事件已送出」不是目標應用程式確認內容正確。逾時或中斷時結果可能已部分或全部輸入；應先檢查電腦，再決定是否重新操作。

若要換手機或重新配對，請在手機設定按「解除信任／重新配對」，並在 Windows 視窗按同名按鈕；兩端都清除後再完成一次系統藍牙配對。自動連線只會建立連線與驗證，不會自動送出草稿文字。

Windows 接收端執行之前會檢查前景視窗，避免將文字輸入接收程式的視窗；輸入長文字時也會檢查前景視窗是否變更。這不能保證所有可能的焦點變化都被攔截。輸入較高權限的應用可能受 Windows UIPI 阻擋。換行（CR、LF 或 CRLF）以 Enter 輸入；目標程式可能有自己的行為。

## HID 鍵盤模式

需手機實際支援 Android HID Device 且與電腦完成配對。英文、數字和美式鍵盤的部分符號使用按下／釋放事件；中文和 Emoji 需用完整文字模式。鍵盤配置、輸入法與 Caps Lock 會影響輸出。系統可能在 App 不處於前景時取消 HID 註冊。

## 安全與限制

- 不記錄鍵盤輸入、不自動輸入、不錄音、不上傳資料。
- Windows 藍牙連線使用驗證與加密。六位代碼只用於首次信任交換；後續使用 Android Keystore 與 Windows 目前使用者 DPAPI 保護的 256-bit 信任金鑰，並以隨機挑戰驗證。此測試版未經獨立安全審查。
- 具訊息 ID，但 Android 不自動重送；Windows 僅在程式執行期間保留最近 1024 個 ID。使用者再按一次會產生新 ID。
- 自動連線只在 App 啟動時嘗試一次；背景常駐、即時同步、繁體轉換尚未實作。對端掉線不能在所有情況下即時發現。
- Android debug APK 與 Windows 程式仍需實機檢查，Windows 藍牙 API 在未封裝應用的相容性未證實。EXE 被安全軟體提示時請勿忽略；可自行檢查原始碼並建置。

## Windows PowerShell 啟動器

`Start-KEYBRIDGE.ps1` 是測試版啟動器，使用目前使用者的 `%LOCALAPPDATA%`，不需要管理員權限。它會檢查指定版本的 EXE，缺少時下載固定版本的 ZIP，並驗證 ZIP 和 EXE 的 SHA-256。雜湊不符會停止；啟動器不會繞過 Windows 藍牙配對，也不會以管理員權限安裝服務。

藍牙裝置檢查不能保證手機已配對。請在 Windows 藍牙設定中確認；手機第一次必須選擇電腦並輸入接收程式代碼。程式未經簽署，請先檢查來源與檔案，再自行決定是否執行。

## 授權

本專案自身程式碼尚未指定開源授權。AndroidX、Kotlin 與其他第三方依賴的授權請參閱 `third-party-licenses/`。

## 從原始碼建置

Android：JDK 17、Android SDK 35，使用 Android Studio 開啟 `Android/`。執行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`），需網路下載依賴。

Windows：.NET 8 SDK，在 `Windows/` 執行 `dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true`。需使用 Windows 10 2004 或更新的 SDK。

## 本環境檢查結果

Android `assembleDebug`、`lintDebug`、APK 簽章驗證；Windows x64 Release 自包含 GUI EXE 編譯。藍牙、手機、Windows 執行及焦點／輸入：均未在 POCO X6 與 Windows 11 實機驗收。APK 是 debug 建置；自動測試尚未覆蓋實體藍牙與 Windows 原生 API。繁體中文、Emoji、Enter 及藍牙連線的實際表現仍需測試。
