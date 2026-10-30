# Asistente IA para Android

Versión móvil del asistente. En vez de mascota: al decir la frase de activación («oye asistente» por defecto) aparece una **ventana flotante solo con su cara**, con efecto glitch y la boca animada mientras habla.

## ⚠️ Estado honesto
La IA del móvil usa la librería `tasks-genai:0.10.27` de Google, que no he podido descargar ni probar aquí; la llamo por reflexión, así que si el nombre de algún método cambió, fallará al usarla (lo dirá en voz alta) y habría que ajustarlo.
No he podido compilar el APK ni probarlo en un móvil (aquí no hay acceso a los servidores de Google/Gradle). Lo que sí está comprobado:
- Todo el Kotlin **compila** contra el SDK de Android 33 (kotlinc, 0 errores).
- La lógica pura (detección de la frase, política de respuestas, límites, normalización de herramientas) pasa sus pruebas.
- La página de la cara (glitch + boca) se ha probado en Chromium.
Lo que depende del móvil real (reconocimiento de voz continuo, WebView flotante, notificaciones, permisos según fabricante) **puede necesitar ajustes**. Si algo falla, dime el síntoma.

## Compilar el APK
**Opción A – Android Studio:** abre esta carpeta, espera a que sincronice y pulsa *Build → Build APK(s)*.
**Opción B – GitHub (sin instalar nada de Android):** ejecuta `crear-apk.bat` (Windows) o `./crear-apk.sh` con el GitHub CLI (`gh`) ya iniciado con tu cuenta. Crea un repositorio privado, sube el código, lanza la compilación y descarga `app-debug.apk` en esta carpeta.
A mano: crea un repositorio en github.com, sube todo el contenido (incluida la carpeta oculta `.github`) y en *Actions → Compilar APK → Run workflow* descarga el artefacto `AsistenteIA-debug-apk`.
Instálalo en el móvil (permite «instalar apps desconocidas»).

## Primera configuración (pantalla principal de la app)
1. Pulsa cada permiso de la lista: micrófono, mostrar sobre otras apps, notificaciones, cambiar brillo, acceso a notificaciones (mensajes), accesibilidad (inicio/atrás/bloquear/captura), contactos+SMS y «sin ahorro de batería».
2. Elige cerebro (por defecto **«IA en el propio móvil»: sin clave ni internet**):
   - **IA en el móvil** (por defecto, el «Ollama del móvil»): usa MediaPipe LLM Inference y corre dentro del teléfono. Descarga **una vez** un modelo pequeño desde la app: pega un enlace https de un modelo `.task`/`.litertlm` (p. ej. de la comunidad `litert-community` en Hugging Face: Qwen2.5 0.5B o Gemma 3 1B — Gemma suele pedir aceptar licencia, así que en ese caso descárgalo en el navegador y usa «Elegir un archivo») y elige su plantilla. Necesita ~4 GB de RAM o más; en móviles modestos usa el modelo más pequeño. Con él el asistente charla, y redacta respuestas a mensajes. Las órdenes ("sube el volumen"…) las resuelve primero el intérprete interno, que es más fiable que un modelo pequeño. Sin modelo instalado funciona como «Sin IA».
   - **Sin IA**: entiende órdenes habituales ("sube el volumen", "alarma a las siete y media", "abre WhatsApp", "llama a mamá", "pon un temporizador de 5 minutos", "manda un mensaje a Ana diciendo que llego tarde"…). Contesta lo básico. No redacta respuestas a mensajes (solo envía lo que tú dictes o aceptes), porque eso requiere IA.
   - **Chat gratis online** (sin clave, experimental): añade charla libre y redacción de respuestas usando un servicio público gratuito (Pollinations). Ojo: el texto de la conversación —y de los mensajes que redacte— sale a ese servicio. No lo he podido probar desde aquí.
   - **Ollama** en tu PC (sin clave): IP del PC en tu wifi, p. ej. `http://192.168.1.50:11434`; arranca Ollama con `OLLAMA_HOST=0.0.0.0`. Es la opción gratuita más privada y completa.
   - **Claude**: solo si quieres, con tu clave.
   Si el chat/IA falla o no hay red, las órdenes básicas siguen funcionando.
3. Activa «Escuchar la frase de activación».

## Qué puede hacer por voz
Volumen y brillo, linterna, abrir apps, llamar (abre el marcador), enviar SMS, alarmas y temporizadores, control de música, abrir webs/buscar, abrir ajustes (wifi, bluetooth, etc.), inicio/atrás/recientes/notificaciones/bloquear/captura, leer tus mensajes recientes y contestar a uno concreto («contesta a Ana que llego tarde»).

## Responder mensajes por ti — seguridad
- Por defecto **no contesta nada**. Por app: *No / Borrador / Automático*.
- *Borrador*: te llega una notificación con la respuesta y botones Enviar/Descartar.
- *Automático*: máx. 3 respuestas por hora y conversación; nunca contesta mensajes con dinero, claves, urgencias o datos sensibles (se queda en borrador); ignora códigos de verificación; grupos desactivados salvo que los actives.
- Por defecto añade «(Respuesta automática)» para que la otra persona sepa que es una IA (se puede quitar).
- Funciona usando el botón «Responder» de la notificación, así que solo vale para conversaciones con notificación activa.

## Límites
- Solo Android 8+. Wifi/Bluetooth: Android solo permite abrir su panel, no conmutarlos.
- La cara no aparece sobre la pantalla de bloqueo.
- Silenciar los pitidos del micrófono baja los volúmenes de notificación/sistema mientras escucha (se puede desactivar).
- En Android 14 el servicio de micrófono no puede arrancar solo al encender el móvil: abre la app una vez tras reiniciar.
- Escuchar siempre gasta batería.
