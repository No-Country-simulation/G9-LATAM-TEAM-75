package EnergiAI.client;

import EnergiAI.dto.AnalisisRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Llama al microservicio de clasificación del equipo de Data cuando ya
 * exista una URL configurada (propiedad {@code data.modelo.url} en
 * {@code application.properties}, o variable de entorno
 * {@code DATA_MODELO_URL} — Spring Boot mapea automáticamente esa variable
 * de entorno a la propiedad, sin configuración extra).
 *
 * Mientras esa URL no esté configurada, o si falla la llamada (el servicio
 * está caído, la sesión de Colab se cerró, hay timeout de red, etc.),
 * devuelve {@code Optional.empty()} y {@link EnergiAI.service.AnalisisService}
 * usa el mock local como respaldo — así la app nunca se rompe aunque el
 * microservicio de Data no esté listo o esté caído.
 *
 * {@code @Component} le dice a Spring que cree una única instancia
 * (singleton) de esta clase y la inyecte donde haga falta (aquí, en
 * {@link EnergiAI.service.AnalisisService}).
 */
@Component
public class ModeloDataClient {

    private static final Logger logger = LoggerFactory.getLogger(ModeloDataClient.class);

    /**
     * Cliente HTTP síncrono de Spring para hacer la petición POST al
     * microservicio de Data. Se crea una sola vez (campo final) y se
     * reutiliza en cada llamada a {@link #predecir}.
     */
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * URL del microservicio de Data, leída de la propiedad
     * {@code data.modelo.url}. El {@code :} dentro de
     * {@code ${data.modelo.url:}} es un valor por defecto vacío: si la
     * propiedad no está definida en absoluto, Spring inyecta {@code ""}
     * en vez de fallar al arrancar la aplicación.
     */
    private final String url;

    /**
     * Constructor usado por Spring para inyectar el valor de la propiedad
     * {@code data.modelo.url} vía {@code @Value}.
     *
     * @param url la URL configurada (puede venir vacía)
     */
    public ModeloDataClient(@Value("${data.modelo.url:}") String url) {
        this.url = url;
    }

    /**
     * @return true si hay una URL configurada (no nula ni vacía/en blanco)
     */
    public boolean estaConfigurado() {
        return url != null && !url.isBlank();
    }

    /**
     * Intenta obtener una predicción real del microservicio de Data.
     *
     * Le manda por POST el mismo objeto {@code request} que llegó al
     * backend (Spring/Jackson lo serializa a JSON automáticamente con los
     * mismos nombres {@code @JsonProperty} que usa la API), y espera de
     * vuelta un JSON que se deserializa a {@link PrediccionModelo}.
     *
     * @param request los datos de consumo ya validados
     * @return la predicción del modelo si la URL está configurada y la
     *         llamada tuvo éxito; {@code Optional.empty()} en cualquier
     *         otro caso (sin URL configurada, o cualquier excepción de
     *         red/HTTP/deserialización)
     */
    public Optional<PrediccionModelo> predecir(AnalisisRequest request) {
        if (!estaConfigurado()) {
            return Optional.empty();
        }

        try {
            PrediccionModelo prediccion = restTemplate.postForObject(url, request, PrediccionModelo.class);
            return Optional.ofNullable(prediccion);
        } catch (Exception e) {
            // Se captura Exception (no un tipo más específico) a propósito:
            // cualquier falla al hablar con un servicio externo (timeout,
            // conexión rechazada, JSON inesperado, error 500 del otro lado,
            // etc.) debe degradar limpiamente al mock, nunca tumbar la API.
            logger.warn("No se pudo contactar el microservicio de Data en {}, se usa el mock local", url, e);
            return Optional.empty();
        }
    }
}
