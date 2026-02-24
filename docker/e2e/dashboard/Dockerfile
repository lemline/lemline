FROM node:22-alpine AS build

WORKDIR /workspace/lemline-dashboard

COPY lemline-dashboard/package*.json ./
RUN npm ci

COPY lemline-dashboard ./

RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /workspace/lemline-dashboard/dist /usr/share/nginx/html
COPY docker/e2e/dashboard/config.js /usr/share/nginx/html/config.js

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
