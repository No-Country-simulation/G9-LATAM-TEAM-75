package EnergiAI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la API de EnergiAI.
 *
 * {@code @SpringBootApplication} habilita en un solo paso:
 * <ul>
 *   <li>{@code @Configuration}: esta clase puede declarar beans de Spring.</li>
 *   <li>{@code @EnableAutoConfiguration}: Spring Boot configura automáticamente
 *       Tomcat embebido, Jackson (JSON), el validador de Bean Validation, etc.
 *       según qué dependencias (starters) haya en el {@code pom.xml}.</li>
 *   <li>{@code @ComponentScan}: escanea el paquete {@code EnergiAI} y sus
 *       subpaquetes ({@code controller}, {@code service}, {@code client},
 *       {@code exception}) para encontrar y registrar los beans anotados
 *       con {@code @RestController}, {@code @Service}, {@code @Component},
 *       {@code @ControllerAdvice}, etc.</li>
 * </ul>
 */
@SpringBootApplication
public class EnergiAiApplication {

	/**
	 * Arranca la aplicación: levanta el contenedor de Spring y el servidor
	 * web embebido (Tomcat) en el puerto configurado (por defecto 8080).
	 *
	 * @param args argumentos de línea de comandos (no se usan en este proyecto)
	 */
	public static void main(String[] args) {
		SpringApplication.run(EnergiAiApplication.class, args);
	}

}
