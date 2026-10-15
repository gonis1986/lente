# Lens Screen Translator 🔍🌐

App Android assistente autonoma per la ricerca, riconoscimento testo OCR e traduzione in tempo reale degli elementi a schermo, in stile Google Lens / Circle to Search.

## Caratteristiche principali
- **OCR su schermo in locale**: Riconoscimento testo e caratteri tramite Google ML Kit Text Recognition (incluso supporto specifico per caratteri giapponesi).
- **Traduzione istantanea**: Traduzione del testo identificato su schermo tramite Google ML Kit Translate.
- **Modalità Assistente (VoiceInteractionService)**: Invocabile come assistente predefinito di sistema (es. pressione prolungata del tasto Home o gesture di navigazione).
- **Modalità Accessibilità (AccessibilityService)**: Overlay trasparente per catturare la schermata e selezionare porzioni di testo.

## Requisiti
- Android SDK 30+ (Android 11 o superiore)
- Java 11 / Kotlin 2.0
