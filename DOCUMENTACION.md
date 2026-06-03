# Documentación de la Aplicación UASD Android

## Descripción General

**UASD Assistant** es una aplicación Android diseñada para docentes de la Universidad Autónoma de Santo Domingo (UASD). Permite gestionar secciones de clases, registrar notas de evaluaciones y controlar la asistencia de estudiantes, todo sincronizado en tiempo real con Firebase Realtime Database.

### Tecnologías Utilizadas

| Tecnología | Uso |
|---|---|
| **Kotlin** | Lenguaje principal |
| **Firebase Realtime Database** | Base de datos en tiempo real |
| **Firebase Auth** | Autenticación anónima |
| **Vosk (offline)** | Reconocimiento de voz sin conexión |
| **Android Speech API** | Reconocimiento de voz nativo (online) |
| **Gemini AI API** | Procesamiento de audio con IA |
| **Google Drive API** | Subida de archivos de audio |
| **Google Apps Script** | Procesamiento backend de audio |
| **Material Design 3** | Componentes UI |
| **Kotlin Coroutines** | Operaciones asíncronas |

### Permisos Requeridos

- `INTERNET` — Comunicación con Firebase, Gemini y Google Drive
- `RECORD_AUDIO` — Dictado de notas por voz
- `GET_ACCOUNTS` — Autenticación con Google para Drive
- `READ/WRITE_EXTERNAL_STORAGE` — Almacenamiento en Android ≤ 12

---

## Estructura de la Base de Datos (Firebase)

```
root/
├── secciones/
│   └── {nrc}: Seccion { nrc, codigoMateria, nombreMateria, claveSeccion, creditos, periodo, conextras, conparticipacion, linkLista, updatedAt }
│
└── seccion_detalles/
    └── {nrc}/
        ├── encuentros/       → Lista de horarios (hora, dias, aula)
        ├── estudiantes/      → Lista de Estudiante { matricula, nombre, id_estudiante }
        ├── evaluaciones/     → Lista de Evaluacion { id, nombre, valor, esExtra }
        ├── notas/
        │   └── {evalId}/
        │       └── {matricula}: { nota: Double, updatedAt: Long }  (legacy: solo Double)
        ├── observaciones/
        │   └── {matricula}: String
        ├── asistencias/      → Lista de AsistenciaSesion { id, nombre, fecha }
        └── asistencia_datos/
            └── {sesionId}/
                └── {matricula}: Boolean

estudiante_records/
└── {matricula}/
    └── {codigoMateria}/
        └── {evalId}: { nombre, nota, updatedAt }
```

---

## Flujo de Navegación

```
MainActivity (Lista de Secciones)
├── [Clic en Sección] → StudentListActivity + StudentListFragment (Gestión de notas)
│                          ├── [FAB] → AttendanceActivity (Control de asistencia)
│                          ├── [FAB] → Nueva Evaluación (diálogo)
│                          └── [FAB] → Puntos Extra
└── [FAB Calendario] → CalendarActivity (Vista semanal de clases)
                          └── [Clic en clase] → StudentListActivity
```

---

## Módulos / Archivos Principales

### 1. `Models.kt` — Modelos de Datos

Contiene todos los *data classes* que representan las entidades del dominio:

| Clase | Descripción |
|---|---|
| `Seccion` | Sección de clase: NRC, materia, clave, período, horarios |
| `Estudiante` | Alumno: matrícula, nombre, ID |
| `Encuentro` | Horario de clase: hora, días, aula |
| `Evaluacion` | Actividad evaluativa: nombre, puntaje, si es extra |
| `GradableEstudiante` | Vista de un estudiante con su nota (para la UI) |
| `AsistenciaSesion` | Sesión de asistencia: ID, nombre, fecha |
| `SeccionEncuentro` | Combinación de sección + horario (para calendario) |
| `Materia` | Materia con su ID de libro en Google |

---

### 2. `MainActivity.kt` — Pantalla Principal

**Propósito:** Punto de entrada de la aplicación. Muestra la lista de todas las secciones del docente.

**Funcionamiento:**
1. Al iniciar, autentica al usuario de forma **anónima** en Firebase Auth.
2. Escucha en tiempo real el nodo `secciones/` en Firebase y carga los horarios (`encuentros`) de `seccion_detalles/`.
3. Presenta la lista en un `RecyclerView` usando `SeccionAdapter`, ordenada alfabéticamente por nombre de materia.
4. Un handler actualiza la UI cada **60 segundos** para refrescar el resaltado de la clase activa en ese momento.
5. El clic en una sección navega a `StudentListActivity`, pasando todas las secciones de la misma materia (para poder cambiar entre secciones sin salir).
6. Un **FloatingActionButton** (ícono de calendario) navega a `CalendarActivity`.

