# Contexto del Proyecto — NahuApp (StreamXHD-TV)

## Que es
App Android nativa (Kotlin) que wrappea el sitio StreamXHD (hoy `streamxhd.sbs`) en un WebView. Diseñada para Android TV. Sin librerías de terceros — solo WebView + HTML5 video.

## Arquitectura
- **WebGuards** (`WebGuards.kt`): reglas compartidas por las dos activities.
  - `AD_BLOCKED_HOSTS` + `isAdHost()` / `blockedResponse()` (respuesta vacía para ads).
  - `isSiteHost()`: cualquier dominio `streamxhd.*` / `streamx-hd.*`.
  - `tvUserAgent()`: UA de Android TV con la versión real de Chrome/WebView del equipo.
  - `normalizePlayerUrl()`: resuelve URLs relativas contra el dominio actual, rechaza esquemas que no sean http(s) y convierte `live1.php` → `live2.php` (calidad automática).
- **MainActivity**: WebView principal.
  - Dominios con respaldo (ver abajo). Valida que la página cargada sea la agenda (`#content` + `#tabs`) antes de inyectar.
  - `INJECTED_JS` (una sola vez por página, guard `window.__nahuInjected`):
    - Fuerza `playbackMode = 'live2'` y clickea el botón `[data-mode="live2"]` si la página arrancó en live1 (instalación nueva).
    - Intercepta clicks en `[data-action="play"]` y manda **todos los servidores del evento** (mismo `data-target`, nombres de `.compact-server-row .compact-server-name`) a `Android.playServers(json)`. `Android.playUrl(url)` queda por compatibilidad.
    - Botón refresh flotante (`#__refresh_btn`), `tabindex="0"` en play/`.tab`, foco inicial en el primer `.tab`.
  - Bloquea popups (`setSupportMultipleWindows(true)` + `onCreateWindow` → false) y navegación fuera de `streamxhd.*` (`shouldOverrideUrlLoading`).
  - OTA (ver abajo). `webView.destroy()` en `onDestroy`.
- **PlayerActivity**: recibe `urls`, `names`, `index` (todos los servidores del evento).
  - Carga un **wrapper HTML con iframe** via `loadDataWithBaseURL(base, ...)`, donde `base` = esquema+host de la URL del player (same-origin con el iframe, así el wrapper accede al documento del player).
  - El iframe envía `Sec-Fetch-Dest: iframe` (check del server) y `window.top !== window.self` pasa el check inline.
  - Script del wrapper: `tryPlay()` (Clappr `window.player.play()` / `<video>.play()` + `unlockSound()`), `setupVideo()` (desmuteo cada 2 s), `killAds()` (saca iframes que no sean `streamxhd.*`).
  - **Teclas en `dispatchKeyEvent`**, no en `onKeyDown`: el WebView consume OK y las flechas, así que `onKeyDown` nunca las veía.
    - OK/ENTER/PLAY_PAUSE → play/pausa · PLAY / PAUSE · ◀ ▶ (y REW/FF) → ±15 s dentro de la ventana DVR (`seekable`).
    - ▼ / CH+ / NEXT → servidor siguiente · ▲ / CH- / PREV → anterior · INFO/MENU → muestra servidor actual.
    - BACK → cierra el reproductor.
  - Aviso si el servidor no arranca: poll cada 2 s (`STATUS_JS`); si en 20 s nunca llegó a `playing` (o `video.error`) muestra "Este servidor no arranca. Probá otro con ▲▼".
  - Bloquea popups y cualquier navegación top-level (`shouldOverrideUrlLoading` → true siempre).
  - `onPause`/`onResume` pausan el WebView (no sigue sonando en segundo plano); `onDestroy` destruye el WebView.
  - `gestureUnlock()`: despacha DPAD_DOWN directo al WebView (no pasa por `dispatchKeyEvent`) a 400/1400/2800 ms.
  - **IMPORTANTE**: los JS de control resuelven el video **dentro del iframe** (`playerFrame.contentWindow.document.querySelector('video')`).

## Players del sitio (Nov 2026)
- `live1.php` / `live2.php?stream=X` (dominio principal): **Clappr 0.8** + p2p (swarmcloud), `autoPlay:false`, expone `window.player`. Muestra un botón propio `#soundUnlock` "CLICK PARA ACTIVAR SONIDO" hasta que se llama `window.unlockSound()` (existe otra vez; `window.soundUnlocked` indica el estado). **No** es el aviso nativo de Chrome.
- `channel/?id=X` (marcados "DRM" en la web, hoy en `streamxhd.click`): **Bitmovin 8**, DASH CENC. En el emulador no usa EME/Widevine (`video.mediaKeys` null), así que no hace falta `onPermissionRequest`.
- La página `live2.php` tiene un anuncio propio (`nativeAdSequence`) que hace `window.open(url)` al primer click. Sin bloqueo de popups el WebView abría esa URL **en lugar del reproductor** (se vio `bc.game`).

## Protecciones del server
1. **Server-side (HTTP)**: header `Sec-Fetch-Dest` debe ser `iframe` → si no, 403 "Acceso directo bloqueado".
2. **Client-side inline**: `window.self !== window.top`.
3. **`no.js`**: checks de sandbox, document.domain, plugins, parent.

