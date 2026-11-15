# Firma permanente de TikLBot para APK de producción

La APK de producción se firma con un keystore privado permanente. No subas el archivo JKS, su contenido Base64 ni sus contraseñas al repositorio.

## Secretos requeridos en GitHub

En el repositorio pagadbner/TikLBot, abre Settings → Secrets and variables → Actions → New repository secret y crea estos cuatro secretos:

| Nombre | Valor |
|---|---|
| ANDROID_KEYSTORE_BASE64 | Contenido completo del archivo privado keystore-base64.txt |
| ANDROID_KEYSTORE_PASSWORD | Valor KEYSTORE_PASSWORD de guardar-credenciales.txt |
| ANDROID_KEY_ALIAS | tiklbot-release |
| ANDROID_KEY_PASSWORD | Valor KEY_PASSWORD de guardar-credenciales.txt |

No agregues comillas ni espacios extra. No publiques los valores en Issues, Pull Requests, comentarios o archivos del repositorio.

## Flujo de publicación

1. Un cambio llega a main.
2. El workflow Android APK ejecuta pruebas y genera una APK Release firmada.
3. Solo si ese workflow termina en verde, Publish latest TikLBot release descarga el artefacto de esa ejecución.
4. Verifica el SHA-256 y crea o reemplaza los dos archivos de la única Release con etiqueta latest.
5. La app consulta la Release pública, compara VersionCode, descarga la APK, verifica SHA-256 y abre el instalador de Android mediante un URI privado de FileProvider.

## Importante sobre la firma

Todas las futuras versiones deben usar exactamente este keystore, alias y contraseñas. Perder o reemplazar el keystore impedirá que Android acepte las APK posteriores como actualizaciones de esta instalación.

La instalación conserva los datos privados cuando Android acepta la APK como actualización de la misma app (mismo applicationId y misma firma). Android siempre muestra su confirmación de instalación; una app normal no puede instalar actualizaciones silenciosamente.

La APK histórica 0.13.3 se compiló como assembleDebug, no como una Release firmada con este keystore nuevo. Por ello, no se debe asumir que Android permitirá instalar la primera APK Release encima de esa instalación. Antes de distribuir la primera Release hay que comprobar la firma del APK actualmente instalado y planificar una transición si su certificado no coincide. No desinstales la app antigua hasta tener resuelto cómo conservar los datos que necesites.
