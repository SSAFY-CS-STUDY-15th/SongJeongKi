# Spring Boot JVM vs AOT/Native Image 실험

## 1. 실험 제목

Spring Boot 애플리케이션은 반드시 JVM이 필요할까? - JVM 실행과 AOT/Native Image 실행 비교 실험

## 2. 실험 목적

이 실험은 같은 Spring Boot REST API 애플리케이션을 세 가지 방식으로 빌드하고 실행해 비교한다.

- 일반 JVM 실행: `java -jar`로 실행하고, Spring이 런타임에 컨테이너를 초기화하는 비용을 확인한다.
- AOT JVM 실행: Spring AOT가 생성한 BeanDefinition 등록 코드와 Runtime Hints를 확인하고, AOT 코드로 JVM에서 실행한다.
- Native Image 실행: GraalVM Native Image로 OS 실행 파일을 만들고, `java` 명령 없이 실행 가능한지 확인한다.

핵심 관찰 대상은 빌드 시간, 실행 파일 크기, 시작 시간, RSS 메모리, BeanDefinition 수, Java/JVM 필요 여부다.

## 3. 실험 환경

| 항목 | 값 |
|---|---|
| OS | macOS arm64 |
| Java | Oracle GraalVM 21.0.11 |
| Spring Boot | 3.5.14 |
| Gradle | Wrapper 사용 |
| Native Build | GraalVM Native Build Tools |
| 애플리케이션 | 상품 카탈로그 REST API |
| 측정일 | 2026-05-18 |

이번 환경에서는 `native-image` 명령이 PATH에는 없었지만, Gradle이 다음 실행 파일을 toolchain으로 찾아 Native Image 빌드에 사용했다.

```text
/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home/lib/svm/bin/native-image
```

## 4. 예제 애플리케이션 구성

단순 Hello World가 아니라 Spring 컨테이너 초기화 비용이 어느 정도 보이도록 다음 요소를 포함했다.

| 요소 | 파일 |
|---|---|
| Main Application | `AotLabApplication` |
| Controller | `ProductController`, `RuntimeController` |
| Service | `ProductService` |
| Repository | `ProductRepository` |
| Configuration | `AppConfig` |
| `@Bean` 등록 | `CacheManager`, `Caffeine`, `PriceLabelFormatter` |
| Properties Binding | `CatalogProperties` |
| 외부 라이브러리 | Caffeine Cache, Apache Commons Text |
| 프록시 유발 요소 | `@Cacheable`, `@CacheEvict` |
| 시작 완료 로깅 | `StartupLogger` |
| 측정 endpoint | `/api/runtime` |

주요 API:

```bash
curl -sS http://localhost:8080/api/products?limit=3
curl -sS http://localhost:8080/api/products/P-0007
curl -sS http://localhost:8080/api/runtime
curl -sS http://localhost:8080/actuator/health
```

`/api/runtime`은 PID, VM 이름, BeanDefinition 수, 상품 수, heap 사용량을 반환한다.

## 5. Gradle 실행 모드

비교가 섞이지 않도록 Gradle property로 실행 모드를 분리했다.

| 모드 | 명령 | 의미 |
|---|---|---|
| 일반 JVM | `./gradlew clean bootJar` | AOT 없이 일반 JAR 빌드 |
| AOT JVM | `./gradlew -Paot clean bootJar` | AOT 산출물을 포함한 JAR 빌드 |
| Native Image | `./gradlew -Pnative clean nativeCompile` | AOT 처리 후 네이티브 실행 파일 빌드 |

## 6. 실험 방법

### 6.1 일반 JVM 실행

빌드 시간을 측정한다.

```bash
/usr/bin/time -p ./gradlew --console=plain clean bootJar
```

JAR 크기를 확인한다.

```bash
ls -lh build/libs/test-for-aot-0.0.1-SNAPSHOT.jar
stat -f %z build/libs/test-for-aot-0.0.1-SNAPSHOT.jar
```

JVM 방식으로 실행한다.

```bash
java -jar build/libs/test-for-aot-0.0.1-SNAPSHOT.jar --server.port=18080 --debug=false
```

측정한다.

```bash
curl -sS http://127.0.0.1:18080/actuator/health
curl -sS http://127.0.0.1:18080/api/runtime
ps -o pid,etime,rss,vsz,command -p <PID>
```

확인 포인트:

- `java -jar`가 필요하다.
- VM 이름은 HotSpot VM이다.
- Spring Boot 시작 로그와 `StartupLogger` 로그에서 시작 시간을 확인한다.

### 6.2 Spring AOT 산출물 생성

AOT 처리만 실행한다.

