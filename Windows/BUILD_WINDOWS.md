# Windows 接收程式建置

此程式必須在 Windows 11 上建置與執行，因為它使用 Windows Runtime 的 Bluetooth RFCOMM API 及 `user32.dll` 的 `SendInput`。

需求：

- Windows 10 2004（build 19041）或更新版本
- .NET 8 SDK
- 已啟用 Bluetooth Classic

在 PowerShell：

```powershell
cd Windows
dotnet restore
dotnet build -c Release
dotnet run -c Release
```

要產生單一可執行檔：

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

執行程式後會開啟圖形化視窗。第一次連線時顯示六位數代碼；手機與 Windows 必須先在系統藍牙設定完成配對，接著在 KEYBRIDGE 的「完整文字」模式輸入代碼並連線。成功後會以 Windows 目前使用者 DPAPI 保存信任資料，下一次由手機自動驗證，不會再次要求代碼。視窗內可解除信任並重新配對。

程式不會安裝服務、不會背景記錄鍵盤，也不會在沒有手機「傳送」請求時輸入文字。Windows 的 `SendInput` 回傳成功只代表輸入事件進入系統佇列，不能確認特定 App 已顯示完整文字。這些流程尚未在使用者的 POCO X6 與 Windows 11 組合實機驗收。
