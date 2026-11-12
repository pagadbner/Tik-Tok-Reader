# TikLBot — TikTok LIVE Control Center for Android

Aplicación Android nativa (Kotlin + Jetpack Compose) para leer comentarios públicos de TikTok LIVE mediante síntesis neuronal Piper ejecutada localmente en el teléfono.

> Estado del proyecto: versión de desarrollo 0.8.0. El conector usa el protocolo Webcast comunitario de TikTok; no es una API oficial y puede verse afectado por cambios o límites del servicio.

## Funciones disponibles en 0.8.0

### Conexión y servicio
- Conexión a un LIVE público por nombre de usuario.
- Servicio Android en primer plano con notificación persistente.
- Servidor WebSocket integrado en el proceso de la app, disponible únicamente en `127.0.0.1:8765`.
- Reintentos para obtener la cookie anónima `ttwid` y reutilización en reconexiones.
- El puente local no se expone a la red Wi-Fi ni a Internet.

### Lectura, moderación y eventos
- Lectura de comentarios en español con filtros de palabras y usuarios ignorados.
- Lectura opcional del nombre, emojis y símbolos.
- Filtro local para leer solo comentarios cuyo estado de seguidor venga confirmado por TikTok.
- Avisos de nuevos seguidores y regalos, si el evento llega desde Webcast.
- Cambios de voz, velocidad, volumen y filtros de lectura sin reconectar el LIVE.
- Voces aleatorias entre modelos Piper que ya estén instalados.

### Voces
- Síntesis Piper sin enviar el texto del comentario a un servicio TTS remoto.
- Catálogo descargable de voces en español de México, España y Argentina.
- Los modelos se descargan bajo demanda y se guardan en el almacenamiento privado de la app.
- Los modelos comunitarios son experimentales; comprueba la licencia de cada modelo antes de redistribuirlo o usarlo comercialmente.

### Cola y telemetría local
- Cola de voz acotada para comentarios.
- Carril prioritario separado para anuncios de seguidores y regalos: se reproducen antes de los comentarios pendientes, sin interrumpir la frase que ya está sonando.
- Contadores de comentarios recibidos, filtrados y encolados, eventos recibidos y tamaño de cola.
- El puente local emite un mensaje WebSocket `live_stats` al recibir comentarios o eventos. Ejemplo:

```json
{
  "type": "live_stats",
  "comments_received": 120,
  "comments_filtered": 8,
  "comments_queued": 92,
  "events_received": 4,
  "events_queued": 4,
  "speech_queue_size": 3
}
```

## Compilar

Requisitos:
- JDK 17
- Android SDK Platform 35 y Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1
- Gradle 8.11.1

Desde la raíz:

```bash
./scripts/setup-piper.sh
gradle testDebugUnitTest assembleDebug
```

GitHub Actions ejecuta pruebas unitarias, compila el APK, calcula su SHA-256 y publica el artefacto descargable. Los APK debug de Actions se firman con una clave temporal del runner; no deben considerarse una identidad de firma estable para actualizaciones permanentes.

## Uso

1. Instala el APK.
2. Descarga al menos una voz Piper desde el catálogo.
3. Escribe el usuario del creador que esté transmitiendo.
4. Configura filtros, voz y avisos.
5. Pulsa Iniciar. El LIVE debe estar activo y ser accesible públicamente.
6. Detén el servicio al finalizar.

No se solicita la contraseña de TikTok ni se almacena una cookie de sesión de una cuenta. La cookie anónima `ttwid` se usa para la conexión pública y se mantiene en memoria durante la ejecución.

## Limitaciones conocidas

- La integración Webcast no es oficial. TikTok puede cambiar el protocolo, limitar peticiones o dejar de enviar determinados campos/eventos.
- El estado de seguidor solo se puede filtrar cuando TikTok incluye ese dato; si falta, el modo de solo seguidores descarta el comentario.
- Los avisos de regalos y seguidores dependen de que el evento correspondiente llegue y pueda interpretarse.
- La cola prioritaria no interrumpe una frase que ya se está reproduciendo.
- La síntesis es local después de descargar el modelo; la primera descarga requiere Internet.
- El servidor WebSocket está limitado a loopback. No debe exponerse a la LAN sin autenticación, control de acceso y una revisión de seguridad.
- No se ha validado el comportamiento en todos los modelos de Android ni en una transmisión real después de cada cambio; las pruebas de CI no sustituyen las pruebas en dispositivo.

## Perfiles de voz y escenas (0.9.0 en desarrollo)\n\n- Perfiles DSP locales Natural, Jarvis, Cortana, Meme y DJ sobre el PCM generado por Piper. Son efectos de audio inspirados; no son clones de voces protegidas.\n- Efectos aplicados después de la síntesis, sin enviar audio ni texto a servicios remotos.\n- Escenas rápidas Gaming, Charlando, Música y Memes que aplican perfiles y preferencias de lectura.\n- Selección del perfil y prueba de voz desde la app; los cambios de perfil se envían al servicio activo sin reconectar el LIVE.\n- Pruebas unitarias para salida PCM acotada y fallback de perfiles desconocidos.\n\n## Hoja de ruta

El desarrollo se dividirá en etapas pequeñas para proteger la conexión y el servidor local:

| Etapa | Objetivo |
|---|---|
| 0.9.x | Perfiles de voz Jarvis/Cortana inspirados (sin clonar voces protegidas), voces de memes, efectos DSP y escenas LIVE. |
| 0.10.x | Comandos personalizados, bienvenida, alertas de regalos detalladas y metas configurables. |
| 0.11.x | Favoritos, usuarios prioritarios, antispam ampliado y controles de moderación basados solo en metadatos disponibles. |
| 0.12.x | Panel de sesión, estadísticas, historial local y exportación CSV. |
| 0.13.x | API local documentada e integración con OBS; panel remoto solo tras añadir autenticación y protección de red. |
| 1.0.0 | Rediseño del centro de control, perfiles/escenas, copia de seguridad y arquitectura de extensiones segura. |

Cada etapa debe conservar el funcionamiento offline de Piper, mantener bajo el consumo de memoria, evitar almacenar datos de sesión innecesarios y pasar el workflow de Android antes de considerarse terminada.

## Licencias

Piper Kotlin y sus componentes nativos conservan sus propias licencias. Los modelos de voz pueden tener licencias distintas entre sí. Revisa y conserva los avisos de cada dependencia y modelo antes de redistribuir el APK.