```bash
./gradlew -Paot clean processAot
./gradlew printExperimentPaths
```

생성 파일을 확인한다.

```bash
find build/generated/aotSources -type f | sort | head -50
find build/generated/aotResources -type f | sort
find build/generated/aotClasses -type f | sort
```

확인 포인트:

- `*__BeanDefinitions.java`: BeanDefinition 등록 코드
- `*__ApplicationContextInitializer.java`: AOT ApplicationContext 초기화 코드
- `reflect-config.json`: Native Image 리플렉션 힌트
- `resource-config.json`: Native Image 리소스 힌트
- `*SpringCGLIB*.class`: 빌드 시점에 생성된 프록시 클래스

### 6.3 AOT JVM 실행

AOT 산출물을 포함한 JAR을 빌드한다.

```bash
/usr/bin/time -p ./gradlew --console=plain -Paot clean bootJar
```

AOT 모드를 켜서 JVM 위에서 실행한다.

```bash
java -Dspring.aot.enabled=true \
  -jar build/libs/test-for-aot-0.0.1-SNAPSHOT.jar \
  --server.port=18080 --debug=false
```

측정 명령은 일반 JVM과 동일하다.

```bash
curl -sS http://127.0.0.1:18080/api/runtime
ps -o pid,etime,rss,vsz,command -p <PID>
```

확인 포인트:

- 여전히 JVM이 필요하다.
- 시작 로그에 `Starting AOT-processed AotLabApplication`이 표시된다.
- BeanDefinition 수와 시작 시간이 일반 JVM 실행과 달라지는지 확인한다.

### 6.4 Native Image 빌드

Native Image 빌드 시간을 측정한다.

```bash
/usr/bin/time -p ./gradlew --console=plain -Pnative clean nativeCompile
```

실행 파일을 확인한다.

```bash
ls -lh build/native/nativeCompile/aot-lab
stat -f %z build/native/nativeCompile/aot-lab
file build/native/nativeCompile/aot-lab
```

이번 환경에서 생성된 파일은 macOS arm64용 실행 파일이다.

```text
build/native/nativeCompile/aot-lab: Mach-O 64-bit executable arm64
```

확인 포인트:

- Native Image 빌드 중 Spring AOT가 먼저 실행된다.
- GraalVM은 reachable type/method, reflection metadata, resource metadata를 빌드 시점에 분석한다.
- macOS에서 만든 바이너리는 Linux 컨테이너에서 그대로 실행할 수 없다. Linux 컨테이너에서 비교하려면 Linux 환경에서 다시 빌드해야 한다.

### 6.5 Native Image 실행

`java` 명령 없이 실행한다.

```bash
./build/native/nativeCompile/aot-lab --server.port=18080 --debug=false
```

측정한다.

```bash
curl -sS http://127.0.0.1:18080/actuator/health
curl -sS http://127.0.0.1:18080/api/runtime
ps -o pid,etime,rss,vsz,command -p <PID>
```

확인 포인트:

- 실행 명령에 `java`가 없다.
- `/api/runtime`의 VM 이름은 `Substrate VM`이다.
- 시작 시간과 RSS가 JVM 실행보다 줄어드는지 확인한다.

## 7. 측정 결과

Gradle dependency cache와 daemon이 준비된 상태에서 측정했다. 값은 머신 상태, 실행 시점, OS, GraalVM 버전에 따라 달라질 수 있다.

| 측정 항목 | 일반 JVM 실행 | AOT JVM 실행 | Native Image 실행 |
|---|---:|---:|---:|
| 빌드 명령 | `./gradlew clean bootJar` | `./gradlew -Paot clean bootJar` | `./gradlew -Pnative clean nativeCompile` |
| 빌드 시간 | 1.39s | 2.54s | 102.04s |
| 실행 산출물 | Boot JAR | AOT 포함 Boot JAR | OS 네이티브 실행 파일 |
| 실행 파일 크기 | 29,655,358 bytes | 29,955,196 bytes | 105,251,288 bytes |
| 표시 크기 | 28M | 29M | 100M |
| 실행 명령 | `java -jar ...` | `java -Dspring.aot.enabled=true -jar ...` | `./build/native/nativeCompile/aot-lab` |
| Java/JVM 필요 여부 | 필요 | 필요 | 불필요 |
| 시작 시간 로그 | 0.981s | 0.715s | 0.065s |
| process running | 1.212s | 0.895s | 0.087s |
| `ApplicationReadyEvent` startupMs | 1008ms | 740ms | 66ms |
| BeanDefinition 수 | 310 | 307 | 307 |
| `/api/runtime` heapUsedMb | 43MB | 17MB | 46MB |
| `/api/runtime` heapCommittedMb | 80MB | 96MB | 46MB |
| RSS | 233,616KB | 234,816KB | 98,608KB |
| VM 이름 | Java HotSpot VM | Java HotSpot VM | Substrate VM |
| 런타임 리플렉션 의존도 | JVM에서 동적 리플렉션 가능 | AOT 힌트 일부 사용 | 빌드 시점 힌트 필요 |
| 배포 난이도 | 낮음 | 중간 | 중간~높음 |
| 라이브러리 호환성 | 가장 높음 | 높음 | 검증 필요 |
| 디버깅 편의성 | 가장 좋음 | 좋음 | 상대적으로 어려움 |
| 장기 실행 서버 적합성 | 높음 | 높음 | 상황 의존 |
| 짧은 배치/서버리스 적합성 | 보통 | 보통 | 높음 |

