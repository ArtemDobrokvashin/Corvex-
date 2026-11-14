FROM node:22-alpine AS checked
WORKDIR /app
COPY gateway.cjs gateway.test.cjs ./
RUN node --test gateway.test.cjs

FROM node:22-alpine
WORKDIR /app
COPY --from=checked /app/gateway.cjs ./gateway.cjs
EXPOSE 8443
CMD ["node", "gateway.cjs"]
