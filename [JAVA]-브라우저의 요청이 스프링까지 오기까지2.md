# 이전 발표 오류 사항 :
이전 발표에서 "코요테에서 body를 꼭 Stream으로 넘기지 않을 수도 있다"라고 표현했는데, 이 표현은 부정확하다.
사실 Tomcat Coyote가 요청 body를 JSON, DTO, Map, MultipartFile 같은 애플리케이션 객체로 파싱해서 넘기는 경우는 없다고 봐도 무방하다.
Coyote의 역할은 body 내용을 비즈니스 객체로 변환하는 것이 아니라,
HTTP 요청 라인과 헤더를 파싱하고, body를 어떤 규칙으로 읽어야 하는지 준비하는 것이다.


# 이전 편 보완 자료 : 
### Request.recycle()과 RequestFacade

이전 발표에서 Catalina의 Request.recycle()같은 메서드들이 어플리케이션에 의해 호출되지 않도록 Facade 패턴으로  RequestFacade 객체를 만들어 넘긴다는 이야기를 했었다.
여기서 recycle() 메서드 라는게 있다 식으로 넘기며, 자세하게 알아보지 않았었는데, 이걸 자세히 보다보면 톰캣의 철학을 엿볼 수 있어, 한번 정리해 보았다.

결론부터 정리하자면 다음 한 문장이다.

> 톰캣은 고트래픽 환경에서 객체 생성과 GC(Garbage Collection) 부담을 줄이기 위해 내부 요청 객체를 재사용하고, 그 재사용 생명주기를 안전하게 관리하기 위해 recycle()과 RequestFacade 구조를 함께 사용한다.

---

### 1. 톰캣은 가벼운 서블릿 컨테이너를 지향한다.

톰캣은 짧은 시간 동안 매우 많은 HTTP 요청을 반복해서 처리해야 한다.
이런 환경에서 요청이 들어올 때마다 내부 객체를 계속 새로 만들면 다음 비용이 누적된다.

- 객체 생성 비용
- 필드 초기화 비용
- 짧은 생명주기의 객체 증가
- GC 대상 증가
- 메모리 사용량 증가

그래서 톰캣 내부에는 Request.recycle()과 같이 한 번 만든 객체를 요청 종료 후 초기화하고 다시 사용하는 구조가 들어 있다.

recycle() 메서드의 호출 위치는 아래와 같다.

```
요청 1 처리
    ↓
Request 내부 상태 사용
    ↓
요청 종료
    ↓
Request.recycle()
    ↓
이전 요청 상태 초기화
    ↓
다음 요청에서 재사용 가능
```
---

### 2. recycle()은 무엇을 하는 메서드인가

톰캣 내부의 실제 요청 객체는 다음 클래스다.

org.apache.catalina.connector.Request

이 객체에는 recycle()이라는 메서드가 있다.

request.recycle();

recycle()은 요청 처리가 끝난 뒤, 해당 Request 객체를 다음 요청에서 다시 사용할 수 있도록 내부 상태를 초기화하는 메서드다.

즉 객체를 삭제하는 메서드가 아니다.
또 Java GC를 직접 실행하는 메서드도 아니다.

정확히는 다음 요청을 위해 이전 요청의 정보를 지우는 메서드다.

예를 들면 다음 상태들이 정리될 수 있다.

- header 관련 캐시
- parameter 파싱 상태
- cookie 처리 상태
- session 연결 상태
- mapping 결과
- attribute 저장소
- input buffer 상태
- facade 연결 상태

따라서 recycle()은 애플리케이션 코드가 호출하면 안 된다.
요청 처리 생명주기에 맞춰 톰캣 컨테이너가 호출해야 한다.

---

### 3. 왜 recycle()을 애플리케이션에 노출하면 안 될까

만약 애플리케이션 코드가 내부 Request를 직접 다룰 수 있다면 이런 호출이 가능해진다.

request.recycle();

요청 처리 도중에 이 메서드가 호출되면, 아직 사용 중인 요청 정보가 초기화될 수 있다.

그러면 다음 문제가 생긴다.

- getHeader() 결과가 이상해질 수 있다.
- getParameter() 결과가 사라질 수 있다.
- getSession() 연결이 깨질 수 있다.
- 요청 매핑 정보가 사라질 수 있다.
- request body 읽기 상태가 꼬일 수 있다.
- response와의 연결 상태가 깨질 수 있다.

즉 recycle()은 요청 처리 흐름을 컨테이너가 정상적으로 끝냈을 때만 호출되어야 한다.

