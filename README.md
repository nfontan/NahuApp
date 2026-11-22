# NahuApp

Aplicación Android TV que carga el sitio **StreamXHD** (hoy `streamxhd.sbs`) como WebView, permitiendo ver eventos deportivos en vivo directamente desde el televisor.

## Características

- **Reproducción a pantalla completa** — Al presionar "Ver aquí", el stream se abre en una Activity separada con autoplay y sonido
- **Cambio de servidor** — Dentro del reproductor, ▲/▼ (o CH+/CH-) pasan a otro servidor del mismo partido
- **Aviso de servidor caído** — Si un servidor no arranca en 20 s, la app sugiere cambiar de servidor
- **Dominios automáticos** — Recuerda el último dominio que funcionó, lee los dominios oficiales de streamveri.xyz y prueba los de respaldo si uno cae
- **Bloqueo de publicidad** — Bloquea scripts de anuncios, popups y redirecciones que reemplazaban el reproductor
- **Actualización automática (OTA)** — Al abrir, avisa si hay una versión nueva y la instala
- **Interfaz limpia** — Sin barra de título, keepScreenOn activado

## Control remoto en el reproductor

| Tecla | Acción |
|---|---|
| OK / Play-Pausa | Reproducir / pausar |
| ◀ / ▶ | Retroceder / adelantar 15 s |
| ▲ / ▼ (CH- / CH+) | Servidor anterior / siguiente |
| Info / Menú | Ver qué servidor se está usando |
| Atrás | Volver a la agenda |

## Requisitos

- Android TV 5.0+ (API 21)
- Conexión a Internet

## Instalación

### Desde Downloader
1. Abrí **Downloader** en tu TV
2. Ingresá la URL del APK
3. Instalalo

### Desde USB
1. Copiá `releases/NahuApp.apk` a un pendrive
2. Conectalo al TV
3. Abrí el archivo con un gestor de archivos

### Desde ADB
```bash
adb connect <IP_DE_TU_TV>
adb install releases/NahuApp.apk
```

## Compilar desde código

```bash
export JAVA_HOME=/ruta/a/jdk17
export ANDROID_HOME=/ruta/al/android-sdk
./gradlew assembleRelease
```

El APK firmado se genera en `app/build/outputs/apk/release/`.

## Estructura del proyecto

```
├── app/
│   ├── src/main/
│   │   ├── java/com/streamxhd/tv/
│   │   │   ├── MainActivity.kt      # Agenda en WebView, dominios, OTA
│   │   │   ├── PlayerActivity.kt    # Reproductor fullscreen y control remoto
│   │   │   └── WebGuards.kt         # Bloqueo de ads, dominios, user agent
│   │   ├── res/
│   │   │   ├── drawable/            # Icono del escudo de Racing Club
│   │   │   ├── layout/              # Layouts de las Activities
│   │   │   └── values/              # Strings, colores, temas
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── releases/
│   └── NahuApp.apk                  # APK precompilado
├── build.gradle.kts
└── settings.gradle.kts
```

## Tecnologías

- **Kotlin** — Lenguaje principal
- **Android WebView** — Visualización de la web
- **AndroidX AppCompat** — Compatibilidad con versiones anteriores
- **Gradle 8.5 + AGP 8.2.2** — Sistema de build

## Licencia

Uso personal.
