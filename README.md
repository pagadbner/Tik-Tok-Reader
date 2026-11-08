# TikTok Live Reader — Android 0.5.0

Aplicación Android nativa (Kotlin + Jetpack Compose) que conecta con el chat público de TikTok LIVE y lee comentarios en voz alta por el altavoz del teléfono. **Discord no forma parte del proyecto.**

## Funciones incluidas en el código

- Conexión al LIVE público por nombre de usuario (`@usuario` opcional).
- Eventos de conexión, reconexión, desconexión, error y fin del LIVE mostrados en la notificación persistente.
- Recepción de comentarios `onComment` y envío al pipeline de moderación.
- Lectura FIFO: un solo consumidor de voz, sin interrumpir el comentario actual.
- Lectura opcional del nombre antes del texto.
- Lista de palabras bloqueadas, lista de usuarios ignorados, antirrepetición y límite de longitud.
- Capacidad configurable de cola (5–100 comentarios).
- Piper TTS neuronal local con voz española de México `es_MX-ald-medium`.
- Descarga inicial de la voz desde Hugging Face; después, la síntesis ocurre en el teléfono sin mandar el texto a un servicio TTS remoto.
- Servicio Android en primer plano con notificación persistente.
- GitHub Actions que prepara Piper y sus dependencias nativas, ejecuta pruebas y compila el APK.

## Preparar el proyecto localmente

Requisitos:

- JDK 17
- Android SDK Platform 35 y Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1
- Gradle 8.11.1

Desde la raíz del repositorio:

```bash
./scripts/setup-piper.sh
gradle testDebugUnitTest assembleDebug
```

`setup-piper.sh` clona `piper-kotlin`, inicializa sus submódulos, descarga ONNX Runtime y prepara los datos de ejecución de eSpeak-NG. El motor nativo de Piper se compila como parte del APK mediante Gradle.

## Compilación automática con GitHub Actions

El workflow `.github/workflows/android-apk.yml`:

1. Descarga el repositorio de la app y el motor Piper con sus submódulos.
2. Instala JDK 17, Android SDK 35, NDK 27 y CMake 3.22.1.
3. Prepara ONNX Runtime, Piper, eSpeak-NG y los datos de idioma español.
4. Ejecuta `testDebugUnitTest` y `assembleDebug`.
5. Comprueba que el APK exista, calcula SHA-256 y publica el artefacto.
6. Si se publica un tag `v*`, adjunta el APK al GitHub Release.

Para obtenerlo: sube este proyecto a GitHub, abre **Actions**, ejecuta **Android APK** y descarga el artefacto `TikTok-Live-Reader-Android-...`. El archivo instalable es `app-debug.apk`.

El APK debug se firma con la clave de depuración temporal del runner. Para distribuir actualizaciones firmadas con la misma identidad o publicar en Play Store se debe configurar una clave release propia mediante GitHub Secrets; no se debe subir el keystore al repositorio.

## Primer uso

1. Instala el APK.
2. Abre la aplicación y pulsa **Descargar voz Piper es-MX**. El modelo ocupa aproximadamente 63 MB, más el archivo de configuración. La conexión al LIVE se habilita después de validar la voz.
3. Escribe el nombre de un creador que esté transmitiendo en LIVE.
4. Ajusta lectura de nombre, palabras bloqueadas, usuarios ignorados y tamaño de cola.
5. Pulsa **Iniciar** y permite que Android mantenga la notificación del servicio.
6. Usa **Detener** al terminar.

No se solicita contraseña de TikTok, cookies ni token de sesión para leer el chat público.

## Alcance y limitaciones importantes

- El conector se basa en TikTokLiveJava y el servicio Webcast interno de TikTok. **No es una API oficial**: TikTok puede cambiar el protocolo, bloquear temporalmente peticiones o limitar la conexión. No se puede garantizar que conecte con todos los LIVE ni de forma permanente.
- La app lee comentarios públicos. Los regalos y otros eventos no se convierten en voz en esta versión. El filtro por rol de moderador no está activado porque esta integración no expone una marca fiable de moderador; los filtros de palabras y usuarios sí están conectados.
- Piper puede ser lento en teléfonos de gama baja. La velocidad depende del procesador y la memoria disponibles.
- Android y las capas de fabricantes pueden detener servicios por ahorro de batería. La notificación persistente ayuda, pero no garantiza ejecución 24/7.
- La descarga inicial de la voz requiere Internet; después la síntesis Piper es local.

## Dependencias y licencias

- TikTokLiveJava (`com.github.jwdeveloper.TikTok-Live-Java:Client:1.13.0-Release`): conector comunitario de Webcast; licencia MIT.
- Piper Kotlin y su núcleo nativo: GPL-3.0-or-later; conservar avisos de licencia y código fuente correspondiente al redistribuir.
- Modelo `es_MX-ald-medium`: publicado en `rhasspy/piper-voices`, licencia MIT según la ficha del repositorio. Revisar la ficha del modelo antes de redistribuirlo.
- ONNX Runtime, eSpeak-NG y Sonic conservan sus respectivas licencias.

Fuentes técnicas:
- https://github.com/jwdeveloper/TikTokLiveJava
- https://github.com/piper-tts-apps/piper-kotlin
- https://huggingface.co/rhasspy/piper-voices/tree/main/es/es_MX/ald/medium
