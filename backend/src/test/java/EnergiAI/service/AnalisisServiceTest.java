package EnergiAI.service;

import EnergiAI.client.ModeloDataClient;
import EnergiAI.client.PrediccionModelo;
import EnergiAI.dto.AnalisisRequest;
import EnergiAI.dto.AnalisisResponse;
import EnergiAI.session.HistorialSesion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link AnalisisService}: el árbol de decisión de
 * respaldo (mock), el cálculo del costo, cuándo se usa la predicción real
 * de Data vs. el mock, y cuándo se guarda (o no) en el historial de sesión.
 *
 * Es una prueba unitaria pura (sin levantar Spring): {@link ModeloDataClient}
 * y {@link HistorialSesion} se reemplazan por mocks de Mockito, así que
 * corre rápido y no depende de que el backend ni el modelo de Data estén
 * arriba.
 */
@ExtendWith(MockitoExtension.class)
class AnalisisServiceTest {

    @Mock
    private ModeloDataClient modeloDataClient;

    @Mock
    private HistorialSesion historialSesion;

    @InjectMocks
    private AnalisisService analisisService;

    /**
     * Arma una petición válida con valores por defecto razonables, para no
     * repetir los mismos 10 campos en cada prueba. Los tests que quieren
     * un escenario específico solo tocan {@code consumoKwh}/{@code picoKwh}.
     */
    private AnalisisRequest crearRequest(double consumoKwh, double picoKwh) {
        AnalisisRequest request = new AnalisisRequest();
        request.setConsumoKwh(consumoKwh);
        request.setUsoHorarioPicoKwh(picoKwh);
        request.setTamanoHogar(3);
        request.setTemperaturaPromedio(20.0);
        request.setCantidadRefrigeradores(1);
        request.setCantidadMicroondas(1);
        request.setCantidadLavadoras(1);
        request.setCantidadPantallas(1);
        request.setCantidadAireAcondicionado(0);
        request.setCantidadFocos(5);
        return request;
    }

    @Test
    void clasificaComoEficiente_cuandoConsumoBajoYPicoBajo() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        AnalisisResponse response = analisisService.analizar(crearRequest(180, 20));

        assertThat(response.getCategoria()).isEqualTo("Eficiente");
    }

    @Test
    void clasificaComoModerado_cuandoConsumoBajoPeroPicoAlto() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        // 100 kWh de pico sobre 180 de consumo = ~55%, muy por encima del 20%
        AnalisisResponse response = analisisService.analizar(crearRequest(180, 100));

        assertThat(response.getCategoria()).isEqualTo("Moderado");
    }

    @Test
    void clasificaComoModerado_cuandoConsumoAltoPeroNoExcesivo() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        // Entre 250 y 500 kWh, sin importar el pico, siempre es Moderado
        AnalisisResponse response = analisisService.analizar(crearRequest(400, 10));

        assertThat(response.getCategoria()).isEqualTo("Moderado");
    }

    @Test
    void clasificaComoIneficiente_cuandoConsumoMuyAlto() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        AnalisisResponse response = analisisService.analizar(crearRequest(520, 100));

        assertThat(response.getCategoria()).isEqualTo("Ineficiente");
    }

    @Test
    void calculaElCostoComoConsumoPorTarifaFija() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        AnalisisResponse response = analisisService.analizar(crearRequest(180, 20));

        // Tarifa de referencia del hackathon: $0.75 por kWh
        assertThat(response.getCostoEstimadoMensual()).isEqualTo(135.0);
    }

    @Test
    void usaCategoriaYProbabilidadDelModelo_cuandoDataRespondeConExito() {
        PrediccionModelo prediccion = new PrediccionModelo();
        prediccion.setCategoria("Ineficiente");
        prediccion.setProbabilidad(0.81);
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(prediccion));

        // Con estos valores el mock local diría "Eficiente"; si el modelo
        // real respondió, su predicción debe ganar siempre.
        AnalisisResponse response = analisisService.analizar(crearRequest(180, 20));

        assertThat(response.getCategoria()).isEqualTo("Ineficiente");
        assertThat(response.getProbabilidad()).isEqualTo(0.81);
    }

    @Test
    void usaRecomendacionesDelModelo_cuandoDataLasManda() {
        PrediccionModelo prediccion = new PrediccionModelo();
        prediccion.setCategoria("Eficiente");
        prediccion.setProbabilidad(0.9);
        prediccion.setRecomendaciones(List.of("Recomendación personalizada de Data"));
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(prediccion));

        AnalisisResponse response = analisisService.analizar(crearRequest(180, 20));

        assertThat(response.getRecomendaciones()).containsExactly("Recomendación personalizada de Data");
    }

    @Test
    void generaRecomendacionesPorDefecto_cuandoDataNoLasManda() {
        PrediccionModelo prediccion = new PrediccionModelo();
        prediccion.setCategoria("Eficiente");
        prediccion.setProbabilidad(0.9);
        prediccion.setRecomendaciones(null);
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(prediccion));

        AnalisisResponse response = analisisService.analizar(crearRequest(180, 20));

        assertThat(response.getRecomendaciones()).isNotEmpty();
    }

    @Test
    void guardaEnElHistorialDeSesion_cuandoNoEsUnaSimulacion() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        AnalisisRequest request = crearRequest(180, 20);
        request.setSimulacion(false);

        analisisService.analizar(request);

        verify(historialSesion).agregar(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noGuardaEnElHistorialDeSesion_cuandoEsUnaSimulacion() {
        when(modeloDataClient.predecir(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        AnalisisRequest request = crearRequest(180, 20);
        request.setSimulacion(true);

        analisisService.analizar(request);

        verify(historialSesion, never()).agregar(org.mockito.ArgumentMatchers.any());
    }
}
