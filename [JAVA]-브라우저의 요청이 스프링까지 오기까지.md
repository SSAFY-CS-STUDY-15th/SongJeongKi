# **브라우저의 요청은 스프링에 오기 전에 어디를 지날까**
**Coyote와 Catalina를 기준으로 본 톰캣 앞단의 요청 처리 구조**

스프링 MVC의 요청 처리 흐름을 설명할 때는 보통 `DispatcherServlet`부터 시작한다.  
하지만 실제 요청은 그보다 먼저 톰캣 내부에서 HTTP 파싱과 요청/응답 객체 준비 과정을 거친다.

이번 글에서는 범위를 넓히지 않고, **브라우저가 보낸 HTTP 요청이 어떤 과정을 거쳐 톰캣 내부에서 해석되고, 어떻게 서블릿 표준 요청/응답 객체로 준비되기 시작하는지**까지만 정리해보려 한다.  
즉 이번 편의 관심사는 필터 체인이나 `DispatcherServlet` 내부 동작이 아니라, 그 이전 단계인 **Coyote와 Catalina 앞단**이다.

구조를 단순화하면 이번 글에서 다룰 범위는 아래와 같다.

```
-------------------------------------------
[브라우저]
    ↓
[소켓 연결 / HTTP 요청 전송]
    ↓
[Coyote]
  ├─ 요청 수신
  ├─ Request Line 파싱
  ├─ Header 파싱
  └─ Body 읽기/처리 준비
    ↓
[Adapter]
  └─ Coyote가 파싱한 요청을 Catalina의 서블릿 컨테이너 처리 흐름으로 넘기는 브리지
    ↓
[Catalina]
  ├─ Coyote 요청/응답을 Catalina의 서블릿 처리 흐름에 연결
  ├─ Context / Servlet 매핑
  └─ 컨테이너 파이프라인을 통해 서블릿 실행 준비
-------------------------------------------
    ↓
[FilterChain]
    ↓
[DispatcherServlet]
    ↓
[HandlerMapping]
    ↓
[Controller]
    ↓
[Service / Business Logic]
    ↓
[Controller 반환]
    ↓
[ViewResolver 또는 HttpMessageConverter]
    ↓
[Catalina]
  └─ 서블릿 실행 결과 회수
    ↓
[Coyote]
  ├─ 응답 상태코드/헤더 작성
  ├─ 응답 바디 직렬화
  └─ 소켓으로 HTTP 응답 전송
    ↓
[브라우저]
```
이 범위만 정확히 잡아도 이후에 필터 체인, Spring Security, `DispatcherServlet`을 볼 때 책임 경계가 훨씬 분명해진다.

---
## **1. 브라우저의 요청은 아직 자바 객체가 아니다**

브라우저는 사용자가 URL을 입력하거나 링크를 클릭하면 HTTP 요청 메시지를 만든다.  
이 요청에는 URL, HTTP Method, Header, Cookie, Body 같은 정보가 포함된다. 하지만 이 시점의 요청은 아직 `HttpServletRequest` 같은 자바 객체가 아니다.  
그냥 네트워크를 통해 전송되는 **바이트 스트림**일 뿐이다.

즉 서버 입장에서는 먼저 해야 할 일이 하나 있다.  
브라우저가 보낸 이 바이트를 읽어서 “HTTP 요청”으로 해석해야 한다는 점이다.

이 역할을 톰캣 내부에서 맡는 쪽이 바로 **Coyote**다.

---

## **2. Coyote: HTTP 요청을 처음 읽는 계층**

톰캣 내부를 단순화해서 보면, **Coyote는 HTTP 프로토콜 처리 계층**이다.  
브라우저가 보낸 바이트 스트림을 읽어 request line과 header를 먼저 파싱하고, body는 이후 읽을 수 있도록 처리 방식과 입력 필터를 준비한다.

이 단계에서 핵심이 되는 클래스는 다음과 같다.
- [org.apache.tomcat.util.net.NioEndpoint](./assets/tomcat-embed-core-11.0.21-sources/org/apache/tomcat/util/net/NioEndpoint.java)
- [org.apache.coyote.http11.Http11NioProtocol](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/http11/Http11NioProtocol.java)
- [org.apache.coyote.http11.Http11Processor](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/http11/Http11Processor.java)

각 클래스의 역할을 짧게 정리하면 이렇다.
### **`org.apache.tomcat.util.net.NioEndpoint`**
주요 메서드
* NioEndpoint.bind()
    * 서버 소켓 바인딩
