# BT Diag — sonda de Bluetooth para head unit Allwinner T3

APK de diagnostico. NO reproduce nada: solo averigua como esta implementado
el Bluetooth en el equipo, para saber si es posible construir un reproductor
BT con visualizador y controles.

## Compilar
Sube este repo a GitHub. El workflow `.github/workflows/build.yml` corre
`gradle assembleDebug` (Gradle 8.7, JDK 17) y sube el APK como artifact.
No hace falta gradle wrapper.

## Como usarlo en el auto
1. Instala el APK.
2. Abrelo y acepta el permiso de microfono (lo necesita el Visualizer).
3. Toca **Permiso** y habilita "BT Diag" en Acceso a notificaciones.
4. Empareja el celular y **pone musica sonando**.
5. Volve a la app y toca **Reintentar**.
6. Cambia de cancion en el celular y mira el panel verde de abajo.
7. Toca **Play/Pausa** y **Next**: si el celular reacciona, el control por
   teclas multimedia funciona.
8. Toca **Guardar** y pasa el archivo `Download/bt_diag.txt`.

## Que estamos buscando
- `A2DP_SINK` y `AVRCP_CONTROLLER` disponibles -> hay stack de Android.
- `com.android.bluetooth` instalado -> idem.
- Visualizer(0) con amplitud > 4 mientras suena musica -> el audio pasa por
  el mixer de Android: visualizador y ecualizador viables.
- Ninguna de las anteriores -> modulo BT externo por UART, el proyecto no
  es viable como app Android.
