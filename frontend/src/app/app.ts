import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs';
import ExcelJS from 'exceljs';
import { environment } from '../environments/environment';

/**
 * Forma de la respuesta que devuelve el backend, tanto en
 * `POST /analisis-energetico` como en cada ítem de
 * `GET /analisis-energetico` (ver `AnalisisResponse.java`). Los nombres
 * en snake_case (`costo_estimado_mensual`, `consumo_kwh`) coinciden a
 * propósito con las propiedades JSON que manda Jackson en el backend.
 *
 * `fecha` la pone el backend siempre (nunca la manda el frontend): es el
 * momento en que se calculó ese análisis.
 */
interface AnalisisResponse {
  fecha: string;
  categoria: string;
  probabilidad: number;
  recomendaciones: string[];
  costo_estimado_mensual: number;
  consumo_kwh: number;
  // Resto de los datos de entrada, repetidos por el backend (ver
  // AnalisisResponse.java) para poder exportar el historial completo a
  // Excel y volver a subirlo como un lote nuevo si hace falta.
  uso_horario_pico_kwh: number;
  tamano_hogar: number;
  temperatura_promedio: number;
  refrigeradores: number;
  microondas: number;
  lavadoras: number;
  pantallas: number;
  aire_acondicionado: number;
  focos: number;
  // Mes/año del recibo (formulario normal o columna opcional del
  // análisis por lotes), o null/undefined si no se indicó.
  mes?: string | null;
  anio?: number | null;
}

/**
 * Diferencia entre el análisis actual y el anterior en el historial de
 * esta sesión, usada por la sección "Comparación con tu análisis anterior".
 */
interface Comparacion {
  deltaCosto: number;
  deltaCostoPct: number;
  deltaConsumo: number;
  mejoro: boolean;
  empeoro: boolean;
}

/**
 * Un punto del mini-gráfico de barras del historial: el análisis original
 * más la altura (en %) que debe tener su barra, ya calculada.
 */
interface BarraHistorial {
  item: AnalisisResponse;
  /** Alto de la barra como porcentaje (0-100) del análisis más caro mostrado. */
  alturaPct: number;
}

/**
 * Un resultado del análisis por lotes, junto con el mes al que corresponde
 * esa fila (columna "mes" del CSV/Excel subido; no viaja al backend, es
 * solo para mostrarla en la tabla de resultados).
 */
interface FilaLote {
  mes: string;
  resultado: AnalisisResponse;
}

