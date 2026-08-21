package EnergiAI.service;

import EnergiAI.client.ModeloDataClient;
import EnergiAI.client.PrediccionModelo;
import EnergiAI.dto.AnalisisRequest;
import EnergiAI.dto.AnalisisResponse;
import EnergiAI.session.HistorialSesion;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Constructor usado por Spring para inyectar sus dos dependencias
     * ({@code @Autowired} implícito: al haber un solo constructor, Spring
     * no necesita la anotación explícita).
     *
     * @param modeloDataClient cliente hacia el microservicio de Data
     * @param historialSesion historial de la sesión HTTP actual
     */
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
     *       personalizadas según sus propios datos si no (ver {@link
     *       #recomendacionesPersonalizadas}).</li>
     *   <li>Guarda el resultado en el historial de la sesión — salvo que
     *       {@code request.isSimulacion()} sea true (ver {@link
     *       AnalisisRequest#isSimulacion()}), usado por el simulador de
     *       ahorro del frontend para probar escenarios sin ensuciar el
     *       historial real.</li>
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

        // Se repiten los demás datos de entrada tal cual llegaron, para que
        // el historial de la sesión tenga todo lo necesario (ej. para
        // exportarlo a Excel y poder volver a subirlo como un lote nuevo).
        response.setUsoHorarioPicoKwh(request.getUsoHorarioPicoKwh());
        response.setTamanoHogar(request.getTamanoHogar());
        response.setTemperaturaPromedio(request.getTemperaturaPromedio());
        response.setCantidadRefrigeradores(request.getCantidadRefrigeradores());
        response.setCantidadMicroondas(request.getCantidadMicroondas());
        response.setCantidadLavadoras(request.getCantidadLavadoras());
        response.setCantidadPantallas(request.getCantidadPantallas());
        response.setCantidadAireAcondicionado(request.getCantidadAireAcondicionado());
        response.setCantidadFocos(request.getCantidadFocos());
        response.setMes(request.getMes());
        response.setAnio(request.getAnio());

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
                recomendaciones != null ? recomendaciones : recomendacionesPersonalizadas(request, categoria));

        // Se guarda en el historial de ESTA sesión únicamente (no en una
        // base de datos compartida) para poder consultarlo después con
        // GET /analisis-energetico — excepto si es una simulación ("¿y si
        // reduzco mi consumo un 20%?"), que no debe mezclarse con los
        // análisis reales del historial.
        if (!request.isSimulacion()) {
            historialSesion.agregar(response);
        }

        return response;
    }

    /**
     * Procesamiento por lotes: analiza varias peticiones de un jalón (por
     * ejemplo, las filas de un CSV que subió el usuario), reutilizando
     * exactamente la misma lógica de {@link #analizar} para cada una —
     * incluye el mismo cálculo de costo, la misma clasificación (modelo
     * real o mock), y cada resultado real (sin {@code simulacion:true})
     * queda guardado en el historial de la sesión igual que un análisis
     * hecho a mano en el formulario.
     *
     * @param requests una petición de análisis por cada fila del lote
     * @return un resultado por cada petición, en el mismo orden que llegaron
     */
    public List<AnalisisResponse> analizarLote(List<AnalisisRequest> requests) {
        return requests.stream().map(this::analizar).toList();
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

    // Consumo diario estimado por unidad de cada tipo de equipo, en kWh.
    // Coeficientes usados para estimar, de los equipos que declaró el
    // usuario, cuál es el que más pesa en su consumo. El de aire
    // acondicionado (2.60) es el MISMO que se usó al reentrenar el
    // modelo (antes era 9.60, heredado del dataset viejo de Data — con
    // ese valor un solo A/C representaba más de la mitad de un consumo
    // mensual típico, dando ahorros irrealmente grandes).
    private static final double KWH_DIA_REFRIGERADOR = 1.20;
    private static final double KWH_DIA_MICROONDAS = 0.24;
    private static final double KWH_DIA_LAVADORA = 0.25;
    private static final double KWH_DIA_PANTALLA = 0.50;
    private static final double KWH_DIA_AIRE_ACONDICIONADO = 2.60;
    // Antes 0.54 (heredado del dataset viejo de Data) -- con ese valor los
    // focos superaban al refrigerador y hasta al A/C en las recomendaciones,
    // algo poco realista. 0.12 es el mismo valor con el que se reentrenó el modelo.
    private static final double KWH_DIA_FOCO = 0.12;

    /** Días por mes usados para convertir los coeficientes KWH_DIA_* a kWh/mes en las recomendaciones. */
    private static final int DIAS_POR_MES = 30;

    /** % mínimo del consumo en equipos que debe representar un segundo equipo para también mencionarlo. */
    private static final double UMBRAL_SEGUNDO_EQUIPO_PORCENTAJE = 20;

    /** Umbral de "mucho uso en horario pico", como % del consumo total. */
    private static final double UMBRAL_PICO_ALTO_PORCENTAJE = 25;

    /** Umbral de consumo mensual por persona considerado alto, en kWh. */
    private static final double UMBRAL_CONSUMO_POR_PERSONA_ALTO = 100;

    /**
     * Recomendaciones personalizadas según los datos reales del usuario
     * (no solo la categoría). Solo se usan cuando el microservicio de
     * Data no mandó sus propias recomendaciones (ver {@link #analizar}).
     *
     * Combina:
     * <ol>
     *   <li>Un mensaje de apertura según la categoría.</li>
     *   <li>Cuál tipo de equipo (refrigeradores, aire acondicionado, etc.)
     *       representa la mayor parte de su consumo estimado, con un
     *       consejo específico para ese equipo.</li>
     *   <li>Un aviso si una parte grande de su consumo ocurre en horario
     *       pico.</li>
     *   <li>Un aviso si su consumo por persona es alto comparado con lo
     *       típico.</li>
     * </ol>
     *
     * @param request  los mismos datos que se analizaron (para leer las
     *                 cantidades de equipos, el horario pico, etc.)
     * @param categoria una de {@code "Eficiente"}, {@code "Moderado"} o
     *                  {@code "Ineficiente"}
     * @return entre 2 y 4 recomendaciones en texto plano
    * 
    */



    private List<String> recomendacionesPersonalizadas(AnalisisRequest request, String categoria) {
        List<String> recomendaciones = new ArrayList<>();

        switch (categoria) {
            case "Eficiente":
                recomendaciones.add("Tu perfil es eficiente: mantén estos hábitos.");
                break;
            case "Moderado":
                recomendaciones.add("Estás cerca de un perfil eficiente; estos ajustes pueden ayudarte a mejorar:");
                break;
            default: // Ineficiente
                recomendaciones.add("Hay bastante margen de ahorro; empieza por lo siguiente:");
                break;
        }

        agregarConsejoDelEquipoConMasConsumo(request, recomendaciones);
        agregarAvisoDeUsoEnHorarioPico(request, recomendaciones);
        agregarAvisoDeConsumoPorPersona(request, recomendaciones);

        return recomendaciones;
    }

    /**
     * Estima cuánto aporta cada tipo de equipo al consumo diario (cantidad
     * declarada × su coeficiente de {@code KWH_DIA_*}) y agrega un consejo
     * específico sobre el que más pesa, con su consumo y costo mensual
     * estimados en números concretos (no solo el porcentaje). Si hay un
     * segundo equipo que también representa una parte importante del
     * consumo (≥ {@link #UMBRAL_SEGUNDO_EQUIPO_PORCENTAJE}), se agrega un
     * segundo consejo para él — así la recomendación no siempre gira
     * alrededor de un único equipo (típicamente el aire acondicionado)
     * entre distintos análisis. No agrega nada si el usuario no declaró
     * ningún equipo (todo en 0).
     */
    private void agregarConsejoDelEquipoConMasConsumo(AnalisisRequest request, List<String> recomendaciones) {
        Map<String, Double> aportePorEquipoDiario = new LinkedHashMap<>();
        aportePorEquipoDiario.put("Refrigeradores", request.getCantidadRefrigeradores() * KWH_DIA_REFRIGERADOR);
        aportePorEquipoDiario.put("Microondas", request.getCantidadMicroondas() * KWH_DIA_MICROONDAS);
        aportePorEquipoDiario.put("Lavadoras", request.getCantidadLavadoras() * KWH_DIA_LAVADORA);
        aportePorEquipoDiario.put("Pantallas", request.getCantidadPantallas() * KWH_DIA_PANTALLA);
        aportePorEquipoDiario.put("Aire acondicionado", request.getCantidadAireAcondicionado() * KWH_DIA_AIRE_ACONDICIONADO);
        aportePorEquipoDiario.put("Focos", request.getCantidadFocos() * KWH_DIA_FOCO);

        double totalDiario = aportePorEquipoDiario.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalDiario <= 0) {
            return;
        }

        List<Map.Entry<String, Double>> ordenadosDeMayorAMenor = aportePorEquipoDiario.entrySet().stream()
                .filter(equipo -> equipo.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        Map.Entry<String, Double> equipoTop = ordenadosDeMayorAMenor.get(0);
        recomendaciones.add(String.format(
                "%s: tu equipo con más peso en el consumo estimado. %s",
                formatearEquipoConNumeros(equipoTop, totalDiario), consejoParaEquipo(equipoTop.getKey())));

        if (ordenadosDeMayorAMenor.size() > 1) {
            Map.Entry<String, Double> segundoEquipo = ordenadosDeMayorAMenor.get(1);
            double porcentajeSegundo = (segundoEquipo.getValue() / totalDiario) * 100;
            if (porcentajeSegundo >= UMBRAL_SEGUNDO_EQUIPO_PORCENTAJE) {
                recomendaciones.add(String.format(
                        "También pesa bastante: %s. %s",
                        formatearEquipoConNumeros(segundoEquipo, totalDiario),
                        consejoParaEquipo(segundoEquipo.getKey())));
            }
        }
    }

    /**
     * Formatea un equipo con sus números concretos: kWh/mes, costo
     * estimado en $/mes (a la tarifa de referencia), y el porcentaje que
     * representa sobre el total estimado en equipos.
     *
     * @param equipo entrada equipo -> consumo diario estimado (kWh/día)
     * @param totalDiario suma del consumo diario estimado de todos los equipos
     */
    private String formatearEquipoConNumeros(Map.Entry<String, Double> equipo, double totalDiario) {
        double kwhMes = equipo.getValue() * DIAS_POR_MES;
        double costoMes = kwhMes * TARIFA_KWH;
        double porcentaje = (equipo.getValue() / totalDiario) * 100;
        return String.format("%s (~%.0f kWh/mes, ~$%.2f/mes, %.0f%% de tu consumo en equipos)",
                equipo.getKey(), kwhMes, costoMes, porcentaje);
    }

    /** Consejo concreto según qué tipo de equipo resultó ser el de mayor consumo. */
    private String consejoParaEquipo(String equipo) {
        return switch (equipo) {
            case "Refrigeradores" -> "Revisa que los empaques cierren bien y evita dejarlo abierto mucho tiempo.";
            case "Microondas" -> "Consume poco por sí solo; evita dejarlo conectado sin uso.";
            case "Lavadoras" -> "Usa cargas completas, agua fría, y evita lavar en horario pico.";
            case "Pantallas" -> "Apágalas por completo (no solo en espera) cuando no las estés usando.";
            case "Aire acondicionado" -> "Súbele 1-2°C a la temperatura (cada grado puede ahorrarte ~6-10% de su consumo) y evita usarlo en horario pico: es donde más impacto vas a notar.";
            case "Focos" -> "Si no son LED, cambiarlos puede darte un ahorro notable con poca inversión.";
            default -> "";
        };
    }

    /**
     * Si una parte grande del consumo total ocurre en horario pico
     * (por encima de {@link #UMBRAL_PICO_ALTO_PORCENTAJE}), agrega un
     * aviso puntual — mover esas actividades de horario es de los
     * cambios con más impacto en la categoría y el costo.
     */
    private void agregarAvisoDeUsoEnHorarioPico(AnalisisRequest request, List<String> recomendaciones) {
        if (request.getConsumoKwh() <= 0) {
            return;
        }
        double porcentajePico = (request.getUsoHorarioPicoKwh() / request.getConsumoKwh()) * 100;
        if (porcentajePico > UMBRAL_PICO_ALTO_PORCENTAJE) {
            double costoPico = request.getUsoHorarioPicoKwh() * TARIFA_KWH;
            recomendaciones.add(String.format(
                    "El %.0f%% de tu consumo (%.0f kWh/mes, ~$%.2f/mes) ocurre en horario pico. Mover esas "
                            + "actividades a otro horario puede bajar tu costo y mejorar tu categoría.",
                    porcentajePico, request.getUsoHorarioPicoKwh(), costoPico));
        }
    }

    /**
     * Si el consumo mensual dividido entre el número de personas del
     * hogar supera {@link #UMBRAL_CONSUMO_POR_PERSONA_ALTO}, agrega un
     * aviso — puede indicar equipos encendidos sin necesidad.
     */
    private void agregarAvisoDeConsumoPorPersona(AnalisisRequest request, List<String> recomendaciones) {
        if (request.getTamanoHogar() <= 0) {
            return;
        }
        double consumoPorPersona = request.getConsumoKwh() / request.getTamanoHogar();
        if (consumoPorPersona > UMBRAL_CONSUMO_POR_PERSONA_ALTO) {
            double costoPorPersona = consumoPorPersona * TARIFA_KWH;
            recomendaciones.add(String.format(
                    "Tu consumo por persona (~%.0f kWh/mes, ~$%.2f/mes) está por encima de lo típico; "
                            + "vale la pena revisar si hay equipos encendidos sin uso.",
                    consumoPorPersona, costoPorPersona));
        }
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
