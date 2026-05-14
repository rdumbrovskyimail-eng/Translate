# Translator — Voice Translator RU ⇄ DE

Real-time голосовой переводчик русский ⇄ немецкий на базе **Gemini 3.1 Flash Live Preview** (Google Generative Language API, WebSocket Bidi).

## ⚡ Особенности

- 🎙 Real-time голосовой стриминг: capture 16 kHz · playback 24 kHz · PCM 16-bit
- 🤖 Gemini 3.1 Flash Live (`gemini-3.1-flash-live-preview`) с `thinkingLevel`
- 🔊 AEC + NoiseSuppressor + AGC + soft-tanh clipping
- 📡 Auto-reconnect: WS goAway + sessionResumption + network-monitor
- 💾 Шифрование настроек: AES-256-GCM, Android Keystore (StrongBox → TEE)
- 🎨 3 темы: Obsidian, Sakura, Gem — с живой интерполяцией всех цветов
- 🔒 API-ключ хранится локально, шифрованно, никуда кроме Google не уходит

## 📱 Требования

| Компонент | Версия |
| --------- | ------ |
| Android | 8.0+ (minSdk 26) |
| compileSdk / targetSdk | 36 |
| Kotlin | 2.3.20 |
| AGP | 9.1.0 |
| Gradle | 9.4.1 |
| Compose BOM | 2025.10.01 |

## 🚀 Запуск

1. Получи Gemini API key в [Google AI Studio](https://aistudio.google.com/app/apikey).
2. Открой приложение, введи ключ на стартовом экране.
3. Дай разрешение на микрофон и нотификации.
4. Говори — перевод произносится автоматически.

## 🔧 Локальная сборка

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/
🎤 Audio формат
Поток
Hz
Каналы
Формат
Capture
16 000
Mono
PCM 16-bit LE
Playback
24 000
Mono
PCM 16-bit LE
🔐 Безопасность
API-ключ и все настройки шифруются AES-256-GCM на ключе из Android Keystore. Ключ генерируется один раз, никогда не покидает Secure Enclave (StrongBox если доступен, иначе TEE/ARM TrustZone).
📋 Лицензия
Личное использование.