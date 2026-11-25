# MP3 Local — Android

App nativa y gratuita para **navegar por YouTube**, recibir enlaces desde otras aplicaciones y guardar **MP3 de contenido cuya descarga esté autorizada**. La extracción y conversión se realizan **en el dispositivo Android** mediante yt-dlp y FFmpeg, sin servidor externo.

## Funciones

- Navegador integrado con búsqueda de YouTube y enlaces de videos individuales.
- Pegar enlaces desde el portapapeles o compartirlos desde YouTube con **MP3 Local**.
- Convertir audio a MP3 (calidad solicitada: 192 kbps; la calidad real depende de la fuente).
- Actualizar el motor yt-dlp desde la pestaña **↻ Motor**, sin reinstalar la aplicación.
- Comprobación automática del motor antes de descargar (máximo una vez cada 7 días tras éxito; reintentos con intervalo de 24 horas si falla). Si no hay conexión a GitHub, intenta usar el motor instalado.
- Descarga en segundo plano mediante servicio de primer plano, notificación de progreso y cancelación.
- Guardar archivos en **Música / MP3 Local** con MediaStore, sin permisos amplios de almacenamiento.
- Pestaña **Mis MP3**: reproducir y compartir archivos guardados.
- Interfaz en español y sin anuncios, cuentas ni servidor.
- Solo admite enlaces https de videos YouTube en el descargador; no descarga listas completas.

## Descargar APK desde GitHub

1. Abre la pestaña **Actions** de este repositorio.
2. Selecciona **Compilar APK Android** y la ejecución más reciente exitosa.
3. En **Artifacts**, descarga **MP3-Local-Android-APK**.
4. Extrae el ZIP, instala `app-debug.apk` en Android 10 o superior (ARM 32/64 bits).
5. Si Android advierte que la app es de origen desconocido, habilita la instalación desde el gestor de archivos que estás utilizando, solo si confías en el APK.

**Nota:** GitHub Actions debe estar habilitado para el repositorio. El flujo produce un APK de depuración firmado automáticamente por el entorno de compilación; para publicar una versión oficial se requiere crear y proteger una firma de lanzamiento propia.

## Abrir en Android Studio

- Android Studio con JDK 17 y Android SDK 36.
- Abre la carpeta raíz del repositorio (archivo `settings.gradle`).
- Gradle: 9.6.0; Android Gradle Plugin: 9.4.0.
- Ejecuta la configuración **app** en un dispositivo Android 10+.
- Repositorios Maven: Google y Maven Central.

El flujo de GitHub Actions instala Gradle en el ejecutor, por lo que este repositorio no incluye archivos binarios del Gradle Wrapper.

## Cómo usar

1. Escribe una búsqueda y toca **Ir**, o pega un enlace https de un video.
2. Abre un video individual dentro del navegador.
3. La primera descarga comprueba si existe una actualización del motor yt-dlp. Si deseas hacerlo manualmente, pulsa **↻ Motor** y espera la confirmación.
4. Toca **↓ MP3** (solo cuando tengas derecho/autorización para descargarlo).
5. Espera la notificación de conversión; también puedes tocar **✕** para cancelar.
6. Abre **Mis MP3** para reproducir o compartir el archivo.
7. Alternativa útil: desde la app oficial de YouTube, pulsa **Compartir → MP3 Local**, si Android muestra esta aplicación como destino.

Si YouTube bloquea la reproducción o el inicio de sesión en WebView, utiliza la app oficial y comparte el enlace. Los cambios de YouTube pueden afectar yt-dlp; revisa las actualizaciones de la biblioteca cuando aparezcan errores 403 u otros errores de extracción.

## Mensaje «yt-dlp older than 90 days»

Ese aviso indica que el motor incluido en la APK es antiguo; por sí solo no confirma un fallo. En la versión 1.1.0 pulsa **↻ Motor** para actualizar desde el canal estable de yt-dlp. Si falla la actualización, comprueba la conexión a Internet, especialmente el acceso a GitHub, y repite. La aplicación también intenta actualizar antes de descargar, con una frecuencia limitada para no consumir datos de forma innecesaria.

## Arquitectura

- `MainActivity.java`: interfaz, navegador WebView, búsqueda, enlaces, biblioteca MP3.
- `DownloadService.java`: yt-dlp + FFmpeg, servicio foreground, MediaStore y progreso.
- `UrlTools.java`: validación estricta de URLs de videos.
- `.github/workflows/android.yml`: pruebas y compilación automática de APK.

## Privacidad y límites

No se necesita cuenta de la app. Los archivos se convierten localmente y se guardan en el almacenamiento del usuario. La navegación por YouTube está sujeta a las condiciones y prácticas de privacidad de YouTube. La app no extrae DRM ni elude requisitos de inicio de sesión. Ciertos videos pueden fallar por restricciones de acceso o por cambios en YouTube.

**Derechos y plataforma:** Descarga únicamente videos propios o contenido cuya descarga y reutilización estén permitidas. Las condiciones de YouTube pueden restringir la extracción/descarga y el uso de navegadores no oficiales. Este proyecto se ofrece para pruebas y usos autorizados; no supone permiso de YouTube ni de los titulares del contenido.

## Licencias y atribución

Código de esta aplicación: GPL-3.0-or-later. Utiliza los componentes abiertos `io.github.junkfood02.youtubedl-android:library:0.18.1` y `ffmpeg:0.18.1` del proyecto [youtubedl-android](https://github.com/yausername/youtubedl-android), sujeto a sus licencias respectivas, así como [yt-dlp](https://github.com/yt-dlp/yt-dlp) y [FFmpeg](https://ffmpeg.org/). Para su redistribución revisa y conserva los avisos de copyright, texto de licencia y obligaciones relativas a binarios nativos.

El código se proporciona sin garantía. Es necesario probar el APK en un dispositivo real antes de considerarlo una publicación estable.
