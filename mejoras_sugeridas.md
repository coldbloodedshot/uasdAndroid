# Mejoras Sugeridas — UASD Android

> Análisis basado en revisión completa del código fuente. Las sugerencias están ordenadas de mayor a menor urgencia dentro de cada categoría.

---

## 🔴 Seguridad (Crítico)

### 1. API Key de Gemini expuesta en el código fuente
**Archivo:** `StudentListFragment.kt` línea ~1071
```kotlin
// ❌ PROBLEMA ACTUAL
val GEMINI_API_KEY = "AIzaSyCqE6brL3uq0mP3YVgRc9QmGDUvgHzJNaY"
```
**Riesgo:** Cualquier persona que descompile el APK puede extraer la clave y usar la cuota de Gemini a tu costa.

**Solución:**
- Moverla a `local.properties` → leer vía `BuildConfig` en tiempo de compilación.
- O mejor: proxy backend (Firebase Functions) que llame a Gemini, manteniendo la clave solo en el servidor.

```kotlin
// ✅ SOLUCIÓN en build.gradle
buildConfigField "String", "GEMINI_API_KEY", "\"${localProperties['gemini.api.key']}\""

// En código:
val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY
```

---

### 2. URL de Google Apps Script expuesta en el código
**Archivo:** `StudentListFragment.kt` línea ~1578

Similar al punto anterior, la URL del script (que puede recibir datos sensibles de notas) está embebida. Debe moverse también a `BuildConfig` o eliminarse si el flujo ya no se usa.

---

### 3. Reglas de Firebase Realtime Database
**Riesgo desconocido:** La autenticación es completamente anónima (`signInAnonymously`). Si las reglas de Firebase permiten escritura sin restricciones adicionales, **cualquier persona con el archivo `google-services.json`** (incluido en el repositorio) podría leer o modificar todas las notas.

**Solución:** Verificar y endurecer las reglas en la consola de Firebase:
```json
{
  "rules": {
    ".read": "auth != null",
    ".write": "auth != null"
  }
}
```
Idealmente, implementar autenticación real (Google Sign-In) y restringir por UID de docente.

---

### 4. `google-services.json` en el repositorio
El archivo `app/google-services.json` contiene el `api_key` de Firebase y los identificadores del proyecto. Si el repositorio es público, esto expone el proyecto Firebase.

**Solución:** Agregar al `.gitignore`:
```
app/google-services.json
local.properties
```
Y compartirlo por canal seguro entre los colaboradores.

---

## 🟠 Estabilidad y Robustez

### 5. `StudentListFragment` tiene demasiadas responsabilidades (~1700 líneas)
El fragmento maneja: UI, lógica de negocio, Firebase, 3 motores de voz, Google Drive, Gemini, navegación y estado. Esto genera:
- Difícil mantenimiento y testing
- Alto riesgo de errores al cambiar una parte
- Fugas de memoria difíciles de detectar

**Solución:** Separar en capas (patrón MVVM):
```
StudentListViewModel.kt   <- Lógica de negocio + Firebase
StudentListFragment.kt    <- Solo UI (~300 líneas)
DictationManager.kt       <- Todo lo de voz (Vosk, Native, Gemini)
GradeRepository.kt        <- Operaciones CRUD de notas
```

---

### 6. Listeners de Firebase en `AttendanceActivity` nunca se limpian
**Archivo:** `AttendanceActivity.kt`

Los tres `addValueEventListener` de `cargarDatos()` no se eliminan en `onDestroy()`. Con el tiempo, si el usuario entra y sale muchas veces, se acumulan listeners duplicados.

```kotlin
// ✅ SOLUCIÓN: guardar referencias y limpiar
private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()

override fun onDestroy() {
    super.onDestroy()
    listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
}
```

---

### 7. Crash potencial en `SeccionAdapter.convertTo24h()`
**Archivo:** `SeccionAdapter.kt` línea ~76

```kotlin
val date = sdf12.parse(timeStr.trim()) // puede retornar null
return sdf24.format(date)              // NullPointerException si date es null
```

**Solución:**
```kotlin
private fun convertTo24h(timeStr: String): String {
    return try {
        val sdf12 = SimpleDateFormat("h:mm a", Locale.US)
        val sdf24 = SimpleDateFormat("HH:mm", Locale.US)
        sdf24.format(sdf12.parse(timeStr.trim()) ?: return "00:00")
    } catch (e: Exception) {
        "00:00"
    }
}
```

---

### 8. `CoroutineScope(Dispatchers.Main).launch {}` sin ciclo de vida
**Archivo:** `StudentListFragment.kt` (múltiples ocurrencias)

Se usan coroutines con scope global. Si el fragmento se destruye mientras la coroutine corre, se ejecuta código sobre vistas ya destruidas, potencial crash.

