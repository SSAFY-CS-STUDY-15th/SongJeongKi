# **브라우저의 요청은 스프링에 오기 전에 어디를 지날까**
**Coyote와 Catalina를 기준으로 본 톰캣 앞단의 요청 처리 구조**

스프링 MVC의 요청 처리 흐름을 설명할 때는 보통 `DispatcherServlet`부터 시작한다.  
하지만 실제 요청은 그보다 먼저 톰캣 내부에서 HTTP 파싱과 요청/응답 객체 준비 과정을 거친다.

이번 글에서는 범위를 넓히지 않고, **브라우저가 보낸 HTTP 요청이 어떤 과정을 거쳐 톰캣 내부에서 해석되고, 어떻게 서블릿 표준 요청/응답 객체로 준비되기 시작하는지**까지만 정리해보려 한다.  
즉 이번 편의 관심사는 필터 체인이나 `DispatcherServlet` 내부 동작이 아니라, 그 이전 단계인 **Coyote와 Catalina 앞단**이다.

구조를 단순화하면 이번 글에서 다룰 범위는 아래와 같다.

```
--------------------------------------------------------------------
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
  └─ Coyote 요청/응답을 Catalina 요청/응답과 연결
    ↓
[Catalina]
  ├─ org.apache.catalina.connector.Request / Response 준비
  └─ RequestFacade / ResponseFacade를 통한 서블릿 표준 객체 준비--------------------------------------------------------------------
  ├─ Host / Context / Wrapper 매핑
  ├─ 컨테이너 파이프라인 실행
  ├─ ApplicationFilterChain 생성
  └─ Servlet.service() 호출 준비
    ↓
[FilterChain]
    ↓
[DispatcherServlet]
    ↓
[HandlerMapping]
    ↓
[Controller]ㅁ
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

---
## **1. 브라우저에서의 요청 : 브라우저의 요청은 아직 자바 객체가 아니다**

브라우저는 사용자가 URL을 입력하거나 링크를 클릭하면 HTTP 요청 메시지를 만든다.  
이 요청에는 URL, HTTP Method, Header, Cookie, Body 같은 정보가 포함된다. 하지만 이 시점의 요청은 아직 `HttpServletRequest` 같은 자바 객체가 아니다.  
그냥 네트워크를 통해 전송되는 **바이트 스트림**일 뿐이다.

즉 서버 입장에서는 먼저 해야 할 일이 하나 있다.  
브라우저가 보낸 바이트를 읽고, HTTP 프로토콜에 따라 request line, header, body 처리 정보로 파싱해야 하는 것이다.
이 역할을 톰캣 내부에서 맡는 쪽이 바로 **Coyote**다.

#### 참고 : 브라우저로부터 들어오는 byte 스트림 예시
~~~
GET /hello?name=kim HTTP/1.1\r\n        <-- Request Line
Host: localhost:8080\r\n
Cookie: JSESSIONID=abc\r\n
\r\n
~~~
---

## **2. Coyote: HTTP 요청을 처음 읽는 계층**

톰캣 내부를 단순화해서 보면, **Coyote는 HTTP 프로토콜 처리 계층**이다.
브라우저가 보낸 바이트 스트림을 읽어 request line과 header를 먼저 파싱하고, body는 이후 읽을 수 있도록 처리 방식과 입력 필터를 준비한다.

이 단계에서 핵심이 되는 클래스는 다음과 같다.
- [org.apache.coyote.http11.Http11NioProtocol](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/http11/Http11NioProtocol.java)
- [org.apache.tomcat.util.net.NioEndpoint](./assets/tomcat-embed-core-11.0.21-sources/org/apache/tomcat/util/net/NioEndpoint.java)
- [org.apache.coyote.http11.Http11Processor](./assets/tomcat-embed-core-11.0.21-sources/org/apache/coyote/http11/Http11Processor.java)


각 클래스의 역할을 짧게 정리하면 이렇다.

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
>NIO는 **소켓 채널을 non-blocking 모드로 설정하고, `Selector`를 통해 여러 채널의 I/O 가능 상태를 감시하는 I/O 모델**이다.  
>기존 blocking I/O에서는 스레드가 `read()`를 호출했을 때 데이터가 도착할 때까지 호출이 반환되지 않을 수 있다.  
>반면 NIO의 non-blocking 채널에서는 `read()`를 호출해도 읽을 데이터가 없으면 즉시 반환된다.  
>Tomcat의 NIO Connector는 이 특성을 이용해 다음 방식으로 동작한다. 
>1. 서버는 클라이언트 소켓을 `SocketChannel`로 관리한다.  
>2. 각 채널은 non-blocking 모드로 설정된다.  
>3. `Selector`에 채널을 등록하고, 관심 이벤트를 지정한다.  
>    - `OP_ACCEPT`  
>    - `OP_READ`  
>    - `OP_WRITE`  
>4. `Selector.select()`는 I/O 수행이 가능한 채널들을 감지한다.  
>5. 선택된 채널만 `SocketProcessor`로 전달되어 실제 읽기/쓰기 처리가 수행된다.    
>
>즉 NIO는 스레드가 각 연결마다 `read()`에서 대기하는 구조가 아니라,    
>`Selector`가 여러 소켓 채널의 I/O readiness event를 감시하고, 준비된 채널만 작업 스레드에서 처리하게 하는 구조다.

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

> `NioEndpoint`는 Tomcat Connector에서 NIO 기반 TCP 네트워크 처리를 담당하는 엔드포인트 구현체다. 서버 소켓을 바인딩하고, 클라이언트 연결을 수락하며, Selector 기반으로 소켓 I/O 이벤트를 감시한 뒤 처리 가능한 소켓을 상위 프로토콜 처리 계층으로 전달한다.

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


## **3. Adapter: HTTP 처리 결과를 서블릿 컨테이너 흐름으로 넘기는 객체**

Coyote가 HTTP 요청을 읽고 해석했다고 해서, 그 요청이 곧바로 스프링 MVC로 들어가는 것은 아니다.  
이제 그 결과를 **서블릿 컨테이너 실행 흐름**으로 넘겨야 한다.

이 경계를 담당하는 핵심 클래스가 다음이다.

- [org.apache.catalina.connector.CoyoteAdapter](./assets/tomcat-embed-core-11.0.21-sources/org/apache/catalina/connector/CoyoteAdapter.java)

이 클래스는 이름 그대로 **Coyote와 Catalina 사이를 연결하는 어댑터** 역할을 한다.  
앞단의 Coyote가 HTTP 프로토콜을 처리했다면, 이제부터는 Catalina가 그 결과를 웹 애플리케이션 처리 흐름으로 이어받는다.

여기서 Service() 메서드를 살펴보면 Coyote 쪽 `Request/Response`(org.apache.coyote 패키지)는 HTTP 메시지 자체를 처리하기 위한 내부 객체이고, Catalina 쪽 `Request/Response`(org.apache.catalina.connector 패키지)는 이를 감싸 `HttpServletRequest` / `HttpServletResponse` interface를 구현한 구현체로써 서블릿 컨테이너에서 사용할 수 있게 만든 객체다.

직접 코드를 까보면, Coyote 패키지의 Request/Response는 요청과 관련된 정보를 String 객체나 서블릿 API용 객체로 바로 들고 있지 않다. 대신 MessageBytes처럼 소켓에서 들어온 byte 데이터를 효율적으로 다루기 위한 내부 버퍼 객체를 사용한다. 이는 불필요한 문자열 변환과 객체 생성을 줄이고, 필요한 시점에만 데이터를 변환하기 위한 구조다.

반면 Catalina 패키지의 Request/Response는 내부에 coyote.Request 객체를 final로 들고 있다. 그리고 이 객체의 데이터를 기반으로 파라미터 파싱, 쿠키 변환, 세션 처리, attribute 관리, 서블릿 매핑 정보, HttpServletRequest API 같은 웹 애플리케이션 실행에 필요한 기능을 제공한다.

학습하면서 계속 들었던 의문은 왜 굳이 Adapter를 두는가였다.

catalina.connector.Request 내부에는 어차피 coyote.Request가 들어 있고, 두 객체 모두 같은 요청 데이터를 기반으로 동작한다. 그래서 처음에는 coyote.Request가 직접 catalina.Request를 호출하는 구조로 만들어도 되지 않을까 생각했다.

하지만 CoyoteAdapter를 두는 이유는 Coyote가 Catalina 내부 구조에 직접 의존하지 않게 하기 위해서다.

Coyote는 HTTP 파싱, 소켓 처리, 저수준 요청/응답 객체 관리를 담당한다. 반면 Catalina는 서블릿 컨테이너 실행 흐름, 즉 Context 탐색, 서블릿 매핑, 필터 체인, 파이프라인 실행을 담당한다.

따라서 CoyoteAdapter는 단순히 요청을 전달하는 객체가 아니다. 두 계층의 책임을 분리한 상태에서 org.apache.coyote.Request/Response를 org.apache.catalina.connector.Request/Response와 연결하고, 이후 요청을 Catalina의 컨테이너 파이프라인으로 넘기는 경계 객체다.

마지막으로 각자의 역할을 정리해 보자.
- **Coyote**: HTTP 요청을 읽고 해석
- **CoyoteAdapter**: 해석된 요청을 Catalina 쪽 흐름으로 전달
- **Catalina**: 요청/응답 객체를 구성하고 서블릿 컨테이너 흐름에 올림

볼만한 메서드
* service(req, res)
	- Coyote가 준비한 요청/응답을 받아 Catalina 쪽 `Request` / `Response`와 연결하고, 이를 컨테이너가 처리할 수 있는 상태로 만든 뒤 컨테이너 파이프라인으로 넘겨 실제 서블릿 처리 흐름이 시작되도록 한다.

---

## **4. Catalina: Request / Response 객체가 준비되는 지점**

Catalina는 톰캣의 서블릿 컨테이너 계층이다.  
이번 글에서 Catalina 전체를 다 다루지 않고, **요청과 응답이 어떤 객체로 준비되기 시작하는지**만 보고 넘어가려고 한다.

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

RequestFacade는 내부 Request를 단순히 감싸는 래퍼에 가깝다. 코드를 보면 내부에 org.apache.catalina.connector.Request를 가지고 있고, 대부분의 메서드는 이 객체에 그대로 위임한다. 다만 외부 애플리케이션 코드에 노출되어야 하는 HttpServletRequest 표준 메서드만 제공하고, recycle()처럼 컨테이너 내부에서만 사용되어야 하는 메서드는 노출하지 않는다.

즉 Facade의 핵심은 내부 객체를 감싸되, 외부에 허용된 기능만 보여주는 것이다.

애플리케이션 코드는 `Facade`를 통해 접근하기 때문에 WAS가 굳이 톰캣이 아니더라도 애플리케이션 코드는 범용적으로 서버를 갈아낄 수 있게된다.
자바의 추상화의 가장 큰 장점을 그대로 사용한 좋은 예시로 보였다.
(사실 정확히 하자면 Facade의 1차 목적은 이식성이 아니라, 톰캣 내부 Request 객체를 외부 코드로부터 보호하는 것이었다. 내부 Request에는 recycle()과 같이 컨테이너가 직접 관리해야 하는 상태와 메서드가 있기 때문이었다.
이식성은 이 결과 구조에서 따라오는 좋은 효과였다고 한다.)

### **`org.apache.catalina.connector.ResponseFacade`**

`HttpServletResponse`를 구현하는 외부 노출용 래퍼다.  
응답 역시 내부 객체를 직접 노출하지 않고, 표준 인터페이스 형태로 감싸 제공한다.

RequestFacade와 동일하다.

---

## **5. 정리**
이번 편은 톰캣 전체를 소개하려는 글이 아니라, 브라우저가 보낸 HTTP 요청이 스프링 MVC에 도달하기 전에 어떤 과정을 거치는지를 앞단 기준으로 정리한 글이다.

핵심 흐름은 다음과 같다.

1. 브라우저는 HTTP 요청 메시지를 전송한다.
2. 이 요청은 처음부터 자바 객체가 아니라 네트워크 바이트 스트림이다.
3. Coyote는 이 바이트를 읽고 request line, header, body를 해석한다.
4. CoyoteAdapter는 Coyote와 Catalina 사이에서 요청/응답 객체를 연결한다.
5. Catalina는 해석된 요청을 내부 Request, Response 객체로 관리한다.
6. 애플리케이션이 사용하는 HttpServletRequest, HttpServletResponse는 RequestFacade, ResponseFacade를 통해 노출된다.

즉 여기까지 오면 요청은 브라우저가 보낸 단순한 바이트 스트림 상태를 벗어나, 톰캣 내부에서 HTTP 요청으로 해석되었고, Catalina를 통해 서블릿 표준 요청/응답 객체로 준비되기 시작한 상태가 된다.

스프링 MVC는 요청 처리의 중심이지만, 그보다 먼저 요청을 읽고 파싱하고 표준 요청/응답 객체로 준비하는 작업은 이미 톰캣 내부에서 진행된다.

이번 편은 여기서 멈춘다.(살려주게..)

이 이후부터는 Catalina가 준비된 요청을 실제 서블릿 실행 흐름으로 어떻게 연결하는지의 이야기다.
- Catalina는 요청 URL을 기준으로 어떤 `Host`, `Context`, `Wrapper`를 선택하는지
- `Wrapper`가 실제 서블릿을 어떻게 관리하고 호출하는지
- 컨테이너 파이프라인과 `Valve`는 어떤 순서로 동작하는지
- `ApplicationFilterChain`은 어떻게 만들어지는지
- 최종적으로 어떤 지점에서 서블릿의 `service()`가 호출되는지

이 흐름을 이해하면 `DispatcherServlet`이 어디서 등장하는지도 자연스럽게 보일 것 같다.

다음 편에서는 이렇게 준비된 요청이 Catalina 내부에서 어떤 컨테이너 구조를 따라 이동하고, 어떻게 필터 체인과 서블릿 실행으로 이어지는지를 정리해보려고 한다.