**Comportamiento especial:**
- Si hay una clase en curso (según horario), esa sección se resalta en color diferente gracias al `SeccionAdapter`.

---

### 3. `SeccionAdapter.kt` — Adaptador de Secciones

**Propósito:** Renderiza cada sección en la lista principal.

**Características:**
- Muestra: nombre de materia, NRC y clave de sección.
- Implementa **lógica de resaltado activo**: compara la hora actual del dispositivo con los horarios de `Encuentro` de cada sección. Si la clase está en curso, pinta el fondo con `uasd_active_highlight`.
- Convierte horarios en formato `h:mm a` (12h) a formato 24h para la comparación.
- Aplica colores alternos (zebra) para filas pares e impares.

---

### 4. `StudentListActivity.kt` — Actividad de Lista de Estudiantes

**Propósito:** Contenedor de `StudentListFragment`. Gestiona la navegación entre múltiples secciones de la misma materia.

**Funcionamiento:**
1. Recibe la lista de secciones de la misma materia (pasada como `Serializable`).
2. Muestra botones de selección de sección (por `claveSeccion`) en una barra horizontal.
3. Al cambiar de sección, carga un nuevo `StudentListFragment` manteniendo el estado previo (evaluación seleccionada, modo dictado, texto de búsqueda).
4. Delega las opciones del menú al fragmento activo: eliminar evaluación, ver estadísticas, configurar dictado.

**Búsqueda cruzada de secciones:**
- Implementa `OnSearchListener`: cuando el fragmento busca un nombre y no encuentra resultados en la sección actual, consulta Firebase en las **otras secciones** de la misma materia y resalta (en naranja) los botones donde sí se encuentra el estudiante.

---

### 5. `StudentListFragment.kt` — Fragmento Principal de Gestión de Notas ⭐

**El módulo más complejo de la aplicación (~1700 líneas).** Gestiona toda la funcionalidad de notas, evaluaciones y dictado de voz.

#### 5.1 Carga de Datos

Escucha en tiempo real 4 nodos de Firebase simultáneamente para la sección activa:
- **Estudiantes** → lista base de alumnos
- **Evaluaciones** → popula el `Spinner` de selección
- **Notas** → mapa `evalId → { matricula → Double }`
- **Observaciones** → notas adhesivas por alumno

#### 5.2 Vista de Notas

- **Spinner de Evaluaciones**: permite seleccionar qué evaluación visualizar. La primera opción es siempre `"--- Acumulado Total ---"`.
- **Vista Acumulado**: suma todas las notas de todas las evaluaciones por alumno.
- **Vista por Evaluación**: muestra la nota individual de cada alumno.
- **Búsqueda**: filtra alumnos por nombre o matrícula en tiempo real.
- Clic en la nota → diálogo para editar. Clic en ícono de nota adhesiva → diálogo de observación.

#### 5.3 Gestión de Evaluaciones

| Acción | Descripción |
|---|---|
| **Nueva Evaluación** (FAB) | Crea una evaluación con nombre y puntaje. Opcionalmente la replica en todas las secciones de la misma materia. |
| **Eliminar Evaluación** (menú) | Borra la evaluación y todas sus notas de Firebase. |
| **Puntos Extra** (FAB) | Crea o navega a una evaluación especial de tipo "extra". Las notas extra se **suman** (no reemplazan). |
| **Estadísticas** (menú) | Muestra: cantidad evaluada, promedio, desviación, máximo y mínimo. |

#### 5.4 Sistema de Dictado de Notas por Voz 🎤

El fragmento soporta **3 motores de reconocimiento de voz**, seleccionables desde el menú de configuración:

##### Motor 1: Vosk (Offline)
- Carga el modelo de español (`model-es`) desde los assets de la app.
- Usa una gramática dinámica que incluye los nombres y matrículas de los alumnos + vocabulario numérico.
- Opera completamente sin conexión a internet.
- Más preciso para nombres en español dominicano.

##### Motor 2: Android Nativo (Online)
- Usa `SpeechRecognizer` de Android con el servicio de reconocimiento del sistema.
- Configura el idioma en `es-US`.
- Más rápido de iniciar; requiere conexión.
- Se reconecta automáticamente al detectar silencio o error de timeout.