## 8. AOT 산출물 확인 결과

| 산출물 | 측정값 | 의미 |
|---|---:|---|
| 사용자 패키지 `*__BeanDefinitions.java` | 11개 | 런타임 BeanDefinition 생성 작업 일부를 빌드 시점 코드로 대체 |
| native-image hint 파일 | 3개 | 리플렉션, 리소스, native-image 설정 힌트 |
| AOT 프록시 클래스 | 2개 | CGLIB 프록시를 빌드 시점에 생성 |

대표 생성 파일:

```text
build/generated/aotSources/com/example/aotlab/AotLabApplication__ApplicationContextInitializer.java
build/generated/aotSources/com/example/aotlab/product/ProductController__BeanDefinitions.java
build/generated/aotSources/com/example/aotlab/product/ProductService__BeanDefinitions.java
build/generated/aotResources/META-INF/native-image/com.example/test-for-aot/reflect-config.json
build/generated/aotResources/META-INF/native-image/com.example/test-for-aot/resource-config.json
build/generated/aotClasses/com/example/aotlab/product/ProductService$$SpringCGLIB$$0.class
```

해석:

- 일반 JVM 실행에서는 Spring이 런타임에 애너테이션과 클래스패스를 분석해 BeanDefinition을 만든다.
- AOT 실행에서는 일부 BeanDefinition 등록 코드가 미리 생성된다.
- Native Image에서는 런타임 리플렉션, 리소스 접근, 프록시 사용 대상을 빌드 시점에 알려야 한다.

## 9. Native Image 빌드 로그 요약

Native Image 빌드에서 확인된 주요 값이다.

| 항목 | 값 |
|---|---:|
| reachable types | 22,435 |
| reachable methods | 112,654 |
| reflection 등록 types | 7,168 |
| JNI 등록 types | 62 |
| image heap resources | 308 |
| Native Image 자체 생성 시간 | 1m 38s |
| 전체 Gradle 빌드 시간 | 102.04s |
| Native Image 빌드 Peak RSS | 6.21GB |
| 생성된 바이너리 크기 | 100M |

이 값은 GraalVM Native Image가 애플리케이션을 닫힌 세계 가정으로 분석하고, 실행에 필요한 타입, 메서드, 리플렉션 메타데이터, 리소스를 빌드 시점에 확정한다는 점을 보여준다.

## 10. 결과 해석

이번 실험에서는 Native Image의 시작 시간이 가장 짧았다.

| 비교 | 결과 |
|---|---:|
| AOT JVM 시작 시간 개선 | 일반 JVM 대비 약 27% 감소 |
| Native Image 시작 시간 개선 | 일반 JVM 대비 약 93% 감소 |
| Native Image RSS 개선 | 일반 JVM 대비 약 58% 감소 |
| AOT JVM 빌드 시간 증가 | 일반 JVM 대비 약 1.8배 |
| Native Image 빌드 시간 증가 | 일반 JVM 대비 약 73배 |
| Native Image 파일 크기 | 일반 JVM JAR 대비 약 3.5배 |

핵심은 “Native Image가 무조건 빠르다”가 아니다. Spring AOT가 런타임에 하던 컨테이너 분석 일부를 빌드 시점으로 옮기고, Native Image가 그 결과를 OS 실행 파일에 포함하기 때문에 시작 비용이 줄어든다.

반대로 빌드 비용은 크게 증가했다. Native Image 빌드는 102초가 걸렸고, 빌드 중 Peak RSS는 6.21GB였다. 따라서 개발 중 반복 빌드나 디버깅에는 일반 JVM 방식이 훨씬 편하다.

## 11. 운영 관점 비교

