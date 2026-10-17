# Sound Stop Timer

USB Audio Class入力またはBluetoothマイクの入力音量が設定したしきい値を超えるとラップを記録し、メインのストップウォッチは継続するAndroidアプリです。音声データの保存や送信は行いません。

## 必要な環境

- Android StudioとAndroid SDK Platform 36
- JDK 17以上
- Android 12（API 31）以上の端末
- USB Audio Class入力のUSBレシーバー、またはマイク入力対応Bluetoothヘッドセット

## 起動

プロジェクトをAndroid Studioで開き、Gradle同期後に実機へ実行します。USBレシーバーを接続するか、Bluetoothヘッドセットを端末の設定から接続し、画面の入力機器メニューで使用するマイクを選んでください。USB入力にはマイク権限、Bluetooth入力にはマイクとBluetoothの権限が必要です。計測中はアプリを前面に置いてください。画面消灯を防ぎ、アプリを離れると計測を停止します。

## 動作

USB入力はAndroidがUSBオーディオ入力として認識した機器を`AudioRecord`へ直接指定します。USB入力は48 kHz、44.1 kHz、16 kHzの順に利用可能な形式を試し、Bluetooth入力は通話用オーディオ経路を選択します。音量しきい値は -90〜0 dBFS、閾値を超え続ける時間は50〜1000 ms（50 ms刻み）で調整できます。音声は約20 msごとに解析します。閾値を下げる、または停止時間を短くすると検知しやすくなります。音声検知ではしきい値以上の音が続いた区間を1ラップとして記録し、メインの計測はラップ件数が上限に達するまで継続します。音がいったんしきい値を下回ると次のラップ検知が有効になります。計測中は「ラップ」ボタンでも区間時間と累計時間を記録できます。ラップ件数の上限は停止中に1〜10件で設定でき、上限に達するとメインの計測と音声監視を停止します。Androidが入力デバイスとして認識しないUSB Bluetoothアダプターには対応しません。計測値はデバイスやマイクの自動音量調整に左右されるため、実機で調整してください。

## 開始音

開始音には kiefac 作成の「Logarithm-out square-wave beep」（0.8秒、500Hz）を使用しています。Freesound の [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) 素材です。作者の掲載ページ: https://freesound.org/people/kiefac/sounds/347218/