이 구조 때문에 내부 Request 객체는 애플리케이션 코드에 직접 노출되면 안 된다.
애플리케이션이 recycle() 같은 메서드를 직접 호출하면, 아직 처리 중인 요청 상태가 초기화되어 요청 흐름이 깨질 수 있기 때문이다.
그래서 RequestFacade를 사용한다.

---

### 4. recycle() 말고도 감춰지는 내부 메서드들

recycle()만 감춰지는 것은 아니다.
org.apache.catalina.connector.Request에는 톰캣 내부에서만 사용해야 하는 메서드들이 많다.

예를 들면 다음과 같다.

setContext(...)
- 현재 요청이 어느 웹 애플리케이션 Context에 속하는지 설정
setWrapper(...)
- 현재 요청을 처리할 Servlet Wrapper 설정
setCoyoteRequest(...)
- Coyote 계층의 요청 객체와 연결
setConnector(...)
- 현재 요청을 받은 Connector와 연결
setResponse(...)
- 현재 요청과 응답 객체 연결
clearCookies()
- 파싱된 쿠키 캐시 초기화
clearLocales()
- Locale 관련 캐시 초기화

이런 메서드들이 애플리케이션에 노출되면, 요청이 어느 웹 애플리케이션으로 가야 하는지, 어떤 서블릿이 처리해야 하는지, 어떤 응답과 연결되어 있는지 같은 내부 상태가 외부 코드에 의해 바뀔 수 있다.

그래서 RequestFacade는 이런 내부 제어 메서드를 숨기고, HttpServletRequest에 정의된 표준 기능만 제공한다.

참고용 실제 코드:
- [org.apache.catalina.connector.Request](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/Request.java)

---

# 준비된 요청은 Catalina 내부에서 어떻게 Servlet까지 도달할까

RequestFacade, recycle, Pipeline, Valve, FilterChain 기준으로 보는 톰캣 요청 실행 흐름

이전 편에서는 브라우저가 보낸 HTTP 요청이 Coyote에서 어떻게 읽히고, CoyoteAdapter를 거쳐 Catalina 쪽 요청/응답 객체로 연결되는지 정리했다.

이번 편에서는 그 다음 흐름을 따라가 보려고 한다.


### 1. Catalina 내부 실행 흐름으로 들어가자

이전 편에서 CoyoteAdapter는 Coyote 요청/응답과 Catalina 요청/응답을 연결한다고 했다.

이제 연결된 요청은 Catalina 내부 컨테이너 구조를 따라 이동한다.

큰 흐름은 다음과 같다.
```
CoyoteAdapter.service()
    ↓
Engine Pipeline
    ↓
StandardEngineValve
    ↓
Host Pipeline
    ↓
StandardHostValve
    ↓
Context Pipeline
    ↓
StandardContextValve
    ↓
Wrapper Pipeline
    ↓
StandardWrapperValve
    ↓
ApplicationFilterChain
    ↓
Filter.doFilter()
    ↓
Servlet.service()
    ↓
DispatcherServlet.service()
```
여기서 핵심 용어가 두 개 나온다.

Pipeline
Valve

---

### 2. Pipeline과 Valve

- Pipeline: 요청 처리 단계를 연결하는 구조
- Valve: 그 Pipeline 안에서 실제로 실행되는 처리 단위

