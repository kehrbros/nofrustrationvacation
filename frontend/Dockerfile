FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Vite inlines these at build time, see src/vite-env.d.ts
ARG VITE_OIDC_AUTHORITY=http://localhost:8080
ARG VITE_OIDC_CLIENT_ID=vacantagent-frontend
ARG VITE_API_BASE_URL=
RUN npm run generate:api && npm run build

FROM nginx:1.27-alpine
# The nginx image renders templates with envsubst on startup
ENV BACKEND_UPSTREAM=http://backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
