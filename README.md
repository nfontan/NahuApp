# NahuApp

Aplicación Android TV que carga **stream-xhd.com** como WebView, permitiendo ver eventos deportivos en vivo directamente desde el televisor.

## Características

- **WebView optimizado** — JavaScript, DOM storage, mixed content habilitados
- **Reproducción a pantalla completa** — Al presionar "Ver aquí", el stream se abre en una Activity separada, evitando problemas con iframes embebidos
- **Control remoto** — Navegación con D-Pad, botón Atrás para volver, doble clic en Atrás para salir
- **Pantalla completa nativa** — Soporte para HTML5 video fullscreen vía `onShowCustomView`
- **Interfaz limpia** — Sin barra de título, keepScreenOn activado
- **User-Agent Android TV** — El sitio se ve correctamente en televisores

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
│   │   │   ├── MainActivity.kt      # Activity principal con WebView
│   │   │   └── PlayerActivity.kt    # Activity de reproducción fullscreen
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
