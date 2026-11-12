# TikLBot — Contexto general del proyecto

> Documento de referencia del proyecto. Última actualización: 12 de noviembre de 2026.
> Repositorio: https://github.com/pagadbner/TikLBot

## 1. Objetivo
TikLBot es una aplicación Android nativa diseñada para escuchar comentarios públicos de TikTok LIVE y convertirlos en voz hablada en el propio teléfono. La meta es que el creador pueda dejar la aplicación trabajando mientras transmite, moderar qué comentarios se leen y escuchar una voz neuronal en español mexicano sin depender de un servicio TTS remoto.

La aplicación debe ser autónoma: interfaz, servicio en segundo plano, filtros, cola de lectura, motor Piper y puente local viven dentro de la misma APK. No se contempla Render, un servidor externo ni Discord Voice.

## 2. Experiencia prevista
1. El usuario instala y abre la APK.
2. Descarga una sola vez el modelo de voz Piper español de México.
3. Escribe el nombre público del creador que está transmitiendo.
4. Configura si se pronuncia el nombre del comentarista, palabras bloqueadas, usuarios ignorados y capacidad de la cola.
5. Pulsa **Iniciar**.
6. El servicio Android mantiene la sesión y procesa los comentarios recibidos.
7. Cada comentario aceptado pasa a la cola FIFO y Piper lo reproduce por el altavoz.
8. El usuario puede detener el lector desde la aplicación.

No se deben solicitar contraseña, cookies ni token de sesión de TikTok para leer un chat público.

## 3. Tecnologías
- **Kotlin:** lenguaje principal de Android.
- **Jetpack Compose / Material 3:** interfaz nativa.
- **Android Foreground Service:** ejecución persistente con notificación.
- **Kotlin Coroutines:** operaciones asíncronas.
- **DataStore:** dependencia incluida para preferencias.
- **Piper TTS:** síntesis neuronal local; modelo previsto es_MX-ald-medium.
- **TikTokLiveJava:** conector comunitario de Webcast integrado inicialmente.
- **Java-WebSocket:** biblioteca usada por el puente local.
- **GitHub Actions / Gradle:** pruebas y compilación automática del APK.

## 4. Arquitectura y archivos principales
| Ruta | Responsabilidad |
|---|---|
| app/src/main/java/com/myclivebot/MainActivity.kt | Pantalla principal y controles. |
| app/src/main/java/com/myclivebot/BotForegroundService.kt | Servicio Android que coordina conexión, filtros, voz y notificaciones. |
| app/src/main/java/com/myclivebot/core/TikTokLiveConnector.kt | Adaptador del cliente comunitario de TikTok LIVE. |
| app/src/main/java/com/myclivebot/core/LocalBridgeServer.kt | Servidor WebSocket local enlazado a loopback. |
| app/src/main/java/com/myclivebot/core/CommentFilter.kt | Reglas de moderación y antirrepetición. |
| app/src/main/java/com/myclivebot/core/CommentPipeline.kt | Paso de comentarios aceptados hacia la cola. |
| app/src/main/java/com/myclivebot/core/CommentQueue.kt | Cola FIFO de comentarios. |
| app/src/main/java/com/myclivebot/core/SpeechQueueWorker.kt | Consumidor de la cola y coordinación de lectura. |
| app/src/main/java/com/myclivebot/core/PiperSpeechEngine.kt | Preparación y ejecución de la voz Piper. |
| app/src/main/java/com/myclivebot/core/LiveComment.kt | Modelo de comentario recibido. |
| app/src/test/java/com/myclivebot/core/CommentFilterTest.kt | Pruebas unitarias del filtro. |
| scripts/setup-piper.sh | Preparación local del motor Piper y dependencias. |
| scripts/prepare-piper-assets.sh | Preparación de recursos Piper para CI. |
| .github/workflows/android-apk.yml | Automatización de pruebas, compilación y artefactos APK. |
| icon/ | Diseño vectorial maestro e instrucciones de identidad visual. |

## 5. Funciones presentes en el código
- Interfaz Android con Jetpack Compose y estilo oscuro tipo Glassmorphism, acentos morado/cian y tarjetas translúcidas.
- Campo para el nombre público del creador.
- Opción para leer el nombre antes del comentario.
- Lista de palabras bloqueadas y lista de usuarios ignorados.
- Filtro antirrepetición.
- Límite configurable de longitud y capacidad de cola.
- Cola FIFO con un consumidor de voz.
- Piper preparado para voz española de México y síntesis local después de descargar el modelo.
- Servicio Android en primer plano con notificación de estado.
- Puente WebSocket local para diagnóstico y mensajes de estado.

## 6. Puente local
El puente definido en LocalBridgeServer.kt utiliza WebSocket en 127.0.0.1:8765. La dirección loopback evita exponer el puerto a otros dispositivos de la red Wi-Fi. Su propósito es ofrecer una interfaz local de diagnóstico e integración; no es un backend remoto.

**Importante:** la presencia del puente local no corrige automáticamente la incompatibilidad del conector TikTok. El puente y el transporte de TikTok son componentes separados. El puente debe iniciarse y detenerse de forma ordenada con el servicio y sus estados deben reflejar el estado real del servidor.

