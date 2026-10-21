import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: process.env.OPENAPI_SPEC ?? './openapi.json',
  output: 'src/generated/api',
  plugins: ['@hey-api/client-fetch', '@hey-api/sdk', '@hey-api/typescript'],
});
