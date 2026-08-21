package EnergiAI.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Envoltorio para analizar varios perfiles de consumo de un jalón
 * ({@code POST /analisis-energetico/lote}), por ejemplo al subir un CSV
 * con varias viviendas desde el frontend.
 *
 * {@code @Valid} en el campo {@code analisis} hace que Bean Validation
 * valide CADA elemento de la lista con las mismas reglas que
 * {@link AnalisisRequest} ya tiene (esto es "validación en cascada": sin
 * el {@code @Valid} aquí, Spring solo revisaría que la lista no sea nula,
 * no el contenido de cada fila).
 */
public class LoteAnalisisRequest {

    /** Una petición de análisis por fila del CSV/Excel subido (no puede venir vacía). */
    @NotEmpty(message = "el lote no puede estar vacío")
    @Valid
    private List<AnalisisRequest> analisis;

    /** @return la lista de peticiones a analizar */
    public List<AnalisisRequest> getAnalisis() {
        return analisis;
    }

    /** @param analisis la lista de peticiones a analizar */
    public void setAnalisis(List<AnalisisRequest> analisis) {
        this.analisis = analisis;
    }
}
