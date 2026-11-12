# TikLBot — Contexto general del proyecto

> Documento de referencia del proyecto. Última actualización: 12 de noviembre de 2026.
> Repositorio: https://github.com/pagadbner/TikLBot

## Objetivo
TikLBot es una aplicación Android nativa para leer comentarios públicos de TikTok LIVE mediante voz Piper en español mexicano. La interfaz, el servicio, el transporte Android, los filtros, la cola, Piper y el puente local están dentro de la misma APK. No requiere Render ni un servidor de aplicación externo.

## Arquitectura
- Kotlin + Jetpack Compose para la interfaz.
- Android Foreground Service para ejecución en segundo plano.
- OkHttp para HTTPS y WebSocket Android-compatible hacia TikTok.
- Decodificador protobuf/gzip local para mensajes Webcast.
- Servidor WebSocket local en 127.0.0.1:8765, integrado en el proceso Android.
- Piper TTS local para voz española de México.
- Filtros, cola FIFO y procesamiento de comentarios dentro de la aplicación.

## Conexión TikTok LIVE
TikTokLiveJava fue retirada porque usa java.net.http.HttpClient, API que no está disponible en Android estándar. El conector actual usa OkHttp: consulta la sala pública por nombre de usuario, obtiene la cookie anónima ttwid, abre el WebSocket Webcast, envía entrada a la sala y heartbeats, confirma paquetes y decodifica protobuf/gzip para extraer comentarios.

No solicita contraseña, cookies de sesión de usuario ni tokens privados. No utiliza un servicio de firma externo. La integración depende del protocolo interno no oficial Webcast de TikTok, que puede cambiar y debe validarse con un LIVE público real.

## Servidor local integrado
LocalBridgeServer.kt escucha únicamente en 127.0.0.1:8765; no expone el puerto a la red Wi-Fi/LAN. El puente sirve para comunicación local y diagnóstico. Es independiente del WebSocket que conecta con TikTok.

## Funciones
- Lectura de comentarios públicos de TikTok LIVE.
- Prefijo con nombre del comentarista.
- Filtros de palabras y usuarios ignorados.
- Supresión de mensajes repetidos.
- Cola FIFO y límite de capacidad.
- Piper TTS local con voz española de México.
- Servicio Android en primer plano y notificación de estado.
- Servidor WebSocket local integrado en la APK.
- Sin credenciales de TikTok ni backend de aplicación remoto.

## Archivos principales
| Ruta | Responsabilidad |
|---|---|
| app/src/main/java/com/myclivebot/MainActivity.kt | Interfaz principal |
| app/src/main/java/com/myclivebot/BotForegroundService.kt | Servicio Android |
| app/src/main/java/com/myclivebot/core/TikTokLiveConnector.kt | Transporte OkHttp y protocolo Webcast |
| app/src/main/java/com/myclivebot/core/LocalBridgeServer.kt | Servidor WebSocket local |
| app/src/main/java/com/myclivebot/core/CommentFilter.kt | Moderación |
| app/src/main/java/com/myclivebot/core/CommentPipeline.kt | Procesamiento |
| app/src/main/java/com/myclivebot/core/CommentQueue.kt | Cola FIFO |
| app/src/main/java/com/myclivebot/core/SpeechQueueWorker.kt | Worker de voz |
| app/src/main/java/com/myclivebot/core/PiperSpeechEngine.kt | Motor Piper |
| .github/workflows/android-apk.yml | Compilación y pruebas |

## Compilación
- JDK 17
- Android SDK / compileSdk 35
- Android NDK 27.0.12077973
- CMake 3.22.1
- Gradle 8.11.1

Comando principal:
gradle testDebugUnitTest assembleDebug --no-daemon --stacktrace

El workflow genera APK debug y checksum SHA-256. La distribución release requiere una clave privada configurada en GitHub Secrets; nunca se debe subir el keystore al repositorio.

## Estado de validación
El cambio elimina la dependencia que causaba el error de resolución de java.net.http.HttpClient y reemplaza el transporte con OkHttp. La compilación de CI valida el código, pero no sustituye una prueba en un teléfono Android contra un LIVE público real. La conexión final debe confirmarse en un LIVE activo, comprobando handshake, recepción de comentarios, reconexión y finalización del directo.

## Plan futuro
1. Validar conexión real en Android con un LIVE público.
2. Añadir pruebas instrumentadas para handshake, gzip, protobuf, reconexión y LIVE terminado.
3. Añadir contadores de comentarios recibidos, filtrados y leídos.
4. Mejorar persistencia de configuración y controles de pausa/reanudación.
5. Mantener actualizado el protocolo si TikTok modifica Webcast.
6. Preparar firma release y GitHub Releases.

## Reglas para contribuciones
1. Leer la versión actual de cada archivo antes de modificarlo.
2. No declarar una función terminada solo porque compila.
3. Mantener el servidor local en loopback y no introducir servicios remotos como requisito.
4. No solicitar ni almacenar credenciales de TikTok.
5. Ejecutar pruebas y workflow después de cada cambio.
6. Actualizar este documento cuando cambie la arquitectura o el estado de validación.
