# Contexto del Proyecto — NahuApp (StreamXHD-TV)

## Que es
App Android nativa (Kotlin) que wrappea stream-xhd.com en un WebView. Diseñada para Android TV. Sin librerías de terceros — solo WebView + HTML5 video.

## Arquitectura
- **MainActivity**: WebView principal. Inyecta `INJECTED_JS` via `onPageFinished` que:
  - Forza `localStorage.playbackMode = 'live2'` (calidad adaptativa automática).
  - Intercepts clicks en `[data-action="play"]` → extrae `data-url` → llama `Android.playUrl(url)` → abre `PlayerActivity`.
  - Inyecta botón refresh flotante (`#__refresh_btn`) dentro de la página (D-pad reachable).
  - Agrega `tabindex="0"` a `[data-action="play"]` y `.tab` (filtros), auto-focus al primer `.tab`.
  - `MutationObserver` mantiene tabindex y refresh button ante cambios del DOM.
- **PlayerActivity**: Actividad separada que recibe la URL del stream via Intent. Usa **wrapper HTML con iframe**:
  - Carga un HTML wrapper via `loadDataWithBaseURL("https://streamx-hd.com/", ...)` con un `<iframe src="URL_DEL_PLAYER">`.
  - El iframe envía `Sec-Fetch-Dest: iframe` automáticamente (check server-side del player).
  - Wrapper e iframe son **same-origin** (`streamx-hd.com`), permitiendo acceso al contenido del iframe.
  - Script wrapper llama `unlockSound()` del player para desmutear + ocultar overlay de sonido.
  - Intento de auto-play via `v.play()` en el video del iframe.
  - `mediaPlaybackRequiresUserGesture = false`.
  - `screenOrientation = "landscape"`.
  - Manejo de D-pad: CENTER/ENTER/PLAY_PAUSE → toggle play/pause, LEFT/RIGHT → seek ±15s, INFO → fullscreen toggle.
  - `VideoBridge` (`@JavascriptInterface`) para trackear foco del video.
  - Fullscreen HTML5 (`onShowCustomView`/`onHideCustomView`).

## Protecciones del server (streamx-hd.com)
El server tiene **3 capas de protección** contra acceso directo:

1. **Server-side (HTTP)**: Verifica header `Sec-Fetch-Dest`. Si no es `iframe` → 403 "Acceso directo bloqueado".
2. **Client-side inline (HTML)**: Script同步 en `<head>`: `window.self !== window.top`. Si `true` → piensa que estás en iframe → pasa.
3. **`no.js`**: Múltiples checks (sandbox, document.domain, plugins, parent). `window.parent === window` → no bloquea.

**Solución**: Wrapper HTML con `<iframe>` → el browser envía `Sec-Fetch-Dest: iframe` naturalmente → el inline check ve que `window.top !== window.self` → todo pasa.

## Bugs conocidos (sin fix)
1. **PlayerActivity sin `canGoBack()`**: BACK siempre cierra, no permite navegar atrás en el WebView (intencional para video player).

## Cambios aplicados (Jul 5-26)
1. **Control por D-pad en el reproductor**: Mapeo de teclas + `VideoBridge` + JS de control.
2. **Calidad automática forzada**: `localStorage.playbackMode = 'live2'` en INJECTED_JS.
3. **Volumen máximo removido**: Se sacó `AudioManager` (subía volumen del TV). Se reemplazó por JS que desmutea el `<video>` directo.
4. **Botón refresh**: Se cambió de `Button` nativo (no reachable por D-pad) a `<div>` inyectado en la página con `tabindex`.
5. **Filtros `.tab` navegables**: `tabindex="0"` + `focusFirstTab()` al cargar la página.
6. **Auto-play + auto-fullscreen**: `mediaPlaybackRequiresUserGesture = false` + `AUTO_SETUP_VIDEO_JS` que reproduce y entra a fullscreen automáticamente.
7. **Marco amarillo eliminado**: `v.style.outline = 'none'` en el `<video>`.
8. **Playback en wrapper iframe**: PlayerActivity carga un wrapper HTML con `<iframe src="player_url">` same-origin (`streamx-hd.com`). Esto evita las 3 capas de protección del server. El wrapper llama `unlockSound()` del player para unmute + ocultar overlay.

## Build & Deploy
- Build: `/tmp/opencode/gradle-8.5/bin/gradle clean assembleRelease` (usar gradle directo, el wrapper tiene issues con JDK)
- Sign: `zipalign -v -p -f 4 app-release-unsigned.apk aligned.apk && apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey aligned.apk`
- JDK 17 en `/tmp/opencode/jdk17`, Android SDK 34 + build-tools 34.0.0 en `/tmp/opencode/android-sdk/`
- Debug keystore: `/tmp/opencode/debug.keystore` (password: android)
- APK firmado se copia a `releases/NahuApp.apk` y `C:\Users\Nahu\Desktop\NahuApp.apk`
- **Push al repo**: El usuario descarga desde `https://github.com/nfontan/NahuApp/blob/main/releases/NahuApp.apk` (no desde releases assets). Hay que hacer `git push` para que se actualice.
- GitHub PAT guardado en `.github_pat` (agregado a `.gitignore`).
- GitHub repo: `https://github.com/nfontan/NahuApp.git`, user `nfontan`.