**Solución:** Usar `viewLifecycleOwner.lifecycleScope.launch {}` que se cancela automáticamente cuando el fragmento muere:
```kotlin
// ANTES:
CoroutineScope(Dispatchers.Main).launch { ... }

// DESPUES:
viewLifecycleOwner.lifecycleScope.launch { ... }
```

---

### 9. `getSerializableExtra()` deprecado en Android 13+
**Archivo:** `StudentListActivity.kt` línea ~30

```kotlin
@Suppress("DEPRECATION", "UNCHECKED_CAST")
secciones = intent.getSerializableExtra("SECCIONES_LIST") as? List<Seccion>
```

**Solución:**
```kotlin
secciones = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    intent.getSerializableExtra("SECCIONES_LIST", ArrayList::class.java) as? List<Seccion>
} else {
    @Suppress("DEPRECATION")
    intent.getSerializableExtra("SECCIONES_LIST") as? List<Seccion>
} ?: emptyList()
```

---

### 10. `notifyDataSetChanged()` usado en todos los adapters
Todos los adapters llaman `notifyDataSetChanged()` ante cualquier cambio, lo que redibuja toda la lista aunque solo cambie un ítem. Con listas grandes, esto causa janks (saltos de UI).

**Solución:** Implementar `DiffUtil` en los adapters para actualizaciones granulares y animadas.

---

## 🟡 Organización del Código y Arquitectura

### 11. Constantes mágicas dispersas por el código

Strings como `"seccion_detalles"`, `"estudiantes"`, `"notas"`, `"asistencias"`, etc., aparecen repetidos literalmente en múltiples archivos. Un typo en uno solo causa un bug silencioso.

**Solución:** Crear un objeto de constantes:
```kotlin
// FirebasePaths.kt
object DB {
    const val SECCIONES = "secciones"
    const val SECCION_DETALLES = "seccion_detalles"
    const val ESTUDIANTES = "estudiantes"
    const val EVALUACIONES = "evaluaciones"
    const val NOTAS = "notas"
    const val OBSERVACIONES = "observaciones"
    const val ASISTENCIAS = "asistencias"
    const val ASISTENCIA_DATOS = "asistencia_datos"
    const val ENCUENTROS = "encuentros"
}
```

---

### 12. Lógica de "clase en curso" duplicada

La lógica para detectar si una clase está activa ahora existe en `SeccionAdapter.kt` (`esClaseAhora()`) y `CalendarActivity.kt` (`parseHoraToMinutes()`), con implementaciones distintas.

**Solución:** Crear una clase utilitaria `ScheduleUtils.kt` centralizada.

---

### 13. `Seccion.kt` es un archivo vacío (0 bytes)

El archivo existe pero está vacío. Es un residuo que genera confusión. Eliminarlo.

---

### 14. Ausencia de inyección de dependencias

`FirebaseDatabase.getInstance()` se llama directamente en cada Activity y Fragment, acoplando la UI con Firebase y haciendo imposible el testing unitario.

**Solución mínima:** Un repositorio singleton:
```kotlin
object GradeRepository {
    private val db = FirebaseDatabase.getInstance().reference
    fun getSeccionRef(nrc: String) = db.child(DB.SECCION_DETALLES).child(nrc)
}
```

---

## 🟢 Usabilidad

### 15. No hay indicador de carga al iniciar la app

Cuando `MainActivity` abre, la lista aparece vacía hasta que Firebase responde. No hay `ProgressBar` ni skeleton UI.

**Solución:** Mostrar un spinner central mientras llega el primer `onDataChange`, ocultarlo al recibir los datos.

---

### 16. El modo dictado no tiene instrucciones visibles

Cuando el usuario activa el dictado, solo cambia el color del botón. No hay guía sobre el formato esperado.

**Solución:** Un `Snackbar` o banner persistente durante el dictado:
> 🎙️ Dictando — Di: "[Nombre] [Nota]" — ej: "García 85"

---

### 17. No se puede editar el nombre de una evaluación

Una vez creada, solo se puede eliminar (perdiendo todas las notas). Si el docente escribe mal el nombre, no tiene solución sin perder datos.

**Solución:** Opción "Editar nombre" en el menú o long-press sobre la evaluación en el Spinner.

---

### 18. No hay confirmación al salir del modo dictado accidentalmente

Si el usuario presiona "Atrás" durante el dictado, este se detiene. Debería preguntar: _"¿Deseas detener el dictado?"_.

---

### 19. La búsqueda cruzada no tiene texto explicativo

Los botones de sección se ponen naranja cuando el estudiante existe en otra sección, pero no hay leyenda visible explicando esto.

**Solución:** Una pequeña nota bajo la barra de búsqueda cuando hay botones resaltados.

---

### 20. Los puntos extra no se distinguen en la vista acumulada

