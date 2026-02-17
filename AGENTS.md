#  AI Agent Development Prompt
**Audience**: AI Agents / Development Automation
##  Your Mission
You are an expert Java 25 and Spring Boot 4.0.0-M3 development agent. Your mission is to systematically implement the **Keybridge API Architecture** through 12 carefully planned phases. Your work must be production-grade, well-tested, and aligned with the architectural vision.
### Core Principle (Remember This!)
> **The server owns ALL credential/session/protocol complexity; clients send only domain operations.**
##  Key Constraints & Standards
### Language & Runtime
- **Java Version**: 25 (GraalVM Community Edition)
- **Preview Features**: ENABLED (`--enable-preview` flag required)
- **Spring Boot**: 4.0.0-M3 (from Spring Milestones repository)
- **Build Tool**: Maven 3.9+
- **Compiler**: Maven Compiler Plugin 3.13.0
### Code Standards (STRICT - NO EXCEPTIONS)
1. **SOLID Principles**: All code must follow SOLID
2. **Records**: Use Java 25 records for DTOs (immutable, clean)
3. **Virtual Threads**: Use `Executors.newVirtualThreadPerTaskExecutor()` for async operations
4. **Constructor Injection**: ONLY dependency injection method (no field injection, no setters)
5. **No Lombok**: Do NOT use Lombok—rely on records, explicit getters, and constructors
6. **No null**: Use `Optional<T>` or throw exceptions
7. **Explicit Logging**: Use SLF4J with appropriate log levels
8. **Reactive**: Use Reactor's `Mono<T>` and `Flux<T>` where appropriate
9. **Idiomatic Java 25**: Text blocks, pattern matching, record deconstruction
### Testing Standards (MANDATORY)
- **Unit Tests**: JUnit 5 + AssertJ (>85% coverage)
- **Integration Tests**: WireMock for HTTP mocking + real scenarios
- **Test Naming**: Follow Behavior-Driven Development (BDD)
  - Format: `should_<action>_when_<condition>` or `given_<context>_when_<action>_then_<result>`
- **Example**: `should_throwSessionPoolException_when_poolExhaustedAfterTimeout`
### Dependency Management (NO SURPRISES)
All dependencies must be declared in the **parent pom.xml** version properties:
- Spring Boot: 4.0.0-M3 (from `spring-milestones` repository)
- Apache Commons Pool: 2.12.0
- Resilience4j: 2.2.0
- Micrometer: 1.14.2
- Jackson: (managed by Spring Boot BOM)
- SLF4J: (managed by Spring Boot BOM)
- JUnit Jupiter: 5.11.4
- Mockito: 5.14.2
- WireMock: 3.10.0
## ️ Project Structure
### Current Repository
```
/Volumes/Development/keybridge-spring-boot-starter/
├── pom.xml                                    (Parent POM - DO NOT MODIFY VERSIONS)
├── .zencoder/
│   ├── rules/repo.md                          (Repository info)
│   └── docs/AI_AGENT_DEVELOPMENT_PROMPT.md    (This file)
├── docs/
│   ├── ARCHITECTURE_QUICK_REFERENCE.md        (5-min overview)
│   ├── ARCHITECTURE_IMPLEMENTATION_PLAN.md    (Full 12-phase plan)
│   ├── PROJECT_STRUCTURE_TEMPLATE.md          (Exact file/package layout)
│   ├── IMPLEMENTATION_STATUS.md               (Progress tracking)
│   └── Keybridge.yaml                         (355+ operations spec)
├── keybridge-client/                          (OLD - Will deprecate)
├── keybridge-spring-boot-autoconfigure/       (OLD - Will deprecate)
├── keybridge-spring-boot-starter/             (OLD - Will deprecate)
│
├── keybridge-api-server-starter/              (NEW - Phase 1)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/revfcu/keybridge/server/
│       │   ├── controller/
│       │   │   └── KeybridgeGatewayController.java
│       │   ├── builder/
│       │   │   └── QueryBuilder.java
│       │   ├── operation/
│       │   │   └── OperationRegistry.java
│       │   ├── session/
│       │   │   ├── KeybridgeSessionPool.java
│       │   │   ├── KeybridgeSession.java
│       │   │   ├── KeybridgeSessionFactory.java
│       │   │   └── SessionPoolProperties.java
│       │   ├── connector/
│       │   │   └── KeybridgeConnector.java
│       │   ├── exception/
│       │   │   ├── KeybridgeOperationException.java
│       │   │   └── SessionPoolException.java
│       │   ├── config/
│       │   │   ├── KeybridgeServerAutoConfiguration.java
│       │   │   └── KeybridgeServerProperties.java
│       │   └── dto/
│       │       ├── OperationRequest.java
│       │       ├── OperationResponse.java
│       │       └── ErrorResponse.java
│       └── test/java/... (mirror structure)
│
└── keybridge-api-client-starter/              (NEW - Phase 8)
    ├── pom.xml
    └── src/
        ├── main/java/com/revfcu/keybridge/client/
        │   ├── KeybridgeClient.java
        │   ├── config/
        │   │   └── KeybridgeClientAutoConfiguration.java
        │   ├── dto/
        │   │   └── OperationRequest.java
        │   └── exception/
        │       └── KeybridgeClientException.java
        └── test/java/... (mirror structure)
```