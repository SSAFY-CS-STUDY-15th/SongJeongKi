# Spring Boot는 어떤 ApplicationContext를 사용할까?

## DefaultApplicationContextFactory에서 시작된 AOT와 Native Image 탐색기

Spring Boot 내부 구조를 보다가 문득 궁금해졌다.

Spring에서 가장 중요한 객체 중 하나는 `ApplicationContext`다.  
`ApplicationContext`는 스프링 컨테이너 역할을 하며, BeanDefinition을 관리하고, Bean을 생성하고, 의존관계를 주입하고, 애플리케이션 실행에 필요한 여러 부가 기능을 제공한다.

그렇다면 Spring Boot는 실제로 어떤 `ApplicationContext` 구현체를 사용할까?

처음에는 단순히 이 질문에서 출발했다.

웹 애플리케이션이라면 당연히 `WebApplicationContext` 계열을 사용하겠지, 정도로 생각했다. 그런데 Spring Boot 내부 코드를 따라가다 보니 `DefaultApplicationContextFactory`에서 아래 코드를 발견했다.

```java
private ConfigurableApplicationContext createDefaultApplicationContext() {
    if (!AotDetector.useGeneratedArtifacts()) {
        return new AnnotationConfigApplicationContext();
    }
    return new GenericApplicationContext();
}
```

처음에는 조금 이상하게 느껴졌다.

“Spring Boot는 애너테이션 기반 설정을 많이 쓰니까 `AnnotationConfigApplicationContext`를 쓰는 건 이해되는데, 왜 어떤 경우에는 `GenericApplicationContext`를 리턴하지?”

특히 `GenericApplicationContext`는 이름 그대로 범용 컨텍스트라는 느낌이 강했다.  
그렇다면 Spring Boot가 특정 상황에서는 애너테이션 기반 컨텍스트를 포기하고 더 일반적인 컨텍스트를 사용하는 이유가 있을 것이다.

그 지점에서 `AotDetector.useGeneratedArtifacts()`가 눈에 들어왔다.

---

## 1. 문제의 시작: `AotDetector.useGeneratedArtifacts()`

해당 메서드는 대략 이런 형태다.

```java
public static boolean useGeneratedArtifacts() {
    return (inNativeImage || SpringProperties.getFlag(AOT_ENABLED));
}
```

주석을 보면 이 메서드는 런타임에 AOT 최적화를 고려해야 하는지 판단한다고 되어 있다.

여기서 AOT(Ahead Of Time)는 실행 시점이 아니라, 빌드 시점에 미리 애플리케이션 구조를 분석하고 필요한 코드를 생성해두는 방식을 의미한다.

즉, 위 코드는 다음 의미에 가깝다.

```text
현재 애플리케이션이 Native Image로 실행 중이거나,
JVM 실행 중이더라도 AOT 활성화 속성이 켜져 있으면 true를 반환한다.
```

반대로 일반적인 방식으로 Spring Boot 애플리케이션을 실행하면 보통 `false`가 된다.

```bash
java -jar app.jar
```

이 경우에는 일반 JVM(Java Virtual Machine) 실행이기 때문에 `inNativeImage`도 `false`이고, 별도로 `spring.aot.enabled` 같은 설정을 켜지 않았다면 `AOT_ENABLED`도 `false`다.

그러면 `createDefaultApplicationContext()`는 다음 객체를 생성한다.

```java
return new AnnotationConfigApplicationContext();
```

하지만 AOT 결과물을 사용해야 하는 상황이면 다음 객체를 생성한다.

```java
return new GenericApplicationContext();
```

이게 핵심적인 의문이었다.

“왜 AOT를 사용하면 `AnnotationConfigApplicationContext`가 아니라 `GenericApplicationContext`를 사용할까?”

---

## 2. 일반 Spring Boot 실행에서는 런타임에 많은 일이 일어난다

일반적인 Spring Boot 애플리케이션은 실행될 때 많은 작업을 런타임에 수행한다.

예를 들어 다음과 같은 작업들이다.

```text
1. 클래스패스에서 클래스 탐색
2. @Component, @Service, @Repository, @Controller 탐색
3. @Configuration 클래스 분석
4. @Bean 메서드 분석
5. @Autowired 대상 탐색
6. @Conditional 조건 평가
7. BeanDefinition 생성
8. BeanFactory에 BeanDefinition 등록
9. Bean 객체 생성
10. 의존관계 주입
```