En la vista de acumulado, no es evidente qué parte del total corresponde a puntos extra.

**Solución:** En el total del item, mostrar un sub-texto como `"(incl. +2.5 extra)"` en verde.

---

## 🎨 Estética / UI

### 21. No hay soporte para modo oscuro (Dark Mode)

La app usa colores hardcoded como `Color.WHITE`, `Color.GRAY`, `Color.BLACK` directamente en el código Java/Kotlin. En modo oscuro del sistema, el texto puede quedar ilegible.

**Solución:**
- Definir todos los colores en `res/values/colors.xml` y `res/values-night/colors.xml`.
- Reemplazar `android.graphics.Color.*` en adapters por atributos de tema (`?attr/colorSurface`).

---

### 22. El diálogo de nueva evaluación es muy básico

El diálogo solo tiene dos `EditText` sin hints claros ni validación visual.

**Solución:** Usar `TextInputLayout` de Material Design:
- Contador de caracteres para el nombre
- Indicador de puntaje máximo sugerido
- Validación visual inline (borde rojo si el valor está vacío)

---

### 23. La `ActionBar` limita la personalización del header

No se puede agregar el logo de la UASD, tipografía institucional ni colores personalizados con la `ActionBar` por defecto.

**Solución:** Migrar a `MaterialToolbar` embebida en el layout, con control total sobre el diseño.

---

## ⚡ Rendimiento

### 24. Carga del modelo Vosk sin indicador de progreso

`StorageService.unpack()` puede tardar varios segundos. Si el usuario intenta dictar antes de que termine, solo ve un Toast.

**Solución:** Spinner animado en el botón de dictado durante la carga del modelo.

---

### 25. `CalendarActivity` escucha toda la rama `seccion_detalles/`

Cualquier cambio de nota en Firebase (aunque sea de otra sección) dispara una recarga completa del calendario, que solo necesita los horarios (que no cambian frecuentemente).

**Solución:** Usar `addListenerForSingleValueEvent` para los encuentros del calendario.

---

### 26. El RecyclerView de `MainActivity` se redibuja entero cada minuto

El handler de 60s llama `notifyDataSetChanged()` en toda la lista solo para actualizar el resaltado de la clase activa.

**Solución:** Calcular qué ítems cambiaron de estado activo/inactivo y solo notificar esos con `notifyItemChanged(position)`.

---

## ✨ Funcionalidades Nuevas Sugeridas

### 27. Exportación de notas a CSV

El docente podría exportar la planilla completa de una evaluación (nombre, matrícula, nota) como `.csv` para abrir en Excel. Firebase ya tiene los datos; solo falta el formateo y compartir el archivo via `FileProvider`.

### 28. Notificaciones de recordatorio de clase

`WorkManager` podría enviar una notificación 15 minutos antes de cada clase con el aula y nombre de la materia, calculado a partir de los encuentros en Firebase.

### 29. Historial de cambios de notas (Audit Log)

Guardar un timestamp y el valor anterior cada vez que se modifica una nota, para poder revertir cambios accidentales.

### 30. Buscador global de estudiantes

Un buscador que encuentre a un alumno en cualquier sección y muestre sus notas acumuladas en todas las materias, cruzando `estudiante_records`.

---

## Resumen de Prioridades

| Prioridad | Categoría | Ítem | Esfuerzo |
|---|---|---|---|
| 🔴 Crítico | Seguridad | #1 API Key expuesta en código | Bajo |
| 🔴 Crítico | Seguridad | #3 Reglas de Firebase | Bajo |
| 🔴 Crítico | Seguridad | #4 google-services.json en repo | Bajo |
| 🟠 Alto | Estabilidad | #5 Refactor StudentListFragment (MVVM) | Alto |
| 🟠 Alto | Estabilidad | #6 Listeners sin limpiar en Attendance | Bajo |
| 🟠 Alto | Estabilidad | #8 Coroutines sin ciclo de vida | Medio |
| 🟡 Medio | Arquitectura | #11 Constantes mágicas de Firebase | Bajo |
| 🟡 Medio | Arquitectura | #12 Lógica de horario duplicada | Bajo |
| 🟡 Medio | Arquitectura | #13 Archivo Seccion.kt vacío | Muy bajo |
| 🟢 Deseable | Usabilidad | #15 Indicador de carga inicial | Bajo |
| 🟢 Deseable | Usabilidad | #17 Editar nombre de evaluación | Medio |
| 🎨 Mejora | Estética | #21 Soporte modo oscuro | Medio |
| 🎨 Mejora | Estética | #22 TextInputLayout en diálogos | Bajo |
| ✨ Futuro | Feature | #27 Exportar notas a CSV | Medio |
| ✨ Futuro | Feature | #29 Historial de cambios | Medio |
