/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.rest.v2.protocol;

import com.fasterxml.jackson.core.JsonParseException;
import com.microsoft.rest.v2.Base64Url;
import com.microsoft.rest.v2.DateTimeRfc1123;
import com.microsoft.rest.v2.RestException;
import com.microsoft.rest.v2.RestResponse;
import com.microsoft.rest.v2.SwaggerMethodParser;
import com.microsoft.rest.v2.UnixTime;
import com.microsoft.rest.v2.annotations.HeaderCollection;
import com.microsoft.rest.v2.http.HttpHeader;
import com.microsoft.rest.v2.http.HttpHeaders;
import com.microsoft.rest.v2.http.HttpMethod;
import com.microsoft.rest.v2.http.HttpResponse;
import com.microsoft.rest.v2.util.FluxUtil;
import com.microsoft.rest.v2.util.TypeUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deserializes an {@link HttpResponse}.
 */
public final class HttpResponseDecoder {
    private final SwaggerMethodParser methodParser;
    private final SerializerAdapter<?> serializer;

    /**
     * Creates an HttpResponseDecoder.
     * @param methodParser metadata about the Swagger method used for decoding.
     * @param serializer the serializer
     */
    public HttpResponseDecoder(SwaggerMethodParser methodParser, SerializerAdapter<?> serializer) {
        this.methodParser = methodParser;
        this.serializer = serializer;
    }

    /**
     * Asynchronously decodes an {@link HttpResponse}, deserializing into a response or error value.
     * @param response the {@link HttpResponse}
     * @return A {@link Mono} containing either the decoded HttpResponse or an error
     */
    public Mono<HttpResponse> decode(final HttpResponse response) {
        response.withIsDecoded(true);

        final Object deserializedHeaders;
        try {
            deserializedHeaders = deserializeHeaders(response.headers());
        } catch (IOException e) {
            return Mono.error(new RestException("HTTP response has malformed headers", response, e));
        }

        final Type returnValueWireType = methodParser.returnValueWireType();

        final Type entityType = getEntityType();

        boolean isSerializableBody = methodParser.httpMethod() != HttpMethod.HEAD
            && !FluxUtil.isFluxByteBuffer(entityType)
            && !(TypeUtil.isTypeOrSubTypeOf(entityType, Mono.class) && TypeUtil.isTypeOrSubTypeOf(TypeUtil.getTypeArgument(entityType), Void.class))
            && !TypeUtil.isTypeOrSubTypeOf(entityType, byte[].class)
            && !TypeUtil.isTypeOrSubTypeOf(entityType, Void.TYPE) && !TypeUtil.isTypeOrSubTypeOf(entityType, Void.class);

        int[] expectedStatuses = methodParser.expectedStatusCodes();
        boolean isErrorStatus = true;
        if (expectedStatuses != null) {
            for (int expectedStatus : expectedStatuses) {
                if (expectedStatus == response.statusCode()) {
                    isErrorStatus = false;
                    break;
                }
            }
        } else {
            isErrorStatus = response.statusCode() / 100 != 2;
        }

        Mono<HttpResponse> result;
        if (isErrorStatus) {
            final HttpResponse bufferedResponse = response.buffer();
            result = bufferedResponse.bodyAsString()
                    .map(bodyString -> {
                        bufferedResponse.withDeserializedHeaders(deserializedHeaders);
                        Object body = null;
                        try {
                            body = deserializeBody(bodyString, methodParser.exceptionBodyType(), null, SerializerEncoding.fromHeaders(response.headers()));
                        } catch (IOException ignored) {
                            // This translates in RestProxy as a RestException with no deserialized body.
                            // The response content will still be accessible via the .response() member.
                        }
                        return bufferedResponse.withDeserializedBody(body);
                    })
                    // If service response does not have a body then netty-reactor will not emit body i.e. no 'onNext' will be invoked when
                    // subscribing to such httpResponse.body(), hence the above 'map' op won't emit which result in downstream subscriber
                    // terminal complete event, this is incorrect.  We still want to handover the response to subscriber hence the following
                    // 'switchIfEmpty'.
                    //
                    .switchIfEmpty(Mono.defer(() -> Mono.just(bufferedResponse)));
            //
        } else if (isSerializableBody) {
            final HttpResponse bufferedResponse = response.buffer();
            result = bufferedResponse.bodyAsString().map(bodyString -> {
                try {
                    Object body = deserializeBody(bodyString, getEntityType(), returnValueWireType, SerializerEncoding.fromHeaders(response.headers()));
                    return bufferedResponse
                            .withDeserializedHeaders(deserializedHeaders)
                            .withDeserializedBody(body);
                } catch (JsonParseException e) {
                    throw new RestException("HTTP response has a malformed body", response, e);
                } catch (IOException e) {
                    throw new RestException("Deserialization Failed", response, e);
                }
            });
        } else {
            result = Mono.just(response.withDeserializedHeaders(deserializedHeaders));
        }

        return result;
    }

