FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY src ./src

FROM node:22-alpine
RUN addgroup -S nodum && adduser -S -G nodum nodum
WORKDIR /app
COPY --from=build --chown=nodum:nodum /app/node_modules ./node_modules
COPY --from=build --chown=nodum:nodum /app/src ./src
COPY --chown=nodum:nodum package.json ./package.json
USER nodum
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8082/actuator/health || exit 1
CMD ["node", "src/server.js"]
