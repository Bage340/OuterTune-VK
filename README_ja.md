# OuterTune

[![OuterTune app icon](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/outertune.webp)](https://github.com/yuuichi-s/OuterTune/blob/dev/assets/outertune.webp)


[![Latest release](https://img.shields.io/github/v/release/yuuichi-s/OuterTune?include_prereleases)](https://github.com/yuuichi-s/OuterTune/releases)
[![License](https://img.shields.io/github/license/yuuichi-s/OuterTune)](https://www.gnu.org/licenses/gpl-3.0)

Android向け Material 3 YouTube Music クライアント & ローカル音楽プレイヤー

> [!NOTE]
> これは [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) の**個人メンテナンス用フォーク**です。
>
> 上流がYouTube Music機能の開発を停止したため、個人利用を目的として維持しています。
>
> - APKの配布は現在予定していません。


## 上流との差分

[OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) をベースに以下の変更を加えています：

- YouTube Music の複数バグを修正（アルバムトラック未表示、プレイリストクラッシュ、検索結果パースエラー）；YTM サムネイルの解像度を改善
- 歌詞取得の精度向上と表示遅延の改善（LrcLib + キャプショントラック使用）；再生中の操作バーに歌詞切替ボタンを追加
- ボトムナビゲーションを修正：タブタップでルートに直接移動、アクティブタブの再タップで先頭スクロール＆検索バーリセット、ルートごとに検索バーの状態を保持
- オーディオフォーカスを維持するプレイヤー設定を追加
- タブレットUIを復元、プレイヤーの二重オーバーレイを修正、ダイアログをスクロール可能に変更
- ローカルのタグ抽出を TagLib に統一（全フレーバー）、ffMetadataEx は full の FFmpeg オーディオデコーダー用に維持、ローカル楽曲リンクとギャップレス再生を改善
- アルバム画面に表示される楽曲数を修正
- 手動のハイコントラスト切替を廃止し、システムのコントラスト設定を自動検出するように変更（Android 14+）
- 13色のプリセットから選択できるカスタムアクセントカラーオプションを追加（Material You ダイナミックテーマとは排他）
- フォルダー画面を修正：バック操作後に検索バーを復元、デフォルトのソートをトラック番号昇順に修正、ソート変更後にリストが更新されない問題を修正
- スライダースタイルを選択可能に追加（デフォルト：アニメーション付きスクイグリー）
- YouTube Music の再生を妨げる「Source error 2004」を解消
- Kotlin・KSP・NewPipeExtractor・Ktor・AGP・Gradle を更新

## 機能

OuterTune は [InnerTune](https://github.com/z-huang/InnerTune) を強化したフォークです。ローカル音楽プレイヤーと YouTube Music クライアントの両機能を備えています。

- YouTube Music クライアント機能
    * 楽曲のダウンロード（オフライン再生）
    * 広告なし・バックグラウンド再生によるシームレスな再生
    * アカウント同期
        + アプリからリモートアカウントへのプレイリスト完全同期は現在一時的に利用不可
- ローカル音声ファイルの再生（MP3、OGG、FLACなど）
    * ローカル楽曲と YouTube Music の楽曲を同時に再生可能
    * MediaStore の壊れたメタデータ抽出器の代わりにカスタムタグ抽出器を使用（`\` 区切りのタグなども正しく表示）
- スタイリッシュな Material 3 デザイン
- 複数キュー
- 同期歌詞、および単語単位・カラオケ形式の歌詞に対応（LRC、TTMLなど）
- 音量正規化、テンポ・ピッチ調整、その他各種オーディオエフェクト
- Android Auto 対応
- Android 8（Oreo）以降をサポート

> [!NOTE]
> Android 8（Oreo）以降をサポートしています。Android 7.x（Nougat）でも動作する可能性はありますが、正式サポート対象外です。

## スクリーンショット

[![メインプレイヤー画面](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)

[![プレイヤー画面](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)

[![YouTube Music との同期](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)

[全画像ギャラリー](https://github.com/yuuichi-s/OuterTune/tree/dev/assets/gallery)

> [!WARNING]
> YouTube Music が利用できない地域では、プロキシまたは VPN を使用しない限りこのアプリは使用できません。

## ビルド & コントリビュート

ご自身でビルドしたい方は[ビルドおよびコントリビュートに関するノート](CONTRIBUTING.md)をご覧ください。

### 翻訳の投稿

OuterTune の翻訳には Weblate を使用しています。詳細や翻訳の投稿は[Weblate ページ](https://hosted.weblate.org/projects/yuuichi-s-outertune/)をご覧ください。

[![翻訳ステータス](https://hosted.weblate.org/widget/yuuichi-s-outertune/multi-auto.svg)](https://hosted.weblate.org/projects/yuuichi-s-outertune/)

世界中の方々に OuterTune をお届けするためにご協力いただきありがとうございます。

## ヘルプ & サポート

- **このフォーク固有のバグ**については、[このリポジトリの Issue](https://github.com/yuuichi-s/OuterTune/issues) を作成してください。

## クレジット

すべてのコントリビューターに感謝します。[こちら](https://github.com/OuterTune/OuterTune/graphs/contributors)からご確認いただけます。

このフォークの素晴らしいベースを提供してくださった [z-huang/InnerTune](https://github.com/z-huang/InnerTune) なしには実現できませんでした。

ローカル音楽プレイヤーの理想的な体験のインスピレーションをくれた [Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet)。

精神的サポートと伝説の歌詞パーサーを提供してくれた [Gramophone](https://github.com/FoedusProgramme/Gramophone)。

## 免責事項

本プロジェクトおよびその内容は、YouTube、Google LLC またはその関連会社・子会社と一切関係なく、資金提供、承認、推薦も受けていません。

本プロジェクトで使用されている商標、サービスマーク、商号、その他の知的財産権はそれぞれの権利者に帰属します。
