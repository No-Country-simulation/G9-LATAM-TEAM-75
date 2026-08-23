# ============================================================
# Entrenamiento del modelo de EnergiAI (Random Forest).
#
# Lee dataset_consumo.csv (generado con generar_dataset.py, y subido a
# OCI Object Storage como fuente de datos del proyecto) y entrena un
# RandomForestRegressor para predecir el consumo diario esperado de
# una vivienda a partir de su tamaño, temperatura, uso en horario pico,
# y cantidad de cada tipo de electrodoméstico.
#
# Corre esto para regenerar random_forest_consumo.pkl:
#   python entrenamiento.py
# ============================================================

import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder
import joblib

# ------------------------------------------------------------
# 1. CARGAR EL DATASET
# ------------------------------------------------------------

df = pd.read_csv("dataset_consumo.csv")

print("Primeras filas del dataset:")
print(df.head())
print("\nDescripción del consumo objetivo (kWh/día):")
print(df["Energy_Consumption_kWh"].describe())

# ------------------------------------------------------------
# 2. ENTRENAR
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
# 3. EVALUAR
# ------------------------------------------------------------

y_pred = pipeline.predict(X_test)

print("\nResultados Random Forest")
print("MAE :", round(mean_absolute_error(y_test, y_pred), 4), "kWh")
print("RMSE:", round(mean_squared_error(y_test, y_pred) ** 0.5, 4), "kWh")
print("R²  :", round(r2_score(y_test, y_pred), 4))

# ------------------------------------------------------------
# 4. IMPORTANCIA DE VARIABLES (confirma que ya no hay fuga de datos:
#    ninguna variable domina por completo, a diferencia del dataset
#    anterior donde Peak_Hours_Usage_kWh tenía ~97% de la importancia)
# ------------------------------------------------------------

rf = pipeline.named_steps["random_forest"]
nombres_variables = pipeline.named_steps["preprocessor"].get_feature_names_out()

importancia_df = pd.DataFrame({
    "Variable": nombres_variables,
    "Importancia": rf.feature_importances_,
}).sort_values("Importancia", ascending=False).reset_index(drop=True)

print("\nIMPORTANCIA DE VARIABLES")
print(importancia_df)

# ------------------------------------------------------------
# 5. GUARDAR EL MODELO
# ------------------------------------------------------------

joblib.dump({
    "modelo": pipeline,
    "variables": list(X.columns),
}, "random_forest_consumo.pkl")

print("\nModelo guardado en random_forest_consumo.pkl")
