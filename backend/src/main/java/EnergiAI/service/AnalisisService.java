package EnergiAI.service;

import EnergiAI.client.ModeloDataClient;
import EnergiAI.client.PrediccionModelo;
import EnergiAI.dto.AnalisisRequest;
import EnergiAI.dto.AnalisisResponse;
import EnergiAI.session.HistorialSesion;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lógica del análisis energético.
 *
 * {@code @Service} le dice a Spring que cree una única instancia (singleton)
 * de esta clase y la inyecte donde haga falta (aquí, en
 * {@link EnergiAI.controller.AnalisisController}).
 *
 * IMPORTANTE:
 *  - El CÁLCULO DEL COSTO sí es responsabilidad del backend (esto es real).
 *  - La CLASIFICACIÓN (Eficiente/Moderado/Ineficiente) la hace el microservicio
 *    del equipo de Data cuando ModeloDataClient tiene una URL configurada
 *    (propiedad data.modelo.url). Mientras esa URL no exista, o si la llamada
 *    falla, se usa el MOCK de abajo, que aplica EXACTAMENTE el árbol de
 *    decisión que Data definió, para poder probar el backend sin depender
 *    de que el microservicio esté arriba.
 */
@Service
public class AnalisisService {

    /** Tarifa de referencia del hackathon: $0.75 por kWh. */
    private static final double TARIFA_KWH = 0.75;

    /**
     * Cliente HTTP hacia el microservicio de Data. Inyectado por Spring vía
     * el constructor (ver {@link #AnalisisService(ModeloDataClient)}).
     */
    private final ModeloDataClient modeloDataClient;

    /**
     * Historial de la sesión HTTP actual (en memoria, sin base de datos —
     * ver {@link HistorialSesion}). Aunque este servicio es un singleton,
     * Spring inyecta aquí un proxy que resuelve la sesión correcta en
     * cada petición.
     */
    private final HistorialSesion historialSesion;

    public AnalisisService(ModeloDataClient modeloDataClient, HistorialSesion historialSesion) {
        this.modeloDataClient = modeloDataClient;
        this.historialSesion = historialSesion;
    }

    /**
     * Orquesta el análisis completo de un perfil de consumo:
     * <ol>
     *   <li>Intenta obtener la clasificación del modelo real de Data
     *       (vía {@link ModeloDataClient#predecir}).</li>
     *   <li>Si no hay respuesta (URL no configurada, o la llamada falló),
     *       usa el mock local ({@link #clasificarSegunReglasDeData}).</li>
     *   <li>Calcula el costo mensual estimado (esto SIEMPRE lo hace este
     *       backend, nunca Data — no es una responsabilidad del modelo de
     *       clasificación).</li>
     *   <li>Arma las recomendaciones: las del modelo si las mandó, o unas
     *       genéricas por categoría si no.</li>
     * </ol>
     *
     * @param request datos de consumo ya validados por Bean Validation
     *                 (ver {@code @Valid} en el controlador)
     * @return la clasificación, probabilidad, costo estimado y recomendaciones
     */
    public AnalisisResponse analizar(AnalisisRequest request) {
        AnalisisResponse response = new AnalisisResponse();
        response.setFecha(LocalDateTime.now());
        response.setConsumoKwh(request.getConsumoKwh());

        Optional<PrediccionModelo> prediccion = modeloDataClient.predecir(request);

        String categoria;
        double probabilidad;

        if (prediccion.isPresent()) {
            // ===== Respuesta real del microservicio de Data =====
            categoria = prediccion.get().getCategoria();
            probabilidad = prediccion.get().getProbabilidad();
        } else {
            // ===== MOCK (mientras no haya microservicio de Data disponible) =====
            categoria = clasificarSegunReglasDeData(request);
            // El árbol de reglas no da probabilidad; el modelo real sí la da.
            probabilidad = 1.0;
        }

        response.setCategoria(categoria);
        response.setProbabilidad(probabilidad);

        // Cálculo real del costo (responsabilidad del backend, no de Data)
        double costo = request.getConsumoKwh() * TARIFA_KWH;
        response.setCostoEstimadoMensual(redondear(costo));

        // Si el microservicio de Data mandó sus propias recomendaciones,
        // se respetan tal cual; si no las mandó (o no respondió y se usó
        // el mock), se generan unas por defecto según la categoría.
        List<String> recomendaciones = prediccion.map(PrediccionModelo::getRecomendaciones).orElse(null);
        response.setRecomendaciones(
                recomendaciones != null ? recomendaciones : recomendacionesSegunData(categoria));

        // Se guarda en el historial de ESTA sesión únicamente (no en una
        // base de datos compartida) para poder consultarlo después con
        // GET /analisis-energetico.
        historialSesion.agregar(response);

        return response;
    }

