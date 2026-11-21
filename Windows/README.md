# TikLBot para Windows

Proyecto de escritorio independiente basado en Kotlin/JVM y Compose Desktop. Esta carpeta no modifica el módulo Android `app/`.

## Estado de adaptación

- Shell de escritorio y navegación inicial.
- Transporte JVM de TikTok LIVE compartido desde Android: conector WebSocket, parser de eventos y cola serial de eventos.
- Chat de comentarios en tiempo real en la pantalla Home.
- Actualizador PC separado, con descarga y validación SHA-256 del instalador EXE.
- Empaquetado MSI y envoltorio instalador EXE.

La adaptación funcional completa continúa por etapas: motores de voz/audio, persistencia, automatizaciones y DJ Music/reproductor de escritorio todavía requieren su integración y pruebas propias. No deben considerarse implementados hasta que sus pruebas pasen.

## Compilar localmente (Windows)

Requisitos: JDK 17, Gradle 8.11.1, WiX Toolset 3 y Inno Setup 6.

```powershell
gradle -p Windows test packageMsi
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" "Windows/installer/TikLBot.iss"
```

El instalador EXE final se genera en `Windows/build/installer/TikLBot-Windows-Setup.exe`.

## Actualizaciones

El cliente PC consulta la Release `latest` y solo ofrece actualización cuando encuentra `WindowsVersionName`, el instalador EXE y su SHA-256. La actualización se verifica antes de ejecutar el instalador. La publicación PC se adjunta a la misma Release después de que la publicación Android de Main termina correctamente.
