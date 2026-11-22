# TikLBot para Windows

Aplicación de escritorio independiente basada en Kotlin/JVM y Compose Desktop. Todo el código de esta adaptación vive dentro de `Windows/`; no sustituye ni modifica el módulo Android `app/`.

## Estado de adaptación

- Interfaz de escritorio con navegación Home, Chat en vivo, DJ Music, Voces, Automatizaciones y Settings.
- Transporte TikTok LIVE JVM compartido, con parser protobuf, heartbeat, ACK, reconexión y cola serial de eventos.
- Chat de comentarios, filtros compartidos, pipeline de lectura y cola de voz con prioridad para comentarios.
- Motores Edge TTS, TikTok TTS y TikFinity adaptados a audio Java Sound para Windows.
- Motor Piper local con runtime JNI Windows x64, catálogo de voces, descarga bajo demanda, validación de modelos/configuración, síntesis por fragmentos y selección aleatoria entre voces instaladas.
- Preferencias de Windows persistentes, incluyendo filtros, alertas, plantillas y comandos personalizados.
- Historial local SQLite con escritura asíncrona y cola acotada.
- Perfil público de TikTok: consulta independiente del avatar y del estado LIVE.
- Puente WebSocket local para integraciones, limitado a `127.0.0.1:8765`.
- Panel de automatizaciones para follows, regalos, compartidos, entradas, likes, bienvenidas y comandos locales de moderadores.
- Panel DJ Music integrado con búsqueda YouTube, miniaturas, datos del artista, reproducción, volumen, silencio, cola, reordenamiento, reproducción inmediata y solicitudes `!play` con permisos de seguidores/moderadores.
- Historial SQLite y puente WebSocket local ya conectados al ciclo de vida y a los eventos de la interfaz.
- Actualizador PC independiente: consulta la Release compartida, descarga el EXE y verifica SHA-256 antes de iniciar el instalador.
- Workflow de Windows que ejecuta pruebas, genera el icono ICO desde el SVG, compila MSI, lo envuelve en EXE y prepara el adjunto para la misma Release de Android.

## Pendiente antes de declarar la conversión completa

- Implementar una recuperación de `ttwid` en Windows equivalente en fiabilidad a la lectura de cookies HttpOnly del WebView Android. La consulta HTTP actual se mantiene como primera opción; no se simula un fallback si el navegador no puede leer la cookie.
- Probar los cuatro motores de voz en un equipo Windows real, incluyendo latencia, cancelación y salida de audio.
- Probar la conexión real a TikTok LIVE, DJ Music, audio, instalación limpia y actualización de una versión anterior en un equipo Windows.
- Verificar en Windows real el icono nativo generado desde el SVG y el funcionamiento del actualizador con UAC.

El runtime Piper JNI incluye componentes nativos GPL-3.0; consulta `THIRD_PARTY_NOTICES.md` y los textos de licencia incluidos en los recursos. Los modelos se descargan por separado y conservan sus propias condiciones de uso.

No se debe considerar funcional una característica pendiente solo porque su interfaz aparezca en la aplicación. La conexión LIVE, la reproducción y el actualizador requieren pruebas reales en Windows antes de publicar.

## Compilar localmente (Windows)

Requisitos: JDK 17, Gradle 8.11.1, WiX Toolset 3 y Inno Setup 6.

```powershell
gradle -p Windows test packageMsi
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" "Windows/installer/TikLBot.iss"
```

El instalador EXE se genera en `Windows/build/installer/TikLBot-Windows-Setup.exe`.

## Actualizaciones

El cliente PC consulta la Release `latest` y solo ofrece actualización cuando encuentra `WindowsVersionName`, el instalador EXE y su SHA-256. El archivo descargado se verifica antes de ejecutar el instalador. Una descarga o validación fallida no debe borrar una descarga verificada anterior. La publicación PC se adjunta a la misma Release después de que la publicación Android de Main termina correctamente.