##### Motor 3: Gemini AI (Hold-to-Talk)
- Graba audio con `AudioRecorder` (formato M4A/AAC) mientras el usuario mantiene presionado el botón.
- Al soltar, envía el audio a **Gemini Flash** (`gemini-flash-latest`) con un prompt que incluye la lista completa de alumnos.
- Gemini responde con un JSON `{ updates: [{id, grade}], errors: [...] }`.
- Las notas identificadas se guardan automáticamente en Firebase.

##### Procesamiento de Texto de Dictado (`procesarTextoDictado`)
- Detecta patrones `[NOMBRE] [NOTA]` en el texto reconocido usando expresiones regulares.
- Convierte números en palabras ("veinte", "cinco") a valores numéricos.
- Usa `FuzzyMatcher` para identificar al estudiante correcto incluso con pronunciación imprecisa.
- Evita guardar la misma nota dos veces en la misma sesión (`savedMatchesThisSession`).
- Emite sonido de éxito (beep) al guardar y sonido de error (alarma) cuando no hay coincidencia.
- Al guardar una nota, **desplaza** la lista hacia el alumno y lo **resalta** brevemente en cyan.

---

### 6. `EstudianteAdapter.kt` — Adaptador de Estudiantes

**Propósito:** Renderiza cada alumno en la lista de notas.

**Características:**
- Muestra: matrícula, nombre, nota (o `"-"` si no hay), ícono de observación.
- Si el alumno tiene una observación, muestra un **ícono morado** y el texto de la nota adhesiva.
- Resalte temporal: el alumno cuya matrícula coincida con `highlightedStudentId` se pinta en cyan claro (usado durante el dictado).
- En modo acumulado, la nota se muestra en color morado; en modo normal, en azul UASD.
- Colores zebra alternos en filas.

---

### 7. `AttendanceActivity.kt` — Control de Asistencia

**Propósito:** Registrar y consultar la asistencia de estudiantes por sesión.

**Funcionamiento:**
1. Muestra un `Spinner` con las sesiones creadas. La primera opción es siempre `"--- Resumen de Asistencias ---"`.
2. **Vista de sesión individual**: lista a todos los alumnos con un toggle de presencia (checkbox/switch). Al cambiar el estado, se guarda inmediatamente en Firebase en `asistencia_datos/{sesionId}/{matricula}`.
3. **Vista resumen**: muestra `presentes / total_sesiones` por alumno, acumulando todas las sesiones.
4. Un FAB permite crear nuevas sesiones (con nombre opcional y fecha automática del día).
5. Un ítem del menú permite **eliminar** la sesión seleccionada y sus datos de asistencia.

**Estructura en Firebase:**
- `asistencias/{id}` → metadatos de la sesión (nombre, fecha)
- `asistencia_datos/{sesionId}/{matricula}` → `true` si el alumno estuvo presente

---

### 8. `CalendarActivity.kt` — Calendario Docente

**Propósito:** Vista semanal de todas las clases del docente, organizadas por día.

**Funcionamiento:**
1. Muestra botones de días de la semana (L, M, I, J, V, S, D). Al iniciar, selecciona automáticamente el día actual.
2. Carga todas las secciones y sus encuentros (horarios) desde Firebase.
3. Filtra los encuentros del día seleccionado y los muestra ordenados cronológicamente.
4. Implementa lógica de parseo de horarios en formato mixto (`"7:00 PM - 9:50 PM"`, `"1:00 - 3:50 PM"`) convirtiéndolos a minutos para el ordenamiento.
5. El clic en una clase navega directamente a `StudentListActivity` de esa materia.

---

### 9. `FuzzyMatcher.kt` — Comparación Difusa de Nombres

**Propósito:** Identificar a qué estudiante corresponde un nombre dictado por voz, aun cuando la pronunciación o transcripción sea imprecisa.

**Algoritmos implementados:**

| Modo | Algoritmo | Uso |
|---|---|---|
| `"name"` | **Jaro-Winkler** + tokenización | Comparar nombres completos |
| `"id"` | **Levenshtein** | Comparar matrículas |

**Proceso de búsqueda de nombre:**
1. Normaliza texto (elimina tildes, comas, convierte a minúsculas).
2. Descompone en tokens (palabras).
3. Para cada token del query, busca el mejor token coincidente en el candidato usando Jaro-Winkler (umbral > 0.85).
4. Calcula precisión y recall de los tokens → F1-score como puntuación final.
5. Retorna el índice del mejor candidato, su puntuación de coincidencia, y la diferencia con el segundo mejor (nivel de discriminación).

