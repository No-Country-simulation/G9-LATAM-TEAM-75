package EnergiAI.session;

import EnergiAI.dto.AnalisisResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Guarda los análisis hechos durante la sesión HTTP actual (identificada
 * por la cookie de sesión que manda el navegador), sin persistirlos en
 * ningún lado ni compartirlos con otras sesiones/personas.
 *
 * {@code @SessionScope} le dice a Spring que cree UNA instancia distinta
 * de esta clase POR CADA sesión HTTP (por cada navegador/pestaña que
 * mantenga la cookie de sesión), en vez de una sola instancia global como
 * hacen los beans normales ({@code @Component} solo, sin {@code @Scope}).
 *
 * Como {@link EnergiAI.service.AnalisisService} es un singleton (una sola
 * instancia para toda la aplicación) pero depende de este bean de sesión,
 * Spring inyecta automáticamente un proxy: cada vez que el servicio llama
 * a un método de {@code HistorialSesion}, el proxy redirige la llamada a
 * la instancia que corresponde a la sesión de la petición HTTP actual.
 *
 * Todo esto vive en la memoria del proceso del backend: si el backend se
 * reinicia, o la sesión expira (por inactividad) o el navegador borra la
 * cookie, el historial de esa sesión desaparece — no hay base de datos
 * detrás.
 */
@Component
@SessionScope
public class HistorialSesion {

    /** Máximo de análisis que se conservan por sesión (los más viejos se descartan). */
    private static final int MAXIMO = 20;

    /** Más reciente primero. */
    private final List<AnalisisResponse> analisis = new ArrayList<>();

    /**
     * Agrega un análisis al principio de la lista (más reciente primero)
     * y descarta el más viejo si se supera {@link #MAXIMO}.
     *
     * @param respuesta el análisis recién calculado, para esta sesión
     */
    public void agregar(AnalisisResponse respuesta) {
        analisis.add(0, respuesta);
        if (analisis.size() > MAXIMO) {
            analisis.remove(analisis.size() - 1);
        }
    }

    /**
     * @return todos los análisis de esta sesión, más reciente primero.
     *         Es una vista de solo lectura: quien la reciba no puede
     *         modificar la lista interna directamente.
     */
    public List<AnalisisResponse> obtenerTodos() {
        return Collections.unmodifiableList(analisis);
    }

    /** Vacía el historial de esta sesión únicamente. */
    public void limpiar() {
        analisis.clear();
    }
}