이 과정에서 스프링은 클래스의 애너테이션 메타데이터를 읽고, 생성자와 메서드 정보를 확인하고, 어떤 객체를 Bean으로 등록할지 결정한다.

이때 리플렉션(Reflection)도 많이 사용된다.

**Reflection**은 런타임에 클래스, 메서드, 필드, 생성자 같은 메타데이터를 조회하고 조작할 수 있는 기능이다.

예를 들어 스프링은 런타임에 이런 질문을 계속 던진다.

```text
이 클래스에 @Component가 붙어 있는가?
이 클래스는 @Configuration인가?
이 메서드는 @Bean 메서드인가?
이 생성자는 어떤 파라미터를 받는가?
이 필드에는 @Autowired가 붙어 있는가?
이 조건부 설정은 현재 환경에서 활성화되어야 하는가?
```

일반 JVM 실행에서는 이런 방식이 자연스럽다.  
JVM은 런타임 동적 기능을 잘 지원하고, 리플렉션도 비교적 자유롭게 사용할 수 있다.

그래서 일반 실행에서는 `AnnotationConfigApplicationContext`가 잘 맞는다.

`AnnotationConfigApplicationContext`는 이름 그대로 애너테이션 기반 설정을 읽고 처리하는 데 특화된 `ApplicationContext` 구현체이기 때문이다.

---

## 3. AOT는 런타임 분석 작업을 빌드 시점으로 옮긴다

그런데 AOT 방식은 관점이 다르다.

AOT는 런타임에 스프링이 하던 일부 분석 작업을 빌드 시점으로 옮긴다.

즉, 일반 실행에서는 애플리케이션이 시작될 때 하던 작업을:

```text
런타임:
@Component 탐색
@Configuration 분석
@Bean 메서드 분석
@Autowired 의존성 분석
BeanDefinition 생성
```

AOT에서는 빌드 시점에 미리 처리한다.

```text
빌드 시점:
@Component 탐색 결과 계산
@Configuration 분석
@Bean 메서드 분석
@Autowired 의존성 분석
BeanDefinition 등록 코드 생성
Runtime Hints 생성
프록시 정보 생성
리소스 접근 정보 생성
```

그리고 런타임에는 미리 생성된 결과물을 사용한다.

```text
런타임:
GenericApplicationContext 생성
AOT가 생성한 초기화 코드 실행
BeanDefinition 등록
Bean 생성
애플리케이션 시작
```

이렇게 되면 런타임에 애너테이션을 다시 많이 분석할 필요가 줄어든다.

개념적으로는 이런 차이다.

일반 실행에서는 런타임에 다음과 같은 판단을 한다.

```text
"이 클래스는 @Service인가?"
"이 생성자에는 어떤 의존성이 필요한가?"
"이 @Bean 메서드는 어떤 BeanDefinition으로 등록해야 하는가?"
```

AOT에서는 빌드 시점에 이미 분석해두고, 런타임에는 생성된 등록 코드를 실행한다.

예를 들어 아래와 같은 서비스가 있다고 해보자.

```java
@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }
}
```

일반 실행에서는 스프링이 런타임에 `@Service`를 찾고, 생성자를 분석하고, `MemberRepository` 타입의 Bean을 찾아 주입한다.

AOT에서는 이 정보를 미리 분석해서, 개념적으로는 다음과 비슷한 Bean 등록 코드를 만들어둘 수 있다.

```java
context.registerBean(MemberService.class, () ->
    new MemberService(context.getBean(MemberRepository.class))
);
```

실제 생성 코드는 더 복잡하지만, 핵심은 같다.

```text
런타임에 애너테이션을 해석해서 BeanDefinition을 만드는 대신,
빌드 시점에 BeanDefinition 등록 방식을 미리 만들어둔다.
```

그래서 AOT 실행에서는 `AnnotationConfigApplicationContext`처럼 애너테이션 설정을 런타임에 해석하는 기능이 강한 컨텍스트가 꼭 필요하지 않다.

이미 만들어진 등록 코드를 실행하고 BeanDefinition을 담을 수 있는 범용 컨테이너가 필요하다.

이 역할에 `GenericApplicationContext`가 잘 맞는다.