* NioEndpoint.startInternal()
    * endpoint 시작
* NioEndpoint.Acceptor.run()
    * 클라이언트 연결 accept
* NioEndpoint.Poller.run()
    * 읽기/쓰기 이벤트 감시
* SocketProcessor.doRun()
    * 들어온 소켓을 실제 처리 흐름으로 넘김
>실제 소켓 연결을 받고, 네트워크 입출력을 처리하는 입구에 가깝다.  

### **`org.apache.coyote.http11.Http11NioProtocol`**
주요 메서드
* Http11NioProtocol()
    * NioEndpoint를 생성해서 NIO 기반 네트워크 처리 구조를 연결
* getNamePrefix()
    * http-nio 같은 프로토콜 이름 prefix 제공
* AbstractProtocol.init()
    * 프로토콜 핸들러 초기화
* AbstractProtocol.start()
    * 프로토콜 핸들러 시작
* ConnectionHandler.process()
    * 들어온 소켓 연결에 대해 적절한 Processor를 연결하고 처리 시작
* createProcessor()
    * Http11Processor 생성

>HTTP/1.1 요청을 NIO 기반으로 처리하기 위한 상위 프로토콜 핸들러다.
>NioEndpoint를 사용해 소켓을 받고, Http11Processor를 생성·관리하는 쪽이다.

> [막간 상식] NIO(Non-blocking I/O)란?  
> 기존 입출력 방식은 보통 블로킹 I/O로, 데이터가 안오면 해당 스레드가 무조건 기다렸지만, NIO는 데이터가 준비되었는지 보고, 준비된 것만 처리 후 다른 일을 할 수 있음.  
> 즉, 한 스레드가 무작정 묶이지 않게 하는 방식임.


### **`org.apache.coyote.http11.Http11Processor`**
주요 메서드
* service(SocketWrapperBase<?> socketWrapper)
    * 요청 1개를 실제로 처리하는 핵심 진입점
* setSocketWrapper(socketWrapper)
    * 현재 요청을 처리할 소켓 연결 설정
* inputBuffer.parseRequestLine(...)
    * request line 파싱 (ex : GET /hello?name=kim HTTP/1.1)
* prepareRequestProtocol()
    * HTTP/1.1, 1.0, 0.9 판별
* inputBuffer.parseHeaders()
    * 헤더 파싱
* prepareRequest()
    * Host/URI/keep-alive/body 처리 방식 등 요청 검증 및 준비
* prepareInputFilters(headers)
    * body를 어떤 방식으로 읽을지 결정
* getAdapter().service(request, response)
    * 파싱된 요청을 Catalina 쪽으로 넘김
* endRequest()
    * 요청 종료 처리
* prepareResponse()
    * 응답 헤더/바디 전송 준비

>HTTP 요청 1개를 실제로 처리하는 실행 담당 클래스다.
>request line, header, body를 읽고 해석해, 서블릿 컨테이너가 사용할 수 있는 상태로 만든다.

### Coyote의 역할을 한 줄로 정리하면

브라우저가 보낸 바이트 스트림을 읽어, request line과 header를 먼저 해석하고, body는 이후에 읽을 수 있도록 처리 규칙을 준비하는 것이다.

### 주의사항
org.apache.coyote.http11.Http11Processor#service() 안에서는 먼저 다음 메서드들이 호출된다.
* inputBuffer.parseRequestLine(...)
* prepareRequestProtocol()
* inputBuffer.parseHeaders()

즉 request line과 header는 이 단계에서 이미 파싱된다.

반면 body는 다르다.
이 단계의 핵심은 body 전체를 미리 애플리케이션 객체로 만드는 것이 아니라,
이후 필요 시 읽을 수 있도록 입력 처리 규칙을 준비하는 것이다.
prepareRequest() 내부에서 prepareInputFilters(headers)가 호출되고, 여기서는 Content-Length, Transfer-Encoding: chunked 같은 정보를 바탕으로 요청 본문을 어떤 방식으로 읽을지 결정한다.

즉 이 단계에서 body에 대해 일어나는 핵심은
“본문 전체를 미리 객체화하는 것”이 아니라,
본문을 이후에 읽을 수 있도록 입력 필터와 읽기 규칙을 설정하는 것이다.

---

### 이 차이가 왜 중요할까

예를 들어 header는 이미 파싱되어 있기 때문에, 필터나 컨트롤러에서 getHeader()로 여러 번 참조해도 보통 큰 문제가 없다.
반면 body는 실제 소비 시점이 뒤로 미뤄져 있기 때문에, 필터에서 request.getInputStream()이나 getReader()로 먼저 끝까지 읽어버리면 이후 @RequestBody나 HttpMessageConverter가 다시 읽을 때 비어 있을 수 있다.