    private Object deserializeBody(String value, Type resultType, Type wireType, SerializerEncoding encoding) throws IOException {
        Object result;

        if (wireType == null) {
            result = serializer.deserialize(value, resultType, encoding);
        }
        else {
            final Type wireResponseType = constructWireResponseType(resultType, wireType);
            final Object wireResponse = serializer.deserialize(value, wireResponseType, encoding);
            result = convertToResultType(wireResponse, resultType, wireType);
        }

        return result;
    }

    private Type constructWireResponseType(Type resultType, Type wireType) {
        Type wireResponseType = resultType;

        if (resultType == byte[].class) {
            if (wireType == Base64Url.class) {
                wireResponseType = Base64Url.class;
            }
        }
        else if (resultType == OffsetDateTime.class) {
            if (wireType == DateTimeRfc1123.class) {
                wireResponseType = DateTimeRfc1123.class;
            }
            else if (wireType == UnixTime.class) {
                wireResponseType = UnixTime.class;
            }
        }
        else {
            if (TypeUtil.isTypeOrSubTypeOf(resultType, List.class)) {
                final Type resultElementType = TypeUtil.getTypeArgument(resultType);
                final Type wireResponseElementType = constructWireResponseType(resultElementType, wireType);

                wireResponseType = TypeUtil.createParameterizedType(
                        (Class<?>) ((ParameterizedType) resultType).getRawType(), wireResponseElementType);
            }
            else if (TypeUtil.isTypeOrSubTypeOf(resultType, Map.class) || TypeUtil.isTypeOrSubTypeOf(resultType, RestResponse.class)) {
                Type[] typeArguments = TypeUtil.getTypeArguments(resultType);
                final Type resultValueType = typeArguments[1];
                final Type wireResponseValueType = constructWireResponseType(resultValueType, wireType);

                wireResponseType = TypeUtil.createParameterizedType(
                        (Class<?>) ((ParameterizedType) resultType).getRawType(), typeArguments[0], wireResponseValueType);
            }
        }
        return wireResponseType;
    }

    private Object convertToResultType(Object wireResponse, Type resultType, Type wireType) {
        Object result = wireResponse;

        if (wireResponse != null) {
            if (resultType == byte[].class) {
                if (wireType == Base64Url.class) {
                    result = ((Base64Url) wireResponse).decodedBytes();
                }
            } else if (resultType == OffsetDateTime.class) {
                if (wireType == DateTimeRfc1123.class) {
                    result = ((DateTimeRfc1123) wireResponse).dateTime();
                } else if (wireType == UnixTime.class) {
                    result = ((UnixTime) wireResponse).dateTime();
                }
            } else {
                if (TypeUtil.isTypeOrSubTypeOf(resultType, List.class)) {
                    final Type resultElementType = TypeUtil.getTypeArgument(resultType);

                    final List<Object> wireResponseList = (List<Object>) wireResponse;

                    final int wireResponseListSize = wireResponseList.size();
                    for (int i = 0; i < wireResponseListSize; ++i) {
                        final Object wireResponseElement = wireResponseList.get(i);
                        final Object resultElement = convertToResultType(wireResponseElement, resultElementType, wireType);
                        if (wireResponseElement != resultElement) {
                            wireResponseList.set(i, resultElement);
                        }
                    }

                    result = wireResponseList;
                }
                else if (TypeUtil.isTypeOrSubTypeOf(resultType, Map.class)) {
                    final Type resultValueType = TypeUtil.getTypeArguments(resultType)[1];

                    final Map<String, Object> wireResponseMap = (Map<String, Object>) wireResponse;

                    final Set<String> wireResponseKeys = wireResponseMap.keySet();
                    for (String wireResponseKey : wireResponseKeys) {
                        final Object wireResponseValue = wireResponseMap.get(wireResponseKey);
                        final Object resultValue = convertToResultType(wireResponseValue, resultValueType, wireType);
                        if (wireResponseValue != resultValue) {
                            wireResponseMap.put(wireResponseKey, resultValue);
                        }
                    }
                } else if (TypeUtil.isTypeOrSubTypeOf(resultType, RestResponse.class)) {
                    RestResponse<?, ?> restResponse = (RestResponse<?, ?>) wireResponse;
                    Object wireResponseBody = restResponse.body();

                    // FIXME: RestProxy is always in charge of creating RestResponse--so this doesn't seem right
                    Object resultBody = convertToResultType(wireResponseBody, TypeUtil.getTypeArguments(resultType)[1], wireType);
                    if (wireResponseBody != resultBody) {
                        result = new RestResponse<>(restResponse.request(), restResponse.statusCode(), restResponse.headers(), restResponse.rawHeaders(), resultBody);
                    } else {
                        result = restResponse;
                    }
                }
            }
        }

        return result;
    }

