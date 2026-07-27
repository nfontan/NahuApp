# Contexto del Proyecto — NahuApp (StreamXHD-TV)

## Que es
App Android nativa (Kotlin) que wrappea stream-xhd.com en un WebView. Diseñada para Android TV. Sin librerías de terceros — solo WebView + HTML5 video.

## Arquitectura
- **MainActivity**: WebView principal. Inyecta `INJECTED_JS` via `onPageFinished` que:
  - Forza `localStorage.playbackMode = 'live2'` (calidad adaptativa automática).
  - No intercepta clicks en play — el sitio carga el video en su propio `<iframe>` (evita bloqueo por validación de iframe en el servidor).
  - Inyecta botón refresh flotante (`#__refresh_btn`) dentro de la página (D-pad reachable).
  - Agrega `tabindex="0"` a `[data-action="play"]` y `.tab` (filtros), auto-focus al primer `.tab`.
  - `MutationObserver` mantiene tabindex y refresh button ante cambios del DOM.
  - Fullscreen HTML5 (`onShowCustomView`/`onHideCustomView`). Doble-back para salir.
- **PlayerActivity**: Actividad separada que recibe la URL del stream via Intent. Inyecta `AUTO_SETUP_VIDEO_JS` via `onPageFinished` que:
  - Desmutea el `<video>`, setea volumen 1.0, outline none.
  - Agrega `tabindex="0"`, trackea foco via `VideoBridge` (`@JavascriptInterface`).
  - Auto-play (`v.play()`) y auto-fullscreen al iniciar.
  - Polling cada 2s para re-desmutear si es necesario.
  - Manejo de D-pad: CENTER/ENTER/PLAY_PAUSE → toggle play/pause, LEFT/RIGHT → seek ±15s, INFO → fullscreen toggle.
  - `mediaPlaybackRequiresUserGesture = false`.
  - `screenOrientation = "landscape"`.

## Bugs conocidos (sin fix)
1. **PlayerActivity sin `canGoBack()`**: BACK siempre cierra, no permite navegar atrás en el WebView (intencional para video player).

## Cambios aplicados (Jul 5)
1. **Control por D-pad en el reproductor**: Mapeo de teclas + `VideoBridge` + JS de control.
2. **Calidad automática forzada**: `localStorage.playbackMode = 'live2'` en INJECTED_JS.
3. **Volumen máximo removido**: Se sacó `AudioManager` (subía volumen del TV). Se reemplazó por JS que desmutea el `<video>` directo.
4. **Botón refresh**: Se cambió de `Button` nativo (no reachable por D-pad) a `<div>` inyectado en la página con `tabindex`.
5. **Filtros `.tab` navegables**: `tabindex="0"` + `focusFirstTab()` al cargar la página.
6. **Auto-play + auto-fullscreen**: `mediaPlaybackRequiresUserGesture = false` + `AUTO_SETUP_VIDEO_JS` que reproduce y entra a fullscreen automáticamente.
7. **Marco amarillo eliminado**: `v.style.outline = 'none'` en el `<video>`.
8. **Playback en iframe nativo**: Se removió la interceptación de clicks en play. El sitio carga el video en su propio `<iframe>` dentro de la página, evitando el bloqueo por validación de iframe del servidor.

## Build & Deploy
- Build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release-unsigned.apk`
- Sign: `zipalign -v -p -f 4 app-release-unsigned.apk aligned.apk && apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey aligned.apk`
- JDK 17 + Android SDK 34 + build-tools 34.0.0 en `/tmp/opencode/`
- Debug keystore: `/tmp/opencode/debug.keystore` (password: android)
- APK firmado se copia a `C:\Users\Nahu\Desktop\NahuApp.apk`