---

## 4. 그래서 AOT 모드에서는 왜 GenericApplicationContext인가?

처음 보았던 코드를 다시 보자.

```java
private ConfigurableApplicationContext createDefaultApplicationContext() {
    if (!AotDetector.useGeneratedArtifacts()) {
        return new AnnotationConfigApplicationContext();
    }
    return new GenericApplicationContext();
}
```

이 코드는 다음 흐름으로 해석할 수 있다.

```text
AOT 결과물을 사용하지 않는 일반 실행
→ AnnotationConfigApplicationContext 사용
→ 런타임에 애너테이션 기반 설정 분석

AOT 결과물을 사용하는 실행
→ GenericApplicationContext 사용
→ 미리 생성된 초기화 코드와 BeanDefinition 등록 코드 사용
```

즉, `GenericApplicationContext`를 사용하는 이유는 기능이 부족해서가 아니라, AOT 실행 방식에서는 오히려 그 정도의 범용 컨테이너가 더 적합하기 때문이다.

`AnnotationConfigApplicationContext`는 런타임에 애너테이션 설정을 해석하는 쪽에 특화되어 있다.

반면 AOT 실행에서는 이미 빌드 시점에 분석이 끝난 상태이기 때문에, 런타임에는 미리 생성된 코드를 실행해서 BeanDefinition을 등록하면 된다.

정리하면 다음과 같다.

```text
AnnotationConfigApplicationContext
= 런타임에 @Configuration, @Component, @Bean 등을 분석하는 데 적합

GenericApplicationContext
= 이미 만들어진 BeanDefinition 등록 코드를 받아 실행하는 데 적합
```

이제 처음의 의문이 조금 풀렸다.

Spring Boot가 AOT 환경에서 `GenericApplicationContext`를 사용하는 것은 애너테이션 기반 설정을 포기하는 것이 아니라, 애너테이션 분석 결과를 이미 빌드 시점에 처리해두었기 때문에 런타임에서는 더 단순한 컨텍스트로 충분해지는 것이다.

---

## 5. Native Image까지 가면 JVM 없이 실행할 수도 있다

AOT를 조사하다 보니 자연스럽게 Native Image까지 이어졌다.

일반적인 Java 애플리케이션은 JVM 위에서 실행된다.

```text
Java source
→ javac 컴파일
→ .class 바이트코드
→ JAR(Java Archive)
→ JVM에서 실행
```

실행 명령은 보통 다음과 같다.

```bash
java -jar app.jar
```

이 경우 실행 서버에는 Java 런타임이 필요하다.  
즉, JRE(Java Runtime Environment) 또는 JDK(Java Development Kit)가 설치되어 있어야 한다.

반면 GraalVM Native Image를 사용하면 Java 애플리케이션을 운영체제에서 직접 실행 가능한 네이티브 바이너리로 만들 수 있다.

```text
Java source
→ Spring AOT 처리
→ Native Image 빌드
→ OS에서 직접 실행 가능한 바이너리 생성
```

실행은 다음처럼 할 수 있다.

```bash
./my-app
```

이 경우 실행 환경에 `java` 명령이 없어도 된다.  
즉, JVM이 설치되어 있지 않은 서버에서도 실행할 수 있다.

물론 조건은 있다.

Native Image는 특정 OS(Operating System)와 CPU(Central Processing Unit) 아키텍처에 맞게 빌드된다.  
예를 들어 macOS ARM64에서 만든 바이너리를 Linux x86_64 서버에서 그대로 실행할 수는 없다.

```text
macOS arm64용 바이너리
→ Linux x86_64 서버에서 실행 불가
```

운영 서버가 Linux x86_64라면 Linux x86_64용 Native Image를 만들어야 한다.

그래도 실행 시점에 JVM이 필요 없다는 점은 꽤 흥미롭다.

---

## 6. 그러면 애플리케이션 시작 시간이 빨라질까?

그렇다.  
AOT와 Native Image를 사용하면 애플리케이션 시작 시간이 빨라질 수 있다.

일반 Spring Boot 실행에서는 시작 시점에 다음 작업이 많이 수행된다.

```text
클래스패스 스캔
애너테이션 메타데이터 분석
자동 설정 조건 평가
BeanDefinition 생성
프록시 준비
Bean 생성
내장 웹 서버 시작
```

