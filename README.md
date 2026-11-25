# Noel Mp3 Free — Android 1.3.0

Aplicación Android gratuita y en español que permite explorar YouTube, administrar una cola persistente y convertir a MP3 contenido cuya descarga esté autorizada. Se basa en yt-dlp y FFmpeg, sin servicio de conversión externo.

## Novedades de la versión 1.3

- **Nombre**: Noel Mp3 Free en el lanzador y en el encabezado, sin cambiar el identificador del paquete (`com.lsnoglos.mp3local`).
- **Icono**: flecha blanca de descarga sobre fondo violeta y rótulo pequeño «MP3 Noel» en el borde inferior.
- **⋯**: botón pequeño a la derecha de **Descargar MP3**. Abre la carpeta de destino mediante un administrador compatible con Android; si el dispositivo no admite abrir carpetas por URI, lanza el selector del sistema situado en la carpeta. Si ambos fallan, abre la biblioteca integrada.
- **⚙** en la esquina superior derecha: ajustes de **calidad** y **carpeta de destino**.

### Calidad de conversión

Opciones **128, 192, 256 y 320 kbps**. Predeterminada: **192 kbps**. La cifra controla la tasa de bits del MP3 generado por FFmpeg; **una tasa mayor no puede recuperar detalle que el video original no contiene**.

### Carpeta de destino

- **Predeterminada** (si no eliges otra): almacenamiento compartido **Música / MP3 Local**, igual que en las versiones anteriores.
- **Personalizada**: pulsa **⚙ → Seleccionar carpeta…** y elige cualquier carpeta permitida por el selector nativo de Android. La app conserva el permiso con el **Storage Access Framework (SAF)**, sin solicitar acceso a todo el almacenamiento.
- **Restablecer**: **⚙ → Usar carpeta predeterminada**.
- Las restricciones de Android 11+ impiden escoger ciertas ubicaciones sensibles, como las raíces de almacenamiento y algunas carpetas del sistema.
- Si el usuario elimina o revoca el permiso de una carpeta previamente elegida, la descarga marca error y solicita volver a seleccionarla; **no guarda silenciosamente en otra ubicación**.
- **Mis MP3** lista los archivos de la ubicación configurada actualmente. Los archivos antiguos no se mueven al cambiarla.
- **Los ajustes se guardan en el momento**. Cada elemento de cola conserva la calidad y la carpeta elegidas cuando se añade, para que los cambios posteriores no alteren descargas ya pendientes. Trabajos creados con versiones anteriores conservan 192 kbps y el destino predeterminado.

## Navegación y cola

- **Ir** (azul): navega a la URL o ejecuta una búsqueda.
- **←**: retrocede; **↻**: recarga la página del navegador.
- **Pegar** (morado): pega en la barra **sin navegar**.
- **Capturar actual** (ámbar): copia la URL visible a la barra **sin navegar**.
- **Descargar MP3** (verde): añade una descarga a la cola FIFO.
- **Cola (N)**: muestra progreso, permite cancelar o reintentar, conserva pendientes al abandonar la pantalla y mantiene un historial local.
- **Mis MP3**: reproduce y comparte audios de la ubicación de destino actual.

La app comprueba las actualizaciones del motor yt-dlp en segundo plano cuando corresponde, sin botón «Motor».

## Compilar o descargar APK

1. Abre [GitHub Actions](https://github.com/lsnoglos/Mp3-local/actions/workflows/android.yml).
2. Selecciona una ejecución **Compilar APK Android** marcada con éxito.
3. Descarga el artefacto **MP3-Local-Android-APK**.
4. Extrae el ZIP e instala `app-debug.apk` en un dispositivo Android 10 o posterior.
5. Esta APK es de depuración. GitHub Actions puede firmar cada compilación con una clave distinta y Android puede pedir desinstalar la versión anterior. Los archivos MP3 guardados en carpetas públicas no deberían borrarse; el historial privado de la app puede perderse.

Para trabajar en local, abre el proyecto con Android Studio, Java 17, SDK 36 y Gradle 9.6. Puedes ejecutar `:app:testDebugUnitTest :app:assembleDebug`.

## Componentes

- `MainActivity.java`: pantalla, navegación, configuración, exploración de carpeta, cola y biblioteca.
- `OutputSettings.java`: preferencias, tasas de bits admitidas y escritor de archivos SAF / MediaStore.
- `DownloadQueueStore.java`: cola FIFO persistente; guarda calidad y carpeta por trabajo.
- `DownloadService.java`: descargas y conversiones secuenciales, progreso, notificaciones y guardado.
- `UrlTools.java`: validación de enlaces.
- `res/drawable/ic_launcher.xml`: símbolo de descarga con texto pequeño «MP3 Noel».

## Condiciones y limitaciones

La navegación web y la extracción de audio están sujetas a las condiciones de YouTube. Descarga únicamente videos propios o contenido cuya descarga y conversión estén autorizadas. Los cambios en YouTube pueden afectar algunas descargas aunque se actualice yt-dlp; el proyecto no elude DRM ni controles de acceso.

Código de la aplicación: GPL-3.0-or-later. Revisa las licencias de [youtubedl-android](https://github.com/yausername/youtubedl-android), [yt-dlp](https://github.com/yt-dlp/yt-dlp) y [FFmpeg](https://ffmpeg.org/) antes de redistribuir la APK. Las compilaciones deben probarse en un dispositivo Android real antes de considerarse estables.
