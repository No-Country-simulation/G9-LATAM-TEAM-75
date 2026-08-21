package EnergiAI.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configura CORS para toda la API. Reemplaza al {@code @CrossOrigin} que
 * antes estaba directo en {@link EnergiAI.controller.AnalisisController}:
 * las anotaciones de Spring solo aceptan valores constantes en tiempo de
 * compilación, así que no se puede leer la URL permitida desde
 * {@code application.properties} con {@code @Value} dentro de un
 * {@code @CrossOrigin("...")} — por eso se necesita este bean, que sí
 * puede leer la propiedad en tiempo de ejecución.
 *
 * En desarrollo local, el origen permitido es {@code http://localhost:4200}
 * (el valor por defecto). Al desplegar (ej. en Railway), se cambia con la
 * variable de entorno {@code CORS_ALLOWED_ORIGIN} a la URL real donde
 * quede publicado el frontend — sin tocar el código.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Origen(es) permitidos para CORS, separados por coma si hay más de
     * uno (ej. para probar con el frontend de producción y el de
     * desarrollo local a la vez).
     */
    @Value("${app.cors.allowed-origin:http://localhost:4200}")
    private String allowedOrigins;

    /**
     * {@code allowCredentials(true)} es necesario para que la cookie de
     * sesión (que identifica de quién es el historial en
     * {@link EnergiAI.session.HistorialSesion}) viaje entre el frontend y
     * el backend a pesar de ser orígenes distintos — por eso también se
     * exige un origen exacto en vez de {@code "*"} (el estándar CORS no
     * permite combinar credenciales con "cualquier origen").
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
