2. Infraestructura de Firebase

Firestore: * Colección users: {uid, email, settings}
Colección data: {id, ownerId, payload, timestamp}
Auth: Login con Google. El uid es la clave de enlace entre todas las apps.
Esquema de Realtime Database (Simplificado)
/asignaciones/{nrc}: Mapeo de códigos de sección con metadatos generales.
nombreMateria: Nombre descriptivo (ej: "Fisica Basica").
periodo: Ciclo lectivo (ej: "Primer Semestre 2026").
updatedAt: Timestamp de la última actualización.
/materias/{codigoMateria}: Catálogo de asignaturas.
nombre: Nombre oficial de la materia.
idLibroGoogle: Referencia a recursos externos.
/seccion_detalles/{nrc}: Información profunda de cada sección.
/encuentros/{eID}: Horarios y ubicación.
aula, dias, hora.
/estudiantes/{matricula}: Listado de alumnos inscritos.
id_estudiante, matricula, nombre.