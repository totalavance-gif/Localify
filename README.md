# Localify

Reproductor estilo Spotify para tu música local: lista con
carátulas y buscador, mini-reproductor, pantalla "sonando" con
barra de progreso, aleatorio y repetir una. Sigue sonando con
la pantalla apagada (servicio + notificación con controles +
audífonos/Bluetooth). Todo 100% local, sin internet ni cuentas.

## Uso

1. Instala la APK y abre la app.
2. Acepta el permiso de audio (y notificaciones en Android 13+).
3. Toca una canción; usa la barra inferior para abrir el
   reproductor completo.

## Descargar la APK

Cada push a `main` la compila en Actions: pestaña **Actions** →
último run verde → artifact **localify-apk** (instala la debug).
Si un build falla, el workflow abre un issue con el extracto del log.

## Compilar en local

Necesitas JDK 17 + Android SDK:

```sh
./gradlew assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/`.
