import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';
import mdx from '@astrojs/mdx';

export default defineConfig({
  site: 'https://aldefy.github.io',
  base: '/compose-rebound',
  integrations: [tailwind(), mdx()],
  output: 'static',
});