## 7. Estado técnico conocido y limitaciones
Este documento distingue entre código presente y comportamiento validado:
- La interfaz, filtros, cola, servicio, Piper y clases del puente local existen en el repositorio.
- La integración de TikTokLiveJava tiene un bloqueo conocido en Android: Failed resolution of: Ljava/net/http/HttpClient;. La biblioteca usa APIs de Java SE java.net.http que no están disponibles de forma estándar en Android. Por tanto, no debe considerarse validada la conexión real a un LIVE Android hasta sustituir o adaptar el transporte HTTP/WebSocket y probarlo en un dispositivo.
- El puente local no es una solución alternativa a ese bloqueo: solo resuelve comunicación local entre componentes.
- TikTokLiveJava y el protocolo Webcast son comunitarios/no oficiales. TikTok puede cambiar el protocolo, firmas o restricciones.
- La recepción de comentarios debe probarse contra una transmisión pública real. Una compilación correcta por sí sola no demuestra que la conexión funcione.
- La lectura de regalos, eventos especiales y detección fiable de moderadores no se consideran funciones terminadas.
- Android puede limitar procesos en segundo plano según fabricante, batería y permisos.
- La descarga inicial de la voz necesita Internet; la síntesis Piper posterior debe funcionar localmente.
- El nombre de usuario público no equivale a autenticación. No se deben pedir credenciales de la cuenta.

## 8. Compilación
Configuración documentada actualmente:
- JDK 17
- Android SDK / compileSdk 35
- Android NDK 27.0.12077973
- CMake 3.22.1
- Gradle 8.11.1

Comandos de referencia desde la raíz:

    ./scripts/setup-piper.sh
    gradle testDebugUnitTest assembleDebug

El workflow .github/workflows/android-apk.yml prepara las dependencias nativas de Piper, ejecuta pruebas unitarias y genera el APK debug. Los artefactos se consultan en Actions. Los tags v* están contemplados para adjuntar el APK a un GitHub Release.

La APK debug de CI usa una clave de depuración temporal. Para actualizaciones públicas firmadas con identidad estable se debe configurar una clave release mediante GitHub Secrets; nunca se debe guardar un keystore privado en el repositorio.

## 9. Identidad visual e icono
La carpeta icon/ conserva el diseño maestro vectorial de TikLBot. El icono representa un globo de conversación con ondas de audio, sobre una base oscura con acentos violeta y cian. Los recursos Android adaptativos se guardan en app/src/main/res/ para que el icono también aparezca en el launcher del teléfono.

Mantener el SVG maestro y los recursos Android sincronizados cuando se rediseñe la marca.

## 10. Plan de trabajo futuro
### Prioridad crítica — conexión Android real
- Retirar la dependencia directa de APIs Java SE no disponibles en Android.
- Implementar transporte HTTP y WebSocket Android-compatible (por ejemplo, OkHttp) o mantener un fork Android del conector.
- Verificar el protocolo Webcast, encabezados, compresión, protobuf y mecanismo de firma que requiera la versión actual de TikTok.
- Añadir pruebas de conexión, desconexión, reconexión, LIVE terminado y errores.
- Probar recepción de comentarios reales en un teléfono Android.

### Prioridad alta — puente y servicio
- Integrar el puente local como canal real de eventos/diagnóstico, con ciclo de vida ligado al servicio.
- Exponer ping, estado, conexión y métricas de cola sin abrir el puerto a la LAN.
- Asegurar apagado limpio del socket, conector, worker y Piper.
- Evitar estados de conexión falsos y manejar puertos ocupados.

### Prioridad alta — voz y moderación
- Ajustes de volumen, velocidad y pausas de Piper.
- Mejoras para normalizar emojis, enlaces, menciones y textos largos.
- Reglas configurables de spam, repetición y usuarios.
- Controles para pausar/reanudar la lectura y vaciar la cola.
- Mejor gestión de errores y descargas/reanudación del modelo.

### Prioridad media — experiencia de usuario
- Pantalla de actividad reciente y contador de comentarios aceptados/filtrados.
- Indicadores diferenciados: servidor local, conexión TikTok y motor de voz.
- Configuración persistente.
- Accesibilidad, tamaños de letra y soporte de tema.
- Icono, splash screen y nombre/versionado coherentes.

### Prioridad media — calidad y distribución
- Más pruebas unitarias y pruebas instrumentadas Android.
- Validación en varias versiones y fabricantes Android.
- APK release firmada con secretos de CI.
- GitHub Releases con notas de versión, SHA-256 y changelog.
- Documentación de instalación, permisos, solución de problemas y licencias.

## 11. Reglas para futuras contribuciones
1. Trabajar sobre main sin sobrescribir archivos ajenos al cambio solicitado.
2. Antes de editar, leer la versión actual de cada archivo y conservar su funcionalidad.
3. No declarar una función terminada solo porque compila: indicar si fue probada en dispositivo.
4. No añadir servidores externos ni servicios de pago como requisito.
5. No almacenar contraseñas, cookies ni tokens de sesión de TikTok.
6. Mantener Piper local y conservar sus avisos/licencias.
7. Ejecutar pruebas y workflow después de cambios de código; revisar los logs si falla.
8. Actualizar este documento cuando cambie la arquitectura, las dependencias o el estado real del proyecto.

## 12. Licencias y atribuciones
Revisar y conservar las licencias de cada dependencia y recurso antes de redistribuir la APK. El proyecto utiliza o contempla TikTokLiveJava, Piper/piper-kotlin, ONNX Runtime, eSpeak-NG, Sonic y el modelo de voz de Piper. La app no es oficial ni está afiliada a TikTok.

Referencias:
- TikTokLiveJava: https://github.com/jwdeveloper/TikTokLiveJava
- Piper Kotlin: https://github.com/piper-tts-apps/piper-kotlin
- Voces Piper: https://huggingface.co/rhasspy/piper-voices
- GitHub Actions: https://docs.github.com/actions