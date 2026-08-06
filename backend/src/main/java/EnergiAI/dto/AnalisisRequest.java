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
 * Household_Size, Avg_Temperature_C, Has_AC, Peak_Hours_Usage_kWh), para
 * que estos mismos campos sirvan de entrada al modelo entrenado.
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
 *   "tiene_aire_acondicionado": false
 * }
 * }</pre>
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

    /**
     * Si la vivienda tiene aire acondicionado (equivale a Has_AC del
     * dataset de Data, que ahí viene como texto "Yes"/"No" y aquí ya es
     * un booleano nativo de JSON).
     */
    @NotNull(message = "es obligatorio")
    @JsonProperty("tiene_aire_acondicionado")
    private Boolean tieneAireAcondicionado;

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

    /** @return true si la vivienda tiene aire acondicionado */
    public Boolean getTieneAireAcondicionado() {
        return tieneAireAcondicionado;
    }

    /** @param tieneAireAcondicionado true si la vivienda tiene aire acondicionado */
    public void setTieneAireAcondicionado(Boolean tieneAireAcondicionado) {
        this.tieneAireAcondicionado = tieneAireAcondicionado;
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
