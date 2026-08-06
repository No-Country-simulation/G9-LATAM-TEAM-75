/**
 * Configuración de entorno para desarrollo local (`ng serve`).
 *
 * `angular.json` reemplaza este archivo por `environment.prod.ts` al
 * compilar con `ng build --configuration production` (ver
 * `fileReplacements` en la sección `production` del build), así que el
 * mismo código en `app.ts` (`environment.apiUrl`) apunta a un backend
 * distinto según el entorno sin tener que tocar el código fuente.
 */
export const environment = {
  production: false,
  /** URL base del backend en desarrollo: el que corre en tu máquina. */
  apiUrl: 'http://localhost:8080',
};
