# Pantalla Remota

App Android para ver y manejar la pantalla de tu celular desde Chrome en otro dispositivo. Sin servidores externos ni suscripciones: el celular mismo sirve la página.

## Compilar el APK (GitHub, gratis, sin instalar nada)

1. Creá un repositorio nuevo en GitHub (puede ser privado), por ejemplo `pantalla-remota`.
2. Subí todo el contenido de esta carpeta (incluida la carpeta oculta `.github`).
   - Desde la web: "Add file → Upload files" y arrastrá los archivos. Si `.github` no se sube al arrastrar, creá a mano el archivo `.github/workflows/build.yml` con "Add file → Create new file" y pegá su contenido.
3. Entrá a la pestaña **Actions**: se ejecuta "Compilar APK" solo (tarda ~3–5 min).
4. Al terminar, abrí la ejecución y descargá **PantallaRemota-apk** (un .zip con el `app-debug.apk`).

## Instalar y configurar (en el celular que vas a controlar)

1. Instalá el APK (permitir "instalar apps desconocidas").
2. Abrí la app → **1. Activar control (Accesibilidad)** → activá "Pantalla Remota (control)".
   - En Android 13 o más nuevo, si aparece "Configuración restringida": andá a Ajustes → Apps → Pantalla Remota → menú ⋮ (arriba a la derecha) → **Permitir configuración restringida**, y volvé a intentar.
3. Tocá **Evitar que Android corte la app (batería)** y aceptá.
4. Tocá **2. Iniciar compartir pantalla** y elegí **pantalla completa** (no "una sola app").
5. La app muestra la dirección (ej. `http://192.168.0.15:8080`) y el PIN.

## Conectarte desde el otro celular

- **Misma Wi-Fi:** abrí la dirección en Chrome, poné el PIN y listo.
- **Desde cualquier lugar (datos móviles, otra casa):** instalá **Tailscale** (gratis) en los dos celulares con la misma cuenta. La app va a mostrar una dirección `http://100.x.x.x:8080`: usá esa.

## Controles

- Tocar = toque · mantener = toque largo · arrastrar = deslizar.
- ◀ Atrás · ● Inicio · ▢ Recientes · 🔔 Notificaciones · ⌨ Teclado · ☀ Despertar pantalla · 🔒 Bloquear.
- Teclado: tocá primero un campo de texto en la pantalla remota, escribí y tocá ➤. ⌫ borra, ↵ envía/Enter.
- Calidad: Baja (datos móviles), Media, Alta.

## Límites

- Si el celular tiene bloqueo con PIN/patrón, ☀ prende la pantalla pero no lo desbloquea: lo tenés que desbloquear con los toques (o quitarle el bloqueo).
- Android pide autorizar la captura cada vez que se inicia el compartir (es una regla del sistema). Mientras esté activo queda una notificación fija con botón "Detener".
- Algunas apps (bancos, Netflix) bloquean la captura y se ven en negro.
- Sin audio.
