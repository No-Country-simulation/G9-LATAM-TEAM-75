import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs';
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
  /** Si la vivienda tiene aire acondicionado (chip Sí/No en el formulario). */
  tieneAireAcondicionado: boolean | null = null;

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
      tiene_aire_acondicionado: this.tieneAireAcondicionado,
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
      this.tieneAireAcondicionado == null
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

  /**
   * Convierte la probabilidad (0-1) del resultado actual a un porcentaje
   * entero (0-100), para dibujar el anillo de confianza en la tarjeta de
   * resultado (`[style.--pct.%]` en app.html).
   *
   * @returns el porcentaje redondeado, o 0 si todavía no hay resultado
   */
  porcentajeProbabilidad(): number {
    return Math.round((this.resultado()?.probabilidad ?? 0) * 100);
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
   * KPI del dashboard: costo mensual promedio de todos los análisis del
   * historial.
   *
   * @returns el promedio redondeado a 2 decimales, o 0 si el historial
   *          está vacío (evita dividir entre cero)
   */
  costoPromedio(): number {
    const items = this.historial();
    if (items.length === 0) return 0;
    const suma = items.reduce((acc, i) => acc + i.costo_estimado_mensual, 0);
    return Math.round((suma / items.length) * 100) / 100;
  }

  /**
   * KPI del dashboard: consumo mensual promedio de todos los análisis del
   * historial.
   *
   * @returns el promedio redondeado a un entero, o 0 si el historial
   *          está vacío
   */
  consumoPromedio(): number {
    const items = this.historial();
    if (items.length === 0) return 0;
    const suma = items.reduce((acc, i) => acc + i.consumo_kwh, 0);
    return Math.round(suma / items.length);
  }

  /**
   * KPI del dashboard: la categoría que más veces se repite en el
   * historial (moda estadística). En caso de empate, gana la primera
   * categoría encontrada al recorrer el historial en orden.
   *
   * @returns la categoría más frecuente, o `'—'` si el historial está vacío
   */
  categoriaMasFrecuente(): string {
    const items = this.historial();
    if (items.length === 0) return '—';

    // Cuenta cuántas veces aparece cada categoría en un Map (categoría -> conteo)
    const conteo = new Map<string, number>();
    for (const i of items) {
      conteo.set(i.categoria, (conteo.get(i.categoria) ?? 0) + 1);
    }

    // Recorre el Map buscando la categoría con mayor conteo
    let mejor = items[0].categoria;
    let mejorConteo = 0;
    for (const [categoria, cantidad] of conteo) {
      if (cantidad > mejorConteo) {
        mejor = categoria;
        mejorConteo = cantidad;
      }
    }
    return mejor;
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
   * Formatea una fecha ISO a un texto corto y legible en español para
   * mostrar en la lista del historial (ej. "04 ago, 21:04").
   *
   * @param iso fecha en formato ISO 8601 (como la guarda {@link agregarAlHistorial})
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
}
