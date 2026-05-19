package com.example.aotlab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void productEndpointReturnsPreloadedProducts() {
        ResponseEntity<String> response = this.restTemplate.getForEntity("/api/products?limit=3", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("priceLabel");
    }

    @Test
    void runtimeEndpointShowsContainerInitializationSignals() {
        ResponseEntity<String> response = this.restTemplate.getForEntity("/api/runtime", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("beanDefinitionCount", "heapUsedMb");
    }
}
