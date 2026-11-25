# MP3 Local — Android 1.2.0

Aplicación Android en español, gratuita y sin anuncios, para explorar YouTube y descargar audio de contenido cuya descarga y conversión estén autorizadas. La conversión a MP3 ocurre localmente mediante yt-dlp y FFmpeg, sin servidor de pago.

## Navegación renovada

- **Ir** (azul): abre el enlace introducido o busca la frase escrita en YouTube.
- **←** (gris): vuelve a la página anterior sin cambiar el campo de búsqueda.
- **↻** (azul): recarga la página actual del navegador. No es el antiguo botón «Motor».
- **Pegar** (morado): copia texto del portapapeles en la barra **sin navegar ni recargar**.
- **Capturar actual** (ámbar): copia automáticamente la URL de la página WebView actual al campo, **sin navegar ni recargar**.
- **Descargar MP3** (verde, con icono de descarga): incorpora el video a la cola. Puedes seguir navegando mientras se procesa.
- **Cola (N)**: muestra cuántos archivos están pendientes o en proceso.
- **Mis MP3**: reproduce o comparte los audios almacenados.

El botón **Motor** fue eliminado porque las actualizaciones de yt-dlp se comprueban automáticamente en segundo plano (máximo cada 7 días tras una actualización exitosa, y con límite de reintentos cuando la red falla). No necesitas administrar el motor para navegar.

## Cola de descargas

- **FIFO**: los enlaces se procesan de uno en uno, en el orden que se añaden.
- **Persistente**: la lista queda guardada localmente si sales de la pantalla. Al volver a abrir la app, los trabajos pendientes pueden reanudarse.
- **Cancelar por elemento**: cancela una descarga en ejecución o elimina un elemento pendiente de la cola, sin afectar los demás.
- **Reintentar**: los trabajos fallidos o cancelados vuelven a ponerse en espera.
- **Limpiar historial**: borra del listado las tareas completadas, fallidas y canceladas, no los archivos MP3 guardados.
- **Evitar duplicados accidentales**: si pulsas descargar varias veces el mismo enlace mientras sigue pendiente/en ejecución, se mantiene una única entrada activa.
- **Estado visible**: En espera, Descargando, Completado, Error o Cancelado.

Los MP3 se publican a través de MediaStore en `Música / MP3 Local`. El servicio de primer plano continúa mientras haya archivos pendientes; Android podría interrumpir procesos si el sistema restringe su ejecución o si se fuerza el cierre de la aplicación.

## APK de prueba desde GitHub Actions

1. Abre [Actions](https://github.com/lsnoglos/Mp3-local/actions/workflows/android.yml).
2. Selecciona una ejecución **Compilar APK Android** terminada en verde.
3. Descarga el artefacto **MP3-Local-Android-APK** de esa ejecución.
4. Extrae el ZIP e instala `app-debug.apk` en Android 10+.
5. La firma de depuración puede cambiar entre ejecuciones de GitHub Actions: podría ser necesario desinstalar la versión anterior para instalar la nueva. Los archivos en la carpeta pública `Música / MP3 Local` no deberían eliminarse al desinstalar la app, pero el historial privado de la cola sí puede perderse.

## Compilar localmente

- Android Studio, Java 17, SDK 36, Gradle 9.6, Android Gradle Plugin 9.4.
- Abre el proyecto por `settings.gradle`.
- Compila la configuración `app` con `:app:assembleDebug`.

La construcción usa los módulos `library:0.18.1` y `ffmpeg:0.18.1` de [youtubedl-android](https://github.com/yausername/youtubedl-android).

## Arquitectura

- `MainActivity.java`: interfaz renovada, WebView, barra de enlace, navegación, cola, biblioteca.
- `DownloadQueueStore.java`: trabajos FIFO persistentes en SharedPreferences, reintentos y cancelación.
- `DownloadService.java`: servicio foreground con procesamiento secuencial, yt-dlp + FFmpeg y guardado MediaStore.
- `UrlTools.java`: validación de enlaces de YouTube.
- `.github/workflows/android.yml`: pruebas unitarias y compilación automática APK.

## Limitaciones y derechos

El navegador WebView depende de la compatibilidad del sitio web; la reproducción o inicio de sesión pueden estar restringidos. Si fuera necesario, comparte enlaces desde la aplicación oficial de YouTube. Los cambios de YouTube pueden afectar las descargas, incluso con yt-dlp actualizado.

Descarga únicamente videos propios o contenido cuya descarga y reutilización estén permitidas. Las condiciones de YouTube pueden restringir la extracción/descarga de audio. La app no omite DRM ni controles de acceso. Es un proyecto de prueba, no una autorización de YouTube ni de titulares de derechos.

## Licencias

Código de esta aplicación: GPL-3.0-or-later. Sus dependencias tienen licencias y obligaciones propias: [youtubedl-android](https://github.com/yausername/youtubedl-android), [yt-dlp](https://github.com/yt-dlp/yt-dlp) y [FFmpeg](https://ffmpeg.org/). Revisa y conserva los avisos y licencias al redistribuir, en especial para binarios nativos. No se garantiza compatibilidad con todos los videos o versiones de Android.
