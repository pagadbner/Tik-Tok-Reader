# TikLBot — TikTok LIVE Control Center for Android

Aplicación Android nativa (Kotlin + Jetpack Compose) para leer comentarios públicos de TikTok LIVE mediante síntesis neuronal Piper ejecutada localmente en el teléfono.

> Estado del proyecto: versión de desarrollo 0.11.0. El conector usa el protocolo Webcast comunitario de TikTok; no es una API oficial y puede verse afectado por cambios o límites del servicio.

## Funciones disponibles en 0.10.0

### Conexión y servicio
- Conexión a un LIVE público por nombre de usuario.
- Servicio Android en primer plano con notificación persistente.
- Servidor WebSocket integrado en el proceso de la app, disponible únicamente en `127.0.0.1:8765`.
- Captura de `ttwid` tanto en la respuesta final como en las redirecciones HTTP; caché temporal y diagnóstico de respuestas sin cookie.
- Recuperación alternativa mediante WebView Android invisible y no adjunto a la interfaz: no abre ventanas ni muestra un navegador. Si TikTok requiere consentimiento/verificación interactiva, informa del fallo y no intenta omitirla.
- La cookie anónima se valida antes de entregarla al mismo transporte WebSocket.
- El puente local no se expone a la red Wi-Fi ni a Internet.

### Lectura, moderación y eventos
- Lectura de comentarios en español con filtros de palabras y usuarios ignorados.
- Limpieza del nombre hablado: conserva solo letras Unicode y espacios; elimina emojis, números y símbolos. Si no queda ninguna letra, lee solo el comentario.
- Lectura opcional del nombre, emojis y símbolos.
- Filtro local para leer solo comentarios cuyo estado de seguidor venga confirmado por TikTok.
- Avisos de nuevos seguidores y regalos, si el evento llega desde Webcast.
- Cambios de voz, velocidad, volumen y filtros de lectura sin reconectar el LIVE.
- Comandos personalizados configurables como `!redes`, `!discord`, `!youtube` y `!horario`; sus respuestas se reproducen localmente mediante Piper.
- Bienvenida opcional al primer comentario aceptado de cada usuario durante la conexión.
- Alertas de regalos con umbral configurable por contador de repetición y plantilla personalizable.
- Voces aleatorias entre modelos Piper que ya estén instalados.

### Automatizaciones locales 0.10.0

Los comandos se configuran en la app, una regla por línea, con el formato:

```text
!redes=Mis redes están en mi perfil.
!discord=Únete a mi comunidad de Discord.
!youtube=Encuentra mis videos en YouTube.
!horario=Consulta mi horario en el perfil.
```

- El comando se reconoce cuando aparece como primer token de un comentario, sin distinguir mayúsculas/minúsculas.
- Se puede usar `{user}` para insertar el nombre limpio del autor y `{command}` para repetir el comando.
- La respuesta se sintetiza solo en el dispositivo. **No se envía un mensaje al chat de TikTok**: el conector actual no implementa una vía oficial/verificada para publicar mensajes.
- La bienvenida es opcional y se emite una sola vez por usuario y conexión cuando llega su primer comentario aceptado. No representa una detección del evento de entrada a la sala.
- Los regalos pueden filtrarse por número mínimo de repeticiones. La plantilla admite `{user}` y `{count}`. El evento que actualmente interpreta el conector expone el contador de repetición; no se anuncian nombres de regalos ni valores de diamantes que no estén disponibles/validados.

### Voces
- Síntesis Piper sin enviar el texto del comentario a un servicio TTS remoto.
- Catálogo descargable de voces Piper en español de México, España y Argentina.
- Nueva voz Cortana de Halo en español latino (variante pública de HirCoir; no es el checkpoint CE-Legacy exacto del enlace compartido).
- Nueva voz Jarvis en inglés estadounidense; requiere descargar aproximadamente 114 MB y leerá los comentarios con pronunciación inglesa.
- La selección aleatoria de voces se limita al idioma de la voz seleccionada para no alternar accidentalmente entre español e inglés.
- Los modelos se descargan bajo demanda y se guardan en el almacenamiento privado de la app.
- Los modelos comunitarios son experimentales; comprueba la licencia de cada modelo antes de redistribuirlo o usarlo comercialmente. Las fichas de las variantes Cortana pública y Jarvis no muestran una licencia de redistribución clara.

