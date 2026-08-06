import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { routes } from './app.routes';

/**
 * Configuración de la aplicación: qué "providers" (servicios globales de
 * Angular) están disponibles para toda la app. `main.ts` la usa al
 * arrancar (`bootstrapApplication(App, appConfig)`).
 *
 * No hay `provideZoneChangeDetection()` ni se importa `zone.js` en
 * ningún lado del proyecto: esta app corre en modo *zoneless*. Eso
 * significa que Angular NO detecta automáticamente los cambios que
 * ocurren fuera de sus propios eventos (clicks, inputs). Por eso
 * `App` en `app.ts` usa `signal()` para el estado que cambia desde la
 * respuesta HTTP — son los signals los que le avisan a Angular que hay
 * que volver a pintar la pantalla, no un mecanismo automático de fondo.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    // Habilita el enrutador de Angular (aunque hoy `routes` está vacío,
    // sin rutas definidas — la app es de una sola pantalla).
    provideRouter(routes),
    // Habilita HttpClient (usado en app.ts para llamar al backend) en
    // toda la aplicación.
    provideHttpClient(), // habilita las llamadas HTTP al backend
  ],
};
