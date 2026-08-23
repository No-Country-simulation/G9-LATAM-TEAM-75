# ============================================================
# Servidor del modelo de EnergiAI, para desplegar en Railway (o
# cualquier host que corra un proceso Python persistente) — reemplaza
# a la celda de Colab + ngrok, que se caía cada vez que se cerraba la
# sesión o cambiaba de URL.
#
# Los nombres de campo de AnalisisRequest coinciden EXACTOS con los que
# manda el backend Java (ver AnalisisRequest.java): sin el prefijo
# "cantidad_", y compatibles con "mes"/"anio" extra que el backend
# manda pero que este servidor simplemente ignora (no afectan la
# predicción).
# ============================================================

import pandas as pd
import joblib
from fastapi import FastAPI
from pydantic import BaseModel

modelo_cargado = joblib.load("random_forest_consumo.pkl")
# Soporta los dos formatos que hemos visto: el pipeline directo, o un
# dict {"modelo": pipeline, "variables": [...]}.
modelo = modelo_cargado["modelo"] if isinstance(modelo_cargado, dict) else modelo_cargado

app = FastAPI(title="EnergiAI - Modelo de consumo eléctrico")

DIAS_POR_MES = 30
MARGEN_PORCENTAJE = 0.15


class AnalisisRequest(BaseModel):
    consumo_kwh: float
    tamano_hogar: int
    temperatura_promedio: float
    uso_horario_pico_kwh: float
    refrigeradores: int
    microondas: int
    lavadoras: int
    pantallas: int
    aire_acondicionado: int
    focos: int
    # Opcionales: el backend los manda para el historial, pero el
    # modelo no los usa para predecir.
    mes: str | None = None
    anio: int | None = None


@app.get("/")
def salud():
    return {"status": "ok", "endpoint": "/predecir"}


@app.post("/predecir")
def predecir(datos: AnalisisRequest):
    household_size = "Casa" if datos.tamano_hogar > 0 else "Pequeño_establecimiento"

    # Mensual -> diario, para alimentar al modelo en la escala con la
    # que se entrenó.
    pico_diario = datos.uso_horario_pico_kwh / DIAS_POR_MES

    entrada = pd.DataFrame([{
        "Household_Size": household_size,
        "Avg_Temperature_C": datos.temperatura_promedio,
        "Peak_Hours_Usage_kWh": pico_diario,
        "Refrigeradores": datos.refrigeradores,
        "Microondas": datos.microondas,
        "Lavadoras": datos.lavadoras,
        "Pantallas": datos.pantallas,
        "Aire_Acondicionado": datos.aire_acondicionado,
        "Focos": datos.focos,
    }])

    consumo_predicho_diario = float(modelo.predict(entrada)[0])
    # El modelo predice en escala DIARIA; se pasa a mensual (x30) para
    # comparar contra consumo_kwh, que llega mensual desde Java.
    consumo_predicho = consumo_predicho_diario * DIAS_POR_MES

    margen = consumo_predicho * MARGEN_PORCENTAJE
    limite_inferior = consumo_predicho - margen
    limite_superior = consumo_predicho + margen

    consumo_real = datos.consumo_kwh

    if consumo_real < limite_inferior:
        categoria = "Eficiente"
    elif consumo_real <= limite_superior:
        categoria = "Moderado"
    else:
        categoria = "Ineficiente"

    diferencia = consumo_real - consumo_predicho
    diferencia_porcentaje = (diferencia / consumo_predicho * 100) if consumo_predicho != 0 else 0
    probabilidad = max(0.0, min(1.0, 1 - abs(diferencia_porcentaje) / 100))

    return {
        "categoria": categoria,
        "probabilidad": round(probabilidad, 4),
    }