### Cola y telemetría local
- Cola de voz acotada para comentarios; ante saturación, descarta el comentario pendiente más antiguo para priorizar mensajes recientes, sin cortar la frase que ya se reproduce.
- Alertas prioritarias conservan su carril independiente y no se descartan para hacer espacio a comentarios.
- Carril prioritario separado para anuncios de seguidores y regalos: se reproducen antes de los comentarios pendientes, sin interrumpir la frase que ya está sonando.
- Panel de comentarios LIVE dentro de la aplicación, actualizado en tiempo real y limitado a los 60 mensajes más recientes.
- El puente local emite cada comentario como `live_comment`, además de las estadísticas.
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
- El modo de solo seguidores prioriza `User.is_follower` (campo 1029), utiliza `User.follow_status` (campo 1024) como respaldo y admite el esquema anidado `follow_info.follow_status` (campo 22 / subcampo 3). Si TikTok no incluye esa metadata, el estado permanece desconocido y el comentario se descarta en ese modo.
- Los avisos de regalos y seguidores dependen de que el evento correspondiente llegue y pueda interpretarse.
- La cola prioritaria no interrumpe una frase que ya se está reproduciendo.
- La síntesis es local después de descargar el modelo; la primera descarga requiere Internet.
- El servidor WebSocket está limitado a loopback. No debe exponerse a la LAN sin autenticación, control de acceso y una revisión de seguridad.
- No se ha validado el comportamiento en todos los modelos de Android ni en una transmisión real después de cada cambio; las pruebas de CI no sustituyen las pruebas en dispositivo.

## Cambios 0.11.0\n\n- Recuperación de `ttwid` por HTTP y, si falla, mediante un WebView invisible en segundo plano. No se abre Activity ni ventana de navegador.\n- Decodificación del estado de seguimiento desde `follow_info.follow_status`, con pruebas para estados positivo, negativo y ausente.\n- Política de cola de baja latencia: al llenarse, se elimina el comentario pendiente más antiguo y se conserva el audio en reproducción.\n\n## Hoja de ruta

El modelo CE-Legacy exacto compartido para Cortana requiere aceptar acceso en Hugging Face; se ofrece una variante pública distinta del mismo creador.

El desarrollo se dividirá en etapas pequeñas para proteger la conexión y el servidor local. La IP pública no se falsifica ni se rota desde Android; el cliente mantiene un User-Agent coherente y usa reintentos moderados, cookies y diagnósticos sin intentar evadir controles de TikTok:

| Etapa | Objetivo |
|---|---|
| 0.9.x | Panel LIVE, limpieza de nombres, resiliencia `ttwid` y voces adicionales Cortana/Jarvis. |
| 0.10.0 | Comandos locales configurables, bienvenida al primer comentario por usuario y alertas de regalos con umbral/plantilla por repetición. |\n| 0.11.0 | Recuperación oculta de cookies, cola de baja latencia y corrección de metadatos de seguidores. |
| 0.11.x | Favoritos, usuarios prioritarios, antispam ampliado y controles de moderación basados solo en metadatos disponibles. |
| 0.12.x | Historial local ampliado, estadísticas y exportación CSV. |
| 0.13.x | API local documentada e integración con OBS; panel remoto solo tras añadir autenticación y protección de red. |
| 1.0.0 | Rediseño del centro de control, perfiles/escenas, copia de seguridad y arquitectura de extensiones segura. |

Cada etapa debe conservar el funcionamiento offline de Piper, mantener bajo el consumo de memoria, evitar almacenar datos de sesión innecesarios y pasar el workflow de Android antes de considerarse terminada.

## Licencias

Piper Kotlin y sus componentes nativos conservan sus propias licencias. Los modelos de voz pueden tener licencias distintas entre sí. Revisa y conserva los avisos de cada dependencia y modelo antes de redistribuir el APK.
