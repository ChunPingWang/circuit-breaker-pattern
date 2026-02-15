.PHONY: up-all up-python up-java up-dotnet down test test-python test-java-02 test-java-03 test-dotnet

# ============================================
#  Docker Compose
# ============================================

up-all:
	docker compose --profile all up --build

up-python:
	docker compose --profile python up --build

up-java:
	docker compose --profile java up --build

up-dotnet:
	docker compose --profile dotnet up --build

down:
	docker compose --profile all down

# ============================================
#  Tests
# ============================================

test: test-python test-java-02 test-java-03 test-dotnet

test-python:
	cd 01-python-manual && python -m pytest tests/ -v

test-java-02:
	cd 02-java-resilience4j && ./gradlew test

test-java-03:
	cd 03-spring-cloud-cb && ./gradlew test

test-dotnet:
	cd 04-dotnet-polly && dotnet test
