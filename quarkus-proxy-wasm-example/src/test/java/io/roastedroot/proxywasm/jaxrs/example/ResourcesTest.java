package io.roastedroot.proxywasm.jaxrs.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ResourcesTest {

    @Test
    public void reverse() throws InterruptedException {

        given().when()
                .get("/test")
                .then()
                .statusCode(200)
                .header("x-response-counter", "1")
                .body(equalTo("Hello World"));

        given().when()
                .get("/test")
                .then()
                .statusCode(200)
                .header("x-response-counter", "2")
                .body(equalTo("Hello World"));
    }
}
