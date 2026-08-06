import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

/**
 * Punto de entrada del frontend: arranca la aplicación Angular en el
 * navegador, montando el componente raíz {@link App} en el `<app-root>`
 * de `index.html`, con la configuración (`providers`) definida en
 * `app.config.ts`.
 *
 * `bootstrapApplication` es la forma moderna de arrancar una app Angular
 * standalone (sin `NgModule` raíz). Si algo falla durante el arranque
 * (por ejemplo un error de configuración), se captura y se imprime en la
 * consola del navegador en vez de dejar la página en blanco sin ninguna
 * pista de qué pasó.
 */
bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
