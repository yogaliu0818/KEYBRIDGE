# 專案工作記錄

- 0.6 測試版新增一次性首次配對與信任：Android 以 Android Keystore 保護信任金鑰，Windows 以目前使用者 DPAPI 保護；後續用隨機挑戰自動驗證。Windows 改為 WinForms GUI，提供藍牙設定捷徑與解除信任。
- 0.6 編譯產物雜湊：最終 APK `389FE48CA8B5057EBE1F7F3D8A15B20EBF4677BEABFA81DA9FA82D66C2CF84B8`；Windows ZIP `5119BE60C69C69BA25AC0D690AB6FAC7F458238DD3B086A5405C3700C1D2AE6D`；ZIP 內 EXE `CA9FD5C18DFA691A6533A4AF5CF654731E23542522D7F875963CCBF782B1CFB9`。
- 0.6 只驗證 Android assembleDebug/lintDebug、Windows self-contained publish；POCO X6、Windows 11、實體藍牙、中文/Emoji/Enter/語音和焦點輸入仍未驗收。

- 0.5 測試版原始碼 ZIP 的 SHA-256：`44099DC4F4927CF15E50F896F7D81626047582EC81509DCFBAC9D6768EA11E16`。
- Android APK 的 SHA-256：`DA788CCDD5BB7CA731C3DBF67E7ECEEEF9D2C659EF6557F1B5A924583608BC0F`；Windows 接收程式 ZIP：`A1F2F8CDBA99F59D71B37630AFA03048C160AF4CA339B7FC07566FF4DFBBD7BA`。
- POCO X6 與 Windows 11 的藍牙、Unicode、Enter 和語音辨識尚未實機驗收。配對、通道連線與輸入事件送出是不同狀態。
- whisper.cpp 採 MIT；原始碼包另含多種第三方內容。例如未啟用的 `examples/ffmpeg-transcode.cpp` 標示 GPL-2.0。公開前須檢查各依賴及其授權，不可將整個原始碼包一律標為 MIT。
- Windows 啟動器目前仍是初稿：無法自動確認手機已配對或藍牙無線電狀態，下載及實機流程尚待驗證。