즉 톰캣 앞단을 이해하는 실질적인 의미는 단순히
“요청이 스프링보다 먼저 파싱된다”는 사실을 아는 데 있지 않다.

더 중요한 건,
헤더는 이미 파싱되어 있고, body는 아직 읽기 규칙만 준비된 상태라는 차이를 이해하는 데 있다.

이 관점을 잡고 나면 왜 어떤 값은 필터에서 읽어도 안전하고, 왜 어떤 값은 먼저 건드리면 뒤에서 문제가 생기는지도 훨씬 자연스럽게 설명할 수 있다.

---

### Coyote 결론

결국 Coyote를 이해한다는 것은 톰캣 내부 클래스를 외우는 것이 아니라,
요청의 어떤 부분은 이미 구조화되어 있고, 어떤 부분은 아직 소비 시점이 남아 있는지를 구분하는 데 있다.

---


## **3. Adapter: HTTP 처리 결과를 서블릿 컨테이너 흐름으로 넘기는 경계**

Coyote가 HTTP 요청을 읽고 해석했다고 해서, 그 요청이 곧바로 스프링 MVC로 들어가는 것은 아니다.  
이제 그 결과를 **서블릿 컨테이너 실행 흐름**으로 넘겨야 한다.

이 경계를 담당하는 핵심 클래스가 다음이다.

- [org.apache.catalina.connector.CoyoteAdapter](./assets/CoyoteAdapter.java)

이 클래스는 이름 그대로 **Coyote와 Catalina 사이를 연결하는 어댑터** 역할을 한다.  
앞단의 Coyote가 HTTP 프로토콜을 처리했다면, 이제부터는 Catalina가 그 결과를 웹 애플리케이션 처리 흐름으로 이어받는다.

여기서 Coyote 쪽 `Request/Response`는 HTTP 메시지 자체를 처리하기 위한 내부 객체이고, Catalina 쪽 `Request/Response`는 이를 감싸 `HttpServletRequest` / `HttpServletResponse` 형태로 서블릿 컨테이너에서 사용할 수 있게 만든 객체다.  

따라서 `CoyoteAdapter`는 단순 전달자가 아니라, **HTTP 처리용 객체를 웹 애플리케이션 처리용 객체와 연결해 컨테이너 파이프라인으로 태우는 역할**을 한다.

즉 이 지점의 역할을 단순화하면 다음과 같이 볼 수 있다.
- **Coyote**: HTTP 요청을 읽고 해석
- **CoyoteAdapter**: 해석된 요청을 Catalina 쪽 흐름으로 전달
- **Catalina**: 요청/응답 객체를 구성하고 서블릿 컨테이너 흐름에 올림

이 경계를 기준으로 보면,  
“HTTP를 다루는 단계”와 “자바 웹 애플리케이션이 사용할 요청 객체를 준비하는 단계”를 분리해서 볼 수 있다.

볼만한 메서드
* service(req, res)
	- Coyote가 준비한 요청/응답을 받아 Catalina 쪽 `Request` / `Response`와 연결하고, 이를 컨테이너가 처리할 수 있는 상태로 만든 뒤 컨테이너 파이프라인으로 넘겨 실제 서블릿 처리 흐름이 시작되도록 한다.



---

## **4. Catalina: Request / Response 객체가 준비되는 지점**

Catalina는 톰캣의 서블릿 컨테이너 계층이다.  
이번 글에서 Catalina 전체를 다 다루지는 않지만, 적어도 **요청과 응답이 어떤 객체로 준비되기 시작하는지**는 보고 넘어갈 필요가 있다.

이 단계에서 핵심이 되는 클래스는 다음과 같다.
- [org.apache.catalina.connector.Request](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/Request.java)
- [org.apache.catalina.connector.Response](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/Response.java)
- [org.apache.catalina.connector.RequestFacade](./assets/tomcat-embed-core-11.0.21-sources/org/apache/catalina/connector/RequestFacade.java)
- [org.apache.catalina.connector.ResponseFacade](./assets/tomcat-embed-core-11.0.21-sources/org/apache/catalina/connector/ResponseFacade.java)

각 클래스의 역할은 아래처럼 볼 수 있다.

### **`org.apache.catalina.connector.Request`**

톰캣 내부에서 사용하는 실제 요청 객체다.  
Catalina는 이 내부 객체를 중심으로 현재 요청 상태를 관리한다.

