package EnergiAI.controller;

import EnergiAI.dto.AnalisisRequest;
import EnergiAI.dto.AnalisisResponse;
import EnergiAI.service.AnalisisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST de la API de EnergiAI.
 *
 * Endpoints:
 * <ul>
 *   <li>{@code POST /analisis-energetico} — analiza el consumo y devuelve la
 *       clasificación, la probabilidad, el costo estimado y recomendaciones.</li>
 *   <li>{@code GET /analisis-energetico} — consulta los análisis hechos en
 *       la sesión actual (más reciente primero). No hay base de datos:
 *       vive en memoria, atado a la sesión de ESTE navegador únicamente.</li>
 *   <li>{@code GET /estado} — endpoint simple de salud, para confirmar que el
 *       backend está arriba.</li>
 * </ul>
 *
 * {@code @RestController} = {@code @Controller} + {@code @ResponseBody}: cada
 * método devuelve directamente un objeto Java que Spring serializa a JSON con
 * Jackson (no hay vistas HTML de por medio).
 *
 * {@code @CrossOrigin} habilita CORS solo para {@code http://localhost:4200}
 * (donde corre el frontend en desarrollo), para que el navegador no bloquee
 * las peticiones que hace Angular hacia este backend por venir de otro origen.
 * {@code allowCredentials = "true"} es necesario para que la cookie de
 * sesión (que identifica de quién es el historial en
 * {@link EnergiAI.session.HistorialSesion}) viaje entre el frontend
 * ({@code :4200}) y el backend ({@code :8080}) a pesar de ser orígenes
 * distintos — por eso también se exige un origen exacto arriba en vez de
 * {@code "*"} (el estándar CORS no permite combinar credenciales con
 * "cualquier origen").
 */
@CrossOrigin(origins = "http://localhost:4200", allowCredentials = "true")
@RestController
public class AnalisisController {

    /**
     * Inyectado por Spring vía el constructor (inyección de dependencias):
     * como {@link AnalisisService} está anotado con {@code @Service}, Spring
     * crea una única instancia (singleton) y se la pasa aquí automáticamente
     * al construir este controlador.
     */
    private final AnalisisService analisisService;

    public AnalisisController(AnalisisService analisisService) {
        this.analisisService = analisisService;
    }

    /**
     * Analiza un perfil de consumo eléctrico y devuelve su clasificación.
     *
     * {@code @Valid} le dice a Spring que, antes de ejecutar este método,
     * valide {@code request} contra las anotaciones de Bean Validation
     * declaradas en {@link AnalisisRequest} (por ejemplo {@code @NotNull},
     * {@code @Min}). Si algún campo no cumple, Spring lanza
     * {@code MethodArgumentNotValidException} automáticamente — este método
     * nunca llega a ejecutarse, y quien responde es
     * {@link EnergiAI.exception.GlobalExceptionHandler#handleValidation}.
     *
     * {@code @RequestBody} le dice a Spring que tome el cuerpo JSON de la
     * petición HTTP y lo convierta (deserialice) a un objeto
     * {@link AnalisisRequest} usando Jackson, antes de llamar a este método.
     *
     * @param request datos de consumo ya validados
     * @return la clasificación, probabilidad, costo estimado y recomendaciones
     */
    @PostMapping("/analisis-energetico")
    public AnalisisResponse analizar(@Valid @RequestBody AnalisisRequest request) {
        return analisisService.analizar(request);
    }

    /**
     * Consulta de resultados: devuelve los análisis hechos durante la
     * sesión HTTP actual (identificada por la cookie de sesión que manda
     * el navegador), más reciente primero.
     *
     * No hay base de datos ni almacenamiento compartido: si nunca se hizo
     * un análisis en esta sesión, devuelve una lista vacía; si el
     * navegador manda una sesión nueva (primera visita, o borró las
     * cookies), tampoco ve nada de sesiones anteriores de otra persona.
     *
     * @return los análisis de esta sesión, más reciente primero (puede
     *         ser una lista vacía)
     */
    @GetMapping("/analisis-energetico")
    public List<AnalisisResponse> historial() {
        return analisisService.obtenerHistorialSesion();
    }

    /**
     * Borra el historial de la sesión HTTP actual únicamente (no afecta a
     * otras sesiones/personas).
     */
    @DeleteMapping("/analisis-energetico")
    public void borrarHistorial() {
        analisisService.borrarHistorialSesion();
    }

    /**
     * Endpoint de salud: confirma que el backend está corriendo y respondiendo.
     * Útil para que el frontend (o quien esté probando la API) verifique
     * rápido si el servidor está arriba, sin tener que mandar un análisis
     * completo.
     *
     * @return un texto plano confirmando que la API funciona
     */
    @GetMapping("/estado")
    public String estado() {
        return "API de análisis energético funcionando correctamente";
    }
}
