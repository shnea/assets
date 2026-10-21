FROM node:24-alpine AS development
WORKDIR /app
COPY package.json package-lock.json ./
COPY vendor ./vendor
RUN npm ci
COPY index.html tsconfig.json ./
COPY src ./src
COPY public ./public
CMD ["npm", "run", "dev"]
FROM development AS build
RUN npm run build
FROM nginx:1.30.5-alpine
COPY --from=build /app/dist /usr/share/nginx/html
