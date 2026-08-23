# ============================================================
# Entrenamiento del modelo de EnergiAI (Random Forest).
#
# Genera un dataset sintético "de abajo hacia arriba": el consumo total
# (Energy_Consumption_kWh) se CALCULA a partir de cuántos electrodomésticos
# tiene cada vivienda (con watts típicos reales), en vez de generar los
# electrodomésticos a partir de un consumo total ya fijado.
#
# Esto corrige un problema real que encontramos en una versión anterior
# del dataset: ahí las cantidades de equipos se generaban A PARTIR del
# consumo real (para que ese consumo tuviera un ~70% "explicado" por los
# equipos). El modelo entrenado con esos datos terminaba dependiendo casi
# por completo de una sola variable (Peak_Hours_Usage_kWh, ~97% de la
# importancia) y clasificaba casi cualquier combinación libre de equipos
# como "Ineficiente", sin importar qué tan eficiente fuera en realidad.
#
# Aquí, Peak_Hours_Usage_kWh se genera INDEPENDIENTE del consumo total
# (no se deriva de él), y el consumo se calcula sumando el aporte real de
# cada equipo — así el modelo aprende la relación real equipos -> consumo.
#
# Corre este script para regenerar random_forest_consumo.pkl:
#   python entrenamiento.py
# ============================================================

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder
import joblib

np.random.seed(42)

# ------------------------------------------------------------
# 1. GENERAR DATASET SINTÉTICO (bottom-up, sin fuga de datos)
# ------------------------------------------------------------

N = 3000

# Consumo diario típico por unidad de cada equipo (kWh/día), basado en
# potencias reales aproximadas de electrodomésticos domésticos.
KWH_DIA = {
    "Refrigeradores": 1.20,   # corre todo el día
    "Microondas": 0.25,       # uso corto, varias veces al día
    "Lavadoras": 0.30,        # uso ocasional
    "Pantallas": 0.50,        # varias horas encendida
    "Aire_Acondicionado": 2.60,  # el que más consume, pero sin aplastar al resto
    "Focos": 0.12,
}

household_size = np.random.choice(["Casa", "Pequeño_establecimiento"], size=N, p=[0.85, 0.15])
avg_temp = np.random.uniform(10, 38, size=N).round(1)

refrigeradores = np.random.randint(0, 5, size=N)
microondas = np.random.randint(0, 3, size=N)
lavadoras = np.random.randint(0, 4, size=N)
pantallas = np.random.randint(0, 6, size=N)
focos = np.random.randint(2, 21, size=N)

# Más probabilidad de tener A/C si la temperatura es alta (esto SÍ es
# realista y no es fuga de datos: el clima influye en qué equipos tiene
# la vivienda, no en el consumo objetivo directamente).
prob_ac = np.clip((avg_temp - 15) / 25, 0.02, 0.9)
aire_acondicionado = (np.random.rand(N) < prob_ac).astype(int) * np.random.randint(1, 4, size=N)

# Peak_Hours_Usage_kWh: INDEPENDIENTE del consumo total. Se modela como
# unas horas de uso concentrado en el día, con algo de ruido, sin mirar
# el consumo total en ningún momento.
uso_horario_pico_kwh = np.round(np.random.gamma(shape=2.0, scale=1.0, size=N), 2)

# ------------------------------------------------------------
# 2. CALCULAR EL CONSUMO TOTAL A PARTIR DE LOS EQUIPOS (bottom-up)
# ------------------------------------------------------------

consumo_base = (
    refrigeradores * KWH_DIA["Refrigeradores"]
    + microondas * KWH_DIA["Microondas"]
    + lavadoras * KWH_DIA["Lavadoras"]
    + pantallas * KWH_DIA["Pantallas"]
    + aire_acondicionado * KWH_DIA["Aire_Acondicionado"] * (1 + np.clip((avg_temp - 22) / 20, -0.3, 0.6))
    + focos * KWH_DIA["Focos"]
)

# Un poco más de consumo base si es "Casa" (más personas) vs. pequeño
# establecimiento, más ruido gaussiano para que no sea una fórmula
# perfecta (el modelo real necesita algo de variabilidad para no
# sobreajustar de forma irreal).
consumo_base += np.where(household_size == "Casa", 1.5, 0.5)
ruido = np.random.normal(0, 0.8, size=N)
energy_consumption = np.clip(consumo_base + ruido, 0.3, None).round(2)

df = pd.DataFrame({
    "Household_Size": household_size,
    "Avg_Temperature_C": avg_temp,
    "Peak_Hours_Usage_kWh": uso_horario_pico_kwh,
    "Refrigeradores": refrigeradores,
    "Microondas": microondas,
    "Lavadoras": lavadoras,
    "Pantallas": pantallas,
    "Aire_Acondicionado": aire_acondicionado,
    "Focos": focos,
    "Energy_Consumption_kWh": energy_consumption,
})

print("Primeras filas del dataset generado:")
print(df.head())
print("\nDescripción del consumo objetivo (kWh/día):")
print(df["Energy_Consumption_kWh"].describe())

# ------------------------------------------------------------
# 3. ENTRENAR
# ------------------------------------------------------------

columnas_categoricas = ["Household_Size"]
columnas_numericas = [
    "Avg_Temperature_C", "Peak_Hours_Usage_kWh", "Refrigeradores",
    "Microondas", "Lavadoras", "Pantallas", "Aire_Acondicionado", "Focos",
]

X = df[columnas_categoricas + columnas_numericas]
y = df["Energy_Consumption_kWh"]

X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

preprocessor = ColumnTransformer([
    ("categorical", OneHotEncoder(handle_unknown="ignore"), columnas_categoricas),
], remainder="passthrough")

pipeline = Pipeline([
    ("preprocessor", preprocessor),
    ("random_forest", RandomForestRegressor(
        n_estimators=200, max_depth=10, min_samples_leaf=5, random_state=42
    )),
])

pipeline.fit(X_train, y_train)

# ------------------------------------------------------------
# 4. EVALUAR
# ------------------------------------------------------------

y_pred = pipeline.predict(X_test)

print("\nResultados Random Forest")
print("MAE :", round(mean_absolute_error(y_test, y_pred), 4), "kWh")
print("RMSE:", round(mean_squared_error(y_test, y_pred) ** 0.5, 4), "kWh")
print("R²  :", round(r2_score(y_test, y_pred), 4))

# ------------------------------------------------------------
# 5. CONFIRMAR QUE YA NO HAY FUGA DE DATOS
# ------------------------------------------------------------

rf = pipeline.named_steps["random_forest"]
nombres_variables = pipeline.named_steps["preprocessor"].get_feature_names_out()

importancia_df = pd.DataFrame({
    "Variable": nombres_variables,
    "Importancia": rf.feature_importances_,
}).sort_values("Importancia", ascending=False).reset_index(drop=True)

print("\nIMPORTANCIA DE VARIABLES (ya no debería dominarla una sola)")
print(importancia_df)

# ------------------------------------------------------------
# 6. GUARDAR EL MODELO
# ------------------------------------------------------------

joblib.dump({
    "modelo": pipeline,
    "variables": list(X.columns),
}, "random_forest_consumo.pkl")

print("\nModelo guardado en random_forest_consumo.pkl")