AOT는 이 중 일부를 빌드 시점으로 옮긴다.

Native Image는 JVM 위에서 바이트코드를 실행하는 대신, 미리 기계어로 컴파일된 바이너리를 실행한다.

그래서 시작 시간이 짧아지고, 메모리 사용량도 줄어드는 경우가 많다.

하지만 여기서 중요한 점이 있다.

**빠르다고 해서 항상 써야 하는 것은 아니다.**

---

## 7. 단점도 분명하다

AOT와 Native Image는 장점이 명확하지만, 단점도 분명하다.

```text
빌드 시간이 길어질 수 있음
GraalVM Native Image 빌드 환경이 필요함
리플렉션 기반 라이브러리 호환성 문제가 생길 수 있음
동적 클래스 로딩에 제약이 있음
프록시, 직렬화, 리소스 접근 설정이 필요할 수 있음
디버깅과 운영 분석이 JVM보다 불편할 수 있음
장기 실행 성능은 JVM의 JIT 최적화가 더 유리할 수 있음
```

여기서 **JIT(Just-In-Time)**는 JVM이 실행 중 자주 실행되는 코드를 분석해 기계어로 최적화하는 방식이다.

Native Image는 미리 컴파일된 바이너리이기 때문에 시작은 빠르지만, 장기 실행 중에 JVM이 수행하는 JIT 최적화 이점을 그대로 갖지는 않는다.

따라서 항상 떠 있는 일반적인 API 서버라면, 시작 시간을 몇 초 줄이는 것보다 다음 요소가 더 중요할 수 있다.

```text
라이브러리 호환성
디버깅 편의성
운영 안정성
모니터링 도구 호환성
장기 실행 성능
배포 단순성
```

즉, AOT와 Native Image는 “무조건 좋은 기술”이 아니라, 사용해야 할 상황이 비교적 분명한 기술이다.

---

## 8. 언제 활용할 수 있을까?

AOT와 Native Image는 특히 콜드 스타트가 중요한 환경에서 의미가 크다.

예를 들면 다음과 같은 상황이다.

```text
서버리스 함수
짧고 자주 실행되는 배치
Kubernetes CronJob
컨테이너가 자주 뜨고 내려가는 환경
오토스케일링이 매우 잦은 서비스
메모리 제한이 강한 환경
CLI 도구
```

여기서 **콜드 스타트**는 애플리케이션 프로세스가 새로 시작되어 요청을 처리할 준비가 될 때까지 걸리는 시간을 의미한다.

예를 들어 서버리스 함수에서는 요청이 들어왔을 때 컨테이너가 새로 생성되고 애플리케이션이 시작될 수 있다.

```text
요청 발생
→ 컨테이너 시작
→ 애플리케이션 시작
→ 요청 처리
```

이때 Spring Boot 애플리케이션 시작 시간이 5초라면 사용자 응답 지연이 커질 수 있다.  
반대로 Native Image로 시작 시간이 크게 줄어든다면 실질적인 효과가 있다.

짧고 자주 실행되는 배치도 비슷하다.

```text
전체 실행 시간 10초
애플리케이션 시작 시간 4초
실제 작업 시간 6초
```

이런 구조라면 시작 시간이 전체 실행 시간에서 큰 비중을 차지한다.

이 경우 AOT나 Native Image를 검토할 수 있다.

하지만 배치가 2시간 동안 실행된다면 이야기가 달라진다.

```text
전체 실행 시간 2시간
애플리케이션 시작 시간 5초
```

이 경우 시작 시간을 줄여도 전체 실행 시간에 미치는 영향은 작다.  
이때는 AOT보다 DB 쿼리 최적화, 인덱스 설계, chunk 처리, 병렬 처리, 외부 API 호출 구조 개선이 더 중요하다.

---

## 9. “짧게 실행할 거면 그냥 스크립트를 쓰면 되지 않나?”

이 의문도 자연스럽다.

짧게 실행되는 작업이라고 해서 무조건 Spring Boot를 쓸 필요는 없다.

예를 들어 다음 작업이라면 Python, Bash, Node.js 같은 스크립트가 더 적절할 수 있다.

```text
CSV 파일 하나 변환
특정 디렉토리 파일 정리
간단한 API 호출 후 결과 저장
S3 파일 다운로드 후 압축
로그 파일 grep 후 요약
```

