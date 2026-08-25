package EnergiAI.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Datos de entrada que recibe la API en {@code POST /analisis-energetico}.
 *
 * Es un DTO (Data Transfer Object): una clase simple, sin lógica de negocio,
 * cuyo único propósito es representar la forma del JSON que manda el
 * frontend. Jackson (la librería de JSON que usa Spring) la deserializa
 * automáticamente a partir del cuerpo de la petición HTTP.
 *
 * Alineado con el dataset real que usa el equipo de Data (columnas del CSV
 * en OCI Object Storage: Household_ID, Date, Energy_Consumption_kWh,
 * Household_Size, Avg_Temperature_C, Has_AC, Peak_Hours_Usage_kWh), más las
 * cantidades de electrodomésticos que Data agregó después (Refrigeradores,
 * Microondas, Lavadoras, Pantallas, Aire_Acondicionado, Focos).
 *
 * IMPORTANTE: esas cantidades de equipos las pide este formulario
 * directamente al usuario (son datos REALES). En el notebook de Data esas
 * mismas columnas se generaron al azar solo para entrenar el modelo con un
 * dataset simulado (no había datos reales de equipos por vivienda) — pero
 * para USAR el modelo con una persona real, esos valores tienen que venir
 * de la persona, no inventarse.
 *
 * NOTA: consumo_kwh y uso_horario_pico_kwh son MENSUALES (lo que el
 * usuario conoce de su recibo), pero el dataset de Data es DIARIO. El
 * microservicio de Data es responsable de convertir mensual -> diario
 * (dividir entre ~30) antes de alimentar el modelo; este backend no
 * hace esa conversión porque no le corresponde a él.
 *
 * Ejemplo de JSON válido:
 * <pre>{@code
 * {
 *   "consumo_kwh": 180,
 *   "uso_horario_pico_kwh": 32,
 *   "tamano_hogar": 4,
 *   "temperatura_promedio": 17.8,
 *   "refrigeradores": 1,
 *   "microondas": 1,
 *   "lavadoras": 1,
 *   "pantallas": 2,
 *   "aire_acondicionado": 0,
 *   "focos": 8
 * }
 * }</pre>
 *
 * Estos nombres (sin el prefijo "cantidad_") coinciden a propósito con los
 * que espera el microservicio de Data en Colab ({@code AnalisisRequest} de
 * su celda FastAPI) — así su Pydantic no necesita tocarse.
 *
 * Cada campo tiene anotaciones de <b>Bean Validation</b> ({@code @NotNull},
 * {@code @Min}, {@code @Max}). Spring las revisa automáticamente antes de
 * ejecutar el controlador (ver {@code @Valid} en
 * {@link EnergiAI.controller.AnalisisController#analizar}); si alguna falla,
 * la petición nunca llega al código de negocio.
 */
public class AnalisisRequest {

    /**
     * Consumo eléctrico mensual, en kWh (equivale a Energy_Consumption_kWh
     * del dataset de Data, pero a escala mensual en vez de diaria).
     * Se usa para: (1) calcular el costo estimado en {@link
     * EnergiAI.service.AnalisisService}, y (2) como insumo para derivar
     * "kWh/Individuo" en el modelo de Data.
     */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("consumo_kwh")
    private Double consumoKwh;

    /**
     * Consumo eléctrico mensual durante horario pico, en kWh (equivale a
     * Peak_Hours_Usage_kWh del dataset de Data). No puede ser mayor que
     * {@link #consumoKwh} — eso lo garantiza {@link
     * #isUsoHorarioPicoDentroDelConsumoTotal()} más abajo.
     */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("uso_horario_pico_kwh")
    private Double usoHorarioPicoKwh;

    /**
     * Cantidad de personas que viven en el hogar (equivale a Household_Size
     * del dataset de Data). El rango 1-30 es una cota de sanidad para evitar
     * valores absurdos, no una regla de negocio estricta.
     */
    @NotNull(message = "es obligatorio")
    @Min(value = 1, message = "debe ser al menos 1")
    @Max(value = 30, message = "no puede ser mayor a 30")
    @JsonProperty("tamano_hogar")
    private Integer tamanoHogar;

    /**
     * Temperatura promedio de la zona, en grados Celsius (equivale a
     * Avg_Temperature_C del dataset de Data). El rango -30 a 55 cubre
     * prácticamente cualquier clima habitado del planeta.
     */
    @NotNull(message = "es obligatorio")
    @Min(value = -30, message = "no puede ser menor a -30")
    @Max(value = 55, message = "no puede ser mayor a 55")
    @JsonProperty("temperatura_promedio")
    private Double temperaturaPromedio;

    /** Cantidad de refrigeradores en la vivienda. */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("refrigeradores")
    private Integer cantidadRefrigeradores;

    /** Cantidad de microondas en la vivienda. */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("microondas")
    private Integer cantidadMicroondas;

    /** Cantidad de lavadoras en la vivienda. */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("lavadoras")
    private Integer cantidadLavadoras;

    /** Cantidad de pantallas/televisores en la vivienda. */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("pantallas")
    private Integer cantidadPantallas;

    /**
     * Cantidad de equipos de aire acondicionado en la vivienda. Un valor
     * de 0 significa que no tiene.
     */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("aire_acondicionado")
    private Integer cantidadAireAcondicionado;

    /** Cantidad de focos/lámparas en la vivienda. */
    @NotNull(message = "es obligatorio")
    @Min(value = 0, message = "no puede ser negativo")
    @JsonProperty("focos")
    private Integer cantidadFocos;

    /**
     * Marca esta petición como una simulación ("¿y si redujera mi consumo
     * en pico un 20%?"): se calcula y devuelve normalmente, pero NO se
     * guarda en el historial de la sesión (ver
     * {@link EnergiAI.service.AnalisisService#analizar}), para no mezclar
     * hipótesis con análisis reales. No requiere validación: si no viene
     * en el JSON, Jackson la deja en {@code false} (su valor por defecto).
     */
    @JsonProperty("simulacion")
    private boolean simulacion = false;

    /**
     * Mes al que corresponde este recibo (ej. "Enero"), opcional — se
     * pide tanto en el formulario normal como en el análisis por lotes
     * (columna "mes" del CSV/Excel subido), para identificar cada
     * análisis en el historial y en la descarga. No afecta la
     * clasificación ni se valida: si no viene, queda null.
     */
    @JsonProperty("mes")
    private String mes;

    /**
     * Año al que corresponde este recibo (ej. 2026), opcional — mismo
     * propósito que {@link #mes}, solo para identificar el análisis en
     * el historial y en la descarga. No afecta la clasificación ni se
     * valida: si no viene, queda null.
     */
    @JsonProperty("anio")
    private Integer anio;

    // ---- Getters y Setters ----
    // Jackson los usa por reflexión para leer/escribir cada campo al
    // deserializar el JSON entrante. Spring Validation también los usa
    // (vía los getters) para evaluar las anotaciones @NotNull/@Min/@Max.

    /** @return el consumo eléctrico mensual en kWh */
    public Double getConsumoKwh() {
        return consumoKwh;
    }

    /** @param consumoKwh consumo eléctrico mensual en kWh */
    public void setConsumoKwh(Double consumoKwh) {
        this.consumoKwh = consumoKwh;
    }

    /** @return el consumo mensual en horario pico, en kWh */
    public Double getUsoHorarioPicoKwh() {
        return usoHorarioPicoKwh;
    }

    /** @param usoHorarioPicoKwh consumo mensual en horario pico, en kWh */
    public void setUsoHorarioPicoKwh(Double usoHorarioPicoKwh) {
        this.usoHorarioPicoKwh = usoHorarioPicoKwh;
    }

    /** @return la cantidad de personas en el hogar */
    public Integer getTamanoHogar() {
        return tamanoHogar;
    }

    /** @param tamanoHogar cantidad de personas en el hogar */
    public void setTamanoHogar(Integer tamanoHogar) {
        this.tamanoHogar = tamanoHogar;
    }

    /** @return la temperatura promedio de la zona, en °C */
    public Double getTemperaturaPromedio() {
        return temperaturaPromedio;
    }

    /** @param temperaturaPromedio temperatura promedio de la zona, en °C */
    public void setTemperaturaPromedio(Double temperaturaPromedio) {
        this.temperaturaPromedio = temperaturaPromedio;
    }

    /** @return la cantidad de refrigeradores */
    public Integer getCantidadRefrigeradores() {
        return cantidadRefrigeradores;
    }

    /** @param cantidadRefrigeradores la cantidad de refrigeradores */
    public void setCantidadRefrigeradores(Integer cantidadRefrigeradores) {
        this.cantidadRefrigeradores = cantidadRefrigeradores;
    }

    /** @return la cantidad de microondas */
    public Integer getCantidadMicroondas() {
        return cantidadMicroondas;
    }

    /** @param cantidadMicroondas la cantidad de microondas */
    public void setCantidadMicroondas(Integer cantidadMicroondas) {
        this.cantidadMicroondas = cantidadMicroondas;
    }

    /** @return la cantidad de lavadoras */
    public Integer getCantidadLavadoras() {
        return cantidadLavadoras;
    }

    /** @param cantidadLavadoras la cantidad de lavadoras */
    public void setCantidadLavadoras(Integer cantidadLavadoras) {
        this.cantidadLavadoras = cantidadLavadoras;
    }

    /** @return la cantidad de pantallas/televisores */
    public Integer getCantidadPantallas() {
        return cantidadPantallas;
    }

    /** @param cantidadPantallas la cantidad de pantallas/televisores */
    public void setCantidadPantallas(Integer cantidadPantallas) {
        this.cantidadPantallas = cantidadPantallas;
    }

    /** @return la cantidad de equipos de aire acondicionado */
    public Integer getCantidadAireAcondicionado() {
        return cantidadAireAcondicionado;
    }

    /** @param cantidadAireAcondicionado la cantidad de equipos de aire acondicionado */
    public void setCantidadAireAcondicionado(Integer cantidadAireAcondicionado) {
        this.cantidadAireAcondicionado = cantidadAireAcondicionado;
    }

    /**
     * Campo derivado, solo para el JSON que sale hacia el microservicio de
     * Data: su Pydantic pide un booleano "tiene_aire_acondicionado" además
     * del conteo "aire_acondicionado". El formulario del frontend no pide
     * este booleano por separado (sería redundante), así que se calcula
     * aquí a partir de {@link #cantidadAireAcondicionado}. No tiene setter
     * a propósito: no se espera como entrada, solo se serializa de salida.
     *
     * @return true si hay al menos un equipo de aire acondicionado
     */
    @JsonProperty("tiene_aire_acondicionado")
    public boolean isTieneAireAcondicionado() {
        return cantidadAireAcondicionado != null && cantidadAireAcondicionado > 0;
    }

    /** @return la cantidad de focos/lámparas */
    public Integer getCantidadFocos() {
        return cantidadFocos;
    }

    /** @param cantidadFocos la cantidad de focos/lámparas */
    public void setCantidadFocos(Integer cantidadFocos) {
        this.cantidadFocos = cantidadFocos;
    }

    /** @return true si esta petición es una simulación (no se guarda en el historial) */
    public boolean isSimulacion() {
        return simulacion;
    }

    /** @param simulacion true si esta petición es una simulación */
    public void setSimulacion(boolean simulacion) {
        this.simulacion = simulacion;
    }

    /** @return el mes al que corresponde este recibo, o null si no se mandó */
    public String getMes() {
        return mes;
    }

    /** @param mes el mes al que corresponde este recibo */
    public void setMes(String mes) {
        this.mes = mes;
    }

    /** @return el año al que corresponde este recibo, o null si no se mandó */
    public Integer getAnio() {
        return anio;
    }

    /** @param anio el año al que corresponde este recibo */
    public void setAnio(Integer anio) {
        this.anio = anio;
    }

    /**
     * Validación cruzada entre dos campos: no tiene sentido consumir más
     * en horario pico que en total. Bean Validation permite este patrón
     * con {@code @AssertTrue} sobre un método {@code isXxx()}: Spring lo
     * trata como una regla más, junto a las de @NotNull/@Min/@Max de
     * arriba, y si devuelve {@code false} agrega un error de validación
     * con la propiedad "usoHorarioPicoDentroDelConsumoTotal" (nombre
     * derivado del método) y el {@code message} de la anotación.
     *
     * Si {@link #consumoKwh} o {@link #usoHorarioPicoKwh} son null, esta
     * regla no reporta nada (devuelve true) porque esos casos ya los
     * cubren los {@code @NotNull} de cada campo por separado — evita
     * duplicar el mismo error dos veces.
     *
     * {@code @JsonIgnore} evita que Jackson intente tratar este método
     * como si fuera un campo más al serializar/deserializar JSON (Jackson,
     * por convención de JavaBeans, interpreta cualquier {@code isXxx()}
     * público como el getter de una propiedad booleana).
     *
     * @return true si el dato es válido (o si no se puede evaluar aún
     *         porque falta alguno de los dos campos), false si el pico
     *         supera al consumo total
     */
    @AssertTrue(message = "uso_horario_pico_kwh no puede ser mayor que consumo_kwh")
    @JsonIgnore
    public boolean isUsoHorarioPicoDentroDelConsumoTotal() {
        if (consumoKwh == null || usoHorarioPicoKwh == null) {
            return true;
        }
        return usoHorarioPicoKwh <= consumoKwh;
    }
}