**Criterios de aceptación en el dictado:**
- Nombre: coincidencia ≥ 50%
- Matrícula: coincidencia ≥ 80%

---

### 10. `AudioRecorder.kt` — Grabador de Audio

**Propósito:** Utilitario simple para grabar audio del micrófono en formato M4A (AAC).

- Usa `MediaRecorder` con fuente `MIC`, formato `MPEG_4` y encoder `AAC`.
- Guarda el archivo en el directorio de caché de la app (`cacheDir`).
- Métodos: `startRecording(fileName)` y `stopRecording()` → retorna el `File`.
- Usado exclusivamente por el motor de dictado Gemini.

---

### 11. `DriveServiceHelper.kt` — Integración con Google Drive

**Propósito:** Subir archivos de audio a Google Drive para procesamiento por Apps Script (flujo legacy).

**Operaciones:**
- `createFolderIfNotExist(folderName)`: Busca o crea la carpeta `"UASD_Audios_Notas"` en el Drive del usuario.
- `uploadFile(file, mimeType, folderId)`: Sube el archivo al Drive y retorna su ID.
- Usa OAuth2 con el scope `DRIVE_FILE`.

> **Nota:** Este flujo (Drive + Apps Script) es un método alternativo/legacy de dictado. El método principal actual usa Gemini directamente desde el dispositivo.

---

### 12. Adaptadores Secundarios

#### `AttendanceAdapter.kt`
- Renderiza la lista de alumnos en la pantalla de asistencia.
- Cada ítem muestra nombre + matrícula, con un switch/checkbox de asistencia.
- En modo resumen, muestra el texto `"presentes / total"` en lugar del toggle.

#### `EncuentroAdapter.kt`
- Renderiza los encuentros/horarios en el `CalendarActivity`.
- Muestra: nombre de materia, hora, aula y días de clase.
- El clic navega a la lista de estudiantes de esa sección.

---

## Diagrama de Componentes

```
┌─────────────────────────────────────────────────────────┐
│                    UASD Assistant                        │
├─────────────┬───────────────────┬───────────────────────┤
│ MainActivity│StudentListActivity│   CalendarActivity     │
│  (Secciones)│  + Fragment       │   (Vista semanal)      │
│             │                   │                        │
│ SeccionAdap │ EstudianteAdapter │   EncuentroAdapter     │
├─────────────┴───────────────────┴───────────────────────┤
│              AttendanceActivity                          │
│              AttendanceAdapter                           │
├─────────────────────────────────────────────────────────┤
│         SERVICIOS TRANSVERSALES                          │
│  FuzzyMatcher │ AudioRecorder │ DriveServiceHelper       │
│  Vosk SDK     │ Android STT   │ Gemini AI                │
├─────────────────────────────────────────────────────────┤
│         Firebase Realtime Database                       │
└─────────────────────────────────────────────────────────┘
```

---

## Notas de Implementación

### Persistencia del Estado
- `StudentListActivity` guarda el índice de la sección activa en `onSaveInstanceState`.
- `StudentListFragment` persiste la búsqueda actual y la evaluación seleccionada entre rotaciones de pantalla.
- Al cambiar de sección dentro de `StudentListActivity`, se transfiere el nombre de la evaluación seleccionada y el estado del dictado al nuevo fragmento.

### Sincronización en Tiempo Real
- Todos los listeners de Firebase son `ValueEventListener` persistentes (no `addListenerForSingleValueEvent`), salvo en `CalendarActivity` y en la creación de evaluaciones en otras secciones.
- Los listeners se eliminan en `onDestroyView` del fragmento para evitar fugas de memoria.

### Dictado de Voz - Consideraciones
- Vosk carga el modelo de español desde los assets al iniciar el fragmento si el modo seleccionado es Vosk.
- El modelo ocupa espacio considerable en los assets (`model-es/`).
- El modo Gemini impone un mínimo de 500ms de grabación para evitar gastar cuota con grabaciones accidentales.
- La gramática dinámica de Vosk incluye todos los nombres y matrículas de la sección activa, mejorando la precisión.

### Puntos Extra
- Las evaluaciones extra (`esExtra == 1`) se acumulan: cada dictado o entrada manual **suma** al total existente.
- Para corregir (sobrescribir) un valor de puntos extra, existe el botón "Corregir" en el diálogo.
- La app recuerda el último valor de puntos extra ingresado manualmente (`SharedPreferences`) para agilizar la entrada repetitiva.
