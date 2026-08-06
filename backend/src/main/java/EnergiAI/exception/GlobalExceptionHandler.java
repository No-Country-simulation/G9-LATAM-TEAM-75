package EnergiAI.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Captura los errores de toda la aplicación y devuelve un JSON claro y
 * consistente ({@code { "codigo": <int>, "mensaje": "<texto>" }}) en vez
 * de que cada tipo de error tenga una forma distinta (o de que algunos
 * caigan en la página de error HTML por defecto de Spring Boot).
 *
 * {@code @ControllerAdvice} le dice a Spring que esta clase aplica a
 * <b>todos</b> los controladores de la aplicación (no solo a uno en
 * particular). Cada método anotado con {@code @ExceptionHandler(TipoDeError.class)}
 * intercepta ese tipo de excepción en cualquier punto del ciclo de vida de
 * la petición: durante la conversión del JSON de entrada, durante la
 * validación con {@code @Valid}, durante el enrutamiento, o dentro del
 * propio código del controlador/servicio.
 *
 * Spring elige el método más específico para cada excepción (por ejemplo,
 * una {@code MethodArgumentNotValidException} entra por
 * {@link #handleValidation}, no por el {@code catch-all} de
 * {@link #handleGeneral}, aunque técnicamente también sea una
 * {@code Exception}).
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Se dispara cuando la validación de {@code @Valid} falla (algún campo
     * de {@link EnergiAI.dto.AnalisisRequest} no cumple sus anotaciones de
     * Bean Validation: {@code @NotNull}, {@code @Min}, {@code @Max},
     * {@code @AssertTrue}).
     *
     * Recorre todos los errores de campo (puede haber varios a la vez, ej.
     * dos campos vacíos en la misma petición) y arma un solo mensaje con
     * todos ellos, en vez de reportar solo el primero.
     *
     * @param ex la excepción que trae la lista de errores de validación
     * @return 400 Bad Request con el detalle de qué campos fallaron
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        StringBuilder mensaje = new StringBuilder("Datos inválidos: ");
        ex.getBindingResult().getFieldErrors().forEach(error ->
                mensaje.append(error.getField())
                       .append(" ")
                       .append(error.getDefaultMessage())
                       .append("; ")
        );

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("codigo", 400);
        respuesta.put("mensaje", mensaje.toString().trim());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(respuesta);
    }

    /**
     * JSON mal formado (sintaxis inválida), un campo con el tipo
     * equivocado (ej. texto donde se espera un número), o body faltante
     * en la petición. Estos casos ocurren ANTES de que Bean Validation
     * pueda actuar (Jackson ni siquiera logra construir el objeto
     * {@link EnergiAI.dto.AnalisisRequest}), así que necesitan su propio
     * manejador — sin este método, caerían en {@link #handleGeneral} y
     * el cliente recibiría un 500 en vez de un 400.
     *
     * No se expone {@code ex.getMessage()} al cliente porque suele incluir
     * detalles internos de Jackson (nombres de clases Java, rutas de
     * parseo) que no le sirven al usuario y no deberían filtrarse.
     *
     * @param ex la excepción de deserialización (su detalle se ignora)
     * @return 400 Bad Request con un mensaje genérico
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonInvalido(HttpMessageNotReadableException ex) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("codigo", 400);
        respuesta.put("mensaje", "El cuerpo de la petición no es un JSON válido o le falta información.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(respuesta);
    }

    /**
     * Ruta que no existe (ej. {@code /analisis-energetica} en vez de
     * {@code /analisis-energetico}, o cualquier URL no mapeada). Sin este
     * manejador, Spring Boot respondería con su página de error HTML por
     * defecto (el "Whitelabel Error Page"), inconsistente con el resto de
     * la API que siempre responde JSON.
     *
     * @param ex la excepción de recurso no encontrado (su detalle se ignora)
     * @return 404 Not Found en el mismo formato JSON que el resto de la API
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRutaNoEncontrada(NoResourceFoundException ex) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("codigo", 404);
        respuesta.put("mensaje", "El recurso solicitado no existe.");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
    }

    /**
     * Método HTTP no soportado en esa ruta (ej. {@code GET} en
     * {@code /analisis-energetico}, que solo acepta {@code POST}).
     *
     * @param ex trae el método HTTP que se usó (ej. "GET"), para incluirlo
     *           en el mensaje y que quede claro qué se intentó hacer
     * @return 405 Method Not Allowed
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMetodoNoSoportado(HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("codigo", 405);
        respuesta.put("mensaje", "Método " + ex.getMethod() + " no soportado en esta ruta.");

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(respuesta);
    }

    /**
     * Captura cualquier otro error no previsto por los manejadores
     * anteriores (bugs, fallos inesperados de alguna librería, etc.).
     * Es el último recurso: como {@code Exception} es la superclase de
     * (casi) todo, este método atrapa lo que ningún otro
     * {@code @ExceptionHandler} más específico haya atrapado antes.
     *
     * El detalle completo del error ({@code ex}) se registra en el log
     * del servidor para poder diagnosticarlo, pero <b>nunca</b> se manda
     * {@code ex.getMessage()} al cliente: podría revelar información
     * interna (rutas de archivos, nombres de clases, detalles de la base
     * de datos, etc.) que no le corresponde ver a quien llama a la API.
     *
     * @param ex la excepción no prevista (se loguea, no se expone)
     * @return 500 Internal Server Error con un mensaje genérico
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        logger.error("Error interno no controlado", ex);

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("codigo", 500);
        respuesta.put("mensaje", "Ocurrió un error interno, intenta de nuevo más tarde.");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
    }
}