Tomcat 공식 문서에서는 Valve를 Catalina 컨테이너의 요청 처리 파이프라인에 삽입되는 컴포넌트로 설명한다. 
특히 Tomcat 4.1 문서에서는 Valve가 Engine, Host, Context와 같은 Catalina 컨테이너의 request processing pipeline에 삽입된다고 명시하고 있으며, 최신 Tomcat 9 문서에서도 각 Valve가 Engine, Host, Context에 연결될 수 있다고 설명한다.
[공식 문서](https://tomcat.apache.org/tomcat-4.1-doc/config/valve.html?utm_source=chatgpt.com)

구조는 다음처럼 볼 수 있다.
```
Container
  └─ Pipeline
       ├─ Valve
       ├─ Valve
       └─ Basic Valve
```
여기서 Basic Valve는 해당 컨테이너 단계에서 기본적으로 실행되는 마지막 Valve다.

Catalina 컨테이너 계층은 대략 다음과 같다.
```
Engine
  └─ Host
       └─ Context
            └─ Wrapper
```
각 계층은 의미가 다르다.

##### Engine
- 하나의 Service 안에서 요청 처리를 담당하는 최상위 컨테이너
##### Host
- 가상 호스트
- 예: localhost, example.com
##### Context
- 하나의 웹 애플리케이션
- 예: /, /app, /api
##### Wrapper
- 하나의 Servlet
- 예: dispatcherServlet Wrapper
##### Servlet
- 실제 요청을 처리하는 애플리케이션 코드
- 예: DispatcherServlet

이해를 돕기위한 예시: 
```
Host: localhost
  └── Context: /shop
        ├── Wrapper: OrderServlet
        │     └── Servlet: OrderServlet 인스턴스
        │
        ├── Wrapper: CartServlet
        │     └── Servlet: CartServlet 인스턴스
        │
        └── Wrapper: UserServlet
              └── Servlet: UserServlet 인스턴스
```
즉 요청은 단순히 바로 서블릿으로 가는 것이 아니라, Engine → Host → Context → Wrapper → Servlet 순서로 매핑되고 실행된다.

Tomcat의 HTTP Connector는 특정 TCP 포트에서 연결을 받고, 하나 이상의 Connector가 하나의 Service에 속하며, 해당 Service의 Engine으로 요청을 넘겨 처리하게 된다.  ￼

---

### 3. Engine 단계

CoyoteAdapter는 요청을 Catalina 컨테이너에 넘긴다.
이때 최상위 진입점은 보통 Engine이다.

Engine 단계에서는 요청을 어떤 Host로 보낼지 결정된 상태를 기반으로 다음 단계로 넘긴다.

흐름은 대략 다음과 같다.
```
CoyoteAdapter.service()
    ↓
Engine.getPipeline().getFirst().invoke(request, response)
    ↓
StandardEngineValve.invoke()
    ↓
Host로 전달
```
StandardEngineValve의 핵심 역할은 현재 요청에 매핑된 Host를 찾아 해당 Host의 Pipeline으로 넘기는 것이다.

Engine 단계의 핵심:
요청을 적절한 Host 컨테이너로 전달한다.

예를 들어 다음 요청이 있다고 하자.

GET /hello HTTP/1.1
Host: localhost:8080

이 요청의 Host 헤더를 기준으로 Catalina는 어떤 가상 호스트가 이 요청을 처리할지 결정한다.

개발 환경에서는 대부분 localhost 하나만 사용하므로 이 단계가 잘 보이지 않는다.
하지만 하나의 Tomcat에서 여러 가상 호스트를 운영할 수 있기 때문에 Host 선택 단계가 존재한다.

---

### 4. Host 단계

Host는 가상 호스트 단위 컨테이너다.

예를 들어 다음과 같은 도메인을 생각할 수 있다.

- www.example.com
- admin.example.com
- api.example.com

각 도메인이 서로 다른 웹 애플리케이션 구성을 가질 수 있다.

Host 단계에서는 요청을 어떤 Context, 즉 어떤 웹 애플리케이션으로 보낼지 결정한다.

Host Pipeline
    ↓
StandardHostValve.invoke()
    ↓
Context Pipeline

여기서 Context는 웹 애플리케이션 하나를 의미한다.

예를 들어 다음과 같이 배포되어 있다고 하자.
```
/       → ROOT 애플리케이션
/app    → app 애플리케이션
/admin  → admin 애플리케이션
```
요청 URI가 다음이라면

`/app/users/1`

Tomcat은 /app Context를 선택한다.

Host 단계의 핵심:
요청 URI를 기준으로 어떤 웹 애플리케이션 Context가 처리할지 결정한다.

---

### 5. Context 단계

Context는 하나의 웹 애플리케이션을 의미한다.

Spring Boot 내장 Tomcat을 쓰는 경우에도 결국 하나의 웹 애플리케이션 Context가 존재한다.

Context 단계에서는 요청을 어떤 Wrapper, 즉 어떤 Servlet이 처리할지 결정한다.
```
Context Pipeline
    ↓
StandardContextValve.invoke()
    ↓
Wrapper Pipeline
```
여기서 중요한 것이 Servlet Mapping이다.

예를 들어 Spring MVC 애플리케이션에서는 보통 DispatcherServlet이 다음과 같이 매핑된다.

`/`

또는 전통적인 web.xml 기반 애플리케이션에서는 다음처럼 특정 경로에 매핑될 수도 있다.
```
<servlet-mapping>
    <servlet-name>dispatcher</servlet-name>
    <url-pattern>/api/*</url-pattern>
</servlet-mapping>
```
Tomcat은 요청 URI와 Servlet Mapping 정보를 비교해서 어떤 Servlet이 요청을 처리할지 결정한다.

Context 단계의 핵심:
현재 웹 애플리케이션 안에서 어떤 Servlet Wrapper로 보낼지 결정한다.

---

### 6. Wrapper 단계

Wrapper는 하나의 Servlet을 감싸는 컨테이너다.

Spring MVC에서는 보통 다음 Servlet이 Wrapper에 들어 있다.

org.springframework.web.servlet.DispatcherServlet

즉 Wrapper는 “Servlet 하나를 관리하는 Catalina 컨테이너”라고 볼 수 있다.

Wrapper 단계의 핵심 클래스는 다음이다.

`org.apache.catalina.core.StandardWrapperValve`

이 단계에서 중요한 일이 일어난다.

1. Servlet 인스턴스를 가져온다.
2. 해당 요청에 적용될 Filter들을 찾는다.
3. ApplicationFilterChain을 만든다.
4. FilterChain을 실행한다.
5. 최종적으로 Servlet.service()를 호출한다.

흐름은 다음과 같다.
```
StandardWrapperValve.invoke()
    ↓
ApplicationFilterFactory.createFilterChain()
    ↓
ApplicationFilterChain.doFilter()
    ↓
Filter.doFilter()
    ↓
Servlet.service()
```
여기서 드디어 우리가 알고 있는 서블릿 실행 흐름으로 들어간다.

---

### 7. ApplicationFilterChain은 언제 만들어질까

Spring MVC를 공부할 때 필터는 보통 다음처럼 설명된다.
```
Filter
    ↓
DispatcherServlet
    ↓
Interceptor
    ↓
Controller
```
그런데 Tomcat 관점에서 보면 필터 체인은 DispatcherServlet이 만드는 것이 아니다.

필터 체인은 Catalina의 Wrapper 단계에서 만들어진다.

즉 순서는 다음과 같다.
```
Tomcat StandardWrapperValve
    ↓
ApplicationFilterChain 생성
    ↓
Filter 실행
    ↓
DispatcherServlet.service() 호출
```
ApplicationFilterChain은 현재 요청 URL과 Servlet Mapping에 맞는 필터들을 모아서 만든 실행 체인이다.

예를 들어 다음 필터들이 있다고 하자.

- LoggingFilter
- SecurityFilter
- EncodingFilter

그리고 최종 Servlet이 DispatcherServlet이라면 실행 구조는 다음과 같다.
```
ApplicationFilterChain.doFilter()
    ↓
LoggingFilter.doFilter()
    ↓
SecurityFilter.doFilter()
    ↓
EncodingFilter.doFilter()
    ↓
DispatcherServlet.service()
```
필터는 다음처럼 다음 단계 호출 여부를 직접 결정한다.

`chain.doFilter(request, response);`

이 호출을 하지 않으면 뒤의 필터나 Servlet은 실행되지 않는다.

예를 들어 인증 실패 시 다음처럼 응답을 끝낼 수 있다.
```
if (!authenticated) {
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```
`chain.doFilter(request, response);`

이 경우 DispatcherServlet까지 요청이 도달하지 않는다.

---

### 8. Servlet.service()와 DispatcherServlet

필터 체인이 끝까지 진행되면 최종적으로 Servlet의 service()가 호출된다.

Spring MVC에서는 이 Servlet이 보통 DispatcherServlet이다.

정확한 흐름은 다음처럼 볼 수 있다.
```
ApplicationFilterChain.internalDoFilter()
    ↓
servlet.service(request, response)
    ↓
DispatcherServlet.service()
    ↓
FrameworkServlet.processRequest()
    ↓
DispatcherServlet.doDispatch()
    ↓
HandlerMapping
    ↓
HandlerAdapter
    ↓
Controller
```
여기부터는 Spring MVC 영역이다.

즉 Tomcat 입장에서는 DispatcherServlet도 그냥 하나의 Servlet이다.

Tomcat은 Spring Controller를 직접 알지 못한다.
Tomcat은 요청을 Servlet 표준에 맞춰 DispatcherServlet.service()까지 호출할 뿐이다.

그 이후에 어떤 Controller를 찾을지, 어떤 @RequestMapping을 실행할지, 어떤 HttpMessageConverter를 사용할지는 Spring MVC가 담당한다.

정리하면 다음과 같다.

- Tomcat의 책임:
  - HTTP 요청을 파싱하고, 서블릿 컨테이너 구조를 따라 적절한 Servlet.service()를 호출한다.
- Spring MVC의 책임:
  - DispatcherServlet 안에서 HandlerMapping, HandlerAdapter, Controller, ViewResolver 또는 HttpMessageConverter 흐름을 처리한다.

---

### 9. 전체 흐름 다시 보기

이제 이전 편과 이번 편을 연결하면 전체 흐름은 다음과 같다.
```
[브라우저]
    ↓
HTTP 요청 메시지 전송
    ↓
[Coyote]
    ↓
Request Line 파싱
Header 파싱
Body 읽기 규칙 준비
    ↓
[CoyoteAdapter]
    ↓
Coyote Request/Response와 Catalina Request/Response 연결
    ↓
[Catalina Request/Response]
    ↓
RequestFacade / ResponseFacade 준비
    ↓
[Engine Pipeline]
    ↓
StandardEngineValve
    ↓
[Host Pipeline]
    ↓
StandardHostValve
    ↓
[Context Pipeline]
    ↓
StandardContextValve
    ↓
[Wrapper Pipeline]
    ↓
StandardWrapperValve
    ↓
ApplicationFilterChain 생성
    ↓
Filter.doFilter()
    ↓
Servlet.service()
    ↓
DispatcherServlet.service()
    ↓
Spring MVC 내부 처리
```
조금 더 압축하면 다음과 같다.

Coyote
- HTTP 프로토콜 처리
CoyoteAdapter
- Coyote와 Catalina 연결
Catalina Request/Response
- 톰캣 내부 요청/응답 객체
RequestFacade/ResponseFacade
- 애플리케이션에 노출되는 서블릿 표준 객체
Engine
- Host 선택
Host
- Context 선택
Context
- Wrapper 선택
Wrapper
- Servlet 실행 준비
ApplicationFilterChain
- Filter 실행 후 Servlet.service() 호출
DispatcherServlet
- Spring MVC 진입점

---

### 10. 주의할 점: request를 저장하지 말 것

이번 내용을 실무 관점에서 가장 직접적으로 연결하면 다음 규칙이 나온다.

HttpServletRequest, HttpServletResponse를 요청 처리 범위 밖으로 저장하지 말 것.

위험한 예시는 다음과 같다.
```
static HttpServletRequest savedRequest;
public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    savedRequest = (HttpServletRequest) request;
    chain.doFilter(request, response);
}
```
또는 다음도 위험하다.
```
executor.submit(() -> {
    String uri = request.getRequestURI();
});
```
요청 처리가 끝난 뒤 비동기 스레드에서 request에 접근하면 이미 recycle된 상태일 수 있다.

안전한 방식은 필요한 값만 복사하는 것이다.
```
String uri = request.getRequestURI();
String method = request.getMethod();
String traceId = request.getHeader("X-Trace-Id");
executor.submit(() -> {
    log.info("method={}, uri={}, traceId={}", method, uri, traceId);
});
```
요청 객체는 컨테이너가 관리하는 생명주기를 가진다.
따라서 요청 생명주기 밖에서 필요하다면 객체 참조가 아니라 값 복사가 필요하다.

---

### 11. 정리

이번 편에서는 CoyoteAdapter 이후 요청이 Catalina 내부에서 어떻게 이동하는지 정리했다.

핵심은 다음과 같다.

1. Catalina의 내부 Request는 애플리케이션에 직접 노출되지 않는다.
2. 애플리케이션은 RequestFacade를 통해 HttpServletRequest 표준 API만 사용한다.
3. Request.recycle()은 요청 처리 후 내부 상태를 초기화하기 위한 컨테이너 내부 메서드다.
4. 요청 객체를 요청 처리 이후에 저장하거나 다른 스레드에서 사용하면 recycle 문제를 만날 수 있다.
5. Catalina 내부 요청 흐름은 Engine → Host → Context → Wrapper 순서로 진행된다.
6. 각 컨테이너는 Pipeline과 Valve 구조를 통해 요청을 다음 단계로 넘긴다.
7. Wrapper 단계에서 ApplicationFilterChain이 만들어진다.
8. 필터 체인 끝에서 최종적으로 Servlet.service()가 호출된다.
9. Spring MVC의 DispatcherServlet은 Tomcat 입장에서는 하나의 Servlet이다.
10. DispatcherServlet 이후부터가 Spring MVC 내부 처리 흐름이다.

최종 흐름 순서.
```
네트워크 바이트 스트림
    ↓
Coyote Request
    ↓
Catalina Request
    ↓
RequestFacade(HttpServletRequest)
    ↓
FilterChain
    ↓
DispatcherServlet
    ↓
Controller
```
이 흐름을 이해하면 DispatcherServlet이 갑자기 등장하는 것이 아니라, Tomcat의 Wrapper가 관리하는 Servlet으로서 호출된다는 점이 보인다.