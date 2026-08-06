package EnergiAI.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Respuesta que devuelve la API en {@code POST /analisis-energetico}.
 *
 * Igual que {@link AnalisisRequest}, es un DTO simple sin lógica de negocio:
 * Jackson lo serializa a JSON automáticamente para mandarlo de vuelta al
 * frontend. Los nombres de propiedad en JSON son snake_case (por
 * {@code @JsonProperty}) para que coincidan con el resto de la API, mientras
 * que en Java se usa camelCase (convención del lenguaje).
 *
 * Ejemplo de JSON de salida:
 * <pre>{@code
 * {
 *   "categoria": "Eficiente",
 *   "probabilidad": 1.0,
 *   "costo_estimado_mensual": 135.0,
 *   "recomendaciones": [
 *     "Continúe con sus hábitos",
 *     "Mantenga el mantenimiento de los equipos"
 *   ]
 * }
 * }</pre>
 */
public class AnalisisResponse {

    /**
     * Momento en que se calculó este análisis. Se usa solo para el
     * historial de la sesión ({@code GET /analisis-energetico}) — no
     * viene de ningún dato del usuario, la pone el backend al vuelo.
     */
    private LocalDateTime fecha;

    /**
     * Consumo mensual que se analizó, en kWh. Se repite acá (ya venía en
     * el {@link AnalisisRequest}) para que el historial de la sesión
     * ({@code GET /analisis-energetico}) tenga todo lo que necesita el
     * dashboard del frontend sin tener que guardar el request por separado.
     */
    @JsonProperty("consumo_kwh")
    private Double consumoKwh;

    /**
     * Clasificación del perfil energético: {@code "Eficiente"},
     * {@code "Moderado"} o {@code "Ineficiente"}. Viene del modelo de Data
     * si está disponible, o del mock local en
     * {@link EnergiAI.service.AnalisisService} si no.
     */
    private String categoria;

    /**
     * Qué tan seguro está el clasificador de su predicción, entre 0 y 1.
     * El modelo real de Data la calcula con {@code predict_proba}; el mock
     * local no tiene ese concepto y siempre manda 1.0.
     */
    private Double probabilidad;

    /**
     * Lista de sugerencias en texto plano para reducir el consumo o
     * mantener buenos hábitos, según la categoría.
     */
    private List<String> recomendaciones;

    /**
     * Costo mensual estimado en la moneda de referencia, calculado por
     * este mismo backend (no por Data) como
     * {@code consumo_kwh × 0.75} (tarifa fija del hackathon).
     */
    @JsonProperty("costo_estimado_mensual")
    private Double costoEstimadoMensual;

    // ---- Getters y Setters ----
    // Jackson los usa por reflexión para construir el JSON de salida a
    // partir de este objeto.

    /** @return el momento en que se calculó este análisis */
    public LocalDateTime getFecha() {
        return fecha;
    }

    /** @param fecha el momento en que se calculó este análisis */
    public void setFecha(LocalDateTime fecha) {
        this.fecha = fecha;
    }

    /** @return el consumo mensual analizado, en kWh */
    public Double getConsumoKwh() {
        return consumoKwh;
    }

    /** @param consumoKwh el consumo mensual analizado, en kWh */
    public void setConsumoKwh(Double consumoKwh) {
        this.consumoKwh = consumoKwh;
    }

    /** @return la categoría del perfil energético */
    public String getCategoria() {
        return categoria;
    }

    /** @param categoria la categoría del perfil energético */
    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    /** @return la confianza de la predicción, entre 0 y 1 */
    public Double getProbabilidad() {
        return probabilidad;
    }

    /** @param probabilidad la confianza de la predicción, entre 0 y 1 */
    public void setProbabilidad(Double probabilidad) {
        this.probabilidad = probabilidad;
    }

    /** @return la lista de recomendaciones */
    public List<String> getRecomendaciones() {
        return recomendaciones;
    }

    /** @param recomendaciones la lista de recomendaciones */
    public void setRecomendaciones(List<String> recomendaciones) {
        this.recomendaciones = recomendaciones;
    }

    /** @return el costo mensual estimado */
    public Double getCostoEstimadoMensual() {
        return costoEstimadoMensual;
    }

    /** @param costoEstimadoMensual el costo mensual estimado */
    public void setCostoEstimadoMensual(Double costoEstimadoMensual) {
        this.costoEstimadoMensual = costoEstimadoMensual;
    }
}
