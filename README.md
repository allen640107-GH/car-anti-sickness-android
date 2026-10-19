# 車內防暈助手（Android 原生版）

在螢幕**左右邊緣**顯示半透明動態提示點（類似 iPhone「動態車輛提示」），可浮在其他 App（Line、Chrome、桌面）之上，跟隨車體晃動，減輕坐車暈眩。

> 這是 **原生 Android APK**，不是 PWA。網頁版無法畫在其他 App 上層。

## 功能

- **系統浮層**：使用「顯示在其他應用程式上層」權限，在左右邊緣繪製半透明提示點
- **自動偵測行駛**：GPS 速度約 15–25 km/h 持續數秒後開啟；停下約 45 秒後關閉（加速度感測為備援）
- **前景服務 + 通知**：可在背景持續監控；通知列可暫停／繼續／停止
- **一鍵設定**：授權浮層 → 定位 → 通知 → 開始監控
- **可正常解除安裝**

套件名稱：`com.allen.carsickness`  
最低系統：Android 8.0（API 26）

## 安裝 APK（側載）

### 1. 允許安裝未知應用程式

1. 將 `app-debug.apk` 傳到手機（下載、USB、Google Drive 皆可）
2. 用檔案總管開啟 APK
3. 若系統提示「禁止安裝未知應用程式」，到該來源（檔案／瀏覽器／Drive）的設定中開啟「允許從此來源安裝」

### 2. 安裝後首次設定

1. 開啟「**車內防暈助手**」
2. 點「**開始設定並啟動**」
3. 依序允許：
   - **顯示在其他應用程式上層**（系統設定頁開關）
   - **定位**（建議「一律允許」或「使用期間允許」）
   - **通知**（Android 13+）
4. 看到「監控中」後，可切換到 Line／Chrome 等 App
5. 開車達約 18 km/h 並持續約 8 秒後，左右邊緣會出現提示點
6. **測試用**：點「強制顯示提示點」可立刻看到邊緣點（不必真的開車）

### 3. 使用中

- 將本 App **留在背景**（不要強制停止）
- 通知列可「暫停／繼續／停止」
- 若不需要自動偵測，可關閉「自動偵測行駛」，改用強制顯示

### 4. 解除安裝

與一般 App 相同：設定 → 應用程式 → 車內防暈助手 → 解除安裝。

## 自行編譯

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # 或本機 JDK 17+
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
# 產物：app/build/outputs/apk/debug/app-debug.apk
```

## 技術說明

| 項目 | 實作 |
|------|------|
| 浮層 | `WindowManager` + `TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_TOUCHABLE`（不擋點擊） |
| 邊緣點 | 自訂 `DotsOverlayView`，左右各一排，跟隨加速度平移 |
| 行駛偵測 | `LocationManager` GPS 速度 + 加速度偏差（移植自 PWA `vehicle-detect.js`） |
| 背景 | `ForegroundService`（`FOREGROUND_SERVICE_LOCATION`） |

## 隱私

定位僅在本機用於估算速度，**不會上傳**。可隨時停止服務或解除安裝。

## 授權

個人使用／MIT（可自由修改）。
