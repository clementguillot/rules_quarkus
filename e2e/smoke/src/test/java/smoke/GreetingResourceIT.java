package smoke;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
class GreetingResourceIT extends GreetingResourceTest {
  // Execute the same endpoint test against the packaged Fast JAR.
}
