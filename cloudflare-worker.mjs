import { Container, getContainer } from '@cloudflare/containers';

const required = ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'APP_BASE_URL'];
const optional = [
  'INGESTION_TOKEN', 'ADMIN_BOOTSTRAP_USERNAME', 'ADMIN_BOOTSTRAP_PASSWORD',
  'YOUTUBE_CHANNEL_URL', 'FEED_ALLOWED_HOSTS', 'IMAGE_LICENSED_HOSTS',
  'AI_ENABLED', 'AI_API_KEY', 'AI_MODEL',
];

export class DevFlashContainer extends Container {
  defaultPort = 8080;
  sleepAfter = '10m';

  constructor(ctx, env) {
    super(ctx, env);
    this.envVars = Object.fromEntries(
      [...required, ...optional].filter((name) => typeof env[name] === 'string')
        .map((name) => [name, env[name]]),
    );
  }
}

export default {
  fetch(request, env) {
    if (required.some((name) => !env[name])) {
      return new Response('DevFlash runtime configuration is missing.', { status: 503 });
    }
    return getContainer(env.DEVFLASH, 'production').fetch(request);
  },
};
