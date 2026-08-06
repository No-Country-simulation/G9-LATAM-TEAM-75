package EnergiAI.client;

import java.util.List;

/**
 * Forma esperada de la respuesta del microservicio de Data (cuando exista).
 *
 * Es un DTO usado solo para deserializar la respuesta JSON de ese servicio
 * externo (ver {@link ModeloDataClient#predecir}) — no viaja tal cual hacia
 * el frontend; {@link EnergiAI.service.AnalisisService} toma sus datos y
 * arma con ellos un {@link EnergiAI.dto.AnalisisResponse}.
 *
 * Contrato propuesto para el equipo de Data:
 * <pre>
 * POST {data.modelo.url}
 * body: el mismo JSON que recibe /analisis-energetico
 * respuesta esperada:
 * {
 *   "categoria": "Eficiente" | "Moderado" | "Ineficiente",
 *   "probabilidad": 0.81,
 *   "recomendaciones": ["...", "..."]   // opcional; si no la mandan,
 *                                       // el backend genera unas por defecto
 * }
 * </pre>
 */
public class PrediccionModelo {

    /** Categoría predicha por el modelo: Eficiente, Moderado o Ineficiente. */
    private String categoria;

    /** Confianza de la predicción, entre 0 y 1 (viene de {@code predict_proba}). */
    private Double probabilidad;

    /**
     * Recomendaciones opcionales generadas por el propio modelo/servicio de
     * Data. Puede venir {@code null} si el servicio no las incluye — en ese
     * caso el backend genera unas por defecto según la categoría.
     */
    private List<String> recomendaciones;

    /** @return la categoría predicha */
    public String getCategoria() {
        return categoria;
    }

    /** @param categoria la categoría predicha */
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

    /** @return las recomendaciones del modelo, o null si no mandó ninguna */
    public List<String> getRecomendaciones() {
        return recomendaciones;
    }

    /** @param recomendaciones las recomendaciones del modelo */
    public void setRecomendaciones(List<String> recomendaciones) {
        this.recomendaciones = recomendaciones;
    }
}
