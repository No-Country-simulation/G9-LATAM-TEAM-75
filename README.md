<div align="center">

# ⚡ EnergiAI

**Analiza tu consumo eléctrico, descubre qué tan eficiente eres, y recibe recomendaciones reales para ahorrar.**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](backend)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)](backend)
[![Angular](https://img.shields.io/badge/Angular-22-DD0031?logo=angular&logoColor=white)](frontend)
[![Python](https://img.shields.io/badge/Python-scikit--learn-3776AB?logo=python&logoColor=white)](model)
[![FastAPI](https://img.shields.io/badge/FastAPI-model%20server-009688?logo=fastapi&logoColor=white)](model)
[![OCI](https://img.shields.io/badge/OCI-Object%20Storage-F80000?logo=oracle&logoColor=white)](#ciencia-de-datos)
[![Railway](https://img.shields.io/badge/Deploy-Railway-0B0D0E?logo=railway&logoColor=white)](#despliegue)

Proyecto para el **Hackathon ONE** (Alura + Oracle) — equipo **G9 LATAM**

</div>

---

## Tabla de contenido

- [Arquitectura](#arquitectura)
- [Stack tecnológico](#stack-tecnológico)
- [Funcionalidades](#funcionalidades)
- [API REST](#api-rest)
- [Ejemplos de uso](#ejemplos-de-uso)
- [Ciencia de datos](#ciencia-de-datos)
- [Cómo correrlo en local](#cómo-correrlo-en-local)
- [Despliegue](#despliegue)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Pruebas automatizadas](#pruebas-automatizadas)

---

## 🏗️ Arquitectura

```
┌─────────────┐      HTTPS       ┌──────────────────┐      HTTPS       ┌──────────────────────┐
│   Angular    │ ───────────────▶│   Spring Boot     │ ───────────────▶│ Modelo (Random Forest) │
│  (frontend)  │◀─────────────── │    (backend)      │◀─────────────── │  FastAPI (servicio en   │
└─────────────┘   JSON + cookie  └──────────────────┘   JSON            │  Railway, model/)      │
                                          │                             └──────────────────────┘
                                          ▼
                                  Historial en memoria
                                  (por sesión HTTP,
                                   sin base de datos)
```

Los tres (frontend, backend y modelo) están desplegados como servicios independientes en Railway. El modelo también se puede correr en Google Colab + ngrok para pruebas rápidas sin desplegar nada.

| Componente | Responsabilidad |
|---|---|
| 🖥️ **Frontend** (Angular 22, zoneless) | Formulario de análisis, panel de resultado, historial, simulador de ahorro, análisis por lotes (CSV/Excel) y descarga de reportes en Excel. |
| ⚙️ **Backend** (Spring Boot 4.1 / Java 21) | Valida la entrada, calcula el costo, arma las recomendaciones, guarda el historial de la sesión, y le pide la clasificación al modelo — si no responde, cae automáticamente a un clasificador local de respaldo, así la API nunca se cae por depender de un servicio externo. |
| 🧠 **Modelo** (Python / scikit-learn) | Un `RandomForestRegressor` servido con FastAPI (código en [`model/`](model/)). Compara el consumo real declarado contra lo que predice para esa vivienda. |
| ☁️ **OCI** | El dataset de entrenamiento (`dataset_consumo.csv`) vive en un bucket de **OCI Object Storage** (`energiai-dataset`); `model/entrenamiento.py` lo lee directo de ahí. |

> No hay base de datos: el historial de análisis vive en memoria, atado a la cookie de sesión del navegador (`@SessionScope` de Spring) — cada quien ve solo lo que analizó en su propia sesión, y desaparece si borra las cookies o se reinicia el backend.

## 🧰 Stack tecnológico

| Capa | Tecnología |
|---|---|
| Frontend | Angular 22 (standalone, zoneless), TypeScript, ExcelJS |
| Backend | Java 21, Spring Boot 4.1, Spring Web, Bean Validation, springdoc-openapi (Swagger) |
| Modelo | Python, scikit-learn (`RandomForestRegressor`), pandas, FastAPI |
| Almacenamiento del dataset | OCI Object Storage |
| Despliegue | Railway (3 servicios independientes), Railpack como builder |
| Pruebas | JUnit 5 + Mockito (backend) |

## ✅ Funcionalidades

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

## 🔌 API REST

Todos los endpoints devuelven JSON. Documentación interactiva completa en `/swagger-ui.html` una vez el backend está corriendo.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/analisis-energetico` | Analiza un perfil de consumo y devuelve su clasificación |
| `POST` | `/analisis-energetico/lote` | Igual, pero para varias viviendas de un jalón |
| `GET` | `/analisis-energetico` | Consulta el historial de la sesión actual |
| `DELETE` | `/analisis-energetico` | Borra el historial de la sesión actual |
| `GET` | `/estado` | Endpoint de salud |

---

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

`mes` y `anio` son opcionales — solo sirven para identificar el análisis en el historial y en las descargas.

**Salida (`200 OK`):**
```json
{
  "categoria": "Eficiente",
  "probabilidad": 0.7203,
  "costo_estimado_mensual": 165.0,
  "consumo_kwh": 220.0,
  "fecha": "2026-08-21T06:57:10.36",
  "recomendaciones": [
    "Tu perfil es eficiente: mantén estos hábitos.",
    "Aire acondicionado (~78 kWh/mes, ~$58.50/mes, 33% de tu consumo en equipos): tu equipo con más peso en el consumo estimado. Súbele 1-2°C a la temperatura..."
  ]
}
```

---

### `POST /analisis-energetico/lote`

Analiza varias viviendas a la vez — pensado para cuando el frontend sube un CSV o Excel con un recibo por fila.

**Entrada:**
```json
{
  "analisis": [
    { "consumo_kwh": 220, "uso_horario_pico_kwh": 30, "tamano_hogar": 4, "temperatura_promedio": 24, "refrigeradores": 2, "microondas": 1, "lavadoras": 2, "pantallas": 2, "aire_acondicionado": 1, "focos": 10, "mes": "Enero" },
    { "consumo_kwh": 450, "uso_horario_pico_kwh": 150, "tamano_hogar": 4, "temperatura_promedio": 24, "refrigeradores": 2, "microondas": 1, "lavadoras": 2, "pantallas": 2, "aire_acondicionado": 1, "focos": 10, "mes": "Febrero" }
  ]
}
```

**Salida (`200 OK`):** un arreglo con un resultado por cada fila, en el mismo orden, con la misma forma que `POST /analisis-energetico`.

---

### `GET /analisis-energetico`

Devuelve el historial de análisis de la sesión actual (identificada por cookie), más reciente primero.

**Salida (`200 OK`):**
```json
[
  {
    "categoria": "Eficiente",
    "probabilidad": 0.7203,
    "costo_estimado_mensual": 165.0,
    "consumo_kwh": 220.0,
    "mes": "Agosto",
    "anio": 2026,
    "fecha": "2026-08-21T06:57:10.36",
    "recomendaciones": ["..."]
  }
]
```

---

### `DELETE /analisis-energetico`

Borra el historial de la sesión actual. Responde `200 OK` sin cuerpo.

---

### `GET /estado`

Endpoint de salud.

**Salida (`200 OK`, texto plano):**
```
API de análisis energético funcionando correctamente
```

---

### Errores

Todos los errores de la API responden con el mismo formato:

```json
{
  "codigo": 400,
  "mensaje": "Datos inválidos: consumo_kwh es obligatorio; "
}
```

| Código | Cuándo ocurre |
|---|---|
| `400` | Datos inválidos (falta un campo, valor fuera de rango) o JSON mal formado |
| `404` | Ruta que no existe |
| `405` | Método HTTP no soportado en esa ruta |
| `500` | Error interno no previsto (se registra en el log del servidor, nunca se expone el detalle) |

## 📋 Ejemplos de uso

Tres casos reales, probados directamente contra la API desplegada en Railway — uno por cada categoría posible.

<details>
<summary><b>Caso 1 — Eficiente</b> (equipos de alto consumo, pero uso real bajo)</summary>

**Entrada:**
```json
{
  "consumo_kwh": 260, "uso_horario_pico_kwh": 40, "tamano_hogar": 4,
  "temperatura_promedio": 30, "refrigeradores": 2, "microondas": 1,
  "lavadoras": 2, "pantallas": 3, "aire_acondicionado": 2, "focos": 15,
  "mes": "Junio", "anio": 2026
}
```
**Salida:**
```json
{ "categoria": "Eficiente", "probabilidad": 0.5741, "costo_estimado_mensual": 195.0, "consumo_kwh": 260.0 }
```
</details>

<details>
<summary><b>Caso 2 — Moderado</b> (consumo dentro de lo esperado para sus equipos)</summary>

**Entrada:**
```json
{
  "consumo_kwh": 420, "uso_horario_pico_kwh": 150, "tamano_hogar": 4,
  "temperatura_promedio": 30, "refrigeradores": 2, "microondas": 1,
  "lavadoras": 2, "pantallas": 3, "aire_acondicionado": 2, "focos": 15,
  "mes": "Julio", "anio": 2026
}
```
**Salida:**
```json
{ "categoria": "Moderado", "probabilidad": 0.9309, "costo_estimado_mensual": 315.0, "consumo_kwh": 420.0 }
```
</details>

<details>
<summary><b>Caso 3 — Ineficiente</b> (pocos equipos, pero consumo real muy por encima de lo esperado)</summary>

**Entrada:**
```json
{
  "consumo_kwh": 180, "uso_horario_pico_kwh": 20, "tamano_hogar": 2,
  "temperatura_promedio": 22, "refrigeradores": 1, "microondas": 1,
  "lavadoras": 1, "pantallas": 1, "aire_acondicionado": 0, "focos": 6,
  "mes": "Marzo", "anio": 2026
}
```
**Salida:**
```json
{ "categoria": "Ineficiente", "probabilidad": 0.5481, "costo_estimado_mensual": 135.0, "consumo_kwh": 180.0 }
```
</details>

## 🔬 Ciencia de datos

El modelo (`RandomForestRegressor`) predice el consumo diario esperado de una vivienda a partir de: tamaño del hogar, temperatura promedio, uso en horario pico, y la cantidad de cada tipo de electrodoméstico. El backend compara ese consumo esperado contra el consumo real declarado por el usuario para decidir la categoría (dentro de un margen = Moderado, muy por debajo = Eficiente, muy por encima = Ineficiente).

**Dataset:** generado de abajo hacia arriba — el consumo total (`Energy_Consumption_kWh`) se calcula a partir de coeficientes de consumo reales por tipo de equipo (refrigerador, microondas, lavadora, pantalla, aire acondicionado, foco), cada uno con su aporte diario típico en kWh. El uso en horario pico (`Peak_Hours_Usage_kWh`) se genera de forma independiente al consumo total, y la probabilidad de tener aire acondicionado aumenta con la temperatura promedio de la zona (reflejando un patrón real de clima). Con esta metodología, la importancia de variables del modelo queda repartida de forma realista (aire acondicionado y refrigeradores como los que más pesan, consistente con la vida real), validado con más de una decena de casos de prueba donde solo cambia el consumo real declarado, manteniendo el mismo equipo — el modelo distingue correctamente entre Eficiente, Moderado e Ineficiente.

**Métricas del modelo** (sobre un conjunto de prueba separado del de entrenamiento):

| Métrica | Valor |
|---|---|
| MAE | ≈ 0.83 kWh |
| RMSE | ≈ 1.05 kWh |
| R² | ≈ 0.95 |

**Notebook completo** ([`model/analisis_eda.ipynb`](model/analisis_eda.ipynb)): exploración y limpieza de datos (EDA), análisis de patrones de consumo (correlaciones, distribuciones, relación temperatura/aire acondicionado), transformación de variables, entrenamiento del modelo, evaluación, la lógica de clasificación y recomendaciones basadas en reglas, y la serialización del modelo — todo ejecutado, con las gráficas y salidas ya generadas. También disponible en [Google Colab](https://colab.research.google.com/drive/19PO42IJJcCgwAnGrDVDig15iMXns-kyr?usp=sharing).

**Scripts (carpeta [`model/`](model/)):**

| Archivo | Qué hace |
|---|---|
| `analisis_eda.ipynb` | Notebook con el flujo completo de Ciencia de Datos (EDA, patrones, entrenamiento, evaluación, recomendaciones, serialización) |
| `generar_dataset.py` | Genera `dataset_consumo.csv` y se sube a OCI Object Storage |
| `entrenamiento.py` | Lee el dataset directo desde OCI, entrena el `RandomForestRegressor`, evalúa (MAE/RMSE/R²), imprime la importancia de variables, y serializa el modelo con `joblib` (`random_forest_consumo.pkl`) |
| `servidor.py` | Expone el modelo entrenado como API (`POST /predecir`) con FastAPI, listo para correr en Colab o como servicio persistente |

## 💻 Cómo correrlo en local

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

Por defecto, el frontend en desarrollo apunta a `http://localhost:8080` y el backend solo acepta peticiones desde `http://localhost:4200` (CORS). El backend, por defecto, usa el modelo desplegado en Railway (estable); si ese servicio no está disponible por cualquier razón, cae automáticamente a un clasificador local de respaldo — la app nunca deja de funcionar.

## 🚀 Despliegue

Los tres componentes están desplegados en [Railway](https://railway.app), como tres servicios separados dentro del mismo proyecto (cada uno apuntando a su propia carpeta del repo):

| Servicio | URL |
|---|---|
| 🖥️ Frontend | `https://scintillating-bravery-production-abf8.up.railway.app` |
| ⚙️ Backend | `https://g9-latam-team-75-production.up.railway.app` |
| 🧠 Modelo | `https://profound-courage-production.up.railway.app` |

El modelo corre de forma permanente en su propio servicio (a partir de `model/`), así que ya no depende de que una sesión de Colab esté activa.

**Variables de entorno del backend** (todas opcionales, con valores por defecto para desarrollo local):

| Variable | Para qué |
|---|---|
| `PORT` | Puerto del servidor (lo asigna Railway automáticamente) |
| `DATA_MODELO_URL` | URL del servicio del modelo (`https://profound-courage-production.up.railway.app/predecir`) |
| `CORS_ALLOWED_ORIGIN` | Origen permitido para CORS (la URL del frontend desplegado) |
| `COOKIE_SAME_SITE` / `COOKIE_SECURE` | Atributos de la cookie de sesión, necesarios para que funcione entre dominios distintos de Railway |

## 📂 Estructura del proyecto

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
├── frontend/            # SPA (Angular)
│   └── src/app/
│       ├── app.ts        # Componente principal (formulario, historial, lotes, simulador)
│       ├── app.html
│       └── app.css
└── model/               # Modelo de Data (Python)
    ├── generar_dataset.py  # Genera dataset_consumo.csv (sube a OCI Object Storage)
    ├── entrenamiento.py    # Entrena leyendo desde OCI, evalúa, serializa el modelo
    ├── servidor.py          # API del modelo (FastAPI)
    └── random_forest_consumo.pkl
```

## 🧪 Pruebas automatizadas

```bash
cd backend
./mvnw test
```

11 pruebas (JUnit 5 + Mockito) cubriendo: clasificación por reglas (mock) en sus tres categorías, cálculo de costo, precedencia del modelo real sobre el mock, origen de las recomendaciones, y que las simulaciones no ensucien el historial real.

## ▶️ Guía rápida: levantar todo en local

**Requisitos previos:** Java 21, Node.js 18+ (con npm), y conexión a internet (el backend consulta el modelo desplegado en Railway por defecto).

1. **Clona el repo y abre dos terminales** — una para el backend y otra para el frontend.

2. **Terminal 1 — Backend:**
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
   Espera a que aparezca `Started EnergiaiApplication` en la consola. Queda corriendo en `http://localhost:8080`.

3. **Terminal 2 — Frontend:**
   ```bash
   cd frontend
   npm install
   npm start
   ```
   Queda corriendo en `http://localhost:4200`.

4. **Abre el navegador en `http://localhost:4200`.** El frontend ya viene configurado para hablar con el backend en `localhost:8080`, y el backend acepta peticiones desde `localhost:4200` — no hay que tocar nada más.

5. **Usa la app:**
   - Llena el formulario con un perfil de consumo (kWh del mes, equipos, tamaño del hogar, temperatura) y dale **Analizar** para ver la categoría, el costo estimado y las recomendaciones.
   - O sube un Excel/CSV con varios recibos desde la sección de **carga por lotes** (puedes descargar la plantilla vacía desde ahí mismo).
   - Cada análisis que hagas se guarda en el **historial** de tu sesión (mientras no cierres el navegador ni borres las cookies), con gráfico por mes, comparación contra tu análisis anterior, y ranking de eficiencia.
   - Prueba el **simulador de ahorro** para ver cuánto bajaría tu costo si reduces uso en horario pico o cambias cuántos aires acondicionados usas.

> Si cierras cualquiera de las dos terminales, la app deja de responder de ese lado — ambas deben quedar corriendo al mismo tiempo mientras la uses.

---

<div align="center">

Hecho con ⚡ para el **Hackathon ONE** — G9 LATAM

</div>
