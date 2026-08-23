# EnergiAI

Solución inteligente para analizar el consumo eléctrico de una vivienda o pequeño establecimiento, clasificar su perfil energético (**Eficiente / Moderado / Ineficiente**), estimar el costo mensual y dar recomendaciones personalizadas para reducir el gasto.

Proyecto para el **Hackathon ONE** (Alura + Oracle) — equipo **G9 LATAM**.

---

## Tabla de contenido

- [Arquitectura](#arquitectura)
- [Stack tecnológico](#stack-tecnológico)
- [Funcionalidades](#funcionalidades)
- [API REST](#api-rest)
- [Ciencia de datos](#ciencia-de-datos)
- [Cómo correrlo en local](#cómo-correrlo-en-local)
- [Despliegue](#despliegue)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Pruebas automatizadas](#pruebas-automatizadas)
- [Limitaciones conocidas](#limitaciones-conocidas)

---

## Arquitectura

```
┌─────────────┐      HTTPS       ┌──────────────────┐      HTTPS       ┌────────────────────────┐
│   Angular    │ ───────────────▶│   Spring Boot     │ ───────────────▶│  Modelo (Random Forest)  │
│  (frontend)  │◀─────────────── │    (backend)      │◀─────────────── │  FastAPI en Colab + ngrok │
└─────────────┘   JSON + cookie  └──────────────────┘   JSON            └────────────────────────┘
                                          │
                                          ▼
                                  Historial en memoria
                                  (por sesión HTTP,
                                   sin base de datos)
```

- **Frontend (Angular 22, zoneless)**: formulario de análisis, panel de resultado, historial, simulador de ahorro, análisis por lotes (CSV/Excel) y descarga de reportes en Excel.
- **Backend (Spring Boot 4.1 / Java 21)**: valida la entrada, calcula el costo, arma las recomendaciones, guarda el historial de la sesión, y le pide la clasificación al modelo de Data — si el modelo no responde, cae automáticamente a un clasificador local de respaldo (mock por reglas), así la API nunca se cae por depender de un servicio externo.
- **Modelo de Data**: un `RandomForestRegressor` (scikit-learn) servido con FastAPI, corriendo en Google Colab y expuesto con ngrok. El backend le manda los mismos datos del formulario y compara el consumo real contra lo que el modelo predice para esa vivienda.
- **OCI**: el dataset de entrenamiento del modelo está almacenado en **OCI Object Storage**.
- **Despliegue de la app**: backend y frontend están desplegados en **Railway** (ver [Despliegue](#despliegue)).

No hay base de datos: el historial de análisis vive en memoria, atado a la cookie de sesión del navegador (`@SessionScope` de Spring) — cada quien ve solo lo que analizó en su propia sesión, y desaparece si borra las cookies o se reinicia el backend.

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Frontend | Angular 22 (standalone, zoneless), TypeScript, ExcelJS |
| Backend | Java 21, Spring Boot 4.1, Spring Web, Bean Validation, springdoc-openapi (Swagger) |
| Modelo | Python, scikit-learn (`RandomForestRegressor`), pandas, FastAPI, ngrok, Google Colab |
| Almacenamiento del dataset | OCI Object Storage |
| Despliegue | Railway (backend + frontend), Railpack como builder |
| Pruebas | JUnit 5 + Mockito (backend) |

## Funcionalidades

### Requisitos obligatorios del MVP

- [x] `POST /analisis-energetico` — análisis del perfil energético.
- [x] Clasificación en Eficiente / Moderado / Ineficiente, con probabilidad.
- [x] Recomendaciones de optimización, personalizadas según los datos del usuario.
- [x] Estimación del costo mensual (tarifa de referencia $0.75/kWh).
- [x] Validación de entrada y manejo de errores (400/404/405/500 con mensajes claros).
- [x] API documentada (Swagger/OpenAPI vía springdoc, en `/swagger-ui.html`).
- [x] Modelo entrenado, serializado (`joblib`) y cargado en producción.
- [x] Integración con OCI (Object Storage para el dataset de entrenamiento).
- [x] Más de tres ejemplos de uso reales/simulados (ver [Ciencia de datos](#ciencia-de-datos)).

### Recursos opcionales implementados

- [x] **Dashboard de seguimiento** — historial visual con mini-gráfico de barras por mes.
- [x] **Historial de análisis** — por sesión, sin necesidad de login ni base de datos.
- [x] **Procesamiento por lotes vía CSV/Excel** — sube un archivo con varios recibos, descarga una plantilla con diseño, y descarga el historial completo en Excel (con una pestaña re-subible directo al análisis por lotes).
- [x] **Pruebas automatizadas** — 11 pruebas unitarias en el backend (JUnit + Mockito).
- [x] **Alertas de alto consumo** — pantalla flotante automática cuando el resultado es "Ineficiente", con todas las recomendaciones.
- [x] **Comparación entre períodos** — "mejoraste/empeoraste" contra tu análisis anterior.
- [x] **Ranking de eficiencia** — tus mejores y peores análisis de la sesión.
- [x] **Simulación de escenarios de ahorro** — reduce tu uso en horario pico y/o cuántos aires acondicionados usarías, y ve el ahorro estimado antes de aplicarlo de verdad.

### Explícitamente fuera de alcance

- **Docker**: decisión explícita del equipo, no se contenerizó la aplicación.
- **Login/autenticación**: el historial es por sesión de navegador, no por usuario registrado.
- **Base de datos**: el historial vive en memoria por diseño (ver [Arquitectura](#arquitectura)).

## API REST 

Todos los endpoints devuelven JSON. Documentación interactiva completa en `/swagger-ui.html` una vez el backend está corriendo.

### `POST /analisis-energetico`


Analiza un perfil de consumo y devuelve su clasificación.

**Entrada:**
```json
{
  "consumo_kwh": 220,
  "uso_horario_pico_kwh": 30,
  "tamano_hogar": 4,
  "temperatura_promedio": 24,
  "refrigeradores": 2,
  "microondas": 1,
  "lavadoras": 2,
  "pantallas": 2,
  "aire_acondicionado": 1,
  "focos": 10,
  "mes": "Agosto",
  "anio": 2026
}
```

**Salida:**
```json
{
  "categoria": "Eficiente",
  "probabilidad": 0.7203,
  "costo_estimado_mensual": 165.0,
  "recomendaciones": [
    "Tu perfil es eficiente: mantén estos hábitos.",
    "Aire acondicionado (~78 kWh/mes, ~$58.50/mes, 33% de tu consumo en equipos): tu equipo con más peso en el consumo estimado. Súbele 1-2°C a la temperatura..."
  ],
  "fecha": "2026-08-21T06:57:10.36",
  "consumo_kwh": 220.0
}
```

`mes` y `anio` son opcionales — solo sirven para identificar el análisis en el historial y en las descargas.

### `POST /analisis-energetico/lote`

Igual que el anterior, pero para varias viviendas a la vez (`{ "analisis": [ {...}, {...} ] }`). Pensado para cuando el frontend sube un CSV o Excel.

### `GET /analisis-energetico`

Devuelve el historial de análisis de la sesión actual (identificada por cookie), más reciente primero.

### `DELETE /analisis-energetico`

Borra el historial de la sesión actual.

### `GET /estado`

Endpoint de salud — confirma que la API está arriba.

## Ciencia de datos

El modelo (`RandomForestRegressor`) predice el consumo diario esperado de una vivienda a partir de: tamaño del hogar, temperatura promedio, uso en horario pico, y la cantidad de cada tipo de electrodoméstico. El backend compara ese consumo esperado contra el consumo real declarado por el usuario para decidir la categoría (dentro de un margen = Moderado, muy por debajo = Eficiente, muy por encima = Ineficiente).

**Hallazgo relevante durante el desarrollo — fuga de datos:** la primera versión del dataset generaba las cantidades de electrodomésticos *a partir* del consumo real (para que ese consumo tuviera un ~70% "explicado" por los equipos). El modelo entrenado con esos datos terminaba dependiendo casi por completo de una sola variable (`Peak_Hours_Usage_kWh`, con ~97% de la importancia), y clasificaba casi cualquier combinación libre de equipos como "Ineficiente", sin importar qué tan eficiente fuera en realidad.

**Solución:** se regeneró el dataset de entrenamiento de abajo hacia arriba — el consumo total se calcula a partir de coeficientes de consumo reales por tipo de equipo (refrigerador, microondas, lavadora, pantalla, aire acondicionado, foco), en vez de al revés. Con el modelo reentrenado, la importancia de variables quedó repartida de forma realista (aire acondicionado y refrigeradores como los que más pesan, consistente con la vida real), y validado con más de una decena de casos de prueba donde solo cambia el consumo real declarado, manteniendo el mismo equipo — el modelo distingue correctamente entre Eficiente, Moderado e Ineficiente.

Métricas del modelo reentrenado: MAE ≈ 0.83 kWh, RMSE ≈ 1.05 kWh, R² ≈ 0.95 (sobre un conjunto de prueba separado del de entrenamiento).

## Cómo correrlo en local

### Backend

```bash
cd backend
./mvnw spring-boot:run
```
Corre en `http://localhost:8080`. Swagger en `http://localhost:8080/swagger-ui.html`.

### Frontend

```bash
cd frontend
npm install
npm start
```
Corre en `http://localhost:4200`.

Por defecto, el frontend en desarrollo apunta a `http://localhost:8080` y el backend solo acepta peticiones desde `http://localhost:4200` (CORS). Si el modelo de Data no está disponible (Colab apagado), el backend cae automáticamente a un clasificador local de respaldo — la app nunca deja de funcionar.

## Despliegue

Backend y frontend están desplegados en [Railway](https://railway.app):

- **Backend:** `https://g9-latam-team-75-production.up.railway.app`
- **Frontend:** `https://scintillating-bravery-production-abf8.up.railway.app`

Variables de entorno que usa el backend en producción (todas opcionales, con valores por defecto para desarrollo local):

| Variable | Para qué |
|---|---|
| `PORT` | Puerto del servidor (lo asigna Railway automáticamente) |
| `DATA_MODELO_URL` | URL del microservicio del modelo (Colab + ngrok) |
| `CORS_ALLOWED_ORIGIN` | Origen permitido para CORS (la URL del frontend desplegado) |
| `COOKIE_SAME_SITE` / `COOKIE_SECURE` | Atributos de la cookie de sesión, necesarios para que funcione entre dominios distintos de Railway |

## Estructura del proyecto

```
EnergiAI/
├── backend/            # API REST (Spring Boot)
│   └── src/main/java/EnergiAI/
│       ├── controller/  # Endpoints REST
│       ├── service/     # Lógica de negocio (clasificación, recomendaciones, costo)
│       ├── client/      # Cliente HTTP hacia el modelo de Data
│       ├── dto/         # Request/Response
│       ├── session/     # Historial en memoria por sesión
│       ├── config/      # CORS
│       └── exception/   # Manejo centralizado de errores
└── frontend/            # SPA (Angular)
    └── src/app/
        ├── app.ts        # Componente principal (formulario, historial, lotes, simulador)
        ├── app.html
        └── app.css
```

## Pruebas automatizadas

```bash
cd backend
./mvnw test
```

11 pruebas (JUnit 5 + Mockito) cubriendo: clasificación por reglas (mock) en sus tres categorías, cálculo de costo, precedencia del modelo real sobre el mock, origen de las recomendaciones, y que las simulaciones no ensucien el historial real.

## Limitaciones conocidas

- El historial no persiste entre reinicios del backend (vive en memoria, por diseño — ver [Arquitectura](#arquitectura)).
- El modelo depende de que la sesión de Colab esté activa para dar predicciones reales; si no lo está, el backend usa un clasificador de respaldo basado en reglas (menos preciso, pero la app sigue funcionando).
- El anillo de confianza del resultado en el frontend está fijo en 98% (decisión explícita del equipo para la demo, no representa la probabilidad real del modelo).
