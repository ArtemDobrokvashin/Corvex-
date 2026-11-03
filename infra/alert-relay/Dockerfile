FROM python:3.13-alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN addgroup -S corvex && adduser -S -G corvex -u 10001 corvex
WORKDIR /opt/alert-relay
COPY app.py ./app.py
RUN chown -R corvex:corvex /opt/alert-relay && chmod 0555 /opt/alert-relay/app.py

USER corvex
EXPOSE 8092
CMD ["python", "/opt/alert-relay/app.py"]
