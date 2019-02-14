package com.microsoft.rest.v3.http;

import com.microsoft.rest.v3.policy.RequestIdPolicy;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.Assert.*;

public class HttpPipelineTests {
    @Test
    public void withNoRequestPolicies() throws MalformedURLException {
        final HttpMethod expectedHttpMethod = HttpMethod.GET;
        final URL expectedUrl = new URL("http://my.site.com");
        final HttpPipeline httpPipeline = new HttpPipelineBuilder().withHttpClient(new HttpClient() {
            @Override
            public Mono<HttpResponse> sendRequestAsync(HttpRequest request) {
                assertEquals(0, request.headers().size());
                assertEquals(expectedHttpMethod, request.httpMethod());
                assertEquals(expectedUrl, request.url());
                return Mono.<HttpResponse>just(new MockHttpResponse(200));
            }
        }).build();

        final HttpResponse response = httpPipeline.sendRequest(new HttpRequest("MOCK_CALLER_METHOD", expectedHttpMethod, expectedUrl, null)).block();
        assertNotNull(response);
        assertEquals(200, response.statusCode());
    }

    @Test
    public void withUserAgentRequestPolicy() throws MalformedURLException {
        final HttpMethod expectedHttpMethod = HttpMethod.GET;
        final URL expectedUrl = new URL("http://my.site.com/1");
        final String expectedUserAgent = "my-user-agent";
        final HttpClient httpClient = new HttpClient() {
            @Override
            public Mono<HttpResponse> sendRequestAsync(HttpRequest request) {
                assertEquals(1, request.headers().size());
                assertEquals(expectedUserAgent, request.headers().value("User-Agent"));
                assertEquals(expectedHttpMethod, request.httpMethod());
                assertEquals(expectedUrl, request.url());
                return Mono.<HttpResponse>just(new MockHttpResponse(200));
            }
        };
        final HttpPipeline httpPipeline = new HttpPipelineBuilder()
                .withHttpClient(httpClient)
                .withUserAgentPolicy(expectedUserAgent)
                .build();
        final HttpResponse response = httpPipeline.sendRequest(new HttpRequest("MOCK_CALLER_METHOD", expectedHttpMethod, expectedUrl, null)).block();
        assertNotNull(response);
        assertEquals(200, response.statusCode());
    }

    @Test
    public void withRequestIdRequestPolicy() throws MalformedURLException {
        final HttpMethod expectedHttpMethod = HttpMethod.GET;
        final URL expectedUrl = new URL("http://my.site.com/1");
        final HttpPipeline httpPipeline = new HttpPipelineBuilder()
                .withPolicy(new RequestIdPolicy())
                .withHttpClient(
                    new HttpClient() {
                        @Override
                        public Mono<HttpResponse> sendRequestAsync(HttpRequest request) {
                            assertEquals(1, request.headers().size());
                            final String requestId = request.headers().value("x-ms-client-request-id");
                            assertNotNull(requestId);
                            assertFalse(requestId.isEmpty());

                            assertEquals(expectedHttpMethod, request.httpMethod());
                            assertEquals(expectedUrl, request.url());
                            return Mono.<HttpResponse>just(new MockHttpResponse(200));
                        }
                    }).build();
        final HttpResponse response = httpPipeline.sendRequest(new HttpRequest("MOCK_CALLER_METHOD", expectedHttpMethod, expectedUrl, null)).block();
        assertNotNull(response);
        assertEquals(200, response.statusCode());
    }
}