이런 작업에 Spring Boot와 AOT를 도입하는 것은 과할 수 있다.

Spring을 쓰는 이유는 “짧게 실행되기 때문”이 아니다.  
작업 자체가 Spring의 구조를 필요로 할 때 Spring을 쓰는 것이다.

예를 들어 다음과 같은 요구사항이 있다면 Spring Batch를 고려할 수 있다.

```text
DB 트랜잭션 관리가 필요함
대량 데이터를 chunk 단위로 처리해야 함
실패한 지점부터 재시작해야 함
처리 이력을 관리해야 함
여러 Job/Step 구조가 필요함
Skip/Retry 정책이 필요함
기존 Spring Service와 Repository를 재사용해야 함
외부 시스템 연동이 복잡함
```

이런 경우 Spring Batch는 의미가 있다.

그리고 그 Spring Batch 작업이 짧고 자주 실행되며, 매번 새 프로세스로 뜨고, 시작 시간이 부담된다면 AOT/Native Image가 추가 최적화 수단이 될 수 있다.

순서는 이렇게 보는 것이 맞다.

```text
1. 작업 복잡도 때문에 Spring 또는 Spring Batch가 필요하다
2. 그런데 실행이 짧고 자주 발생한다
3. Spring Boot 시작 시간이 실제 병목이다
4. AOT/Native Image를 검토한다
```

반대로 이렇게 생각하면 이상하다.

```text
1. AOT가 빠르다
2. 그러니 짧은 작업에 Spring을 쓰자
```

AOT는 Spring을 선택하는 이유가 아니라, 이미 Spring을 쓰는 애플리케이션의 시작 비용을 줄이는 최적화 수단이다.

---

## 10. 기존 서버에서 스케줄러로 돌리면 안 되나?

대부분의 경우에는 기존 Spring Boot 프로젝트 안에서 스케줄러로 처리하는 것이 더 자연스럽다.

예를 들어 기존 서버가 항상 떠 있고, 배치 로직이 기존 Service, Repository, 설정을 그대로 사용한다면 굳이 별도 AOT 프로젝트로 분리할 필요는 낮다.

```java
@Scheduled(cron = "0 0 3 * * *")
public void runDailyJob() {
    settlementService.settle();
}
```

이 방식은 단순하다.

```text
기존 코드 재사용 가능
배포 단위가 늘어나지 않음
설정 관리가 단순함
DB 연결과 트랜잭션 관리 재사용 가능
로그와 모니터링 체계 재사용 가능
```

하지만 배치가 무겁다면 이야기가 달라진다.

웹 서버와 배치가 같은 JVM 안에서 실행되면 CPU, 메모리, DB 커넥션 풀을 공유한다.

```text
하나의 JVM
├─ 웹 요청 처리
├─ API 응답
├─ DB 커넥션 풀 사용
├─ 스케줄러 배치 실행
└─ 메모리와 CPU 공유
```

배치가 대량 데이터를 처리하면서 DB 커넥션을 오래 점유하거나, 메모리를 많이 사용하거나, CPU를 과도하게 사용하면 웹 요청 처리에도 영향을 줄 수 있다.

이 경우 배치를 별도 실행 단위로 분리할 수 있다.

다만 이때의 분리 이유는 AOT가 아니다.

```text
배치를 분리하는 이유
→ 장애 격리
→ 리소스 격리
→ 배포 주기 분리
→ 재시작 범위 분리
→ 운영 책임 분리

AOT를 사용하는 이유
→ 시작 시간 감소
→ 메모리 사용량 감소
→ JVM 없는 실행 환경 지원
```

즉, 배치를 분리해야 한다고 해서 곧바로 AOT를 써야 하는 것은 아니다.

배치를 분리한 뒤에도 일반 JVM 방식으로 실행할 수 있다.  
그 상태에서 시작 시간이나 메모리 사용량이 실제 문제가 될 때 AOT/Native Image를 검토하는 것이 자연스럽다.

---

## 11. 정리: AOT와 Native Image는 마지막 최적화인가?

일반적인 장기 실행 Spring Boot 서버에서는 AOT/Native Image가 마지막 최적화에 가까울 수 있다.

우선순위로 보면 보통 다음이 먼저다.