**Solución**: wrapper con `<iframe>` same-origin → el browser manda `Sec-Fetch-Dest: iframe` y los checks pasan.

## Bloqueo de publicidad
- Red: `shouldInterceptRequest` → respuesta vacía para `skygg.lat`, `llvpn.com`, `llvpn.one`, `bz.cubbiesexcheat.com`, `histats.com` (y subdominios). Lista en `WebGuards`.
- DOM: `killAds()` en el wrapper.
- Popups/redirecciones: bloqueados en ambas activities.

## Dominios con respaldo
Directorio oficial: `https://streamveri.xyz/` (hoy lista `streamxhd.sbs` principal, `.st`, `.click`; `streamxhd.com` redirige a `.sbs`).

Orden de prueba (`startDomainCycle`): último dominio que funcionó (`SharedPreferences` `nahuapp/last_domain`) → dominios leídos del directorio en el arranque anterior (`discovered_domains`) → `DEFAULT_DOMAINS` (`streamxhd.sbs`, `.st`, `.click`, `.com`). El directorio se lee en cada arranque en segundo plano y los dominios nuevos se agregan a la lista en curso.

Se pasa al siguiente dominio si: error de red en el main frame, HTTP >= 400 en el main frame, timeout (12 s hasta `onPageCommitVisible`), redirección fuera de `streamxhd.*` (dominio vencido/estacionado) o la página no es la agenda. Si fallan todos: diálogo "No se pudo conectar" con **Reintentar** / Salir.

## Auto-update OTA
- Al abrir, MainActivity lee `releases/version.txt` de GitHub (raw, sin cache); si es mayor que el `versionCode` instalado → diálogo → descarga `releases/NahuApp.apk` a `cacheDir/updates` (archivo `.part` + rename).
- Antes de abrir el instalador verifica con `getPackageArchiveInfo` que sea `com.streamxhd.tv` y de versión mayor (evita instalar descargas truncadas).
- Instala vía FileProvider (`REQUEST_INSTALL_PACKAGES`). Para que actualice sin desinstalar, el APK se firma **siempre** con `/home/nahu/opencode-tools/debug.keystore`.
- `versionCode` = `git rev-list --count HEAD` al compilar; `versionName` = `1.3-build<N>`. `writeVersionFile` (finalizer de `assembleRelease`) escribe `releases/version.txt`.

## Historial
- Jul 2026: D-pad, calidad live2, refresh in-page, wrapper iframe.
- Ago 2026: bloqueo de ads.
- Sep 2026: dominios de respaldo. **Ese commit (`ee991e9`) dejó un error de sintaxis en el script del wrapper** que lo desactivó hasta Nov 2026.
- Oct 2026: Clappr, OTA, versionado automático, `gestureUnlock()`.
- Nov 2026 (1.3): arreglo del script del wrapper, `unlockSound()`, bloqueo de popups/redirecciones, teclas en `dispatchKeyEvent`, cambio de servidor ▲▼, aviso de servidor caído, destrucción del WebView, dominios dinámicos con directorio + último dominio bueno, OTA verificado, UA con versión real, `WebGuards` compartido.

## Probar en emulador (WSL)
- AVD `tv34` (Android TV 14, WebView 113) en `/root/avd`; correr emulator/adb como **root** (`wsl -u root`, el usuario `nahu` no tiene acceso a `/dev/kvm`).
- Con ventana (WSLg): `DISPLAY=:0 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/mnt/wslg/runtime-dir ANDROID_SDK_ROOT=... ANDROID_AVD_HOME=/root/avd emulator -avd tv34 -gpu swiftshader_indirect`. Sin ventana: agregar `-no-window -no-audio`.
- Teclado del emulador: flechas = D-pad, Enter = OK, Esc = Atrás.
- Los builds **debug** exponen DevTools del WebView: `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`.

## Build & Deploy
- Build: `/home/nahu/opencode-tools/gradle-8.5/bin/gradle clean assembleRelease` (usar gradle directo, el wrapper tiene issues con JDK)
- Sign (con `JAVA_HOME`/`PATH` apuntando a jdk17): `zipalign -p -f 4 app-release-unsigned.apk aligned.apk && apksigner sign --ks /home/nahu/opencode-tools/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out NahuApp.apk aligned.apk`
- JDK 17 en `/home/nahu/opencode-tools/jdk17`, Android SDK 34 + build-tools 34.0.0 en `/home/nahu/opencode-tools/android-sdk/`
- **Toolchain persistente** en `/home/nahu/opencode-tools` (no usar `/tmp/opencode` — se borra al reiniciar el WSL)
- `local.properties`: `sdk.dir=/home/nahu/opencode-tools/android-sdk`
- APK firmado se copia a `releases/NahuApp.apk`
- **Push al repo**: las TVs descargan `releases/NahuApp.apk` y `releases/version.txt` de `main` (raw.githubusercontent). Publicar = commit + `git push`; eso dispara el OTA en todas las TVs.
- GitHub PAT guardado en `.github_pat` (en `.gitignore`).
- GitHub repo: `https://github.com/nfontan/NahuApp.git`, user `nfontan`.
