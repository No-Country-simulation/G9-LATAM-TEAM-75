# ============================================================
# Genera el dataset de entrenamiento de EnergiAI ("de abajo hacia
# arriba"): el consumo total (Energy_Consumption_kWh) se CALCULA a
# partir de cuántos electrodomésticos tiene cada vivienda (con watts
# típicos reales), en vez de generar los electrodomésticos a partir de
# un consumo total ya fijado.
#
# Esto corrige un problema real que encontramos en una versión anterior
# del dataset (la que está en OCI Object Storage): ahí las cantidades
# de equipos se generaban A PARTIR del consumo real (para que ese
# consumo tuviera un ~70% "explicado" por los equipos). El modelo
# entrenado con esos datos terminaba dependiendo casi por completo de
# una sola variable (Peak_Hours_Usage_kWh, ~97% de la importancia) y
# clasificaba casi cualquier combinación libre de equipos como
# "Ineficiente", sin importar qué tan eficiente fuera en realidad.
#
# Corre este script para regenerar dataset_consumo.csv:
#   python generar_dataset.py
#
# Después de correrlo, sube dataset_consumo.csv a OCI Object Storage
# (reemplazando o junto al dataset anterior), para que quede como la
# fuente de datos real del proyecto.
# ============================================================

import numpy as np
import pandas as pd

np.random.seed(42)

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

# Consumo total calculado a partir de los equipos (bottom-up), más un
# poco más de consumo base si es "Casa" (más personas) vs. pequeño
# establecimiento, y ruido gaussiano para que no sea una fórmula
# perfecta.
consumo_base = (
    refrigeradores * KWH_DIA["Refrigeradores"]
    + microondas * KWH_DIA["Microondas"]
    + lavadoras * KWH_DIA["Lavadoras"]
    + pantallas * KWH_DIA["Pantallas"]
    + aire_acondicionado * KWH_DIA["Aire_Acondicionado"] * (1 + np.clip((avg_temp - 22) / 20, -0.3, 0.6))
    + focos * KWH_DIA["Focos"]
)
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

df.to_csv("dataset_consumo.csv", index=False)
print(f"Guardado dataset_consumo.csv con {len(df)} filas")
print(df.head())