```text
1. 애플리케이션 구조가 적절한가
2. DB 쿼리와 인덱스가 적절한가
3. 외부 API 호출 구조가 적절한가
4. 트랜잭션 범위가 적절한가
5. 스레드 풀과 커넥션 풀 설정이 적절한가
6. 메모리 누수나 GC 문제가 없는가
7. 배포와 스케일링 구조가 적절한가
8. 그래도 시작 시간이나 메모리가 병목인가
9. AOT/Native Image 검토
```

항상 떠 있는 API 서버라면 시작 시간이 몇 초 줄어드는 것보다 운영 안정성과 호환성이 더 중요할 수 있다.

반면 다음 환경에서는 초기 설계 단계부터 검토할 가치가 있다.

```text
서버리스
짧고 자주 실행되는 배치
Kubernetes CronJob
자주 스케일아웃되는 마이크로서비스
메모리 제한이 강한 컨테이너 환경
JVM이 없는 환경에서 실행해야 하는 CLI 도구
```

내가 이번에 이해한 결론은 다음과 같다.

```text
AOT는 Spring이 런타임에 수행하던 일부 분석 작업을 빌드 시점으로 옮기는 기술이다.

Native Image는 JVM 없이 실행 가능한 네이티브 바이너리를 만드는 기술이다.

AOT/Native Image는 Spring을 사용해야 하는 이유가 아니라,
이미 Spring을 사용하는 애플리케이션의 시작 비용과 메모리 비용을 줄이기 위한 최적화 수단이다.
```

---

## 12. 처음 코드로 돌아가서

다시 처음의 코드로 돌아가 보자.

```java
private ConfigurableApplicationContext createDefaultApplicationContext() {
    if (!AotDetector.useGeneratedArtifacts()) {
        return new AnnotationConfigApplicationContext();
    }
    return new GenericApplicationContext();
}
```

처음에는 단순히 `ApplicationContext` 구현체가 무엇인지 확인하려고 봤던 코드였다.

하지만 이 코드에는 Spring Boot의 두 가지 실행 방식이 드러나 있었다.

```text
일반 JVM 실행
→ 런타임에 애너테이션 기반 설정을 분석
→ AnnotationConfigApplicationContext 사용

AOT/Native Image 실행
→ 빌드 시점에 생성된 결과물을 사용
→ GenericApplicationContext 사용
```

즉, `GenericApplicationContext`가 등장한 이유는 Spring이 웹 컨텍스트를 사용하지 않는다거나, 애너테이션 기반 설정을 버렸다는 뜻이 아니다.

오히려 AOT 환경에서는 애너테이션 분석이 빌드 시점에 미리 처리되었기 때문에, 런타임에서는 더 범용적이고 단순한 컨텍스트로 충분해지는 것이다.

이 작은 분기문 하나에서 Spring Boot의 런타임 초기화 방식, AOT, Native Image, JVM 없는 실행까지 이어지는 흐름을 확인할 수 있었다.

---

## 마무리

이번 탐색은 단순한 질문에서 시작했다.

```text
Spring Boot는 어떤 ApplicationContext 구현체를 사용할까?
```

그런데 내부 코드를 따라가다 보니 AOT와 Native Image까지 이어졌다.

특히 흥미로웠던 점은 Spring Boot가 실행 방식에 따라 컨텍스트 선택 전략을 달리한다는 점이었다.

일반 실행에서는 런타임에 애너테이션 기반 설정을 해석한다.  
AOT 실행에서는 빌드 시점에 미리 생성된 결과물을 사용한다.  
Native Image까지 가면 JVM 없이 실행 가능한 바이너리도 만들 수 있다.

결국 중요한 것은 기술 자체보다 **언제 이 기술을 선택할 것인가**다.

AOT와 Native Image는 분명 강력하다.  
하지만 모든 Spring Boot 애플리케이션에 무조건 적용해야 하는 기술은 아니다.

항상 떠 있는 서버인지, 짧게 실행되는 배치인지, 서버리스인지, 메모리 제한이 중요한지, 콜드 스타트가 실제 문제인지에 따라 판단해야 한다.

이번 코드를 통해 Spring Boot의 내부 초기화 과정뿐 아니라, 기술 선택의 기준까지 같이 고민해볼 수 있었다.