    private Type getEntityType() {
        Type token = methodParser.returnType();

        if (TypeUtil.isTypeOrSubTypeOf(token, Mono.class)) {
            token = TypeUtil.getTypeArgument(token);
        } else if (TypeUtil.isTypeOrSubTypeOf(token, Flux.class)) {
            Type t = TypeUtil.getTypeArgument(token);
            try {
                if (TypeUtil.isTypeOrSubTypeOf(t, Class.forName("com.microsoft.azure.v2.OperationStatus"))) {
                    token = t;
                }
            } catch (ClassNotFoundException ignored) { }
        }

        if (TypeUtil.isTypeOrSubTypeOf(token, RestResponse.class)) {
            token = TypeUtil.getSuperType(token, RestResponse.class);
            token = TypeUtil.getTypeArguments(token)[1];
        }

        // TODO: unwrap OperationStatus a different way?
        try {
            if (TypeUtil.isTypeOrSubTypeOf(token, Class.forName("com.microsoft.azure.v2.OperationStatus"))) {
                token = TypeUtil.getTypeArgument(token);
            }
        } catch (Exception ignored) { }

        return token;
    }

    private Type getHeadersType() {
        Type token = methodParser.returnType();
        Type headersType = null;

        if (TypeUtil.isTypeOrSubTypeOf(token, Mono.class)) {
            token = TypeUtil.getTypeArgument(token);
        }

        if (TypeUtil.isTypeOrSubTypeOf(token, RestResponse.class)) {
            headersType = TypeUtil.getTypeArguments(TypeUtil.getSuperType(token, RestResponse.class))[0];
        }

        return headersType;
    }

    private Object deserializeHeaders(HttpHeaders headers) throws IOException {
        final Type deserializedHeadersType = getHeadersType();
        if (deserializedHeadersType == null) {
            return null;
        } else {
            final String headersJsonString = serializer.serialize(headers, SerializerEncoding.JSON);
            Object deserializedHeaders = serializer.deserialize(headersJsonString, deserializedHeadersType, SerializerEncoding.JSON);

            final Class<?> deserializedHeadersClass = TypeUtil.getRawClass(deserializedHeadersType);
            final Field[] declaredFields = deserializedHeadersClass.getDeclaredFields();
            for (final Field declaredField : declaredFields) {
                if (declaredField.isAnnotationPresent(HeaderCollection.class)) {
                    final Type declaredFieldType = declaredField.getGenericType();
                    if (TypeUtil.isTypeOrSubTypeOf(declaredField.getType(), Map.class)) {
                        final Type[] mapTypeArguments = TypeUtil.getTypeArguments(declaredFieldType);
                        if (mapTypeArguments.length == 2 && mapTypeArguments[0] == String.class && mapTypeArguments[1] == String.class) {
                            final HeaderCollection headerCollectionAnnotation = declaredField.getAnnotation(HeaderCollection.class);
                            final String headerCollectionPrefix = headerCollectionAnnotation.value().toLowerCase();
                            final int headerCollectionPrefixLength = headerCollectionPrefix.length();
                            if (headerCollectionPrefixLength > 0) {
                                final Map<String, String> headerCollection = new HashMap<>();
                                for (final HttpHeader header : headers) {
                                    final String headerName = header.name();
                                    if (headerName.toLowerCase().startsWith(headerCollectionPrefix)) {
                                        headerCollection.put(headerName.substring(headerCollectionPrefixLength), header.value());
                                    }
                                }

                                final boolean declaredFieldAccessibleBackup = declaredField.isAccessible();
                                try {
                                    if (!declaredFieldAccessibleBackup) {
                                        declaredField.setAccessible(true);
                                    }
                                    declaredField.set(deserializedHeaders, headerCollection);
                                } catch (IllegalAccessException ignored) {
                                } finally {
                                    if (!declaredFieldAccessibleBackup) {
                                        declaredField.setAccessible(declaredFieldAccessibleBackup);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return deserializedHeaders;
        }
    }
}