### **`org.apache.catalina.connector.Response`**

톰캣 내부에서 사용하는 실제 응답 객체다.  
응답 상태 코드, 헤더, 바디 등의 정보도 이 객체를 중심으로 관리된다.

### **`org.apache.catalina.connector.RequestFacade`**

`HttpServletRequest`를 구현하는 외부 노출용 래퍼다.  
애플리케이션 코드는 내부 `Request`를 직접 다루지 않고, 이 `Facade`를 통해 서블릿 표준 인터페이스에 접근한다.

### **`org.apache.catalina.connector.ResponseFacade`**

`HttpServletResponse`를 구현하는 외부 노출용 래퍼다.  
응답 역시 내부 객체를 직접 노출하지 않고, 표준 인터페이스 형태로 감싸 제공한다.

즉 이 흐름을 기준으로 보면, 우리가 나중에 스프링 컨트롤러나 필터에서 사용하는 request/response는 **스프링이 처음부터 만든 객체가 아니다**.  
정확히는 톰캣이 내부적으로 관리하는 요청/응답 객체를 서블릿 표준 인터페이스 형태로 감싸 애플리케이션 코드에서 사용할 수 있게 만든 것이다.

이 점을 한 문장으로 정리하면 다음과 같다.

`HttpServletRequest`,`HttpServletResponse`는 스프링 고유 객체가 아니라, 톰캣이 준비한 서블릿 표준 요청/응답 객체다.

---

## **5. 이번 편에서 여기까지만 보면 되는 이유**

여기까지 오면 요청은 브라우저가 보낸 단순한 바이트 스트림 상태를 벗어나,  
톰캣 내부에서 **HTTP 요청으로 해석**되었고,  
Catalina를 통해 **서블릿 표준 요청/응답 객체로 준비되기 시작한 상태**가 된다.

즉 이번 편에서 정리한 범위는 다음과 같다.

1. 브라우저는 HTTP 요청 메시지를 전송한다.
2. Coyote는 이 요청을 수신하고, request line / header / body를 해석한다.
3. `CoyoteAdapter`는 이를 Catalina 쪽으로 연결한다.
4. Catalina는 내부 `Request`, `Response`를 구성하고,
5. 이를 `RequestFacade`, `ResponseFacade`를 통해 서블릿 표준 객체 형태로 노출할 준비를 한다.

이번 편에서는 의도적으로 여기서 멈춘다.  
왜냐하면 그 이후부터는 이야기가 달라지기 때문이다.

- 어떤 필터들이 먼저 동작하는지
- 필터 체인이 어떻게 연결되는지
- 어떤 요청이 `DispatcherServlet`으로 들어가는지
- 그리고 `DispatcherServlet` 안에서 어떤 핸들러가 선택되는지

이런 흐름은 톰캣 앞단의 HTTP 처리와는 다른 층위의 이야기다.  
그래서 다음 편에서는 여기서부터 이어서, **필터 체인과** **`DispatcherServlet`** **진입 전후의 흐름**을 중심으로 다룰 예정이다.

---

## **정리**

이번 편은 톰캣 전체를 소개하려는 글이 아니라,  
**브라우저가 보낸 HTTP 요청이 스프링 MVC에 도달하기 전에 어떤 과정을 거치는지**를 앞단 기준으로 정리한 글이다.

핵심만 다시 정리하면 다음과 같다.

- 브라우저가 보낸 요청은 처음부터 자바 객체가 아니라 **네트워크 바이트 스트림**이다.
- **Coyote**는 이 바이트를 읽고 request line, header, body를 해석하는 HTTP 처리 계층이다.
- `org.apache.catalina.connector.CoyoteAdapter`는 Coyote와 Catalina 사이의 연결 지점이다.
- **Catalina**는 해석된 요청을 내부 `Request`, `Response` 객체로 관리한다.
- 애플리케이션이 사용하는 `HttpServletRequest`, `HttpServletResponse`는 `RequestFacade`, `ResponseFacade`를 통해 노출되는 **서블릿 표준 객체**다.

즉 스프링 MVC는 요청 처리의 중심이긴 하지만,  
그보다 먼저 요청을 읽고 파싱하고 표준 요청/응답 객체로 준비하는 작업은 이미 톰캣 내부에서 끝나고 있다.

다음 편에서는 이렇게 준비된 요청이 실제로 어떤 필터들을 통과하고, 어디서 `DispatcherServlet`으로 연결되는지를 이어서 정리해보려고 한다.