| 관점 | JVM 실행 | AOT JVM 실행 | Native Image 실행 |
|---|---|---|---|
| 장기 실행 웹 서버 | 기본 선택지로 적합 | 시작 시간 개선이 필요할 때 검토 | 메모리/시작 시간이 중요하면 검토 |
| 서버리스 | cold start가 부담될 수 있음 | 일부 개선 가능 | 가장 적합한 후보 |
| Kubernetes CronJob | JVM 부팅 비용이 상대적으로 큼 | 일부 개선 가능 | 짧고 자주 실행되면 유리 |
| 빠른 스케일아웃 | 인스턴스 준비 시간이 상대적으로 김 | 일부 개선 가능 | 빠른 준비 시간에 유리 |
| 라이브러리 호환성 | 가장 좋음 | 대체로 좋음 | 리플렉션/동적 로딩 검증 필요 |
| 디버깅/프로파일링 | 가장 편함 | JVM 도구 사용 가능 | JVM 대비 불편 |
| 빌드 파이프라인 | 단순 | 약간 증가 | 빌드 시간/메모리 비용 큼 |

## 12. Docker 비교 방법

JVM 이미지:

```bash
./gradlew clean bootJar
docker build -f Dockerfile.jvm -t test-for-aot:jvm .
docker run --rm -p 8080:8080 test-for-aot:jvm
```

Native 이미지:

```bash
./gradlew -Pnative clean nativeCompile
docker build -f Dockerfile.native -t test-for-aot:native .
docker run --rm -p 8081:8080 test-for-aot:native
```

주의: 위 Native Dockerfile은 현재 OS에서 만든 바이너리를 복사한다. macOS에서 만든 `Mach-O` 바이너리는 Linux 컨테이너에서 실행할 수 없다. Linux 컨테이너 기준으로 비교하려면 Linux 환경에서 `nativeCompile`을 실행하거나 Buildpacks를 사용한다.

```bash
./gradlew -Pnative bootBuildImage --imageName=test-for-aot:native
docker run --rm -p 8080:8080 test-for-aot:native
```

## 13. 결론

- 일반 Spring Boot JVM 실행은 런타임에 애너테이션 메타데이터, 클래스패스, 조건부 설정, BeanDefinition, 의존성 주입 정보를 분석한다.
- Spring AOT는 이 분석 일부를 빌드 시점으로 옮겨 BeanDefinition 등록 코드, Runtime Hints, 프록시 클래스, 리소스 메타데이터를 생성한다.
- Native Image는 JVM 없이 실행 가능한 OS 바이너리를 만들 수 있다.
- 이번 실험에서 Native Image는 시작 시간과 RSS가 크게 줄었다.
- 대신 빌드 시간이 크게 늘고, 실행 파일 크기가 커졌으며, 리플렉션 기반 라이브러리와 동적 로딩은 별도 검증이 필요하다.
- 항상 떠 있는 장기 실행 웹 서버에서는 JVM 실행 방식이 여전히 실용적인 기본 선택지다.
- 서버리스, 짧은 배치, Kubernetes CronJob, 빠른 스케일아웃 환경에서는 AOT/Native Image가 의미 있는 선택지가 될 수 있다.
- AOT/Native Image는 Spring을 쓰는 이유가 아니라, 이미 Spring을 쓰는 애플리케이션의 시작 비용과 메모리 비용을 줄이기 위한 최적화 수단이다.

## 14. 면접 포인트

- “Spring Boot의 시작 비용은 JVM 자체만의 문제가 아니라, 런타임에 BeanDefinition 생성, 조건 평가, 클래스패스 분석, 리플렉션 기반 의존성 분석이 수행되기 때문에 발생한다고 이해하고 실험으로 확인했습니다.”
- “Spring AOT는 런타임 컨테이너 초기화 과정 중 일부를 빌드 시점으로 이동시켜 BeanDefinition 등록 코드와 Runtime Hints를 생성합니다.”
- “Native Image는 JVM 없이 실행 가능한 바이너리를 만들 수 있지만, 리플렉션과 동적 클래스 로딩에 제약이 있어 모든 서비스에 무조건 적용할 기술은 아닙니다.”
- “장기 실행 웹 서버는 JVM의 JIT, 디버깅, 라이브러리 호환성이 강점이고, 서버리스나 짧게 실행되는 Batch/CronJob은 Native Image의 빠른 시작과 낮은 RSS가 장점입니다.”
- “AOT/Native Image는 Spring을 선택하는 이유가 아니라, 이미 Spring으로 작성된 애플리케이션의 시작 비용과 메모리 비용을 줄이기 위한 최적화 수단으로 보는 것이 적절합니다.”
