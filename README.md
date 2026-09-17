# α6k転送 / α6k Transfer

Sony αカメラの Wi-Fi アクセスポイントに接続し、DLNA/UPnP MediaServer から写真・動画を Android 端末へ転送するアプリです。
Connect to a Sony α camera's Wi-Fi access point and transfer photos and videos from its DLNA/UPnP media server to your Android device.

- 対応機種 / Supported: α6000（実機検証済み / verified on-device）、α6100以降（実験的 / experimental）
- 対応メディア / Media: JPEG写真、MP4動画（カメラがDLNAで公開するもの / as exposed by the camera via DLNA）
- UI言語 / Languages: 日本語 / English（設定で切替 / switchable in Settings）
- テーマ / Themes: システム / ライト / ダーク

## 使い方 / Usage

1. カメラ：MENU → スマートフォン転送 → スマートフォンから選ぶ →「表示中」
   Camera: MENU → Send to Smartphone → Select on Smartphone → "Displaying"
2. アプリの「接続」タブで「接続＋バインド」（初回のみ承認ダイアログ）
   In the Connect tab, tap "Connect + bind" (approval dialog on first use)
3. 「カメラ探索」→ LOCATION確定後、「転送」タブで「一覧読込」
   Tap "Discover camera", then load the list in the Transfer tab
4. 写真・動画を選択して転送（バックグラウンド継続対応）
   Select photos/videos and transfer (continues in the background)

## 主な機能 / Features

- 写真（JPEG）・動画（MP4）の一覧・選択転送、種別フィルタ（すべて/写真/動画）
  Browse and transfer JPEG photos and MP4 videos, with All/Photos/Videos filter
- バックグラウンド転送（フォアグラウンドサービス＋通知プログレス＋中止対応）
  Background transfer via a foreground service with notification progress and cancel
- 転送済みラベル（端末上の永続履歴＋ギャラリー照合）
  "Saved" labels backed by on-device persistent history plus gallery matching
- 保存フォルダ指定（設定 → DCIM配下サブフォルダ名）
  Custom save subfolder under DCIM (Settings)
- 固定ポート・固定コンテナIDを持たない探索設計（NOTIFY待受 → LOCATION → Browse → 深さ優先走査）
  Port-free discovery (NOTIFY listen → LOCATION → Browse → depth-first walk)

※ RAW（.ARW等）はα6000がDLNAで公開しないため転送対象外です。
RAW (.ARW, etc.) is not exposed by the α6000 over DLNA and is therefore out of scope.

## 動作環境 / Requirements

- Android 10（API 29）以降
- 位置情報権限（Wi-Fiスキャン用）、通知権限（転送進捗表示用、Android 13以降）

## ビルド / Build

```bash
./gradlew :app:assembleRelease
```

署名鍵は公開リポジトリに含みません（更新互換のため同一証明書で署名）。
The signing key is not included in this repository.

## 作者 / Author

Yuki_Orita（折田悠希 / おりたゆうき）

- 公式サイト: https://studio-rizi.pages.dev/
- 紹介ページ: https://studio-rizi.pages.dev/projects/a6k-transfer/
- X: https://x.com/InovateofRIZI
- Discord（バグ報告・告知用）: https://discord.gg/x7KXhNTD8M
- GitHub: https://github.com/oriyu90/a6k-transfer

## ライセンス / License

MIT License — see [LICENSE](LICENSE).