    /**
     * Consulta de resultados: devuelve todos los análisis hechos durante
     * la sesión HTTP actual, más reciente primero. No hay base de datos
     * detrás — es exactamente lo que guardó {@link HistorialSesion} en
     * memoria para esta sesión, nada compartido con otras personas.
     *
     * @return el historial de esta sesión (puede estar vacío si todavía
     *         no se ha hecho ningún análisis en ella)
     */
    public List<AnalisisResponse> obtenerHistorialSesion() {
        return historialSesion.obtenerTodos();
    }

    /**
     * Borra el historial de la sesión actual únicamente (no afecta a
     * otras sesiones/personas).
     */
    public void borrarHistorialSesion() {
        historialSesion.limpiar();
    }

    /**
     * Árbol de decisión de respaldo (aproximado, mientras no haya
     * microservicio de Data disponible):
     *
     * <pre>
     *   ¿Consumo <= 250 kWh?
     *     Sí -> ¿Uso en horario pico <= 20% del consumo?
     *              Sí -> Eficiente
     *              No -> Moderado
     *     No -> ¿Consumo > 500 kWh?
     *              Sí -> Ineficiente
     *              No -> Moderado
     * </pre>
     *
     * NOTA: "uso_horario_pico_kwh" llega en kWh absolutos (así lo mide el
     * dataset de Data: columna Peak_Hours_Usage_kWh), así que aquí se
     * convierte a porcentaje del consumo total para aplicar el mismo
     * umbral del 20%.
     *
     * El modelo real de Data (DecisionTreeClassifier) NO usa el consumo
     * directamente como variable (lo excluyeron para evitar fuga de datos,
     * ya que la etiqueta se calculó a partir de esa misma columna); usa
     * Household_Size, Avg_Temperature_C, Has_AC, Peak_Hours_Usage_kWh,
     * uso_horario_pico y kWh/Individuo. Este mock es solo una aproximación
     * para no depender de que el microservicio esté arriba.
     *
     * @param request datos de consumo (ya validados: consumoKwh y
     *                 usoHorarioPicoKwh nunca son null aquí)
     * @return una de {@code "Eficiente"}, {@code "Moderado"} o
     *         {@code "Ineficiente"}
     */
    private String clasificarSegunReglasDeData(AnalisisRequest request) {
        double consumo = request.getConsumoKwh();
        double picoKwh = request.getUsoHorarioPicoKwh();
        // Evita división entre cero si alguna vez consumo llega en 0
        // (Bean Validation solo exige >= 0, no > 0).
        double porcentajePico = consumo > 0 ? (picoKwh / consumo) * 100 : 0;

        if (consumo <= 250) {
            if (porcentajePico <= 20) {
                return "Eficiente";
            } else {
                return "Moderado";
            }
        } else {
            if (consumo > 500) {
                return "Ineficiente";
            } else {
                return "Moderado";
            }
        }
    }

    /**
     * Recomendaciones genéricas según la categoría (basadas en los
     * ejemplos de Data). Solo se usan cuando el microservicio de Data no
     * mandó sus propias recomendaciones (ver {@link #analizar}).
     *
     * @param categoria una de {@code "Eficiente"}, {@code "Moderado"} o
     *                  {@code "Ineficiente"} (cualquier otro valor cae en
     *                  el caso {@code default}, tratado como Ineficiente)
     * @return una lista de 2 recomendaciones en texto plano
     */
    private List<String> recomendacionesSegunData(String categoria) {
        List<String> recomendaciones = new ArrayList<>();

        switch (categoria) {
            case "Eficiente":
                recomendaciones.add("Continúe con sus hábitos");
                recomendaciones.add("Mantenga el mantenimiento de los equipos");
                break;
            case "Moderado":
                recomendaciones.add("Reducir el consumo en horas pico");
                recomendaciones.add("Revisar el consumo general");
                break;
            default: // Ineficiente
                recomendaciones.add("Optimizar el uso de equipos");
                recomendaciones.add("Revisar el consumo en horas pico");
                break;
        }

        return recomendaciones;
    }

    /**
     * Redondea a 2 decimales (centavos), evitando el arrastre de errores
     * de punto flotante típico de trabajar directo con {@code double}
     * (ej. que un cálculo dé 134.99999999999997 en vez de 135.0).
     *
     * @param valor el número a redondear
     * @return {@code valor} redondeado a 2 decimales
     */
    private double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
