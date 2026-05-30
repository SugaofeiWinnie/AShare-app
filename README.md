# AShare App

Spring Boot backend for the A-share market dashboard.

## Requirements

- Java 17
- Maven 3.6+

## Run

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8080`.

## Endpoints

- `GET /api/market/overview`
- `GET /api/market/indices`
- `GET /api/market/boards?type=industry&order=top&limit=20`
- `GET /api/market/ladder`