/**
 * Componente raíz (y único, por ahora) de la app: formulario de análisis,
 * panel de resultado, KPIs del dashboard, e historial de la sesión.
 *
 * Es un componente *standalone* (no depende de un NgModule) que importa
 * directamente lo que necesita (`CommonModule` para `*ngIf`/`*ngFor`,
 * `FormsModule` para `[(ngModel)]`).
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  // ---- Campos del formulario (alineados con el dataset real de Data) ----
  // Cada uno está enlazado en app.html con [(ngModel)] (two-way binding):
  // cuando el usuario escribe en el input, Angular actualiza esta
  // propiedad automáticamente, y viceversa.

  /** Consumo eléctrico mensual en kWh, tal como lo escribe el usuario. */
  consumoKwh: number | null = null;
  /** Consumo mensual en horario pico, en kWh. */
  usoHorarioPicoKwh: number | null = null;
  /** Cantidad de personas que viven en el hogar. */
  tamanoHogar: number | null = null;
  /** Temperatura promedio de la zona, en °C. */
  temperaturaPromedio: number | null = null;
  /** Cantidad de refrigeradores en la vivienda. Arranca en 0 (no tiene). */
  cantidadRefrigeradores: number = 0;
  /** Cantidad de microondas en la vivienda. Arranca en 0 (no tiene). */
  cantidadMicroondas: number = 0;
  /** Cantidad de lavadoras en la vivienda. Arranca en 0 (no tiene). */
  cantidadLavadoras: number = 0;
  /** Cantidad de pantallas/televisores en la vivienda. Arranca en 0 (no tiene). */
  cantidadPantallas: number = 0;
  /** Cantidad de equipos de aire acondicionado en la vivienda. Arranca en 0 (no tiene). */
  cantidadAireAcondicionado: number = 0;
  /** Cantidad de focos/lámparas en la vivienda. Arranca en 0 (no tiene). */
  cantidadFocos: number = 0;
  /** Mes al que corresponde este recibo (ej. "Enero"), opcional. */
  mes: string | null = null;
  /** Año al que corresponde este recibo, opcional. Arranca en el año actual. */
  anio: number | null = new Date().getFullYear();
  /** Años que se ofrecen en el selector: el actual y los 4 anteriores. */
  readonly aniosDisponibles: number[] = Array.from(
    { length: 5 },
    (_, i) => new Date().getFullYear() - i,
  );
  /** Meses que se ofrecen en el selector. */
  readonly mesesDisponibles: string[] = [
    'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre',
  ];

  // ---- Estado de la respuesta ----
  // signal() en vez de propiedades normales: la app corre sin zone.js
  // (Angular zoneless), y solo los signals disparan un re-render cuando
  // cambian desde un callback async como la respuesta HTTP. Si estos
  // fueran propiedades planas (`resultado: AnalisisResponse | null = null`),
  // la plantilla NO se actualizaría al llegar la respuesta del backend.

  /** Resultado del último análisis exitoso, o null si no hay ninguno aún. */
  resultado = signal<AnalisisResponse | null>(null);
  /** Mensaje de error a mostrar (vacío = sin error). */
  error = signal('');
  /** true mientras la petición al backend está en curso. */
  cargando = signal(false);

  /**
   * true mientras la pantalla flotante de "alerta de alto consumo" está
   * visible. Se abre sola cuando un análisis nuevo da "Ineficiente" (ver
   * {@link analizar}), y también se puede reabrir a mano con el botón
   * "Ver alerta de alto consumo" junto al resultado.
   */
  alertaAbierta = signal(false);

  // ---- Simulador de ahorro ----
  // Le pregunta al backend "¿y si reduzco mi consumo en horario pico un
  // X%?" sin guardar esa hipótesis en el historial real (se manda
  // `simulacion: true`, ver AnalisisRequest.java).

  /** Porcentaje de reducción en horario pico a simular (enlazado al slider). */
  porcentajeReduccion = 20;
  /**
   * Cuántos equipos de aire acondicionado seguiría usando en el
   * escenario simulado (enlazado al segundo slider: 0 hasta
   * {@link cantidadAireAcondicionado}). No es un porcentaje — a
   * diferencia del pico, el A/C se cuenta en unidades enteras (1, 2,
   * 3...), así que tiene más sentido elegir cuántos apagaría que un %
   * de "uso". Se inicializa al valor actual (sin reducción) cada vez
   * que hay un análisis nuevo, ver {@link analizar}.
   */
  aireAcondicionadoSimulado: number | null = null;
  /**
   * Consumo diario estimado por unidad de aire acondicionado, en kWh.
   * MISMO coeficiente que usa el backend (KWH_DIA_AIRE_ACONDICIONADO en
   * AnalisisService.java) para poder estimar aquí cuánto reduce el
   * consumo total bajar el uso de este equipo en particular. Ajustado a
   * 2.6 (el mismo valor con el que se reentrenó el modelo) — el 9.6
   * original venía del dataset viejo de Data y hacía que un solo A/C
   * "ahorrara" más de la mitad de un consumo mensual típico.
   */
  private readonly KWH_DIA_AIRE_ACONDICIONADO = 2.6;
  /** Días por mes usados para convertir KWH_DIA_AIRE_ACONDICIONADO a kWh/mes. */
  private readonly DIAS_POR_MES = 30;
  /** MISMA tarifa de referencia que usa el backend (TARIFA_KWH en AnalisisService.java). */
  private readonly TARIFA_KWH = 0.75;

  /**
   * Ahorro mensual estimado ($) que corresponde solo a reducir el uso en
   * horario pico, calculado en el cliente para poder mostrar el ahorro
   * "paso a paso" (primero pico, luego A/C) en vez de solo el total.
   */
  ahorroPicoMensual = signal(0);
  /**
   * Ahorro mensual estimado ($) que corresponde solo a reducir la
   * cantidad de aires acondicionados en uso, adicional al de arriba.
   */
  ahorroAireAcondicionadoMensual = signal(0);

  /** Resultado de la última simulación, o null si no se ha simulado nada. */
  simulacion = signal<AnalisisResponse | null>(null);
  /** true mientras la simulación está en curso. */
  simulando = signal(false);
  /** Mensaje de error del simulador (vacío = sin error). Separado de
   *  {@link error} para no pisar un error del análisis principal. */
  errorSimulacion = signal('');

  // ---- Historial (guardado por el backend, atado a la sesión del navegador) ----

  /**
   * Lista de análisis de esta sesión, más reciente primero. Arranca vacía
   * y se llena al inicio desde el backend (ver {@link ngOnInit}), no
   * desde `sessionStorage`: quien guarda el historial ahora es
   * `HistorialSesion.java`, en memoria del backend, identificado por la
   * cookie de sesión del navegador.
   */
  historial = signal<AnalisisResponse[]>([]);

  /** URL completa del endpoint de análisis del backend. */
  private readonly API_URL = `${environment.apiUrl}/analisis-energetico`;

  /**
   * Todas las peticiones a la API van con `withCredentials: true` para
   * que el navegador mande y reciba la cookie de sesión (`JSESSIONID`)
   * a pesar de que frontend (`:4200`) y backend (`:8080`) son orígenes
   * distintos. Sin esto, cada petición llegaría con una sesión distinta
   * y el backend nunca reconocería el historial de esta pestaña.
   */
  private readonly OPCIONES_HTTP = { withCredentials: true };

  /**
   * `HttpClient` se inyecta por el constructor (inyección de dependencias
   * de Angular): quien crea este componente no necesita saber cómo
   * construirlo, Angular se lo pasa automáticamente porque fue registrado
   * con `provideHttpClient()` en `app.config.ts`.
   */
  constructor(private http: HttpClient) {}

  /**
   * Se ejecuta una sola vez, apenas Angular termina de crear el
   * componente: carga el historial que ya tuviera esta sesión en el
   * backend (por ejemplo si el usuario recargó la página).
   */
  ngOnInit() {
    this.cargarHistorialDesdeBackend();
  }

  /**
   * Trae el historial de esta sesión desde `GET /analisis-energetico`.
   * Si falla (backend apagado, etc.) simplemente lo deja vacío y lo
   * registra en la consola — no bloquea el resto de la app, el usuario
   * puede seguir haciendo análisis nuevos sin problema.
   */
  private cargarHistorialDesdeBackend() {
    this.http.get<AnalisisResponse[]>(this.API_URL, this.OPCIONES_HTTP).subscribe({
      next: (items) => this.historial.set(items),
      error: (err) => console.error('No se pudo cargar el historial', err),
    });
  }

  /**
   * Handler del botón "Analizar mi consumo".
   *
   * Flujo:
   * 1. Limpia el error y el resultado anterior.
   * 2. Valida el formulario en el navegador (ver {@link validarFormulario});
   *    si algo está mal, muestra el error y no llega a llamar al backend.
   * 3. Arma el JSON con los nombres de campo en snake_case que espera la
   *    API (`AnalisisRequest.java`) y lo manda por POST.
   * 4. Si responde a tiempo (antes de 10s): guarda el resultado y lo
   *    agrega al historial de esta pestaña.
   * 5. Si falla (timeout, sin conexión, error de validación del backend,
   *    error interno): muestra un mensaje específico según el tipo de
   *    falla, para que el usuario entienda qué pasó sin tener que abrir
   *    las herramientas de desarrollador.
   */
  analizar() {
    this.error.set('');
    this.resultado.set(null);
    // Un análisis nuevo invalida cualquier simulación del resultado anterior.
    this.simulacion.set(null);
    this.errorSimulacion.set('');

    const errorValidacion = this.validarFormulario();
    if (errorValidacion) {
      this.error.set(errorValidacion);
      return;
    }

    this.cargando.set(true);

    // Arma el JSON tal como lo espera el backend
    const datos = {
      consumo_kwh: this.consumoKwh,
      uso_horario_pico_kwh: this.usoHorarioPicoKwh,
      tamano_hogar: this.tamanoHogar,
      temperatura_promedio: this.temperaturaPromedio,
      refrigeradores: this.cantidadRefrigeradores,
      microondas: this.cantidadMicroondas,
      lavadoras: this.cantidadLavadoras,
      pantallas: this.cantidadPantallas,
      aire_acondicionado: this.cantidadAireAcondicionado,
      focos: this.cantidadFocos,
      mes: this.mes,
      anio: this.anio,
    };

    this.http
      .post<AnalisisResponse>(this.API_URL, datos, this.OPCIONES_HTTP)
      // Si el backend (o el modelo de Data detrás de él) no responde en
      // 10 segundos, se considera una falla en vez de dejar el botón
      // "Analizando..." colgado para siempre.
      .pipe(timeout(10000))
      .subscribe({
        next: (respuesta) => {
          this.resultado.set(respuesta);
          this.cargando.set(false);
          // El backend ya lo guardó en el historial de esta sesión
          // (HistorialSesion.java); solo hace falta reflejarlo acá
          // también, sin tener que volver a pedirlo por GET.
          this.historial.set([respuesta, ...this.historial()]);
          // La alerta de alto consumo se abre sola cuando el resultado
          // es "Ineficiente", con todas las recomendaciones adentro.
          this.alertaAbierta.set(respuesta.categoria === 'Ineficiente');
          // El slider de "cuántos A/C seguirías usando" arranca en el
          // valor actual (sin reducción) para este análisis nuevo.
          this.aireAcondicionadoSimulado = this.cantidadAireAcondicionado;
        },
        error: (err) => {
          this.cargando.set(false);
          console.error(err);

          if (err.name === 'TimeoutError') {
            this.error.set(
              'El servidor no respondió en 10 segundos. Revisa que el backend esté corriendo en localhost:8080, o que una extensión del navegador no esté bloqueando la petición (prueba en una ventana de incógnito).',
            );
          } else if (err.status === 0) {
            // status 0 = el navegador ni siquiera logró conectarse
            // (servidor apagado, CORS bloqueado, DNS, etc.)
            this.error.set(
              'No se pudo conectar con el servidor (conexión rechazada o bloqueada). Revisa que el backend esté corriendo en localhost:8080.',
            );
          } else if (err.error?.mensaje) {
            // El backend sí respondió, pero con un error (400/404/405/500):
            // err.error es el cuerpo JSON { codigo, mensaje } que arma
            // GlobalExceptionHandler.java — se muestra ese mensaje tal cual,
            // ya viene en español y listo para el usuario.
            this.error.set(err.error.mensaje);
          } else {
            this.error.set(`Error del servidor (código ${err.status}).`);
          }
        },
      });
  }

  /**
   * Valida el formulario en el navegador con las MISMAS reglas que ya
   * valida el backend (`AnalisisRequest.java`), para avisarle al usuario
   * al instante en vez de esperar el viaje de ida y vuelta a la red.
   *
   * Esto es una validación de UX, no de seguridad: el backend vuelve a
   * validar todo de cero (nunca hay que confiar en que el frontend ya lo
   * hizo), así que si alguien llama a la API directo sin pasar por este
   * formulario, igual queda protegido.
   *
   * @returns un mensaje de error en español si algo está mal, o `null`
   *          si el formulario es válido y se puede enviar
   */
  private validarFormulario(): string | null {
    if (
      this.consumoKwh == null ||
      this.usoHorarioPicoKwh == null ||
      this.tamanoHogar == null ||
      this.temperaturaPromedio == null ||
      this.cantidadRefrigeradores == null ||
      this.cantidadMicroondas == null ||
      this.cantidadLavadoras == null ||
      this.cantidadPantallas == null ||
      this.cantidadAireAcondicionado == null ||
      this.cantidadFocos == null
    ) {
      return 'Completa todos los campos antes de continuar.';
    }
    if (this.consumoKwh < 0) {
      return 'El consumo no puede ser negativo.';
    }
    if (this.usoHorarioPicoKwh < 0) {
      return 'El uso en horario pico no puede ser negativo.';
    }
    if (this.usoHorarioPicoKwh > this.consumoKwh) {
      return 'El uso en horario pico no puede ser mayor que el consumo total.';
    }
    if (
      this.cantidadRefrigeradores < 0 ||
      this.cantidadMicroondas < 0 ||
      this.cantidadLavadoras < 0 ||
      this.cantidadPantallas < 0 ||
      this.cantidadAireAcondicionado < 0 ||
      this.cantidadFocos < 0
    ) {
      return 'La cantidad de equipos no puede ser negativa.';
    }
    if (this.tamanoHogar < 1 || this.tamanoHogar > 30) {
      return 'El número de personas debe estar entre 1 y 30.';
    }
    if (this.temperaturaPromedio < -30 || this.temperaturaPromedio > 55) {
      return 'La temperatura promedio debe estar entre -30°C y 55°C.';
    }
    return null;
  }

  /**
   * Traduce una categoría a la clase CSS que le da su color (ver
   * `.eficiente`/`.moderado`/`.ineficiente` en `app.css`). Se usa tanto en
   * la tarjeta de resultado como en los ítems del historial y los KPIs.
   *
   * @param categoria "Eficiente" | "Moderado" | "Ineficiente" (u otro
   *                  valor/`undefined`, que no debería ocurrir en la
   *                  práctica pero se maneja sin romper la UI)
   * @returns el nombre de la clase CSS, o `''` si la categoría no se
   *          reconoce (la tarjeta simplemente no lleva color especial)
   */
  claseCategoria(categoria: string | undefined): string {
    switch (categoria) {
      case 'Eficiente':
        return 'eficiente';
      case 'Moderado':
        return 'moderado';
      case 'Ineficiente':
        return 'ineficiente';
      default:
        return '';
    }
  }

  /**
   * Ícono (emoji) asociado a cada categoría, para reforzar visualmente
   * el resultado sin depender solo del color.
   *
   * @param categoria "Eficiente" | "Moderado" | "Ineficiente" (u otro/undefined)
   * @returns el emoji correspondiente, o `''` si no se reconoce
   */
  iconoCategoria(categoria: string | undefined): string {
    switch (categoria) {
      case 'Eficiente':
        return '✅';
      case 'Moderado':
        return '⚠️';
      case 'Ineficiente':
        return '🔴';
      default:
        return '';
    }
  }

  /** Handler del botón "Ver alerta de alto consumo": reabre la pantalla flotante. */
  abrirAlerta() {
    this.alertaAbierta.set(true);
  }

  /** Cierra la pantalla flotante de alerta (botón "✕", "Entendido", o clic afuera). */
  cerrarAlerta() {
    this.alertaAbierta.set(false);
  }

  /**
   * Porcentaje que se muestra en el anillo de confianza de la tarjeta de
   * resultado (`[style.--pct.%]` en app.html). Fijo en 98 a propósito
   * (decisión explícita, no un cálculo real) — antes reflejaba la
   * probabilidad que devolvía el modelo (0-1 convertido a %).
   *
   * @returns 98, si ya hay un resultado; 0 si todavía no hay ninguno
   */
  porcentajeProbabilidad(): number {
    return this.resultado() ? 98 : 0;
  }

  // ---- Simulador de ahorro ----

  /**
   * Handler del slider "¿Y si reduces tu consumo en horario pico?": le
   * resta a `consumoKwh` y a `usoHorarioPicoKwh` el porcentaje elegido
   * (aplicado sobre el pico, ya que reducir el pico reduce el total en
   * esa misma cantidad de kWh), y le pide al backend que clasifique ese
   * escenario hipotético — marcado con `simulacion: true` para que
   * {@code AnalisisService} no lo guarde en el historial real.
   *
   * No hace nada si todavía no hay un análisis real hecho (el botón de
   * simular solo aparece junto al resultado).
   */
  simularAhorro() {
    const actual = this.resultado();
    if (
      !actual ||
      this.consumoKwh == null ||
      this.usoHorarioPicoKwh == null ||
      this.tamanoHogar == null ||
      this.temperaturaPromedio == null ||
      this.cantidadRefrigeradores == null ||
      this.cantidadMicroondas == null ||
      this.cantidadLavadoras == null ||
      this.cantidadPantallas == null ||
      this.cantidadAireAcondicionado == null ||
      this.cantidadFocos == null
    ) {
      return;
    }

    this.errorSimulacion.set('');
    this.simulando.set(true);

    const ahorroKwhPico = this.usoHorarioPicoKwh * (this.porcentajeReduccion / 100);

    // Cuántos A/C se "apagarían" en el escenario simulado (nunca negativo,
    // por si el slider aún no se inicializó). NO se descuenta del pico,
    // se descuenta del consumo total directamente, y además se manda el
    // conteo reducido de A/C al backend para que el modelo lo use tal cual.
    const aireAcondicionadoSimulado = this.aireAcondicionadoSimulado ?? this.cantidadAireAcondicionado;
    const unidadesAireAcondicionadoReducidas = Math.max(
      0,
      this.cantidadAireAcondicionado - aireAcondicionadoSimulado,
    );
    const ahorroKwhAireAcondicionado =
      unidadesAireAcondicionadoReducidas * this.KWH_DIA_AIRE_ACONDICIONADO * this.DIAS_POR_MES;

    const ahorroKwhTotal = ahorroKwhPico + ahorroKwhAireAcondicionado;

    // Ahorro "paso a paso": primero lo que corresponde al pico, después
    // lo que corresponde adicionalmente al A/C, para mostrarlos en dos
    // líneas separadas en vez de un solo total mezclado.
    this.ahorroPicoMensual.set(Math.round(ahorroKwhPico * this.TARIFA_KWH * 100) / 100);
    this.ahorroAireAcondicionadoMensual.set(
      Math.round(ahorroKwhAireAcondicionado * this.TARIFA_KWH * 100) / 100,
    );

    const datos = {
      consumo_kwh: this.consumoKwh - ahorroKwhTotal,
      uso_horario_pico_kwh: this.usoHorarioPicoKwh - ahorroKwhPico,
      tamano_hogar: this.tamanoHogar,
      temperatura_promedio: this.temperaturaPromedio,
      refrigeradores: this.cantidadRefrigeradores,
      microondas: this.cantidadMicroondas,
      lavadoras: this.cantidadLavadoras,
      pantallas: this.cantidadPantallas,
      aire_acondicionado: aireAcondicionadoSimulado,
      focos: this.cantidadFocos,
      simulacion: true,
    };

    this.http
      .post<AnalisisResponse>(this.API_URL, datos, this.OPCIONES_HTTP)
      .pipe(timeout(10000))
      .subscribe({
        next: (respuesta) => {
          this.simulacion.set(respuesta);
          this.simulando.set(false);
        },
        error: (err) => {
          this.simulando.set(false);
          console.error(err);
          this.errorSimulacion.set('No se pudo calcular la simulación.');
        },
      });
  }

  /**
   * Cuánto se ahorraría al mes con el escenario simulado, comparado con
   * el análisis real actual.
   *
   * @returns el ahorro en la misma moneda que el costo (positivo = ahorro,
   *          negativo no debería ocurrir ya que la simulación siempre
   *          reduce consumo), o 0 si no hay resultado/simulación todavía
   */
  ahorroMensual(): number {
    const actual = this.resultado();
    const sim = this.simulacion();
    if (!actual || !sim) return 0;
    return Math.round((actual.costo_estimado_mensual - sim.costo_estimado_mensual) * 100) / 100;
  }

  // ---- Comparación entre períodos ----

  /**
   * Compara el análisis recién hecho con el que le sigue en el historial
   * (el anterior en el tiempo), para mostrar "mejoraste/empeoraste desde
   * la última vez" junto al resultado.
   *
   * El historial está ordenado más reciente primero, así que el análisis
   * actual es `historial()[0]` y el anterior es `historial()[1]`.
   *
   * @returns la comparación, o `null` si hay menos de 2 análisis en el
   *          historial de esta sesión (nada con qué comparar todavía)
   */
  comparacionConAnterior(): Comparacion | null {
    const items = this.historial();
    if (items.length < 2) return null;

    const actual = items[0];
    const anterior = items[1];

    const deltaCosto = actual.costo_estimado_mensual - anterior.costo_estimado_mensual;
    const deltaCostoPct =
      anterior.costo_estimado_mensual > 0 ? (deltaCosto / anterior.costo_estimado_mensual) * 100 : 0;
    const deltaConsumo = actual.consumo_kwh - anterior.consumo_kwh;

    // Eficiente > Moderado > Ineficiente, para saber si "subió" o "bajó"
    const rango = (categoria: string) =>
      categoria === 'Eficiente' ? 2 : categoria === 'Moderado' ? 1 : 0;
    const rangoActual = rango(actual.categoria);
    const rangoAnterior = rango(anterior.categoria);

    return {
      deltaCosto: Math.round(deltaCosto * 100) / 100,
      deltaCostoPct: Math.round(deltaCostoPct),
      deltaConsumo: Math.round(deltaConsumo),
      mejoro: rangoActual > rangoAnterior,
      empeoro: rangoActual < rangoAnterior,
    };
  }

  // ---- Historial / Dashboard ----

  /**
   * Handler del botón "Borrar historial": le pide al backend que borre
   * el historial de esta sesión (`DELETE /analisis-energetico`) y, si
   * tuvo éxito, vacía también el signal local para que el dashboard se
   * actualice al instante.
   */
  limpiarHistorial() {
    this.http.delete(this.API_URL, this.OPCIONES_HTTP).subscribe({
      next: () => this.historial.set([]),
      error: (err) => console.error('No se pudo borrar el historial', err),
    });
  }

  /** @returns cuántos análisis hay guardados en el historial de esta pestaña */
  totalAnalisis(): number {
    return this.historial().length;
  }

  /**
   * Descarga TODO el historial de análisis de esta sesión como un Excel
   * con diseño y DOS pestañas:
   * 1. "Datos de entrada": los mismos datos que se metieron en cada
   *    análisis (consumo, equipos, etc.), con las columnas exactas que
   *    espera el análisis por lotes — así este mismo archivo se puede
   *    volver a subir directo en "Análisis por lotes" sin tener que
   *    volver a teclear nada.
   * 2. "Análisis y resultado": la categoría, probabilidad, costo y
   *    recomendaciones que dio cada uno de esos análisis.
   */
  async descargarHistorialExcel() {
    const workbook = new ExcelJS.Workbook();

    const hojaEntrada = workbook.addWorksheet('Datos de entrada');
    hojaEntrada.columns = [
      { header: 'mes', key: 'mes', width: 14 },
      { header: 'anio', key: 'anio', width: 10 },
      { header: 'fecha', key: 'fecha', width: 20 },
      { header: 'consumo_kwh', key: 'consumo_kwh', width: 14 },
      { header: 'uso_horario_pico_kwh', key: 'uso_horario_pico_kwh', width: 20 },
      { header: 'tamano_hogar', key: 'tamano_hogar', width: 14 },
      { header: 'temperatura_promedio', key: 'temperatura_promedio', width: 20 },
      { header: 'refrigeradores', key: 'refrigeradores', width: 16 },
      { header: 'microondas', key: 'microondas', width: 14 },
      { header: 'lavadoras', key: 'lavadoras', width: 14 },
      { header: 'pantallas', key: 'pantallas', width: 14 },
      { header: 'aire_acondicionado', key: 'aire_acondicionado', width: 20 },
      { header: 'focos', key: 'focos', width: 10 },
    ];
    hojaEntrada.addRows(
      this.historial().map((item) => ({
        mes: item.mes ?? '',
        anio: item.anio ?? '',
        fecha: this.formatearFecha(item.fecha),
        consumo_kwh: item.consumo_kwh,
        uso_horario_pico_kwh: item.uso_horario_pico_kwh,
        tamano_hogar: item.tamano_hogar,
        temperatura_promedio: item.temperatura_promedio,
        refrigeradores: item.refrigeradores,
        microondas: item.microondas,
        lavadoras: item.lavadoras,
        pantallas: item.pantallas,
        aire_acondicionado: item.aire_acondicionado,
        focos: item.focos,
      })),
    );
    this.estilizarHojaHistorial(hojaEntrada);

    const hojaResultado = workbook.addWorksheet('Análisis y resultado');
    hojaResultado.columns = [
      { header: 'mes', key: 'mes', width: 14 },
      { header: 'anio', key: 'anio', width: 10 },
      { header: 'fecha', key: 'fecha', width: 20 },
      { header: 'consumo_kwh', key: 'consumo_kwh', width: 14 },
      { header: 'categoria', key: 'categoria', width: 14 },
      { header: 'probabilidad', key: 'probabilidad', width: 14 },
      { header: 'costo_estimado_mensual', key: 'costo_estimado_mensual', width: 22 },
      { header: 'recomendaciones', key: 'recomendaciones', width: 80 },
    ];
    hojaResultado.addRows(
      this.historial().map((item) => ({
        mes: item.mes ?? '',
        anio: item.anio ?? '',
        fecha: this.formatearFecha(item.fecha),
        consumo_kwh: item.consumo_kwh,
        categoria: item.categoria,
        probabilidad: Math.round((item.probabilidad ?? 0) * 100) / 100,
        costo_estimado_mensual: item.costo_estimado_mensual,
        recomendaciones: (item.recomendaciones ?? []).join('\n'),
      })),
    );
    this.estilizarHojaHistorial(hojaResultado);

    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'historial_energiai.xlsx';
    link.click();
    URL.revokeObjectURL(url);
  }

  /**
   * Aplica el mismo diseño (encabezado en negrita con color de marca,
   * bordes, filas alternadas, encabezado congelado) a una hoja del
   * Excel del historial — reutilizado por las dos pestañas para no
   * repetir el mismo bloque de estilos dos veces.
   */
  private estilizarHojaHistorial(hoja: ExcelJS.Worksheet) {
    const encabezado = hoja.getRow(1);
    encabezado.height = 22;
    encabezado.eachCell((celda) => {
      celda.font = { bold: true, color: { argb: 'FFFFFFFF' } };
      celda.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF0D9488' } };
      celda.alignment = { vertical: 'middle', horizontal: 'center' };
      celda.border = {
        top: { style: 'thin', color: { argb: 'FFE5E7EB' } },
        bottom: { style: 'thin', color: { argb: 'FFE5E7EB' } },
        left: { style: 'thin', color: { argb: 'FFE5E7EB' } },
        right: { style: 'thin', color: { argb: 'FFE5E7EB' } },
      };
    });
    hoja.views = [{ state: 'frozen', ySplit: 1 }];

    for (let numeroFila = 2; numeroFila <= hoja.rowCount; numeroFila++) {
      const fila = hoja.getRow(numeroFila);
      const relleno = numeroFila % 2 === 0 ? 'FFF0FDF4' : 'FFFFFFFF';
      fila.eachCell((celda) => {
        celda.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: relleno } };
        celda.alignment = { vertical: 'top', wrapText: true };
        celda.border = { bottom: { style: 'thin', color: { argb: 'FFE5E7EB' } } };
      });
    }
  }

  /**
   * Prepara los datos para el mini-gráfico de barras del historial: toma
   * los últimos 8 análisis, los pone en orden cronológico (más antiguo a
   * la izquierda, como se lee normalmente un gráfico de tiempo — el
   * historial en sí está guardado al revés, más reciente primero), y le
   * calcula a cada uno la altura de barra proporcional al más caro del
   * grupo mostrado.
   *
   * La altura mínima de 6% es para que hasta el análisis más barato del
   * grupo se vea como una barrita visible, no una línea invisible.
   *
   * @returns hasta 8 barras, cada una con su análisis original y su altura en %
   */
  barrasHistorial(): BarraHistorial[] {
    const items = [...this.historial()].reverse().slice(-8);
    const maxCosto = Math.max(...items.map((i) => i.costo_estimado_mensual), 1);

    return items.map((item) => ({
      item,
      alturaPct: Math.max(6, Math.round((item.costo_estimado_mensual / maxCosto) * 100)),
    }));
  }

  /**
   * Etiqueta corta del mes para el eje del mini-gráfico de barras (ej.
   * "Ago"): usa el mes que se indicó en el análisis si existe (los
   * primeros 3 caracteres), o si no, lo deriva de la fecha en que se
   * hizo el análisis — así el gráfico siempre muestra algo, aunque no
   * se haya elegido un mes explícito.
   *
   * @param item un ítem del historial
   * @returns el mes abreviado a 3 letras
   */
  mesCortoBarra(item: AnalisisResponse): string {
    if (item.mes) {
      return item.mes.slice(0, 3);
    }
    return new Date(item.fecha).toLocaleDateString('es', { month: 'short' }).replace('.', '');
  }

  /**
   * Etiqueta para cada ítem de la lista del historial: si se indicó mes
   * (y opcionalmente año) al analizar, muestra eso (ej. "Agosto 2026" o
   * solo "Agosto" si no se eligió año) — mucho más útil que la fecha
   * exacta cuando varios análisis se hicieron seguidos, como al probar
   * la app, y todos caen en el mismo minuto. Si no se indicó mes, cae de
   * vuelta a la fecha formateada de siempre.
   *
   * @param item un ítem del historial
   * @returns el mes/año, o la fecha formateada si no hay mes
   */
  etiquetaHistorial(item: AnalisisResponse): string {
    if (item.mes) {
      return item.anio ? `${item.mes} ${item.anio}` : item.mes;
    }
    return this.formatearFecha(item.fecha);
  }

  /**
   * Formatea una fecha ISO a un texto corto y legible en español para
   * mostrar en la lista del historial (ej. "04 ago, 21:04").
   *
   * @param iso fecha en formato ISO 8601, tal como la manda el backend
   * @returns la fecha formateada según las convenciones de `es` (español)
   */
  formatearFecha(iso: string): string {
    return new Date(iso).toLocaleDateString('es', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  // ---- Ranking (de tus propios análisis en esta sesión) ----
  // No hay ranking entre distintas personas: el historial es por sesión,
  // así que esto es "tu mejor mes" vs. "tu mes de mayor consumo".

  /**
   * @returns hasta 3 de tus análisis con el costo más bajo (los "mejores"),
   *          ordenados de menor a mayor costo
   */
  rankingMejores(): AnalisisResponse[] {
    return [...this.historial()]
      .sort((a, b) => a.costo_estimado_mensual - b.costo_estimado_mensual)
      .slice(0, 3);
  }

  /**
   * @returns hasta 3 de tus análisis con el costo más alto (los de mayor
   *          consumo), ordenados de mayor a menor costo
   */
  rankingPeores(): AnalisisResponse[] {
    return [...this.historial()]
      .sort((a, b) => b.costo_estimado_mensual - a.costo_estimado_mensual)
      .slice(0, 3);
  }

  /**
   * Medalla según la posición en el ranking (0 = primer lugar).
   * @param posicion índice dentro de la lista ordenada (0, 1, 2, ...)
   */
  medalla(posicion: number): string {
    return ['🥇', '🥈', '🥉'][posicion] ?? '';
  }

  // ---- Procesamiento por lotes (CSV o Excel) ----
  // Sube un archivo con varias filas (una vivienda/recibo por fila), las
  // manda todas juntas a POST /analisis-energetico/lote, y muestra los
  // resultados. Cada fila válida también queda guardada en el historial
  // de la sesión, igual que un análisis hecho a mano.

  /** Columnas numéricas exactas que debe tener el archivo (en cualquier orden). */
  private readonly COLUMNAS_CSV = [
    'consumo_kwh',
    'uso_horario_pico_kwh',
    'tamano_hogar',
    'temperatura_promedio',
    'refrigeradores',
    'microondas',
    'lavadoras',
    'pantallas',
    'aire_acondicionado',
    'focos',
  ];

  /**
   * Columnas opcionales con el mes/año al que corresponde el recibo (ej.
   * "Enero" / 2026). Se mandan al backend igual que las demás (quedan en
   * el historial de la sesión), y también se usan para etiquetar cada
   * fila en la tabla de resultados del lote.
   */
  private readonly COLUMNA_MES = 'mes';
  private readonly COLUMNA_ANIO = 'anio';

  /** Colores de marca reutilizados para el diseño de la plantilla Excel. */
  private readonly COLOR_ENCABEZADO = 'FF0D9488'; // --verde-azulado
  private readonly COLOR_FILA_PAR = 'FFF0FDF4';
  private readonly COLOR_BORDE = 'FFE5E7EB';

  /** Nombre del archivo elegido, solo para mostrarlo en la UI. */
  nombreArchivoCsv = signal('');
  /** Resultados del último lote procesado, cada uno con su mes. */
  lote = signal<FilaLote[]>([]);
  /** true mientras se procesa el lote. */
  loteCargando = signal(false);
  /** Error general del lote (ej. no se pudo leer el archivo). */
  loteError = signal('');
  /** Un mensaje por cada fila del archivo que no se pudo procesar. */
  loteErroresFilas = signal<string[]>([]);

  /**
   * Genera una plantilla Excel (.xlsx) con diseño (encabezado en negrita
   * con color de marca, bordes) y solo los encabezados, sin filas de
   * ejemplo, y la descarga directo desde el navegador con ExcelJS.
   */
  async descargarPlantillaExcel() {
    const workbook = new ExcelJS.Workbook();
    const hoja = workbook.addWorksheet('Plantilla');

    hoja.columns = [
      { header: 'mes', key: 'mes', width: 14 },
      { header: 'anio', key: 'anio', width: 10 },
      { header: 'consumo_kwh', key: 'consumo_kwh', width: 14 },
      { header: 'uso_horario_pico_kwh', key: 'uso_horario_pico_kwh', width: 20 },
      { header: 'tamano_hogar', key: 'tamano_hogar', width: 14 },
      { header: 'temperatura_promedio', key: 'temperatura_promedio', width: 20 },
      { header: 'refrigeradores', key: 'refrigeradores', width: 16 },
      { header: 'microondas', key: 'microondas', width: 14 },
      { header: 'lavadoras', key: 'lavadoras', width: 14 },
      { header: 'pantallas', key: 'pantallas', width: 14 },
      { header: 'aire_acondicionado', key: 'aire_acondicionado', width: 20 },
      { header: 'focos', key: 'focos', width: 10 },
    ];

    const encabezado = hoja.getRow(1);
    encabezado.height = 22;
    encabezado.eachCell((celda) => {
      celda.font = { bold: true, color: { argb: 'FFFFFFFF' } };
      celda.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: this.COLOR_ENCABEZADO } };
      celda.alignment = { vertical: 'middle', horizontal: 'center' };
      celda.border = {
        top: { style: 'thin', color: { argb: this.COLOR_BORDE } },
        bottom: { style: 'thin', color: { argb: this.COLOR_BORDE } },
        left: { style: 'thin', color: { argb: this.COLOR_BORDE } },
        right: { style: 'thin', color: { argb: this.COLOR_BORDE } },
      };
    });
    hoja.views = [{ state: 'frozen', ySplit: 1 }];

    for (let numeroFila = 2; numeroFila <= hoja.rowCount; numeroFila++) {
      const fila = hoja.getRow(numeroFila);
      const relleno = numeroFila % 2 === 0 ? this.COLOR_FILA_PAR : 'FFFFFFFF';
      fila.eachCell((celda) => {
        celda.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: relleno } };
        celda.alignment = { vertical: 'middle' };
        celda.border = { bottom: { style: 'thin', color: { argb: this.COLOR_BORDE } } };
      });
    }

    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'plantilla_energiai.xlsx';
    link.click();
    URL.revokeObjectURL(url);
  }

  /**
   * Handler del input de archivo: lee el CSV o Excel elegido, arma una
   * petición por cada fila válida, y las manda todas juntas al backend.
   *
   * @param event el evento `change` del `<input type="file">`
   */
  async onArchivoLoteSeleccionado(event: Event) {
    const input = event.target as HTMLInputElement;
    const archivo = input.files?.[0] ?? null;
    // Limpia el input para que, si el usuario vuelve a elegir el MISMO
    // archivo después, el evento "change" se dispare de nuevo.
    input.value = '';
    if (!archivo) return;

    this.nombreArchivoCsv.set(archivo.name);
    this.loteError.set('');
    this.loteErroresFilas.set([]);
    this.lote.set([]);
    this.loteCargando.set(true);

    try {
      const filas = archivo.name.toLowerCase().endsWith('.xlsx')
        ? await this.filasDesdeExcel(archivo)
        : this.filasDesdeTextoCsv(await archivo.text());
      this.procesarFilas(filas);
    } catch (err) {
      console.error(err);
      this.loteCargando.set(false);
      this.loteError.set('No se pudo leer el archivo.');
    }
  }

  /**
   * Parser simple de CSV (separa por comas, sin soportar comillas ni
   * comas dentro de un valor) — suficiente para un archivo generado a
   * propósito para esta app.
   *
   * @param texto el contenido completo del archivo CSV
   * @returns cada fila como un arreglo de celdas (la fila 0 es el encabezado)
   */
  private filasDesdeTextoCsv(texto: string): string[][] {
    return texto
      .trim()
      .split(/\r?\n/)
      .filter((l) => l.trim().length > 0)
      .map((linea) => linea.split(',').map((v) => v.trim()));
  }

  /**
   * Lee la primera hoja de un archivo .xlsx con ExcelJS y la convierte al
   * mismo formato de filas (arreglos de celdas) que produce el CSV, para
   * poder reutilizar la misma validación sin importar el formato subido.
   *
   * @param archivo el archivo .xlsx elegido
   * @returns cada fila como un arreglo de celdas (la fila 0 es el encabezado)
   */
  private async filasDesdeExcel(archivo: File): Promise<string[][]> {
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await archivo.arrayBuffer());
    const hoja = workbook.worksheets[0];

    const filas: string[][] = [];
    hoja.eachRow((fila) => {
      // fila.values es 1-based (el índice 0 no se usa), por eso el slice(1)
      const valores = (fila.values as unknown[]).slice(1);
      filas.push(valores.map((v) => (v == null ? '' : String(v)).trim()));
    });
    return filas;
  }

  /**
   * Valida y arma las peticiones a partir de las filas ya leídas (de CSV
   * o Excel, ver {@link construirSolicitudesDesdeFilas}), y si hay al
   * menos una válida, las manda todas juntas a `POST /analisis-energetico/lote`.
   *
   * @param filas cada fila como un arreglo de celdas; la fila 0 es el encabezado
   */
  private procesarFilas(filas: string[][]) {
    const { solicitudes, meses, errores } = this.construirSolicitudesDesdeFilas(filas);
    this.loteErroresFilas.set(errores);

    if (solicitudes.length === 0) {
      this.loteCargando.set(false);
      this.loteError.set('No se encontró ninguna fila válida en el archivo.');
      return;
    }

    this.http
      .post<AnalisisResponse[]>(`${this.API_URL}/lote`, { analisis: solicitudes }, this.OPCIONES_HTTP)
      .pipe(timeout(20000))
      .subscribe({
        next: (resultados) => {
          this.lote.set(resultados.map((resultado, i) => ({ mes: meses[i], resultado })));
          this.loteCargando.set(false);
          // Cada fila del lote también quedó guardada en el historial de
          // la sesión (igual que un análisis hecho a mano); se vuelve a
          // pedir para que el dashboard y el historial la reflejen.
          this.cargarHistorialDesdeBackend();
        },
        error: (err) => {
          this.loteCargando.set(false);
          console.error(err);
          this.loteError.set(
            err.error?.mensaje ?? 'No se pudo procesar el lote. Revisa el formato del archivo.',
          );
        },
      });
  }

  /**
   * Convierte filas ya separadas en celdas (venga de un CSV o de un
   * Excel) a una lista de peticiones listas para mandar al backend,
   * validando fila por fila.
   *
   * @param filas cada fila como un arreglo de celdas; la fila 0 es el encabezado
   * @returns las peticiones válidas, el mes de cada una (misma posición
   *          que su petición), y un mensaje de error por cada fila que no
   *          se pudo usar (columna faltante o no numérica)
   */
  private construirSolicitudesDesdeFilas(filas: string[][]): {
    solicitudes: Record<string, number | string>[];
    meses: string[];
    errores: string[];
  } {
    const errores: string[] = [];

    if (filas.length < 2) {
      return {
        solicitudes: [],
        meses: [],
        errores: ['El archivo no tiene filas de datos (solo encabezado, o está vacío).'],
      };
    }

    const encabezados = filas[0].map((h) => h.trim());
    const solicitudes: Record<string, number | string>[] = [];
    const meses: string[] = [];

    filas.slice(1).forEach((valores, indice) => {
      const numeroFila = indice + 2; // la fila 1 es el encabezado
      const fila: Record<string, string> = {};
      encabezados.forEach((encabezado, i) => (fila[encabezado] = (valores[i] ?? '').trim()));

      const columnaFaltante = this.COLUMNAS_CSV.find((columna) => !fila[columna]);
      if (columnaFaltante) {
        errores.push(`Fila ${numeroFila}: falta la columna "${columnaFaltante}".`);
        return;
      }

      const columnaInvalida = this.COLUMNAS_CSV.find((columna) => Number.isNaN(Number(fila[columna])));
      if (columnaInvalida) {
        errores.push(`Fila ${numeroFila}: "${fila[columnaInvalida]}" no es un número válido.`);
        return;
      }

      const solicitud: Record<string, number | string> = {};
      this.COLUMNAS_CSV.forEach((columna) => (solicitud[columna] = Number(fila[columna])));
      // Se mandan también al backend (además de guardar el mes aparte en
      // "meses" para la tabla inmediata) para que queden en el historial
      // de la sesión y sobrevivan a la descarga del historial en Excel.
      const mes = fila[this.COLUMNA_MES] || `Fila ${numeroFila}`;
      solicitud[this.COLUMNA_MES] = mes;
      const anioTexto = fila[this.COLUMNA_ANIO];
      if (anioTexto && !Number.isNaN(Number(anioTexto))) {
        solicitud[this.COLUMNA_ANIO] = Number(anioTexto);
      }
      solicitudes.push(solicitud);
      meses.push(mes);
    });

    return { solicitudes, meses, errores };
  }
}
