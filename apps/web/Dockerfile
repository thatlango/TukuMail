FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json ./
COPY apps/web/package.json apps/web/package.json
COPY apps/desktop/package.json apps/desktop/package.json
RUN npm install
COPY apps/web apps/web
RUN npm run web:build

FROM nginx:1.27-alpine
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
EXPOSE 80
