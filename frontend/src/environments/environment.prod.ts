/**
 * Configuración de entorno para producción (`ng build --configuration production`).
 * Reemplaza a `environment.ts` en el build final (ver `fileReplacements`
 * en `angular.json`).
 */
export const environment = {
  production: true,
  /** URL base del backend en producción (desplegado en Railway). */
  apiUrl: 'https://g9-latam-team-75-production.up.railway.app',
};
